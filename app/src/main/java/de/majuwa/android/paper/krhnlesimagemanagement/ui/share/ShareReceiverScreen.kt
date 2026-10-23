package de.majuwa.android.paper.krhnlesimagemanagement.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import de.majuwa.android.paper.krhnlesimagemanagement.R

/**
 * Full-screen composable shown when the app is launched as a share target.
 *
 * Displays an occasion-name dialog directly; the caller is responsible for
 * calling [onConfirm] (which should enqueue the upload and finish the activity)
 * or [onDismiss] (which should finish the activity without uploading).
 *
 * [onConfirm] fires at most once: after the first confirm the button is disabled and further
 * confirms are ignored, so a double tap cannot enqueue the upload twice.
 */
@Composable
fun ShareReceiverScreen(
    photoCount: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var submitted by remember { mutableStateOf(false) }

    OccasionDialog(
        message = pluralStringResource(R.plurals.share_upload_message, photoCount, photoCount),
        onDismiss = onDismiss,
        confirmEnabled = !submitted,
        onConfirm = { occasionName ->
            if (!submitted) {
                submitted = true
                onConfirm(occasionName)
            }
        },
    )
}
