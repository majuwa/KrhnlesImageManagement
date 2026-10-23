package de.majuwa.android.paper.krhnlesimagemanagement

import android.graphics.Bitmap
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindListingLimits
import de.majuwa.android.paper.krhnlesimagemanagement.data.ThumbnailTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeHrefException
import de.majuwa.android.paper.krhnlesimagemanagement.data.checkPixelLimit
import de.majuwa.android.paper.krhnlesimagemanagement.data.decodeDownsampled
import de.majuwa.android.paper.krhnlesimagemanagement.data.thumbnailSampleSize
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Thumbnail URL resolution costs at most one Memories check per repository instance (issues
 * #25 / #44). Real time (`runBlocking`): the check runs under `withTimeoutOrNull` on IO threads.
 */
@RunWith(RobolectricTestRunner::class)
class ThumbnailUrlResolutionTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val photo = RemotePhoto("a.jpg", "/remote.php/dav/files/user/A/a.jpg", "42", "image/jpeg")
    private val describePath = "/index.php/apps/memories/api/describe"

    private fun repo(
        remotePhp: Boolean = true,
        checkTimeoutMs: Long = AlbumsRepository.MEMORIES_CHECK_TIMEOUT_MS,
        backoffMs: Long = AlbumsRepository.MEMORIES_RETRY_BACKOFF_MS,
    ) = AlbumsRepository(
        WebDavConfig(
            url = server.url(if (remotePhp) "/remote.php/dav/files/user/" else "/dav/files/user/").toString(),
            username = "u",
            password = "p",
            baseFolder = "",
        ),
        PropfindListingLimits(),
        memoriesCheckTimeoutMs = checkTimeoutMs,
        memoriesRetryBackoffMs = backoffMs,
    )

    private fun serve(vararg answers: MockResponse) {
        val queue = ArrayDeque(answers.toList())
        server.dispatcher =
            object : Dispatcher() {
                @Synchronized
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (queue.size > 1) queue.removeFirst() else queue.first()
            }
    }

    private fun isPreview(url: String) = url.contains("/apps/memories/api/image/preview/42")

    @Test
    fun `many sequential calls send one Memories request`() =
        runBlocking {
            serve(MockResponse().setResponseCode(200))
            val r = repo()
            val urls = (1..500).map { r.thumbnailUrl(photo) }
            assertTrue(urls.all(::isPreview))
            assertEquals(1, server.requestCount)
            assertEquals(describePath, server.takeRequest().path)
        }

    @Test
    fun `concurrent calls share one Memories request`() =
        runBlocking {
            serve(MockResponse().setResponseCode(200).setHeadersDelay(300, TimeUnit.MILLISECONDS))
            val r = repo()
            val urls = (1..50).map { async(Dispatchers.Default) { r.thumbnailUrl(photo) } }.awaitAll()
            assertTrue(urls.all(::isPreview))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `definite non-success status is cached as unavailable`() =
        runBlocking {
            serve(MockResponse().setResponseCode(404))
            val r = repo(backoffMs = 0)
            repeat(20) { assertTrue(r.thumbnailUrl(photo).endsWith("/A/a.jpg")) }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `non Nextcloud url sends no request`() =
        runBlocking {
            serve(MockResponse().setResponseCode(200))
            val r = repo(remotePhp = false)
            repeat(50) { r.thumbnailUrl(photo) }
            assertEquals(0, server.requestCount)
        }

    private val propfindBody =
        """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
          <d:response><d:href>/remote.php/dav/files/user/A/</d:href>
            <d:propstat><d:prop><d:displayname>A</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/files/user/A/a.jpg</d:href>
            <d:propstat><d:prop><d:displayname>a.jpg</d:displayname><d:getcontenttype>image/jpeg</d:getcontenttype>
            <oc:fileid>42</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>"""

    private fun propfindResponse() =
        MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .setBody(propfindBody)

    private val albumHref = "/remote.php/dav/files/user/A/"

    @Test
    fun `retry after an inconclusive check does not block listPhotos`() =
        runBlocking {
            // describe never answers; PROPFIND answers at once.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path == describePath) {
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        } else {
                            propfindResponse()
                        }
                }
            val r = repo(checkTimeoutMs = 1_500, backoffMs = 50)
            val first = measureTimeMillis { r.listPhotos(albumHref) {}.getOrThrow() }
            assertTrue("first check is awaited, took $first", first >= 1_400)
            delay(100) // past the backoff: a retry is due
            val second = measureTimeMillis { r.listPhotos(albumHref) {}.getOrThrow() }
            assertTrue("retry must not block the listing, took $second", second < 700)
        }

    @Test
    fun `listPhotos runs the Memories check concurrently with the PROPFIND`() =
        runBlocking {
            val bothArrived = CountDownLatch(2)
            var concurrent = true
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        bothArrived.countDown()
                        if (!bothArrived.await(5, TimeUnit.SECONDS)) concurrent = false
                        return if (request.path == describePath) {
                            MockResponse().setResponseCode(200)
                        } else {
                            propfindResponse()
                        }
                    }
                }
            val r = repo()
            r.listPhotos(albumHref) {}.getOrThrow()
            assertTrue("describe and PROPFIND must be in flight together", concurrent)
            assertTrue(isPreview(r.thumbnailUrl(photo)))
        }

    @Test
    fun `transient 503 from describe is retried after the backoff`() =
        runBlocking {
            serve(MockResponse().setResponseCode(503), MockResponse().setResponseCode(200))
            val r = repo(backoffMs = 100)
            assertTrue(!isPreview(r.thumbnailUrl(photo)))
            assertEquals(1, server.requestCount)
            delay(200)
            r.thumbnailUrl(photo) // starts the background retry
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!isPreview(r.thumbnailUrl(photo)) && System.nanoTime() < deadline) delay(20)
            assertTrue(isPreview(r.thumbnailUrl(photo)))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `io failure of the check sends no request storm during the backoff`() =
        runBlocking {
            server.dispatcher =
                object : Dispatcher() {
                    // The socket policy at the start of a connection is read from peek().
                    override fun peek(): MockResponse = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)

                    override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(200)
                }
            val r = repo(backoffMs = 60_000)
            assertTrue(!isPreview(r.thumbnailUrl(photo)))
            val afterFirst = server.requestCount
            repeat(100) { assertTrue(!isPreview(r.thumbnailUrl(photo))) }
            assertEquals(afterFirst, server.requestCount)
        }

    @Test
    fun `file id that is not ascii alphanumeric falls back to the WebDAV url`() =
        runBlocking {
            serve(MockResponse().setResponseCode(200))
            val r = repo()
            for (id in listOf("\u0664\u0662", "4/2", "")) {
                val url = r.thumbnailUrl(photo.copy(fileId = id))
                assertTrue("'$id' -> $url", url.endsWith("/A/a.jpg"))
            }
            assertTrue(isPreview(r.thumbnailUrl(photo)))
        }

    @Test
    fun `memories preview url with Arabic-Indic digits is refused without a request`() =
        runBlocking {
            serve(MockResponse().setResponseCode(200))
            val before = server.requestCount
            val url = server.url("/index.php/apps/memories/api/image/preview/\u0664\u0662").toString()
            val failure = runCatching { repo().downloadBitmapForHash(url) }.exceptionOrNull()
            assertTrue("$failure", failure is UnsafeHrefException)
            assertEquals(before, server.requestCount)
        }

    @Test
    fun `huge announced canvas is rejected before decoding`() {
        checkPixelLimit(10_000, 10_000) // 100 MP is still allowed
        assertThrows(ThumbnailTooLargeException::class.java) { checkPixelLimit(60_000, 60_000) }
        assertThrows(ThumbnailTooLargeException::class.java) { checkPixelLimit(Int.MAX_VALUE, Int.MAX_VALUE) }
    }

    @Test
    fun `sharp textured image stays above the blur threshold after downsampled decode`() {
        val w = 2048
        val h = 1536
        val rnd = java.util.Random(7)
        val pixels =
            IntArray(w * h) {
                val v = rnd.nextInt(256)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        src.setPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.PNG, 100, out)
        val decoded = decodeDownsampled(out.toByteArray())!!
        assertTrue(maxOf(decoded.width, decoded.height) < w)
        val variance = BlurDetector.computeLaplacianVariance(decoded)
        assertTrue("variance $variance", variance > BlurDetector.DEFAULT_THRESHOLD)
    }

    @Test
    fun `large original is decoded downsampled`() =
        runBlocking {
            val big = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            big.compress(Bitmap.CompressFormat.PNG, 100, out)
            serve(MockResponse().setBody(Buffer().write(out.toByteArray())))
            val bmp = repo().downloadBitmapForHash(server.url("/remote.php/dav/files/user/A/a.png").toString())
            assertTrue("${bmp.width}x${bmp.height}", maxOf(bmp.width, bmp.height) in 512..1023)
            assertEquals(3000f / 2000f, bmp.width.toFloat() / bmp.height, 0.05f)
        }

    @Test
    fun `small image is not downsampled`() =
        runBlocking {
            val small = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.PNG, 100, out)
            serve(MockResponse().setBody(Buffer().write(out.toByteArray())))
            val bmp = repo().downloadBitmapForHash(server.url("/remote.php/dav/files/user/A/a.png").toString())
            assertEquals(300, bmp.width)
        }

    @Test
    fun `sample size keeps the longest side at or above the target`() {
        assertEquals(1, thumbnailSampleSize(300, 200))
        assertEquals(1, thumbnailSampleSize(1023, 10))
        assertEquals(2, thumbnailSampleSize(1024, 10))
        assertEquals(8, thumbnailSampleSize(6000, 4000))
        assertEquals(8, thumbnailSampleSize(4096, 100))
    }
}
