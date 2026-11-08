package de.majuwa.android.paper.krhnlesimagemanagement

import android.system.ErrnoException
import android.system.OsConstants
import de.majuwa.android.paper.krhnlesimagemanagement.data.DownloadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.DownloadTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriteException
import de.majuwa.android.paper.krhnlesimagemanagement.data.StorageFullException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnexpectedContentException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeHrefException
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.data.toDownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
class DownloadFailureTest {
    private fun http(code: Int) = WebDavHttpException(code, "server said something secret")

    @Test
    fun `failures map to their typed reason`() {
        val cases: List<Pair<Throwable, DownloadFailure>> =
            listOf(
                UnsafeHrefException() to DownloadFailure.Rejected,
                UnexpectedContentException() to DownloadFailure.Rejected,
                http(301) to DownloadFailure.Rejected,
                http(307) to DownloadFailure.Rejected,
                http(404) to DownloadFailure.NotFound,
                http(410) to DownloadFailure.NotFound,
                http(401) to DownloadFailure.AccessDenied,
                http(403) to DownloadFailure.AccessDenied,
                http(500) to DownloadFailure.ServerError,
                http(503) to DownloadFailure.ServerError,
                http(429) to DownloadFailure.ServerError,
                SSLHandshakeException("untrusted") to DownloadFailure.Certificate,
                IOException("wrapped", CertificateException("pin")) to DownloadFailure.Certificate,
                UnknownHostException() to DownloadFailure.Connection,
                ConnectException() to DownloadFailure.Connection,
                SocketTimeoutException() to DownloadFailure.Connection,
                IOException("broken") to DownloadFailure.Connection,
                DownloadTooLargeException() to DownloadFailure.TooLarge,
                StorageFullException() to DownloadFailure.StorageFull,
                IOException("write", ErrnoException("write", OsConstants.ENOSPC)) to DownloadFailure.StorageFull,
                LocalMediaWriteException() to DownloadFailure.WriteFailed,
            )
        cases.forEach { (failure, expected) -> assertEquals(failure.toString(), expected, failure.toDownloadFailure()) }
    }

    @Test
    fun `a wrapped cause is found`() {
        assertEquals(DownloadFailure.NotFound, RuntimeException("x", http(404)).toDownloadFailure())
        assertEquals(DownloadFailure.TooLarge, IOException("x", DownloadTooLargeException()).toDownloadFailure())
    }

    @Test
    fun `cancellation is never mapped`() {
        assertThrows(CancellationException::class.java) { CancellationException("cancel").toDownloadFailure() }
    }
}
