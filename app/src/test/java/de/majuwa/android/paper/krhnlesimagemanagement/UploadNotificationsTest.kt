package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.worker.CancelUploadReceiver
import de.majuwa.android.paper.krhnlesimagemanagement.worker.CompletionSummary
import de.majuwa.android.paper.krhnlesimagemanagement.worker.RetryUploadReceiver
import de.majuwa.android.paper.krhnlesimagemanagement.worker.buildCompletionNotification
import de.majuwa.android.paper.krhnlesimagemanagement.worker.buildProgressNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.UUID

/** The upload notifications built by `worker/UploadNotifications.kt`: cancelled result and Cancel action. */
@RunWith(RobolectricTestRunner::class)
class UploadNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun cancelledResult_saysUploadedAndNotUploaded_neverFailed_andOffersRetry() {
        val retryPath = "${context.noBackupFilesDir}/retry_queue_${UUID.randomUUID()}.json"
        val notification =
            context.buildCompletionNotification(
                CompletionSummary(uploaded = 4, failed = 6, retryQueueFilePath = retryPath, cancelled = true),
                notificationTag = "tag-1",
            )

        assertEquals("Upload cancelled", title(notification))
        assertEquals("4 uploaded, 6 not uploaded.", text(notification))
        assertFalse(text(notification).contains("failed"))
        val action = notification.actions.single()
        assertEquals("Retry Failed (6)", action.title.toString())
        val pending = action.actionIntent
        assertTrue(shadowOf(pending).isBroadcast)
        assertTrue(pending.isImmutable)
        val intent = shadowOf(pending).savedIntent
        assertEquals("retry://${retryPath.substringAfterLast('/')}", intent.data.toString())
        assertEquals(RetryUploadReceiver::class.java.name, intent.component?.className)
        assertEquals(retryPath, intent.getStringExtra(RetryUploadReceiver.EXTRA_QUEUE_FILE))
        assertEquals("tag-1", intent.getStringExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_TAG))
    }

    @Test
    fun cancelledResult_appendsTheReasonThroughTheSharedJoinResource() {
        val notification =
            context.buildCompletionNotification(
                CompletionSummary(
                    1,
                    2,
                    "retry_queue_x.json",
                    failureReason = "A file is too large for the server.",
                    cancelled = true,
                ),
                "tag",
            )

        assertEquals("1 uploaded, 2 not uploaded. A file is too large for the server.", text(notification))
    }

    @Test
    fun nonCancelledResults_keepTheirTitles() {
        val complete = context.buildCompletionNotification(CompletionSummary(2, 0, null), "t")
        val gaveUp = context.buildCompletionNotification(CompletionSummary(1, 1, "retry.json", gaveUp = true), "t")

        assertEquals("Upload complete", title(complete))
        assertEquals("Upload failed", title(gaveUp))
    }

    @Test
    fun progressNotification_hasOneImmutableExplicitCancelBroadcastForItsWork() {
        val workId = UUID.randomUUID()
        val notification = context.buildProgressNotification(1, 3, workId)

        assertEquals(R.drawable.ic_notification_upload, notification.smallIcon.resId)
        assertEquals("1 / 3", text(notification))
        val action = notification.actions.single()
        assertEquals("Cancel", action.title.toString())
        assertTrue(action.actionIntent.isImmutable)
        assertTrue(shadowOf(action.actionIntent).isBroadcast)
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(CancelUploadReceiver::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(workId.toString(), intent.getStringExtra(CancelUploadReceiver.EXTRA_WORK_ID))
        assertTrue(intent.data.toString().contains(workId.toString()))
        assertTrue(shadowOf(action.actionIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun cancelActionsOfTwoWorksAreDistinct() {
        val first =
            context
                .buildProgressNotification(0, 1, UUID.randomUUID())
                .actions
                .single()
                .actionIntent
        val second =
            context
                .buildProgressNotification(0, 1, UUID.randomUUID())
                .actions
                .single()
                .actionIntent

        assertNotEquals(first, second)
    }
}
