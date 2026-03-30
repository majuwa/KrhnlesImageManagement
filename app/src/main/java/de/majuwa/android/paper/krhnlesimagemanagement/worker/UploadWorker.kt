package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.first

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_FOLDER_NAME = "folder_name"
        const val KEY_PHOTO_URIS = "photo_uris"
        const val KEY_MIME_TYPES = "mime_types"
        const val KEY_FILE_NAMES = "file_names"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_FAILED_COUNT = "failed_count"
        private const val CHANNEL_ID = "upload_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        val folderName = inputData.getString(KEY_FOLDER_NAME) ?: return Result.failure()
        val uriStrings = inputData.getStringArray(KEY_PHOTO_URIS) ?: return Result.failure()
        val mimeTypes = inputData.getStringArray(KEY_MIME_TYPES) ?: return Result.failure()
        val fileNames = inputData.getStringArray(KEY_FILE_NAMES) ?: return Result.failure()

        val credentialStore = CredentialStore(applicationContext)
        val config = credentialStore.webDavConfig.first()

        if (!config.isValid) {
            return Result.failure(
                workDataOf("error" to "WebDAV not configured. Please check settings."),
            )
        }

        val remotePath = "${config.uploadPathPrefix}$folderName"
        return executeUpload(config, remotePath, uriStrings, mimeTypes, fileNames)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        return buildForegroundInfo(0, 0)
    }

    private suspend fun executeUpload(
        config: WebDavConfig,
        folderName: String,
        uriStrings: Array<String>,
        mimeTypes: Array<String>,
        fileNames: Array<String>,
    ): Result {
        val client = WebDavClient(config)

        createNotificationChannel()
        setForeground(buildForegroundInfo(0, uriStrings.size))

        val createResult = client.createDirectory(folderName)
        if (createResult.isFailure) {
            showFailureNotification(
                "Failed to create folder: ${createResult.exceptionOrNull()?.message}",
            )
            return Result.failure(
                workDataOf("error" to createResult.exceptionOrNull()?.message),
            )
        }

        val (uploaded, failed) = uploadFiles(client, folderName, uriStrings, mimeTypes, fileNames)
        showCompletionNotification(uploaded, failed)

        return Result.success(
            workDataOf(
                KEY_UPLOADED_COUNT to uploaded,
                KEY_FAILED_COUNT to failed,
            ),
        )
    }

    private suspend fun uploadFiles(
        client: WebDavClient,
        folderName: String,
        uriStrings: Array<String>,
        mimeTypes: Array<String>,
        fileNames: Array<String>,
    ): Pair<Int, Int> {
        var uploaded = 0
        var failed = 0

        for (i in uriStrings.indices) {
            val uri = Uri.parse(uriStrings[i])
            val inputStream = applicationContext.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                failed++
                continue
            }

            val uploadResult =
                inputStream.use {
                    client.uploadFile(folderName, fileNames[i], mimeTypes[i], it)
                }

            if (uploadResult.isSuccess) uploaded++ else failed++

            val progress = i + 1
            setProgress(
                workDataOf(
                    KEY_PROGRESS to progress,
                    KEY_TOTAL to uriStrings.size,
                ),
            )
            setForeground(buildForegroundInfo(progress, uriStrings.size))
        }

        return uploaded to failed
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
        val text = if (total == 0) "Preparing…" else "$current / $total"
        return NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Uploading photos")
            .setContentText(text)
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showCompletionNotification(
        uploaded: Int,
        failed: Int,
    ) {
        val text =
            if (failed == 0) {
                "$uploaded photos uploaded successfully."
            } else {
                "$uploaded uploaded, $failed failed."
            }
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Upload complete")
                .setContentText(text)
                .setOngoing(false)
                .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private fun showFailureNotification(message: String) {
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Upload failed")
                .setContentText(message)
                .setOngoing(false)
                .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Photo Uploads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows upload progress for photo backups"
            }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
