package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    viewModel: AlbumsViewModel,
    onOpenAlbum: (albumHref: String) -> Unit,
) {
    val state by viewModel.albumsState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var albumMenu by remember { mutableStateOf<RemoteAlbum?>(null) }
    var albumToDelete by remember { mutableStateOf<RemoteAlbum?>(null) }
    var albumToRename by remember { mutableStateOf<RemoteAlbum?>(null) }

    LaunchedEffect(Unit) { viewModel.loadAlbums() }

    albumToDelete?.let { album ->
        DeleteAlbumDialog(
            albumName = album.displayName,
            onDismiss = { albumToDelete = null },
            onConfirm = {
                albumToDelete = null
                viewModel.deleteAlbum(album) { success ->
                    if (!success) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                resources.getString(
                                    R.string.snackbar_delete_album_failed,
                                    album.displayName,
                                ),
                            )
                        }
                    }
                }
            },
        )
    }

    albumToRename?.let { album ->
        RenameAlbumDialog(
            initialName = album.displayName,
            onDismiss = { albumToRename = null },
            onConfirm = { newName ->
                albumToRename = null
                viewModel.renameAlbum(album, newName) { success ->
                    if (!success) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                resources.getString(
                                    R.string.snackbar_rename_album_failed,
                                    album.displayName,
                                ),
                            )
                        }
                    }
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_albums)) },
                actions = {
                    IconButton(onClick = { viewModel.loadAlbums() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_reload))
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
        when {
            state.isLoading || state.isDeletingAlbum || state.isRenamingAlbum -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            state.albums.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.empty_albums), style = MaterialTheme.typography.bodyLarge)
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.albums, key = { it.href }) { album ->
                        AlbumCard(
                            album = album,
                            onClick = { onOpenAlbum(album.href) },
                            isMenuExpanded = albumMenu?.href == album.href,
                            onLongClick = { albumMenu = album },
                            onDismissMenu = { albumMenu = null },
                            onRenameClick = {
                                albumMenu = null
                                albumToRename = album
                            },
                            onDeleteClick = {
                                albumMenu = null
                                albumToDelete = album
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(
    album: RemoteAlbum,
    onClick: () -> Unit,
    isMenuExpanded: Boolean,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Box {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    text = album.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                onClick = onRenameClick,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = onDeleteClick,
            )
        }
    }
}

@Composable
private fun DeleteAlbumDialog(
    albumName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_album_title)) },
        text = {
            Text(stringResource(R.string.dialog_delete_album_message, albumName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RenameAlbumDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var albumName by remember(initialName) { mutableStateOf(initialName) }
    var errorMessageResId by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_album_title)) },
        text = {
            OutlinedTextField(
                value = albumName,
                onValueChange = {
                    albumName = it
                    errorMessageResId = null
                },
                label = { Text(stringResource(R.string.label_album_name)) },
                singleLine = true,
                isError = errorMessageResId != null,
                supportingText = {
                    errorMessageResId?.let { Text(stringResource(it)) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedName = albumName.trim()
                    errorMessageResId =
                        when {
                            trimmedName.isBlank() -> R.string.error_album_name_blank
                            trimmedName == initialName -> R.string.error_album_name_unchanged
                            else -> null
                        }
                    if (errorMessageResId == null) {
                        onConfirm(trimmedName)
                    }
                },
            ) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
