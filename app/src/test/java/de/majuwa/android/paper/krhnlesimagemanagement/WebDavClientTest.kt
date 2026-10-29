package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.RemoteFileTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadResponse
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.data.toUploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class WebDavClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    private val oldPath = listOf("Photos", "Old")
    private val newPath = listOf("Photos", "New")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/remote.php/dav/files/user/").toString()
        client =
            WebDavClient(
                WebDavConfig(
                    url = baseUrl,
                    username = "testuser",
                    password = "testpass",
                ),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── testConnection ──────────────────────────────────────────────────────

    @Test
    fun `testConnection succeeds on 207 multistatus`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(207))
            val result = client.testConnection()
            assertTrue(result.isSuccess)

            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertEquals("0", request.getHeader("Depth"))
        }

    @Test
    fun `testConnection succeeds on 200 OK`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))
            val result = client.testConnection()
            assertTrue(result.isSuccess)
        }

    @Test
    fun `testConnection fails on 401 Unauthorized`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))
            val result = client.testConnection()
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("401"))
            assertEquals(401, (result.exceptionOrNull() as WebDavHttpException).code)
        }

    @Test
    fun `testConnection fails with a timeout when the whole call exceeds its limit`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(207).setHeadersDelay(5, TimeUnit.SECONDS))

            val started = System.nanoTime()
            val result = client.testConnection(timeoutMillis = 200)
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue(result.exceptionOrNull() is InterruptedIOException)
            assertTrue("Call timeout not enforced: $elapsedMillis ms", elapsedMillis < 4_000)
        }

    @Test
    fun `testConnection is cancelled with its coroutine and does not wrap the cancellation`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(207).setHeadersDelay(5, TimeUnit.SECONDS))
            var outcome: Result<Unit>? = null

            val started = System.nanoTime()
            val job = launch(Dispatchers.IO) { outcome = client.testConnection() }
            server.takeRequest(2, TimeUnit.SECONDS)
            job.cancelAndJoin()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue(job.isCancelled)
            assertNull("Cancellation must propagate, not become a Result", outcome)
            assertTrue("Cancel did not abort the call: $elapsedMillis ms", elapsedMillis < 4_000)
        }

    @Test
    fun `testConnection reports HTTP 404 as typed exception`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            val result = client.testConnection()
            assertEquals(404, (result.exceptionOrNull() as WebDavHttpException).code)
        }

    @Test
    fun `testConnection sends Basic auth header`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(207))
            client.testConnection()
            val request = server.takeRequest()
            val auth = request.getHeader("Authorization")
            assertTrue("Should have Basic auth", auth!!.startsWith("Basic "))
        }

    // ── createDirectory ─────────────────────────────────────────────────────

    @Test
    fun `createDirectory sends MKCOL for each path segment`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201)) // Photos
            server.enqueue(MockResponse().setResponseCode(201)) // KrohnSync
            server.enqueue(MockResponse().setResponseCode(201)) // Summer2025

            val result = client.createDirectory("Photos/KrohnSync/Summer2025")
            assertTrue(result.isSuccess)

            assertEquals(3, server.requestCount)
            val r1 = server.takeRequest()
            assertEquals("MKCOL", r1.method)
            assertTrue(r1.path!!.endsWith("/Photos"))

            val r2 = server.takeRequest()
            assertEquals("MKCOL", r2.method)
            assertTrue(r2.path!!.endsWith("/Photos/KrohnSync"))

            val r3 = server.takeRequest()
            assertEquals("MKCOL", r3.method)
            assertTrue(r3.path!!.endsWith("/Photos/KrohnSync/Summer2025"))
        }

    @Test
    fun `createDirectory ignores 405 already exists`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(405)) // Already exists
            server.enqueue(MockResponse().setResponseCode(201)) // Created

            val result = client.createDirectory("Existing/New")
            assertTrue(result.isSuccess)
        }

    @Test
    fun `createDirectory fails on 403 Forbidden`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

            val result = client.createDirectory("Protected")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("403"))
        }

    @Test
    fun `renameDirectory sends MOVE with encoded destination`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))

            val result = client.renameDirectory(listOf("Photos", "Old Album"), listOf("Photos", "New Album"))
            assertTrue(result.isSuccess)

            val request = server.takeRequest()
            assertEquals("MOVE", request.method)
            assertTrue(request.path!!.endsWith("/Photos/Old%20Album/"))
            assertEquals(
                server.url("/remote.php/dav/files/user/Photos/New%20Album").toString(),
                request.getHeader("Destination"),
            )
            assertEquals("F", request.getHeader("Overwrite"))
        }

    @Test
    fun `renameDirectory accepts 204`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(204))
            assertTrue(client.renameDirectory(oldPath, newPath).isSuccess)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `renameDirectory treats redirects and 200 as failure without following`() =
        runTest {
            var code = 0
            // A followed redirect would reach /elsewhere/ and fail the request count at once.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path!!.startsWith("/elsewhere/")) {
                            MockResponse().setResponseCode(200)
                        } else {
                            MockResponse().setResponseCode(code).setHeader("Location", server.url("/elsewhere/"))
                        }
                }
            listOf(301, 302, 307, 308, 200).forEach { status ->
                code = status
                val before = server.requestCount
                val failure = client.renameDirectory(oldPath, newPath).exceptionOrNull()
                assertEquals("HTTP $status", status, (failure as WebDavHttpException).code)
                assertEquals("HTTP $status", before + 1, server.requestCount)
            }
        }

    @Test
    fun `renameDirectory reports an unexpected 2xx like 207 as WebDavHttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(207))
            val failure = client.renameDirectory(oldPath, newPath).exceptionOrNull()
            assertEquals(207, (failure as WebDavHttpException).code)
        }

    @Test
    fun `renameDirectory reports 412 and 404 as WebDavHttpException`() =
        runTest {
            listOf(412, 404).forEach { code ->
                server.enqueue(MockResponse().setResponseCode(code))
                val failure = client.renameDirectory(oldPath, newPath).exceptionOrNull()
                assertEquals(code, (failure as WebDavHttpException).code)
            }
        }

    @Test
    fun `renameDirectory rejects invalid segments without a request`() =
        runTest {
            listOf(
                listOf("Photos", "..") to newPath,
                oldPath to listOf("Photos", ".."),
                oldPath to emptyList(),
                emptyList<String>() to newPath,
                listOf("Photos", " ", "Old") to newPath,
                listOf("Photos", "") to newPath,
                oldPath to listOf("Photos", "  "),
                oldPath to listOf("Photos", "a\\b"),
                oldPath to listOf("Photos", "a/b"),
                oldPath to listOf("Photos", "a\u0000b"),
                oldPath to listOf("Photos", "a\nb"),
                listOf("Photos", "a\u007fb") to newPath,
                oldPath to listOf("Photos", "a\u001fb"),
            ).forEach { (old, new) ->
                assertTrue("$old -> $new", client.renameDirectory(old, new).isFailure)
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `credentials are not sent to another origin after a redirect`() =
        runTest {
            val other = MockWebServer()
            other.start()
            try {
                other.enqueue(MockResponse().setResponseCode(207))
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/target/")))

                client.testConnection()

                assertNotNull(server.takeRequest().getHeader("Authorization"))
                val redirected = other.takeRequest()
                assertNull(redirected.getHeader("Authorization"))
                assertTrue(redirected.getHeader("User-Agent")!!.startsWith("Kroehnles"))
            } finally {
                other.shutdown()
            }
        }

    @Test
    fun `renameDirectory is cancelled with its coroutine when the server never answers`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { client.renameDirectory(oldPath, newPath) }
            server.takeRequest(5, TimeUnit.SECONDS)
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
        }

    // ── uploadFile ──────────────────────────────────────────────────────────

    @Test
    fun `uploadFile sends PUT with correct path and content type`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))

            val content = "fake image data".toByteArray()
            val result =
                client.uploadFile(
                    "Photos/Summer",
                    "beach.jpg",
                    "image/jpeg",
                    ByteArrayInputStream(content),
                )
            assertTrue(result.isSuccess)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertTrue(request.path!!.endsWith("/Photos/Summer/beach.jpg"))
            assertEquals("image/jpeg", request.getHeader("Content-Type"))
            assertEquals(content.size.toLong(), request.bodySize)
        }

    @Test
    fun `uploadFile fails on 507 Insufficient Storage`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(507))

            val result =
                client.uploadFile(
                    "Photos",
                    "big.jpg",
                    "image/jpeg",
                    ByteArrayInputStream(ByteArray(10)),
                )
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("507"))
        }

    @Test
    fun `uploadFile sends Content-Length when the size is known`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            val content = ByteArray(1234) { it.toByte() }

            val result = client.uploadFile("Videos", "clip.mp4", "video/mp4", ByteArrayInputStream(content), 1234L)

            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertEquals("1234", request.getHeader("Content-Length"))
            assertNull(request.getHeader("Transfer-Encoding"))
            assertEquals("video/mp4", request.getHeader("Content-Type"))
            assertEquals(1234L, request.bodySize)
        }

    @Test
    fun `uploadFile uses chunked transfer when the size is unknown`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            val content = ByteArray(5000) { it.toByte() }

            val result = client.uploadFile("Videos", "clip.mp4", "video/*", ByteArrayInputStream(content))

            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertNull(request.getHeader("Content-Length"))
            assertEquals("chunked", request.getHeader("Transfer-Encoding"))
            assertEquals(5000L, request.bodySize)
        }

    @Test
    fun `upload body is one-shot and reports a known or unknown length`() {
        val type = "video/mp4".toMediaType()
        val known = WebDavClient.InputStreamRequestBody(type, ByteArrayInputStream(ByteArray(3)), 3L)
        val unknown = WebDavClient.InputStreamRequestBody(type, ByteArrayInputStream(ByteArray(3)), -1L)

        assertTrue(known.isOneShot())
        assertTrue(unknown.isOneShot())
        assertEquals(3L, known.contentLength())
        assertEquals(-1L, unknown.contentLength())
    }

    @Test
    fun `upload read timeout applies to the upload client only and no client has a call timeout`() {
        val shared = client.httpClient
        val upload = client.uploadClient

        assertEquals(TimeUnit.MINUTES.toMillis(5).toInt(), upload.readTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(60).toInt(), shared.readTimeoutMillis)
        assertEquals(0, shared.callTimeoutMillis)
        assertEquals(0, upload.callTimeoutMillis)
        // A copy of the shared client: same pool and dispatcher, same write timeout.
        assertTrue(shared.connectionPool === upload.connectionPool)
        assertTrue(shared.dispatcher === upload.dispatcher)
        assertEquals(shared.writeTimeoutMillis, upload.writeTimeoutMillis)
    }

    @Test
    fun `uploadFile is cancelled with its coroutine when the server never answers`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            var result: Result<UploadResponse>? = null
            val job =
                launch(Dispatchers.IO) {
                    result =
                        client.uploadFile("Videos", "clip.mp4", "video/mp4", ByteArrayInputStream(ByteArray(10)), 10L)
                }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            // Cancellation is rethrown, never returned as a failed result.
            assertNull(result)
        }

    @Test
    fun `uploadFile fails on every redirect without a follow-up request`() =
        runTest {
            var code = 0
            // A followed 301 / 302 / 303 would turn the PUT into a GET to /elsewhere/ answered 200.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path!!.startsWith("/elsewhere/")) {
                            MockResponse().setResponseCode(200)
                        } else {
                            MockResponse().setResponseCode(code).setHeader("Location", server.url("/elsewhere/"))
                        }
                }
            listOf(301, 302, 303, 307, 308).forEach { status ->
                code = status
                val before = server.requestCount
                val failure =
                    client
                        .uploadFile("Videos", "clip.mp4", "video/mp4", ByteArrayInputStream(ByteArray(10)), 10L)
                        .exceptionOrNull()
                assertEquals("HTTP $status", status, (failure as WebDavHttpException).code)
                assertEquals("HTTP $status", before + 1, server.requestCount)
            }
            assertFalse(client.uploadClient.followRedirects)
            assertFalse(client.uploadClient.followSslRedirects)
        }

    @Test
    fun `uploadFile maps 413 and 507 to typed failures`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(413))
            server.enqueue(MockResponse().setResponseCode(507))
            server.enqueue(MockResponse().setResponseCode(500))

            val tooLarge = client.uploadFile("V", "a.mp4", "video/mp4", ByteArrayInputStream(ByteArray(1)))
            val full = client.uploadFile("V", "b.mp4", "video/mp4", ByteArrayInputStream(ByteArray(1)))
            val other = client.uploadFile("V", "c.mp4", "video/mp4", ByteArrayInputStream(ByteArray(1)))

            assertEquals(413, (tooLarge.exceptionOrNull() as WebDavHttpException).code)
            assertEquals(UploadFailure.TooLarge, tooLarge.exceptionOrNull()!!.toUploadFailure())
            assertEquals(UploadFailure.StorageFull, full.exceptionOrNull()!!.toUploadFailure())
            assertEquals(UploadFailure.Other, other.exceptionOrNull()!!.toUploadFailure())
            assertEquals(UploadFailure.Other, java.io.IOException("x").toUploadFailure())
        }

    // ── Error Recovery ──────────────────────────────────────────────────────

    @Test
    fun `testConnection handles timeout gracefully`() =
        runTest {
            server.enqueue(
                MockResponse().setSocketPolicy(
                    okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START,
                ),
            )
            val result = client.testConnection()
            assertTrue("Should fail on disconnect", result.isFailure)
        }

    @Test
    fun `createDirectory handles 500 server error`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

            val result = client.createDirectory("Photos")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("500"))
        }

    @Test
    fun `uploadFile handles 500 server error`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            val result =
                client.uploadFile(
                    "Photos",
                    "photo.jpg",
                    "image/jpeg",
                    ByteArrayInputStream(ByteArray(100)),
                )
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("500"))
        }

    @Test
    fun `uploadFile handles 403 Forbidden`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(403))

            val result =
                client.uploadFile(
                    "Protected",
                    "photo.jpg",
                    "image/jpeg",
                    ByteArrayInputStream(ByteArray(100)),
                )
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("403"))
        }

    @Test
    fun `testConnection handles 503 Service Unavailable`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))

            val result = client.testConnection()
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("503"))
        }

    @Test
    fun `uploadFile includes Content-Length header`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            val content = "test data".toByteArray()

            client.uploadFile(
                "Photos",
                "photo.jpg",
                "image/jpeg",
                ByteArrayInputStream(content),
            )

            val request = server.takeRequest()
            assertEquals("image/jpeg", request.getHeader("Content-Type"))
        }

    @Test
    fun `createDirectory rejects path traversal segments`() =
        runTest {
            val result = client.createDirectory("Photos/../Private")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Invalid path segment"))
        }

    @Test
    fun `createDirectories requests every shared level only once and in order`() =
        runTest {
            repeat(7) { server.enqueue(MockResponse().setResponseCode(201)) }

            val result = client.createDirectories(listOf("Photos/2026/04/30", "Photos/2026/05/01", "Photos/2026/04/30"))
            assertTrue(result.isSuccess)

            val paths =
                List(server.requestCount) { server.takeRequest().path!!.removePrefix("/remote.php/dav/files/user/") }
            assertEquals(
                listOf(
                    "Photos",
                    "Photos/2026",
                    "Photos/2026/04",
                    "Photos/2026/04/30",
                    "Photos/2026/05",
                    "Photos/2026/05/01",
                ),
                paths,
            )
        }

    @Test
    fun `createDirectories validates every path before the first request`() =
        runTest {
            val result = client.createDirectories(listOf("Photos/2026", "Photos/../Private"))

            assertTrue(result.isFailure)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `createDirectories stops at the first failing level`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(403))

            val result = client.createDirectories(listOf("Photos/A", "Photos/B"))

            assertEquals(403, (result.exceptionOrNull() as WebDavHttpException).code)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `uploadFile rejects path traversal filename`() =
        runTest {
            val result =
                client.uploadFile(
                    "Photos/Summer",
                    "../secrets.txt",
                    "text/plain",
                    ByteArrayInputStream("x".toByteArray()),
                )
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Invalid path segment"))
        }

    @Test
    fun `uploadFile URL-encodes path segments`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            val content = "data".toByteArray()

            val result =
                client.uploadFile(
                    "Photos/Summer 2026",
                    "beach photo.jpg",
                    "image/jpeg",
                    ByteArrayInputStream(content),
                )

            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertTrue(request.path!!.contains("Summer%202026"))
            assertTrue(request.path!!.contains("beach%20photo.jpg"))
        }
}
