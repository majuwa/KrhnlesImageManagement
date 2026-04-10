package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun blurReviewScreen(
    viewModel: AlbumsViewModel,
    onNavigateBack: () -> Unit,
) {
    val blurState by viewModel.blurState.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.findBlurryPhotos() }

    var toDelete by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var previewUrl by remember { mutableStateOf<String?>(null) }

    // Pre-select all blurry photos when results arrive
    LaunchedEffect(blurState) {
        if (blurState is BlurState.Found) {
            toDelete = (blurState as BlurState.Found).blurryPhotos.map { it.href }.toSet()
        }
    }

    previewUrl?.let { url ->
        photoPreviewDialog(url = url, onDismiss = { previewUrl = null })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Blurry photos") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.resetBlurState()
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
            targetState = blurState,
            contentKey = { it::class },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "blur-content",
        ) { state ->
            when (state) {
                is BlurState.Scanning -> {
                    blurScanningContent(state)
                }

                is BlurState.NoneFound -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No blurry photos detected in this album.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }

                is BlurState.Found -> {
                    BlurResultsContent(
                        photos = state.blurryPhotos,
                        scores = state.scores,
                        thumbnailUrls = detailState.thumbnailUrls,
                        toDelete = toDelete,
                        onToggle = { href ->
                            toDelete = if (href in toDelete) toDelete - href else toDelete + href
                        },
                        onLongPress = { url -> previewUrl = url },
                        onConfirm = {
                            val photosToDelete =
                                state.blurryPhotos.filter { it.href in toDelete }
                            viewModel.deleteBlurryPhotos(photosToDelete) { failures ->
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

                is BlurState.Error -> {
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
                        Button(onClick = { viewModel.findBlurryPhotos() }) { Text("Retry") }
                    }
                }

                BlurState.Deleting -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Deleting photos…")
                        }
                    }
                }

                BlurState.Idle -> {
                    // transient — findBlurryPhotos() is called immediately in LaunchedEffect
                }
            }
        }
    }
}

@Composable
private fun blurScanningContent(state: BlurState.Scanning) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Scanning for blurry photos… ${state.processed} / ${state.total}",
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
private fun BlurResultsContent(
    photos: List<RemotePhoto>,
    scores: Map<String, Double>,
    thumbnailUrls: Map<String, String>,
    toDelete: Set<String>,
    onToggle: (href: String) -> Unit,
    onLongPress: (thumbUrl: String) -> Unit,
    onConfirm: () -> Unit,
    deleteCount: Int,
) {
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Text(
            text =
                "${photos.size} blurry photo${if (photos.size != 1) "s" else ""} found. " +
                    "Tap to toggle · Long-press to preview.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(photos, key = { it.href }) { photo ->
                val thumbUrl = thumbnailUrls[photo.href]
                val selected = photo.href in toDelete
                val score = scores[photo.href]

                Box(
                    modifier =
                        Modifier
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.surface
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

                    if (selected) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Selected for deletion",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(4.dp).size(24.dp),
                            )
                        }
                    }

                    // Blur score label
                    if (score != null) {
                        Text(
                            text = "%.0f".format(score),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        MaterialTheme.shapes.extraSmall,
                                    ).padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

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
