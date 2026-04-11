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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGridScreen(
    viewModel: PhotoGridViewModel,
    onNavigateToSettings: () -> Unit,
    onStartUpload: (occasionName: String, photos: List<Photo>) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun hasPhotoAccess(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(hasPhotoAccess()) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.any { it.value }
            hasPermission = granted
            if (granted) viewModel.loadPhotos()
        }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasNotificationPermission = granted
        }

    val photoPermissions =
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadPhotos()
        } else {
            permissionLauncher.launch(photoPermissions)
        }
    }

    LaunchedEffect(hasNotificationPermission) {
        if (!hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Show not-configured dialog on first launch if no server is set up
    LaunchedEffect(isConfigured) {
        if (!isConfigured) {
            viewModel.dismissOccasionDialog()
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
                    if (uiState.selectedPhotoIds.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearSelection() }) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
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
            Box(modifier = Modifier.weight(1f)) {
                InnerContent(
                    hasPermission = hasPermission,
                    uiState = uiState,
                    onRequestPermission = { permissionLauncher.launch(photoPermissions) },
                    onPhotoClick = { viewModel.togglePhotoSelection(it) },
                    onDateHeaderClick = { viewModel.toggleDateSelection(it) },
                )
            }
        }
    }

    if (uiState.showOccasionDialog) {
        OccasionDialog(
            onDismiss = { viewModel.dismissOccasionDialog() },
            onConfirm = { occasionName ->
                viewModel.dismissOccasionDialog()
                onStartUpload(occasionName, viewModel.getSelectedPhotos())
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
private fun InnerContent(
    hasPermission: Boolean,
    uiState: PhotoGridUiState,
    onRequestPermission: () -> Unit,
    onPhotoClick: (Long) -> Unit,
    onDateHeaderClick: (LocalDate) -> Unit,
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = uiState.error,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        else -> {
            PhotoGrid(
                photosByDate = uiState.photosByDate,
                selectedPhotoIds = uiState.selectedPhotoIds,
                onPhotoClick = onPhotoClick,
                onDateHeaderClick = onDateHeaderClick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PhotoGrid(
    photosByDate: Map<LocalDate, List<Photo>>,
    selectedPhotoIds: Set<Long>,
    onPhotoClick: (Long) -> Unit,
    onDateHeaderClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        photosByDate.forEach { (date, photos) ->
            item(
                key = "header_$date",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                DateHeader(
                    formattedDate = date.format(dateFormatter),
                    photoCount = photos.size,
                    allSelected = photos.all { it.id in selectedPhotoIds },
                    onClick = { onDateHeaderClick(date) },
                )
            }
            items(
                items = photos,
                key = { it.id },
            ) { photo ->
                PhotoItem(
                    photo = photo,
                    isSelected = photo.id in selectedPhotoIds,
                    onClick = { onPhotoClick(photo.id) },
                )
            }
        }
    }
}

@Composable
private fun DateHeader(
    formattedDate: String,
    photoCount: Int,
    allSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(MaterialTheme.colorScheme.surfaceVariant)
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
                text = pluralStringResource(R.plurals.photo_count, photoCount, photoCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Checkbox(
            checked = allSelected,
            onCheckedChange = { onClick() },
        )
    }
}

@Composable
private fun PhotoItem(
    photo: Photo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model =
                ImageRequest
                    .Builder(LocalContext.current)
                    .data(photo.uri)
                    .crossfade(true)
                    .build(),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isSelected) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            )
        }
        Icon(
            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = stringResource(if (isSelected) R.string.cd_selected else R.string.cd_not_selected),
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

@Composable
private fun OccasionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var occasionName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_upload_title)) },
        text = {
            Column {
                Text(stringResource(R.string.dialog_upload_message))
                OutlinedTextField(
                    value = occasionName,
                    onValueChange = { occasionName = it },
                    label = { Text(stringResource(R.string.label_occasion_name)) },
                    singleLine = true,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(occasionName.trim()) },
                enabled = occasionName.isNotBlank(),
            ) {
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
