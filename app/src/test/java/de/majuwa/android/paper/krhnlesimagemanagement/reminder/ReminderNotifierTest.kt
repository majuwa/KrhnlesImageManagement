package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.MainActivity
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.ACTION_SHOW_NEW_MEDIA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReminderNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = ReminderNotifier(context)

    @Before
    fun grant() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted(id: Int): Notification? = shadowOf(manager).getNotification(id)

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun `posts one notification on the reminder channel with the plural text`() {
        notifier.post(NewMediaCount(37, true, false), sinceLastUpload = true)
        val n = requireNotNull(posted(NEW_PHOTOS_REMINDER_NOTIFICATION_ID))
        assertEquals(1003, NEW_PHOTOS_REMINDER_NOTIFICATION_ID)
        assertEquals(REMINDER_CHANNEL_ID, n.channelId)
        assertEquals("37 new photos and videos since your last upload", text(n))
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(REMINDER_CHANNEL_ID).importance,
        )
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `singular, no-upload and lower-bound texts`() {
        notifier.post(NewMediaCount(1, true, false), sinceLastUpload = true)
        assertEquals("1 new photo or video since your last upload", text(requireNotNull(posted(1003))))
        notifier.post(NewMediaCount(5, true, false), sinceLastUpload = false)
        assertEquals("5 new photos and videos on this device", text(requireNotNull(posted(1003))))
        notifier.post(NewMediaCount(50_000, true, true), sinceLastUpload = true)
        assertEquals("At least 50000 new photos and videos on this device", text(requireNotNull(posted(1003))))
    }

    @Test
    fun `tap intent is explicit, immutable, carries the action and no extras`() {
        notifier.post(NewMediaCount(2, true, false), sinceLastUpload = true)
        val pending = requireNotNull(posted(1003)).contentIntent
        assertNotNull(pending)
        assertTrue(pending.isImmutable)
        assertTrue(shadowOf(pending).isActivity)
        val intent = shadowOf(pending).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(ACTION_SHOW_NEW_MEDIA, intent.action)
        assertNull(intent.extras)
        assertNull(intent.data)
        val expectedFlags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(expectedFlags, intent.flags and expectedFlags)
    }

    @Test
    fun `the lock screen shows only the title, never the count`() {
        notifier.post(NewMediaCount(37, true, false), sinceLastUpload = true)
        val n = requireNotNull(posted(1003))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
        val public = requireNotNull(n.publicVersion)
        assertEquals("Photos waiting for backup", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(public.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(public.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertNull(public.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNull(public.tickerText)
    }

    @Test
    fun `the notification text carries only a count`() {
        notifier.post(NewMediaCount(3, true, false), sinceLastUpload = true)
        val n = requireNotNull(posted(1003))
        assertFalse(text(n).contains("jpg"))
    }

    @Test
    fun `canPost follows permission, app notifications and channel`() {
        assertTrue(notifier.canPost())

        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(notifier.canPost())
        shadowOf(manager).setNotificationsEnabled(true)

        manager.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL_ID, "x", NotificationManager.IMPORTANCE_NONE),
        )
        assertFalse(notifier.canPost())
        manager.deleteNotificationChannel(REMINDER_CHANNEL_ID)
        assertTrue(notifier.canPost())

        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifier.canPost())
    }

    @Test
    fun `pure rule treats a missing channel as allowed`() {
        assertTrue(reminderNotificationsAllowed(true, true, null))
        assertFalse(reminderNotificationsAllowed(false, true, null))
        assertFalse(reminderNotificationsAllowed(true, false, NotificationManager.IMPORTANCE_DEFAULT))
        assertFalse(reminderNotificationsAllowed(true, true, NotificationManager.IMPORTANCE_NONE))
    }

    @Test
    fun `posting leaves upload and download result notifications in place and cancel removes only the reminder`() {
        val other = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        manager.notify("q", 1002, other)
        manager.notify("d", 1005, other)
        notifier.post(NewMediaCount(2, true, false), sinceLastUpload = true)
        assertNotNull(shadowOf(manager).getNotification("q", 1002))
        assertNotNull(shadowOf(manager).getNotification("d", 1005))
        assertNotNull(posted(1003))

        notifier.cancel()
        assertNull(posted(1003))
        assertNotNull(shadowOf(manager).getNotification("q", 1002))
    }

    @Test
    fun `nothing is posted without the permission`() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.post(NewMediaCount(2, true, false), sinceLastUpload = true)
        assertNull(posted(1003))
    }
}
