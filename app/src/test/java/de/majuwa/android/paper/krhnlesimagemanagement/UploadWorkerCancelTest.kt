package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.work.ListenableWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * The real [UploadWorker] cancelled by the user (design D2-D4): the running `PUT` is aborted, what
 * was uploaded stays uploaded, the rest becomes a retry file and the run is reported as cancelled.
 * The user-cancel decision is the `userCancelled` seam (the real rule is covered end-to-end by
 * `UploadWorkerCancelWorkManagerTest`).
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerCancelTest {
    private val h = UploadCancelHarness()

    @Before
    fun setUp() {
        h.setUp()
        h.userCancelled = { true }
    }

    @After
    fun tearDown() = h.tearDown()

    @Test
    fun cancelDuringTheThirdPut_keepsTwoUploaded_andTurnsTheRestIntoARetryFile() {
        h.holdPutNumber(3)
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg")

        h.runAndCancelWhenHeld(queue)

        val retry = h.retryFiles().single()
        assertEquals(listOf("c.jpg", "d.jpg", "e.jpg"), UploadCancelHarness.names(retry))
        val json = JSONObject(retry.readText())
        assertEquals("Trip", json.getString("folderName"))
        assertFalse(json.has("interruptions"))
        assertEquals(listOf(false, false, false), UploadCancelHarness.markers(retry))
        assertFalse(queue.exists())
        assertEquals(setOf(1L, 2L), h.uploadedIds())
        assertEquals(
            setOf(1L, 2L),
            h.verified.current
                .map { it.id }
                .toSet(),
        )

        val entry =
            h.history.state.value
                .single()
        assertEquals(5, entry.photoCount)
        assertEquals(3, entry.failedCount)
        assertTrue(entry.cancelled)

        val posted = h.resultNotifications().single()
        assertEquals(queue.name, posted.tag)
        assertEquals(
            h.context.getString(de.majuwa.android.paper.krhnlesimagemanagement.R.string.notification_upload_cancelled),
            h.title(posted.notification),
        )
        assertEquals("2 uploaded, 3 not uploaded.", h.text(posted.notification))
        assertEquals(
            "Retry Failed (3)",
            posted.notification.actions
                .single()
                .title
                .toString(),
        )
    }

    @Test
    fun resumingTheRetryFile_uploadsOnlyTheRemainingItems_onceOverBothRuns() {
        h.holdPutNumber(3)
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg")
        h.runAndCancelWhenHeld(queue)
        val retry = h.retryFiles().single()
        h.holdPutNumber(null)
        h.userCancelled = null

        val result = h.run(retry)

        assertTrue(result is ListenableWorker.Result.Success)
        val names = h.putNames()
        // a and b once; c was aborted mid-PUT in the first run and sent again in the retry.
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg", "c.jpg", "d.jpg", "e.jpg"), names)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), h.uploadedIds())
        assertFalse(retry.exists())
        val retryEntry =
            h.history.state.value
                .last()
        assertEquals(3, retryEntry.photoCount)
        assertEquals(0, retryEntry.failedCount)
        assertFalse(retryEntry.cancelled)
    }

    @Test
    fun cancelWhileTheFolderIsCreated_sendsNoPhoto_andKeepsEveryItem() {
        h.holdMethod = "MKCOL"
        val queue = h.queueFile("a.jpg", "b.jpg")

        h.runAndCancelWhenHeld(queue)

        assertTrue(h.putNames().isEmpty())
        assertEquals(listOf("a.jpg", "b.jpg"), UploadCancelHarness.names(h.retryFiles().single()))
        val entry =
            h.history.state.value
                .single()
        assertEquals(2, entry.photoCount)
        assertEquals(2, entry.failedCount)
        assertTrue(entry.cancelled)
        assertTrue(h.uploadedIds().isEmpty())
    }

    @Test
    fun cancelBeforeTheForegroundServiceStarts_sendsNothing_andKeepsEveryItem() {
        h.holdForeground = true
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")

        h.runAndCancelWhenHeld(queue)

        assertTrue(h.requests.isEmpty())
        assertEquals(3, UploadCancelHarness.names(h.retryFiles().single()).size)
        assertTrue(
            h.history.state.value
                .single()
                .cancelled,
        )
    }

    @Test
    fun cancelledAutoDateQueue_keepsEveryRemainingItemInItsOwnFolder() {
        h.holdPutNumber(2)
        val queue = h.perPhotoQueueFile("a.jpg" to "2024/05/01", "b.jpg" to "2024/05/02", "c.jpg" to "2024/05/02")

        h.runAndCancelWhenHeld(queue)

        val retry = h.retryFiles().single()
        val photos = JSONObject(retry.readText()).getJSONArray("photos")
        assertEquals("2024/05/02", photos.getJSONObject(0).getString("folderName"))
        assertEquals("2024/05/02", photos.getJSONObject(1).getString("folderName"))
        assertEquals(listOf("b.jpg", "c.jpg"), UploadCancelHarness.names(retry))
        assertFalse(JSONObject(retry.readText()).has("folderName"))
    }

    @Test
    fun legacyFilesDirQueue_isFinalizedIntoTheNoBackupDirectory() {
        h.holdPutNumber(1)
        val queue = h.queueFile("a.jpg", "b.jpg", dir = h.context.filesDir)

        h.runAndCancelWhenHeld(queue)

        assertFalse(queue.exists())
        val retry = h.retryFiles().single()
        assertEquals(h.context.noBackupFilesDir, retry.parentFile)
        assertEquals(listOf("a.jpg", "b.jpg"), UploadCancelHarness.names(retry))
    }

    @Test
    fun cancellingARetryRun_producesANewResumableRetryFile() {
        h.holdPutNumber(2)
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")
        h.runAndCancelWhenHeld(queue)
        val firstRetry = h.retryFiles().single()
        assertEquals(listOf("b.jpg", "c.jpg"), UploadCancelHarness.names(firstRetry))

        // Cancel the retry during its second PUT (c.jpg): b.jpg is uploaded by the retry.
        h.holdPutNumber(2)
        h.runAndCancelWhenHeld(firstRetry)

        val newRetry = h.retryFiles().single()
        assertNotEquals(firstRetry.name, newRetry.name)
        assertFalse(firstRetry.exists())
        assertEquals(listOf("c.jpg"), UploadCancelHarness.names(newRetry))
        assertEquals(2, h.history.state.value.size)
        assertTrue(
            h.history.state.value
                .all { it.cancelled },
        )
    }

    @Test
    fun userCancelAfterFourInterruptions_resumesWithAnInterruptionCountOfZero() {
        h.holdPutNumber(1)
        val queue = h.queueFile("a.jpg", "b.jpg", interruptions = 4)

        h.runAndCancelWhenHeld(queue)

        val retry = h.retryFiles().single()
        assertFalse(JSONObject(retry.readText()).has("interruptions"))
        assertEquals(2, UploadCancelHarness.names(retry).size)
    }

    @Test
    fun stagedItems_uploadedCopiesAreDeleted_remainingCopiesStayForTheRetry() {
        h.holdPutNumber(3)
        val (queue, staged) = h.stagedQueueFile("a.jpg", "b.jpg", "c.jpg", "d.jpg")

        h.runAndCancelWhenHeld(queue)

        val dir =
            de.majuwa.android.paper.krhnlesimagemanagement.worker
                .shareStagingDir(h.context)
        assertFalse(java.io.File(dir, staged[0]).exists())
        assertFalse(java.io.File(dir, staged[1]).exists())
        assertTrue(java.io.File(dir, staged[2]).exists())
        assertTrue(java.io.File(dir, staged[3]).exists())
        val retry = h.retryFiles().single()
        assertEquals(listOf("c.jpg", "d.jpg"), UploadCancelHarness.names(retry))
        assertEquals(
            staged.drop(2),
            JSONObject(retry.readText()).getJSONArray("photos").let { arr ->
                List(arr.length()) { arr.getJSONObject(it).getString("staged") }
            },
        )
        assertEquals(0, h.uploaded.markCalls)

        // The retry uploads the remaining photos from their staged copies.
        h.holdPutNumber(null)
        h.userCancelled = null
        h.putBodies.clear()
        assertTrue(h.run(retry) is ListenableWorker.Result.Success)
        assertEquals(
            "data-c.jpg",
            h.putBodies.entries
                .single { it.key.endsWith("/c.jpg") }
                .value,
        )
        assertEquals(
            "data-d.jpg",
            h.putBodies.entries
                .single { it.key.endsWith("/d.jpg") }
                .value,
        )
        assertTrue(staged.none { java.io.File(dir, it).exists() })
    }

    @Test
    fun cancelledShareUpload_keepsItsStagedCopiesThroughTheSweep() {
        h.holdPutNumber(2)
        val (queue, staged) = h.stagedQueueFile("a.jpg", "b.jpg")
        h.runAndCancelWhenHeld(queue)
        val dir =
            de.majuwa.android.paper.krhnlesimagemanagement.worker
                .shareStagingDir(h.context)
        java.io.File(dir, staged[1]).setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8))
        val staging =
            de.majuwa.android.paper.krhnlesimagemanagement.upload.ShareStaging(
                dir = dir,
                queueDirs = listOf(h.context.noBackupFilesDir),
                freeBytes = { Long.MAX_VALUE },
            )

        staging.sweep()

        // The retry window counts from the cancel (the retry file's own mtime), not from the copy.
        assertTrue(java.io.File(dir, staged[1]).exists())
        assertTrue(h.retryFiles().single().exists())
    }

    // ── bookkeeping of a finished PUT / terminal reporting ──────────────────────────────────

    @Test
    fun cancelArrivingWhileAFinishedPutIsBooked_stillMarksTheItemAndKeepsItsRecord() {
        val scope = CoroutineScope(Dispatchers.Default)
        lateinit var job: kotlinx.coroutines.Job
        h.uploadedOverride = CancellingUploadedPhotos(h.uploaded) { job.cancel() }
        val queue = h.queueFile("a.jpg", "b.jpg")

        job = h.start(queue, scope)
        runBlocking { job.join() }
        scope.cancel()

        assertEquals(setOf(1L), h.uploadedIds())
        assertEquals(listOf(1L), h.verified.current.map { it.id })
        assertEquals(listOf("a.jpg"), h.putNames())
        val retry = h.retryFiles().single()
        assertEquals(listOf("b.jpg"), UploadCancelHarness.names(retry))
        assertTrue(
            h.history.state.value
                .single()
                .cancelled,
        )
    }

    @Test
    fun cancelAfterTheLastItem_reportsOneNormalCompletion_andNoRetryFile() {
        val scope = CoroutineScope(Dispatchers.Default)
        lateinit var job: kotlinx.coroutines.Job
        h.uploadedOverride = CancellingUploadedPhotos(h.uploaded) { job.cancel() }
        val queue = h.queueFile("a.jpg")

        job = h.start(queue, scope)
        runBlocking { job.join() }
        scope.cancel()

        assertTrue(h.retryFiles().isEmpty())
        assertFalse(queue.exists())
        val entry =
            h.history.state.value
                .single()
        assertFalse(entry.cancelled)
        assertEquals(1, entry.photoCount)
        assertEquals(0, entry.failedCount)
        val posted = h.resultNotifications().single()
        assertEquals(h.context.getString(R.string.notification_upload_complete), h.title(posted.notification))
        assertTrue(posted.notification.actions.isNullOrEmpty())
    }

    @Test
    fun cancelWhileTheResultIsBeingRecorded_isNotSplit() {
        val scope = CoroutineScope(Dispatchers.Default)
        lateinit var job: kotlinx.coroutines.Job
        h.history.onAdd = { job.cancel() }
        val queue = h.queueFile("a.jpg", "b.jpg")

        job = h.start(queue, scope)
        runBlocking { job.join() }
        scope.cancel()

        assertEquals(1, h.history.state.value.size)
        assertFalse(
            h.history.state.value
                .single()
                .cancelled,
        )
        assertEquals(1, h.resultNotifications().size)
        assertEquals(
            h.context.getString(R.string.notification_upload_complete),
            h.title(h.resultNotifications().single().notification),
        )
        assertTrue(h.retryFiles().isEmpty())
    }

    @Test
    fun cancelDuringAVerificationDownload_leavesTheItemPending_withoutARecord() {
        h.nextcloud = false
        h.verifyByDownload = true
        h.holdMethod = "GET"
        val queue = h.queueFile("a.jpg")

        h.runAndCancelWhenHeld(queue)

        assertEquals(listOf("a.jpg"), UploadCancelHarness.names(h.retryFiles().single()))
        assertTrue(h.uploadedIds().isEmpty())
        assertTrue(h.verified.current.isEmpty())
        assertEquals(
            1,
            h.history.state.value
                .single()
                .failedCount,
        )
    }

    @Test
    fun finalizationOnTheWorkerDoesNotRunWhenTheStopIsNotAUserCancel() {
        h.userCancelled = { false }
        h.holdPutNumber(3)
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")

        h.runAndCancelWhenHeld(queue)

        // A system stop (constraints, foreground time limit, preemption ...): WorkManager re-runs it.
        assertTrue(queue.exists())
        assertEquals(listOf(true, true, false), UploadCancelHarness.markers(queue))
        assertFalse(JSONObject(queue.readText()).has("interruptions"))
        assertTrue(h.retryFiles().isEmpty())
        assertTrue(
            h.history.state.value
                .isEmpty(),
        )
        assertTrue(h.resultNotifications().isEmpty())
    }

    @Test
    fun aSystemStoppedRun_isContinuedWithoutResendingFinishedItems() {
        h.userCancelled = { false }
        h.holdPutNumber(2)
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")
        h.runAndCancelWhenHeld(queue)
        h.holdPutNumber(null)
        h.userCancelled = null
        h.requests.clear()

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("b.jpg", "c.jpg"), h.putNames())
        assertEquals(
            3,
            (result as ListenableWorker.Result.Success).outputData.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1),
        )
        assertFalse(
            h.history.state.value
                .any { it.cancelled },
        )
    }
}
