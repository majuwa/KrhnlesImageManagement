package de.majuwa.android.paper.krhnlesimagemanagement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareReceiverScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadEnqueuer
import de.majuwa.android.paper.krhnlesimagemanagement.util.parseSharedPhotos

class MainActivity : ComponentActivity() {
    // Process-scoped: enqueuing must not depend on this activity surviving (rotation, finish()).
    private val uploadEnqueuer: UploadEnqueuer
        get() = (application as KrhnlesApplication).uploadEnqueuer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedPhotos = parseSharedPhotos(intent, contentResolver, packageName)

        setContent {
            KrhnlesImageManagementTheme {
                if (sharedPhotos.isNotEmpty()) {
                    ShareReceiverScreen(
                        photoCount = sharedPhotos.size,
                        onConfirm = { occasionName ->
                            uploadEnqueuer.enqueue(listOf(UploadBatch(occasionName, sharedPhotos)))
                            finish()
                        },
                        onDismiss = { finish() },
                    )
                } else {
                    KrhnlesApp(onStartUpload = { batches -> uploadEnqueuer.enqueue(batches) })
                }
            }
        }
    }
}
