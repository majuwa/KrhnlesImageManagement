package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class RetryUploadReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                runCatching { handleRetry(appContext, intent, CredentialStore(appContext).wifiOnly) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "Retry failed: ${it.javaClass.simpleName}")
                    }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val EXTRA_QUEUE_FILE = "retry_queue_file"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_NOTIFICATION_TAG = "notification_tag"
    }
}

private const val TAG = "RetryUploadReceiver"
private const val NO_NOTIFICATION = -1

/**
 * Handles the "Retry Failed" action: dismisses the notification named in the extras (tag + id)
 * first, then enqueues one [UploadWorker] for the validated retry queue file with the current
 * Wi-Fi-only constraint ([wifiOnly] read bounded by [wifiOnlyTimeoutMs], unmetered on timeout).
 *
 * The work is unique per retry file ([ExistingWorkPolicy.KEEP]): a second tap before the first
 * retry has finished does not start a second one. The enqueue is awaited so the request is
 * persisted before the caller (the receiver) calls `pendingResult.finish()`.
 */
internal suspend fun handleRetry(
    context: Context,
    intent: Intent,
    wifiOnly: Flow<Boolean>,
    wifiOnlyTimeoutMs: Long = WIFI_ONLY_READ_TIMEOUT_MS,
) {
    val notificationId = intent.getIntExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_ID, NO_NOTIFICATION)
    if (notificationId != NO_NOTIFICATION) {
        val tag = intent.getStringExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_TAG)
        NotificationManagerCompat.from(context).cancel(tag, notificationId)
    }

    val queueFilePath = intent.getStringExtra(RetryUploadReceiver.EXTRA_QUEUE_FILE)
    if (queueFilePath == null) {
        Log.w(TAG, "Retry action received without a queue file path — ignoring.")
        return
    }
    val queueFile = File(queueFilePath)
    if (!isRetryQueueFile(context.filesDir, queueFile) || !queueFile.exists()) {
        Log.w(TAG, "Retry queue file is missing or invalid — ignoring.")
        return
    }

    val constraints = uploadConstraints(readWifiOnly(wifiOnly, wifiOnlyTimeoutMs))
    val uploadRequest =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
            .setConstraints(constraints)
            .addTag("photo_upload")
            .build()
    WorkManager
        .getInstance(context)
        .enqueueUniqueWork(queueFile.name, ExistingWorkPolicy.KEEP, uploadRequest)
        .await()
}
