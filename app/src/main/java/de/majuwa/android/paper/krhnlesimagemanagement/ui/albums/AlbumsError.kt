package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.content.res.Resources
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryUnavailableException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeHrefException
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

        else -> {
            toConnectionTestResult().toAlbumsError()
        }
    }

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
        AlbumsError.Unknown -> resources.getString(R.string.albums_error_unknown)
    }
