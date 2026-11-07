package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateChangedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateReviewException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.toUploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.albumsErrorMessage
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.toAlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.ConnectionTestResult
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.toConnectionTestResult
import de.majuwa.android.paper.krhnlesimagemanagement.worker.FolderCreationError
import de.majuwa.android.paper.krhnlesimagemanagement.worker.toFolderCreationError
import de.majuwa.android.paper.krhnlesimagemanagement.worker.uploadFailureMessage
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
class CertificateErrorMappingTest {
    private val certificate =
        ServerCertificate(ServerOrigin("cloud.example", 443), "ab".repeat(32).decodeHex(), "CN=a", "CN=b", 0L, 1L)
    private val previous = "cd".repeat(32).decodeHex()

    private fun changedInHandshake() =
        SSLHandshakeException("x").apply {
            initCause(CertificateChangedException(RuntimeException()))
        }

    @Test
    fun `review exception maps to untrusted or changed with details`() {
        val untrusted = CertificateReviewException(certificate, false, null, SSLHandshakeException("x"))
        assertEquals(ConnectionTestResult.UntrustedCertificate(certificate), untrusted.toConnectionTestResult())
        val changed = CertificateReviewException(certificate, true, previous, SSLHandshakeException("x"))
        assertEquals(ConnectionTestResult.CertificateChanged(certificate, previous), changed.toConnectionTestResult())
    }

    @Test
    fun `a bare changed exception inside a handshake failure maps to changed`() {
        assertEquals(ConnectionTestResult.CertificateChanged(null), changedInHandshake().toConnectionTestResult())
    }

    @Test
    fun `other TLS failures and HTTP statuses keep their mapping`() {
        assertEquals(ConnectionTestResult.CertificateError, SSLHandshakeException("x").toConnectionTestResult())
        assertEquals(ConnectionTestResult.AuthFailed, WebDavHttpException(401, "m").toConnectionTestResult())
    }

    @Test
    fun `albums errors distinguish untrusted and changed certificates`() {
        assertEquals(AlbumsError.CertificateChanged, changedInHandshake().toAlbumsError())
        val untrusted = CertificateReviewException(certificate, false, null, SSLHandshakeException("x"))
        assertEquals(AlbumsError.CertificateError, untrusted.toAlbumsError())
        val resources = RuntimeEnvironment.getApplication().resources
        assertNotEquals(
            albumsErrorMessage(resources, AlbumsError.CertificateError),
            albumsErrorMessage(resources, AlbumsError.CertificateChanged),
        )
    }

    @Test
    fun `folder creation carries the changed certificate as a connection error`() {
        assertEquals(
            FolderCreationError.Connection(AlbumsError.CertificateChanged),
            changedInHandshake().toFolderCreationError(),
        )
    }

    @Test
    fun `the upload notification names the changed certificate`() {
        val resources = RuntimeEnvironment.getApplication().resources
        val message = uploadFailureMessage(resources, setOf(UploadFailure.CertificateChanged))
        assertEquals(resources.getString(R.string.upload_failure_certificate_changed), message)
    }

    @Test
    fun `upload failure reports the changed certificate from the cause chain`() {
        assertEquals(UploadFailure.CertificateChanged, changedInHandshake().toUploadFailure())
        assertEquals(UploadFailure.TooLarge, WebDavHttpException(413, "m").toUploadFailure())
        assertEquals(UploadFailure.Other, SSLHandshakeException("x").toUploadFailure())
    }
}
