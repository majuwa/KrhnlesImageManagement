package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.work.WorkManager
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.DownloadFailure
import java.util.UUID

internal const val DOWNLOAD_CHANNEL_ID = "download_channel"

/** Foreground / progress notification of a download batch; only one batch runs at a time. */
internal const val DOWNLOAD_NOTIFICATION_ID = 1004

/** Id of every download result notification; batches are told apart by the notification tag (queue file name). */
internal const val DOWNLOAD_RESULT_NOTIFICATION_ID = 1005

internal val DOWNLOAD_NOTIFICATION_ICON = R.drawable.ic_notification_download

internal fun Context.createDownloadNotificationChannel() {
    val channel =
        NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.channel_name_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_desc_downloads)
        }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

private fun Context.downloadNotificationBuilder(): NotificationCompat.Builder =
    NotificationCompat
        .Builder(this, DOWNLOAD_CHANNEL_ID)
        .setSmallIcon(IconCompat.createWithResource(this, DOWNLOAD_NOTIFICATION_ICON))

/**
 * Foreground / progress notification of the download work [workId] with determinate progress. Its
 * "Cancel" action is WorkManager's own cancel intent for that work (the only use of
 * `createCancelPendingIntent`, see `NoUploadWorkCancelOutsideCancelPathRegressionTest`): the worker
 * is stopped with `STOP_REASON_CANCELLED_BY_APP`, and so are the batches queued after it.
 */
internal fun Context.buildDownloadProgressNotification(
    current: Int,
    total: Int,
    workId: UUID,
): Notification {
    val cancelIntent = WorkManager.getInstance(this).createCancelPendingIntent(workId)
    return downloadNotificationBuilder()
        .setContentTitle(getString(R.string.download_notification_title))
        .setContentText(if (total == 0) getString(R.string.notification_preparing) else "$current / $total")
        .setProgress(total, current, total == 0)
        .setOngoing(true)
        .setSilent(true)
        .addAction(0, getString(R.string.action_cancel_download), cancelIntent)
        .build()
}

/** What the result notification of a download batch reports. */
internal data class DownloadSummary(
    val saved: Int,
    val failed: Int,
    val failures: Set<DownloadFailure>,
    /** The user cancelled: titled "Saving cancelled", counted as "N of M saved", never "failed". */
    val cancelled: Boolean = false,
)

/** The batch result: counts in plural forms, then one sentence per distinct failure reason. */
internal fun Context.buildDownloadResultNotification(summary: DownloadSummary): Notification {
    val title =
        when {
            summary.cancelled -> R.string.download_cancelled
            summary.failed == 0 -> R.string.download_complete
            summary.saved == 0 -> R.string.download_failed
            else -> R.string.download_partial
        }
    val counts =
        if (summary.cancelled) {
            val total = summary.saved + summary.failed
            listOf(resources.getQuantityString(R.plurals.download_cancelled_counts, total, summary.saved, total))
        } else {
            listOfNotNull(
                resources
                    .getQuantityString(R.plurals.download_saved_count, summary.saved, summary.saved)
                    .takeIf { summary.saved > 0 || summary.failed == 0 },
                resources
                    .getQuantityString(R.plurals.download_failed_count, summary.failed, summary.failed)
                    .takeIf { summary.failed > 0 },
            )
        }
    val reasons = if (summary.cancelled) null else downloadFailureMessage(resources, summary.failures)
    val text = joinNotificationTexts(resources, counts + listOfNotNull(reasons)).orEmpty()
    return downloadNotificationBuilder()
        .setContentTitle(getString(title))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(false)
        .build()
}

/**
 * One localized sentence for each distinct failure in [failures], in the fixed order of
 * [DownloadFailure], joined via [joinNotificationTexts]; null for none. Never any server text.
 */
internal fun downloadFailureMessage(
    resources: Resources,
    failures: Set<DownloadFailure>,
): String? =
    joinNotificationTexts(
        resources,
        DownloadFailure.entries.filter { it in failures }.map { resources.getString(it.messageRes()) },
    )

private fun DownloadFailure.messageRes(): Int =
    when (this) {
        DownloadFailure.Unsupported -> R.string.download_failure_unsupported
        DownloadFailure.TooLarge -> R.string.download_failure_too_large
        DownloadFailure.StorageFull -> R.string.download_failure_storage_full
        DownloadFailure.NotFound -> R.string.download_failure_not_found
        DownloadFailure.AccessDenied -> R.string.download_failure_access_denied
        DownloadFailure.Certificate -> R.string.download_failure_certificate
        DownloadFailure.Connection -> R.string.download_failure_connection
        DownloadFailure.ServerError -> R.string.download_failure_server_error
        DownloadFailure.Rejected -> R.string.download_failure_rejected
        DownloadFailure.WriteFailed -> R.string.download_failure_write_failed
        DownloadFailure.ConfigurationChanged -> R.string.download_failure_configuration_changed
        DownloadFailure.Interrupted -> R.string.download_failure_interrupted
    }

/**
 * Posts a download result under [notificationTag] and [DOWNLOAD_RESULT_NOTIFICATION_ID] if
 * `POST_NOTIFICATIONS` is granted.
 */
internal fun Context.notifyDownloadResultIfPermitted(
    notification: Notification,
    notificationTag: String,
) {
    if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat
            .from(this)
            .notify(notificationTag, DOWNLOAD_RESULT_NOTIFICATION_ID, notification)
    }
}
