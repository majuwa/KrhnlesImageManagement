package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.impl.StartStopToken
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkGenerationalId
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.download.downloadWorkRequest
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.worker.parseDownloadQueue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
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
 * End to end with a real (test) WorkManager and no `userCancelled` seam: WorkManager must report
 * `STOP_REASON_CANCELLED_BY_APP` to the running [de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadWorker]
 * when the user cancels it (the Cancel action of the progress notification is WorkManager's
 * `createCancelPendingIntent`, which ends in the same `cancelWorkById`), and every other stop reason
 * must keep the queue for the re-run.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadWorkerCancelWorkManagerTest {
    private val h = DownloadHarness()
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

    /** Photo b is served slowly: the worker stays inside its body. */
    private fun serveStalled(name: String) {
        h.responses["/remote.php/dav/files/u/Photos/Summer/$name"] = {
            MockResponse()
                .setBody(Buffer().write(DownloadHarness.imageHeader(name) + ByteArray(5_000)))
                .setHeader("Content-Type", "image/jpeg")
                .throttleBody(64, 1, TimeUnit.SECONDS)
        }
    }

    /** Enqueues the queue as the app does and waits until photo b is being written. */
    private fun startAndWaitForStalledPhoto(queue: File): UUID {
        h.serve("a.jpg")
        serveStalled("b.jpg")
        h.serve("c.jpg")
        val request = downloadWorkRequest(queue)
        runBlocking { workManager.enqueue(request).await() }
        // The request needs a network (NetworkType.CONNECTED); the test scheduler holds it until told.
        checkNotNull(WorkManagerTestInitHelper.getTestDriver(h.context)).setAllConstraintsMet(request.id)
        waitUntil { state(request.id) == WorkInfo.State.RUNNING }
        waitUntil { FakeMediaStoreProvider.inserted.size >= 2 }
        return request.id
    }

    @Test
    fun `a real user cancel is recognized by the real rule and the queue is finalized`() {
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")
        val id = startAndWaitForStalledPhoto(queue)

        runBlocking { workManager.cancelWorkById(id).await() }

        waitUntil { state(id) == WorkInfo.State.CANCELLED }
        waitUntil { h.resultNotifications().isNotEmpty() }
        val posted = h.resultNotifications().single()
        assertEquals("Saving cancelled", h.title(posted.notification))
        assertEquals("1 of 3 photos saved before cancelling.", h.text(posted.notification))
        assertFalse(queue.exists())
        assertEquals(listOf("a.jpg"), h.published().map { it.displayName })
        assertEquals("the unfinished item was discarded", 1, FakeMediaStoreProvider.inserted.size)
    }

    @Test
    fun `a constraint stop is not a cancel and keeps the queue for the re-run`() {
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")
        val id = startAndWaitForStalledPhoto(queue)

        // WorkManager's own stop entry point: the public API cannot simulate a system stop.
        val processor = WorkManagerImpl.getInstance(h.context).processor
        val token = StartStopToken(WorkGenerationalId(id.toString(), 0))
        val stopped =
            processor.stopForegroundWork(token, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY) ||
                processor.stopWork(token, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        assertTrue(stopped)

        waitUntil { state(id) == WorkInfo.State.ENQUEUED || state(id) == WorkInfo.State.RUNNING }
        assertTrue(queue.exists())
        val saved = checkNotNull(parseDownloadQueue(queue.readText()))
        assertEquals(listOf(true, false, false), saved.entries.map { it.downloaded })
        assertEquals(0, saved.interruptions)
        assertTrue(h.resultNotifications().isEmpty())
        assertEquals(listOf("a.jpg"), h.published().map { it.displayName })
    }
}
