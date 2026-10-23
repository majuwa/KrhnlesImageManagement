package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.util.Log
import java.io.File
import java.io.IOException

private const val RETRY_FILE_REGEX = "retry_queue_[0-9a-f-]+\\.json"
private const val UPLOAD_FILE_REGEX = "upload_queue_[0-9]+_[0-9a-f-]+\\.json"
private val retryFileName = Regex(RETRY_FILE_REGEX)
private val workerQueueFileName = Regex("$RETRY_FILE_REGEX|$UPLOAD_FILE_REGEX")

/** True if [file] is a retry queue file written by the app, directly inside [filesDir]. */
internal fun isRetryQueueFile(
    filesDir: File,
    file: File,
): Boolean = isQueueFileIn(filesDir, file, retryFileName)

/** True if [file] is an upload or retry queue file written by the app, directly inside [filesDir]. */
internal fun isWorkerQueueFile(
    filesDir: File,
    file: File,
): Boolean = isQueueFileIn(filesDir, file, workerQueueFileName)

private fun isQueueFileIn(
    filesDir: File,
    file: File,
    namePattern: Regex,
): Boolean =
    try {
        namePattern.matches(file.name) && file.canonicalFile.parentFile == filesDir.canonicalFile
    } catch (e: IOException) {
        Log.w("QueueFile", "Cannot canonicalize queue file path: ${e.javaClass.simpleName}")
        false
    }
