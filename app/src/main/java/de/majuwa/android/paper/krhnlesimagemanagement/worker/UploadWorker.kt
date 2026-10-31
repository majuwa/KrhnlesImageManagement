package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalDigest
import de.majuwa.android.paper.krhnlesimagemanagement.data.NoVerifier
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemoteFile
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadResponse
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadTarget
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadVerifier
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.Verification
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.toUploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.verifierFor
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.util.DigestCountingInputStream
import de.majuwa.android.paper.krhnlesimagemanagement.util.MediaFileState
import de.majuwa.android.paper.krhnlesimagemanagement.util.isAllowedPhotoUri
import de.majuwa.android.paper.krhnlesimagemanagement.util.mediaFileStateOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private const val UPLOAD_WORKER_TAG = "UploadWorker"

class UploadWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val credentialRepository: CredentialRepository,
    private val clientFactory: (WebDavConfig) -> WebDavClient,
    private val uploadedPhotos: UploadedPhotosRepositoryContract = UploadedPhotosStore(context.applicationContext),
    private val uploadHistory: UploadHistoryRepository = UploadHistoryStore(context.applicationContext),
    private val isActiveNetworkMetered: () -> Boolean = { isActiveNetworkMetered(context.applicationContext) },
    private val verifiedUploads: VerifiedUploadsRepositoryContract = VerifiedUploadsStore(context.applicationContext),
    /**
     * Test seam for [userCancelled]; null = the real rule (stopped by the app through WorkManager's
     * cancel API, `STOP_REASON_CANCELLED_BY_APP`).
     */
    private val userCancelledOverride: (() -> Boolean)? = null,
) : CoroutineWorker(context, params) {
    /** Used by WorkManager's default factory. */
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        CredentialStore(context.applicationContext),
        ::WebDavClient,
    )

    /**
     * [retainedStaged]: staged names referenced by the retry file written for this run.
     * [keepQueue]: the run was interrupted (foreground service refused); the queue file (with the
     * progress of this run) and its staged copies stay for the retried run.
     */
    private data class UploadOutcome(
        val result: Result,
        val retainedStaged: Set<String> = emptySet(),
        val keepQueue: Boolean = false,
    )

    /**
     * [queue]: the queue with every entry uploaded so far marked (also persisted in the queue file).
     * [interrupted]: the loop stopped early because the foreground service was refused.
     */
    private data class UploadRun(
        val queue: UploadQueue,
        val failures: Set<UploadFailure>,
        val interrupted: Boolean,
    )

    /** Queue with every entry uploaded so far; what a user cancel turns into the retry file. */
    @Volatile
    private var liveQueue: UploadQueue? = null

    /** Failure causes seen so far in this run (for the reasons of a cancelled notification). */
    private val liveFailures = mutableSetOf<UploadFailure>()

    /**
     * Set once the run reports its result (or gives up): from then on a cancel no longer
     * finalizes, the terminal report is complete and never split.
     */
    @Volatile
    private var terminal = false

    companion object {
        const val KEY_QUEUE_FILE = "queue_file"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_FAILED_COUNT = "failed_count"

        private fun isActiveNetworkMetered(context: Context): Boolean =
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

        /** Foreground / progress notification only; result notifications use [RESULT_NOTIFICATION_ID]. */
        const val NOTIFICATION_ID = 1001

        /**
         * Id of every result notification (success, partial, failure). Batches are told apart by the
         * notification *tag* (the input queue file name), so ids never collide and never equal
         * [NOTIFICATION_ID].
         */
        const val RESULT_NOTIFICATION_ID = 1002
        val NOTIFICATION_ICON = R.drawable.ic_notification_upload

        /**
         * Foreground-service refusals of one queue (persisted as `interruptions` in the queue file;
         * Wi-Fi-only wake-ups and constraint stops do not count) after which a refused foreground
         * service no longer retries: the run stops with the failure notification and a retry file
         * for the entries not uploaded yet.
         */
        const val MAX_UPLOAD_RUN_ATTEMPTS = 5

        /**
         * Hard backstop for a counter that cannot be persisted: a refused foreground service gives
         * up once WorkManager's `runAttemptCount` reaches this many starts (metered Wi-Fi-only
         * wake-ups also count there, hence the generous factor), so a failing queue file write can
         * never retry forever.
         */
        const val MAX_UPLOAD_RUN_ATTEMPTS_BACKSTOP = 10 * MAX_UPLOAD_RUN_ATTEMPTS
    }

    override suspend fun doWork(): Result {
        val queueFile =
            validQueueFile(applicationContext, inputData.getString(KEY_QUEUE_FILE))
                ?: return Result.failure()
        val queue =
            parseQueue(queueFile) ?: run {
                deleteQueueFile(applicationContext, queueFile)
                return Result.failure()
            }
        liveQueue = queue
        return try {
            runUpload(queueFile, queue)
        } catch (e: CancellationException) {
            // Only a cancel by the app is the user's: it is terminal in WorkManager, so the queue
            // must be finalized here. Every other stop (constraints, foreground-service time limit,
            // preemption, quota, unknown) is re-run by WorkManager: keep the queue as it is.
            if (!terminal && userCancelled()) finalizeOnCancel(queueFile)
            throw e
        }
    }

    /** True if WorkManager stopped this work because the app cancelled it (the user's Cancel). */
    private fun userCancelled(): Boolean =
        userCancelledOverride?.invoke() ?: (isStopped && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP)

    private suspend fun finalizeOnCancel(queueFile: File) {
        val queue = liveQueue ?: return
        // A chunked-upload session of the in-flight item is discarded here (best effort, before
        // the claim) once the nextcloud-chunked-upload change exists; see docs/architecture.md.
        finalizeCancelledQueue(applicationContext, queueFile, queue, liveFailures.toSet(), uploadHistory)
    }

    private suspend fun runUpload(
        queueFile: File,
        queue: UploadQueue,
    ): Result {
        // The Wi-Fi-only setting may have been switched on after this work was enqueued (its
        // constraint is fixed at enqueue time). Back off via WorkManager until an unmetered network
        // is active; the queue file stays untouched meanwhile.
        if (readWifiOnly(credentialRepository.wifiOnly) && isActiveNetworkMetered()) {
            return Result.retry()
        }

        val config = credentialRepository.webDavConfig.first()

        if (!config.isValid) {
            terminalReport {
                deleteQueueFile(applicationContext, queueFile)
                applicationContext.createUploadNotificationChannel()
                applicationContext.notifyIfPermitted(
                    applicationContext.buildFailureNotification(
                        applicationContext.getString(
                            R.string.notification_create_folder_failed,
                            folderCreationErrorMessage(applicationContext.resources, FolderCreationError.NotConfigured),
                        ),
                    ),
                    queueFile.name,
                )
            }
            return Result.failure()
        }

        val outcome = executeUpload(config = config, queue = queue, queueFile = queueFile)
        // Staged copies stay until the queue is final; those of a new retry file survive. An
        // interrupted run keeps everything for its retry.
        if (!outcome.keepQueue) deleteQueueFile(applicationContext, queueFile, keep = outcome.retainedStaged)
        return outcome.result
    }

    /** Runs a terminal report (result, history, notification) to its end even if the work is cancelled meanwhile. */
    private suspend fun <T> terminalReport(block: suspend () -> T): T =
        withContext(NonCancellable) {
            terminal = true
            block()
        }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.createUploadNotificationChannel()
        return buildForegroundInfo(0, 0)
    }

    /**
     * Uploads the entries of [queue] not uploaded by an earlier run. After each successful entry the
     * queue file is rewritten atomically with an `uploaded` marker and the photo is marked uploaded,
     * so an interrupted run never re-uploads finished entries. Result, history and notification
     * cover the whole batch, including entries uploaded by earlier runs.
     */
    private suspend fun executeUpload(
        config: WebDavConfig,
        queue: UploadQueue,
        queueFile: File,
    ): UploadOutcome {
        val notificationTag = queueFile.name
        val total = queue.entries.size
        val pending = queue.pendingIndices
        val remoteFolderPaths = List(total) { "${config.uploadPathPrefix}${queue.folderOf(it)}" }
        val client = clientFactory(config)

        applicationContext.createUploadNotificationChannel()
        // A refused foreground service (time budget used up, background start) retries the run
        // later; the queue stays.
        if (!setForegroundOrFalse(buildForegroundInfo(total - pending.size, total))) {
            return interrupted(UploadRun(queue, emptySet(), interrupted = true), queueFile, notificationTag)
        }

        // Nothing left (an earlier run finished every entry): no folder request, only the result.
        if (pending.isNotEmpty() || total == 0) {
            val folderPaths = pending.map { remoteFolderPaths[it] }
            val folderError = createRemoteFolders(client, queue, config.uploadPathPrefix, folderPaths)
            if (folderError != null) {
                terminalReport {
                    uploadHistory.recordRun(queue.historyLabel, total, pending.size)
                    val message =
                        applicationContext.getString(
                            R.string.notification_create_folder_failed,
                            folderCreationErrorMessage(applicationContext.resources, folderError),
                        )
                    applicationContext.notifyIfPermitted(
                        applicationContext.buildFailureNotification(message),
                        notificationTag,
                    )
                }
                return UploadOutcome(Result.failure())
            }
        }

        // Read once per run; an unreadable setting means no extra downloads.
        val verifier = verifierFor(config, readVerifyByDownload(credentialRepository.verifyByDownload), client)
        val upload = UploadContext(client, verifier, config, applicationContext.hasMediaLocationAccess())
        val run = uploadFiles(upload, queue, remoteFolderPaths, queueFile)
        return if (run.interrupted) interrupted(run, queueFile, notificationTag) else finish(run, notificationTag)
    }

    /**
     * An interrupted run is retried (queue and its progress kept) until [MAX_UPLOAD_RUN_ATTEMPTS]
     * refusals are used up; then it stops like a finished run with the remaining entries failed.
     * The refusal is counted in the queue file; if that write fails the run is retried only while
     * `runAttemptCount` is below [MAX_UPLOAD_RUN_ATTEMPTS], and never from [MAX_UPLOAD_RUN_ATTEMPTS_BACKSTOP].
     */
    private suspend fun interrupted(
        run: UploadRun,
        queueFile: File,
        notificationTag: String,
    ): UploadOutcome {
        // A user cancel is not an interruption: it is finalized by the caller of doWork, never
        // counted and never retried.
        if (userCancelled()) throw CancellationException("Upload cancelled by the user")
        if (run.queue.interruptions + 1 < MAX_UPLOAD_RUN_ATTEMPTS &&
            runAttemptCount < MAX_UPLOAD_RUN_ATTEMPTS_BACKSTOP
        ) {
            val saved =
                rewriteQueueFile(
                    queueFile,
                    run.queue
                        .withInterruption()
                        .toProgressJson()
                        .toString(),
                )
            // An unsaved counter never grows: once WorkManager itself has started the work
            // MAX_UPLOAD_RUN_ATTEMPTS times, stop instead of retrying with a stuck counter.
            if (saved || runAttemptCount < MAX_UPLOAD_RUN_ATTEMPTS) {
                return UploadOutcome(Result.retry(), keepQueue = true)
            }
        }
        Log.w(UPLOAD_WORKER_TAG, "Upload interrupted too often; giving up after $MAX_UPLOAD_RUN_ATTEMPTS interruptions")
        return finish(run, notificationTag, gaveUp = true)
    }

    /** Records and reports the whole batch; entries not uploaded go to a new retry file. */
    private suspend fun finish(
        run: UploadRun,
        notificationTag: String,
        gaveUp: Boolean = false,
    ): UploadOutcome =
        terminalReport {
            val queue = run.queue
            val total = queue.entries.size
            val failedIndices = queue.pendingIndices
            val failed = failedIndices.size
            val uploaded = total - failed
            uploadHistory.recordRun(queue.historyLabel, total, failed)

            val retryQueueFilePath =
                if (failedIndices.isNotEmpty()) writeRetryQueueFile(queue, failedIndices)?.absolutePath else null
            val retainedStaged =
                if (retryQueueFilePath != null) {
                    failedIndices.mapNotNull { queue.entries[it].staged }.toSet()
                } else {
                    emptySet()
                }
            val resources = applicationContext.resources
            val reasons =
                listOfNotNull(
                    uploadFailureMessage(resources, run.failures),
                    resources.getString(R.string.upload_failure_interrupted).takeIf { gaveUp },
                )
            val summary =
                CompletionSummary(
                    uploaded,
                    failed,
                    retryQueueFilePath,
                    joinNotificationTexts(resources, reasons),
                    gaveUp,
                )
            applicationContext.notifyIfPermitted(
                applicationContext.buildCompletionNotification(summary, notificationTag),
                notificationTag,
            )

            val data = workDataOf(KEY_UPLOADED_COUNT to uploaded, KEY_FAILED_COUNT to failed)
            UploadOutcome(if (gaveUp) Result.failure(data) else Result.success(data), retainedStaged)
        }

    /**
     * Uploads every pending entry `i` of [initialQueue] into `remoteFolderPaths[i]`; one progress over
     * the whole batch. Each success is persisted in [queueFile] and marked uploaded at once. A refused
     * foreground update interrupts the run, except after the last entry (the batch is complete).
     */
    private suspend fun uploadFiles(
        upload: UploadContext,
        initialQueue: UploadQueue,
        remoteFolderPaths: List<String>,
        queueFile: File,
    ): UploadRun {
        var queue = initialQueue
        val pending = queue.pendingIndices
        val total = queue.entries.size
        val failures = mutableSetOf<UploadFailure>()
        var finished = total - pending.size

        for ((n, i) in pending.withIndex()) {
            val entry = queue.entries[i]
            val outcome = uploadPhoto(upload, remoteFolderPaths[i], entry)
            // A finished PUT is always booked, even if the user cancels right now: the item is
            // marked uploaded and never re-sent. The cancel surfaces at the next suspension point.
            queue = withContext(NonCancellable) { bookOutcome(upload, queue, i, outcome, queueFile) }
            outcome.failure?.let { failures.add(it) }
            liveQueue = queue
            liveFailures.addAll(failures)

            finished++
            setProgress(workDataOf(KEY_PROGRESS to finished, KEY_TOTAL to total))
            val foregroundKept = setForegroundOrFalse(buildForegroundInfo(finished, total))
            if (!foregroundKept && n < pending.lastIndex) return UploadRun(queue, failures, interrupted = true)
        }
        return UploadRun(queue, failures, interrupted = false)
    }

    /**
     * Books the finished `PUT` of entry [index]: on success the queue file is rewritten atomically
     * with the `uploaded` marker and the photo is marked at once, then the verified-upload record is
     * bound to the target. Runs non-cancellable; every DataStore write is bounded by
     * [BOOKKEEPING_TIMEOUT_MS] so the block always ends. Returns the queue with the entry marked.
     */
    private suspend fun bookOutcome(
        upload: UploadContext,
        queue: UploadQueue,
        index: Int,
        outcome: PhotoUploadOutcome,
        queueFile: File,
    ): UploadQueue {
        val entry = queue.entries[index]
        var booked = queue
        if (outcome.failure == null) {
            booked = queue.withUploaded(index)
            // A failed rewrite only means a re-run uploads this entry again.
            rewriteQueueFile(queueFile, booked.toProgressJson().toString())
            withTimeoutOrNull(BOOKKEEPING_TIMEOUT_MS) { uploadedPhotos.markUploaded(entry.id) }
        }
        withTimeoutOrNull(BOOKKEEPING_TIMEOUT_MS) { recordOutcome(upload, entry, outcome) }
        return booked
    }

    /**
     * Run-wide upload state: the client, the chosen verification strategy, the configuration of this
     * run and whether originals can be opened (`ACCESS_MEDIA_LOCATION`).
     */
    private class UploadContext(
        val client: WebDavClient,
        val verifier: UploadVerifier,
        val config: WebDavConfig,
        val originalAccess: Boolean,
    )

    /**
     * [failure] null = uploaded; [target] is where the `PUT` wrote (null: nothing was sent); [record]
     * is set only for a verified upload of an original.
     */
    private class PhotoUploadOutcome(
        val failure: UploadFailure?,
        val target: UploadTarget? = null,
        val record: VerifiedUpload? = null,
    )

    /**
     * Streams one photo or video (never buffered); the size is sent when known. A MediaStore photo
     * opened as the original is hashed while it streams and verified afterwards (design D2 / D4): a
     * mismatch or a check that cannot finish is a failed photo; no strategy, a photo that could not
     * be opened as the original (its location may be redacted), or a local file that is not provably
     * the one that was sent, is an unverified (but successful) upload. A staged (shared) entry is
     * never verified.
     */
    private suspend fun uploadPhoto(
        upload: UploadContext,
        remoteFolderPath: String,
        entry: UploadQueueEntry,
    ): PhotoUploadOutcome {
        val staged = entry.staged
        val uri = entry.uri.toUri()
        // Validated before any MediaStore query on the uri: only foreign content:// URIs, never
        // file:// or this app's own providers. A staged entry never touches its uri.
        if (staged == null && !isAllowedPhotoUri(uri, applicationContext.packageName)) {
            Log.w(UPLOAD_WORKER_TAG, "Photo URI rejected: unsupported scheme or own authority")
            return PhotoUploadOutcome(UploadFailure.Other)
        }
        val verifiable = staged == null && entry.id >= 0L
        // Read before the stream is opened: the metadata of the file that is about to be sent.
        val before = if (verifiable) applicationContext.contentResolver.mediaFileStateOf(uri) else null
        val source =
            applicationContext.openEntry(entry, uri, wantOriginal = verifiable && upload.originalAccess)
                ?: return PhotoUploadOutcome(UploadFailure.Other)
        val target = UploadTarget(upload.config.url, remoteFolderPath, entry.displayName)
        // The PUT replaces whatever is at the target: drop its records first, so a run that dies
        // during the PUT never leaves a record describing a file that was overwritten.
        verifiedUploads.applyUploadLogged(target)
        val original = (source as? UploadSource.Original)?.takeIf { verifiable }
        val (response, sent) = put(upload, remoteFolderPath, entry, source, hashed = original != null)
        val putFailure = response.exceptionOrNull()
        return when {
            putFailure != null -> PhotoUploadOutcome(putFailure.toUploadFailure(), target)
            original == null || sent == null -> PhotoUploadOutcome(null, target)
            else -> verifyUpload(upload, target, entry, original, sent, before, response.getOrThrow())
        }
    }

    /**
     * Streams [source] with one `PUT`; when [hashed], the sent bytes are hashed and counted on the
     * way (the returned stream). The length comes from the raw stream (a staged one reads it from
     * the file channel), so the digest wrapper is applied only afterwards.
     */
    private suspend fun put(
        upload: UploadContext,
        remoteFolderPath: String,
        entry: UploadQueueEntry,
        source: UploadSource,
        hashed: Boolean,
    ): Pair<kotlin.Result<UploadResponse>, DigestCountingInputStream?> {
        var counting: DigestCountingInputStream? = null
        val response =
            source.stream.use { stream ->
                val length = applicationContext.uploadLengthOf(stream, entry)
                val body = if (hashed) DigestCountingInputStream(stream).also { counting = it } else stream
                upload.client.uploadFile(
                    remoteFolderPath,
                    entry.displayName,
                    entry.mimeType,
                    body,
                    length,
                    requestServerHash = hashed && upload.verifier.requestsServerHash,
                )
            }
        return response to counting
    }

    /** Verifies the upload of an [original] (never of a stream whose location may be redacted). */
    private suspend fun verifyUpload(
        upload: UploadContext,
        target: UploadTarget,
        entry: UploadQueueEntry,
        original: UploadSource.Original,
        sent: DigestCountingInputStream,
        before: MediaFileState?,
        put: UploadResponse,
    ): PhotoUploadOutcome {
        val local = LocalDigest(sha256 = sent.sha256Hex(), sizeBytes = sent.bytesRead)
        // Not provably the file that was sent (metadata missing, or it changed / is stale): a good
        // backup that is never offered for removal. No download is spent on it.
        val provable = before != null && before.size == local.sizeBytes
        val verification =
            if (provable && upload.verifier !== NoVerifier) {
                upload.verifier.verify(RemoteFile(target.folder, target.fileName), local, put)
            } else {
                Verification.Unavailable
            }
        return when (verification) {
            Verification.Verified -> {
                PhotoUploadOutcome(
                    failure = null,
                    target = target,
                    record =
                        VerifiedUpload(
                            id = entry.id,
                            uri = original.mediaUri.toString(),
                            size = local.sizeBytes,
                            dateModified = checkNotNull(before).dateModified,
                            serverUrl = target.serverUrl,
                            folder = target.folder,
                            fileName = target.fileName,
                            verifiedAt = System.currentTimeMillis(),
                        ),
                )
            }

            Verification.Unavailable -> {
                PhotoUploadOutcome(null, target)
            }

            Verification.Mismatch, Verification.Inconclusive -> {
                PhotoUploadOutcome(UploadFailure.Verification, target)
            }
        }
    }

    /**
     * Binds the verified-upload records to what this `PUT` left on the server, in one edit: records
     * of any photo at the target are dropped; a verified upload is recorded only while the run's
     * server and account are still the configured ones; a successful but unverified upload and a
     * failed verification also forget the photo's older record (elsewhere). Nothing is sent → nothing.
     */
    private suspend fun recordOutcome(
        upload: UploadContext,
        entry: UploadQueueEntry,
        outcome: PhotoUploadOutcome,
    ) {
        val target = outcome.target ?: return
        val record = outcome.record?.takeIf { credentialRepository.isStillConfiguredAs(upload.config) }
        val notVerified = outcome.failure == null || outcome.failure == UploadFailure.Verification
        val forgetIds = if (record == null && notVerified && entry.id >= 0L) setOf(entry.id) else emptySet()
        verifiedUploads.applyUploadLogged(target, record, forgetIds)
    }

    /** Writes the retry file: only failed entries, each keeping its folder; common folder and label copied. */
    private fun writeRetryQueueFile(
        queue: UploadQueue,
        failedIndices: List<Int>,
    ): File? =
        try {
            val retryFile =
                File(queueFilesDir(applicationContext), "retry_queue_${UUID.randomUUID()}.json")
            retryFile.writeText(queue.toRetryJson(failedIndices).toString())
            retryFile
        } catch (e: Exception) {
            Log.w(UPLOAD_WORKER_TAG, "Failed to write retry queue file: ${e.javaClass.simpleName}")
            null
        }

    private fun buildForegroundInfo(
        current: Int,
        total: Int,
    ): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            buildProgressNotification(current, total),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    /** The progress notification of this work, with its Cancel action. */
    internal fun buildProgressNotification(
        current: Int,
        total: Int,
    ): Notification = applicationContext.buildProgressNotification(current, total, id)
}

