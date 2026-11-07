package de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer

import android.app.Application
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import de.majuwa.android.paper.krhnlesimagemanagement.data.RepositoryClosedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeVideoUrlException
import okhttp3.Call
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * True for a load failure that must not be retried: a bad HTTP status (a 401 would otherwise send
 * several failed logins and trip Nextcloud's brute-force throttling; a redirect would be requested
 * again), one of the video client's own guard refusals or a TLS / certificate failure (a pinned or
 * untrusted certificate does not become trusted by retrying) anywhere in the cause chain. Transient
 * network errors (timeouts, resets) keep the default retries.
 */
internal fun isFinalVideoLoadError(error: IOException): Boolean =
    generateSequence<Throwable>(error) { it.cause }.take(MAX_LOAD_ERROR_CAUSES).any {
        it is HttpDataSource.InvalidResponseCodeException ||
            it is UnsafeVideoUrlException ||
            it is RepositoryClosedException ||
            it is SSLException ||
            it is CertificateException
    }

private const val MAX_LOAD_ERROR_CAUSES = 16

/** Upper bound of the read-ahead buffer of one player; the device-derived default can be far larger. */
internal const val VIDEO_TARGET_BUFFER_BYTES = 32 * 1024 * 1024

/**
 * A byte cap that wins over the time thresholds: a server that ignores `Range` or a high-bitrate
 * file can then never make the player buffer more than [VIDEO_TARGET_BUFFER_BYTES].
 */
@OptIn(UnstableApi::class)
internal fun videoLoadControl(): LoadControl =
    DefaultLoadControl
        .Builder()
        .setTargetBufferBytes(VIDEO_TARGET_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

/**
 * The only place that builds a Media3 player. Never the one-argument `ExoPlayer.Builder(context)`,
 * `DefaultMediaSourceFactory`, `DefaultDataSource` or a cache: they route a `MediaItem` through
 * `DefaultHttpDataSource` (system trust only, no credentials, platform redirect handling), keep
 * every extractor and leave copies on disk. Here the data source is the OkHttp one over the album
 * flow's video call factory, the extractors are the restricted mp4 / matroska set and a failed
 * request is never retried for an HTTP status or a guard refusal.
 */
class Media3VideoPlayerFactory(
    private val application: Application,
) : VideoPlayerFactory {
    @OptIn(UnstableApi::class)
    override fun create(
        streamUrl: String,
        callFactory: Call.Factory,
    ): VideoPlayback {
        // Since Media3 1.4 subtitles are parsed during extraction: an extractor without a parser
        // factory makes a file with an embedded subtitle track fail.
        val subtitleParsers = DefaultSubtitleParserFactory()
        val extractors =
            ExtractorsFactory {
                arrayOf<Extractor>(
                    Mp4Extractor(subtitleParsers),
                    FragmentedMp4Extractor(subtitleParsers),
                    MatroskaExtractor(subtitleParsers),
                )
            }
        val retryPolicy =
            object : DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                    if (isFinalVideoLoadError(loadErrorInfo.exception)) {
                        C.TIME_UNSET
                    } else {
                        super.getRetryDelayMsFor(loadErrorInfo)
                    }
            }
        val mediaSourceFactory =
            ProgressiveMediaSource
                .Factory(OkHttpDataSource.Factory(callFactory), extractors)
                .setLoadErrorHandlingPolicy(retryPolicy)
        val player =
            ExoPlayer
                .Builder(application, mediaSourceFactory)
                .setLoadControl(videoLoadControl())
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .build()
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.playWhenReady = true
        return ExoVideoPlayback(player)
    }
}

/** [VideoPlayback] over an [ExoPlayer] (stable `Player` API only). */
internal class ExoVideoPlayback(
    private val player: ExoPlayer,
) : VideoPlayback {
    private var reportedPlaying = true
    private var reportedReady = false
    private var surface: SurfaceView? = null

    override var listener: VideoPlaybackListener? = null

    private val playerListener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                val playing = isPlaying
                if (playing != reportedPlaying) {
                    reportedPlaying = playing
                    listener?.onPlayingChanged(playing)
                }
                if (!reportedReady && player.playbackState == Player.STATE_READY) {
                    reportedReady = true
                    listener?.onReady()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error)
            }
        }

    init {
        player.addListener(playerListener)
    }

    override val isPlaying: Boolean
        get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED

    override val positionMs: Long get() = player.currentPosition.coerceAtLeast(0L)

    override val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

    override val videoAspectRatio: Float
        get() {
            val size = player.videoSize
            return if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        }

    override fun play() {
        // After the end a bare play() does nothing; an idle (stopped / failed-and-reset) player needs prepare().
        val state = player.playbackState
        if (state == Player.STATE_ENDED) player.seekToDefaultPosition()
        if (state == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setVideoSurface(surface: SurfaceView?) {
        if (this.surface === surface) return
        this.surface?.let { player.clearVideoSurfaceView(it) }
        this.surface = surface
        if (surface != null) player.setVideoSurfaceView(surface)
    }

    override fun release() {
        player.removeListener(playerListener)
        listener = null
        player.release()
    }
}
