package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun AlbumDetailScreen(
    viewModel: AlbumsViewModel,
    albumHref: String,
    onOpenPhoto: (index: Int) -> Unit,
    onFindDuplicates: () -> Unit,
    onFindBlurry: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.detailState.collectAsStateWithLifecycle()
    val isDeletingPhotos by viewModel.isDeletingPhotos.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selectedHrefs by remember { mutableStateOf(emptySet<String>()) }
    val selectionMode = selectedHrefs.isNotEmpty()
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(albumHref) { viewModel.loadAlbum(albumHref) }

    if (showDeleteConfirmation) {
        DeletePhotosDialog(
            count = selectedHrefs.size,
            onDismiss = { showDeleteConfirmation = false },
            onConfirm = {
                showDeleteConfirmation = false
                val toDelete = state.photos.filter { it.href in selectedHrefs }
                viewModel.deleteSelectedPhotos(toDelete) { failures ->
                    selectedHrefs = emptySet()
                    if (failures > 0) {
                        scope.launch {
                            snackbarHostState.showSnackbar("$failures photo(s) could not be deleted.")
                        }
                    }
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.photos.isNotEmpty() && !selectionMode && !isDeletingPhotos) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExtendedFloatingActionButton(
                        onClick = onFindBlurry,
                        icon = { Icon(Icons.Default.BlurOn, contentDescription = null) },
                        text = { Text("Find blurry") },
                    )
                    ExtendedFloatingActionButton(
                        onClick = onFindDuplicates,
                        icon = { Icon(Icons.Default.FindReplace, contentDescription = null) },
                        text = { Text("Find duplicates") },
                    )
                }
            }
        },
        topBar = {
            if (selectionMode) {
                SelectionTopBar(
                    selectedCount = selectedHrefs.size,
                    onClearSelection = { selectedHrefs = emptySet() },
                    onDeleteClick = { showDeleteConfirmation = true },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = state.albumName.ifBlank { "Album" },
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
            }
        },
    ) { padding ->
        AlbumDetailContent(
            state = state,
            isDeletingPhotos = isDeletingPhotos,
            selectedHrefs = selectedHrefs,
            modifier = Modifier.fillMaxSize().padding(padding),
            onPhotoClick = { index ->
                if (selectionMode) {
                    val href = state.photos[index].href
                    selectedHrefs = if (href in selectedHrefs) selectedHrefs - href else selectedHrefs + href
                } else {
                    onOpenPhoto(index)
                }
            },
            onPhotoLongClick = { href -> selectedHrefs = selectedHrefs + href },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    )
}

@Composable
private fun AlbumDetailContent(
    state: AlbumDetailState,
    isDeletingPhotos: Boolean,
    selectedHrefs: Set<String>,
    modifier: Modifier = Modifier,
    onPhotoClick: (index: Int) -> Unit,
    onPhotoLongClick: (href: String) -> Unit,
) {
    when {
        isDeletingPhotos -> DeletingContent(modifier = modifier)
        state.isLoading -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        state.error != null -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        state.photos.isEmpty() -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text("No photos in this album.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(state.photos, key = { _, p -> p.href }) { index, photo ->
                    PhotoGridItem(
                        photo = photo,
                        thumbUrl = state.thumbnailUrls[photo.href],
                        isSelected = photo.href in selectedHrefs,
                        onClick = { onPhotoClick(index) },
                        onLongClick = { onPhotoLongClick(photo.href) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeletingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Deleting photos…")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGridItem(
    photo: RemotePhoto,
    thumbUrl: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
        }

        if (isSelected) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun DeletePhotosDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete photos?") },
        text = {
            Text(
                "Permanently delete $count photo${if (count != 1) "s" else ""} from the server?" +
                    " This cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
