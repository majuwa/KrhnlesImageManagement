package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

private const val RETRY_FILE_REGEX = "retry_queue_[0-9a-f-]+\\.json"
private const val UPLOAD_FILE_REGEX = "upload_queue_[0-9]+_[0-9a-f-]+\\.json"
private val retryFileName = Regex(RETRY_FILE_REGEX)
private val workerQueueFileName = Regex("$RETRY_FILE_REGEX|$UPLOAD_FILE_REGEX")

/**
 * Directory for new queue and retry files. `noBackupFilesDir` is excluded from cloud backup and
 * device transfer automatically (the files contain per-photo capture days).
 */
internal fun queueFilesDir(context: Context): File = context.noBackupFilesDir

/** True if [file] is a retry queue file written by the app, directly inside a queue directory. */
internal fun isRetryQueueFile(
    context: Context,
    file: File,
): Boolean = isQueueFileIn(queueParents(context), file, retryFileName)

/** True if [file] is an upload or retry queue file written by the app, directly inside a queue directory. */
internal fun isWorkerQueueFile(
    context: Context,
    file: File,
): Boolean = isQueueFileIn(queueParents(context), file, workerQueueFileName)

/** `noBackupFilesDir` for new files, plus the legacy `filesDir` for files written by older versions. */
private fun queueParents(context: Context): List<File> = listOf(context.noBackupFilesDir, context.filesDir)

private fun isQueueFileIn(
    parents: List<File>,
    file: File,
    namePattern: Regex,
): Boolean =
    try {
        val parent = file.canonicalFile.parentFile
        namePattern.matches(file.name) && parents.any { it.canonicalFile == parent }
    } catch (e: IOException) {
        Log.w("QueueFile", "Cannot canonicalize queue file path: ${e.javaClass.simpleName}")
        false
    }
