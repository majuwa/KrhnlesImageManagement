package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.DownloadTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnexpectedContentException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeHrefException
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** [WebDavClient.download]: streaming, caps, redirects, origin and cancellation (MockWebServer). */
@RunWith(RobolectricTestRunner::class)
class WebDavClientDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var client: WebDavClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
        client =
            WebDavClient(
                WebDavConfig(
                    url = server.url("/remote.php/dav/files/user/").toString(),
                    username = "testuser",
                    password = "testpass",
                ),
                null,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun url(path: String = "/remote.php/dav/files/user/Album/a.jpg") = server.url(path)

    private fun image(
        bytes: ByteArray,
        type: String = "image/jpeg",
    ) = MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", type)

    @Test
    fun `the streamed bytes equal the body and identity encoding is requested`() =
        runTest {
            val body = ByteArray(100_000) { it.toByte() }
            server.enqueue(image(body))
            var received = ByteArray(0)
            var announced = -1L

            val result =
                client.download(url(), 1_000_000L) { downloaded ->
                    announced = downloaded.contentLength
                    received = downloaded.stream.readBytes()
                }

            assertTrue(result.isSuccess)
            assertArrayEquals(body, received)
            assertEquals(body.size.toLong(), announced)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertNotNull(request.getHeader("Authorization"))
        }

    @Test
    fun `octet-stream and a missing content type are accepted`() =
        runTest {
            server.enqueue(image(ByteArray(3), "application/octet-stream"))
            server.enqueue(MockResponse().setBody("abc").removeHeader("Content-Type"))

            assertTrue(client.download(url(), 100L) { it.stream.readBytes() }.isSuccess)
            assertTrue(client.download(url(), 100L) { it.stream.readBytes() }.isSuccess)
        }

    @Test
    fun `a url on another port sends no request and no credentials`() =
        runTest {
            val result = client.download(other.url("/remote.php/dav/files/user/a.jpg"), 100L) { }

            assertTrue(result.exceptionOrNull() is UnsafeHrefException)
            assertEquals(0, other.requestCount)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `redirects fail and the location is never requested`() =
        runTest {
            listOf(301, 302, 307).forEach { code ->
                server.enqueue(
                    MockResponse().setResponseCode(code).setHeader("Location", other.url("/stolen.jpg").toString()),
                )
                val result = client.download(url(), 100L) { }
                assertEquals(code, (result.exceptionOrNull() as WebDavHttpException).code)
            }
            assertEquals(0, other.requestCount)
        }

    @Test
    fun `a missing file is a 404 exception and the sink is not called`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404).setBody("secret error page"))
            var called = false

            val result = client.download(url(), 100L) { called = true }

            assertEquals(404, (result.exceptionOrNull() as WebDavHttpException).code)
            assertFalse(called)
        }

    @Test
    fun `an announced length above the cap fails before the sink`() =
        runTest {
            server.enqueue(image(ByteArray(2_000)))
            var called = false

            val result = client.download(url(), 1_000L) { called = true }

            assertTrue(result.exceptionOrNull() is DownloadTooLargeException)
            assertFalse(called)
        }

    @Test
    fun `a chunked body above the cap fails while streaming`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setChunkedBody(Buffer().write(ByteArray(5_000)), 500)
                    .setHeader("Content-Type", "image/jpeg"),
            )
            var read = 0L

            val result =
                client.download(url(), 1_000L) { downloaded ->
                    val buffer = ByteArray(256)
                    while (true) {
                        val n = downloaded.stream.read(buffer)
                        if (n < 0) break
                        read += n
                    }
                }

            assertTrue(result.exceptionOrNull() is DownloadTooLargeException)
            assertEquals(1_000L, read)
        }

    private fun assertRejected(response: MockResponse) =
        runBlocking {
            server.enqueue(response)
            var called = false

            val result = client.download(url(), 1_000L) { called = true }

            assertTrue(result.exceptionOrNull() is UnexpectedContentException)
            assertFalse(called)
        }

    @Test
    fun `svg and tiff are rejected though they are images`() {
        assertRejected(image(ByteArray(3), "image/svg+xml"))
        assertRejected(image(ByteArray(3), "image/tiff"))
        assertRejected(image(ByteArray(3), "image/x-unknown"))
    }

    @Test
    fun `allow listed image types are accepted in any case`() =
        runTest {
            listOf("image/jpeg", "IMAGE/PNG", "image/heic", "image/avif", "image/x-adobe-dng", "image/webp").forEach {
                server.enqueue(image(ByteArray(3), it))
                assertTrue(it, client.download(url(), 100L) { body -> body.stream.readBytes() }.isSuccess)
            }
        }

    @Test
    fun `a content type header that does not parse fails closed`() {
        assertRejected(image(ByteArray(3), "image"))
        assertRejected(image(ByteArray(3), "jpeg/"))
        assertRejected(image(ByteArray(3), "not a type"))
    }

    @Test
    fun `an encoded body is rejected and identity is accepted`() =
        runBlocking {
            assertRejected(image(ByteArray(3)).setHeader("Content-Encoding", "gzip"))
            assertRejected(image(ByteArray(3)).setHeader("Content-Encoding", "br"))
            server.enqueue(image(ByteArray(3)).setHeader("Content-Encoding", "identity"))

            assertTrue(client.download(url(), 100L) { it.stream.readBytes() }.isSuccess)
        }

    @Test
    fun `an http 1 body of unknown length that is not chunked is rejected`() {
        // No Content-Length and no chunking: the end is the closed connection, a truncation looks the same.
        assertRejected(
            image(ByteArray(10))
                .removeHeader("Content-Length")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
        )
    }

    @Test
    fun `a chunked body of unknown length is accepted`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setChunkedBody(Buffer().write(ByteArray(2_000)), 500)
                    .setHeader("Content-Type", "image/jpeg"),
            )
            var received = 0

            val result = client.download(url(), 10_000L) { received = it.stream.readBytes().size }

            assertTrue(result.isSuccess)
            assertEquals(2_000, received)
        }

    @Test
    fun `text html is rejected before the sink`() =
        runTest {
            server.enqueue(MockResponse().setBody("<html>login</html>").setHeader("Content-Type", "text/html"))
            var called = false

            val result = client.download(url(), 1_000L) { called = true }

            assertTrue(result.exceptionOrNull() is UnexpectedContentException)
            assertFalse(called)
        }

    @Test
    fun `cancelling while stalled before the first byte returns at once`() =
        cancelWhileStalled(SocketPolicy.NO_RESPONSE)

    @Test
    fun `cancelling mid body returns at once`() = cancelWhileStalled(SocketPolicy.NO_RESPONSE, midBody = true)

    private fun cancelWhileStalled(
        policy: SocketPolicy,
        midBody: Boolean = false,
    ) {
        if (midBody) {
            server.enqueue(
                image(ByteArray(2_000)).setBody(Buffer().write(ByteArray(2_000))).throttleBody(10, 1, TimeUnit.SECONDS),
            )
        } else {
            server.enqueue(MockResponse().setSocketPolicy(policy))
        }
        val started = AtomicBoolean(false)
        runBlocking {
            withTimeout(5_000) {
                val job =
                    async(Dispatchers.Default) {
                        client.download(url(), 10_000L) { downloaded ->
                            started.set(true)
                            downloaded.stream.readBytes()
                        }
                    }
                // Give the request time to reach the stalled state, then cancel.
                if (midBody) {
                    while (!started.get()) delay(20)
                } else {
                    delay(300)
                }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        }
    }
}
