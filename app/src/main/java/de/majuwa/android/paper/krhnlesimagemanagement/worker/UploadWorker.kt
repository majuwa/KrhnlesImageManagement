package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.util.isAllowedPhotoUri
import kotlinx.coroutines.flow.first
import org.json.JSONException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class UploadWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val credentialRepository: CredentialRepository,
    private val clientFactory: (WebDavConfig) -> WebDavClient,
    private val uploadedPhotos: UploadedPhotosRepositoryContract = UploadedPhotosStore(context.applicationContext),
    private val uploadHistory: UploadHistoryRepository = UploadHistoryStore(context.applicationContext),
    private val isActiveNetworkMetered: () -> Boolean = { isActiveNetworkMetered(context.applicationContext) },
) : CoroutineWorker(context, params) {
    /** Used by WorkManager's default factory. */
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        CredentialStore(context.applicationContext),
        ::WebDavClient,
    )

    private data class UploadFilesResult(
        val uploaded: Int,
        val failedIndices: List<Int>,
        val uploadedIds: Set<Long>,
    )

    companion object {
        const val KEY_QUEUE_FILE = "queue_file"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_FAILED_COUNT = "failed_count"

        private fun isActiveNetworkMetered(context: Context): Boolean =
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

        /** Foreground / progress notification only; result notifications use [RESULT_NOTIFICATION_ID]. */
        const val NOTIFICATION_ID = 1001

        /**
         * Id of every result notification (success, partial, failure). Batches are told apart by the
         * notification *tag* (the input queue file name), so ids never collide and never equal
         * [NOTIFICATION_ID].
         */
        const val RESULT_NOTIFICATION_ID = 1002
        val NOTIFICATION_ICON = R.drawable.ic_notification_upload
        private const val CHANNEL_ID = "upload_channel"
        private const val REQUEST_CODE_RETRY = 2001
        private const val TAG = "UploadWorker"
    }

    override suspend fun doWork(): Result {
        val queueFile =
            validQueueFile(applicationContext, inputData.getString(KEY_QUEUE_FILE))
                ?: return Result.failure()
        val queue =
            parseQueue(queueFile) ?: run {
                queueFile.delete()
                return Result.failure()
            }

        // The Wi-Fi-only setting may have been switched on after this work was enqueued (its
        // constraint is fixed at enqueue time). Back off via WorkManager until an unmetered network
        // is active; the queue file stays untouched meanwhile.
        if (readWifiOnly(credentialRepository.wifiOnly) && isActiveNetworkMetered()) {
            return Result.retry()
        }

        val config = credentialRepository.webDavConfig.first()

        if (!config.isValid) {
            queueFile.delete()
            createNotificationChannel()
            showFailureNotification(
                applicationContext.getString(
                    R.string.notification_create_folder_failed,
                    folderCreationErrorMessage(applicationContext.resources, FolderCreationError.NotConfigured),
                ),
                queueFile.name,
            )
            return Result.failure()
        }

        return executeUpload(
            config = config,
            queue = queue,
            notificationTag = queueFile.name,
        ).also { queueFile.delete() }
    }

    private fun parseQueue(queueFile: File): UploadQueue? =
        try {
            parseUploadQueue(queueFile.readText()).also {
                if (it == null) Log.w(TAG, "Malformed queue file: inconsistent folder format")
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed queue file: ${e.javaClass.simpleName}")
            null
        } catch (e: IOException) {
            Log.w(TAG, "Unreadable queue file: ${e.javaClass.simpleName}")
            null
        }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        return buildForegroundInfo(0, 0)
    }

    private suspend fun executeUpload(
        config: WebDavConfig,
        queue: UploadQueue,
        notificationTag: String,
    ): Result {
        val total = queue.entries.size
        val occasionName = queue.historyLabel
        val remoteFolderPaths = List(total) { "${config.uploadPathPrefix}${queue.folderOf(it)}" }
        val client = clientFactory(config)

        createNotificationChannel()
        setForeground(buildForegroundInfo(0, total))

        val folderError = createRemoteFolders(client, queue, config.uploadPathPrefix, remoteFolderPaths)
        if (folderError != null) {
            recordHistory(
                occasionName = occasionName,
                photoCount = total,
                failedCount = total,
            )
            showFailureNotification(
                applicationContext.getString(
                    R.string.notification_create_folder_failed,
                    folderCreationErrorMessage(applicationContext.resources, folderError),
                ),
                notificationTag,
            )
            return Result.failure()
        }

        val result = uploadFiles(client, queue.entries, remoteFolderPaths)
        val failed = result.failedIndices.size
        recordHistory(
            occasionName = occasionName,
            photoCount = total,
            failedCount = failed,
        )

        val retryQueueFilePath =
            if (result.failedIndices.isNotEmpty()) {
                writeRetryQueueFile(queue, result.failedIndices)?.absolutePath
            } else {
                null
            }

        showCompletionNotification(result.uploaded, failed, retryQueueFilePath, notificationTag)

        if (result.uploadedIds.isNotEmpty()) {
            uploadedPhotos.markAsUploaded(result.uploadedIds)
        }

        return Result.success(
            workDataOf(
                KEY_UPLOADED_COUNT to result.uploaded,
                KEY_FAILED_COUNT to failed,
            ),
        )
    }

    private suspend fun recordHistory(
        occasionName: String,
        photoCount: Int,
        failedCount: Int,
    ) {
        val now = System.currentTimeMillis()
        runCatching {
            uploadHistory.addEntry(
                UploadHistoryEntry(
                    id = now,
                    occasionName = occasionName,
                    timestampMillis = now,
                    photoCount = photoCount,
                    failedCount = failedCount,
                ),
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to persist upload history entry: ${throwable.javaClass.simpleName}")
        }
    }

    /** Uploads `entries[i]` into `remoteFolderPaths[i]`; one progress over all photos. */
    private suspend fun uploadFiles(
        client: WebDavClient,
        entries: List<UploadQueueEntry>,
        remoteFolderPaths: List<String>,
    ): UploadFilesResult {
        var uploaded = 0
        val failedIndices = mutableListOf<Int>()
        val uploadedIds = mutableSetOf<Long>()

        for (i in entries.indices) {
            val entry = entries[i]
            val succeeded = uploadPhoto(client, remoteFolderPaths[i], entry.uri, entry.mimeType, entry.displayName)
            if (succeeded) {
                uploaded++
                // -1 marks entries that pre-date photo ids: uploaded, but never marked.
                val photoId = entry.id
                if (photoId >= 0L) {
                    uploadedIds.add(photoId)
                }
            } else {
                failedIndices.add(i)
            }

            val progress = i + 1
            setProgress(
                workDataOf(
                    KEY_PROGRESS to progress,
                    KEY_TOTAL to entries.size,
                ),
            )
            setForeground(buildForegroundInfo(progress, entries.size))
        }

        return UploadFilesResult(uploaded, failedIndices, uploadedIds)
    }

    private suspend fun uploadPhoto(
        client: WebDavClient,
        remoteFolderPath: String,
        uriString: String,
        mimeType: String,
        fileName: String,
    ): Boolean {
        val inputStream = openPhoto(uriString.toUri()) ?: return false
        return inputStream.use { client.uploadFile(remoteFolderPath, fileName, mimeType, it) }.isSuccess
    }

    /** Opens a photo; an unreadable one (revoked grant, deleted file) is a failed photo, not a crash. */
    private fun openPhoto(uri: Uri): InputStream? {
        if (!isAllowedPhotoUri(uri, applicationContext.packageName)) {
            // Defence in depth: only foreign content:// URIs, never file:// or this app's own providers.
            Log.w(TAG, "Photo URI rejected: unsupported scheme or own authority")
            return null
        }
        return try {
            applicationContext.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            logUnreadable(e)
        } catch (e: FileNotFoundException) {
            logUnreadable(e)
        } catch (e: IllegalArgumentException) {
            logUnreadable(e)
        }
    }

    private fun logUnreadable(e: Exception): InputStream? {
        Log.w(TAG, "Photo could not be opened: ${e.javaClass.simpleName}")
        return null
    }

    /** Writes the retry file: only failed entries, each keeping its folder; common folder and label copied. */
    private fun writeRetryQueueFile(
        queue: UploadQueue,
        failedIndices: List<Int>,
    ): File? =
        try {
            val retryFile =
                File(queueFilesDir(applicationContext), "retry_queue_${UUID.randomUUID()}.json")
            retryFile.writeText(queue.toRetryJson(failedIndices).toString())
            retryFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write retry queue file: ${e.javaClass.simpleName}")
            null
        }

    private fun buildForegroundInfo(
        current: Int,
        total: Int,
    ): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            buildProgressNotification(current, total),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private fun uploadNotificationBuilder(): NotificationCompat.Builder =
        NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(IconCompat.createWithResource(applicationContext, NOTIFICATION_ICON))

    internal fun buildProgressNotification(
        current: Int,
        total: Int,
    ): Notification {
        val text =
            if (total == 0) {
                applicationContext.getString(R.string.notification_preparing)
            } else {
                "$current / $total"
            }
        return uploadNotificationBuilder()
            .setContentTitle(applicationContext.getString(R.string.notification_uploading_title))
            .setContentText(text)
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showCompletionNotification(
        uploaded: Int,
        failed: Int,
        retryQueueFilePath: String?,
        notificationTag: String,
    ) {
        notifyIfPermitted(
            buildCompletionNotification(uploaded, failed, retryQueueFilePath, notificationTag),
            notificationTag,
        )
    }

    internal fun buildCompletionNotification(
        uploaded: Int,
        failed: Int,
        retryQueueFilePath: String?,
        notificationTag: String,
    ): Notification {
        val text =
            if (failed == 0) {
                applicationContext.resources.getQuantityString(
                    R.plurals.notification_upload_success,
                    uploaded,
                    uploaded,
                )
            } else {
                applicationContext.resources.getQuantityString(
                    R.plurals.notification_upload_partial,
                    uploaded,
                    uploaded,
                    failed,
                )
            }
        val builder =
            uploadNotificationBuilder()
                .setContentTitle(applicationContext.getString(R.string.notification_upload_complete))
                .setContentText(text)
                .setOngoing(false)

        if (failed > 0 && retryQueueFilePath != null) {
            val retryIntent =
                Intent(applicationContext, RetryUploadReceiver::class.java).apply {
                    putExtra(RetryUploadReceiver.EXTRA_QUEUE_FILE, retryQueueFilePath)
                    putExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_ID, RESULT_NOTIFICATION_ID)
                    putExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_TAG, notificationTag)
                    // Unique data URI: PendingIntent identity ignores extras, so each retry file needs its own.
                    data = "retry://${File(retryQueueFilePath).name}".toUri()
                }
            val retryPendingIntent =
                PendingIntent.getBroadcast(
                    applicationContext,
                    REQUEST_CODE_RETRY,
                    retryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val retryLabel =
                applicationContext.getString(R.string.notification_retry_failed, failed)
            builder.addAction(0, retryLabel, retryPendingIntent)
        }

        return builder.build()
    }

    private fun showFailureNotification(
        message: String,
        notificationTag: String,
    ) {
        notifyIfPermitted(buildFailureNotification(message), notificationTag)
    }

    internal fun buildFailureNotification(message: String): Notification =
        uploadNotificationBuilder()
            .setContentTitle(applicationContext.getString(R.string.notification_upload_failed))
            .setContentText(message)
            .setOngoing(false)
            .build()

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.channel_name_uploads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.channel_desc_uploads)
            }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun notifyIfPermitted(
        notification: Notification,
        notificationTag: String,
    ) {
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat
                .from(applicationContext)
                .notify(notificationTag, RESULT_NOTIFICATION_ID, notification)
        }
    }
}

