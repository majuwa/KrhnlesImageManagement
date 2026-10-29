package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.model.mediaItemId
import de.majuwa.android.paper.krhnlesimagemanagement.util.isAllowedPhotoUri
import de.majuwa.android.paper.krhnlesimagemanagement.util.mediaFileStateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "UploadedRemoval"
private const val MEDIA_AUTHORITY = "media"
private const val SEGMENT_COUNT = 4
private const val SEGMENT_COLLECTION = 1
private const val SEGMENT_MEDIA = 2
private const val SEGMENT_RAW_ID = 3
private const val COLLECTION_IMAGES = "images"
private const val COLLECTION_VIDEO = "video"

/** How long one server call of the check may take; a slower one keeps its photo. */
internal const val REMOVAL_SERVER_CALL_TIMEOUT_MS = 30_000L

/** Server checks that run at once. */
internal const val REMOVAL_CHECK_PARALLELISM = 4

/** Server failures / timeouts in a row after which the check stops and every remaining photo is kept. */
internal const val REMOVAL_MAX_CONSECUTIVE_SERVER_FAILURES = 5

/** A verified photo that passed every check: [uri] is a validated MediaStore image / video item. */
data class RemovalCandidate(
    val record: VerifiedUpload,
    val uri: Uri,
)

/**
 * The MediaStore uri of [record] if it is consistent (design D7), else null. Fail closed: only
 * `content://media/<volume>/images|video/media/<rawId>` of another app's provider, with `rawId`
 * equal to the record id (video: without the video flag), the flag set exactly for `video` uris.
 */
fun validatedMediaUri(
    record: VerifiedUpload,
    ownPackage: String,
): Uri? {
    val uri = runCatching { record.uri.toUri() }.getOrNull() ?: return null
    val segments = uri.pathSegments
    val kind =
        when (segments.getOrNull(SEGMENT_COLLECTION)) {
            COLLECTION_IMAGES -> MediaKind.Image
            COLLECTION_VIDEO -> MediaKind.Video
            else -> null
        }
    val rawId = segments.getOrNull(SEGMENT_RAW_ID)?.toLongOrNull()
    val valid =
        kind != null &&
            rawId != null &&
            uri.authority == MEDIA_AUTHORITY &&
            uri.query == null &&
            uri.fragment == null &&
            segments.size == SEGMENT_COUNT &&
            segments[0].isNotBlank() &&
            segments[SEGMENT_MEDIA] == "media" &&
            isAllowedPhotoUri(uri, ownPackage) &&
            mediaItemId(rawId, kind) == record.id
    return uri.takeIf { valid }
}

/**
 * Result of [RemovalCandidateChecker.check]. [serverUnreachable]: the check stopped after
 * [REMOVAL_MAX_CONSECUTIVE_SERVER_FAILURES] server failures in a row; [candidates] is then empty
 * and every photo is kept.
 */
data class RemovalCheck(
    val candidates: List<RemovalCandidate>,
    val serverUnreachable: Boolean = false,
)

/** Decides which recorded photos may be moved to the trash (design D6). */
interface RemovalCandidateChecker {
    /**
     * Checks every record: same server, valid MediaStore item, unchanged on the device, still on the
     * server with the recorded size. Failures keep the photo; [REMOVAL_MAX_CONSECUTIVE_SERVER_FAILURES]
     * server failures or timeouts in a row stop the whole check ([RemovalCheck.serverUnreachable]).
     * [onProgress] gets `(done, total)` and may be called from a background thread.
     */
    suspend fun check(
        records: List<VerifiedUpload>,
        config: WebDavConfig,
        onProgress: (done: Int, total: Int) -> Unit,
    ): RemovalCheck

    /** The local half of [check] again (valid item, unchanged on the device), right before a trash request. */
    suspend fun recheckLocal(candidates: List<RemovalCandidate>): List<RemovalCandidate>
}

/** How the server answered the size check of one photo. */
private enum class ServerAnswer {
    /** `2xx` with exactly the recorded size. */
    SameSize,

    /** An answer, but not the recorded size (other size, none, 404, 403, 3xx…): keep the photo. */
    Other,

    /** No usable answer (timeout, I/O, `5xx`, `408`, `429`): keep the photo and count it. */
    Unreachable,

    /** Not asked (the record's path is invalid): keep the photo, the server is not judged. */
    NotAsked,
}

