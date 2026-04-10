package de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer

import android.app.Activity
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import kotlinx.coroutines.delay

private const val CONTROLS_HIDE_DELAY_MS = 3_000L
private const val MAX_ZOOM = 8f

@Composable
fun fullscreenViewerScreen(
    viewModel: AlbumsViewModel,
    initialIndex: Int,
    onNavigateBack: () -> Unit,
) {
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val photos = detailState.photos
    val context = LocalContext.current
    val view = LocalView.current

    // Hide system bars for true immersive fullscreen; restore when leaving.
    DisposableEffect(Unit) {
        val window = (context as Activity).window
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

    // Track whether the currently visible page is zoomed in (disables pager swipe).
    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    // Reset zoom flag whenever the user swipes to a different page.
    LaunchedEffect(pagerState.currentPage) {
        isCurrentPageZoomed = false
    }

    // Auto-hide controls after delay whenever they become visible.
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(CONTROLS_HIDE_DELAY_MS)
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
                val url = viewModel.fullImageUrl(photo)
                ZoomablePage(
                    url = url,
                    contentDescription = photo.displayName,
                    onZoomedChange = { zoomed ->
                        if (page == pagerState.currentPage) isCurrentPageZoomed = zoomed
                    },
                )
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
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }

                    // Page counter bottom-centre
                    if (photos.size > 1) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${photos.size}",
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
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
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
                                event.changes.forEach { it.consume() }
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
