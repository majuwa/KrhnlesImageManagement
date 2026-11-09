package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.LaunchRequestViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.launchRequestOf
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
    private val launchRequestViewModel: LaunchRequestViewModel by viewModels()

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
            if (savedInstanceState == null) {
                // Only a fresh start: a recreated activity (rotation, process death) must not re-apply the
                // original intent.
                launchRequestOf(intent)?.let(launchRequestViewModel::offer)
            }
            // Once per process (guarded in the controller), also after process death restored the activity.
            (application as KrhnlesApplication).reconcileInBackground()
            // A running instance receives taps on the reminder here (CLEAR_TOP + SINGLE_TOP). Share-mode
            // instances never register: the share screen stays and the request is dropped.
            addOnNewIntentListener { newIntent ->
                if (!isShareIntent(newIntent)) launchRequestOf(newIntent)?.let(launchRequestViewModel::offer)
            }
            setContent {
                KrhnlesImageManagementTheme {
                    KrhnlesApp(
                        onStartUpload = { batches -> uploadEnqueuer.enqueue(batches) },
                        launchRequests = launchRequestViewModel.pending,
                        onLaunchRequestConsumed = { launchRequestViewModel.consume() },
                    )
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
