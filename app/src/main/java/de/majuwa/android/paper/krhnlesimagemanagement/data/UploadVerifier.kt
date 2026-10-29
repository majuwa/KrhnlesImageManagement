package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig

/** Where an uploaded file lives: the exact remote folder string of the `PUT` and the file name. */
data class RemoteFile(
    val folder: String,
    val fileName: String,
)

/** SHA-256 (hex, any case) and byte count of exactly the bytes that were sent. */
data class LocalDigest(
    val sha256: String,
    val sizeBytes: Long,
)

/** Result of checking one uploaded file against the local digest. */
sealed interface Verification {
    /** The server copy is byte-identical. */
    data object Verified : Verification

    /** The server copy differs (hash, size or more bytes than were sent). */
    data object Mismatch : Verification

    /** The check could not finish (network error, timeout, non-2xx download). */
    data object Inconclusive : Verification

    /** No strategy can tell; the upload counts as uploaded but is never verified. */
    data object Unavailable : Verification
}

/**
 * One way to verify an upload. The worker sees only this interface; strategies are chosen by
 * [verifierFor]. [verify] never throws except for coroutine cancellation.
 */
interface UploadVerifier {
    /** True if the `PUT` should carry `X-Hash: sha256` so the server computes a hash. */
    val requestsServerHash: Boolean

    suspend fun verify(
        target: RemoteFile,
        local: LocalDigest,
        put: UploadResponse,
    ): Verification
}

/** No strategy available: never verified, no extra request. */
object NoVerifier : UploadVerifier {
    override val requestsServerHash: Boolean = false

    override suspend fun verify(
        target: RemoteFile,
        local: LocalDigest,
        put: UploadResponse,
    ): Verification = Verification.Unavailable
}

/**
 * Compares the SHA-256 the Nextcloud server returned for the `PUT` with the local one; no extra
 * request. A missing or malformed server hash delegates to [fallback] (or is [Verification.Unavailable]).
 */
class NextcloudHashVerifier(
    private val fallback: UploadVerifier? = null,
) : UploadVerifier {
    override val requestsServerHash: Boolean = true

    override suspend fun verify(
        target: RemoteFile,
        local: LocalDigest,
        put: UploadResponse,
    ): Verification {
        val serverHash = put.serverSha256?.takeIf(::isSha256Hex)
        return when {
            serverHash == null -> {
                fallback?.verify(target, local, put) ?: Verification.Unavailable
            }

            serverHash.equals(local.sha256, ignoreCase = true) -> {
                Verification.Verified
            }

            else -> {
                Verification.Mismatch
            }
        }
    }
}

/** Downloads the uploaded file again and compares SHA-256 and size (extra data, opt-in). */
class DownloadVerifier(
    private val download: suspend (folder: String, fileName: String, maxBytes: Long) -> Result<RemoteDigest>,
) : UploadVerifier {
    constructor(client: WebDavClient) : this(client::downloadSha256)

    override val requestsServerHash: Boolean = false

    override suspend fun verify(
        target: RemoteFile,
        local: LocalDigest,
        put: UploadResponse,
    ): Verification {
        val result = download(target.folder, target.fileName, local.sizeBytes)
        val digest = result.getOrNull()
        return when {
            digest != null -> {
                if (digest.sizeBytes == local.sizeBytes && digest.sha256.equals(local.sha256, ignoreCase = true)) {
                    Verification.Verified
                } else {
                    Verification.Mismatch
                }
            }

            result.exceptionOrNull() is RemoteFileTooLargeException -> {
                Verification.Mismatch
            }

            else -> {
                // Type only: exception messages may carry server details.
                Log.w(TAG, "Verification download failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                Verification.Inconclusive
            }
        }
    }

    private companion object {
        const val TAG = "DownloadVerifier"
    }
}

/**
 * Picks the strategy: a Nextcloud URL (`/remote.php/`) verifies through the server hash with the
 * download as fallback when [downloadEnabled]; any other server verifies by download only when
 * [downloadEnabled], else not at all. [client] serves the download strategy.
 */
fun verifierFor(
    config: WebDavConfig,
    downloadEnabled: Boolean,
    client: WebDavClient,
): UploadVerifier = verifierFor(config, downloadEnabled) { DownloadVerifier(client) }

internal fun verifierFor(
    config: WebDavConfig,
    downloadEnabled: Boolean,
    downloadVerifier: () -> UploadVerifier,
): UploadVerifier {
    val download = if (downloadEnabled) downloadVerifier() else null
    return when {
        buildNextcloudBase(config.url) != null -> NextcloudHashVerifier(fallback = download)
        download != null -> download
        else -> NoVerifier
    }
}
