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
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareReceiverScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.util.parseSharedPhotos
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
                            enqueueUpload(occasionName, sharedPhotos)
                            finish()
                        },
                        onDismiss = { finish() },
                    )
                } else {
                    KrhnlesApp(
                        onStartUpload = { occasionName, photos ->
                            enqueueUpload(occasionName, photos)
                        },
                    )
                }
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
                                    put("id", p.id)
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

        WorkManager.getInstance(this).enqueue(uploadRequest)
        Toast
            .makeText(
                this,
                getString(R.string.toast_uploading, occasionName),
                Toast.LENGTH_SHORT,
            ).show()
    }
}

