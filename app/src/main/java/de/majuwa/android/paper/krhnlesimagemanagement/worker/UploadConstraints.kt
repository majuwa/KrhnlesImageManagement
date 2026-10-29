package de.majuwa.android.paper.krhnlesimagemanagement.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

internal const val WIFI_ONLY_READ_TIMEOUT_MS = 3_000L

/** Tag of every upload work request. */
internal const val UPLOAD_WORK_TAG = "photo_upload"

/** First back-off delay of a retried upload run; doubles per run (exponential, capped by WorkManager). */
internal const val UPLOAD_BACKOFF_DELAY_SECONDS = 60L

/**
 * The one upload work request for [queueFile] (new uploads and retries): [constraints], the
 * upload tag and an explicit exponential back-off, so a run that keeps being interrupted (refused
 * foreground service, Wi-Fi-only on a metered network) waits longer each time instead of looping.
 */
internal fun uploadWorkRequest(
    queueFile: File,
    constraints: Constraints,
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UPLOAD_BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
        .addTag(UPLOAD_WORK_TAG)
        .build()

/** Network constraint of an upload: unmetered when Wi-Fi-only is enabled, otherwise none. */
internal fun uploadConstraints(wifiOnly: Boolean): Constraints =
    Constraints
        .Builder()
        .apply { if (wifiOnly) setRequiredNetworkType(NetworkType.UNMETERED) }
        .build()

/**
 * Reads the "verify by downloading again" setting, bounded by [timeoutMs]. A timeout or any
 * non-cancellation failure yields `false`: an unreadable setting never causes extra data use.
 */
internal suspend fun readVerifyByDownload(
    verifyByDownload: Flow<Boolean>,
    timeoutMs: Long = WIFI_ONLY_READ_TIMEOUT_MS,
): Boolean =
    runCatching { withTimeoutOrNull(timeoutMs) { verifyByDownload.first() } }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull() ?: false

/**
 * Reads the current Wi-Fi-only setting, bounded by [timeoutMs]. A timeout or any non-cancellation
 * failure yields `true` (unmetered), so an unreadable setting never uses mobile data.
 */
internal suspend fun readWifiOnly(
    wifiOnly: Flow<Boolean>,
    timeoutMs: Long = WIFI_ONLY_READ_TIMEOUT_MS,
): Boolean =
    runCatching { withTimeoutOrNull(timeoutMs) { wifiOnly.first() } }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull() ?: true
