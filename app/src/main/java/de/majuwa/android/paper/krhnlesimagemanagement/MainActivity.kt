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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

        // WorkManager Data has a 10 KB limit — write photo list to a file instead.
        val queue =
            JSONObject().apply {
                put("folderName", occasionName.trim())
                put(
                    "photos",
                    JSONArray().also { arr ->
                        photos.forEach { p ->
                            arr.put(
                                JSONObject().apply {
                                    put("uri", p.uri.toString())
                                    put("mimeType", p.mimeType)
                                    put("displayName", p.displayName)
                                },
                            )
                        }
                    },
                )
            }
        val queueFile = File(filesDir, "upload_queue_${System.currentTimeMillis()}.json")
        queueFile.writeText(queue.toString())

        val inputData = workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath)

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
