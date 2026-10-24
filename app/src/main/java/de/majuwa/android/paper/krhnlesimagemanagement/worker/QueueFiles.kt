package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

private const val RETRY_FILE_REGEX = "retry_queue_[0-9a-f-]+\\.json"
private const val UPLOAD_FILE_REGEX = "upload_queue_[0-9]+_[0-9a-f-]+\\.json"
private val retryFileName = Regex(RETRY_FILE_REGEX)
private val uploadFileName = Regex(UPLOAD_FILE_REGEX)
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

/** True if [name] is the name of an upload queue file (not a retry file). */
internal fun isUploadQueueFileName(name: String): Boolean = uploadFileName.matches(name)

/** True if [name] is the name of a retry queue file. */
internal fun isRetryQueueFileName(name: String): Boolean = retryFileName.matches(name)

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

/** Temporary file next to [queueFile] used by [rewriteQueueFile]; never matches a queue file name. */
internal fun queueTempFile(queueFile: File): File = File(queueFile.parentFile, "${queueFile.name}.tmp")

/**
 * Replaces the content of [queueFile] with [json] atomically: written to [queueTempFile] first and
 * renamed over the queue file, so a reader (or a run after process death) sees either the old or
 * the new content, never a partial file. Returns false (queue file unchanged) on failure.
 */
internal fun rewriteQueueFile(
    queueFile: File,
    json: String,
): Boolean {
    val temp = queueTempFile(queueFile)
    return try {
        temp.writeText(json)
        temp.renameTo(queueFile).also { renamed ->
            if (!renamed) {
                Log.w("QueueFile", "Queue file progress could not be saved: rename failed")
                temp.delete()
            }
        }
    } catch (e: IOException) {
        Log.w("QueueFile", "Queue file progress could not be saved: ${e.javaClass.simpleName}")
        temp.delete()
        false
    }
}
