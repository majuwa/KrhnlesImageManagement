package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.ChunkedUploadCreation
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.data.newTransferId
import de.majuwa.android.paper.krhnlesimagemanagement.data.toUploadFailure
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

/** The four chunked-upload requests of [WebDavClient] against a MockWebServer (issue #69). */
@RunWith(RobolectricTestRunner::class)
class WebDavClientChunkedTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient
    private val id = "krhnles-0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = clientFor("/remote.php/dav/files/alice/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clientFor(path: String) =
        WebDavClient(WebDavConfig(url = server.url(path).toString(), username = "alice", password = "pw"), null)

    @Test
    fun `mkcol creates the upload folder with destination and total length`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))

            val result = client.createChunkedUpload(id, "Videos/2024", "clip one.mp4", 12_345L)

            assertEquals(ChunkedUploadCreation.Created, result.getOrThrow())
            val request = server.takeRequest()
            assertEquals("MKCOL", request.method)
            assertEquals("/remote.php/dav/uploads/alice/$id", request.path)
            assertEquals(
                server.url("/remote.php/dav/files/alice/Videos/2024/clip%20one.mp4").toString(),
                request.getHeader("Destination"),
            )
            assertEquals("12345", request.getHeader("OC-Total-Length"))
            assertNull(request.getHeader("X-Hash"))
            assertNotNull(request.getHeader("Authorization"))
        }

    @Test
    fun `mkcol 405 means the folder exists and other statuses fail`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(200))

            assertEquals(
                ChunkedUploadCreation.AlreadyExists,
                client.createChunkedUpload(id, "V", "a.mp4", 1L).getOrThrow(),
            )
            val missing = client.createChunkedUpload(id, "V", "a.mp4", 1L).exceptionOrNull()
            val ok = client.createChunkedUpload(id, "V", "a.mp4", 1L).exceptionOrNull()

            assertEquals(404, (missing as WebDavHttpException).code)
            assertEquals(200, (ok as WebDavHttpException).code)
        }

    @Test
    fun `chunk put sends the numbered chunk with exact length and without a hash header`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            val data = ByteArray(10) { it.toByte() }

            val result = client.uploadChunk(id, 3, "V", "a.mp4", 25L, ByteArrayInputStream(data), 10L)

            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/remote.php/dav/uploads/alice/$id/00003", request.path)
            assertEquals("10", request.getHeader("Content-Length"))
            assertEquals("25", request.getHeader("OC-Total-Length"))
            assertEquals(server.url("/remote.php/dav/files/alice/V/a.mp4").toString(), request.getHeader("Destination"))
            assertNull(request.getHeader("X-Hash"))
            assertEquals(data.toList(), request.body.readByteArray().toList())
        }

    @Test
    fun `consecutive chunks read consecutive parts of one stream`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201))
            val stream = ByteArrayInputStream(ByteArray(15) { it.toByte() })

            client.uploadChunk(id, 1, "V", "a.mp4", 15L, stream, 10L).getOrThrow()
            client.uploadChunk(id, 2, "V", "a.mp4", 15L, stream, 5L).getOrThrow()

            assertEquals(
                (0..9).map { it.toByte() },
                server
                    .takeRequest()
                    .body
                    .readByteArray()
                    .toList(),
            )
            assertEquals(
                (10..14).map { it.toByte() },
                server
                    .takeRequest()
                    .body
                    .readByteArray()
                    .toList(),
            )
        }

    @Test
    fun `a stream shorter than the chunk fails the chunk`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))

            val failure =
                client
                    .uploadChunk(
                        id,
                        1,
                        "V",
                        "a.mp4",
                        10L,
                        ByteArrayInputStream(ByteArray(4)),
                        10L,
                    ).exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure !is WebDavHttpException)
        }

    @Test
    fun `move assembles the file and asks for the hash only when requested`() =
        runTest {
            val hash = "ab".repeat(32)
            server.enqueue(MockResponse().setResponseCode(201).setHeader("X-Hash-SHA256", hash))
            server.enqueue(MockResponse().setResponseCode(204))

            val withHash = client.finishChunkedUpload(id, "V", "a.mp4", 25L, requestServerHash = true).getOrThrow()
            val without = client.finishChunkedUpload(id, "V", "a.mp4", 25L).getOrThrow()

            assertEquals(hash, withHash.serverSha256)
            assertNull(without.serverSha256)
            val first = server.takeRequest()
            assertEquals("MOVE", first.method)
            assertEquals("/remote.php/dav/uploads/alice/$id/.file", first.path)
            assertEquals("sha256", first.getHeader("X-Hash"))
            assertEquals("25", first.getHeader("OC-Total-Length"))
            assertEquals(server.url("/remote.php/dav/files/alice/V/a.mp4").toString(), first.getHeader("Destination"))
            assertNull(server.takeRequest().getHeader("X-Hash"))
        }

    @Test
    fun `a malformed server hash on move is dropped`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201).setHeader("X-Hash-SHA256", "zz"))
            server.enqueue(MockResponse().setResponseCode(201).setHeader("X-Hash-SHA256", "ab".repeat(31)))
            server.enqueue(MockResponse().setResponseCode(201).setHeader("X-Hash-SHA256", "AB".repeat(32)))

            assertNull(client.finishChunkedUpload(id, "V", "a.mp4", 1L, true).getOrThrow().serverSha256)
            assertNull(client.finishChunkedUpload(id, "V", "a.mp4", 1L, true).getOrThrow().serverSha256)
            assertEquals(
                "AB".repeat(32),
                client.finishChunkedUpload(id, "V", "a.mp4", 1L, true).getOrThrow().serverSha256,
            )
        }

    @Test
    fun `move accepts only 201 and 204`() =
        runTest {
            listOf(
                200,
                202,
                207,
                400,
                404,
                409,
                412,
                500,
            ).forEach { server.enqueue(MockResponse().setResponseCode(it)) }

            listOf(200, 202, 207, 400, 404, 409, 412, 500).forEach { code ->
                val failure = client.finishChunkedUpload(id, "V", "a.mp4", 1L).exceptionOrNull()
                assertEquals(code, (failure as WebDavHttpException).code)
            }
        }

    @Test
    fun `every method fails on a redirect without a follow-up request`() =
        runTest {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path!!.startsWith("/elsewhere/")) {
                            MockResponse().setResponseCode(200)
                        } else {
                            MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere/"))
                        }
                }
            val calls: List<suspend () -> Result<Any>> =
                listOf(
                    { client.createChunkedUpload(id, "V", "a.mp4", 1L) },
                    { client.uploadChunk(id, 1, "V", "a.mp4", 1L, ByteArrayInputStream(ByteArray(1)), 1L) },
                    { client.finishChunkedUpload(id, "V", "a.mp4", 1L) },
                    { client.abortChunkedUpload(id) },
                )

            calls.forEach { call ->
                val before = server.requestCount
                val failure = call().exceptionOrNull()
                assertEquals(302, (failure as WebDavHttpException).code)
                assertEquals(before + 1, server.requestCount)
            }
        }

    @Test
    fun `413 and 507 map to the typed failures on every request`() =
        runTest {
            listOf(413, 507, 413, 507).forEach { server.enqueue(MockResponse().setResponseCode(it)) }

            val failures =
                listOf(
                    client.createChunkedUpload(id, "V", "a.mp4", 1L).exceptionOrNull(),
                    client
                        .uploadChunk(
                            id,
                            1,
                            "V",
                            "a.mp4",
                            1L,
                            ByteArrayInputStream(ByteArray(1)),
                            1L,
                        ).exceptionOrNull(),
                    client.finishChunkedUpload(id, "V", "a.mp4", 1L).exceptionOrNull(),
                    client.abortChunkedUpload(id).exceptionOrNull(),
                ).map { it!!.toUploadFailure() }

            assertEquals(
                listOf(
                    UploadFailure.TooLarge,
                    UploadFailure.StorageFull,
                    UploadFailure.TooLarge,
                    UploadFailure.StorageFull,
                ),
                failures,
            )
        }

    @Test
    fun `delete removes the upload folder and treats 404 as success`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(500))

            assertTrue(client.abortChunkedUpload(id).isSuccess)
            assertTrue(client.abortChunkedUpload(id).isSuccess)
            val failure = client.abortChunkedUpload(id).exceptionOrNull()

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/remote.php/dav/uploads/alice/$id", request.path)
            assertEquals(500, (failure as WebDavHttpException).code)
        }

    @Test
    fun `invalid ids and segments fail without any request`() =
        runTest {
            val stream = ByteArrayInputStream(ByteArray(1))
            val failures =
                listOf(
                    client.createChunkedUpload("bad", "V", "a.mp4", 1L),
                    client.createChunkedUpload("../x", "V", "a.mp4", 1L),
                    client.createChunkedUpload(id, "V/../X", "a.mp4", 1L),
                    client.createChunkedUpload(id, "V", "..", 1L),
                    client.createChunkedUpload(id, "V", "a/b.mp4", 1L),
                    client.uploadChunk(id, 0, "V", "a.mp4", 1L, stream, 1L),
                    client.uploadChunk(id, 10_001, "V", "a.mp4", 1L, stream, 1L),
                    client.uploadChunk("bad", 1, "V", "a.mp4", 1L, stream, 1L),
                    client.finishChunkedUpload("bad", "V", "a.mp4", 1L),
                    client.finishChunkedUpload(id, "V", "\u0000", 1L),
                    client.abortChunkedUpload("bad"),
                    client.abortChunkedUpload("$id/../x"),
                )

            failures.forEach { assertTrue(it.isFailure) }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `urls that are not nextcloud files urls send no chunked request`() =
        runTest {
            val legacy = clientFor("/remote.php/webdav/")
            val plain = clientFor("/")
            val unsafe = clientFor("/remote.php/dav/files/%2e%2e/")

            listOf(legacy, plain, unsafe).forEach { other ->
                assertTrue(other.createChunkedUpload(id, "V", "a.mp4", 1L).isFailure)
                assertTrue(other.abortChunkedUpload(id).isFailure)
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a sub path install uses its own uploads collection`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            val sub = clientFor("/nextcloud/remote.php/dav/files/alice/")

            sub.createChunkedUpload(id, "V", "a.mp4", 1L).getOrThrow()

            val request = server.takeRequest()
            assertEquals("/nextcloud/remote.php/dav/uploads/alice/$id", request.path)
            assertEquals(
                server.url("/nextcloud/remote.php/dav/files/alice/V/a.mp4").toString(),
                request.getHeader("Destination"),
            )
        }

    @Test
    fun `a slow chunk is cancelled with its coroutine`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            var result: Result<Unit>? = null
            val job =
                launch(Dispatchers.IO) {
                    result = client.uploadChunk(id, 1, "V", "a.mp4", 10L, ByteArrayInputStream(ByteArray(10)), 10L)
                }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            assertNull(result)
        }

    @Test
    fun `a slow move is cancelled with its coroutine`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            var result: Result<*>? = null
            val job = launch(Dispatchers.IO) { result = client.finishChunkedUpload(id, "V", "a.mp4", 10L) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            assertNull(result)
        }

    @Test
    fun `generated transfer ids are accepted by the client`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))

            assertTrue(client.createChunkedUpload(newTransferId(), "V", "a.mp4", 1L).isSuccess)
        }
}
