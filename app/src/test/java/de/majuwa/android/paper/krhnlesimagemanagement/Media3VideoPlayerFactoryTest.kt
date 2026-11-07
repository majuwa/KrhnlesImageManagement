package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindListingLimits
import de.majuwa.android.paper.krhnlesimagemanagement.data.RepositoryClosedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeVideoUrlException
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.Media3VideoPlayerFactory
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlayback
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlaybackError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlaybackListener
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.isFinalVideoLoadError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.toVideoPlaybackError
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The network contract of video playback: the real [OkHttpDataSource] over the video call factory
 * and the real [Media3VideoPlayerFactory] against `MockWebServer` (issue #74). Decoding and
 * rendering are manual device checks.
 */
@RunWith(RobolectricTestRunner::class)
class Media3VideoPlayerFactoryTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var repo: AlbumsRepository

    private val path = "/remote.php/dav/files/user/Photos/Trip/clip.mp4"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        other = MockWebServer().also { it.start() }
        repo =
            AlbumsRepository(
                WebDavConfig(server.url("/remote.php/dav/files/user/").toString(), "testuser", "testpass", "Photos"),
                null,
                PropfindListingLimits(),
            )
    }

    @After
    fun tearDown() {
        repo.close()
        server.shutdown()
        other.shutdown()
    }

    private fun dataSource() = OkHttpDataSource.Factory(repo.videoCallFactory()).createDataSource()

    private fun spec(
        url: String = server.url(path).toString(),
        position: Long = 0,
    ) = DataSpec
        .Builder()
        .setUri(Uri.parse(url))
        .setPosition(position)
        .build()

    @Test
    fun `opening at a position sends a Range request with credentials for the configured origin`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(
                    206,
                ).setHeader("Content-Range", "bytes 500-999/1000")
                .setBody("x".repeat(500)),
        )
        val source = dataSource()

        source.open(spec(position = 500))
        source.close()

        val request = server.takeRequest()
        assertEquals("bytes=500-", request.getHeader("Range"))
        assertEquals(Credentials.basic("testuser", "testpass"), request.getHeader("Authorization"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
    }

    @Test
    fun `a redirect surfaces as a bad status and is not followed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen").toString()))
        val failure = runCatching { dataSource().open(spec()) }.exceptionOrNull()

        assertTrue("$failure", failure is HttpDataSource.InvalidResponseCodeException)
        assertEquals(302, (failure as HttpDataSource.InvalidResponseCodeException).responseCode)
        assertEquals(0, failure.responseBody.size)
        assertEquals(1, server.requestCount)
        assertEquals(0, other.requestCount)
    }

    @Test
    fun `an error status never carries the server body`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret server text".repeat(1000)))
        val failure = runCatching { dataSource().open(spec()) }.exceptionOrNull()

        assertTrue("$failure", failure is HttpDataSource.InvalidResponseCodeException)
        assertEquals(0, (failure as HttpDataSource.InvalidResponseCodeException).responseBody.size)
    }

    @Test
    fun `an unsafe url surfaces as a data source exception caused by the guard and sends nothing`() {
        val outside =
            runCatching { dataSource().open(spec(server.url("/remote.php/dav/files/user/Other/x.mp4").toString())) }
        val foreign = runCatching { dataSource().open(spec(other.url(path).toString())) }

        for (result in listOf(outside, foreign)) {
            val failure = result.exceptionOrNull()
            assertTrue("$failure", failure is HttpDataSource.HttpDataSourceException)
            assertTrue(generateSequence(failure) { it.cause }.any { it is UnsafeVideoUrlException })
        }
        assertEquals(0, server.requestCount)
        assertEquals(0, other.requestCount)
    }

    @Test
    fun `a closed repository surfaces as a data source exception caused by the guard`() {
        val source = dataSource()
        repo.close()
        val failure = runCatching { source.open(spec()) }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it is RepositoryClosedException })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `status guard and refusal errors are final, transient errors are retried`() {
        val status = HttpDataSource.InvalidResponseCodeException(401, "m", null, emptyMap(), spec(), ByteArray(0))
        assertTrue(isFinalVideoLoadError(status))
        assertTrue(
            isFinalVideoLoadError(
                HttpDataSource.InvalidResponseCodeException(302, "m", null, emptyMap(), spec(), ByteArray(0)),
            ),
        )
        assertTrue(isFinalVideoLoadError(UnsafeVideoUrlException()))
        assertTrue(isFinalVideoLoadError(RepositoryClosedException()))
        assertTrue(
            isFinalVideoLoadError(
                HttpDataSource.HttpDataSourceException(UnsafeVideoUrlException(), spec(), 2001, 1),
            ),
        )
        assertFalse(isFinalVideoLoadError(SocketTimeoutException("t")))
        assertFalse(isFinalVideoLoadError(IOException("reset")))
    }

    // ── The real player ────────────────────────────────────────────────────────

    private class Events : VideoPlaybackListener {
        val error = AtomicReference<PlaybackException?>()

        override fun onPlayingChanged(isPlaying: Boolean) = Unit

        override fun onReady() = Unit

        override fun onError(error: PlaybackException) {
            this.error.set(error)
        }
    }

    private fun startPlayer(url: String): Pair<VideoPlayback, Events> {
        val application = RuntimeEnvironment.getApplication() as Application
        val events = Events()
        // The player reports through the listener; set it right after creation, before the loop runs.
        val playback = Media3VideoPlayerFactory(application).create(url, repo.videoCallFactory())
        playback.listener = events
        return playback to events
    }

    private fun awaitError(events: Events): PlaybackException? {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (events.error.get() == null && System.nanoTime() < deadline) {
            // The playback thread's delayed messages only run when the (paused) clock advances.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        return events.error.get()
    }

    @Test
    fun `a 401 ends playback with one request and no retries`() {
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    MockResponse().setResponseCode(401)
            }
        val (playback, events) = startPlayer(server.url(path).toString())

        val error = awaitError(events)
        // A retry would arrive within the default back-off (1 s) at the latest.
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(20)
        }
        playback.release()

        assertNotNull(error)
        assertEquals(VideoPlaybackError.Server(AlbumsError.AuthFailed), error!!.toVideoPlaybackError())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a redirect ends playback as Redirected without following it`() {
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    MockResponse().setResponseCode(302).setHeader("Location", other.url("/x").toString())
            }
        val (playback, events) = startPlayer(server.url(path).toString())

        val error = awaitError(events)
        playback.release()

        assertEquals(VideoPlaybackError.Redirected, error!!.toVideoPlaybackError())
        assertEquals(1, server.requestCount)
        assertEquals(0, other.requestCount)
    }

    @Test
    fun `an unsafe url ends playback without a request or a crash`() {
        val (playback, events) = startPlayer(server.url("/remote.php/dav/files/user/Other/x.mp4").toString())

        val error = awaitError(events)
        playback.release()

        assertEquals(VideoPlaybackError.Server(AlbumsError.Unknown), error!!.toVideoPlaybackError())
        assertEquals(0, server.requestCount)
    }
}
