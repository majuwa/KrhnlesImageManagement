package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlayback
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlaybackListener
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlayerFactory
import okhttp3.Call

/** A player that records calls; tests drive its events through [emitReady], [emitPlaying] and [emitError]. */
class FakeVideoPlayback(
    val streamUrl: String,
    val callFactory: Call.Factory,
) : VideoPlayback {
    override var isPlaying: Boolean = true
    override var positionMs: Long = 0
    override var durationMs: Long = 0
    override var videoAspectRatio: Float = 0f
    override var listener: VideoPlaybackListener? = null

    var playCalls = 0
        private set
    var pauseCalls = 0
        private set
    val seeks = mutableListOf<Long>()
    var released = false
        private set
    var surface: SurfaceView? = null
        private set

    /** The video played to its end; a `play()` then restarts it from the beginning like the real player. */
    var ended = false
        private set

    override fun play() {
        playCalls++
        if (ended) {
            ended = false
            positionMs = 0
        }
        isPlaying = true
        listener?.onPlayingChanged(true)
    }

    override fun pause() {
        pauseCalls++
        isPlaying = false
        listener?.onPlayingChanged(false)
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        this.positionMs = positionMs
    }

    override fun setVideoSurface(surface: SurfaceView?) {
        this.surface = surface
    }

    override fun release() {
        released = true
    }

    fun emitReady() = listener?.onReady()

    fun emitPlaying(playing: Boolean) {
        isPlaying = playing
        listener?.onPlayingChanged(playing)
    }

    fun emitEnded() {
        ended = true
        emitPlaying(false)
    }

    fun emitError(error: PlaybackException) = listener?.onError(error)
}

/** Creates [FakeVideoPlayback]s and records them. */
class FakeVideoPlayerFactory : VideoPlayerFactory {
    val created = mutableListOf<FakeVideoPlayback>()
    var failure: RuntimeException? = null

    override fun create(
        streamUrl: String,
        callFactory: Call.Factory,
    ): VideoPlayback {
        failure?.let { throw it }
        return FakeVideoPlayback(streamUrl, callFactory).also { created += it }
    }
}
