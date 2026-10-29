package de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.majuwa.android.paper.krhnlesimagemanagement.R
import kotlinx.coroutines.launch

/** The top-bar overflow menu with "Remove uploaded photos from device"; disabled while a removal runs. */
@Composable
internal fun UploadedRemovalMenu(
    enabled: Boolean,
    onRemoveUploaded: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_remove_uploaded)) },
            enabled = enabled,
            onClick = {
                expanded = false
                onRemoveUploaded()
            },
            modifier = Modifier.testTag("removeUploadedItem"),
        )
    }
}

/**
 * Dialogs, the system trash launcher and the result snackbar of the removal action. [onTrashed] runs
 * once after photos were confirmed in the trash (the screen reloads the grid).
 */
@Composable
internal fun UploadedRemovalHost(
    viewModel: UploadedRemovalViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToSettings: () -> Unit,
    onTrashed: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val request by viewModel.pendingRequest.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    val trashLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onTrashResult(result.resultCode)
        }
    // The request is cleared as soon as it is launched, so a recreated screen never launches it twice.
    LaunchedEffect(request) {
        request?.let {
            trashLauncher.launch(it)
            viewModel.onRequestLaunched()
        }
    }
    LaunchedEffect(outcome) {
        val shown = outcome ?: return@LaunchedEffect
        val message =
            when (shown) {
                is RemovalOutcome.Trashed -> {
                    resources.getQuantityString(R.plurals.removal_result_trashed, shown.count, shown.count)
                }

                RemovalOutcome.Cancelled -> {
                    resources.getString(R.string.removal_result_cancelled)
                }

                RemovalOutcome.Failed -> {
                    resources.getString(R.string.removal_result_failed)
                }
            }
        // Consuming changes this effect's key; the snackbar must outlive the effect, so it has its own scope.
        viewModel.consumeOutcome()
        if (shown is RemovalOutcome.Trashed) onTrashed()
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    when (val current = state) {
        is RemovalState.Checking -> {
            CheckingDialog(current, onCancel = viewModel::cancel)
        }

        is RemovalState.Confirm -> {
            ConfirmDialog(
                current.count,
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancel,
            )
        }

        RemovalState.NothingToRemove -> {
            InfoDialog(
                stringResource(R.string.removal_nothing_title),
                stringResource(R.string.removal_nothing_message),
                viewModel::cancel,
            )
        }

        RemovalState.ServerUnreachable -> {
            InfoDialog(
                stringResource(R.string.removal_server_unreachable_title),
                stringResource(R.string.removal_server_unreachable_message),
                viewModel::cancel,
            )
        }

        RemovalState.NotConfigured -> {
            NotConfiguredRemovalDialog(onNavigateToSettings, viewModel::cancel)
        }

        RemovalState.Trashing -> {
            TrashingDialog()
        }

        RemovalState.Idle -> {
            // No dialog while idle.
        }
    }
}

@Composable
private fun CheckingDialog(
    state: RemovalState.Checking,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.action_remove_uploaded)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.total > 0) {
                    Text(
                        pluralStringResource(R.plurals.removal_checking_progress, state.total, state.done, state.total),
                    )
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(stringResource(R.string.removal_checking_preparing))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("removalCancelCheck")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.removal_confirm_title, count, count)) },
        text = { Text(stringResource(R.string.removal_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("removalConfirm")) {
                Text(stringResource(R.string.action_move_to_trash))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun InfoDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
private fun NotConfiguredRemovalDialog(
    onNavigateToSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.removal_nothing_title)) },
        text = { Text(stringResource(R.string.removal_not_configured_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onNavigateToSettings()
                },
            ) { Text(stringResource(R.string.action_configure)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun TrashingDialog() {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.removal_trashing))
            }
        },
    )
}
