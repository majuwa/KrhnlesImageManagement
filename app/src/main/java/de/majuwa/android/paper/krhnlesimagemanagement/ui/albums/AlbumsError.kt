package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.content.res.Resources
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumNameTooLongException
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryUnavailableException
import de.majuwa.android.paper.krhnlesimagemanagement.data.InvalidAlbumNameException
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindResponseTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeHrefException
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.ConnectionTestResult
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.toConnectionTestResult

/**
 * Why loading or scanning albums failed. Held in the album UI states instead of raw exception
 * text; the UI maps every case to a localized message ([albumsErrorMessage]).
 */
sealed interface AlbumsError {
    /** No complete credentials are stored (e.g. after logout). */
    data object NotConfigured : AlbumsError

    /** The stored server address cannot be used. */
    data object InvalidServerUrl : AlbumsError

    /** The server configuration changed while this album was open. */
    data object ConfigurationChanged : AlbumsError

    /** TLS handshake failed or the certificate is not trusted (e.g. self-signed or expired). */
    data object CertificateError : AlbumsError

    data object UnknownHost : AlbumsError

    data object ServerUnreachable : AlbumsError

    data object Timeout : AlbumsError

    /** HTTP 401 / 403. */
    data object AuthFailed : AlbumsError

    /** HTTP 404. */
    data object NotFound : AlbumsError

    data class HttpError(
        val code: Int,
    ) : AlbumsError

    /** The folder listing exceeds the response size limit (album list; never silently truncated). */
    data object ListingTooLarge : AlbumsError

    /** Rename: HTTP 412, an album with the new name already exists (never overwritten). */
    data object AlreadyExists : AlbumsError

    /** Rename: the new name is blank, `.` / `..` or contains `/`, `\` or NUL (no request was sent). */
    data object InvalidName : AlbumsError

    /** Rename: HTTP 404 / 409, the album (or its parent folder) no longer exists on the server. */
    data object AlbumGone : AlbumsError

    /** Rename: HTTP 400 / 403, the server rejected the name or the account may not rename. */
    data object RenameRejected : AlbumsError

    /** Rename: the new name exceeds 255 UTF-8 bytes (no request was sent). */
    data object NameTooLong : AlbumsError

    /** Rename: a 2xx other than 201 / 204 (e.g. 207); the rename may be incomplete, the list is reloaded. */
    data object RenamePartial : AlbumsError

    /** Rename: no answer in time; the server may still have completed the rename. */
    data object RenameTimedOut : AlbumsError

    data object Unknown : AlbumsError
}

/** Maps a failure while loading / scanning albums to a typed [AlbumsError]. */
fun Throwable.toAlbumsError(): AlbumsError =
    when (this) {
        is AlbumsRepositoryUnavailableException -> {
            when (reason) {
                AlbumsRepositoryUnavailableException.Reason.NOT_CONFIGURED -> AlbumsError.NotConfigured
                AlbumsRepositoryUnavailableException.Reason.INVALID_CONFIGURATION -> AlbumsError.InvalidServerUrl
                AlbumsRepositoryUnavailableException.Reason.TIMEOUT -> AlbumsError.Timeout
            }
        }

        is UnsafeHrefException -> {
            AlbumsError.Unknown
        }

        is InvalidAlbumNameException -> {
            AlbumsError.InvalidName
        }

        is AlbumNameTooLongException -> {
            AlbumsError.NameTooLong
        }

        is PropfindResponseTooLargeException -> {
            AlbumsError.ListingTooLarge
        }

        else -> {
            toConnectionTestResult().toAlbumsError()
        }
    }

/**
 * Maps a failure of the rename `MOVE` to a typed [AlbumsError]: 412 exists, 404 / 409 the album is
 * gone, 400 / 403 rejected name or missing permission; everything else (401, 423, TLS, timeout, other
 * statuses) uses the generic [toAlbumsError] path. Branches on the status code only.
 */
