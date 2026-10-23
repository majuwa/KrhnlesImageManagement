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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName

/**
 * Asks for the occasion (remote folder) name. A name that cannot be a single folder (`.`, `..`,
 * containing `/` or `\`) shows an inline error and cannot be confirmed. [confirmEnabled] lets the
 * caller block re-entry after a confirm.
 */
@Composable
fun OccasionDialog(
    message: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    confirmEnabled: Boolean = true,
) {
    var occasionName by remember { mutableStateOf("") }
    val invalid = occasionName.isNotBlank() && !isValidOccasionName(occasionName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_upload_title)) },
        text = {
            Column {
                Text(message)
                OutlinedTextField(
                    value = occasionName,
                    onValueChange = { occasionName = it },
                    label = { Text(stringResource(R.string.label_occasion_name)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText =
                        if (invalid) {
                            { Text(stringResource(R.string.error_invalid_occasion_name)) }
                        } else {
                            null
                        },
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
                enabled = confirmEnabled && isValidOccasionName(occasionName),
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
