package de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.isKnownDate
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.OccasionDialog
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import de.majuwa.android.paper.krhnlesimagemanagement.upload.previewUploadPath
import de.majuwa.android.paper.krhnlesimagemanagement.upload.resolveAutoDateUploadBatch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val PREVIEW_PATH_LIST_MAX_HEIGHT = 240.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGridScreen(
    viewModel: PhotoGridViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToUploadHistory: () -> Unit,
    onStartUpload: (batches: List<UploadBatch>) -> Unit,
    removalViewModel: UploadedRemovalViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val autoDateFoldersEnabled by viewModel.autoDateFoldersEnabled.collectAsStateWithLifecycle()
    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()
    val uploadBaseFolder by viewModel.uploadBaseFolder.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val removalState by removalViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val media = rememberMediaPermissions(viewModel)
    var hasNotificationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasNotificationPermission = granted
        }

    LaunchedEffect(hasNotificationPermission) {
        if (!hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Show not-configured dialog on first launch if no server is set up
    LaunchedEffect(isConfigured) {
        if (!isConfigured) {
            viewModel.dismissAutoDateFolderPreviewDialog()
            viewModel.dismissOccasionDialog()
        }
    }

    LaunchedEffect(autoDateFoldersEnabled) {
        if (!autoDateFoldersEnabled) {
            viewModel.dismissAutoDateFolderPreviewDialog()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.selectedPhotoIds.isNotEmpty()) {
                        Text(
                            pluralStringResource(
                                R.plurals.selected_count,
                                uiState.selectedPhotoIds.size,
                                uiState.selectedPhotoIds.size,
                            ),
                        )
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    PhotoGridTopBarActions(
                        uiState = uiState,
                        onClearSelection = { viewModel.clearSelection() },
                        onNavigateToUploadHistory = onNavigateToUploadHistory,
                        onNavigateToSettings = onNavigateToSettings,
                        onToggleFilter = { viewModel.toggleShowOnlyNewPhotos() },
                        removalEnabled = removalState == RemovalState.Idle,
                        onRemoveUploaded = removalViewModel::start,
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = uiState.selectedPhotoIds.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                FloatingActionButton(
                    onClick = { viewModel.onUploadRequested() },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.cd_upload))
                }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            AnimatedVisibility(visible = uploadProgress != null) {
                uploadProgress?.let { progress ->
                    UploadProgressBanner(progress.current, progress.total)
                }
            }
            media.banner?.let { banner ->
                MediaAccessBannerRow(banner, media.bannerOpensSettings, media.onBannerAction)
            }
            AnimatedVisibility(visible = uiState.showOnlyNewPhotos) {
                NewOnlyFilterBanner(
                    onClear = { viewModel.toggleShowOnlyNewPhotos() },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                InnerContent(
                    hasPermission = media.access.hasAccess,
                    uiState = uiState,
                    onRequestPermission = media.request,
                    onPhotoClick = { viewModel.togglePhotoSelection(it) },
                    onDateHeaderClick = { viewModel.toggleDateSelection(it) },
                    onRefresh = { viewModel.refreshPhotos() },
                )
            }
        }
    }

    UploadedRemovalHost(
        viewModel = removalViewModel,
        snackbarHostState = snackbarHostState,
        onNavigateToSettings = onNavigateToSettings,
        onTrashed = { viewModel.refreshPhotos() },
    )

    if (uiState.showOccasionDialog) {
        val selectedPhotos = viewModel.getSelectedPhotos()
        OccasionDialog(
            message = stringResource(R.string.dialog_upload_message),
            onDismiss = { viewModel.dismissOccasionDialog() },
            onConfirm = { occasionName ->
                viewModel.dismissOccasionDialog()
                onStartUpload(listOf(UploadBatch.Occasion(occasionName, selectedPhotos)))
                viewModel.clearSelection()
            },
        )
    }

    if (uiState.showAutoDateFolderPreviewDialog) {
        val uploadBatch =
            remember(uiState.selectedPhotoIds, uiState.photosByDate) {
                resolveAutoDateUploadBatch(viewModel.getSelectedPhotos())
            }
        AutoDateFolderPreviewDialog(
            previewPaths = uploadBatch.folders.distinct().map { previewUploadPath(uploadBaseFolder, it) },
            onDismiss = { viewModel.dismissAutoDateFolderPreviewDialog() },
            onConfirm = {
                viewModel.dismissAutoDateFolderPreviewDialog()
                onStartUpload(listOf(uploadBatch))
                viewModel.clearSelection()
            },
        )
    }

    if (uiState.showNotConfiguredDialog) {
        NotConfiguredDialog(
            onDismiss = { viewModel.dismissNotConfiguredDialog() },
            onConfigure = {
                viewModel.dismissNotConfiguredDialog()
                onNavigateToSettings()
            },
        )
    }
}

@Composable
private fun PhotoGridTopBarActions(
    uiState: PhotoGridUiState,
    onClearSelection: () -> Unit,
    onNavigateToUploadHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onToggleFilter: () -> Unit,
    removalEnabled: Boolean,
    onRemoveUploaded: () -> Unit,
) {
    if (uiState.selectedPhotoIds.isNotEmpty()) {
        TextButton(onClick = onClearSelection) {
            Text(stringResource(R.string.action_clear))
        }
    }
    if (uiState.selectedPhotoIds.isEmpty()) {
        IconButton(onClick = onNavigateToUploadHistory) {
            Icon(Icons.Default.History, contentDescription = stringResource(R.string.cd_upload_history))
        }
    }
    if (uiState.uploadedPhotoIds.isNotEmpty() && uiState.selectedPhotoIds.isEmpty()) {
        // The filter button is hidden during selection mode to avoid
        // cluttering the top bar when the "Clear" action is already present.
        IconButton(onClick = onToggleFilter) {
            Icon(
                Icons.Default.FilterList,
                contentDescription =
                    stringResource(
                        if (uiState.showOnlyNewPhotos) {
                            R.string.action_show_all_photos
                        } else {
                            R.string.action_show_only_new
                        },
                    ),
                tint =
                    if (uiState.showOnlyNewPhotos) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
            )
        }
    }
    IconButton(onClick = onNavigateToSettings) {
        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
    }
    if (uiState.selectedPhotoIds.isEmpty()) {
        UploadedRemovalMenu(enabled = removalEnabled, onRemoveUploaded = onRemoveUploaded)
    }
}

@Composable
private fun AutoDateFolderPreviewDialog(
    previewPaths: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_auto_date_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    pluralStringResource(
                        R.plurals.dialog_auto_date_preview_message,
                        previewPaths.size,
                        previewPaths.size,
                    ),
                )
                // The path list scrolls within a bounded height so every day folder is reachable
                // while the message stays on top and the buttons stay visible.
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = PREVIEW_PATH_LIST_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    previewPaths.forEach { previewPath ->
                        Text(stringResource(R.string.preview_upload_path, previewPath))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_upload))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun UploadProgressBanner(
    current: Int,
    total: Int,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.upload_progress, current, total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        LinearProgressIndicator(
            progress = { if (total > 0) current.toFloat() / total else 0f },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(4.dp),
        )
    }
}

