package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_QUEUE_FILE = "queue_file"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_FAILED_COUNT = "failed_count"
        private const val CHANNEL_ID = "upload_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        val queueFilePath = inputData.getString(KEY_QUEUE_FILE) ?: return Result.failure()
        val queueFile = File(queueFilePath)
        val queue = JSONObject(queueFile.readText())
        val folderName = queue.getString("folderName")
        val photosArray = queue.getJSONArray("photos")
        val uriStrings = Array(photosArray.length()) { photosArray.getJSONObject(it).getString("uri") }
        val mimeTypes = Array(photosArray.length()) { photosArray.getJSONObject(it).getString("mimeType") }
        val fileNames = Array(photosArray.length()) { photosArray.getJSONObject(it).getString("displayName") }

        val credentialStore = CredentialStore(applicationContext)
        val config = credentialStore.webDavConfig.first()

        if (!config.isValid) {
            queueFile.delete()
            return Result.failure(
                workDataOf("error" to "WebDAV not configured. Please check settings."),
            )
        }

        val remotePath = "${config.uploadPathPrefix}$folderName"
        return executeUpload(config, remotePath, uriStrings, mimeTypes, fileNames)
            .also { queueFile.delete() }
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
            val uri = uriStrings[i].toUri()
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
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(applicationContext.getString(R.string.notification_upload_complete))
                .setContentText(text)
                .setOngoing(false)
                .build()
        notifyIfPermitted(notification)
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
