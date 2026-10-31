package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "UploadCancellation"

/** Bound of the suspending parts of [finalizeCancelledQueue] (history write). */
internal const val CANCEL_FINALIZE_TIMEOUT_MS = 10_000L

/** Bound of every WorkManager read / cancel made on behalf of the user's Cancel action. */
internal const val CANCEL_WORK_TIMEOUT_MS = 5_000L

/** What [finalizeCancelledQueue] did with the queue file. */
internal sealed interface CancelFinalization {
    /** Items remain: they are in [retryFile], and "Retry Failed" resumes them. */
    data class Resumable(
        val retryFile: File,
    ) : CancelFinalization

    /** Every item had been uploaded: the batch was reported as complete and the queue deleted. */
    data object Completed : CancelFinalization

    /** The queue file was already claimed (or is gone): nothing was done. */
    data object NotClaimed : CancelFinalization
}

/**
 * Finalizes the queue of an upload the user cancelled, exactly once (design D4):
 * 1. **Claim**: the queue file is renamed (atomically) to a new `retry_queue_<uuid>.json` in the
 *    no-backup directory. Only the caller whose rename succeeds goes on; right after the rename the
 *    file is already a valid retry file (uploaded entries are skipped), so a crash later loses nothing.
 * 2. Nothing pending: [deleteQueueFile] and the usual completion is reported.
 * 3. Otherwise the retry file is rewritten with only the entries not uploaded (each with its folder,
 *    no `uploaded` markers and no `interruptions`: a user cancel never uses the interruption
 *    budget), its modification time is refreshed (the 7-day staging retry window starts now) and
 *    the staged copies of the uploaded entries are deleted.
 * 4. One history entry and one result notification (tagged with the original queue file name).
 *
 * [queue] is the progress of the run (the worker's in-memory queue, or the parsed file for the
 * start-up recovery). Runs non-cancellable; the suspending history write is bounded by
 * [CANCEL_FINALIZE_TIMEOUT_MS].
 */
internal suspend fun finalizeCancelledQueue(
    context: Context,
    queueFile: File,
    queue: UploadQueue,
    failures: Set<UploadFailure>,
    history: UploadHistoryRepository,
): CancelFinalization =
    withContext(NonCancellable) {
        val notificationTag = queueFile.name
        val retryFile = File(queueFilesDir(context), "retry_queue_${UUID.randomUUID()}.json")
        if (!queueFile.renameTo(retryFile)) {
            Log.w(TAG, "Cancelled queue was not claimed (already finalized or not renamable)")
            return@withContext CancelFinalization.NotClaimed
        }
        queueTempFile(queueFile).delete()

        val pending = queue.pendingIndices
        val total = queue.entries.size
        val notUploaded = pending.size
        val resumable = pending.isNotEmpty()
        if (resumable) {
            prepareRetryFile(context, retryFile, queue, pending)
        } else {
            deleteQueueFile(context, retryFile)
        }

        withTimeoutOrNull(CANCEL_FINALIZE_TIMEOUT_MS) {
            history.recordRun(queue.historyLabel, total, notUploaded, cancelled = resumable)
        }
        val resources = context.resources
        val summary =
            CompletionSummary(
                uploaded = total - notUploaded,
                failed = notUploaded,
                retryQueueFilePath = retryFile.absolutePath.takeIf { resumable },
                failureReason = uploadFailureMessage(resources, failures),
                cancelled = resumable,
            )
        context.createUploadNotificationChannel()
        context.notifyIfPermitted(context.buildCompletionNotification(summary, notificationTag), notificationTag)
        if (resumable) CancelFinalization.Resumable(retryFile) else CancelFinalization.Completed
    }

/**
 * Turns the claimed [retryFile] into the retry file of the entries at [pending]. The staged copies
 * of the uploaded entries are deleted only after the rewrite succeeded: until then the claimed file
 * may still list them as pending.
 */
private fun prepareRetryFile(
    context: Context,
    retryFile: File,
    queue: UploadQueue,
    pending: List<Int>,
) {
    val rewritten = rewriteQueueFile(retryFile, queue.toRetryJson(pending).toString())
    if (!retryFile.setLastModified(System.currentTimeMillis())) {
        Log.w(TAG, "Retry queue file time could not be refreshed")
    }
    if (rewritten) {
        val pendingStaged = pending.mapNotNull { queue.entries[it].staged }.toSet()
        val uploadedStaged =
            queue.entries
                .filter { it.uploaded }
                .mapNotNull { it.staged }
                .toSet()
        deleteStagedFiles(shareStagingDir(context), uploadedStaged - pendingStaged)
    }
}

