package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
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
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "upload_channel"
        private const val REQUEST_CODE_RETRY = 2001
        private const val TAG = "UploadWorker"
    }

    override suspend fun doWork(): Result {
        val queueFilePath = inputData.getString(KEY_QUEUE_FILE) ?: return Result.failure()
        val queueFile = File(queueFilePath)
        val queue = JSONObject(queueFile.readText())
        val folderName = queue.getString("folderName")
        val photosArray = queue.getJSONArray("photos")
        val photoIds = LongArray(photosArray.length()) {
            // -1L is used as a sentinel for entries that pre-date this feature (no "id" field).
            // Such entries are filtered out later in uploadFiles() so they are never marked as uploaded.
            photosArray.getJSONObject(it).optLong("id", -1L)
        }
        val uriStrings = Array(photosArray.length()) { photosArray.getJSONObject(it).getString("uri") }
        val mimeTypes = Array(photosArray.length()) { photosArray.getJSONObject(it).getString("mimeType") }
        val fileNames = Array(photosArray.length()) { photosArray.getJSONObject(it).getString("displayName") }

        val credentialStore = CredentialStore(applicationContext)
        val uploadHistoryStore = UploadHistoryStore(applicationContext)
        val config = credentialStore.webDavConfig.first()

        if (!config.isValid) {
            queueFile.delete()
            return Result.failure(
                workDataOf("error" to "WebDAV not configured. Please check settings."),
            )
        }

        val remoteFolderPath = "${config.uploadPathPrefix}$folderName"
        return executeUpload(
            config = config,
            remoteFolderPath = remoteFolderPath,
            occasionName = folderName,
            photoIds = photoIds,
            uriStrings = uriStrings,
            mimeTypes = mimeTypes,
            fileNames = fileNames,
            uploadHistoryStore = uploadHistoryStore,
        )
            .also { queueFile.delete() }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        return buildForegroundInfo(0, 0)
    }

    private suspend fun executeUpload(
        config: WebDavConfig,
        remoteFolderPath: String,
        occasionName: String,
        photoIds: LongArray,
        uriStrings: Array<String>,
        mimeTypes: Array<String>,
        fileNames: Array<String>,
        uploadHistoryStore: UploadHistoryStore,
    ): Result {
        val client = WebDavClient(config)

        createNotificationChannel()
        setForeground(buildForegroundInfo(0, uriStrings.size))

        val createResult = client.createDirectory(remoteFolderPath)
        if (createResult.isFailure) {
            recordHistory(
                uploadHistoryStore = uploadHistoryStore,
                occasionName = occasionName,
                photoCount = uriStrings.size,
                failedCount = uriStrings.size,
            )
            showFailureNotification(
                applicationContext.getString(
                    R.string.notification_create_folder_failed,
                    createResult.exceptionOrNull()?.message ?: "",
                ),
            )
            return Result.failure(
                workDataOf("error" to createResult.exceptionOrNull()?.message),
            )
        }

        val result = uploadFiles(client, remoteFolderPath, photoIds, uriStrings, mimeTypes, fileNames)
        val failed = result.failedIndices.size
        recordHistory(
            uploadHistoryStore = uploadHistoryStore,
            occasionName = occasionName,
            photoCount = uriStrings.size,
            failedCount = failed,
        )

        val retryQueueFilePath =
            if (result.failedIndices.isNotEmpty()) {
                writeRetryQueueFile(
                    occasionName,
                    photoIds,
                    result.failedIndices,
                    uriStrings,
                    mimeTypes,
                    fileNames,
                )?.absolutePath
            } else {
                null
            }

        showCompletionNotification(result.uploaded, failed, retryQueueFilePath)

        if (result.uploadedIds.isNotEmpty()) {
            UploadedPhotosStore(applicationContext).markAsUploaded(result.uploadedIds)
        }

        return Result.success(
            workDataOf(
                KEY_UPLOADED_COUNT to result.uploaded,
                KEY_FAILED_COUNT to failed,
            ),
        )
    }

    private suspend fun recordHistory(
        uploadHistoryStore: UploadHistoryStore,
        occasionName: String,
        photoCount: Int,
        failedCount: Int,
    ) {
        val now = System.currentTimeMillis()
        runCatching {
            uploadHistoryStore.addEntry(
                UploadHistoryEntry(
                    id = now,
                    occasionName = occasionName,
                    timestampMillis = now,
                    photoCount = photoCount,
                    failedCount = failedCount,
                ),
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to persist upload history entry", throwable)
        }
    }

    private suspend fun uploadFiles(
        client: WebDavClient,
        remoteFolderPath: String,
        photoIds: LongArray,
        uriStrings: Array<String>,
        mimeTypes: Array<String>,
        fileNames: Array<String>,
    ): UploadFilesResult {
        var uploaded = 0
        val failedIndices = mutableListOf<Int>()
        val uploadedIds = mutableSetOf<Long>()

        for (i in uriStrings.indices) {
            val uri = uriStrings[i].toUri()
            val inputStream = applicationContext.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                failedIndices.add(i)
                continue
            }

            val uploadResult =
                inputStream.use {
                    client.uploadFile(remoteFolderPath, fileNames[i], mimeTypes[i], it)
                }

            if (uploadResult.isSuccess) {
                uploaded++
                val photoId = photoIds.getOrElse(i) { -1L }
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
                    KEY_TOTAL to uriStrings.size,
                ),
            )
            setForeground(buildForegroundInfo(progress, uriStrings.size))
        }

        return UploadFilesResult(uploaded, failedIndices, uploadedIds)
    }

    private fun writeRetryQueueFile(
        folderName: String,
        photoIds: LongArray,
        failedIndices: List<Int>,
        uriStrings: Array<String>,
        mimeTypes: Array<String>,
        fileNames: Array<String>,
    ): File? =
        try {
            val queue =
                JSONObject().apply {
                    put("folderName", folderName)
                    put(
                        "photos",
                        JSONArray().also { arr ->
                            failedIndices.forEach { i ->
                                arr.put(
                                    JSONObject().apply {
                                        val photoId = photoIds.getOrElse(i) { -1L }
                                        if (photoId >= 0L) {
                                            put("id", photoId)
                                        }
                                        put("uri", uriStrings[i])
                                        put("mimeType", mimeTypes[i])
                                        put("displayName", fileNames[i])
                                    },
                                )
                            }
                        },
                    )
                }
            val retryFile =
                File(applicationContext.filesDir, "retry_queue_${UUID.randomUUID()}.json")
            retryFile.writeText(queue.toString())
            retryFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write retry queue file", e)
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

    private fun buildProgressNotification(
        current: Int,
        total: Int,
    ): Notification {
        val text =
            if (total == 0) {
                applicationContext.getString(R.string.notification_preparing)
            } else {
                "$current / $total"
            }
        return NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        retryQueueFilePath: String? = null,
    ) {
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
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(applicationContext.getString(R.string.notification_upload_complete))
                .setContentText(text)
                .setOngoing(false)

        if (failed > 0 && retryQueueFilePath != null) {
            val retryIntent =
                Intent(applicationContext, RetryUploadReceiver::class.java).apply {
                    putExtra(RetryUploadReceiver.EXTRA_QUEUE_FILE, retryQueueFilePath)
                    putExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID)
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

        notifyIfPermitted(builder.build())
    }

    private fun showFailureNotification(message: String) {
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(applicationContext.getString(R.string.notification_upload_failed))
                .setContentText(message)
                .setOngoing(false)
                .build()
        notifyIfPermitted(notification)
    }

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

    private fun notifyIfPermitted(notification: Notification) {
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat
                .from(applicationContext)
                .notify(NOTIFICATION_ID, notification)
        }
    }
}