/** The queue file named by [path] if it is an existing, validated worker queue file, else null. */
private fun validQueueFile(
    context: Context,
    path: String?,
): File? {
    val file = path?.let(::File)
    return when {
        file == null -> {
            null
        }

        !isWorkerQueueFile(context, file) -> {
            Log.w("UploadWorker", "Queue file has an unexpected name or location — ignoring.")
            null
        }

        !file.exists() -> {
            null
        }

        else -> {
            file
        }
    }
}

/**
 * Validates every effective folder of [queue] before any request, then creates each distinct
 * [remoteFolderPaths] entry level by level (each cumulative path at most once, 405 ok). Returns
 * why it failed (never raw exception text), or null on success.
 */
private suspend fun createRemoteFolders(
    client: WebDavClient,
    queue: UploadQueue,
    uploadPathPrefix: String,
    remoteFolderPaths: List<String>,
): FolderCreationError? =
    if (!queue.hasValidFolders()) {
        FolderCreationError.InvalidFolderName
    } else {
        // An empty queue still creates the common folder (or the base folder) as before.
        val paths = remoteFolderPaths.distinct().ifEmpty { listOf("$uploadPathPrefix${queue.folderName.orEmpty()}") }
        client.createDirectories(paths).exceptionOrNull()?.toFolderCreationError()
    }