fun Throwable.toRenameAlbumError(): AlbumsError =
    if (this is WebDavHttpException) {
        when (code) {
            HTTP_PRECONDITION_FAILED -> AlbumsError.AlreadyExists
            HTTP_NOT_FOUND, HTTP_CONFLICT -> AlbumsError.AlbumGone
            HTTP_BAD_REQUEST, HTTP_FORBIDDEN -> AlbumsError.RenameRejected
            in HTTP_SUCCESS_CODES -> AlbumsError.RenamePartial
            else -> toAlbumsError()
        }
    } else {
        toAlbumsError()
    }

private val HTTP_SUCCESS_CODES = 200..299
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_PRECONDITION_FAILED = 412

/** Reuses the connection-test classification (TLS cause chain, DNS, timeouts, HTTP status). */
private fun ConnectionTestResult.toAlbumsError(): AlbumsError =
    when (this) {
        ConnectionTestResult.NotConfigured -> AlbumsError.NotConfigured
        ConnectionTestResult.CertificateError -> AlbumsError.CertificateError
        ConnectionTestResult.UnknownHost -> AlbumsError.UnknownHost
        ConnectionTestResult.ServerUnreachable -> AlbumsError.ServerUnreachable
        ConnectionTestResult.Timeout -> AlbumsError.Timeout
        ConnectionTestResult.AuthFailed -> AlbumsError.AuthFailed
        ConnectionTestResult.NotFound -> AlbumsError.NotFound
        is ConnectionTestResult.HttpError -> AlbumsError.HttpError(code)
        ConnectionTestResult.InvalidUrl -> AlbumsError.InvalidServerUrl
        ConnectionTestResult.Success, ConnectionTestResult.UnknownError -> AlbumsError.Unknown
    }

/** Localized, user-friendly text for [error]. In Compose pass `LocalResources.current`. */
fun albumsErrorMessage(
    resources: Resources,
    error: AlbumsError,
): String =
    when (error) {
        AlbumsError.NotConfigured -> resources.getString(R.string.albums_error_not_configured)

        AlbumsError.InvalidServerUrl -> resources.getString(R.string.albums_error_invalid_url)

        AlbumsError.ConfigurationChanged -> resources.getString(R.string.albums_error_configuration_changed)

        AlbumsError.CertificateError -> resources.getString(R.string.albums_error_certificate)

        AlbumsError.UnknownHost -> resources.getString(R.string.albums_error_unknown_host)

        AlbumsError.ServerUnreachable -> resources.getString(R.string.albums_error_unreachable)

        AlbumsError.Timeout -> resources.getString(R.string.albums_error_timeout)

        AlbumsError.AuthFailed -> resources.getString(R.string.albums_error_auth)

        AlbumsError.NotFound -> resources.getString(R.string.albums_error_not_found)

        is AlbumsError.HttpError -> resources.getString(R.string.albums_error_http, error.code)

        AlbumsError.ListingTooLarge -> resources.getString(R.string.albums_error_listing_too_large)

        AlbumsError.AlreadyExists,
        AlbumsError.InvalidName,
        AlbumsError.NameTooLong,
        AlbumsError.AlbumGone,
        AlbumsError.RenameRejected,
        AlbumsError.RenamePartial,
        AlbumsError.RenameTimedOut,
        -> renameErrorMessage(resources, error)

        AlbumsError.Unknown -> resources.getString(R.string.albums_error_unknown)
    }

private fun renameErrorMessage(
    resources: Resources,
    error: AlbumsError,
): String =
    when (error) {
        AlbumsError.AlreadyExists -> resources.getString(R.string.albums_error_already_exists)
        AlbumsError.InvalidName -> resources.getString(R.string.error_album_name_invalid)
        AlbumsError.NameTooLong -> resources.getString(R.string.error_album_name_too_long)
        AlbumsError.RenamePartial -> resources.getString(R.string.albums_error_rename_partial)
        AlbumsError.AlbumGone -> resources.getString(R.string.albums_error_album_gone)
        AlbumsError.RenameRejected -> resources.getString(R.string.albums_error_rename_rejected)
        AlbumsError.RenameTimedOut -> resources.getString(R.string.albums_error_rename_timeout)
        else -> resources.getString(R.string.albums_error_unknown)
    }
