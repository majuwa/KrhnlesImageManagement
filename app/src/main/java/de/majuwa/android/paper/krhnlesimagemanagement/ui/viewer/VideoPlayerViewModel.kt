package de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositorySource
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.toAlbumsError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Playback of one album video at a time, scoped to the viewer entry (plain `viewModel()`; it never
 * references the album ViewModel: the viewer passes the repository source like the reviews do).
 *
 * [play] is a guarded one-shot (job + generation): it asks `repositories.get()`, the repository's
 * `videoStreamUrl()` and `videoCallFactory()`, then the [VideoPlayerFactory] builds the player.
 * Playback is always started by the user and nothing is preloaded. [resume] re-asks the source and
 * continues the existing player only if it still yields the repository the player was built with
 * (a pin / config change releases it and starts again), so a resumed stream never outlives it.
 */
class VideoPlayerViewModel internal constructor(
    application: Application,
    private val playerFactory: VideoPlayerFactory,
) : AndroidViewModel(application) {
    /** Used by the default factory. */
    constructor(application: Application) : this(application, Media3VideoPlayerFactory(application))

    private val _state = MutableStateFlow<VideoPlaybackState>(VideoPlaybackState.Idle)
    val state: StateFlow<VideoPlaybackState> = _state.asStateFlow()

    private val _playback = MutableStateFlow<VideoPlayback?>(null)

    /** The live player of [state], or `null`; the surface and the controls bind to it. */
    val playback: StateFlow<VideoPlayback?> = _playback.asStateFlow()

    private var startJob: Job? = null
    private var resumeJob: Job? = null
    private var generation = 0
    private var builtWith: AlbumsRepositoryContract? = null
    private var lastPhoto: RemotePhoto? = null
    private var lastRepositories: AlbumsRepositorySource? = null

    /** Starts [photo]; ignored while it is already starting or playing, replaces another video. */
    fun play(
        photo: RemotePhoto,
        repositories: AlbumsRepositorySource,
    ) {
        if (startJob?.isActive == true) return
        val current = _state.value
        if (current is VideoPlaybackState.Playing && current.href == photo.href) return
        if (current is VideoPlaybackState.Preparing && current.href == photo.href) return
        releasePlayer()
        start(photo, repositories)
    }

    private fun start(
        photo: RemotePhoto,
        repositories: AlbumsRepositorySource,
    ) {
        lastPhoto = photo
        lastRepositories = repositories
        val gen = ++generation
        _state.value = VideoPlaybackState.Preparing(photo.href)
        startJob =
            viewModelScope.launch {
                try {
                    val repository = repositories.get().getOrThrow()
                    val url = repository.videoStreamUrl(photo) ?: throw IllegalArgumentException("No stream URL")
                    if (gen != generation) return@launch
                    val playback = playerFactory.create(url, repository.videoCallFactory())
                    if (gen != generation) {
                        playback.release()
                        return@launch
                    }
                    builtWith = repository
                    bind(playback, photo.href)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (gen != generation) return@launch
                    // Type only: exception messages may contain server details.
                    Log.w(TAG, "Could not start video playback: ${e.javaClass.simpleName}")
                    fail(photo.href, VideoPlaybackError.Server(e.toAlbumsError()))
                }
            }
    }

    private fun bind(
        playback: VideoPlayback,
        href: String,
    ) {
        playback.listener =
            object : VideoPlaybackListener {
                override fun onPlayingChanged(isPlaying: Boolean) {
                    if (_playback.value !== playback) return
                    if (_state.value is VideoPlaybackState.Playing) {
                        _state.value =
                            VideoPlaybackState.Playing(href, isPlaying)
                    }
                }

                override fun onReady() {
                    if (_playback.value !== playback) return
                    _state.value = VideoPlaybackState.Playing(href, playback.isPlaying)
                }

                override fun onError(error: PlaybackException) {
                    if (_playback.value !== playback) return
                    // Error code only: a Media3 message can contain the request URL.
                    Log.w(TAG, "Video playback failed: code ${error.errorCode}")
                    releasePlayer()
                    _state.value = VideoPlaybackState.Failed(href, error.toVideoPlaybackError())
                }
            }
        _playback.value = playback
    }

    private fun fail(
        href: String,
        error: VideoPlaybackError,
    ) {
        releasePlayer()
        _state.value = VideoPlaybackState.Failed(href, error)
    }

    /** Pauses the current video (the viewer went to the background); the player is kept. */
    fun pause() {
        _playback.value?.pause()
    }

    /**
     * Continues a paused video if the repository source still yields the repository the player
     * was built with; otherwise the player is released and the video starts again.
     */
    fun resume() {
        val playback = _playback.value ?: return
        val repositories = lastRepositories ?: return
        val photo = lastPhoto ?: return
        if (resumeJob?.isActive == true) return
        resumeJob =
            viewModelScope.launch {
                val repository = repositories.get().getOrNull()
                if (_playback.value !== playback) return@launch
                if (repository != null && repository === builtWith) {
                    playback.play()
                } else {
                    releasePlayer()
                    start(photo, repositories)
                }
            }
    }

    /** Stops and releases the player; a start that is still running never publishes afterwards. */
    fun stop() {
        generation++
        startJob?.cancel()
        resumeJob?.cancel()
        releasePlayer()
        _state.value = VideoPlaybackState.Idle
    }

    /** Starts the last video again after a failure. */
    fun retry() {
        val photo = lastPhoto ?: return
        val repositories = lastRepositories ?: return
        if (_state.value !is VideoPlaybackState.Failed) return
        stop()
        play(photo, repositories)
    }

    /** Releases the current player (if any) without touching [state]. */
    private fun releasePlayer() {
        val playback = _playback.value
        _playback.value = null
        builtWith = null
        playback?.let {
            it.listener = null
            it.release()
        }
    }

    override fun onCleared() {
        generation++
        releasePlayer()
    }

    private companion object {
        const val TAG = "VideoPlayerViewModel"
    }
}
