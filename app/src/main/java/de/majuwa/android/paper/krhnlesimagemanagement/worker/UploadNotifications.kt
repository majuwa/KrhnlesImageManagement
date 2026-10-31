package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import de.majuwa.android.paper.krhnlesimagemanagement.R
import java.io.File
import java.util.UUID

internal const val UPLOAD_CHANNEL_ID = "upload_channel"
internal const val REQUEST_CODE_RETRY = 2001
internal const val REQUEST_CODE_CANCEL = 2002

internal fun Context.createUploadNotificationChannel() {
    val channel =
        NotificationChannel(
            UPLOAD_CHANNEL_ID,
            getString(R.string.channel_name_uploads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_desc_uploads)
        }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

private fun Context.uploadNotificationBuilder(): NotificationCompat.Builder =
    NotificationCompat
        .Builder(this, UPLOAD_CHANNEL_ID)
        .setSmallIcon(IconCompat.createWithResource(this, UploadWorker.NOTIFICATION_ICON))

/**
 * Foreground / progress notification of the upload work [workId]; its "Cancel" action is an
 * immutable, explicit broadcast to the non-exported [CancelUploadReceiver]. The data URI makes the
 * `PendingIntent` unique per work (identity ignores extras).
 */
internal fun Context.buildProgressNotification(
    current: Int,
    total: Int,
    workId: UUID,
): Notification {
    val text =
        if (total == 0) {
            getString(R.string.notification_preparing)
        } else {
            "$current / $total"
        }
    val cancelIntent =
        Intent(this, CancelUploadReceiver::class.java).apply {
            putExtra(CancelUploadReceiver.EXTRA_WORK_ID, workId.toString())
            data = "cancel-upload://$workId".toUri()
        }
    val cancelPendingIntent =
        PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    return uploadNotificationBuilder()
        .setContentTitle(getString(R.string.notification_uploading_title))
        .setContentText(text)
        .setProgress(total, current, total == 0)
        .setOngoing(true)
        .setSilent(true)
        .addAction(0, getString(R.string.notification_action_cancel), cancelPendingIntent)
        .build()
}

/**
 * The batch result; [CompletionSummary.failureReason] (user-facing cause, e.g. HTTP 413 / 507)
 * follows the counts. A [CompletionSummary.cancelled] batch is titled "Upload cancelled" and calls
 * the items not uploaded "not uploaded", never failed.
 */
internal fun Context.buildCompletionNotification(
    summary: CompletionSummary,
    notificationTag: String,
): Notification {
    val uploaded = summary.uploaded
    val failed = summary.failed
    val retryQueueFilePath = summary.retryQueueFilePath
    val failureReason = summary.failureReason
    val counts =
        when {
            summary.cancelled -> {
                resources.getQuantityString(
                    R.plurals.notification_upload_cancelled_counts,
                    failed,
                    uploaded,
                    failed,
                )
            }

            failed == 0 -> {
                resources.getQuantityString(R.plurals.notification_upload_success, uploaded, uploaded)
            }

            else -> {
                resources.getQuantityString(R.plurals.notification_upload_partial, uploaded, uploaded, failed)
            }
        }
    val text =
        if (failed > 0 && failureReason != null) {
            getString(R.string.notification_text_with_reason, counts, failureReason)
        } else {
            counts
        }
    val title =
        when {
            summary.cancelled -> R.string.notification_upload_cancelled
            summary.gaveUp -> R.string.notification_upload_failed
            else -> R.string.notification_upload_complete
        }
    val builder =
        uploadNotificationBuilder()
            .setContentTitle(getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)

    if (failed > 0 && retryQueueFilePath != null) {
        val retryIntent =
            Intent(this, RetryUploadReceiver::class.java).apply {
                putExtra(RetryUploadReceiver.EXTRA_QUEUE_FILE, retryQueueFilePath)
                putExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_ID, UploadWorker.RESULT_NOTIFICATION_ID)
                putExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_TAG, notificationTag)
                // Unique data URI: PendingIntent identity ignores extras, so each retry file needs its own.
                data = "retry://${File(retryQueueFilePath).name}".toUri()
            }
        val retryPendingIntent =
            PendingIntent.getBroadcast(
                this,
                REQUEST_CODE_RETRY,
                retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        builder.addAction(0, getString(R.string.notification_retry_failed, failed), retryPendingIntent)
    }
    return builder.build()
}

internal fun Context.buildFailureNotification(message: String): Notification =
    uploadNotificationBuilder()
        .setContentTitle(getString(R.string.notification_upload_failed))
        .setContentText(message)
        .setOngoing(false)
        .build()

/** Posts a result notification under [notificationTag] and [UploadWorker.RESULT_NOTIFICATION_ID] if allowed. */
internal fun Context.notifyIfPermitted(
    notification: Notification,
    notificationTag: String,
) {
    if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat
            .from(this)
            .notify(notificationTag, UploadWorker.RESULT_NOTIFICATION_ID, notification)
    }
}
