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
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
private const val WORK_STATE_TIMEOUT_SECONDS = 5L

/** The unique work name of the retry of [retryFile] (one retry per file, see [handleRetry]). */
internal fun retryWorkName(retryFile: File): String = retryFile.name

/**
 * Whether the retry work for the retry file named [retryFileName] is still enqueued, blocked or
 * running; null if the state is unknown (WorkManager unavailable, no answer within 5 s).
 * Blocking: call off the main thread.
 */
internal fun hasUnfinishedRetryWork(
    context: Context,
    retryFileName: String,
): Boolean? =
    try {
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(retryFileName)
            .get(WORK_STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .any { !it.state.isFinished }
    } catch (e: TimeoutException) {
        logUnknownWorkState(e)
    } catch (e: ExecutionException) {
        logUnknownWorkState(e)
    } catch (e: IllegalStateException) {
        // WorkManager not initialized.
        logUnknownWorkState(e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logUnknownWorkState(e)
    }

private fun logUnknownWorkState(e: Exception): Boolean? {
    Log.w(TAG, "Retry work state unknown: ${e.javaClass.simpleName}")
    return null
}

/**
 * Handles the "Retry Failed" action: dismisses the notification named in the extras (tag + id)
 * first, then enqueues one [UploadWorker] for the validated retry queue file with the current
 * Wi-Fi-only constraint ([wifiOnly] read bounded by [wifiOnlyTimeoutMs], unmetered on timeout).
 *
 * The work is unique per retry file ([ExistingWorkPolicy.KEEP], name [retryWorkName]): a second
 * tap before the first retry has finished does not start a second one. The enqueue is awaited so
 * the request is persisted before the caller (the receiver) calls `pendingResult.finish()`.
 *
 * The retry file's modification time is refreshed first: the staging sweep judges a retry by it,
 * so a tapped retry gets a full new retention period for its staged copies.
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
    if (!isRetryQueueFile(context, queueFile) || !queueFile.exists()) {
        Log.w(TAG, "Retry queue file is missing or invalid — ignoring.")
        return
    }

    if (!queueFile.setLastModified(System.currentTimeMillis())) {
        Log.w(TAG, "Retry queue file time could not be refreshed")
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
        .enqueueUniqueWork(retryWorkName(queueFile), ExistingWorkPolicy.KEEP, uploadRequest)
        .await()
}
