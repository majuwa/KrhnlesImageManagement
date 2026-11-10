package de.majuwa.android.paper.krhnlesimagemanagement.data.tls

import android.util.Log
import okhttp3.OkHttpClient
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession

/** OkHttp's default verifier, created once (an `OkHttpClient` per verifier would build a pool each time). */
private val defaultHostnameVerifier: HostnameVerifier by lazy { OkHttpClient().hostnameVerifier }

/**
 * OkHttp's standard host name verification, plus one exception: the exact pinned leaf, currently
 * valid, on exactly the pinned host and port is accepted even if its names do not cover the host
 * (self-signed certificates often have no SAN). The validity check is repeated here because a
 * resumed TLS session skips the trust manager.
 */
internal class PinAwareHostnameVerifier(
    private val pin: PinnedCertificate?,
    private val base: HostnameVerifier = defaultHostnameVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
) : HostnameVerifier {
    override fun verify(
        hostname: String,
        session: SSLSession,
    ): Boolean = base.verify(hostname, session) || isPinned(hostname, session)

    private fun isPinned(
        hostname: String,
        session: SSLSession,
    ): Boolean {
        val target = pin ?: return false
        if (!target.origin.matches(hostname, session.peerPort)) return false
        val leaf = leafOf(session) ?: return false
        if (!leaf.sha256Fingerprint().constantTimeEquals(target.sha256)) return false
        return try {
            leaf.checkValidity(Date(clock()))
            true
        } catch (e: CertificateException) {
            Log.w(TAG, "Pinned certificate is outside its validity period: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun leafOf(session: SSLSession): X509Certificate? =
        try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (e: SSLException) {
            Log.w(TAG, "No peer certificate: ${e.javaClass.simpleName}")
            null
        }

    private companion object {
        const val TAG = "PinAwareHostnameVerifier"
    }
}
