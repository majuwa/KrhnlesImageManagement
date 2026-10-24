package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "UploadWorker"

/**
 * Size of [uri] as reported by its provider (`openAssetFileDescriptor(uri, "r").length`; the
 * descriptor is closed again), or [WebDavClient.UNKNOWN_CONTENT_LENGTH] when it is unknown or
 * cannot be read — the upload then uses chunked transfer.
 */
internal fun ContentResolver.contentLengthOf(uri: Uri): Long {
    val length =
        try {
            openAssetFileDescriptor(uri, "r")?.use { it.length }
        } catch (e: FileNotFoundException) {
            logUnknownLength(e)
        } catch (e: SecurityException) {
            logUnknownLength(e)
        } catch (e: IllegalArgumentException) {
            logUnknownLength(e)
        } catch (e: IOException) {
            logUnknownLength(e)
        } catch (e: UnsupportedOperationException) {
            logUnknownLength(e)
        }
    return if (length == null || length == AssetFileDescriptor.UNKNOWN_LENGTH || length < 0L) {
        WebDavClient.UNKNOWN_CONTENT_LENGTH
    } else {
        length
    }
}

private fun logUnknownLength(e: Exception): Long? {
    Log.w(TAG, "Size unknown, uploading chunked: ${e.javaClass.simpleName}")
    return null
}

/**
 * [CoroutineWorker.setForeground] that reports a refused foreground service (e.g.
 * `ForegroundServiceStartNotAllowedException` once the `dataSync` time budget is used up, or a
 * background-start restriction) as `false` instead of failing the work.
 */
internal suspend fun CoroutineWorker.setForegroundOrFalse(info: ForegroundInfo): Boolean =
    try {
        setForeground(info)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Foreground service not allowed: ${e.javaClass.simpleName}")
        false
    }

/** What the result notification of a batch reports. */
internal data class CompletionSummary(
    val uploaded: Int,
    val failed: Int,
    val retryQueueFilePath: String?,
    /** User-facing cause of the failed items, appended to the counts; null = none. */
    val failureReason: String? = null,
    /** The run stopped after too many interrupted attempts: titled "Upload failed". */
    val gaveUp: Boolean = false,
)

/** User-facing reason for the failures of a run, or null if none has a specific message. */
internal fun uploadFailureMessage(
    resources: Resources,
    failures: Set<UploadFailure>,
): String? =
    joinNotificationTexts(
        resources,
        listOfNotNull(
            resources.getString(R.string.upload_failure_too_large).takeIf { UploadFailure.TooLarge in failures },
            resources.getString(R.string.upload_failure_storage_full).takeIf { UploadFailure.StorageFull in failures },
        ),
    )

/**
 * Joins notification sentences through the `notification_text_with_reason` resource (the
 * separator and order are localizable); null for an empty list.
 */
internal fun joinNotificationTexts(
    resources: Resources,
    texts: List<String>,
): String? =
    texts.reduceOrNull {
        first,
        second,
        ->
        resources.getString(R.string.notification_text_with_reason, first, second)
    }
