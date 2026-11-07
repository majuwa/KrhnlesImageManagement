package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import androidx.media3.common.PlaybackException
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositorySource
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryUnavailableException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVideoPlayerFactory
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlaybackError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlaybackState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlayerViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VideoPlayerViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val factory = FakeVideoPlayerFactory()
    private var repo = FakeAlbumsRepository()
    private var source = AlbumsRepositorySource { Result.success(repo) }
    private val video = RemotePhoto("v.mp4", "/Album/v.mp4", "7", "video/mp4", MediaKind.Video)
    private val other = RemotePhoto("w.mp4", "/Album/w.mp4", "8", "video/mp4", MediaKind.Video)
    private lateinit var viewModel: VideoPlayerViewModel

    private fun notConfigured() =
        AlbumsRepositoryUnavailableException(AlbumsRepositoryUnavailableException.Reason.NOT_CONFIGURED)

    private fun decodingFailure() = PlaybackException("x", null, PlaybackException.ERROR_CODE_DECODING_FAILED)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = VideoPlayerViewModel(RuntimeEnvironment.getApplication() as Application, factory)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `play creates exactly one player for the stream url and the repository's call factory`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            assertEquals(VideoPlaybackState.Preparing(video.href), viewModel.state.value)
            advanceUntilIdle()

            assertEquals(1, factory.created.size)
            assertEquals(repo.videoStreamUrl(video), factory.created.single().streamUrl)
            assertSame(repo.videoCallFactory(), factory.created.single().callFactory)
            assertSame(factory.created.single(), viewModel.playback.value)
        }

    @Test
    fun `player events drive the state`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()
            val player = factory.created.single()

            player.emitReady()
            assertEquals(VideoPlaybackState.Playing(video.href, true), viewModel.state.value)
            player.emitPlaying(false)
            assertEquals(VideoPlaybackState.Playing(video.href, false), viewModel.state.value)
        }

    @Test
    fun `re-entry while preparing or playing is ignored`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val slow =
                AlbumsRepositorySource {
                    gate.await()
                    Result.success(repo)
                }
            viewModel.play(video, slow)
            runCurrent()
            viewModel.play(video, slow)
            viewModel.play(video, source)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, factory.created.size)

            viewModel.play(video, source)
            advanceUntilIdle()
            assertEquals(1, factory.created.size)
        }

    @Test
    fun `playing another video releases the first player`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()
            viewModel.play(other, source)
            advanceUntilIdle()

            assertEquals(2, factory.created.size)
            assertTrue(factory.created[0].released)
            assertFalse(factory.created[1].released)
            assertEquals(VideoPlaybackState.Preparing(other.href), viewModel.state.value)
        }

    @Test
    fun `stop releases the player and returns to idle`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()

            viewModel.stop()

            assertTrue(factory.created.single().released)
            assertEquals(VideoPlaybackState.Idle, viewModel.state.value)
            assertNull(viewModel.playback.value)
        }

    @Test
    fun `a failed repository lookup is a failure without a player`() =
        runTest(testDispatcher) {
            viewModel.play(
                video,
                AlbumsRepositorySource {
                    Result.failure(notConfigured())
                },
            )
            advanceUntilIdle()

            assertEquals(
                VideoPlaybackState.Failed(video.href, VideoPlaybackError.Server(AlbumsError.NotConfigured)),
                viewModel.state.value,
            )
            assertTrue(factory.created.isEmpty())
        }

    @Test
    fun `a missing stream url is a failure without a player`() =
        runTest(testDispatcher) {
            repo.videoStreamUrlAvailable = false
            viewModel.play(video, source)
            advanceUntilIdle()

            assertEquals(
                VideoPlaybackState.Failed(video.href, VideoPlaybackError.Server(AlbumsError.NotFound)),
                viewModel.state.value,
            )
            assertTrue(factory.created.isEmpty())
        }

    @Test
    fun `a throwing player factory is a failure instead of a crash`() =
        runTest(testDispatcher) {
            factory.failure = IllegalStateException("boom")
            viewModel.play(video, source)
            advanceUntilIdle()

            assertTrue(viewModel.state.value is VideoPlaybackState.Failed)
            assertNull(viewModel.playback.value)
        }

    @Test
    fun `a stale start never publishes after stop`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            viewModel.play(
                video,
                AlbumsRepositorySource {
                    gate.await()
                    Result.success(repo)
                },
            )
            runCurrent()

            viewModel.stop()
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(factory.created.isEmpty())
            assertEquals(VideoPlaybackState.Idle, viewModel.state.value)
            assertNull(viewModel.playback.value)
        }

    @Test
    fun `a player error releases the player and reports a typed error`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()
            val player = factory.created.single()

            player.emitError(PlaybackException("secret", null, PlaybackException.ERROR_CODE_DECODING_FAILED))

            assertTrue(player.released)
            assertEquals(
                VideoPlaybackState.Failed(video.href, VideoPlaybackError.UnsupportedFormat),
                viewModel.state.value,
            )
            assertNull(viewModel.playback.value)
        }

    @Test
    fun `retry after a failure starts the video again`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()
            factory.created.single().emitError(decodingFailure())

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(2, factory.created.size)
            assertEquals(VideoPlaybackState.Preparing(video.href), viewModel.state.value)
        }

    @Test
    fun `retry is ignored unless the playback failed`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(1, factory.created.size)
        }

    @Test
    fun `pause pauses and resume with an unchanged repository continues the same player`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()
            val player = factory.created.single()
            player.emitReady()

            viewModel.pause()
            assertEquals(1, player.pauseCalls)
            viewModel.resume()
            advanceUntilIdle()

            assertEquals(1, player.playCalls)
            assertFalse(player.released)
            assertEquals(1, factory.created.size)
        }

    @Test
    fun `resume with a changed repository releases the player and builds a new one`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()
            val first = factory.created.single()
            first.emitReady()
            viewModel.pause()

            repo = FakeAlbumsRepository() // a pin / config change rebuilt the repository
            viewModel.resume()
            advanceUntilIdle()

            assertTrue(first.released)
            assertEquals(0, first.playCalls)
            assertEquals(2, factory.created.size)
            assertNotSame(first, viewModel.playback.value)
        }

    @Test
    fun `resume after the configuration disappeared fails without continuing`() =
        runTest(testDispatcher) {
            var available = true
            val flaky =
                AlbumsRepositorySource {
                    if (available) {
                        Result.success(repo)
                    } else {
                        Result.failure(notConfigured())
                    }
                }
            viewModel.play(video, flaky)
            advanceUntilIdle()
            val player = factory.created.single()
            viewModel.pause()

            available = false
            viewModel.resume()
            advanceUntilIdle()

            assertTrue(player.released)
            assertEquals(0, player.playCalls)
            assertTrue(viewModel.state.value is VideoPlaybackState.Failed)
        }

    @Test
    fun `clearing the view model releases the player`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()

            val clear = VideoPlayerViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
            clear.invoke(viewModel)

            assertTrue(factory.created.single().released)
        }

    @Test
    fun `events of a released player are ignored`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()
            val player = factory.created.single()
            val captured = player.listener!!
            viewModel.stop()

            captured.onReady()
            captured.onPlayingChanged(true)
            captured.onError(decodingFailure())

            assertTrue(player.released)
            assertEquals(VideoPlaybackState.Idle, viewModel.state.value)
            assertNull(viewModel.playback.value)
        }

    @Test
    fun `a pause while the player is still being built starts it paused`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            viewModel.pause() // ON_STOP before the start job ran
            advanceUntilIdle()

            val player = factory.created.single()
            assertEquals(1, player.pauseCalls)
            player.emitReady()
            assertEquals(VideoPlaybackState.Playing(video.href, false), viewModel.state.value)
        }

    @Test
    fun `resume before the player exists cancels the pending pause`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            viewModel.pause()
            viewModel.resume()
            advanceUntilIdle()

            assertEquals(0, factory.created.single().pauseCalls)
        }

    @Test
    fun `a new play after a pause starts playing`() =
        runTest(testDispatcher) {
            viewModel.pause()
            viewModel.play(video, source)
            advanceUntilIdle()

            assertEquals(0, factory.created.single().pauseCalls)
        }

    @Test
    fun `resume after the video ended restarts it from the beginning`() =
        runTest(testDispatcher) {
            viewModel.play(video, source)
            advanceUntilIdle()
            val player = factory.created.single()
            player.emitReady()
            player.emitEnded()
            assertEquals(VideoPlaybackState.Playing(video.href, false), viewModel.state.value)

            viewModel.resume()
            advanceUntilIdle()

            assertEquals(0L, player.positionMs)
            assertFalse(player.ended)
            assertEquals(VideoPlaybackState.Playing(video.href, true), viewModel.state.value)
        }
}
