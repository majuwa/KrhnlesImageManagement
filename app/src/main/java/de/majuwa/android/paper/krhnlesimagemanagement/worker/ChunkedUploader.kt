package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.data.ChunkedLocations
import de.majuwa.android.paper.krhnlesimagemanagement.data.ChunkedUploadCreation
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadResponse
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.data.newTransferId
import de.majuwa.android.paper.krhnlesimagemanagement.util.DigestCountingInputStream
import de.majuwa.android.paper.krhnlesimagemanagement.util.MediaFileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "ChunkedUploader"

/** Attempts per file and run; each attempt resumes from the saved progress (design D6). */
internal const val MAX_CHUNKED_ATTEMPTS_PER_RUN = 3

/**
 * Restarts per file and run after a `MOVE` (all chunks confirmed) was answered 404 / 409 / 412
 * (design D5): one; a second such answer fails the file, so a server cannot make the app send the
 * whole file again and again.
 */
internal const val MAX_MOVE_LOST_RESTARTS_PER_RUN = 1

/** Waits before the second and the third attempt after a transient failure. */
internal val CHUNKED_RETRY_DELAYS_MS = longArrayOf(2_000L, 8_000L)

/** Bound of the best-effort `DELETE` of an abandoned upload folder inside a run (design D8). */
internal const val CHUNKED_ABORT_TIMEOUT_MS = 30_000L

/**
 * Bound of each `DELETE` on the user-cancel path: the work is already cancelled there and the
 * process may be cached or killed at any time (design D8).
 */
internal const val CHUNKED_CANCEL_ABORT_TIMEOUT_MS = 10_000L

private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_PRECONDITION_FAILED = 412
private const val HTTP_LOCKED = 423
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val HTTP_NOT_IMPLEMENTED = 501
private const val HTTP_INSUFFICIENT_STORAGE = 507
private const val HTTP_SERVER_ERROR_FIRST = 500
private const val HTTP_SERVER_ERROR_LAST = 599
private const val PERCENT = 100
private const val READ_BUFFER_BYTES = 64 * 1024

/** 404 / 409 / 412 on a chunk `PUT` or the `MOVE`: the server lost the upload (design D5). */
private val LOST_UPLOAD_CODES = setOf(HTTP_NOT_FOUND, HTTP_CONFLICT, HTTP_PRECONDITION_FAILED)

/** SHA-256 of no input: the prefix hash of a transfer without confirmed chunks. */
internal val EMPTY_SHA256: String = MessageDigest.getInstance("SHA-256").digest().toHexString()

/**
 * Test seam of the chunked upload (design D12), bundled so `UploadWorker`'s constructor keeps its
 * size: the [policy] (threshold, chunk size), the [sleep] between attempts and the bounds of the
 * abort `DELETE` inside a run ([abortTimeoutMs]) and on the user-cancel path ([cancelAbortTimeoutMs]).
 * The defaults are the production values.
 */
internal class ChunkedUploadSettings(
    val policy: ChunkedUploadPolicy = ChunkedUploadPolicy(),
    val sleep: suspend (Long) -> Unit = { delay(it) },
    val abortTimeoutMs: Long = CHUNKED_ABORT_TIMEOUT_MS,
    val cancelAbortTimeoutMs: Long = CHUNKED_CANCEL_ABORT_TIMEOUT_MS,
)

/**
 * An opened upload source with the metadata read just before it was opened ([before], verifiable
 * entries only). [original] is the source itself when it is a verifiable original (as opened by
 * `openForUpload`), else null.
 */
internal class OpenedEntry(
    val source: UploadSource,
    val before: MediaFileState?,
    val original: UploadSource.Original?,
)

/**
 * What one chunked upload needs to know about its file. [requestServerHash]: the run's verifier
 * asks for a server hash; it is sent on the `MOVE` only for an original source.
 */
internal class ChunkedRequest(
    val folder: String,
    val fileName: String,
    val locations: ChunkedLocations,
    val requestServerHash: Boolean,
)