/** The queue file named by [path] if it is an existing, validated worker queue file, else null. */
private fun validQueueFile(
    context: Context,
    path: String?,
): File? {
    val file = path?.let(::File)
    return when {
        file == null -> {
            null
        }

        !isWorkerQueueFile(context, file) -> {
            Log.w("UploadWorker", "Queue file has an unexpected name or location — ignoring.")
            null
        }

        !file.exists() -> {
            null
        }

        else -> {
            file
        }
    }
}

/**
 * Validates every effective folder of [queue] before any request, then creates each distinct
 * [remoteFolderPaths] entry level by level (each cumulative path at most once, 405 ok). Returns
 * why it failed (never raw exception text), or null on success.
 */
private suspend fun createRemoteFolders(
    client: WebDavClient,
    queue: UploadQueue,
    uploadPathPrefix: String,
    remoteFolderPaths: List<String>,
): FolderCreationError? =
    if (!queue.hasValidFolders()) {
        FolderCreationError.InvalidFolderName
    } else {
        // An empty queue still creates the common folder (or the base folder) as before.
        val paths = remoteFolderPaths.distinct().ifEmpty { listOf("$uploadPathPrefix${queue.folderName.orEmpty()}") }
        client.createDirectories(paths).exceptionOrNull()?.toFolderCreationError()
    }

