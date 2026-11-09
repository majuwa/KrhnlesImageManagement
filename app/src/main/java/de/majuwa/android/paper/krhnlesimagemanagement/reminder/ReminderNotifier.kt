package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.Manifest
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
import de.majuwa.android.paper.krhnlesimagemanagement.MainActivity
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.ACTION_SHOW_NEW_MEDIA

internal const val REMINDER_CHANNEL_ID = "new_photos_reminder_channel"

/** Never the upload ids 1001 / 1002 or the download ids 1004 / 1005; no tag, so a new reminder replaces the old one. */
internal const val NEW_PHOTOS_REMINDER_NOTIFICATION_ID = 1003

private const val REQUEST_CODE_SHOW_NEW_MEDIA = 1003

/** What the reminder worker needs from the notification side. */
internal interface ReminderNotifications {
    /** Whether a reminder could be shown right now (permission, app notifications, channel). */
    fun canPost(): Boolean

    /** Posts the reminder. [sinceLastUpload]: wording "since your last upload". */
    fun post(
        count: NewMediaCount,
        sinceLastUpload: Boolean,
    )

    fun cancel()
}

/**
 * The pure rule of "can a reminder be shown": permission granted, app notifications on and the channel not
 * blocked. A channel that does not exist yet ([channelImportance] null) counts as allowed (it is created right
 * before posting). Shared by the worker and the Settings hint.
 */
internal fun reminderNotificationsAllowed(
    permissionGranted: Boolean,
    appNotificationsEnabled: Boolean,
    channelImportance: Int?,
): Boolean = permissionGranted && appNotificationsEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE

/** [reminderNotificationsAllowed] for the current state of the device. */
internal fun Context.canPostReminders(): Boolean =
    reminderNotificationsAllowed(
        permissionGranted =
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        appNotificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        channelImportance =
            getSystemService(
                NotificationManager::class.java,
            ).getNotificationChannel(REMINDER_CHANNEL_ID)?.importance,
    )

/** The reminder notification on its own channel (`IMPORTANCE_DEFAULT`) and id; carries only a count. */
internal class ReminderNotifier(
    context: Context,
) : ReminderNotifications {
    private val context = context.applicationContext

    override fun canPost(): Boolean = context.canPostReminders()

    override fun post(
        count: NewMediaCount,
        sinceLastUpload: Boolean,
    ) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel()
        NotificationManagerCompat
            .from(
                context,
            ).notify(NEW_PHOTOS_REMINDER_NOTIFICATION_ID, build(count, sinceLastUpload))
    }

    override fun cancel() {
        NotificationManagerCompat.from(context).cancel(NEW_PHOTOS_REMINDER_NOTIFICATION_ID)
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                REMINDER_CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.reminder_channel_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    internal fun build(
        count: NewMediaCount,
        sinceLastUpload: Boolean,
    ): android.app.Notification {
        val plural =
            when {
                count.isLowerBound -> R.plurals.reminder_new_media_more_than
                sinceLastUpload -> R.plurals.reminder_new_media_since_upload
                else -> R.plurals.reminder_new_media
            }
        return NotificationCompat
            .Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(IconCompat.createWithResource(context, R.drawable.ic_notification_upload))
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(context.resources.getQuantityString(plural, count.total, count.total))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }

    /** Explicit and immutable; carries only the action, no extras. */
    private fun contentIntent(): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_SHOW_NEW_MEDIA)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_SHOW_NEW_MEDIA,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
