package de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer

import android.content.res.Resources
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.durationDescription
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.formatDurationBadge
import kotlinx.coroutines.delay

private const val POSITION_POLL_MS = 250L

/** What a video page shows; derived from the shared [VideoPlaybackState] for this page's video only. */
internal sealed interface VideoPageState {
    data object Idle : VideoPageState

    data object Preparing : VideoPageState

    data class Playing(
        val isPlaying: Boolean,
    ) : VideoPageState

    data class Failed(
        val error: VideoPlaybackError,
    ) : VideoPageState
}

/**
 * One video in the viewer pager: its poster (or a placeholder) with a play button; after a tap the
 * video surface with Compose controls. Nothing is requested for the video itself before the tap.
 * The page is described by [description] ("Video N of M"), never by a file name.
 */
@Composable
internal fun VideoPage(
    description: String,
    posterUrl: String?,
    state: VideoPageState,
    playback: VideoPlayback?,
    controlsVisible: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description },
    ) {
        val live = playback.takeIf { state is VideoPageState.Playing || state is VideoPageState.Preparing }
        if (live != null && state is VideoPageState.Playing) {
            VideoSurface(live)
        } else {
            Poster(posterUrl)
        }
        when (state) {
            VideoPageState.Idle -> {
                PlayButton(Modifier.align(Alignment.Center), onClick = onPlay)
            }

            VideoPageState.Preparing -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
            }

            is VideoPageState.Playing -> {
                if (controlsVisible && live != null) {
                    VideoControls(
                        playback = live,
                        isPlaying = state.isPlaying,
                        onPause = onPause,
                        onResume = onResume,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            is VideoPageState.Failed -> {
                FailureContent(state.error, onRetry, Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun Poster(url: String?) {
    val context = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (url != null && !failed) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Movie,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(96.dp),
            )
        }
    }
}

@Composable
private fun PlayButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.size(88.dp)) {
        Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = stringResource(R.string.cd_play_video),
            tint = Color.White,
            modifier = Modifier.size(72.dp),
        )
    }
}

private val NOT_RETRYABLE =
    setOf(
        VideoPlaybackError.UnsupportedFormat,
        VideoPlaybackError.Server(AlbumsError.NotConfigured),
        VideoPlaybackError.Server(AlbumsError.ConfigurationChanged),
    )

@Composable
private fun FailureContent(
    error: VideoPlaybackError,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = videoPlaybackErrorMessage(LocalResources.current, error),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        // Retrying cannot help once the configuration is gone or the format is unsupported.
        if (error !in NOT_RETRYABLE) Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/** The video, shown on a [SurfaceView] (stable `Player.setVideoSurfaceView`), sized to the video's aspect ratio. */
@Composable
private fun VideoSurface(playback: VideoPlayback) {
    var aspectRatio by remember(playback) { mutableFloatStateOf(playback.videoAspectRatio) }
    LaunchedEffect(playback) {
        while (true) {
            aspectRatio = playback.videoAspectRatio
            delay(POSITION_POLL_MS)
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val sizing = if (aspectRatio > 0f) Modifier.aspectRatio(aspectRatio) else Modifier.fillMaxSize()
        AndroidView(
            factory = { SurfaceView(it) },
            update = { playback.setVideoSurface(it) },
            onRelease = { playback.setVideoSurface(null) },
            modifier = sizing,
        )
    }
    DisposableEffect(playback) { onDispose { playback.setVideoSurface(null) } }
}

/** Play / pause, a seek bar with a spoken "position of duration" state, and the elapsed / total text. */
@Composable
private fun VideoControls(
    playback: VideoPlayback,
    isPlaying: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    var positionMs by remember(playback) { mutableLongStateOf(playback.positionMs) }
    var durationMs by remember(playback) { mutableLongStateOf(playback.durationMs) }
    var dragging by remember(playback) { mutableStateOf(false) }
    LaunchedEffect(playback) {
        while (true) {
            if (!dragging) positionMs = playback.positionMs
            durationMs = playback.durationMs
            delay(POSITION_POLL_MS)
        }
    }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, bottom = 56.dp)
                .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = if (isPlaying) onPause else onResume) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription =
                    stringResource(if (isPlaying) R.string.cd_pause_video else R.string.cd_play_video),
                tint = Color.White,
            )
        }
        val seekDescription = stringResource(R.string.cd_video_seek)
        Slider(
            value = positionMs.coerceIn(0L, maxOf(durationMs, 0L)).toFloat(),
            onValueChange = {
                dragging = true
                positionMs = it.toLong()
            },
            onValueChangeFinished = {
                playback.seekTo(positionMs)
                dragging = false
            },
            valueRange = 0f..maxOf(durationMs, 1L).toFloat(),
            enabled = durationMs > 0L,
            modifier =
                Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = seekDescription
                        stateDescription = seekState(resources, positionMs, durationMs)
                    },
        )
        Text(
            text =
                stringResource(
                    R.string.video_elapsed_of_total,
                    formatDurationBadge(positionMs) ?: "0:00",
                    formatDurationBadge(durationMs) ?: "0:00",
                ),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** "30 seconds of 2 minutes"; the position part is "0 seconds" at the very start. */
internal fun seekState(
    resources: Resources,
    positionMs: Long,
    durationMs: Long,
): String =
    resources.getString(
        R.string.video_position_state,
        durationDescription(resources, positionMs) ?: resources.getQuantityString(R.plurals.duration_seconds, 0, 0),
        durationDescription(resources, durationMs) ?: resources.getQuantityString(R.plurals.duration_seconds, 0, 0),
    )
