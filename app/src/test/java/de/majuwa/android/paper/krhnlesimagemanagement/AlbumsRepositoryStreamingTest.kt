package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.ALBUMS_READ_TIMEOUT_MS
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.MAX_THUMBNAIL_BYTES
import de.majuwa.android.paper.krhnlesimagemanagement.data.MalformedMultistatusException
import de.majuwa.android.paper.krhnlesimagemanagement.data.PhotoListing
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindEntryLimits
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindListingLimits
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindResponseTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.ThumbnailTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.MEMORIES_DESCRIBE_PATH
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.memoriesAwareDispatcher
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.toAlbumsError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** Streamed, bounded and cancellable album listings of [AlbumsRepository] (issues #27 / #46). */
@RunWith(RobolectricTestRunner::class)
class AlbumsRepositoryStreamingTest {
    private lateinit var server: MockWebServer

    private val album = "/remote.php/dav/files/user/Summer25/"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun createRepo(
        limits: PropfindListingLimits = PropfindListingLimits(),
        maxThumbnailBytes: Long = MAX_THUMBNAIL_BYTES,
        readTimeoutMillis: Long = ALBUMS_READ_TIMEOUT_MS,
    ): AlbumsRepository =
        AlbumsRepository(
            WebDavConfig(
                url = server.url("/remote.php/dav/files/user/").toString(),
                username = "testuser",
                password = "testpass",
            ),
            limits,
            maxThumbnailBytes,
            readTimeoutMillis,
        )

    private fun entry(
        href: String,
        contentType: String? = "image/jpeg",
    ): String =
        buildString {
            append("<d:response><d:href>").append(href).append("</d:href><d:propstat><d:prop>")
            if (contentType == null) {
                append("<d:resourcetype><d:collection/></d:resourcetype>")
            } else {
                append("<d:resourcetype/><d:getcontenttype>").append(contentType).append("</d:getcontenttype>")
                append("<oc:fileid>1</oc:fileid>")
            }
            append("</d:prop></d:propstat></d:response>")
        }

    private fun multistatus(entries: List<String>): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">" +
            entries.joinToString("") +
            "</d:multistatus>"

    private fun albumWithPhotos(count: Int): String =
        multistatus(listOf(entry(album, null)) + (0 until count).map { entry("${album}photo$it.jpg") })

    private fun serve(
        body: String,
        memories: MockResponse = MockResponse().setResponseCode(404),
    ) {
        server.dispatcher =
            memoriesAwareDispatcher(
                MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(body),
                memories,
            )
    }

    private fun listPhotos(
        repo: AlbumsRepository,
        onProgress: (Int) -> Unit = {},
    ): Result<PhotoListing> = runBlocking { repo.listPhotos(album, onProgress) }

    // ── Limits ─────────────────────────────────────────────────────────────

    @Test
    fun `listing stops at the photo cap and is flagged truncated`() {
        serve(albumWithPhotos(8))

        val listing = listPhotos(createRepo(PropfindListingLimits(maxPhotos = 5))).getOrThrow()

        assertEquals(5, listing.photos.size)
        assertEquals("${album}photo4.jpg", listing.photos.last().href)
        assertTrue(listing.isTruncated)
    }

    @Test
    fun `listing with exactly the photo cap is not truncated`() {
        serve(albumWithPhotos(5))

        val listing = listPhotos(createRepo(PropfindListingLimits(maxPhotos = 5))).getOrThrow()

        assertEquals(5, listing.photos.size)
        assertFalse(listing.isTruncated)
    }

    @Test
    fun `listing above the byte cap keeps the complete entries and is truncated`() {
        val body = albumWithPhotos(200)
        serve(body)

        val listing = listPhotos(createRepo(PropfindListingLimits(maxResponseBytes = body.length / 2L))).getOrThrow()

        assertTrue(listing.isTruncated)
        assertTrue(listing.photos.isNotEmpty())
        assertTrue(listing.photos.size < 200)
        // Only complete, in-order entries: no partial entry survives the cut.
        listing.photos.forEachIndexed { i, photo -> assertEquals("${album}photo$i.jpg", photo.href) }
    }

    @Test
    fun `byte cap before any complete entry fails as malformed`() {
        serve(albumWithPhotos(50))

        val failure = listPhotos(createRepo(PropfindListingLimits(maxResponseBytes = 64))).exceptionOrNull()

        assertTrue(failure is MalformedMultistatusException)
        assertEquals(AlbumsError.Unknown, failure?.toAlbumsError())
    }

    @Test
    fun `byte cap right after the album collection fails as malformed instead of an empty truncated album`() {
        val collection = entry(album, null)
        val body = multistatus(listOf(collection, entry("${album}${"p".repeat(2_000)}.jpg")))
        serve(body)
        val cap = body.indexOf(collection) + collection.length + 100L

        val failure = listPhotos(createRepo(PropfindListingLimits(maxResponseBytes = cap))).exceptionOrNull()

        assertTrue(failure is MalformedMultistatusException)
    }

    @Test
    fun `byte cap after only skipped entries yields an empty truncated listing`() {
        // Enough skipped entries that the decoder's read-ahead before the cap cannot swallow all of them.
        val body =
            multistatus(
                listOf(entry(album, null)) +
                    (0 until 100).map { entry("${album}${"x".repeat(300)}$it.jpg") } +
                    (0 until 50).map { entry("${album}photo$it.jpg") },
            )
        serve(body)
        val cap = body.indexOf("photo0.jpg") + 5L

        val listing =
            listPhotos(
                createRepo(
                    PropfindListingLimits(
                        maxResponseBytes = cap,
                        entryLimits = PropfindEntryLimits(maxTextLength = 200),
                    ),
                ),
            ).getOrThrow()

        assertTrue(listing.photos.isEmpty())
        assertTrue(listing.isTruncated)
    }

    @Test
    fun `album list above the byte cap fails with a typed error instead of truncating`() {
        val body = multistatus((0 until 200).map { entry("/remote.php/dav/files/user/Album$it/", null) })
        server.enqueue(MockResponse().setResponseCode(207).setBody(body))

        val failure =
            runBlocking {
                createRepo(PropfindListingLimits(maxResponseBytes = body.length / 2L)).listAlbums()
            }.exceptionOrNull()

        assertTrue(failure is PropfindResponseTooLargeException)
        assertEquals(AlbumsError.ListingTooLarge, failure?.toAlbumsError())
    }

    @Test
    fun `an oversized entry is skipped and the rest of the album is listed`() {
        serve(
            multistatus(
                listOf(
                    entry(album, null),
                    entry("${album}${"x".repeat(300)}.jpg"),
                    entry("${album}ok.jpg"),
                ),
            ),
        )

        val listing =
            listPhotos(createRepo(PropfindListingLimits(entryLimits = PropfindEntryLimits(maxTextLength = 200))))
                .getOrThrow()

        assertEquals(listOf("${album}ok.jpg"), listing.photos.map { it.href })
        assertFalse(listing.isTruncated)
    }

    @Test
    fun `duplicate hrefs are listed once`() {
        serve(
            multistatus(
                listOf(entry(album, null), entry("${album}a.jpg"), entry("${album}b.jpg"), entry("${album}a.jpg")),
            ),
        )

        val listing = listPhotos(createRepo()).getOrThrow()

        assertEquals(listOf("${album}a.jpg", "${album}b.jpg"), listing.photos.map { it.href })
    }

    @Test
    fun `unsafe entries are skipped anywhere in a long streamed listing`() {
        val entries =
            listOf(entry(album, null)) +
                (0 until 300).flatMap { i ->
                    listOf(
                        entry("${album}p$i.jpg"),
                        entry("$album../Other/x$i.jpg"),
                        entry("//evil.example${album}y$i.jpg"),
                    )
                }
        serve(multistatus(entries))

        val listing = listPhotos(createRepo()).getOrThrow()

        assertEquals(300, listing.photos.size)
        assertTrue(listing.photos.all { it.href.startsWith(album) && !it.href.contains("..") })
    }

    @Test
    fun `progress is reported throttled and once at the end`() {
        serve(albumWithPhotos(250))
        val reported = mutableListOf<Int>()

        listPhotos(createRepo()) { synchronized(reported) { reported += it } }.getOrThrow()

        assertEquals(listOf(100, 200, 250), reported)
    }

    // ── HTTP and security on the stream path ───────────────────────────────

    @Test
    fun `an error status fails with the typed status before anything is parsed`() {
        server.dispatcher = memoriesAwareDispatcher(MockResponse().setResponseCode(503).setBody("<html/>"))

        val failure = listPhotos(createRepo()).exceptionOrNull()

        assertEquals(503, (failure as WebDavHttpException).code)
    }

    @Test
    fun `the PROPFIND carries credentials for the configured origin`() {
        serve(albumWithPhotos(1))

        listPhotos(createRepo()).getOrThrow()

        val propfind =
            generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }
                .first { it.method == "PROPFIND" }
        assertEquals(Credentials.basic("testuser", "testpass"), propfind.getHeader("Authorization"))
        assertEquals("1", propfind.getHeader("Depth"))
    }

    @Test(timeout = 15_000)
    fun `the Memories check overlaps the listing so thumbnails need no further request`() {
        // Neither response is sent before both requests have arrived: sequential requests could never
        // both see the other one in flight (the first would wait for the latch in vain).
        val bothInFlight = CountDownLatch(2)
        val overlapping = AtomicInteger()
        val propfind = MockResponse().setResponseCode(207).setBody(albumWithPhotos(1))
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    bothInFlight.countDown()
                    if (bothInFlight.await(3, TimeUnit.SECONDS)) overlapping.incrementAndGet()
                    return if (request.path == MEMORIES_DESCRIBE_PATH) MockResponse().setResponseCode(200) else propfind
                }
            }
        val repo = createRepo()

        val photo = listPhotos(repo).getOrThrow().photos.single()
        val requestsAfterListing = server.requestCount
        val thumb = runBlocking { repo.thumbnailUrl(photo) }

        assertEquals("both requests were in flight at the same time", 2, overlapping.get())
        assertEquals(2, requestsAfterListing)
        assertEquals(requestsAfterListing, server.requestCount)
        assertTrue(thumb.contains("/index.php/apps/memories/api/image/preview/1"))
    }

    // ── Cancellation ───────────────────────────────────────────────────────

    @Test(timeout = 15_000)
    fun `cancelling a listing blocked mid-body cancels the call within seconds`() {
        // Headers and the first bytes arrive, then the server stalls far longer than the test may run.
        server.dispatcher =
            memoriesAwareDispatcher(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(albumWithPhotos(500))
                    .throttleBody(256, 1, TimeUnit.HOURS),
            )
        val repo = createRepo()
        var result: Result<PhotoListing>? = null

        runBlocking {
            val job = launch(Dispatchers.Default) { result = repo.listPhotos(album) }
            // Wait until the PROPFIND reached the server and its body is being read.
            withTimeout(5_000) {
                while (server.requestCount < 2) delay(20)
            }
            delay(300)
            val start = System.nanoTime()
            job.cancel()
            withTimeout(3_000) { job.join() } // far below the 60 s read timeout
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000)
        }

        assertNull("a cancelled listing reports neither photos nor an error", result)
    }

    /** Headers arrive at once, the body never starts (KXml blocks while the reader starts). */
    private fun serveStalledBeforeBody() {
        server.dispatcher =
            memoriesAwareDispatcher(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(albumWithPhotos(5))
                    .setBodyDelay(1, TimeUnit.HOURS),
            )
    }

    @Test(timeout = 15_000)
    fun `cancelling a listing stalled before the first body byte reports no error`() {
        serveStalledBeforeBody()
        val repo = createRepo()
        var result: Result<PhotoListing>? = null

        runBlocking {
            val job = launch(Dispatchers.Default) { result = repo.listPhotos(album) }
            withTimeout(5_000) {
                while (server.requestCount < 2) delay(20)
            }
            delay(300)
            job.cancel()
            withTimeout(3_000) { job.join() }
        }

        assertNull("a cancelled listing reports neither photos nor an error", result)
    }

    @Test(timeout = 15_000)
    fun `a server stalling before the first body byte fails with the timeout error`() {
        serveStalledBeforeBody()

        val failure = listPhotos(createRepo(readTimeoutMillis = 500)).exceptionOrNull()

        assertTrue("expected a timeout, got ${failure?.javaClass}", failure is SocketTimeoutException)
        assertEquals(AlbumsError.Timeout, failure?.toAlbumsError())
    }

    @Test(timeout = 15_000)
    fun `a server stalling mid-body fails with the timeout error`() {
        server.dispatcher =
            memoriesAwareDispatcher(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(albumWithPhotos(500))
                    .throttleBody(256, 1, TimeUnit.HOURS),
            )

        val failure = listPhotos(createRepo(readTimeoutMillis = 500)).exceptionOrNull()

        assertEquals(AlbumsError.Timeout, failure?.toAlbumsError())
    }

    // ── Thumbnail download (bounded, cancellable) ──────────────────────────

    private val thumbnailUrl get() = server.url("${album}a.jpg").toString()

    @Test
    fun `a thumbnail above the size cap fails with a typed error`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4_096))))

        val failure =
            runCatching { runBlocking { createRepo(maxThumbnailBytes = 1_024).downloadBitmapForHash(thumbnailUrl) } }
                .exceptionOrNull()

        assertTrue(
            "expected ThumbnailTooLargeException, got ${failure?.javaClass}",
            failure is ThumbnailTooLargeException,
        )
    }

    @Test
    fun `a chunked thumbnail above the size cap fails with a typed error`() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(4_096)), 512))

        val failure =
            runCatching { runBlocking { createRepo(maxThumbnailBytes = 1_024).downloadBitmapForHash(thumbnailUrl) } }
                .exceptionOrNull()

        assertTrue(
            "expected ThumbnailTooLargeException, got ${failure?.javaClass}",
            failure is ThumbnailTooLargeException,
        )
    }

    @Test(timeout = 15_000)
    fun `cancelling a stalled thumbnail download cancels the call within seconds`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4_096))).throttleBody(16, 1, TimeUnit.HOURS))
        val repo = createRepo()
        var failure: Throwable? = null

        runBlocking {
            val job =
                launch(Dispatchers.Default) {
                    failure = runCatching { repo.downloadBitmapForHash(thumbnailUrl) }.exceptionOrNull()
                }
            withTimeout(5_000) {
                while (server.requestCount < 1) delay(20)
            }
            delay(300)
            job.cancel()
            withTimeout(3_000) { job.join() } // far below the 60 s read timeout
        }

        assertTrue("cancellation, not a download error: ${failure?.javaClass}", failure is CancellationException)
    }
}