/** The worker side of a chunked upload. */
internal class ChunkedCallbacks(
    /** Opens the file again (every attempt reads it from the start); null if it cannot be opened. */
    val open: suspend () -> OpenedEntry?,
    /** Size of the freshly opened, unwrapped stream; -1 = unknown. */
    val lengthOf: (InputStream) -> Long,
    /** Runs before the first request of an attempt (drops the verified records at the target). */
    val beginAttempt: suspend () -> Unit,
    /** Saves the progress in the queue file (null = no transfer open for this entry). */
    val persist: (ChunkedTransferState?) -> Unit,
    /**
     * A chunk (not the last) was confirmed: update the notification with the file's percentage.
     * False = the foreground service was refused.
     */
    val chunkConfirmed: suspend (Int) -> Boolean,
)

/** How a chunked upload of one file ended. */
internal sealed interface ChunkedResult {
    /** Assembled on the server; [sent] hashed the whole file, [opened] is the source of the last attempt. */
    class Uploaded(
        val response: UploadResponse,
        val sent: DigestCountingInputStream,
        val opened: OpenedEntry,
    ) : ChunkedResult

    /**
     * The run ends here with the progress ([state]) kept for the next run: the foreground service
     * was refused after a chunk, or the last attempt of this run failed transiently (design D6, e.g.
     * a network outage). The worker treats both as an interruption.
     */
    class Interrupted(
        val state: ChunkedTransferState?,
    ) : ChunkedResult

    /** The file failed in this run; its transfer was removed and the progress cleared. */
    class Failed(
        val cause: Throwable,
    ) : ChunkedResult

    /** The uploads endpoint refused the transfer (design D10): single `PUT`s for the rest of the run. */
    data object Unavailable : ChunkedResult
}

private sealed interface Attempt {
    class Done(
        val result: ChunkedResult.Uploaded,
    ) : Attempt

    data object Restart : Attempt

    data object Interrupted : Attempt

    data object Unavailable : Attempt

    class Transient(
        val cause: Throwable,
    ) : Attempt

    class Fatal(
        val cause: Throwable,
    ) : Attempt
}

/**
 * Uploads one file with Nextcloud chunked upload v2 (design D5 / D6): `MKCOL`, numbered chunk
 * `PUT`s, one `MOVE`. The progress ([ChunkedTransferState]) is saved before the `MKCOL` of a new
 * transfer and after every chunk; a later attempt (in this run or another) re-reads the confirmed
 * prefix on the device and continues after it. It never creates a verified record: the caller
 * verifies a [ChunkedResult.Uploaded] with the unchanged verification path.
 *
 * One instance per file; [saved] is the progress found in the queue file. A cancellation is never
 * caught: a stop by the system keeps the saved progress, a user cancel is cleaned up by the worker.
 */
