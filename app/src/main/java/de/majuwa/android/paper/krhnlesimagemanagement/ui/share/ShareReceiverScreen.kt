package de.majuwa.android.paper.krhnlesimagemanagement.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.majuwa.android.paper.krhnlesimagemanagement.R

/**
 * Full-screen composable shown when the app is launched as a share target.
 *
 * Displays an occasion-name dialog directly; the caller is responsible for
 * calling [onConfirm] (which should enqueue the upload and finish the activity)
 * or [onDismiss] (which should finish the activity without uploading).
 */
@Composable
fun ShareReceiverScreen(
    photoCount: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var occasionName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_upload_title)) },
        text = {
            Column {
                Text(
                    pluralStringResource(
                        R.plurals.share_upload_message,
                        photoCount,
                        photoCount,
                    ),
                )
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
