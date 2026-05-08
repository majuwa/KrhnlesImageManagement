package de.majuwa.android.paper.krhnlesimagemanagement

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
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
                    onStartUpload = { batches ->
                        enqueueUploads(batches)
                    },
                )
            }
        }
    }

    private fun enqueueUploads(batches: List<UploadBatch>) {
        val nonEmptyBatches = batches.filter { it.photos.isNotEmpty() }
        if (nonEmptyBatches.isEmpty()) return

        val workManager = WorkManager.getInstance(this)
        nonEmptyBatches.forEachIndexed { index, batch ->
            // WorkManager Data has a 10 KB limit — write photo list to a file instead.
            val queue =
                JSONObject().apply {
                    put("folderName", batch.folderName.trim())
                    put(
                        "photos",
                        JSONArray().also { arr ->
                            batch.photos.forEach { photo ->
                                arr.put(
                                    JSONObject().apply {
                                        put("uri", photo.uri.toString())
                                        put("mimeType", photo.mimeType)
                                        put("displayName", photo.displayName)
                                    },
                                )
                            }
                        },
                    )
                }
            val queueFile = File(filesDir, "upload_queue_${System.currentTimeMillis()}_$index.json")
            queueFile.writeText(queue.toString())

            val inputData = workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath)

            val uploadRequest =
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(inputData)
                    .addTag("photo_upload")
                    .build()

            workManager.enqueue(uploadRequest)
        }

        val photoCount = nonEmptyBatches.sumOf { it.photos.size }
        Toast
            .makeText(
                this,
                resources.getQuantityString(
                    R.plurals.toast_upload_started,
                    nonEmptyBatches.size,
                    nonEmptyBatches.size,
                    photoCount,
                ),
                Toast.LENGTH_SHORT,
            ).show()
    }
}
