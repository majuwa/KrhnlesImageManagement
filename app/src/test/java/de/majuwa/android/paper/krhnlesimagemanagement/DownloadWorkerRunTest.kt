package de.majuwa.android.paper.krhnlesimagemanagement

import android.system.ErrnoException
import android.system.OsConstants
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.work.ListenableWorker
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriter
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.MAX_DOWNLOAD_RUN_ATTEMPTS_BACKSTOP
import de.majuwa.android.paper.krhnlesimagemanagement.worker.parseDownloadQueue
import de.majuwa.android.paper.krhnlesimagemanagement.worker.queueTempFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** The real [DownloadWorker] against MockWebServer and the fake MediaStore provider. */
@RunWith(RobolectricTestRunner::class)
class DownloadWorkerRunTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val h = DownloadHarness()

    @Before
    fun setUp() = h.setUp()

    @After
    fun tearDown() = h.tearDown()

    private fun assertSuccess(result: ListenableWorker.Result) =
        assertTrue(result.toString(), result is ListenableWorker.Result.Success)

    @Test
    fun `success saves every photo with path name type and bytes and deletes the queue`() {
        h.serve("a.jpg", byteArrayOf(1, 2, 3))
        h.serve("b b.PNG", byteArrayOf(4, 5), "image/png")
        h.serve("c.heic", byteArrayOf(6), "application/octet-stream")
        val queue = h.queueFile("a.jpg", "b b.PNG", "c.heic")

        assertSuccess(h.run(queue))

        val items = h.published().sortedBy { it.displayName }
        assertEquals(listOf("a.jpg", "b b.png", "c.heic"), items.map { it.displayName })
        assertEquals(listOf("image/jpeg", "image/png", "image/heic"), items.map { it.mimeType })
        assertTrue(items.all { it.relativePath == "Pictures/KroehnSync/Summer/" })
        assertEquals(listOf(1, 2, 3), items[0].bytes.drop(DownloadHarness.imageHeader("a.jpg").size).map { it.toInt() })
        assertEquals(listOf(4, 5), items[1].bytes.drop(DownloadHarness.imageHeader("b.png").size).map { it.toInt() })
        assertEquals(0, FakeMediaStoreProvider.inserted.values.count { it.pending })
        assertFalse(queue.exists())
        val posted = h.resultNotifications().single()
        assertEquals(queue.name, posted.tag)
        assertEquals("Photos saved", h.title(posted.notification))
        assertEquals("3 photos saved.", h.text(posted.notification))
        assertTrue(h.requests.all { it.startsWith("GET") })
    }

    @Test
    fun `404 and 500 save nothing for those photos and report counts and reasons`() {
        h.serve("ok.jpg")
        h.serveStatus("gone.jpg", 404)
        h.serveStatus("broken.jpg", 500)
        val queue = h.queueFile("gone.jpg", "ok.jpg", "broken.jpg")

        assertSuccess(h.run(queue))

        assertEquals(listOf("ok.jpg"), h.published().map { it.displayName })
        assertEquals(1, FakeMediaStoreProvider.inserted.size)
        val text = h.text(h.resultNotifications().single().notification)
        assertTrue(text, text.startsWith("1 photo saved. 2 photos could not be saved."))
        assertTrue(text, text.contains("not found on the server"))
        assertTrue(text, text.contains("server reported an error"))
    }

    @Test
    fun `an announced size above the cap saves nothing and the next photo is still saved`() {
        h.maxBytes = 10
        h.serve("big.jpg", ByteArray(100))
        h.serve("small.jpg", ByteArray(5))
        val queue = h.queueFile("big.jpg", "small.jpg")

        assertSuccess(h.run(queue))

        assertEquals(listOf("small.jpg"), h.published().map { it.displayName })
        assertEquals(1, FakeMediaStoreProvider.inserted.size)
        assertTrue(h.text(h.resultNotifications().single().notification).contains("too large"))
    }

    @Test
    fun `a streamed size above the cap discards the partial item and the next photo is still saved`() {
        h.maxBytes = 1_000
        h.responses["/remote.php/dav/files/u/Photos/Summer/big.jpg"] = {
            MockResponse()
                .setChunkedBody(
                    okio.Buffer().write(DownloadHarness.imageHeader("big.jpg") + ByteArray(5_000)),
                    500,
                ).setHeader("Content-Type", "image/jpeg")
        }
        h.serve("small.jpg", ByteArray(5))
        val queue = h.queueFile("big.jpg", "small.jpg")

        assertSuccess(h.run(queue))

        assertEquals(listOf("small.jpg"), h.published().map { it.displayName })
        assertEquals("the partial item was discarded", 1, FakeMediaStoreProvider.deletes.size)
        assertEquals(1, FakeMediaStoreProvider.inserted.size)
    }

    @Test
    fun `videos and svg are reported as unsupported without a request`() {
        val queue = h.queueFile("clip.mp4", "drawing.svg", "scan.tiff", "noextension")

        assertSuccess(h.run(queue))

        assertTrue(h.requests.isEmpty())
        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
        val posted = h.resultNotifications().single().notification
        assertEquals("Saving failed", h.title(posted))
        assertTrue(h.text(posted).contains("Only photos can be saved"))
    }

    @Test
    fun `unsafe hrefs send no request`() {
        val base = "/remote.php/dav/files/u/Photos/Summer"
        val queue =
            h.queueFile(
                hrefs =
                    listOf(
                        "$base/../x.jpg",
                        "$base/%2e%2e/x.jpg",
                        "$base/a%2Fb.jpg",
                        "/remote.php/dav/files/u/Photos/Other/x.jpg",
                        "${h.other.url("/x.jpg")}",
                    ),
            )

        assertSuccess(h.run(queue))

        assertTrue(h.requests.isEmpty())
        assertEquals(0, h.other.requestCount)
        assertTrue(h.text(h.resultNotifications().single().notification).contains("did not look like a photo"))
    }

    @Test
    fun `a redirect is not followed and credentials stay on the configured origin`() {
        h.responses["/remote.php/dav/files/u/Photos/Summer/r.jpg"] = {
            MockResponse().setResponseCode(302).setHeader("Location", h.other.url("/stolen.jpg").toString())
        }
        val queue = h.queueFile("r.jpg")

        assertSuccess(h.run(queue))

        assertEquals(0, h.other.requestCount)
        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
        assertTrue(h.text(h.resultNotifications().single().notification).contains("did not look like a photo"))
    }

    @Test
    fun `html instead of an image is rejected`() {
        h.serve("page.jpg", "<html>login</html>".toByteArray(), "text/html")
        val queue = h.queueFile("page.jpg")

        assertSuccess(h.run(queue))

        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
    }

    @Test
    fun `401 stops the batch after the first request`() {
        h.serveStatus("a.jpg", 401)
        h.serve("b.jpg")
        h.serve("c.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")

        assertSuccess(h.run(queue))

        assertEquals(1, h.requests.size)
        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
        val text = h.text(h.resultNotifications().single().notification)
        assertTrue(text, text.startsWith("3 photos could not be saved."))
        assertTrue(text, text.contains("refused access"))
    }

    @Test
    fun `five consecutive 503 stop the batch`() {
        val names = (1..8).map { "p$it.jpg" }
        names.forEach { h.serveStatus(it, 503) }
        val queue = h.queueFile(*names.toTypedArray())

        assertSuccess(h.run(queue))

        assertEquals(5, h.requests.size)
        assertTrue(h.text(h.resultNotifications().single().notification).startsWith("8 photos could not be saved."))
    }

    @Test
    fun `another failure in between resets the consecutive count`() {
        (1..4).forEach { h.serveStatus("s$it.jpg", 503) }
        h.serveStatus("gone.jpg", 404)
        (5..8).forEach { h.serveStatus("s$it.jpg", 503) }
        val names = (1..4).map { "s$it.jpg" } + "gone.jpg" + (5..8).map { "s$it.jpg" }
        val queue = h.queueFile(*names.toTypedArray())

        assertSuccess(h.run(queue))

        assertEquals(9, h.requests.size)
    }

    @Test
    fun `a changed server url stops the batch before the next request`() {
        h.serve("a.jpg")
        h.responses["/remote.php/dav/files/u/Photos/Summer/b.jpg"] = {
            h.credentials.setConfig(h.config.copy(url = "https://other.example/dav/"))
            MockResponse()
                .setBody(
                    Buffer().write(DownloadHarness.imageHeader("b.jpg") + "b".toByteArray()),
                ).setHeader("Content-Type", "image/jpeg")
        }
        h.serve("c.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")

        assertSuccess(h.run(queue))

        assertEquals(2, h.requests.size)
        assertEquals(listOf("a.jpg", "b.jpg"), h.published().map { it.displayName }.sorted())
        val text = h.text(h.resultNotifications().single().notification)
        assertTrue(text, text.contains("server settings changed"))
    }

    @Test
    fun `a logout stops the batch before the next request`() {
        h.responses["/remote.php/dav/files/u/Photos/Summer/a.jpg"] = {
            h.credentials.setConfig(WebDavConfig())
            MockResponse()
                .setBody(
                    Buffer().write(DownloadHarness.imageHeader("a.jpg") + "a".toByteArray()),
                ).setHeader("Content-Type", "image/jpeg")
        }
        h.serve("b.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg")

        assertSuccess(h.run(queue))

        assertEquals(1, h.requests.size)
    }

    @Test
    fun `a missing configuration sends no request at all`() {
        h.credentials.setConfig(WebDavConfig())
        h.serve("a.jpg")
        val queue = h.queueFile("a.jpg")

        assertSuccess(h.run(queue))

        assertTrue(h.requests.isEmpty())
        assertFalse(queue.exists())
    }

    @Test
    fun `a changed base folder rejects the queued hrefs`() {
        h.serve("a.jpg")
        h.credentials.setConfig(h.config.copy(baseFolder = "Elsewhere"))
        val queue = h.queueFile("a.jpg")

        assertSuccess(h.run(queue))

        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `a full device stops the batch and leaves nothing partial`() {
        h.serve("a.jpg")
        h.serve("b.jpg")
        h.writer =
            LocalMediaWriter(h.context) { _, _ ->
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("write", ErrnoException("write", OsConstants.ENOSPC))
                }
            }
        val queue = h.queueFile("a.jpg", "b.jpg")

        assertSuccess(h.run(queue))

        assertEquals(1, h.requests.size)
        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
        assertTrue(h.text(h.resultNotifications().single().notification).contains("no storage space left"))
    }

    @Test
    fun `an announced size that does not fit the free space fails without writing`() {
        h.freeBytes = { 10L }
        h.serve("a.jpg", ByteArray(1_000))
        val queue = h.queueFile("a.jpg")

        assertSuccess(h.run(queue))

        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
    }

    @Test
    fun `a failing media insert is a write failure and the next photo is tried`() {
        h.serve("a.jpg")
        FakeMediaStoreProvider.insertFailure = IllegalStateException("provider down")
        val queue = h.queueFile("a.jpg")

        assertSuccess(h.run(queue))

        assertTrue(h.text(h.resultNotifications().single().notification).contains("could not be written"))
    }

    @Test
    fun `a queue path outside the app queue directory does nothing`() {
        h.serve("a.jpg")
        val foreign = File(tmp.root, "download_queue_1_0f8fad5b-d9cb-469f-a165-70867728950e.json")
        foreign.writeText(
            """{"serverUrl":"${h.config.url}","albumHref":"${h.albumHref}","entries":[{"href":"${h.hrefOf(
                "a.jpg",
            )}"}]}""",
        )
        val upload = File(h.context.noBackupFilesDir, "upload_queue_1_0f8fad5b-d9cb-469f-a165-70867728950e.json")
        upload.writeText(foreign.readText())

        assertSuccess(h.run(foreign))
        assertSuccess(h.run(upload))

        assertTrue(h.requests.isEmpty())
        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
        assertTrue(foreign.exists())
        assertTrue(h.resultNotifications().isEmpty())
    }

    @Test
    fun `a malformed queue file is deleted and nothing is requested`() {
        val queue = File(h.context.noBackupFilesDir, "download_queue_1_0f8fad5b-d9cb-469f-a165-70867728950e.json")
        queue.writeText("""{"serverUrl":1}""")

        assertSuccess(h.run(queue))

        assertFalse(queue.exists())
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `the client factory receives the pin of the server and a changed pin rebuilds the client`() {
        val httpsConfig = WebDavConfig("https://cloud.example:8443/remote.php/dav/files/u/", "u", "p", "Photos")
        h.config = httpsConfig
        h.credentials.setConfig(httpsConfig)
        val pin = PinnedCertificate(ServerOrigin("cloud.example", 8443), "ab".repeat(32).decodeHex())
        val certificate = ServerCertificate(pin.origin, pin.sha256, "CN=a", "CN=b", 0L, Long.MAX_VALUE / 2)
        val mock = WebDavConfig(h.server.url("/").toString(), "u", "p")
        // The server under test speaks plain HTTP; only the pins handed to the factory are observed.
        h.clientFactory = { _, p ->
            h.seenPins += p
            if (h.seenPins.size == 1) runBlocking { h.trusted.trust(certificate) }
            WebDavClient(mock, p)
        }
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")

        assertSuccess(h.run(queue))

        assertEquals(listOf<PinnedCertificate?>(null, pin), h.seenPins)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `a trusted pin is handed to the first client`() {
        val httpsConfig = WebDavConfig("https://cloud.example:8443/remote.php/dav/files/u/", "u", "p", "Photos")
        h.config = httpsConfig
        h.credentials.setConfig(httpsConfig)
        val pin = PinnedCertificate(ServerOrigin("cloud.example", 8443), "ab".repeat(32).decodeHex())
        runBlocking {
            h.trusted.trust(ServerCertificate(pin.origin, pin.sha256, "CN=a", "CN=b", 0L, Long.MAX_VALUE / 2))
        }
        val mock = WebDavConfig(h.server.url("/").toString(), "u", "p")
        h.clientFactory = { _, p ->
            h.seenPins += p
            WebDavClient(mock, p)
        }

        assertSuccess(h.run(h.queueFile("a.jpg")))

        assertEquals(listOf<PinnedCertificate?>(pin), h.seenPins)
    }

    private fun chunked(
        name: String,
        size: Int,
    ): (okhttp3.mockwebserver.RecordedRequest) -> MockResponse =
        {
            MockResponse()
                .setChunkedBody(Buffer().write(DownloadHarness.imageHeader(name) + ByteArray(size)), 64 * 1024)
                .setHeader("Content-Type", "image/jpeg")
        }

    private fun saved(queue: File) = checkNotNull(parseDownloadQueue(queue.readText()))

    @Test
    fun `a connection failure without a validated network pauses the run and keeps the queue`() {
        h.networkAvailable = { false }
        h.serve("a.jpg")
        h.responses["/remote.php/dav/files/u/Photos/Summer/b.jpg"] = {
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        h.serve("c.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")

        val result = h.run(queue)

        assertTrue(result.toString(), result is ListenableWorker.Result.Retry)
        assertTrue(queue.exists())
        assertEquals(listOf(true, false, false), saved(queue).entries.map { it.downloaded })
        assertEquals("a lost network is not an interruption", 0, saved(queue).interruptions)
        assertNull(saved(queue).pendingItem)
        assertTrue(h.resultNotifications().isEmpty())
        assertEquals(listOf("a.jpg"), h.published().map { it.displayName })
        assertTrue("c.jpg must not be requested", h.requests.none { it.endsWith("c.jpg") })

        // The network is back: the re-run saves the rest.
        h.networkAvailable = { true }
        h.serve("b.jpg")
        assertSuccess(h.run(queue))
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), h.published().map { it.displayName }.sorted())
        assertEquals("3 photos saved.", h.text(h.resultNotifications().single().notification))
    }

    @Test
    fun `a connection failure with a network is a failed photo and the batch goes on`() {
        h.networkAvailable = { true }
        h.responses["/remote.php/dav/files/u/Photos/Summer/a.jpg"] = {
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        h.serve("b.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg")

        assertSuccess(h.run(queue))

        assertFalse(queue.exists())
        assertEquals(listOf("b.jpg"), h.published().map { it.displayName })
        assertTrue(h.text(h.resultNotifications().single().notification).contains("could not be reached"))
    }

    @Test
    fun `a missing network does not pause for ever`() {
        h.networkAvailable = { false }
        h.runAttemptCount = MAX_DOWNLOAD_RUN_ATTEMPTS_BACKSTOP
        h.responses["/remote.php/dav/files/u/Photos/Summer/a.jpg"] = {
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        val queue = h.queueFile("a.jpg")

        assertSuccess(h.run(queue))

        assertFalse(queue.exists())
        assertTrue(h.text(h.resultNotifications().single().notification).contains("could not be reached"))
    }

    @Test
    fun `a system stop during a refused foreground is not an interruption`() {
        h.foregroundFailsFrom = 0
        h.stopped = { true }
        h.serve("a.jpg")
        val queue = h.queueFile("a.jpg")

        assertThrows(CancellationException::class.java) { h.run(queue) }

        assertTrue(queue.exists())
        assertEquals(0, saved(queue).interruptions)
        assertTrue(h.resultNotifications().isEmpty())
    }

    @Test
    fun `the item record is kept while the item could not be deleted and the next run deletes it`() {
        h.maxBytes = 1_000
        h.responses["/remote.php/dav/files/u/Photos/Summer/big.jpg"] = chunked("big.jpg", 5_000)
        FakeMediaStoreProvider.deleteFailure = IllegalStateException("provider")
        h.foregroundFailsFrom = 1
        val queue = h.queueFile("big.jpg", "b.jpg")

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Retry)
        val leftover = FakeMediaStoreProvider.inserted.keys.single()
        assertEquals("the record still points at the undeleted item", leftover, saved(queue).pendingItem)

        FakeMediaStoreProvider.deleteFailure = null
        h.foregroundFailsFrom = null
        h.run(queue)

        assertTrue(FakeMediaStoreProvider.deletes.contains(leftover))
        assertNull(FakeMediaStoreProvider.inserted[leftover])
    }

    @Test
    fun `the item record is cleared once the item was deleted`() {
        h.maxBytes = 1_000
        h.responses["/remote.php/dav/files/u/Photos/Summer/big.jpg"] = chunked("big.jpg", 5_000)
        h.foregroundFailsFrom = 1
        val queue = h.queueFile("big.jpg", "b.jpg")

        h.run(queue)

        assertNull(saved(queue).pendingItem)
        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
    }

    @Test
    fun `a provider that fails with any runtime error is a write failure not a connection failure`() {
        h.serve("a.jpg")
        FakeMediaStoreProvider.insertFailure = UnsupportedOperationException("provider")
        val queue = h.queueFile("a.jpg")

        assertSuccess(h.run(queue))

        val text = h.text(h.resultNotifications().single().notification)
        assertTrue(text, text.contains("could not be written"))
        assertFalse(text, text.contains("could not be reached"))
    }

    @Test
    fun `a body of unknown length stops when the free space runs out while streaming`() {
        val calls = AtomicInteger()
        // Enough for the up-front check, then nothing left.
        h.freeBytes = { if (calls.getAndIncrement() == 0) 250L * 1024 * 1024 else 0L }
        h.responses["/remote.php/dav/files/u/Photos/Summer/a.jpg"] = chunked("a.jpg", 10 * 1024 * 1024)
        h.serve("b.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg")

        assertSuccess(h.run(queue))

        assertTrue(h.published().isEmpty())
        assertTrue("the partial item was discarded", FakeMediaStoreProvider.inserted.isEmpty())
        assertEquals("a full device stops the batch", 1, h.requests.size)
        assertTrue(h.text(h.resultNotifications().single().notification).contains("no storage space left"))
    }

    @Test
    fun `a body of unknown length needs more than the margin free before anything is written`() {
        h.freeBytes = { 100L * 1024 * 1024 }
        h.responses["/remote.php/dav/files/u/Photos/Summer/a.jpg"] = chunked("a.jpg", 1_000)
        val queue = h.queueFile("a.jpg")

        assertSuccess(h.run(queue))

        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
        assertTrue(h.text(h.resultNotifications().single().notification).contains("no storage space left"))
    }

    private fun serveRaw(
        name: String,
        body: ByteArray,
        type: String = "image/jpeg",
    ) {
        h.responses["/remote.php/dav/files/u/Photos/Summer/$name"] = {
            MockResponse().setBody(Buffer().write(body)).setHeader("Content-Type", type)
        }
    }

    @Test
    fun `a body that is not the announced image type is rejected before anything is stored`() {
        serveRaw("html.jpg", "<html>login</html>".toByteArray())
        serveRaw("png-as.jpg", DownloadHarness.imageHeader("x.png") + ByteArray(10))
        serveRaw("empty.jpg", ByteArray(0))
        serveRaw("avif-as.heic", DownloadHarness.imageHeader("x.avif") + ByteArray(10), "image/heic")
        h.serve("ok.jpg")
        val queue = h.queueFile("html.jpg", "png-as.jpg", "empty.jpg", "avif-as.heic", "ok.jpg")

        assertSuccess(h.run(queue))

        assertEquals(listOf("ok.jpg"), h.published().map { it.displayName })
        assertEquals("nothing was ever inserted for the rejected bodies", 1, FakeMediaStoreProvider.inserted.size)
        assertTrue(FakeMediaStoreProvider.deletes.isEmpty())
        assertTrue(h.text(h.resultNotifications().single().notification).contains("did not look like a photo"))
    }

    @Test
    fun `a queue bound to one account stops when the same server has another user`() {
        h.serve("a.jpg")
        val queue = h.queueFile("a.jpg")
        h.credentials.setConfig(h.config.copy(username = "mallory", password = "other"))

        assertSuccess(h.run(queue))

        assertTrue("no request with another account's credentials", h.requests.isEmpty())
        assertTrue(h.text(h.resultNotifications().single().notification).contains("server settings changed"))
    }

    @Test
    fun `an account change in the middle of a batch stops the rest`() {
        h.serve("a.jpg")
        h.responses["/remote.php/dav/files/u/Photos/Summer/b.jpg"] = {
            h.credentials.setConfig(h.config.copy(username = "mallory"))
            MockResponse()
                .setBody(Buffer().write(DownloadHarness.imageHeader("b.jpg") + "b".toByteArray()))
                .setHeader("Content-Type", "image/jpeg")
        }
        h.serve("c.jpg")
        val queue = h.queueFile("a.jpg", "b.jpg", "c.jpg")

        assertSuccess(h.run(queue))

        assertEquals(2, h.requests.size)
    }

    @Test
    fun `a queue file without an account is bound to the server url only`() {
        h.serve("a.jpg")
        val queue = h.queueFile("a.jpg", account = null)

        assertSuccess(h.run(queue))

        assertEquals(listOf("a.jpg"), h.published().map { it.displayName })
    }

    @Test
    fun `an item that cannot be recorded in the queue file is discarded before any byte is written`() {
        h.serve("a.jpg")
        val queue = h.queueFile("a.jpg")
        // The temp file of the atomic rewrite cannot be created: a directory sits in its place.
        assertTrue(queueTempFile(queue).mkdir())

        assertSuccess(h.run(queue))

        assertTrue(h.published().isEmpty())
        assertEquals("the unrecorded item was discarded", 1, FakeMediaStoreProvider.deletes.size)
        assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
        assertTrue(h.text(h.resultNotifications().single().notification).contains("could not be written"))
    }

    @Test
    fun `saving never touches the uploaded markers or the verified records`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            fun store(name: String): DataStore<Preferences> =
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, name) })
            val uploaded = UploadedPhotosStore(store("uploaded.preferences_pb"))
            val verified = VerifiedUploadsStore(store("verified.preferences_pb"))
            val record =
                VerifiedUpload(
                    id = 7L,
                    uri = "content://media/external_primary/images/media/7",
                    serverUrl = h.config.url,
                    folder = "Summer",
                    fileName = "a.jpg",
                    size = 1L,
                    dateModified = 2L,
                    verifiedAt = 3L,
                )
            runBlocking {
                uploaded.markAsUploaded(setOf(7L, 8L))
                verified.recordVerified(record)
            }
            h.serve("a.jpg")

            assertSuccess(h.run(h.queueFile("a.jpg")))

            runBlocking {
                assertEquals(setOf(7L, 8L), uploaded.uploadedPhotoIds.first())
                assertEquals(listOf(record), verified.records.first())
            }
            assertEquals(1, h.published().size)
        } finally {
            scope.cancel()
        }
    }
}
