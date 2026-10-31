package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.impl.StartStopToken
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkGenerationalId
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.worker.CancelUploadReceiver
import de.majuwa.android.paper.krhnlesimagemanagement.worker.handleCancel
import de.majuwa.android.paper.krhnlesimagemanagement.worker.uploadConstraints
import de.majuwa.android.paper.krhnlesimagemanagement.worker.uploadWorkRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End to end with a real (test) WorkManager and no seam: WorkManager must report
 * `STOP_REASON_CANCELLED_BY_APP` to the running [de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker]
 * before its coroutine is cancelled (the ordering design D2 relies on), and every other stop reason
 * must keep the queue for the re-run.
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerCancelWorkManagerTest {
    private val h = UploadCancelHarness()
    private lateinit var workManager: WorkManager

    /** WorkManager's executor of this test; shut down in [tearDown] so no threads outlive the test. */
    private val pool = Executors.newFixedThreadPool(4)

    @Before
    fun setUp() {
        h.setUp()
        val config =
            Configuration
                .Builder()
                .setExecutor(pool)
                .setWorkerFactory(h.workerFactory())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(h.context, config)
        workManager = WorkManager.getInstance(h.context)
    }

    @After
    fun tearDown() {
        runBlocking { workManager.cancelAllWork().await() }
        h.tearDown()
        pool.shutdownNow()
    }

    private fun state(id: UUID) = workManager.getWorkInfoById(id).get()?.state

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("condition not reached", condition())
    }

    private fun enqueue(queue: File): UUID {
        val request = uploadWorkRequest(queue, uploadConstraints(wifiOnly = false))
        runBlocking { workManager.enqueue(request).await() }
        return request.id
    }

    @Test
    fun cancelByWorkId_isRecognizedByTheRealRule_andTheQueueIsFinalized() {
        h.holdPutNumber(2)
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")
        val id = enqueue(queue)
        assertTrue("PUT never held", awaitHeld())
        waitUntil { state(id) == WorkInfo.State.RUNNING }

        runBlocking {
            handleCancel(
                Intent().putExtra(CancelUploadReceiver.EXTRA_WORK_ID, id.toString()),
                workManager,
            )
        }

        waitUntil { state(id) == WorkInfo.State.CANCELLED }
        waitUntil {
            h.history.state.value
                .isNotEmpty() && h.resultNotifications().isNotEmpty()
        }
        val retry = h.retryFiles().single()
        assertEquals(listOf("b.jpg", "c.jpg"), UploadCancelHarness.names(retry))
        assertFalse(queue.exists())
        assertEquals(setOf(1L), h.uploadedIds())
        val entry =
            h.history.state.value
                .single()
        assertTrue(entry.cancelled)
        assertEquals(3, entry.photoCount)
        assertEquals(2, entry.failedCount)
        assertEquals(
            "Retry Failed (2)",
            h
                .resultNotifications()
                .single()
                .notification.actions
                .single()
                .title
                .toString(),
        )
    }

    @Test
    fun aForegroundServiceTimeout_isNotACancel_andKeepsTheQueueForTheRerun() {
        h.holdPutNumber(2)
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")
        val id = enqueue(queue)
        assertTrue("PUT never held", awaitHeld())
        waitUntil { state(id) == WorkInfo.State.RUNNING }

        // The system stops the work for its foreground-service time limit; WorkManager re-enqueues it.
        // (WorkManager's own stop entry point: the public API has no way to simulate a system stop.)
        // The re-run is held at its first PUT, so the queue stays as the stopped run left it.
        h.holdPutNumber(1)
        val processor = WorkManagerImpl.getInstance(h.context).processor
        val token = StartStopToken(WorkGenerationalId(id.toString(), 0))
        val stopped =
            processor.stopForegroundWork(token, WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) ||
                processor.stopWork(token, WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)
        assertTrue(stopped)

        waitUntil { state(id) == WorkInfo.State.ENQUEUED }
        assertTrue(queue.exists())
        assertEquals(listOf(true, false, false), UploadCancelHarness.markers(queue))
        assertTrue(h.retryFiles().isEmpty())
        assertTrue(
            h.history.state.value
                .isEmpty(),
        )
        assertTrue(h.resultNotifications().isEmpty())
    }

    private fun awaitHeld(): Boolean = h.awaitHeld(30)
}