/** Marks one uploaded entry; -1 marks entries that pre-date photo ids (uploaded, never marked). */
private suspend fun UploadedPhotosRepositoryContract.markUploaded(photoId: Long) {
    if (photoId < 0L) return
    runCatching { markAsUploaded(setOf(photoId)) }
        .onFailure { if (it is CancellationException) throw it }
        .onFailure { Log.w("UploadWorker", "Uploaded marker not saved: ${it.javaClass.simpleName}") }
}

/**
 * Size of the open [stream] of [entry]: a staged copy only from its own file (never from the
 * entry URI, whose share grant is gone), a provider entry from its descriptor. Any failure is
 * [WebDavClient.UNKNOWN_CONTENT_LENGTH] (chunked transfer).
 */
private fun Context.uploadLengthOf(
    stream: InputStream,
    entry: UploadQueueEntry,
): Long =
    runCatching {
        if (entry.staged != null) {
            (stream as? FileInputStream)?.channel?.size() ?: WebDavClient.UNKNOWN_CONTENT_LENGTH
        } else {
            contentResolver.contentLengthOf(entry.uri.toUri())
        }
    }.getOrDefault(WebDavClient.UNKNOWN_CONTENT_LENGTH)

/**
 * Opens [entry] for upload: a staged (shared) photo only from its copy (the share grant for its URI
 * is gone, it is never opened), any other one from [uri] (see [openForUpload]).
 */
