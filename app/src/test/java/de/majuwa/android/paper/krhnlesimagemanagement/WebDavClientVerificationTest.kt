package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.RemoteFileTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/** Server hash on upload, the verification download and the size check of [WebDavClient] (MockWebServer). */
@RunWith(RobolectricTestRunner::class)
class WebDavClientVerificationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            WebDavClient(
                WebDavConfig(
                    url = server.url("/remote.php/dav/files/user/").toString(),
                    username = "testuser",
                    password = "testpass",
                ),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── server hash, verification download, size check ─────────────────────

    private val hashHex = "0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789abcdef"

    @Test
    fun `uploadFile sends X-Hash only when the server hash is requested`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201))

            client.uploadFile("P", "a.jpg", "image/jpeg", ByteArrayInputStream(ByteArray(3)), 3L)
            client.uploadFile(
                "P",
                "b.jpg",
                "image/jpeg",
                ByteArrayInputStream(ByteArray(3)),
                3L,
                requestServerHash = true,
            )

            assertNull(server.takeRequest().getHeader("X-Hash"))
            assertEquals("sha256", server.takeRequest().getHeader("X-Hash"))
        }

    @Test
    fun `uploadFile returns a well-formed X-Hash-SHA256 and ignores malformed or absent ones`() =
        runTest {
            val cases =
                listOf(
                    hashHex to hashHex,
                    "abc" to null,
                    "z".repeat(64) to null,
                    (hashHex + "0") to null,
                    "" to null,
                )
            cases.forEach { (header, expected) ->
                server.enqueue(MockResponse().setResponseCode(201).setHeader("X-Hash-SHA256", header))
                val result =
                    client.uploadFile("P", "a.jpg", "image/jpeg", ByteArrayInputStream(ByteArray(1)), 1L, true)
                assertEquals("header '$header'", expected, result.getOrThrow().serverSha256)
            }
            server.enqueue(MockResponse().setResponseCode(201))
            val absent = client.uploadFile("P", "a.jpg", "image/jpeg", ByteArrayInputStream(ByteArray(1)), 1L, true)
            assertNull(absent.getOrThrow().serverSha256)
        }

    @Test
    fun `downloadSha256 returns the hash and size of the body`() =
        runTest {
            val content = "fake image data".toByteArray()
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(content)))

            val digest = client.downloadSha256("Photos/Summer", "beach.jpg", content.size.toLong()).getOrThrow()

            val expected =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(content)
            assertEquals(expected.joinToString("") { "%02x".format(it) }, digest.sha256)
            assertEquals(content.size.toLong(), digest.sizeBytes)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path!!.endsWith("/Photos/Summer/beach.jpg"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
        }

    @Test
    fun `downloadSha256 accepts a shorter body and reports its real size`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
            val digest = client.downloadSha256("P", "a.jpg", 10L).getOrThrow()
            assertEquals(3L, digest.sizeBytes)
        }

    @Test
    fun `downloadSha256 stops at the cap with a distinct oversize failure`() =
        runTest {
            // Chunked, so there is no Content-Length to reject up front: the stream cap must trigger.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setChunkedBody("x".repeat(100), 10),
            )
            val failure = client.downloadSha256("P", "a.jpg", 10L).exceptionOrNull()
            assertTrue(failure is RemoteFileTooLargeException)
        }

    @Test
    fun `downloadSha256 rejects a larger Content-Length without reading the body`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(100)))
            val failure = client.downloadSha256("P", "a.jpg", 10L).exceptionOrNull()
            assertTrue(failure is RemoteFileTooLargeException)
        }

    @Test
    fun `downloadSha256 fails on 404 and on every redirect without a follow-up request`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(404, (client.downloadSha256("P", "a.jpg", 10L).exceptionOrNull() as WebDavHttpException).code)

            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path!!.startsWith("/elsewhere/")) {
                            MockResponse().setResponseCode(200).setBody("x")
                        } else {
                            MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere/"))
                        }
                }
            val before = server.requestCount
            val failure = client.downloadSha256("P", "a.jpg", 10L).exceptionOrNull()
            assertEquals(302, (failure as WebDavHttpException).code)
            assertEquals(before + 1, server.requestCount)
        }

    @Test
    fun `downloadSha256 and remoteFileSize reject invalid segments without a request`() =
        runTest {
            listOf("../x" to "a.jpg", "P" to "..", "P" to "a/b.jpg", "P" to "").forEach { (folder, name) ->
                assertTrue("$folder / $name", client.downloadSha256(folder, name, 1L).isFailure)
                assertTrue("$folder / $name", client.remoteFileSize(folder, name).isFailure)
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `downloadSha256 is cancelled with its coroutine when the server never answers`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            var result: Result<*>? = null
            val job = launch(Dispatchers.IO) { result = client.downloadSha256("P", "a.jpg", 10L) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            assertNull(result)
        }

    @Test
    fun `remoteFileSize is cancelled with its coroutine when the server never answers`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            var result: Result<*>? = null
            val job = launch(Dispatchers.IO) { result = client.remoteFileSize("P", "a.jpg") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            assertTrue(job.isCancelled)
            assertNull(result)
        }

    @Test
    fun `downloadSha256 and remoteFileSize send credentials only to the configured origin`() =
        runTest {
            val other = MockWebServer()
            other.start()
            try {
                other.enqueue(MockResponse().setResponseCode(200).setBody("x"))
                other.enqueue(MockResponse().setResponseCode(200))
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/t/")))
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/t/")))

                client.downloadSha256("P", "a.jpg", 1L)
                client.remoteFileSize("P", "a.jpg")

                assertNotNull(server.takeRequest().getHeader("Authorization"))
                assertNotNull(server.takeRequest().getHeader("Authorization"))
                assertEquals(0, other.requestCount)
            } finally {
                other.shutdown()
            }
        }

    @Test
    fun `remoteFileSize returns the Content-Length of a HEAD`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "1234"))
            assertEquals(1234L, client.remoteFileSize("Photos/Summer", "beach.jpg").getOrThrow())
            val request = server.takeRequest()
            assertEquals("HEAD", request.method)
            assertTrue(request.path!!.endsWith("/Photos/Summer/beach.jpg"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertNotNull(request.getHeader("Authorization"))
        }

    @Test
    fun `remoteFileSize is null without a Content-Length`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).clearHeaders())
            assertNull(client.remoteFileSize("P", "a.jpg").getOrThrow())
        }

    @Test
    fun `remoteFileSize fails on 404 and on redirects`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(404, (client.remoteFileSize("P", "a.jpg").exceptionOrNull() as WebDavHttpException).code)
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", server.url("/x/")))
            val before = server.requestCount
            assertEquals(301, (client.remoteFileSize("P", "a.jpg").exceptionOrNull() as WebDavHttpException).code)
            assertEquals(before + 1, server.requestCount)
        }
}
