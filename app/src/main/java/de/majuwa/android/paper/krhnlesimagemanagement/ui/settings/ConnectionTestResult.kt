package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateChangedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateReviewException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import okio.ByteString
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

/**
 * Outcome of the user-triggered WebDAV connection test. The UI maps every case to a localized,
 * user-friendly string; raw exception text is never shown.
 */
sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult

    data object NotConfigured : ConnectionTestResult

    /** TLS handshake failed or the certificate is not trusted (e.g. self-signed or expired). */
    data object CertificateError : ConnectionTestResult

    /**
     * The configured origin presented a certificate that is neither system-trusted nor pinned; the
     * [certificate] is offered for review (never trusted automatically).
     */
    data class UntrustedCertificate(
        val certificate: ServerCertificate,
    ) : ConnectionTestResult

    /**
     * A certificate is pinned for the origin but the server presented a different, untrusted one.
     * [certificate] is the new one (`null` outside the connection test, e.g. when reused for albums).
     */
    data class CertificateChanged(
        val certificate: ServerCertificate?,
        val previousSha256: ByteString? = null,
    ) : ConnectionTestResult

    /** The host name could not be resolved. */
    data object UnknownHost : ConnectionTestResult

    /** The host resolved but refused or could not route the connection. */
    data object ServerUnreachable : ConnectionTestResult

    /** The server did not answer within the connection-test time limit. */
    data object Timeout : ConnectionTestResult

    /** The server rejected the credentials (HTTP 401 / 403). */
    data object AuthFailed : ConnectionTestResult

    /** The WebDAV URL does not exist on the server (HTTP 404). */
    data object NotFound : ConnectionTestResult

    /** Any other non-success HTTP status. */
    data class HttpError(
        val code: Int,
    ) : ConnectionTestResult

    /** The stored server address is not a valid URL. */
    data object InvalidUrl : ConnectionTestResult

    /** Anything else (including failing to read the stored settings). */
    data object UnknownError : ConnectionTestResult
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/** Maps a connection-test failure to a typed result; checks the whole cause chain for TLS problems. */
fun Throwable.toConnectionTestResult(): ConnectionTestResult {
    val chain = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    return when {
        this is WebDavHttpException -> httpResult(code)

        chain.any {
            it is CertificateReviewException
        } -> chain.filterIsInstance<CertificateReviewException>().first().toResult()

        chain.any { it is CertificateChangedException } -> ConnectionTestResult.CertificateChanged(null)

        chain.any { it is SSLException || it is GeneralSecurityException } -> ConnectionTestResult.CertificateError

        this is UnknownHostException -> ConnectionTestResult.UnknownHost

        this is ConnectException || this is NoRouteToHostException -> ConnectionTestResult.ServerUnreachable

        // SocketTimeoutException and OkHttp's call timeout ("timeout") are both InterruptedIOException.
        this is InterruptedIOException -> ConnectionTestResult.Timeout

        this is IllegalArgumentException -> ConnectionTestResult.InvalidUrl

        else -> ConnectionTestResult.UnknownError
    }
}

private const val MAX_CAUSE_DEPTH = 10

private fun CertificateReviewException.toResult(): ConnectionTestResult =
    if (changed) {
        ConnectionTestResult.CertificateChanged(certificate, previousSha256)
    } else {
        ConnectionTestResult.UntrustedCertificate(certificate)
    }

private fun httpResult(code: Int): ConnectionTestResult =
    when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ConnectionTestResult.AuthFailed
        HTTP_NOT_FOUND -> ConnectionTestResult.NotFound
        else -> ConnectionTestResult.HttpError(code)
    }