private fun Context.openEntry(
    entry: UploadQueueEntry,
    uri: Uri,
    wantOriginal: Boolean,
): UploadSource? {
    val staged = entry.staged
    return if (staged != null) {
        openStagedFile(this, staged)?.let { UploadSource.Plain(it) }
    } else {
        openForUpload(uri, wantOriginal)
    }
}

/** Parses [queueFile]; null (logged by type only) when it is unreadable or malformed. */
internal fun parseQueue(queueFile: File): UploadQueue? =
    try {
        parseUploadQueue(queueFile.readText()).also {
            if (it == null) Log.w(UPLOAD_WORKER_TAG, "Malformed queue file: inconsistent folder format")
        }
    } catch (e: JSONException) {
        Log.w(UPLOAD_WORKER_TAG, "Malformed queue file: ${e.javaClass.simpleName}")
        null
    } catch (e: IOException) {
        Log.w(UPLOAD_WORKER_TAG, "Unreadable queue file: ${e.javaClass.simpleName}")
        null
    }

/**
 * Applies [VerifiedUploadsRepositoryContract.applyUpload]; a failed write is logged by type only and
 * only means the photo is not removable (or, for a forget, is kept until a later upload).
 */
private suspend fun VerifiedUploadsRepositoryContract.applyUploadLogged(
    target: UploadTarget,
    record: VerifiedUpload? = null,
    forgetIds: Set<Long> = emptySet(),
) {
    runCatching { applyUpload(target, record, forgetIds) }
        .onFailure { if (it is CancellationException) throw it }
        .onFailure { Log.w(UPLOAD_WORKER_TAG, "Verified records not updated: ${it.javaClass.simpleName}") }
}

/**
 * True only while [run]'s server URL and account are still the configured, valid ones (bounded
 * read; a timeout or failure is false): a logout or an account / server change during the run
 * never gets a record of this run.
 */
private suspend fun CredentialRepository.isStillConfiguredAs(run: WebDavConfig): Boolean {
    val current =
        runCatching { withTimeoutOrNull(CONFIG_RECHECK_TIMEOUT_MS) { webDavConfig.first() } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    return current != null && current.isValid && current.url == run.url && current.username == run.username
}

private const val CONFIG_RECHECK_TIMEOUT_MS = 3_000L

/** Bound of each DataStore write of the per-item bookkeeping that runs non-cancellable. */
private const val BOOKKEEPING_TIMEOUT_MS = 5_000L
