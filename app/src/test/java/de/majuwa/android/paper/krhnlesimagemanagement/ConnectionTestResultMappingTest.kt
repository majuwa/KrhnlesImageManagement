package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.ConnectionTestResult
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.toConnectionTestResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Every connection-test failure maps to a typed, user-friendly result (review of issue #22 / #41). */
class ConnectionTestResultMappingTest {
    private fun assertMaps(
        expected: ConnectionTestResult,
        failure: Throwable,
    ) = assertEquals(failure.toString(), expected, failure.toConnectionTestResult())

    @Test
    fun `TLS and certificate failures map to CertificateError`() {
        assertMaps(ConnectionTestResult.CertificateError, SSLHandshakeException("handshake"))
        assertMaps(ConnectionTestResult.CertificateError, SSLPeerUnverifiedException("hostname mismatch"))
        assertMaps(ConnectionTestResult.CertificateError, CertPathValidatorException("Trust anchor not found"))
        assertMaps(ConnectionTestResult.CertificateError, CertificateException("expired"))
        // Typical Android shape: handshake failure wrapping the validator error, wrapped once more.
        assertMaps(
            ConnectionTestResult.CertificateError,
            IOException("wrapper", SSLHandshakeException("x").apply { initCause(CertPathValidatorException("y")) }),
        )
    }

    @Test
    fun `network failures map to host and reachability results`() {
        assertMaps(ConnectionTestResult.UnknownHost, UnknownHostException("cloud.example.com"))
        assertMaps(ConnectionTestResult.ServerUnreachable, ConnectException("refused"))
        assertMaps(ConnectionTestResult.ServerUnreachable, NoRouteToHostException("no route"))
    }

    @Test
    fun `socket and call timeouts map to Timeout`() {
        assertMaps(ConnectionTestResult.Timeout, SocketTimeoutException("timeout"))
        assertMaps(ConnectionTestResult.Timeout, InterruptedIOException("timeout"))
    }

    @Test
    fun `HTTP status codes map to auth, not found and generic HTTP results`() {
        assertMaps(ConnectionTestResult.AuthFailed, WebDavHttpException(401, "HTTP 401"))
        assertMaps(ConnectionTestResult.AuthFailed, WebDavHttpException(403, "HTTP 403"))
        assertMaps(ConnectionTestResult.NotFound, WebDavHttpException(404, "HTTP 404"))
        assertMaps(ConnectionTestResult.HttpError(500), WebDavHttpException(500, "HTTP 500"))
        assertMaps(ConnectionTestResult.HttpError(503), WebDavHttpException(503, "HTTP 503"))
    }

    @Test
    fun `invalid URL and unknown failures map to dedicated results`() {
        assertMaps(ConnectionTestResult.InvalidUrl, IllegalArgumentException("Expected URL scheme"))
        assertMaps(ConnectionTestResult.UnknownError, IOException("DataStore read failed"))
        assertMaps(ConnectionTestResult.UnknownError, IllegalStateException())
    }
}
