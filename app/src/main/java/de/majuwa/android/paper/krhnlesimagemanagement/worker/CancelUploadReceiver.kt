package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Target of the "Cancel" action of the upload progress notification (an explicit, immutable
 * broadcast; the receiver is not exported). It only cancels *running* upload work, identified by
 * the work id in [EXTRA_WORK_ID] (see [cancelRunningUpload]); the cancelled worker finalizes its own
 * queue. It touches no file.
 */
class CancelUploadReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                runCatching { handleCancel(intent, WorkManager.getInstance(appContext)) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "Cancel failed: ${it.javaClass.simpleName}")
                    }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val EXTRA_WORK_ID = "work_id"
    }
}

private const val TAG = "CancelUploadReceiver"

/**
 * Cancels the upload named by [CancelUploadReceiver.EXTRA_WORK_ID] if it is running; a missing or
 * malformed id is ignored (logged without the value).
 */
internal suspend fun handleCancel(
    intent: Intent,
    workManager: WorkManager,
) {
    val workId =
        try {
            intent.getStringExtra(CancelUploadReceiver.EXTRA_WORK_ID)?.let(UUID::fromString)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cancel action with a malformed work id — ignoring: ${e.javaClass.simpleName}")
            null
        }
    if (workId == null) {
        Log.w(TAG, "Cancel action without a valid work id — ignoring.")
        return
    }
    cancelRunningUpload(workManager, workId)
}
