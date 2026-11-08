package de.majuwa.android.paper.krhnlesimagemanagement.data

import java.io.IOException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/** Why saving one album photo to the device failed, for user-facing messages (never raw exception text). */
enum class DownloadFailure {
    /** The file is not an allow-listed image type (video, SVG, TIFF, unknown extension). */
    Unsupported,

    /** The file is larger than the per-file limit. */
    TooLarge,

    /** The device has no space left. */
    StorageFull,

    /** The server no longer has the file (404 / 410). */
    NotFound,

    /** The server refused the credentials (401 / 403). */
    AccessDenied,

    /** The server certificate is not trusted. */
    Certificate,

    /** The server could not be reached or the transfer broke off. */
    Connection,

    /** Any other HTTP status. */
    ServerError,

    /** The app refused the request: unsafe href, redirect, or a response that is not an image. */
    Rejected,

    /** The photo could not be written to the device. */
    WriteFailed,

    /** The server configuration changed while the batch was queued or running. */
    ConfigurationChanged,

    /** The batch was interrupted too often and gave up. */
    Interrupted,
}

/** The body is, or announces to be, larger than the per-file limit. */
class DownloadTooLargeException : IOException("Download is larger than the limit")

/** The response `Content-Type` is neither an image nor `application/octet-stream`. */
class UnexpectedContentException : IOException("Response is not an image")

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND_CODE = 404
private const val HTTP_GONE = 410
private const val HTTP_REDIRECT_FIRST = 300
private const val HTTP_REDIRECT_LAST = 399
private const val MAX_DOWNLOAD_CAUSE_DEPTH = 10

/**
 * Maps a failure of one photo download to its typed reason. Branches on exception types and
 * [WebDavHttpException.code] only, never on messages. A [CancellationException] is rethrown, never
 * mapped.
 */
fun Throwable.toDownloadFailure(): DownloadFailure {
    if (this is CancellationException) throw this
    val chain = generateSequence(this) { it.cause }.take(MAX_DOWNLOAD_CAUSE_DEPTH).toList()
    val http = chain.filterIsInstance<WebDavHttpException>().firstOrNull()
    return when {
        chain.any { it is UnsafeHrefException || it is UnexpectedContentException } -> DownloadFailure.Rejected
        chain.any { it is DownloadTooLargeException } -> DownloadFailure.TooLarge
        chain.any { it is StorageFullException } || isOutOfSpaceError() -> DownloadFailure.StorageFull
        chain.any { it is LocalMediaWriteException } -> DownloadFailure.WriteFailed
        http != null -> http.code.toHttpFailure()
        chain.any { it is SSLException || it is GeneralSecurityException } -> DownloadFailure.Certificate
        else -> DownloadFailure.Connection
    }
}

private fun Int.toHttpFailure(): DownloadFailure =
    when (this) {
        in HTTP_REDIRECT_FIRST..HTTP_REDIRECT_LAST -> DownloadFailure.Rejected
        HTTP_NOT_FOUND_CODE, HTTP_GONE -> DownloadFailure.NotFound
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> DownloadFailure.AccessDenied
        else -> DownloadFailure.ServerError
    }
