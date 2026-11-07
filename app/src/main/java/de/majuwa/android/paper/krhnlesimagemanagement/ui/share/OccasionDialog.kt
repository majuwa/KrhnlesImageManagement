package de.majuwa.android.paper.krhnlesimagemanagement.ui.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
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
    suggestions: List<String> = emptyList(),
) {
    // Saveable: the typed name survives rotation and process recreation of the dialog's host.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val occasionName = field.text
    val shown = remember(suggestions, occasionName) { filterOccasionSuggestions(suggestions, occasionName) }
    val invalid = occasionName.isNotBlank() && !isValidOccasionName(occasionName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_upload_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(message)
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
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
                if (shown.isNotEmpty()) {
                    SuggestionList(shown) { name -> field = TextFieldValue(name, TextRange(name.length)) }
                }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionList(
    names: List<String>,
    onSelect: (String) -> Unit,
) {
    val useLabel = stringResource(R.string.occasion_suggestion_use_label)
    Text(
        text = stringResource(R.string.occasion_suggestions_heading),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        names.forEach { name ->
            Surface(
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = useLabel, role = Role.Button) { onSelect(name) },
            ) {
                Text(
                    text = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
