package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareFinish
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareRoute
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.shareFinishMessage
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadEnqueuer
import de.majuwa.android.paper.krhnlesimagemanagement.util.isShareIntent

class MainActivity : ComponentActivity() {
    // Process-scoped: enqueuing must not depend on this activity surviving (rotation, finish()).
    private val uploadEnqueuer: UploadEnqueuer
        get() = (application as KrhnlesApplication).uploadEnqueuer

    private val shareViewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (isShareIntent(intent)) {
            // The ViewModel survives rotation: start() only acts once, a running copy continues.
            shareViewModel.start(intent)
            setContent {
                KrhnlesImageManagementTheme {
                    ShareRoute(
                        viewModel = shareViewModel,
                        onFinished = ::finishShare,
                        onOpenApp = ::openApp,
                    )
                }
            }
        } else {
            setContent {
                KrhnlesImageManagementTheme {
                    KrhnlesApp(onStartUpload = { batches -> uploadEnqueuer.enqueue(batches) })
                }
            }
        }
    }

    /** Shows the share's outcome (if any) and returns to the sharing app. */
    private fun finishShare(result: ShareFinish) {
        shareFinishMessage(resources, result)?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show() }
        finish()
    }

    /** Opens the app in its own task (this activity runs in the sharing app's task). */
    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