internal class ChunkedUploader(
    private val client: WebDavClient,
    private val settings: ChunkedUploadSettings,
    private val request: ChunkedRequest,
    private val callbacks: ChunkedCallbacks,
    saved: ChunkedTransferState?,
) {
    /** The transfer as saved in the queue file (null = none open). */
    var state: ChunkedTransferState? = saved
        private set

    /** Server, account and destination of this upload ([chunkedBinding]). */
    private val binding = chunkedBinding(request.locations.collectionUrl, request.locations.destinationUrl)

    /** Restarts after a lost upload at the `MOVE` in this run (see [MAX_MOVE_LOST_RESTARTS_PER_RUN]). */
    private var moveLostRestarts = 0

    /**
     * Runs up to [MAX_CHUNKED_ATTEMPTS_PER_RUN] attempts. When the last one fails transiently the
     * progress is kept and the run is interrupted (resumed by the next run) instead of discarding
     * every confirmed chunk; any other final failure removes the transfer.
     */
    suspend fun upload(): ChunkedResult {
        var failure: Throwable? = null
        for (number in 1..MAX_CHUNKED_ATTEMPTS_PER_RUN) {
            when (val attempt = attempt()) {
                is Attempt.Done -> {
                    return attempt.result
                }

                Attempt.Interrupted -> {
                    return ChunkedResult.Interrupted(state)
                }

                Attempt.Unavailable -> {
                    return ChunkedResult.Unavailable
                }

                Attempt.Restart -> {
                    Log.i(TAG, "Chunked transfer discarded; starting again")
                }

                is Attempt.Transient -> {
                    failure = attempt.cause
                    if (number == MAX_CHUNKED_ATTEMPTS_PER_RUN) return stalled(attempt.cause)
                    settings.sleep(CHUNKED_RETRY_DELAYS_MS[number - 1])
                }

                is Attempt.Fatal -> {
                    failure = attempt.cause
                    break
                }
            }
        }
        discard()
        return ChunkedResult.Failed(failure ?: IOException("Chunked upload did not finish"))
    }

    /** The last attempt of this run failed transiently: keep the progress, the next run resumes it. */
    private fun stalled(cause: Throwable): ChunkedResult {
        val reason = (cause as? WebDavHttpException)?.let { "HTTP ${it.code}" } ?: cause.javaClass.simpleName
        Log.w(
            TAG,
            "Chunked upload failed $MAX_CHUNKED_ATTEMPTS_PER_RUN times ($reason); progress kept for the next run",
        )
        return ChunkedResult.Interrupted(state)
    }

    /**
     * One attempt. A failed request is a `Result`, not an exception: an [IOException] here is a read
     * of the local file (transient), any other exception (e.g. a `SecurityException` from the provider
     * while the prefix is re-read) fails the file; a cancellation is never caught.
     */
    private suspend fun attempt(): Attempt =
        try {
            val opened = callbacks.open()
            if (opened == null) {
                Attempt.Fatal(FileNotFoundException("File could not be opened"))
            } else {
                opened.source.stream.use { stream -> transfer(opened, stream) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Attempt.Transient(e)
        } catch (e: Exception) {
            Attempt.Fatal(e)
        }

    /** One attempt (design D5): the length first, then the digest wrapper, then the requests. */
    private suspend fun transfer(
        opened: OpenedEntry,
        stream: InputStream,
    ): Attempt {
        val length = callbacks.lengthOf(stream)
        if (length <= 0L) return Attempt.Fatal(IOException("Size unknown"))
        val counting = DigestCountingInputStream(stream)
        callbacks.beginAttempt()
        val begun = begin(length, original = opened.original != null)
        return when {
            begun != null -> begun
            !prefixMatches(counting) -> discardAndRestart()
            else -> sendChunks(counting) ?: finishTransfer(opened, counting)
        }
    }

    /**
     * Keeps the saved transfer only if it is the same one (same binding, length, source kind and chunk size),
     * else starts a new one. Returns null when the transfer is ready for its chunks.
     */
    private suspend fun begin(
        length: Long,
        original: Boolean,
    ): Attempt? {
        val saved = state
        if (saved != null &&
            saved.binding == binding &&
            saved.isSameSource(length, original, settings.policy.chunkBytesFor(length))
        ) {
            return resume(saved)
        }
        // Removed on the server only if it has this binding (another server / account /
        // destination gets no request for the old id: it may not be ours).
        discard()
        return startNew(length, original)
    }

    private suspend fun resume(saved: ChunkedTransferState): Attempt? {
        val creation = client.createChunkedUpload(saved.transferId, request.folder, request.fileName, saved.totalBytes)
        val cause = creation.exceptionOrNull()
        if (cause != null) return creationFailed(cause, abort = true)
        if (creation.getOrNull() == ChunkedUploadCreation.Created) {
            // The server no longer had the folder (24 h cleanup, or the file was assembled): chunk 1 again.
            val reset = saved.copy(confirmedChunks = 0, prefixSha256 = EMPTY_SHA256)
            state = reset
            callbacks.persist(reset)
        }
        return null
    }

    private suspend fun startNew(
        length: Long,
        original: Boolean,
    ): Attempt? {
        val fresh =
            ChunkedTransferState(
                transferId = newTransferId(),
                chunkBytes = settings.policy.chunkBytesFor(length),
                totalBytes = length,
                confirmedChunks = 0,
                prefixSha256 = EMPTY_SHA256,
                original = original,
                binding = binding,
            )
        // Saved before the MKCOL: a stop, give-up or cancel right after it can still resume or abort it.
        state = fresh
        callbacks.persist(fresh)
        val creation = client.createChunkedUpload(fresh.transferId, request.folder, request.fileName, length)
        val cause = creation.exceptionOrNull()
        return when {
            cause != null -> creationFailed(cause, abort = false)

            creation.getOrNull() == ChunkedUploadCreation.Created -> null

            // A new random id cannot exist already: the uploads endpoint does not take MKCOL here.
            else -> unavailable(abort = false)
        }
    }

    /**
     * The `MKCOL` failed: transient → the next attempt (same id); 413 / 507 and non-HTTP errors →
     * the file fails; any other HTTP status → no usable uploads endpoint (design D10).
     */
    private suspend fun creationFailed(
        cause: Throwable,
        abort: Boolean,
    ): Attempt =
        when {
            cause.isTransientChunkFailure() -> Attempt.Transient(cause)
            cause is WebDavHttpException && !cause.isSizeOrStorageError() -> unavailable(abort)
            else -> Attempt.Fatal(cause)
        }

    private suspend fun unavailable(abort: Boolean): Attempt {
        if (abort) {
            discard()
        } else {
            state = null
            callbacks.persist(null)
        }
        return Attempt.Unavailable
    }

    /** Reads the confirmed prefix again on the device (nothing is sent) and compares its hash. */
    private suspend fun prefixMatches(counting: DigestCountingInputStream): Boolean {
        val current = checkNotNull(state)
        if (current.confirmedBytes == 0L) return true
        return withContext(Dispatchers.IO) {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var remaining = current.confirmedBytes
            while (remaining > 0L) {
                ensureActive()
                val read = counting.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            }
            remaining == 0L && counting.sha256Hex().equals(current.prefixSha256, ignoreCase = true)
        }
    }

    /** Sends the missing chunks; null = all are confirmed. */
    private suspend fun sendChunks(counting: DigestCountingInputStream): Attempt? {
        var current = checkNotNull(state)
        for (number in current.confirmedChunks + 1..current.chunkCount) {
            val chunkLength = minOf(current.chunkBytes, current.totalBytes - (number - 1) * current.chunkBytes)
            val failure =
                client
                    .uploadChunk(
                        current.transferId,
                        number,
                        request.folder,
                        request.fileName,
                        current.totalBytes,
                        counting,
                        chunkLength,
                    ).exceptionOrNull()
            if (failure != null) return failedRequest(failure)
            current = current.copy(confirmedChunks = number, prefixSha256 = counting.sha256Hex())
            state = current
            callbacks.persist(current)
            val percent = (current.confirmedBytes * PERCENT / current.totalBytes).toInt()
            if (number < current.chunkCount && !callbacks.chunkConfirmed(percent)) return Attempt.Interrupted
        }
        return null
    }

    private suspend fun finishTransfer(
        opened: OpenedEntry,
        counting: DigestCountingInputStream,
    ): Attempt {
        val current = checkNotNull(state)
        // More bytes than the transfer was started with: the file grew.
        val atEnd = withContext(Dispatchers.IO) { counting.read() == -1 }
        if (!atEnd) return discardAndRestart()
        val moved =
            client.finishChunkedUpload(
                current.transferId,
                request.folder,
                request.fileName,
                current.totalBytes,
                requestServerHash = request.requestServerHash && opened.original != null,
            )
        val failure = moved.exceptionOrNull()
        if (failure != null) return failedRequest(failure, atMove = true)
        // Assembled: the transfer is no longer open, so a cancel from now on never sends a DELETE.
        state = null
        callbacks.persist(null)
        return Attempt.Done(ChunkedResult.Uploaded(moved.getOrThrow(), counting, opened))
    }

    /**
     * A chunk `PUT` or the `MOVE` failed; 404 / 409 / 412 mean the server lost the upload. Lost at
     * the `MOVE` (every chunk confirmed), the file is sent again at most
     * [MAX_MOVE_LOST_RESTARTS_PER_RUN] time per run; after that it fails (the transfer is removed).
     */
    private suspend fun failedRequest(
        cause: Throwable,
        atMove: Boolean = false,
    ): Attempt =
        when {
            cause is WebDavHttpException && cause.code in LOST_UPLOAD_CODES -> {
                if (atMove && moveLostRestarts >= MAX_MOVE_LOST_RESTARTS_PER_RUN) {
                    Attempt.Fatal(cause)
                } else {
                    if (atMove) moveLostRestarts++
                    discardAndRestart()
                }
            }

            cause.isTransientChunkFailure() -> {
                Attempt.Transient(cause)
            }

            else -> {
                Attempt.Fatal(cause)
            }
        }

    private suspend fun discardAndRestart(): Attempt {
        discard()
        return Attempt.Restart
    }

    /**
     * Forgets the transfer's progress and removes it from the server (best effort, bounded), but
     * only if it has this upload's binding: a transfer of another server, account or destination
     * gets no request.
     */
    private suspend fun discard() {
        val current = state ?: return
        state = null
        if (current.binding == binding) client.abortQuietly(current.transferId, settings.abortTimeoutMs)
        callbacks.persist(null)
    }
}

/**
 * The saved transfer was started from a file of this [length] opened the same way ([original] or
 * not) with the chunk size the policy chooses for it ([chunkBytes]).
 */
private fun ChunkedTransferState.isSameSource(
    length: Long,
    original: Boolean,
    chunkBytes: Long,
): Boolean = totalBytes == length && this.original == original && this.chunkBytes == chunkBytes

private fun WebDavHttpException.isSizeOrStorageError(): Boolean =
    code == HTTP_PAYLOAD_TOO_LARGE || code == HTTP_INSUFFICIENT_STORAGE

/**
 * True for a failure worth another attempt (design D6): a network or read error (never a
 * cancellation), 408, 423, 429 or 5xx except 501 (no uploads endpoint) and 507 (the server is full).
 */
internal fun Throwable.isTransientChunkFailure(): Boolean =
    when {
        // A TLS / certificate failure (also wrapped) is never fixed by waiting: it must reach
        // toUploadFailure() as a final failure (a changed certificate has its own reason), not be retried.
        hasCertificateFailure() -> {
            false
        }

        this is WebDavHttpException -> {
            code == HTTP_REQUEST_TIMEOUT ||
                code == HTTP_LOCKED ||
                code == HTTP_TOO_MANY_REQUESTS ||
                (
                    code in HTTP_SERVER_ERROR_FIRST..HTTP_SERVER_ERROR_LAST && code != HTTP_NOT_IMPLEMENTED &&
                        code != HTTP_INSUFFICIENT_STORAGE
                )
        }

        this is IOException -> {
            true
        }

        else -> {
            false
        }
    }

private const val MAX_CAUSE_DEPTH = 10

/** Trust failures only; a plain `SSLException` (e.g. a connection reset during a read) stays transient. */
private fun Throwable.hasCertificateFailure(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any {
        it is CertificateException ||
            it is SSLHandshakeException ||
            it is SSLPeerUnverifiedException
    }

/**
 * Removes the upload folder of [transferId] on a best-effort basis (design D8): bounded by
 * [timeoutMs], not cancellable (a user cancel still sends it), never throwing. Failures are logged
 * by type or HTTP code only.
 */
internal suspend fun WebDavClient.abortQuietly(
    transferId: String,
    timeoutMs: Long,
) {
    withContext(NonCancellable) {
        val result = withTimeoutOrNull(timeoutMs) { abortChunkedUpload(transferId) }
        val failure = result?.exceptionOrNull()
        when {
            result == null -> Log.w(TAG, "Aborting a chunked upload timed out")
            failure is WebDavHttpException -> Log.w(TAG, "Aborting a chunked upload failed: HTTP ${failure.code}")
            failure != null -> Log.w(TAG, "Aborting a chunked upload failed: ${failure.javaClass.simpleName}")
        }
    }
}

/**
 * Aborts the saved [state] of the file [folder]/[fileName] if it belongs to the server, account and
 * destination of [client] (same binding); a transfer of another server or account gets no request.
 */
internal suspend fun WebDavClient.abortIfBound(
    state: ChunkedTransferState,
    folder: String,
    fileName: String,
    timeoutMs: Long,
) {
    val locations = chunkedLocations(folder, fileName) ?: return
    if (chunkedBinding(locations.collectionUrl, locations.destinationUrl) == state.binding) {
        abortQuietly(state.transferId, timeoutMs)
    }
}

/**
 * Aborts every chunked transfer saved in [queue] whose binding matches [client] (each bounded by
 * [timeoutMs]); `remoteFolderPaths[i]` is the folder of entry `i`. Used when the transfers will not be
 * resumed: a give-up, the end of a run, a user cancel (after the queue was finalized).
 */
internal suspend fun WebDavClient.abortOpenTransfers(
    queue: UploadQueue,
    remoteFolderPaths: List<String>,
    timeoutMs: Long,
) {
    queue.entries.forEachIndexed { i, entry ->
        val state = entry.chunked
        if (state != null && !entry.uploaded) abortIfBound(state, remoteFolderPaths[i], entry.displayName, timeoutMs)
    }
}