/**
 * Cancels the upload work [workId] if, and only if, it is upload work (tag [UPLOAD_WORK_TAG]) that is
 * running right now. The state is read bounded by [CANCEL_WORK_TIMEOUT_MS]; the cancel is awaited
 * (also bounded) so it is persisted before the caller finishes. Unknown, finished, enqueued,
 * non-upload work and any failure are left alone (failures are logged by type only). This is the
 * only place that cancels upload work (guarded by `NoUploadWorkCancelOutsideCancelPathRegressionTest`).
 *
 * @return true if a cancel was issued.
 */
internal suspend fun cancelRunningUpload(
    workManager: WorkManager,
    workId: UUID,
): Boolean =
    try {
        val info = withTimeoutOrNull(CANCEL_WORK_TIMEOUT_MS) { workManager.getWorkInfoByIdFlow(workId).first() }
        if (info != null && UPLOAD_WORK_TAG in info.tags && info.state == WorkInfo.State.RUNNING) {
            withTimeoutOrNull(CANCEL_WORK_TIMEOUT_MS) { workManager.cancelWorkById(workId).await() }
            true
        } else {
            false
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Upload could not be cancelled: ${e.javaClass.simpleName}")
        false
    }

/**
 * Wait between the snapshot and the finalization of the start-up recovery: longer than the
 * bounded bookkeeping (5 s) plus [CANCEL_FINALIZE_TIMEOUT_MS] of a worker of this process that was
 * cancelled just before the snapshot, so no worker write can recreate a claimed queue file.
 */
internal const val CANCEL_RECOVERY_GRACE_MS = 30_000L

/**
 * Finalizes cancelled uploads that could not finalize themselves (the cancel reached the work between
 * two runs, or the process died first), once per process at start-up (design D5):
 * 1. one snapshot: the upload / retry queue files (names accepted by `QueueFiles.kt`, both parent
 *    directories) and, per file, the work infos carrying its [queueFileTag] (bounded by
 *    [CANCEL_WORK_TIMEOUT_MS]);
 * 2. a file is a candidate only if it has work and **all** of it is `CANCELLED`. Unknown state,
 *    timeout, no work at all (legacy work without the tag, or pruned by WorkManager) and any unfinished
 *    or otherwise finished work leave the file untouched (fail-closed);
 * 3. after [graceMs], each candidate that still exists is parsed and finalized by
 *    [finalizeCancelledQueue] (the atomic claim makes this exactly-once even against a worker that
 *    finalizes concurrently). An unreadable candidate stays untouched.
 *
 * @return the retry files created.
 */
internal suspend fun recoverCancelledUploads(
    context: Context,
    workManager: WorkManager,
    history: UploadHistoryRepository,
    graceMs: Long = CANCEL_RECOVERY_GRACE_MS,
): List<File> {
    val candidates = listQueueFiles(context).filter { hasOnlyCancelledWork(workManager, it.name) }
    if (candidates.isEmpty()) return emptyList()
    delay(graceMs)
    return candidates.filter { it.exists() }.mapNotNull { file ->
        val queue = parseQueue(file) ?: return@mapNotNull null
        (finalizeCancelledQueue(context, file, queue, emptySet(), history) as? CancelFinalization.Resumable)?.retryFile
    }
}

/** Upload and retry queue files of both queue directories (the current one and the legacy `filesDir`). */
private fun listQueueFiles(context: Context): List<File> =
    listOf(context.noBackupFilesDir, context.filesDir)
        .distinct()
        .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
        .filter { it.isFile && isWorkerQueueFile(context, it) }

/** True only if the work of [queueFileName] is known, non-empty and entirely `CANCELLED`. */
private suspend fun hasOnlyCancelledWork(
    workManager: WorkManager,
    queueFileName: String,
): Boolean =
    try {
        val infos =
            withTimeoutOrNull(CANCEL_WORK_TIMEOUT_MS) {
                workManager.getWorkInfosByTagFlow(queueFileTag(queueFileName)).first()
            }
        !infos.isNullOrEmpty() && infos.all { it.state == WorkInfo.State.CANCELLED }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Work state of a queue unknown: ${e.javaClass.simpleName}")
        false
    }
