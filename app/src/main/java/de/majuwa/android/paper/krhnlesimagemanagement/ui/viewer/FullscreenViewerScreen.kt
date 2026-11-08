package de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer

import android.app.Activity
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.components.mediaPositionDescription
import kotlinx.coroutines.delay

private const val CONTROLS_HIDE_DELAY_MS = 3_000L
private const val MAX_ZOOM = 8f

@Composable
fun FullscreenViewerScreen(
    viewModel: AlbumDetailViewModel,
    initialIndex: Int,
    onNavigateBack: () -> Unit,
    playerViewModel: VideoPlayerViewModel = viewModel(),
) {
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val photos = detailState.photos
    val playbackState by playerViewModel.state.collectAsStateWithLifecycle()
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    // Hide system bars for true immersive fullscreen; restore when leaving.
    DisposableEffect(Unit) {
        val activity = context as? Activity ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    var controlsVisible by remember { mutableStateOf(true) }

    // Photos already saved from this viewer: their Save button is disabled (no accidental double tap).
    var savedHrefs by rememberSaveable(stateSaver = savedHrefsSaver) { mutableStateOf(emptySet<String>()) }

    // Track whether the currently visible page is zoomed in (disables pager swipe).
    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    // Reset zoom flag whenever the user swipes to a different page.
    LaunchedEffect(pagerState.currentPage) {
        isCurrentPageZoomed = false
    }

    VideoPlaybackLifecycle(
        pagerState = pagerState,
        detailState = detailState,
        playbackState = playbackState,
        playerViewModel = playerViewModel,
    )

    // Auto-hide controls after a delay whenever they become visible. On a video page they hide only
    // while the video plays, so a paused or failed video keeps its controls; photos always hide.
    val currentIsVideo = photos.getOrNull(pagerState.currentPage)?.isVideo == true
    val videoPlaying = (playbackState as? VideoPlaybackState.Playing)?.isPlaying == true
    val hideTimeout =
        LocalAccessibilityManager.current
            ?.calculateRecommendedTimeoutMillis(
                CONTROLS_HIDE_DELAY_MS,
                containsIcons = true,
                containsText = true,
                containsControls = true,
            ) ?: CONTROLS_HIDE_DELAY_MS
    // A TalkBack user needs the controls to stay: they are never hidden while touch exploration is on.
    val touchExploration = rememberTouchExplorationEnabled()
    val mayAutoHide = !touchExploration && (!currentIsVideo || videoPlaying)
    LaunchedEffect(controlsVisible, mayAutoHide) {
        if (controlsVisible && mayAutoHide) {
            delay(hideTimeout)
            controlsVisible = false
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { controlsVisible = !controlsVisible },
    ) {
        if (photos.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isCurrentPageZoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val photo = photos[page]
                val description = mediaPositionDescription(photo, page + 1, photos.size)
                if (photo.isVideo) {
                    val pageState = playbackState.forVideo(photo.href)
                    VideoPage(
                        description = description,
                        posterUrl = viewModel.fullImageUrl(photo),
                        state = pageState,
                        playback =
                            playback.takeIf {
                                pageState is VideoPageState.Playing ||
                                    pageState is VideoPageState.Preparing
                            },
                        controlsVisible = controlsVisible,
                        onPlay = { playerViewModel.play(photo, viewModel.repositories) },
                        onPause = playerViewModel::pause,
                        onResume = playerViewModel::resume,
                        onRetry = playerViewModel::retry,
                    )
                } else {
                    ZoomablePage(
                        url = viewModel.fullImageUrl(photo).orEmpty(),
                        contentDescription = description,
                        onZoomedChange = { zoomed ->
                            if (page == pagerState.currentPage) isCurrentPageZoomed = zoomed
                        },
                    )
                }
            }

            // Overlay controls — tap anywhere to toggle
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .systemBarsPadding(),
                ) {
                    // Back button top-left
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = Color.White,
                        )
                    }

                    // Save to device top-right: photos only, a video page has no button
                    photos.getOrNull(pagerState.currentPage)?.takeUnless { it.isVideo }?.let { current ->
                        val alreadySaved = current.href in savedHrefs
                        IconButton(
                            enabled = !alreadySaved,
                            onClick = {
                                viewModel.saveToDevice(listOf(current))
                                savedHrefs = savedHrefs + current.href
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(R.string.cd_save_to_device),
                                tint = Color.White.copy(alpha = if (alreadySaved) SAVED_ICON_ALPHA else 1f),
                            )
                        }
                    }

                    // Page counter bottom-centre
                    if (photos.size > 1) {
                        Text(
                            text =
                                stringResource(
                                    R.string.viewer_page_counter,
                                    pagerState.currentPage + 1,
                                    photos.size,
                                ),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.4f),
                                        shape = MaterialTheme.shapes.small,
                                    ).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val SAVED_ICON_ALPHA = 0.4f

/** Saves the set of saved hrefs across rotation and process death as a list. */
private val savedHrefsSaver =
    listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** The playback state of the video [href] as a page state; every other video page is idle. */
private fun VideoPlaybackState.forVideo(href: String): VideoPageState =
    when {
        this is VideoPlaybackState.Preparing && this.href == href -> VideoPageState.Preparing
        this is VideoPlaybackState.Playing && this.href == href -> VideoPageState.Playing(isPlaying)
        this is VideoPlaybackState.Failed && this.href == href -> VideoPageState.Failed(error)
        else -> VideoPageState.Idle
    }

/** Whether a touch exploration service (TalkBack) is on; follows changes while composed. */
@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var enabled by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        enabled = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * Keeps playback tied to what the user sees: it stops when the page changes, when the album is gone
 * (logout, server change) or the video left the album, and pauses when the viewer goes to the
 * background — except for the Activity recreation of a rotation, which keeps playing.
 */
@Composable
private fun VideoPlaybackLifecycle(
    pagerState: PagerState,
    detailState: AlbumDetailState,
    playbackState: VideoPlaybackState,
    playerViewModel: VideoPlayerViewModel,
) {
    val activity = LocalActivity.current
    val playingHref = playbackState.href
    val photos = detailState.photos

    LaunchedEffect(pagerState.currentPage, playingHref, photos) {
        val pageHref = photos.getOrNull(pagerState.currentPage)?.href
        if (playingHref != null && pageHref != null && pageHref != playingHref) playerViewModel.stop()
    }

    LaunchedEffect(detailState.error, detailState.isLoaded, photos, playingHref) {
        val albumGone =
            detailState.error == AlbumsError.NotConfigured || detailState.error == AlbumsError.ConfigurationChanged
        val videoGone = detailState.isLoaded && photos.none { it.href == playingHref }
        if (playingHref != null && (albumGone || videoGone)) playerViewModel.stop()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) playerViewModel.pause()
    }
}

private val VideoPlaybackState.href: String?
    get() =
        when (this) {
            VideoPlaybackState.Idle -> null
            is VideoPlaybackState.Preparing -> href
            is VideoPlaybackState.Playing -> href
            is VideoPlaybackState.Failed -> href
        }

@Composable
private fun ZoomablePage(
    url: String,
    contentDescription: String,
    onZoomedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    SubcomposeAsyncImage(
        model =
            ImageRequest
                .Builder(context)
                .data(url)
                .crossfade(true)
                .build(),
        // The description lives on the page itself, not on the image painter: SubcomposeAsyncImage
        // only applies `contentDescription` to the painter content, so the `loading` slot below
        // (and any later state) would otherwise leave the page without a TalkBack label.
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxSize()
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Image
                }.onSizeChanged { size = it }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ).pointerInput(Unit) {
                    // Custom gesture handler: only consume events for pinch-zoom or when
                    // already zoomed in. Single-finger swipes at scale==1 pass through to
                    // the HorizontalPager so page-swiping continues to work.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val isMultiTouch = event.changes.size >= 2
                            if (isMultiTouch || scale > 1f) {
                                // Only update state on actual pointer movement, not on
                                // pure pointer-up events (where positionChanged() is false).
                                // This matches detectTransformGestures behaviour and prevents
                                // spurious resets on the final up event.
                                if (event.changes.any { it.positionChanged() }) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    val oldScale = scale
                                    val newScale = (oldScale * zoomChange).coerceIn(1f, MAX_ZOOM)
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val focalOffset =
                                        (centroid - center) * (1f - zoomChange) + offset * zoomChange
                                    val newOffset = focalOffset + panChange * oldScale
                                    scale = newScale
                                    offset =
                                        if (newScale > 1f) {
                                            val maxX = size.width * (newScale - 1) / 2f
                                            val maxY = size.height * (newScale - 1) / 2f
                                            Offset(
                                                newOffset.x.coerceIn(-maxX, maxX),
                                                newOffset.y.coerceIn(-maxY, maxY),
                                            )
                                        } else {
                                            Offset.Zero
                                        }
                                    onZoomedChange(newScale > 1f)
                                }
                                // Only consume events where the pointer actually moved.
                                // Pointer-up events (positionChanged() == false) are left
                                // unconsumed so the HorizontalPager can properly close out
                                // the gesture-tracking state it opened on the initial down
                                // event, preventing the blank-screen artefact after zoom.
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        loading = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Color.White) }
        },
    )
}
