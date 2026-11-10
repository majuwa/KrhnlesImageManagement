package de.majuwa.android.paper.krhnlesimagemanagement.tls

import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinAwareHostnameVerifier
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.sha256Fingerprint
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext

/**
 * The verifier on its own, with a session stub: a resumed TLS session skips the trust manager, so the
 * verifier must repeat the validity check of the pinned leaf (and the origin / fingerprint match).
 */
@RunWith(RobolectricTestRunner::class)
class PinAwareHostnameVerifierTest {
    private val now = System.currentTimeMillis()

    private fun leaf(
        notBefore: Long = now - DAY,
        notAfter: Long = now + DAY,
    ) = HeldCertificate
        .Builder()
        .commonName("no-san")
        .validityInterval(notBefore, notAfter)
        .build()

    private fun pin(
        cert: HeldCertificate,
        port: Int = PORT,
    ) = PinnedCertificate(ServerOrigin("files.example", port), cert.certificate.sha256Fingerprint())

    private fun verify(
        pin: PinnedCertificate?,
        session: SSLSession,
        host: String = "files.example",
    ) = PinAwareHostnameVerifier(pin, clock = { now }).verify(host, session)

    @Test
    fun `a pinned valid leaf without matching names is accepted on the pinned host and port`() {
        val cert = leaf()
        assertTrue(verify(pin(cert), SessionStub(cert, PORT)))
    }

    @Test
    fun `an expired pinned leaf on a resumed session is rejected`() {
        val cert = leaf(notBefore = now - 2 * DAY, notAfter = now - 1_000L)
        assertFalse(verify(pin(cert), SessionStub(cert, PORT)))
    }

    @Test
    fun `a not yet valid pinned leaf is rejected`() {
        val cert = leaf(notBefore = now + DAY, notAfter = now + 2 * DAY)
        assertFalse(verify(pin(cert), SessionStub(cert, PORT)))
    }

    @Test
    fun `another leaf, another port, another host and no pin are rejected`() {
        val cert = leaf()
        assertFalse(verify(pin(cert), SessionStub(leaf(), PORT)))
        assertFalse(verify(pin(cert), SessionStub(cert, PORT + 1)))
        assertFalse(verify(pin(cert), SessionStub(cert, PORT), host = "other.example"))
        assertFalse(verify(null, SessionStub(cert, PORT)))
    }

    @Test
    fun `a session without peer certificates is rejected`() {
        val cert = leaf()
        assertFalse(verify(pin(cert), SessionStub(null, PORT)))
    }

    private class SessionStub(
        private val leaf: HeldCertificate?,
        private val port: Int,
    ) : SSLSession {
        override fun getPeerCertificates(): Array<Certificate> =
            leaf?.let { arrayOf<Certificate>(it.certificate) } ?: throw SSLPeerUnverifiedException("none")

        @Deprecated("javax.security.cert is obsolete; the verifier never reads it")
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> = emptyArray()

        override fun getPeerPort() = port

        override fun getPeerHost() = "files.example"

        override fun getId() = ByteArray(0)

        override fun getSessionContext(): SSLSessionContext? = null

        override fun getCreationTime() = 0L

        override fun getLastAccessedTime() = 0L

        override fun invalidate() = Unit

        override fun isValid() = true

        override fun putValue(
            name: String?,
            value: Any?,
        ) = Unit

        override fun getValue(name: String?): Any? = null

        override fun removeValue(name: String?) = Unit

        override fun getValueNames(): Array<String> = emptyArray()

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getPeerPrincipal(): Principal = throw SSLPeerUnverifiedException("none")

        override fun getLocalPrincipal(): Principal? = null

        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"

        override fun getProtocol() = "TLSv1.3"

        override fun getPacketBufferSize() = 0

        override fun getApplicationBufferSize() = 0
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val PORT = 8443
    }
}
