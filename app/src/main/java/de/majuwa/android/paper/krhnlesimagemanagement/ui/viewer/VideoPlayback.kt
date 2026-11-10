package de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer

import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import okhttp3.Call

/**
 * The part of a player the viewer needs. The only implementation that touches Media3 is
 * [Media3VideoPlayerFactory]; the ViewModel and the UI (and their tests) see only this interface.
 * Methods are called on the main thread.
 */
interface VideoPlayback {
    /** The user's intent: playing, or going to play as soon as enough is buffered. */
    val isPlaying: Boolean

    /** Current position; 0 while unknown. */
    val positionMs: Long

    /** Duration; 0 while unknown. */
    val durationMs: Long

    /** Width / height of the decoded video; 0 while unknown. */
    val videoAspectRatio: Float

    /** Receives the player's events; set to `null` before [release]. */
    var listener: VideoPlaybackListener?

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Shows the video on [surface], or detaches the current one when `null`. */
    fun setVideoSurface(surface: SurfaceView?)

    fun release()
}

interface VideoPlaybackListener {
    /** The playing intent changed (play / pause / ended). */
    fun onPlayingChanged(isPlaying: Boolean)

    /** The first frames are ready to play. */
    fun onReady()

    fun onError(error: PlaybackException)
}

/** Builds a prepared, started player for [streamUrl]; every network request goes through [callFactory]. */
fun interface VideoPlayerFactory {
    fun create(
        streamUrl: String,
        callFactory: Call.Factory,
    ): VideoPlayback
}

/** What the viewer shows for the video page that was started. */
sealed interface VideoPlaybackState {
    data object Idle : VideoPlaybackState

    data class Preparing(
        val href: String,
    ) : VideoPlaybackState

    data class Playing(
        val href: String,
        val isPlaying: Boolean,
    ) : VideoPlaybackState

    data class Failed(
        val href: String,
        val error: VideoPlaybackError,
    ) : VideoPlaybackState
}
