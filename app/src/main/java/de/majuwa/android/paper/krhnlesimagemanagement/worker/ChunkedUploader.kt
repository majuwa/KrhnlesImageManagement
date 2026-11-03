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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

private const val TAG = "ChunkedUploader"

/** Attempts per file and run; each attempt resumes from the saved progress. */
internal const val MAX_CHUNKED_ATTEMPTS_PER_RUN = 3

/** Waits before the second and the third attempt. */
internal val CHUNKED_RETRY_DELAYS_MS = longArrayOf(2_000L, 8_000L)

/** Bound of the best-effort `DELETE` of an abandoned upload folder. */
internal const val CHUNKED_ABORT_TIMEOUT_MS = 30_000L

private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_PRECONDITION_FAILED = 412
private const val HTTP_LOCKED = 423
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val HTTP_INSUFFICIENT_STORAGE = 507
private const val HTTP_SERVER_ERROR_FIRST = 500
private const val HTTP_SERVER_ERROR_LAST = 599
private const val PERCENT = 100
private const val READ_BUFFER_BYTES = 64 * 1024

/** SHA-256 of no input: the prefix hash of a transfer without confirmed chunks. */
private val EMPTY_SHA256: String = MessageDigest.getInstance("SHA-256").digest().toHexString()

/**
 * Test seams of the chunked upload: the [policy] (threshold, chunk size), the [sleep] between
 * attempts and the bound of the abort `DELETE`. Defaults are the production values.
 */
internal class ChunkedUploadSettings(
    val policy: ChunkedUploadPolicy = ChunkedUploadPolicy(),
    val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    val abortTimeoutMs: Long = CHUNKED_ABORT_TIMEOUT_MS,
)

/**
 * An opened upload source with the metadata read just before it was opened. [original] is the
 * source itself when it is a verifiable original (as opened by `openForUpload`), else null.
 */
internal class OpenedEntry(
    val source: UploadSource,
    val before: MediaFileState?,
    val original: UploadSource.Original?,
)

/** What one chunked upload needs to know about its file; [requestServerHash] only counts for originals. */
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
    /** Runs before the first request of an attempt (drops verified records at the target). */
    val beginAttempt: suspend () -> Unit,
    /** The transfer that is open on the server now (null = none); lets a user cancel remove it. */
    val track: (ChunkedTransferState?) -> Unit,
    /** Saves the progress in the queue file (null = no progress); also [track]s it. */
    val persist: suspend (ChunkedTransferState?) -> Unit,
    /**
     * A chunk (not the last) was confirmed: update the notification with the file's [Int] percent.
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

    /** The foreground service was refused after a chunk; the progress ([state]) is kept. */
    class Interrupted(
        val state: ChunkedTransferState?,
    ) : ChunkedResult

    /** The file failed in this run; its transfer was removed and the progress cleared. */
    class Failed(
        val cause: Throwable,
    ) : ChunkedResult

    /** The uploads endpoint refused a new transfer: use a single `PUT` for the rest of the run. */
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

private sealed interface Begin {
    /** [resumed]: the server still had the saved upload folder, so the next request may find it lost. */
    class Ready(
        val resumed: Boolean,
    ) : Begin

    class Stop(
        val attempt: Attempt,
    ) : Begin
}

/**
 * Uploads one file with Nextcloud chunked upload v2 (design D5 / D6): `MKCOL`, numbered chunk
 * `PUT`s, one `MOVE`. The progress ([ChunkedTransferState]) is saved after every chunk, a later
 * attempt (in this run or another) re-reads the confirmed prefix on the device and continues after
 * it. It never creates a verified record: the caller verifies an [ChunkedResult.Uploaded] with the
 * unchanged verification path.
 *
 * One instance per file; [saved] is the progress found in the queue file.
 */
