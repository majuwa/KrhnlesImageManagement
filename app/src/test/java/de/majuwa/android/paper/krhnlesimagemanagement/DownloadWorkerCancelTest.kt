package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.work.ListenableWorker
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriter
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.worker.MAX_DOWNLOAD_RUN_ATTEMPTS
import de.majuwa.android.paper.krhnlesimagemanagement.worker.MAX_DOWNLOAD_RUN_ATTEMPTS_BACKSTOP
import de.majuwa.android.paper.krhnlesimagemanagement.worker.parseDownloadQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The real [DownloadWorker] cancelled by the user, stopped by the system, resumed after a killed
 * run, and interrupted by a refused foreground service. The user-cancel decision is the
 * `userCancelled` seam; the real rule is `STOP_REASON_CANCELLED_BY_APP`.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadWorkerCancelTest {
    private val h = DownloadHarness()

    @Before
    fun setUp() = h.setUp()

    @After
    fun tearDown() = h.tearDown()

    /** Serves [name] slowly (64 bytes a second): the body stalls while the worker reads it. */
    private fun serveStalled(name: String) {
        h.responses["/remote.php/dav/files/u/Photos/Summer/$name"] = {
            MockResponse()
                .setBody(Buffer().write(DownloadHarness.imageHeader("b.jpg") + ByteArray(5_000)))
                .setHeader("Content-Type", "image/jpeg")
                .throttleBody(64, 1, TimeUnit.SECONDS)
        }
    }

    /** Runs the worker, cancels it once the stalled photo is being written, returns the elapsed millis. */
    private fun runAndCancelMidBody(queue: File): Long {
        val scope = CoroutineScope(Dispatchers.Default)
        val started = System.nanoTime()
        val job = scope.launch { h.buildWorker(queue).doWork() }
        runBlocking {
            withTimeout(30_000) { while (FakeMediaStoreProvider.inserted.size < 2) delay(20) }
            delay(200)
            job.cancel()
            withTimeout(10_000) { job.join() }
        }
        scope.cancel()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    @Test
    fun `a user cancel mid body discards the item keeps earlier photos and reports the counts`() {
        h.userCancelled = { true }
        h.serve("a.jpg")
        serveStalled("b.jpg")
        h.serve("c.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")

        runAndCancelMidBody(queue)

        assertEquals(listOf("a.jpg"), h.published().map { it.displayName })
        assertEquals("only the saved photo is left", 1, FakeMediaStoreProvider.inserted.size)
        assertFalse(queue.exists())
        val posted = h.resultNotifications().single()
        assertEquals("Saving cancelled", h.title(posted.notification))
        assertEquals("1 of 3 photos saved before cancelling.", h.text(posted.notification))
        assertEquals(queue.name, posted.tag)
        assertEquals(2, h.requests.size)
    }

    @Test
    fun `a system stop keeps the queue with its markers and the re-run saves only the rest`() {
        h.userCancelled = { false }
        h.serve("a.jpg")
        serveStalled("b.jpg")
        h.serve("c.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")

        runAndCancelMidBody(queue)

        assertTrue(queue.exists())
        assertTrue(h.resultNotifications().isEmpty())
        assertEquals(listOf("a.jpg"), h.published().map { it.displayName })
        assertEquals("the unfinished item was discarded", 1, FakeMediaStoreProvider.inserted.size)
        val saved = checkNotNull(parseDownloadQueue(queue.readText()))
        assertEquals(listOf(true, false, false), saved.entries.map { it.downloaded })
        assertNull(saved.pendingItem)

        h.requests.clear()
        h.serve("b.jpg")
        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf(
                "GET /remote.php/dav/files/u/Photos/Summer/b.jpg",
                "GET /remote.php/dav/files/u/Photos/Summer/c.jpg",
            ),
            h.requests,
        )
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), h.published().map { it.displayName }.sorted())
        assertEquals("3 photos saved.", h.text(h.resultNotifications().single().notification))
        assertFalse(queue.exists())
    }

    @Test
    fun `a pending item left by a killed run is discarded on resume`() {
        val leftover = LocalMediaWriter(h.context).insertPending("Pictures/KroehnSync/Summer/", "b.jpg", "image/jpeg")
        h.serve("b.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg", pendingItem = leftover.uriString, downloaded = setOf(0))

        h.run(queue)

        assertTrue(FakeMediaStoreProvider.deletes.contains(leftover.uriString))
        assertNull(FakeMediaStoreProvider.inserted[leftover.uriString])
        assertEquals(listOf("b.jpg"), h.published().map { it.displayName })
        assertEquals(1, h.requests.size)
    }

    @Test
    fun `a leftover that is already published is never deleted`() {
        val published =
            LocalMediaWriter(
                h.context,
            ).insertPending("Pictures/KroehnSync/Summer/", "keep.jpg", "image/jpeg")
        published.publish()
        h.serve("b.jpg")
        val queue = h.queueFile("b.jpg", pendingItem = published.uriString)

        h.run(queue)

        assertNotNull(FakeMediaStoreProvider.inserted[published.uriString])
        assertFalse(FakeMediaStoreProvider.deletes.contains(published.uriString))
    }

    @Test
    fun `a refused foreground service retries and counts the interruption`() {
        h.foregroundFailsFrom = 0
        h.serve("a.jpg")
        val queue = h.queueFile("a.jpg")

        val result = h.run(queue)

        assertTrue(result.toString(), result is ListenableWorker.Result.Retry)
        assertTrue(h.requests.isEmpty())
        assertEquals(1, checkNotNull(parseDownloadQueue(queue.readText())).interruptions)
        assertTrue(h.resultNotifications().isEmpty())
    }

    @Test
    fun `a refusal between photos keeps what was saved`() {
        h.foregroundFailsFrom = 1
        h.serve("a.jpg")
        h.serve("b.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg")

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(listOf("a.jpg"), h.published().map { it.displayName })
        val saved = checkNotNull(parseDownloadQueue(queue.readText()))
        assertEquals(listOf(true, false), saved.entries.map { it.downloaded })
        assertEquals(1, saved.interruptions)
    }

    @Test
    fun `the interruption cap gives up with the interrupted reason`() {
        h.foregroundFailsFrom = 0
        h.serve("a.jpg")
        val queue = h.queueFile("a.jpg", interruptions = MAX_DOWNLOAD_RUN_ATTEMPTS - 1)

        val result = h.run(queue)

        assertTrue(result.toString(), result is ListenableWorker.Result.Success)
        assertFalse(queue.exists())
        val posted = h.resultNotifications().single().notification
        assertEquals("Saving failed", h.title(posted))
        assertTrue(h.text(posted), h.text(posted).contains("stopped saving repeatedly"))
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `the run attempt backstop gives up even with a fresh counter`() {
        h.foregroundFailsFrom = 0
        h.runAttemptCount = MAX_DOWNLOAD_RUN_ATTEMPTS_BACKSTOP
        val queue = h.queueFile("a.jpg")

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertFalse(queue.exists())
        assertTrue(h.text(h.resultNotifications().single().notification).contains("stopped saving repeatedly"))
    }

    @Test
    fun `earlier saved photos are counted in the final result`() {
        h.serve("b.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg", downloaded = setOf(0))

        h.run(queue)

        assertEquals("2 photos saved.", h.text(h.resultNotifications().single().notification))
        assertEquals(1, h.requests.size)
    }
}
