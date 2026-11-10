package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.model.AlbumSort
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.util.exceedsAlbumNameLimit
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName
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
    // Declared outside the state `when`: the spinner replaces its branches on every reload.
    var query by rememberSaveable { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val sort by viewModel.albumSort.collectAsStateWithLifecycle()
    val sorted by viewModel.sortedAlbums.collectAsStateWithLifecycle()
    // The ViewModel sorts off the main thread; until it caught up with a new list the spinner stays.
    val sortPending = sorted.source !== state.albums
    val shown = remember(sorted, query) { filterAlbums(sorted.entries, query).map { it.album } }

    LaunchedEffect(Unit) { viewModel.loadAlbums() }

    val renameOutcome by viewModel.renameOutcome.collectAsStateWithLifecycle()
    LaunchedEffect(renameOutcome) {
        val outcome = renameOutcome ?: return@LaunchedEffect
        if (outcome is RenameOutcome.Failure) {
            snackbarHostState.showSnackbar(albumsErrorMessage(resources, outcome.error))
        }
        // Only after the snackbar was shown: a cancelled effect (rotation) keeps the outcome.
        viewModel.consumeRenameOutcome()
    }

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
            currentNames = album.currentNames(),
            initialName = album.displayName,
            onDismiss = { albumToRename = null },
            onConfirm = { newName ->
                albumToRename = null
                viewModel.renameAlbum(album, newName)
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_albums)) },
                actions = {
                    AlbumSortMenu(
                        sort = sort,
                        onSelect = { selected ->
                            viewModel.setAlbumSort(selected)
                            gridState.requestScrollToItem(0)
                        },
                    )
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
            state.isBusy() || sortPending -> {
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
                        text = state.error?.let { albumsErrorMessage(resources, it) }.orEmpty(),
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
                AlbumListContent(
                    shown = shown,
                    query = query,
                    gridState = gridState,
                    menuAlbumHref = albumMenu?.href,
                    onQueryChange = { newQuery ->
                        val capped = newQuery.capQuery()
                        if (capped != query) {
                            query = capped
                            gridState.requestScrollToItem(0)
                        }
                    },
                    onOpenAlbum = onOpenAlbum,
                    onLongClick = { albumMenu = it },
                    onDismissMenu = { albumMenu = null },
                    onRename = {
                        albumMenu = null
                        albumToRename = it
                    },
                    onDelete = {
                        albumMenu = null
                        albumToDelete = it
                    },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
}

private fun AlbumsState.isBusy() = isLoading || isDeletingAlbum || isRenamingAlbum

@Composable
private fun AlbumListContent(
    shown: List<RemoteAlbum>,
    query: String,
    gridState: LazyGridState,
    menuAlbumHref: String?,
    onQueryChange: (String) -> Unit,
    onOpenAlbum: (albumHref: String) -> Unit,
    onLongClick: (RemoteAlbum) -> Unit,
    onDismissMenu: () -> Unit,
    onRename: (RemoteAlbum) -> Unit,
    onDelete: (RemoteAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        AlbumSearchField(query = query, onQueryChange = onQueryChange)
        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.albums_no_match, query.trim()),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.href }) { album ->
                    AlbumCard(
                        album = album,
                        onClick = { onOpenAlbum(album.href) },
                        isMenuExpanded = menuAlbumHref == album.href,
                        onLongClick = { onLongClick(album) },
                        onDismissMenu = onDismissMenu,
                        onRenameClick = { onRename(album) },
                        onDeleteClick = { onDelete(album) },
                    )
                }
            }
        }
    }
}

/** Longest search query kept (bounds per-keystroke work and the saved state). */
private const val MAX_QUERY_LENGTH = 255

/** At most [MAX_QUERY_LENGTH] chars, never ending in the middle of a surrogate pair. */
private fun String.capQuery(): String {
    if (length <= MAX_QUERY_LENGTH) return this
    val end = if (this[MAX_QUERY_LENGTH - 1].isHighSurrogate()) MAX_QUERY_LENGTH - 1 else MAX_QUERY_LENGTH
    return substring(0, end)
}

@Composable
private fun AlbumSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.album_search_label)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_album_search))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag(ALBUM_SEARCH_TAG),
    )
}

internal const val ALBUM_SEARCH_TAG = "album_search"

@Composable
private fun AlbumSortMenu(
    sort: AlbumSort,
    onSelect: (AlbumSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = stringResource(sort.labelRes())
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { stateDescription = current },
        ) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.cd_sort_albums))
        }
        SortDropdown(expanded, sort, onDismiss = { expanded = false }, onSelect = onSelect)
    }
}

@Composable
private fun SortDropdown(
    expanded: Boolean,
    sort: AlbumSort,
    onDismiss: () -> Unit,
    onSelect: (AlbumSort) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.selectableGroup()) {
            AlbumSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    leadingIcon = {
                        if (option == sort) Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = {
                        onDismiss()
                        if (option != sort) onSelect(option)
                    },
                    modifier =
                        Modifier.semantics {
                            role = Role.RadioButton
                            selected = option == sort
                        },
                )
            }
        }
    }
}

private fun AlbumSort.labelRes(): Int =
    when (this) {
        AlbumSort.NAME_ASC -> R.string.album_sort_name_asc
        AlbumSort.NAME_DESC -> R.string.album_sort_name_desc
        AlbumSort.MODIFIED_NEWEST -> R.string.album_sort_modified_newest
        AlbumSort.MODIFIED_OLDEST -> R.string.album_sort_modified_oldest
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
    currentNames: Set<String>,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var albumName by remember(initialName) { mutableStateOf(initialName) }
    var errorMessageResId by remember(initialName) { mutableStateOf<Int?>(null) }

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
                            trimmedName in currentNames -> R.string.error_album_name_unchanged
                            !isValidOccasionName(trimmedName) -> R.string.error_album_name_invalid
                            exceedsAlbumNameLimit(trimmedName) -> R.string.error_album_name_too_long
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

/** The display name and the decoded last href segment (the display name may be percent-encoded). */
private fun RemoteAlbum.currentNames(): Set<String> =
    setOf(displayName, Uri.decode(href.trimEnd('/').substringAfterLast('/')))
