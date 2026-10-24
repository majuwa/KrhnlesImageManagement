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
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.toUploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.util.isAllowedPhotoUri
import kotlinx.coroutines.flow.first
import org.json.JSONException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

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

    /**
     * [retainedStaged]: staged names referenced by the retry file written for this run.
     * [keepQueue]: the run was interrupted (foreground service refused); the queue file (with the
     * progress of this run) and its staged copies stay for the retried run.
     */
    private data class UploadOutcome(
        val result: Result,
        val retainedStaged: Set<String> = emptySet(),
        val keepQueue: Boolean = false,
    )

    /**
     * [queue]: the queue with every entry uploaded so far marked (also persisted in the queue file).
     * [interrupted]: the loop stopped early because the foreground service was refused.
     */
    private data class UploadRun(
        val queue: UploadQueue,
        val failures: Set<UploadFailure>,
        val interrupted: Boolean,
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

        /**
         * Runs of one queue (WorkManager's `runAttemptCount + 1`, which also counts Wi-Fi-only
         * back-offs) after which a refused foreground service no longer retries: the run stops with
         * the failure notification and a retry file for the entries not uploaded yet.
         */
        const val MAX_UPLOAD_RUN_ATTEMPTS = 5
    }

    override suspend fun doWork(): Result {
        val queueFile =
            validQueueFile(applicationContext, inputData.getString(KEY_QUEUE_FILE))
                ?: return Result.failure()
        val queue =
            parseQueue(queueFile) ?: run {
                deleteQueueFile(applicationContext, queueFile)
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
            deleteQueueFile(applicationContext, queueFile)
            createNotificationChannel()
            notifyIfPermitted(
                buildFailureNotification(
                    applicationContext.getString(
                        R.string.notification_create_folder_failed,
                        folderCreationErrorMessage(applicationContext.resources, FolderCreationError.NotConfigured),
                    ),
                ),
                queueFile.name,
            )
            return Result.failure()
        }

        val outcome = executeUpload(config = config, queue = queue, queueFile = queueFile)
        // Staged copies stay until the queue is final; those of a new retry file survive. An
        // interrupted run keeps everything for its retry.
        if (!outcome.keepQueue) deleteQueueFile(applicationContext, queueFile, keep = outcome.retainedStaged)
        return outcome.result
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

    /**
     * Uploads the entries of [queue] not uploaded by an earlier run. After each successful entry the
     * queue file is rewritten atomically with an `uploaded` marker and the photo is marked uploaded,
     * so an interrupted run never re-uploads finished entries. Result, history and notification
     * cover the whole batch, including entries uploaded by earlier runs.
     */
    private suspend fun executeUpload(
        config: WebDavConfig,
        queue: UploadQueue,
        queueFile: File,
    ): UploadOutcome {
        val notificationTag = queueFile.name
        val total = queue.entries.size
        val pending = queue.pendingIndices
        val remoteFolderPaths = List(total) { "${config.uploadPathPrefix}${queue.folderOf(it)}" }
        val client = clientFactory(config)

        createNotificationChannel()
        // A refused foreground service (time budget used up, background start) retries the run
        // later; the queue stays.
        if (!setForegroundOrFalse(buildForegroundInfo(total - pending.size, total))) {
            return interrupted(UploadRun(queue, emptySet(), interrupted = true), notificationTag)
        }

        // Nothing left (an earlier run finished every entry): no folder request, only the result.
        if (pending.isNotEmpty() || total == 0) {
            val folderPaths = pending.map { remoteFolderPaths[it] }
            val folderError = createRemoteFolders(client, queue, config.uploadPathPrefix, folderPaths)
            if (folderError != null) {
                recordHistory(occasionName = queue.historyLabel, photoCount = total, failedCount = pending.size)
                val message =
                    applicationContext.getString(
                        R.string.notification_create_folder_failed,
                        folderCreationErrorMessage(applicationContext.resources, folderError),
                    )
                notifyIfPermitted(buildFailureNotification(message), notificationTag)
                return UploadOutcome(Result.failure())
            }
        }

        val run = uploadFiles(client, queue, remoteFolderPaths, queueFile)
        return if (run.interrupted) interrupted(run, notificationTag) else finish(run, notificationTag)
    }

    /**
     * An interrupted run is retried (queue and its progress kept) until [MAX_UPLOAD_RUN_ATTEMPTS]
     * runs are used up; then it stops like a finished run with the remaining entries failed.
     */
    private suspend fun interrupted(
        run: UploadRun,
        notificationTag: String,
    ): UploadOutcome {
        if (runAttemptCount + 1 < MAX_UPLOAD_RUN_ATTEMPTS) return UploadOutcome(Result.retry(), keepQueue = true)
        Log.w(TAG, "Upload interrupted too often; giving up after $MAX_UPLOAD_RUN_ATTEMPTS runs")
        return finish(run, notificationTag, gaveUp = true)
    }

    /** Records and reports the whole batch; entries not uploaded go to a new retry file. */
    private suspend fun finish(
        run: UploadRun,
        notificationTag: String,
        gaveUp: Boolean = false,
    ): UploadOutcome {
        val queue = run.queue
        val total = queue.entries.size
        val failedIndices = queue.pendingIndices
        val failed = failedIndices.size
        val uploaded = total - failed
        recordHistory(occasionName = queue.historyLabel, photoCount = total, failedCount = failed)

        val retryQueueFilePath =
            if (failedIndices.isNotEmpty()) writeRetryQueueFile(queue, failedIndices)?.absolutePath else null
        val retainedStaged =
            if (retryQueueFilePath != null) {
                failedIndices.mapNotNull { queue.entries[it].staged }.toSet()
            } else {
                emptySet()
            }
        val resources = applicationContext.resources
        val reasons =
            listOfNotNull(
                uploadFailureMessage(resources, run.failures),
                resources.getString(R.string.upload_failure_interrupted).takeIf { gaveUp },
            )
        val summary =
            CompletionSummary(uploaded, failed, retryQueueFilePath, joinNotificationTexts(resources, reasons), gaveUp)
        notifyIfPermitted(buildCompletionNotification(summary, notificationTag), notificationTag)

        val data = workDataOf(KEY_UPLOADED_COUNT to uploaded, KEY_FAILED_COUNT to failed)
        return UploadOutcome(if (gaveUp) Result.failure(data) else Result.success(data), retainedStaged)
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

    /**
     * Uploads every pending entry `i` of [initialQueue] into `remoteFolderPaths[i]`; one progress over
     * the whole batch. Each success is persisted in [queueFile] and marked uploaded at once. A refused
     * foreground update interrupts the run, except after the last entry (the batch is complete).
     */
    private suspend fun uploadFiles(
        client: WebDavClient,
        initialQueue: UploadQueue,
        remoteFolderPaths: List<String>,
        queueFile: File,
    ): UploadRun {
        var queue = initialQueue
        val pending = queue.pendingIndices
        val total = queue.entries.size
        val failures = mutableSetOf<UploadFailure>()
        var finished = total - pending.size

        for ((n, i) in pending.withIndex()) {
            val entry = queue.entries[i]
            val failure = uploadPhoto(client, remoteFolderPaths[i], entry)
            if (failure == null) {
                queue = queue.withUploaded(i)
                // A failed rewrite only means a re-run uploads this entry again.
                rewriteQueueFile(queueFile, queue.toProgressJson().toString())
                uploadedPhotos.markUploaded(entry.id)
            } else {
                failures.add(failure)
            }

            finished++
            setProgress(workDataOf(KEY_PROGRESS to finished, KEY_TOTAL to total))
            val foregroundKept = setForegroundOrFalse(buildForegroundInfo(finished, total))
            if (!foregroundKept && n < pending.lastIndex) return UploadRun(queue, failures, interrupted = true)
        }
        return UploadRun(queue, failures, interrupted = false)
    }

    /**
     * Streams one photo or video (never buffered); the size is sent when known. Returns null on
     * success, else why it failed.
     */
    private suspend fun uploadPhoto(
        client: WebDavClient,
        remoteFolderPath: String,
        entry: UploadQueueEntry,
    ): UploadFailure? {
        val staged = entry.staged
        // A staged (shared) photo is read only from its copy: the share grant for its URI is gone.
        val inputStream =
            (if (staged != null) openStagedFile(applicationContext, staged) else openPhoto(entry.uri.toUri()))
                ?: return UploadFailure.Other
        return inputStream
            .use { stream ->
                val length = applicationContext.uploadLengthOf(stream, entry)
                client.uploadFile(remoteFolderPath, entry.displayName, entry.mimeType, stream, length)
            }.exceptionOrNull()
            ?.toUploadFailure()
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

    /** The batch result; [failureReason] (user-facing cause, e.g. HTTP 413 / 507) follows the counts. */
    internal fun buildCompletionNotification(
        summary: CompletionSummary,
        notificationTag: String,
    ): Notification {
        val uploaded = summary.uploaded
        val failed = summary.failed
        val retryQueueFilePath = summary.retryQueueFilePath
        val failureReason = summary.failureReason
        val resources = applicationContext.resources
        val counts =
            if (failed == 0) {
                resources.getQuantityString(R.plurals.notification_upload_success, uploaded, uploaded)
            } else {
                resources.getQuantityString(R.plurals.notification_upload_partial, uploaded, uploaded, failed)
            }
        val text =
            if (failed > 0 && failureReason != null) {
                resources.getString(R.string.notification_text_with_reason, counts, failureReason)
            } else {
                counts
            }
        val title = if (summary.gaveUp) R.string.notification_upload_failed else R.string.notification_upload_complete
        val builder =
            uploadNotificationBuilder()
                .setContentTitle(applicationContext.getString(title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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

/** Marks one uploaded entry; -1 marks entries that pre-date photo ids (uploaded, never marked). */
private suspend fun UploadedPhotosRepositoryContract.markUploaded(photoId: Long) {
    if (photoId < 0L) return
    runCatching { markAsUploaded(setOf(photoId)) }
        .onFailure { if (it is CancellationException) throw it }
        .onFailure { Log.w("UploadWorker", "Uploaded marker not saved: ${it.javaClass.simpleName}") }
}

/**
 * Size of the open [stream] of [entry]: a staged copy only from its own file (never from the
 * entry URI, whose share grant is gone), a provider entry from its descriptor. Any failure is
 * [WebDavClient.UNKNOWN_CONTENT_LENGTH] (chunked transfer).
 */
private fun Context.uploadLengthOf(
    stream: InputStream,
    entry: UploadQueueEntry,
): Long =
    runCatching {
        if (entry.staged != null) {
            (stream as? FileInputStream)?.channel?.size() ?: WebDavClient.UNKNOWN_CONTENT_LENGTH
        } else {
            contentResolver.contentLengthOf(entry.uri.toUri())
        }
    }.getOrDefault(WebDavClient.UNKNOWN_CONTENT_LENGTH)
