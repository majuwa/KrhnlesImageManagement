package de.majuwa.android.paper.krhnlesimagemanagement.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.util.MAX_SHARED_PHOTOS

/**
 * Share-target host: renders [viewModel]'s state and hands its one-shot finish to [onFinished]
 * (show the message, `finish()` the activity). [onOpenApp] opens the app in a new task.
 */
@Composable
fun ShareRoute(
    viewModel: ShareViewModel,
    onFinished: (ShareFinish) -> Unit,
    onOpenApp: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val finish by viewModel.finish.collectAsStateWithLifecycle()
    LaunchedEffect(finish) {
        finish?.let {
            viewModel.consumeFinish()
            onFinished(it)
        }
    }
    ShareReceiverScreen(
        state = state,
        onConfirm = viewModel::confirm,
        onCancel = viewModel::cancel,
        onOpenApp = {
            onOpenApp()
            viewModel.cancel()
        },
    )
}

/**
 * Stateless share-target UI: an indeterminate progress with Cancel while the share is read
 * ([ShareUiState.Preparing]), the occasion dialog ([ShareUiState.Ready]), "Preparing x of n" with
 * Cancel while copying (Back cancels too) and the not-configured dialog. Other states show nothing.
 */
@Composable
fun ShareReceiverScreen(
    state: ShareUiState,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenApp: () -> Unit,
) {
    when (state) {
        ShareUiState.Preparing -> ReadingDialog(onCancel)
        is ShareUiState.Ready -> ShareOccasionDialog(state, onConfirm, onCancel)
        is ShareUiState.Copying -> CopyingDialog(state, onCancel)
        ShareUiState.NotConfigured -> NotConfiguredDialog(onOpenApp, onCancel)
        else -> Unit
    }
}

@Composable
private fun ShareOccasionDialog(
    state: ShareUiState.Ready,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val lines =
        buildList {
            add(pluralStringResource(R.plurals.share_upload_message, state.photoCount, state.photoCount))
            if (state.notIncluded > 0) {
                add(
                    pluralStringResource(
                        R.plurals.share_too_many_photos,
                        state.notIncluded,
                        state.notIncluded,
                        MAX_SHARED_PHOTOS,
                    ),
                )
            }
            if (state.skipped > 0) {
                add(pluralStringResource(R.plurals.share_skipped_photos, state.skipped, state.skipped))
            }
        }
    // The ViewModel ignores every confirm after the first, so a double tap cannot enqueue twice.
    OccasionDialog(
        message = lines.joinToString("\n\n"),
        onDismiss = onCancel,
        onConfirm = onConfirm,
    )
}

@Composable
private fun ReadingDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.share_reading_title)) },
        text = {
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun CopyingDialog(
    state: ShareUiState.Copying,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.share_preparing_title)) },
        text = {
            Column {
                Text(stringResource(R.string.share_preparing, state.current, state.total))
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.current.toFloat() / state.total else 0f },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun NotConfiguredDialog(
    onOpenApp: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.share_not_configured_title)) },
        text = { Text(stringResource(R.string.share_not_configured_message)) },
        confirmButton = {
            TextButton(onClick = onOpenApp) {
                Text(stringResource(R.string.share_not_configured_open_app))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
