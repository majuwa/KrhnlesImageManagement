package de.majuwa.android.paper.krhnlesimagemanagement

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KrhnlesImageManagementTheme {
                KrhnlesApp(
                    onStartUpload = { occasionName, photos ->
                        enqueueUpload(occasionName, photos)
                    },
                )
            }
        }
    }

    private fun enqueueUpload(
        occasionName: String,
        photos: List<Photo>,
    ) {
        if (photos.isEmpty()) return

        val inputData =
            workDataOf(
                UploadWorker.KEY_FOLDER_NAME to occasionName,
                UploadWorker.KEY_PHOTO_URIS to photos.map { it.uri.toString() }.toTypedArray(),
                UploadWorker.KEY_MIME_TYPES to photos.map { it.mimeType }.toTypedArray(),
                UploadWorker.KEY_FILE_NAMES to photos.map { it.displayName }.toTypedArray(),
            )

        val uploadRequest =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(inputData)
                .addTag("photo_upload")
                .build()

        WorkManager.getInstance(this).enqueue(uploadRequest)
        Toast
            .makeText(
                this,
                "Uploading ${photos.size} photos to \"$occasionName\"...",
                Toast.LENGTH_SHORT,
            ).show()
    }
}
