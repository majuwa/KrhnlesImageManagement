package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.data.DownloadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DOWNLOAD_CHANNEL_ID
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DOWNLOAD_NOTIFICATION_ID
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DOWNLOAD_RESULT_NOTIFICATION_ID
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadSummary
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.buildDownloadProgressNotification
import de.majuwa.android.paper.krhnlesimagemanagement.worker.buildDownloadResultNotification
import de.majuwa.android.paper.krhnlesimagemanagement.worker.createDownloadNotificationChannel
import de.majuwa.android.paper.krhnlesimagemanagement.worker.downloadFailureMessage
import de.majuwa.android.paper.krhnlesimagemanagement.worker.notifyDownloadResultIfPermitted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.UUID

/** Download notifications (`worker/DownloadNotifications.kt`) and the failure sentences. */
@RunWith(RobolectricTestRunner::class)
class DownloadWorkerNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun result(
        saved: Int,
        failed: Int,
        failures: Set<DownloadFailure> = emptySet(),
        cancelled: Boolean = false,
    ) = context.buildDownloadResultNotification(DownloadSummary(saved, failed, failures, cancelled))

    @Test
    fun `ids differ from the upload ids`() {
        assertEquals(1004, DOWNLOAD_NOTIFICATION_ID)
        assertEquals(1005, DOWNLOAD_RESULT_NOTIFICATION_ID)
        assertNotEquals(UploadWorker.NOTIFICATION_ID, DOWNLOAD_NOTIFICATION_ID)
        assertNotEquals(UploadWorker.RESULT_NOTIFICATION_ID, DOWNLOAD_RESULT_NOTIFICATION_ID)
    }

    @Test
    fun `the channel is low importance and named Downloads`() {
        context.createDownloadNotificationChannel()

        val channel =
            context
                .getSystemService(
                    NotificationManager::class.java,
                ).getNotificationChannel(DOWNLOAD_CHANNEL_ID)
        assertEquals("Downloads", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `progress is determinate uses the download glyph and offers Cancel`() {
        val notification = context.buildDownloadProgressNotification(2, 10, UUID.randomUUID())

        assertEquals(DOWNLOAD_CHANNEL_ID, notification.channelId)
        assertEquals(R.drawable.ic_notification_download, notification.smallIcon.resId)
        assertEquals(10, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(2, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals("2 / 10", text(notification))
        assertEquals(
            "Cancel",
            notification.actions
                .single()
                .title
                .toString(),
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `the progress before the count is known is indeterminate`() {
        val notification = context.buildDownloadProgressNotification(0, 0, UUID.randomUUID())

        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `result titles and texts for complete partial failed and cancelled`() {
        val complete = result(3, 0)
        assertEquals("Photos saved", title(complete))
        assertEquals("3 photos saved.", text(complete))
        assertEquals("1 photo saved.", text(result(1, 0)))
        assertEquals(R.drawable.ic_notification_download, complete.smallIcon.resId)
        assertEquals(DOWNLOAD_CHANNEL_ID, complete.channelId)

        val partial = result(8, 2, setOf(DownloadFailure.NotFound))
        assertEquals("Photos partly saved", title(partial))
        assertEquals(
            "8 photos saved. 2 photos could not be saved. A file was not found on the server any more.",
            text(partial),
        )

        val failed = result(0, 1, setOf(DownloadFailure.Connection))
        assertEquals("Saving failed", title(failed))
        assertEquals("1 photo could not be saved. The server could not be reached.", text(failed))

        val cancelled = result(2, 8, setOf(DownloadFailure.NotFound), cancelled = true)
        assertEquals("Saving cancelled", title(cancelled))
        assertEquals("2 of 10 photos saved before cancelling.", text(cancelled))
        assertEquals("1 of 1 photo saved before cancelling.", text(result(1, 0, cancelled = true)))
    }

    @Test
    fun `an upload result and a download result coexist and batches do not replace each other`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        context.notifyDownloadResultIfPermitted(result(1, 0), "download_queue_a")
        context.notifyDownloadResultIfPermitted(result(2, 0), "download_queue_b")
        androidx.core.app.NotificationManagerCompat
            .from(context)
            .notify("upload_queue_a", UploadWorker.RESULT_NOTIFICATION_ID, result(5, 0))

        val active = manager.activeNotifications
        assertEquals(3, active.size)
        assertEquals(
            setOf("download_queue_a" to 1005, "download_queue_b" to 1005, "upload_queue_a" to 1002),
            active.map { it.tag to it.id }.toSet(),
        )
    }

    @Test
    fun `without the notification permission nothing is posted`() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        context.notifyDownloadResultIfPermitted(result(1, 0), "tag")

        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test
    fun `every failure reason resolves to a non blank sentence and all are joined in order`() {
        DownloadFailure.entries.forEach { failure ->
            val message = downloadFailureMessage(context.resources, setOf(failure))
            assertNotNull(failure.name, message)
            assertTrue(failure.name, message!!.isNotBlank())
        }

        val all = downloadFailureMessage(context.resources, DownloadFailure.entries.toSet())!!
        assertTrue(all.startsWith("Only photos can be saved"))
        assertEquals(null, downloadFailureMessage(context.resources, emptySet()))
    }

    @Test
    fun `plurals format for one and three`() {
        assertEquals("1 photo saved.", context.resources.getQuantityString(R.plurals.download_saved_count, 1, 1))
        assertEquals("3 photos saved.", context.resources.getQuantityString(R.plurals.download_saved_count, 3, 3))
        assertEquals(
            "1 photo could not be saved.",
            context.resources.getQuantityString(R.plurals.download_failed_count, 1, 1),
        )
        assertEquals(
            "3 photos could not be saved.",
            context.resources.getQuantityString(R.plurals.download_failed_count, 3, 3),
        )
        assertEquals(
            "Saving 1 photo in the background.",
            context.resources.getQuantityString(R.plurals.download_saving, 1, 1),
        )
        assertEquals(
            "Saving 3 photos in the background.",
            context.resources.getQuantityString(R.plurals.download_saving, 3, 3),
        )
    }
}
