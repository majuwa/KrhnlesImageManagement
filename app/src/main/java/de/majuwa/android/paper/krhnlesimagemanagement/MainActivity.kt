package de.majuwa.android.paper.krhnlesimagemanagement

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareReceiverScreen
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.util.parseSharedPhotos
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val credentialStore by lazy { CredentialStore(this) }
    private val wifiOnly: StateFlow<Boolean> by lazy {
        credentialStore.wifiOnly.stateIn(
            scope = lifecycleScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedPhotos = parseSharedPhotos(intent, contentResolver)

        setContent {
            KrhnlesImageManagementTheme {
                if (sharedPhotos.isNotEmpty()) {
                    ShareReceiverScreen(
                        photoCount = sharedPhotos.size,
                        onConfirm = { occasionName ->
                            enqueueUploads(listOf(UploadBatch(occasionName, sharedPhotos)))
                            finish()
                        },
                        onDismiss = { finish() },
                    )
                } else {
                    KrhnlesApp(
                        onStartUpload = { batches ->
                            enqueueUploads(batches)
                        },
                    )
                }
            }
        }
    }

    private fun enqueueUploads(batches: List<UploadBatch>) {
        val nonEmptyBatches = batches.filter { it.photos.isNotEmpty() }
        if (nonEmptyBatches.isEmpty()) return

        val workManager = WorkManager.getInstance(this)
        nonEmptyBatches.forEach { batch ->
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
                                        put("id", photo.id)
                                        put("uri", photo.uri.toString())
                                        put("mimeType", photo.mimeType)
                                        put("displayName", photo.displayName)
                                    },
                                )
                            }
                        },
                    )
                }
            val queueFile = File(filesDir, "upload_queue_${System.currentTimeMillis()}_${UUID.randomUUID()}.json")
            queueFile.writeText(queue.toString())

            val inputData = workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath)

            val constraints =
                Constraints.Builder()
                    .apply { if (wifiOnly.value) setRequiredNetworkType(NetworkType.UNMETERED) }
                    .build()

            val uploadRequest =
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(inputData)
                    .setConstraints(constraints)
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