internal class ChunkedUploader(
    private val client: WebDavClient,
    private val settings: ChunkedUploadSettings,
    private val request: ChunkedRequest,
    private val callbacks: ChunkedCallbacks,
    saved: ChunkedTransferState?,
) {
    /** The transfer as the server knows it (null = none open). */
    var state: ChunkedTransferState? = saved
        private set

    private var afterResume = false

    suspend fun upload(): ChunkedResult {
        var failure: Throwable? = null
        for (number in 1..MAX_CHUNKED_ATTEMPTS_PER_RUN) {
            val attempt = attempt()
            if (attempt is Attempt.Done) return attempt.result
            if (attempt === Attempt.Interrupted) return ChunkedResult.Interrupted(state)
            if (attempt === Attempt.Unavailable) return ChunkedResult.Unavailable
            failure = (attempt as? Attempt.Transient)?.cause ?: (attempt as? Attempt.Fatal)?.cause
            if (attempt is Attempt.Fatal) break
            if (attempt is Attempt.Transient && number < MAX_CHUNKED_ATTEMPTS_PER_RUN) {
                settings.sleep(CHUNKED_RETRY_DELAYS_MS[number - 1])
            }
        }
        discard()
        return ChunkedResult.Failed(failure ?: IOException("Chunked upload did not finish"))
    }

    private suspend fun attempt(): Attempt {
        val opened = callbacks.open() ?: return Attempt.Fatal(IOException("File could not be opened"))
        return try {
            opened.source.stream.use { stream -> transfer(opened, stream) }
        } catch (e: IOException) {
            // A read of the local file failed.
            Attempt.Transient(e)
        }
    }

    private suspend fun transfer(
        opened: OpenedEntry,
        stream: InputStream,
    ): Attempt {
        val length = callbacks.lengthOf(stream)
        if (length <= 0L) return Attempt.Fatal(IOException("Size unknown"))
        val counting = DigestCountingInputStream(stream)
        callbacks.beginAttempt()
        return when (val begin = begin(length, original = opened.original != null)) {
            is Begin.Stop -> begin.attempt
            is Begin.Ready -> run(opened, counting, begin.resumed)
        }
    }

    private suspend fun run(
        opened: OpenedEntry,
        counting: DigestCountingInputStream,
        resumed: Boolean,
    ): Attempt {
        afterResume = resumed
        return if (prefixMatches(counting)) {
            sendChunks(counting) ?: finishTransfer(opened, counting)
        } else {
            discardAndRestart()
        }
    }

    /** Keeps the saved transfer only if it is the same one (design D5.2), else starts a new one. */
    private suspend fun begin(
        length: Long,
        original: Boolean,
    ): Begin {
        val binding = chunkedBinding(request.locations.collectionUrl, request.locations.destinationUrl)
        val saved = state
        if (saved != null && saved.binding == binding && saved.totalBytes == length && saved.original == original) {
            return resume(saved)
        }
        if (saved != null) {
            // Another server / account / destination: send nothing for the old id (it may not be ours there).
            if (saved.binding == binding) client.abortQuietly(saved.transferId, settings.abortTimeoutMs)
            clear()
        }
        return startNew(length, original, binding)
    }

    private suspend fun resume(saved: ChunkedTransferState): Begin {
        callbacks.track(saved)
        val creation =
            client.createChunkedUpload(saved.transferId, request.folder, request.fileName, saved.totalBytes)
        val cause = creation.exceptionOrNull()
        return when {
            cause != null -> {
                Begin.Stop(classify(cause))
            }

            creation.getOrThrow() == ChunkedUploadCreation.AlreadyExists -> {
                Begin.Ready(resumed = true)
            }

            else -> {
                // Created: the server dropped the folder (24 h cleanup, or the file was assembled).
                val reset = saved.copy(confirmedChunks = 0, prefixSha256 = EMPTY_SHA256)
                state = reset
                callbacks.persist(reset)
                Begin.Ready(resumed = false)
            }
        }
    }

    private suspend fun startNew(
        length: Long,
        original: Boolean,
        binding: String,
    ): Begin {
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
        callbacks.track(fresh)
        val creation = client.createChunkedUpload(fresh.transferId, request.folder, request.fileName, length)
        val cause = creation.exceptionOrNull()
        val usable = cause == null && creation.getOrThrow() == ChunkedUploadCreation.Created
        return when {
            usable -> {
                state = fresh
                callbacks.persist(fresh)
                Begin.Ready(resumed = false)
            }

            // The uploads endpoint is not usable here (a new id can not already exist, 404 / 403 / 501 ...).
            cause == null || (cause is WebDavHttpException && !cause.isSizeOrStorageError()) -> {
                callbacks.track(null)
                Begin.Stop(Attempt.Unavailable)
            }

            else -> {
                callbacks.track(null)
                Begin.Stop(classify(cause))
            }
        }
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
            afterResume = false
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
        if (failure != null) return failedRequest(failure)
        clear()
        return Attempt.Done(ChunkedResult.Uploaded(moved.getOrThrow(), counting, opened))
    }

    /**
     * A chunk `PUT` or the `MOVE` failed. The first request after a resume answering 404 / 409 / 412
     * means the server lost the upload (for example its v2 session): discard and start again.
     */
    private suspend fun failedRequest(cause: Throwable): Attempt =
        if (afterResume && cause is WebDavHttpException && cause.code in LOST_UPLOAD_CODES) {
            discardAndRestart()
        } else {
            classify(cause)
        }

    private fun classify(cause: Throwable): Attempt =
        if (cause.isTransientChunkFailure()) Attempt.Transient(cause) else Attempt.Fatal(cause)

    private suspend fun discardAndRestart(): Attempt {
        discard()
        return Attempt.Restart
    }

    /** Removes the transfer from the server (best effort) and forgets its progress. */
    private suspend fun discard() {
        val current = state ?: return
        clear()
        client.abortQuietly(current.transferId, settings.abortTimeoutMs)
        callbacks.persist(null)
    }

    private fun clear() {
        state = null
        callbacks.track(null)
    }

    private companion object {
        val LOST_UPLOAD_CODES = setOf(HTTP_NOT_FOUND, HTTP_CONFLICT, HTTP_PRECONDITION_FAILED)
    }
}

private fun WebDavHttpException.isSizeOrStorageError(): Boolean =
    code == HTTP_PAYLOAD_TOO_LARGE || code == HTTP_INSUFFICIENT_STORAGE

/**
 * True for a failure worth another attempt: a network or read error, 408, 423, 429 or 5xx (except
 * 507, which means the server is full). Everything else, including 413, ends the file.
 */
internal fun Throwable.isTransientChunkFailure(): Boolean =
    when (this) {
        is WebDavHttpException -> {
            code == HTTP_REQUEST_TIMEOUT ||
                code == HTTP_LOCKED ||
                code == HTTP_TOO_MANY_REQUESTS ||
                (code in HTTP_SERVER_ERROR_FIRST..HTTP_SERVER_ERROR_LAST && code != HTTP_INSUFFICIENT_STORAGE)
        }

        is IOException -> {
            true
        }

        else -> {
            false
        }
    }

/**
 * Removes the upload folder of [transferId] on a best-effort basis: bounded by [timeoutMs], not
 * cancellable (a user cancel still sends it), and never throwing. Failures are logged by type or
 * HTTP code only.
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
