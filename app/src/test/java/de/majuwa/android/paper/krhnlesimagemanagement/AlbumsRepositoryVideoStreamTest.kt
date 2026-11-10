package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindListingLimits
import de.majuwa.android.paper.krhnlesimagemanagement.data.RepositoryClosedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeVideoUrlException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.memoriesAwareDispatcher
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The video stream client of [AlbumsRepository]: credentials, redirects, guard and close (issue #74). */
@RunWith(RobolectricTestRunner::class)
class AlbumsRepositoryVideoStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var repo: AlbumsRepository

    private val video =
        RemotePhoto("clip.mp4", "/remote.php/dav/files/user/Trip/clip.mp4", "7", "video/mp4", MediaKind.Video)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        other = MockWebServer().also { it.start() }
        repo = newRepo()
    }

    @After
    fun tearDown() {
        repo.close()
        server.shutdown()
        other.shutdown()
    }

    private fun newRepo() =
        AlbumsRepository(
            WebDavConfig(server.url("/remote.php/dav/files/user/").toString(), "testuser", "testpass", "Photos"),
            null,
            PropfindListingLimits(),
        )

    private fun streamUrl(path: String = "/remote.php/dav/files/user/Photos/Trip/clip.mp4") =
        server.url(path).toString()

    private fun get(
        url: String,
        factory: Call.Factory = repo.videoCallFactory(),
        range: String? = null,
    ): Response =
        factory
            .newCall(
                Request
                    .Builder()
                    .url(url)
                    .apply { if (range != null) header("Range", range) }
                    .build(),
            ).execute()

    /** Runs [request] with `enqueue`, as `OkHttpDataSource` does; returns the failure or null. */
    private fun enqueueFailure(request: Request): Throwable? {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        repo.videoCallFactory().newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    failure.set(e)
                    done.countDown()
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    response.close()
                    done.countDown()
                }
            },
        )
        assertTrue("callback not called", done.await(5, TimeUnit.SECONDS))
        return failure.get()
    }

    @Test
    fun `credentials go to the configured origin and the Range header is unchanged`() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("abc"))

        get(streamUrl(), range = "bytes=100-").use { assertEquals(206, it.code) }

        val recorded = server.takeRequest()
        assertEquals(Credentials.basic("testuser", "testpass"), recorded.getHeader("Authorization"))
        assertEquals("bytes=100-", recorded.getHeader("Range"))
        assertEquals("identity", recorded.getHeader("Accept-Encoding"))
        assertEquals("GET", recorded.method)
    }

    @Test
    fun `a URL on another origin fails without a request and without credentials`() {
        val url = other.url("/remote.php/dav/files/user/Photos/Trip/clip.mp4").toString()

        val failure = runCatching { get(url) }.exceptionOrNull()

        assertTrue("$failure", failure is UnsafeVideoUrlException)
        assertEquals(0, other.requestCount)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `redirects are not followed`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(
                        code,
                    ).setHeader("Location", other.url("/stolen").toString())
                    .setBody("moved"),
            )
            val before = server.requestCount

            get(streamUrl()).use {
                assertEquals(code, it.code)
                assertEquals("", it.body.string())
            }

            assertEquals("one request for $code", before + 1, server.requestCount)
            assertEquals(0, other.requestCount)
        }
    }

    @Test
    fun `urls outside the base folder and traversal attempts fail without a request`() {
        val urls =
            listOf(
                "/remote.php/dav/files/user/Other/clip.mp4",
                "/remote.php/dav/files/user/Photos/../Secret/clip.mp4",
                "/remote.php/dav/files/user/Photos/%2e%2e/Secret/clip.mp4",
                "/remote.php/dav/files/user/Photos/Trip%2Fclip.mp4",
                "/remote.php/dav/files/user/Photos/Trip%5Cclip.mp4",
                "/remote.php/dav/files/user/Photos/",
                "/remote.php/dav/files/user/Photos/Trip/clip.mp4?x=1",
            )
        for (path in urls) {
            val request = Request.Builder().url(server.url(path)).build()
            val sync = runCatching { repo.videoCallFactory().newCall(request).execute() }.exceptionOrNull()
            assertTrue("$path -> $sync", sync is UnsafeVideoUrlException)
            val async = enqueueFailure(request)
            assertTrue("$path -> $async", async is UnsafeVideoUrlException)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a non GET request fails without a request as an IOException`() {
        val request =
            Request
                .Builder()
                .url(streamUrl())
                .post("x".toRequestBody())
                .build()
        assertTrue(
            runCatching {
                repo.videoCallFactory().newCall(request).execute()
            }.exceptionOrNull() is UnsafeVideoUrlException,
        )
        assertTrue(enqueueFailure(request) is IOException)
        val delete =
            Request
                .Builder()
                .url(streamUrl())
                .delete()
                .build()
        assertTrue(enqueueFailure(delete) is UnsafeVideoUrlException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a closed repository fails without a request`() {
        val factory = repo.videoCallFactory()
        repo.close()

        val sync = runCatching { get(streamUrl(), factory) }.exceptionOrNull()
        val async = enqueueFailure(Request.Builder().url(streamUrl()).build())

        assertTrue("$sync", sync is RepositoryClosedException)
        assertTrue("$async", async is RepositoryClosedException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a factory requested after close is closed as well`() {
        repo.close()
        val sync = runCatching { get(streamUrl()) }.exceptionOrNull()
        assertTrue("$sync", sync is RepositoryClosedException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `close cancels a call blocked on a stalled body within seconds`() {
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(1024)))
                .setHeader("Content-Length", "100000")
                .setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )
        val blocked = AtomicReference<Throwable?>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val call = repo.videoCallFactory().newCall(Request.Builder().url(streamUrl()).build())
        Thread {
            started.countDown()
            blocked.set(runCatching { call.execute().use { it.body.bytes() } }.exceptionOrNull())
            finished.countDown()
        }.start()
        started.await()
        Thread.sleep(300)

        val begin = System.nanoTime()
        repo.close()

        assertTrue("close must unblock the call", finished.await(5, TimeUnit.SECONDS))
        assertNotNull(blocked.get())
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin) < 5)
    }

    private fun throttledBodyResponse() =
        MockResponse()
            .setHeader("Content-Length", "100000")
            .setBody(Buffer().write(ByteArray(100000)))
            .throttleBody(16, 10, TimeUnit.SECONDS)

    @Test
    fun `close cancels an execute call whose headers arrived and whose body is throttled`() {
        server.enqueue(throttledBodyResponse())
        val headersArrived = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val blocked = AtomicReference<Throwable?>()
        Thread {
            blocked.set(
                runCatching {
                    repo.videoCallFactory().newCall(Request.Builder().url(streamUrl()).build()).execute().use {
                        headersArrived.countDown()
                        it.body.bytes()
                    }
                }.exceptionOrNull(),
            )
            finished.countDown()
        }.start()
        assertTrue(headersArrived.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)

        repo.close()

        assertTrue("close must unblock the body read", finished.await(5, TimeUnit.SECONDS))
        assertTrue("${blocked.get()}", blocked.get() is IOException)
    }

    @Test
    fun `close cancels an enqueued call whose headers arrived and whose body is throttled`() {
        server.enqueue(throttledBodyResponse())
        val headersArrived = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val blocked = AtomicReference<Throwable?>()
        repo.videoCallFactory().newCall(Request.Builder().url(streamUrl()).build()).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    blocked.set(e)
                    finished.countDown()
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    headersArrived.countDown()
                    blocked.set(runCatching { response.use { it.body.bytes() } }.exceptionOrNull())
                    finished.countDown()
                }
            },
        )
        assertTrue(headersArrived.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)

        repo.close()

        assertTrue("close must unblock the body read", finished.await(5, TimeUnit.SECONDS))
        assertTrue("${blocked.get()}", blocked.get() is IOException)
    }

    @Test
    fun `closing the video client does not cancel a running album call`() {
        // The album client and the video client have different dispatchers.
        val albumHeadersSent = CountDownLatch(1)
        server.dispatcher =
            memoriesAwareDispatcher(
                MockResponse()
                    .setResponseCode(207)
                    .setBodyDelay(600, TimeUnit.MILLISECONDS)
                    .setBody(
                        "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response>" +
                            "<d:href>/remote.php/dav/files/user/Photos/Trip/</d:href><d:propstat><d:prop>" +
                            "<d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>" +
                            "</d:response></d:multistatus>",
                    ),
            )
        val result = AtomicReference<Result<*>>()
        val done = CountDownLatch(1)
        Thread {
            albumHeadersSent.countDown()
            result.set(
                kotlinx.coroutines.runBlocking {
                    repo.listPhotos("/remote.php/dav/files/user/Photos/Trip/")
                },
            )
            done.countDown()
        }.start()
        albumHeadersSent.await()
        Thread.sleep(150)
        repo.videoCallFactory() // builds the video client
        repo.close()

        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue("${result.get()}", result.get().isSuccess)
    }

    @Test
    fun `an error status yields an empty body without reading the announced one`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody(Buffer().write(ByteArray(8 * 1024 * 1024)))
                .setHeader("Content-Length", 64L * 1024 * 1024),
        )

        val begin = System.nanoTime()
        get(streamUrl()).use {
            assertEquals(404, it.code)
            assertEquals(0, it.body.bytes().size)
        }

        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin) < 5)
    }

    @Test
    fun `a success body is passed through unchanged`() {
        server.enqueue(MockResponse().setBody("video-bytes"))
        get(streamUrl()).use { assertEquals("video-bytes", it.body.string()) }
    }

    @Test
    fun `the client has no overall call timeout, no cache and no redirects`() {
        val client = repo.videoCallFactory() as okhttp3.OkHttpClient
        assertEquals(0, client.callTimeoutMillis)
        assertNull(client.cache)
        assertEquals(false, client.followRedirects)
        assertEquals(false, client.followSslRedirects)
    }

    @Test
    fun `videoStreamUrl is the WebDAV URL of a video only`() {
        val expected = server.url("/remote.php/dav/files/user/Photos/Trip/clip.mp4").toString()
        val inFolder = video.copy(href = "/remote.php/dav/files/user/Photos/Trip/clip.mp4")
        assertEquals(expected, repo.videoStreamUrl(inFolder))
        assertNull(repo.videoStreamUrl(inFolder.copy(mediaKind = MediaKind.Image)))
        assertNull(repo.videoStreamUrl(video.copy(href = "/remote.php/dav/files/user/Other/clip.mp4")))
        assertNull(repo.videoStreamUrl(video.copy(href = "/remote.php/dav/files/user/Photos/../x.mp4")))
    }
}
