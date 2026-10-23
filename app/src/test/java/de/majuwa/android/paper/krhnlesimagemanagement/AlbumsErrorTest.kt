package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryUnavailableException
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryUnavailableException.Reason
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeHrefException
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.albumsErrorMessage
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.toAlbumsError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Album loading / scan failures are typed and shown as localized text, never as raw exception
 * messages (review of issues #29 / #48, CLAUDE.md rule 7).
 */
@RunWith(RobolectricTestRunner::class)
class AlbumsErrorTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources

    private fun assertMaps(
        expected: AlbumsError,
        failure: Throwable,
    ) = assertEquals(failure.toString(), expected, failure.toAlbumsError())

    @Test
    fun `repository preparation failures map to configuration errors`() {
        assertMaps(AlbumsError.NotConfigured, AlbumsRepositoryUnavailableException(Reason.NOT_CONFIGURED))
        assertMaps(AlbumsError.InvalidServerUrl, AlbumsRepositoryUnavailableException(Reason.INVALID_CONFIGURATION))
        assertMaps(AlbumsError.Timeout, AlbumsRepositoryUnavailableException(Reason.TIMEOUT))
    }

    @Test
    fun `network and HTTP failures reuse the connection-test classification`() {
        assertMaps(AlbumsError.CertificateError, SSLHandshakeException("self-signed"))
        assertMaps(AlbumsError.UnknownHost, UnknownHostException("cloud.example.com"))
        assertMaps(AlbumsError.ServerUnreachable, ConnectException("refused"))
        assertMaps(AlbumsError.Timeout, SocketTimeoutException("timeout"))
        assertMaps(AlbumsError.AuthFailed, WebDavHttpException(401, "PROPFIND failed: HTTP 401"))
        assertMaps(AlbumsError.NotFound, WebDavHttpException(404, "PROPFIND failed: HTTP 404"))
        assertMaps(AlbumsError.HttpError(503), WebDavHttpException(503, "PROPFIND failed: HTTP 503"))
    }

    @Test
    fun `unsafe hrefs and anything else map to an unknown error`() {
        assertMaps(AlbumsError.Unknown, UnsafeHrefException())
        assertMaps(AlbumsError.Unknown, IllegalStateException("PROPFIND failed: https://secret.example/x"))
    }

    @Test
    fun `every error has its own localized message`() {
        val errors =
            listOf(
                AlbumsError.NotConfigured,
                AlbumsError.InvalidServerUrl,
                AlbumsError.ConfigurationChanged,
                AlbumsError.CertificateError,
                AlbumsError.UnknownHost,
                AlbumsError.ServerUnreachable,
                AlbumsError.Timeout,
                AlbumsError.AuthFailed,
                AlbumsError.NotFound,
                AlbumsError.HttpError(503),
                AlbumsError.Unknown,
            )
        val messages = errors.map { albumsErrorMessage(resources, it) }

        assertTrue(messages.all { it.isNotBlank() })
        assertEquals("one distinct message per error", errors.size, messages.toSet().size)
        assertTrue(albumsErrorMessage(resources, AlbumsError.HttpError(503)).contains("503"))
    }
}
