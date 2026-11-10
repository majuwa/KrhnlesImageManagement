package de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer

import android.content.res.Resources
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.RepositoryClosedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeVideoUrlException
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.albumsErrorMessage
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.toAlbumsError

/** Why a video could not be played; never carries exception text. */
sealed interface VideoPlaybackError {
    /** A server, network or certificate problem, classified like album loading failures. */
    data class Server(
        val error: AlbumsError,
    ) : VideoPlaybackError

    /** The device cannot decode the video or the container is not supported. */
    data object UnsupportedFormat : VideoPlaybackError

    /** The server answered with a redirect, which is deliberately never followed. */
    data object Redirected : VideoPlaybackError
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
private val HTTP_REDIRECTS = 300..399

/**
 * Maps a Media3 failure to a [VideoPlaybackError]. Branches on the app's own guard exceptions, the
 * HTTP status code and Media3 error codes, never on messages. The cause chain is searched first:
 * a guard failure anywhere in it is generic; a bad HTTP status is mapped by its code; decoder and
 * container errors are [VideoPlaybackError.UnsupportedFormat]; network, timeout and TLS failures
 * use [toAlbumsError] on the first cause below the Media3 wrappers (it walks the rest of the chain
 * itself), so a pinned-certificate failure still reads as a changed / untrusted certificate.
 */
fun PlaybackException.toVideoPlaybackError(): VideoPlaybackError {
    val chain = generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    if (chain.any { it is UnsafeVideoUrlException || it is RepositoryClosedException }) {
        return VideoPlaybackError.Server(AlbumsError.Unknown)
    }
    chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
        return statusError(it.responseCode)
    }
    if (errorCode in TRANSIENT_DECODER_ERROR_CODES) return VideoPlaybackError.Server(AlbumsError.Unknown)
    if (errorCode in UNSUPPORTED_ERROR_CODES) return VideoPlaybackError.UnsupportedFormat
    val cause = chain.firstOrNull { !it.javaClass.name.startsWith(MEDIA3_PACKAGE) } ?: this
    return VideoPlaybackError.Server(cause.toAlbumsError().takeUnless { it == AlbumsError.Unknown } ?: ioFallback())
}

private fun PlaybackException.ioFallback(): AlbumsError =
    when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> AlbumsError.Timeout
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> AlbumsError.ServerUnreachable
        else -> AlbumsError.Unknown
    }

private fun statusError(code: Int): VideoPlaybackError =
    when (code) {
        in HTTP_REDIRECTS -> VideoPlaybackError.Redirected
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> VideoPlaybackError.Server(AlbumsError.AuthFailed)
        HTTP_NOT_FOUND, HTTP_GONE -> VideoPlaybackError.Server(AlbumsError.NotFound)
        else -> VideoPlaybackError.Server(AlbumsError.HttpError(code))
    }

private const val MAX_CAUSE_DEPTH = 16
private const val MEDIA3_PACKAGE = "androidx.media3."

private val UNSUPPORTED_ERROR_CODES =
    setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )

/**
 * Decoder failures that say nothing about the file (another app holds the codec, the decoder was
 * reclaimed): a generic, retryable error instead of "not supported on this device".
 */
private val TRANSIENT_DECODER_ERROR_CODES =
    setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    )

/** Localized, user-friendly text for [error]. In Compose pass `LocalResources.current`. */
fun videoPlaybackErrorMessage(
    resources: Resources,
    error: VideoPlaybackError,
): String =
    when (error) {
        is VideoPlaybackError.Server -> albumsErrorMessage(resources, error.error)
        VideoPlaybackError.UnsupportedFormat -> resources.getString(R.string.video_error_unsupported_format)
        VideoPlaybackError.Redirected -> resources.getString(R.string.video_error_redirected)
    }
