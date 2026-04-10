package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun duplicateReviewScreen(
    viewModel: AlbumsViewModel,
    onNavigateBack: () -> Unit,
) {
    val duplicatesState by viewModel.duplicatesState.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Start analysis when the screen first appears
    LaunchedEffect(Unit) { viewModel.findDuplicates() }

    // toDelete: set of photo hrefs the user wants to remove
    var toDelete by rememberSaveable { mutableStateOf(emptySet<String>()) }

    // Pre-populate toDelete when results arrive (keep first photo in each group by default)
    LaunchedEffect(duplicatesState) {
        if (duplicatesState is DuplicatesState.Found) {
            val groups = (duplicatesState as DuplicatesState.Found).groups
            val defaultDelete =
                groups
                    .flatMap { group -> group.drop(1) } // skip the first (best candidate)
                    .map { it.href }
                    .toSet()
            toDelete = defaultDelete
        }
    }

    // URL of the photo currently being previewed via long-press (null = no preview)
    var previewUrl by remember { mutableStateOf<String?>(null) }

    previewUrl?.let { url ->
        PhotoPreviewDialog(url = url, onDismiss = { previewUrl = null })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Find duplicates") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.resetDuplicatesState()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = duplicatesState,
            contentKey = { it::class }, // only animate on state-type change, not on Loading progress ticks
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "duplicates-content",
        ) { state ->
            when (state) {
                is DuplicatesState.Loading -> {
                    LoadingContent(state)
                }

                is DuplicatesState.NoneFound -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No duplicates found in this album.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }

                is DuplicatesState.Found -> {
                    GroupsContent(
                        groups = state.groups,
                        thumbnailUrls = detailState.thumbnailUrls,
                        toDelete = toDelete,
                        onToggle = { href ->
                            toDelete =
                                if (href in toDelete) toDelete - href else toDelete + href
                        },
                        onLongPress = { url -> previewUrl = url },
                        onConfirm = {
                            val photosToDelete =
                                state.groups
                                    .flatten()
                                    .filter { it.href in toDelete }
                            viewModel.deletePhotos(photosToDelete) { failures ->
                                if (failures > 0) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "$failures photo(s) could not be deleted.",
                                        )
                                    }
                                }
                                onNavigateBack()
                            }
                        },
                        deleteCount = toDelete.size,
                    )
                }

                is DuplicatesState.Error -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.findDuplicates() }) { Text("Retry") }
                    }
                }

                DuplicatesState.Deleting -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Deleting photos…")
                        }
                    }
                }

                DuplicatesState.Idle -> {
                    // transient — findDuplicates() is called immediately in LaunchedEffect
                }
            }
        }
    }
}

@Composable
internal fun PhotoPreviewDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .onSizeChanged { size = it }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ).pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val oldScale = scale
                                val newScale = (oldScale * zoom).coerceIn(1f, 8f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val focalOffset = (centroid - center) * (1f - zoom) + offset * zoom
                                val newOffset = focalOffset + pan * oldScale
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
                            }
                        },
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                },
            )
            // Close button top-right
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close preview",
                    tint = Color.White,
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(4.dp),
                )
            }
            // Hint label at the bottom
            val hint = if (scale > 1f) "Pinch to zoom · Drag to pan" else "Pinch to zoom · Tap outside to close"
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun LoadingContent(state: DuplicatesState.Loading) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Analysing photos… ${state.processed} / ${state.total}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = {
                if (state.total > 0) state.processed.toFloat() / state.total else 0f
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupsContent(
    groups: List<List<RemotePhoto>>,
    thumbnailUrls: Map<String, String>,
    toDelete: Set<String>,
    onToggle: (href: String) -> Unit,
    onLongPress: (thumbUrl: String) -> Unit,
    onConfirm: () -> Unit,
    deleteCount: Int,
) {
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text =
                    "${groups.size} duplicate group${if (groups.size != 1) "s" else ""} found. " +
                        "Tap to toggle · Long-press to preview.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        itemsIndexed(groups) { index, group ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Group ${index + 1} — ${group.size} similar photos",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(group) { _, photo ->
                            val thumbUrl = thumbnailUrls[photo.href]
                            val markedForDelete = photo.href in toDelete
                            Box(
                                modifier =
                                    Modifier
                                        .size(100.dp)
                                        .aspectRatio(1f)
                                        .clip(MaterialTheme.shapes.small)
                                        .border(
                                            width = if (markedForDelete) 3.dp else 2.dp,
                                            color =
                                                if (markedForDelete) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                            shape = MaterialTheme.shapes.small,
                                        ).combinedClickable(
                                            onClick = { onToggle(photo.href) },
                                            onLongClick = {
                                                thumbUrl?.let { onLongPress(it) }
                                            },
                                        ),
                            ) {
                                if (thumbUrl != null) {
                                    AsyncImage(
                                        model =
                                            ImageRequest
                                                .Builder(context)
                                                .data(thumbUrl)
                                                .crossfade(true)
                                                .build(),
                                        contentDescription = photo.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Box(
                                        Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator(Modifier.size(32.dp)) }
                                }

                                if (markedForDelete) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Marked for deletion",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier =
                                                Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.75f))
                                                    .padding(4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onConfirm,
                enabled = deleteCount > 0,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                val label =
                    if (deleteCount > 0) {
                        "Delete $deleteCount photo${if (deleteCount != 1) "s" else ""}"
                    } else {
                        "Nothing selected"
                    }
                Text(label)
            }
        }
    }
}