/** Thrown inside the check to cancel the remaining server calls once the server looks unreachable. */
private class ServerUnreachableAbort : Exception("Server unreachable")

class DefaultRemovalCandidateChecker(
    private val resolver: ContentResolver,
    private val ownPackage: String,
    private val clientFactory: (WebDavConfig) -> WebDavClient = ::WebDavClient,
    private val serverCallTimeoutMs: Long = REMOVAL_SERVER_CALL_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemovalCandidateChecker {
    override suspend fun check(
        records: List<VerifiedUpload>,
        config: WebDavConfig,
        onProgress: (done: Int, total: Int) -> Unit,
    ): RemovalCheck {
        val client = clientFactory(config)
        val done = AtomicInteger(0)
        val consecutiveFailures = AtomicInteger(0)
        val permits = Semaphore(REMOVAL_CHECK_PARALLELISM)
        val total = records.size
        return try {
            coroutineScope {
                val candidates =
                    records
                        .map { record ->
                            async {
                                permits.withPermit {
                                    checkOne(client, config, record, consecutiveFailures).also {
                                        onProgress(done.incrementAndGet(), total)
                                    }
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()
                RemovalCheck(candidates)
            }
        } catch (e: ServerUnreachableAbort) {
            Log.w(TAG, "Removal check stopped: ${e.javaClass.simpleName}")
            RemovalCheck(emptyList(), serverUnreachable = true)
        }
    }

    private suspend fun checkOne(
        client: WebDavClient,
        config: WebDavConfig,
        record: VerifiedUpload,
        consecutiveFailures: AtomicInteger,
    ): RemovalCandidate? {
        if (record.serverUrl != config.url) return null
        val local = localCandidate(record) ?: return null
        when (onServer(client, record)) {
            ServerAnswer.SameSize -> {
                consecutiveFailures.set(0)
                return local
            }

            ServerAnswer.Other -> {
                consecutiveFailures.set(0)
            }

            ServerAnswer.Unreachable -> {
                if (consecutiveFailures.incrementAndGet() >= REMOVAL_MAX_CONSECUTIVE_SERVER_FAILURES) {
                    throw ServerUnreachableAbort()
                }
            }

            ServerAnswer.NotAsked -> {
                // Invalid path: says nothing about the server.
            }
        }
        return null
    }

    override suspend fun recheckLocal(candidates: List<RemovalCandidate>): List<RemovalCandidate> =
        candidates.mapNotNull { localCandidate(it.record) }

    private suspend fun localCandidate(record: VerifiedUpload): RemovalCandidate? =
        withContext(ioDispatcher) {
            validatedMediaUri(record, ownPackage)?.let { uri ->
                resolver
                    .mediaFileStateOf(uri)
                    ?.takeIf { it.size == record.size && it.dateModified == record.dateModified }
                    ?.let { RemovalCandidate(record, uri) }
            }
        }

    /** [ServerAnswer.SameSize] only if the server answers with exactly the recorded size. */
    private suspend fun onServer(
        client: WebDavClient,
        record: VerifiedUpload,
    ): ServerAnswer {
        val result =
            try {
                withTimeoutOrNull(serverCallTimeoutMs) { client.remoteFileSize(record.folder, record.fileName) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Defensive: remoteFileSize reports failures as a Result; never let one abort the check.
                Log.w(TAG, "Server check failed: ${e.javaClass.simpleName}")
                return ServerAnswer.Unreachable
            } ?: return ServerAnswer.Unreachable
        val failure = result.exceptionOrNull()
        // Type only: exception messages may carry server details (never shown, never logged).
        if (failure != null) Log.w(TAG, "Server check failed: ${failure.javaClass.simpleName}")
        return when {
            failure == null -> if (result.getOrNull() == record.size) ServerAnswer.SameSize else ServerAnswer.Other
            failure is IllegalArgumentException -> ServerAnswer.NotAsked
            failure is WebDavHttpException && !failure.isUnreachable() -> ServerAnswer.Other
            else -> ServerAnswer.Unreachable
        }
    }
}

/** A status that says the server cannot answer now (overloaded, rate limited, timed out). */
private fun WebDavHttpException.isUnreachable(): Boolean =
    code >= HTTP_SERVER_ERROR || code == HTTP_REQUEST_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS

private const val HTTP_SERVER_ERROR = 500
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
