package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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

            val result = client.renameDirectory("Photos/Old Album", "Photos/New Album")
            assertTrue(result.isSuccess)

            val request = server.takeRequest()
            assertEquals("MOVE", request.method)
            assertTrue(request.path!!.endsWith("/Photos/Old%20Album"))
            assertEquals(
                server.url("/remote.php/dav/files/user/Photos/New%20Album").toString(),
                request.getHeader("Destination"),
            )
            assertEquals("F", request.getHeader("Overwrite"))
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