@Composable
private fun NewOnlyFilterBanner(onClear: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = true,
            onClick = onClear,
            label = { Text(stringResource(R.string.action_show_only_new)) },
            leadingIcon = {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InnerContent(
    hasPermission: Boolean,
    uiState: PhotoGridUiState,
    onRequestPermission: () -> Unit,
    onPhotoClick: (Long) -> Unit,
    onDateHeaderClick: (LocalDate) -> Unit,
    onRefresh: () -> Unit,
) {
    when {
        !hasPermission -> {
            PermissionDeniedContent(
                modifier = Modifier.fillMaxSize(),
                onRequestPermission = onRequestPermission,
            )
        }

        uiState.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        uiState.error != null -> {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = uiState.error,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        else -> {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                PhotoGrid(
                    photosByDate = uiState.photosByDate,
                    selectedPhotoIds = uiState.selectedPhotoIds,
                    uploadedPhotoIds = uiState.uploadedPhotoIds,
                    onPhotoClick = onPhotoClick,
                    onDateHeaderClick = onDateHeaderClick,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
internal fun PhotoGrid(
    photosByDate: Map<LocalDate, List<Photo>>,
    selectedPhotoIds: Set<Long>,
    uploadedPhotoIds: Set<Long>,
    onPhotoClick: (Long) -> Unit,
    onDateHeaderClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter =
        remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale) }
    val unknownDateText = stringResource(R.string.date_unknown)
    // Formatted once per date and locale, not on every selection change; shared by header and items.
    val formattedDates =
        remember(photosByDate.keys, dateFormatter, unknownDateText) {
            photosByDate.keys.associateWith { date ->
                if (date.isKnownDate()) date.format(dateFormatter) else unknownDateText
            }
        }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        photosByDate.forEach { (date, photos) ->
            val formattedDate = formattedDates.getValue(date)
            item(
                key = "header_$date",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                DateHeader(
                    formattedDate = formattedDate,
                    itemCount = photos.size,
                    allSelected = photos.all { it.id in selectedPhotoIds },
                    onClick = { onDateHeaderClick(date) },
                )
            }
            itemsIndexed(
                items = photos,
                key = { _, photo -> photo.id },
            ) { index, photo ->
                PhotoItem(
                    photo = photo,
                    position = index + 1,
                    total = photos.size,
                    formattedDate = formattedDate,
                    isSelected = photo.id in selectedPhotoIds,
                    isUploaded = photo.id in uploadedPhotoIds,
                    onClick = { onPhotoClick(photo.id) },
                )
            }
        }
    }
}

@Composable
internal fun DateHeader(
    formattedDate: String,
    itemCount: Int,
    allSelected: Boolean,
    onClick: () -> Unit,
) {
    val state = stringResource(if (allSelected) R.string.cd_selected else R.string.cd_not_selected)
    val clickLabel = stringResource(if (allSelected) R.string.action_deselect_day else R.string.action_select_day)
    // The announcement is the merged texts (date + item_count plural over photos and videos); the
    // Checkbox is decorative.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = allSelected, role = Role.Checkbox, onValueChange = { onClick() })
                .semantics {
                    heading()
                    stateDescription = state
                    onClick(label = clickLabel) {
                        onClick()
                        true
                    }
                }.background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = pluralStringResource(R.plurals.item_count, itemCount, itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Checkbox(
            checked = allSelected,
            onCheckedChange = null,
            // Keeps the 48dp touch-target size the interactive checkbox had (header height unchanged).
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}

@Composable
internal fun PhotoItem(
    photo: Photo,
    position: Int,
    total: Int,
    formattedDate: String,
    isSelected: Boolean,
    isUploaded: Boolean,
    onClick: () -> Unit,
) {
    val description = mediaItemDescription(photo, position, total, formattedDate)
    val selectionState = stringResource(if (isSelected) R.string.cd_selected else R.string.cd_not_selected)
    val state =
        if (isUploaded) stringResource(R.string.cd_photo_state_uploaded, selectionState) else selectionState
    val clickLabel = stringResource(if (isSelected) R.string.action_deselect_photo else R.string.action_select_photo)
    // One merged node: description + state + checkbox role; children are decorative.
    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onClick() })
                .semantics {
                    contentDescription = description
                    stateDescription = state
                    onClick(label = clickLabel) {
                        onClick()
                        true
                    }
                },
    ) {
        AsyncImage(
            model =
                ImageRequest
                    .Builder(LocalContext.current)
                    .data(photo.uri)
                    .crossfade(true)
                    .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (photo.isVideo) VideoOverlay(photo.durationMs)
        if (isSelected) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            )
        }
        if (isUploaded) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Icon(
            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .then(
                        if (!isSelected) {
                            Modifier
                                .clip(CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        } else {
                            Modifier
                        },
                    ),
        )
    }
}

/** Description by position and date (never the file name); videos also name their duration when known. */
@Composable
private fun mediaItemDescription(
    photo: Photo,
    position: Int,
    total: Int,
    formattedDate: String,
): String {
    if (!photo.isVideo) {
        return if (photo.hasKnownDate) {
            stringResource(R.string.cd_photo_position_taken_on, position, total, formattedDate)
        } else {
            stringResource(R.string.cd_photo_position_date_unknown, position, total)
        }
    }
    val duration = durationDescription(LocalResources.current, photo.durationMs)
    return when {
        duration != null && photo.hasKnownDate -> {
            stringResource(R.string.cd_video_position_duration_taken_on, position, total, duration, formattedDate)
        }

        duration != null -> {
            stringResource(R.string.cd_video_position_duration_date_unknown, position, total, duration)
        }

        photo.hasKnownDate -> {
            stringResource(R.string.cd_video_position_taken_on, position, total, formattedDate)
        }

        else -> {
            stringResource(R.string.cd_video_position_date_unknown, position, total)
        }
    }
}

/** Decorative play indicator and duration badge of a video tile (the tile node carries the description). */
@Composable
private fun BoxScope.VideoOverlay(durationMs: Long?) {
    Icon(
        imageVector = Icons.Default.PlayCircle,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.9f),
        modifier =
            Modifier
                .align(Alignment.Center)
                .size(36.dp),
    )
    formatDurationBadge(durationMs)?.let { badge ->
        Text(
            text = badge,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .clearAndSetSemantics {},
        )
    }
}

@Composable
private fun PermissionDeniedContent(
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.permission_rationale),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
        TextButton(onClick = onRequestPermission) {
            Text(stringResource(R.string.action_grant_permission))
        }
    }
}

@Composable
private fun NotConfiguredDialog(
    onDismiss: () -> Unit,
    onConfigure: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.dialog_no_server_title)) },
        text = {
            Text(stringResource(R.string.dialog_no_server_message))
        },
        confirmButton = {
            TextButton(onClick = onConfigure) {
                Text(stringResource(R.string.action_configure))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_setup_later))
            }
        },
    )
}
