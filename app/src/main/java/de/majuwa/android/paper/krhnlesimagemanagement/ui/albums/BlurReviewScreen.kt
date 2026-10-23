package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.ui.components.photoPositionDescription

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlurReviewScreen(
    viewModel: BlurViewModel,
    detailViewModel: AlbumDetailViewModel,
    albumHref: String,
    onNavigateBack: () -> Unit,
) {
    val blurState by viewModel.blurState.collectAsStateWithLifecycle()
    val detailState by detailViewModel.detailState.collectAsStateWithLifecycle()

    // Set when this screen started a deletion; navigate back once it has finished. The outcome
    // (failure snackbar) is shown by the album detail screen, which is visible after navigating back.
    var awaitingDeletion by rememberSaveable { mutableStateOf(false) }
    val isDeleting = blurState is BlurState.Deleting
    LaunchedEffect(awaitingDeletion, isDeleting) {
        if (awaitingDeletion && !isDeleting) {
            awaitingDeletion = false
            onNavigateBack()
        }
    }
    // Leaving is blocked while deleting, so the deletion always finishes on this screen.
    BackHandler(enabled = isDeleting) { /* consume Back until the deletion has finished */ }

    // Loads the album, stops the scan when the album becomes unavailable (logout / server change)
    // and starts it once the album is loaded (ignored by the ViewModel unless it is Idle, so a
    // recreated screen keeps a running scan, its results or a running deletion).
    val albumReady =
        rememberReviewAlbumReady(
            detailViewModel = detailViewModel,
            albumHref = albumHref,
            isDeleting = isDeleting,
            onAlbumUnavailable = viewModel::cancelScan,
            onAlbumReady = { album ->
                viewModel.findBlurryPhotos(album.photos, album.thumbnailUrls, detailViewModel.repositories)
            },
        )

    // Pre-select all blurry photos once per result; kept across recreation.
    val defaultSelection =
        remember(blurState) { (blurState as? BlurState.Found)?.blurryPhotos?.map { it.href }?.toSet() }
    var toDelete by rememberReviewSelection(
        isScanning = blurState is BlurState.Scanning,
        defaultSelection = defaultSelection,
    )
    var previewUrl by remember { mutableStateOf<String?>(null) }

    previewUrl?.let { url ->
        PhotoPreviewDialog(url = url, onDismiss = { previewUrl = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_blurry_photos)) },
                navigationIcon = {
                    IconButton(enabled = !isDeleting, onClick = {
                        viewModel.resetBlurState()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
        ReviewAlbumGate(
            albumError = detailState.error,
            isDeleting = isDeleting,
            showLoading = !albumReady && blurState == BlurState.Idle,
            modifier = Modifier.fillMaxSize().padding(padding),
            onRetryAlbum = detailViewModel::reload,
        ) {
            AnimatedContent(
                targetState = blurState,
                contentKey = { it::class },
                modifier = Modifier.fillMaxSize().padding(padding),
                label = "blur-content",
            ) { state ->
                when (state) {
                    is BlurState.Scanning -> {
                        BlurScanningContent(state)
                    }

                    is BlurState.NoneFound -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.empty_blurry),
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
                                if (viewModel.deleteBlurryPhotos(photosToDelete, detailViewModel)) {
                                    awaitingDeletion =
                                        true
                                }
                            },
                            deleteCount = toDelete.size,
                        )
                    }

                    is BlurState.Error -> {
                        ScanErrorContent(error = state.error) {
                            if (albumReady) {
                                viewModel.retryFindBlurryPhotos(
                                    detailState.photos,
                                    detailState.thumbnailUrls,
                                    detailViewModel.repositories,
                                )
                            } else {
                                detailViewModel.reload()
                            }
                        }
                    }

                    BlurState.Deleting -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(stringResource(R.string.status_deleting_photos))
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
}

@Composable
private fun BlurScanningContent(state: BlurState.Scanning) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.status_scanning_blur, state.processed, state.total),
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
            text = pluralStringResource(R.plurals.blur_results_summary, photos.size, photos.size),
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
            itemsIndexed(photos, key = { _, photo -> photo.href }) { index, photo ->
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
                            contentDescription = photoPositionDescription(index + 1, photos.size),
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
                                contentDescription = stringResource(R.string.cd_selected_for_deletion),
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
                    pluralStringResource(R.plurals.delete_n_photos, deleteCount, deleteCount)
                } else {
                    stringResource(R.string.nothing_selected)
                }
            Text(label)
        }
    }
}
