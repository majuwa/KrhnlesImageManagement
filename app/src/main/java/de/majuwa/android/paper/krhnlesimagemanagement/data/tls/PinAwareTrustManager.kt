package de.majuwa.android.paper.krhnlesimagemanagement.data.tls

import android.annotation.SuppressLint
import android.util.Log
import okio.ByteString
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** What a [CertificateCapture] recorded for the capture origin. */
data class CapturedCertificate(
    val certificate: ServerCertificate,
    val changed: Boolean,
    val previousSha256: ByteString?,
)

/**
 * Records the first untrusted leaf certificate presented by [origin] during a handshake so it can be
 * shown for review. Recording never changes the outcome: the handshake still fails.
 */
class CertificateCapture(
    val origin: ServerOrigin,
) {
    private val captured = AtomicReference<CapturedCertificate?>(null)

    val value: CapturedCertificate? get() = captured.get()

    internal fun record(value: CapturedCertificate) {
        captured.compareAndSet(null, value)
    }
}

/**
 * The only custom trust manager of the app: system trust first (the platform trust manager, which
 * keeps the network security configuration), and only if that rejects the chain, the one exact
 * pinned leaf for exactly the pinned host and port while it is currently valid. Everything else is
 * rejected; a pin that is present for the peer but does not match is reported as
 * [CertificateChangedException]. An optional [capture] records the rejected leaf of its origin and
 * always rethrows. The non-extended overload (no peer host known), a missing handshake session, a
 * null chain and a delegate that is no [X509ExtendedTrustManager] all fail closed (system trust only).
 *
 * Lint's `CustomX509TrustManager` is suppressed here with the user's explicit approval (design D10);
 * a `NoTrustAllRegressionTest` asserts that this is the only suppression.
 */
@SuppressLint("CustomX509TrustManager")
internal class PinAwareTrustManager(
    private val pin: PinnedCertificate?,
    private val capture: CertificateCapture? = null,
    private val delegate: X509TrustManager = platformTrustManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
        try {
            extendedDelegate()?.checkServerTrusted(chain, authType, socket)
                ?: delegate.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            val session = (socket as? SSLSocket)?.handshakeSession
            onRejected(chain, session, e)
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
        try {
            extendedDelegate()?.checkServerTrusted(chain, authType, engine)
                ?: delegate.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            onRejected(chain, engine?.handshakeSession, e)
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = delegate.checkServerTrusted(chain, authType)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = checkClient(chain, authType) { extendedDelegate()?.checkClientTrusted(chain, authType, socket) }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = checkClient(chain, authType) { extendedDelegate()?.checkClientTrusted(chain, authType, engine) }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = delegate.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun extendedDelegate(): X509ExtendedTrustManager? = delegate as? X509ExtendedTrustManager

    private fun checkClient(
        chain: Array<out X509Certificate>?,
        authType: String?,
        extended: () -> Unit?,
    ) {
        extended() ?: delegate.checkClientTrusted(chain, authType)
    }

    /** The delegate rejected [chain]: accept only the exact pinned leaf, otherwise rethrow (and capture). */
    private fun onRejected(
        chain: Array<out X509Certificate>?,
        session: SSLSession?,
        original: CertificateException,
    ) {
        val leaf = chain?.firstOrNull()
        val peerHost = session?.peerHost
        val peerPort = session?.peerPort ?: -1
        if (leaf == null || peerHost == null || extendedDelegate() == null) throw original
        val pinForPeer = pin?.takeIf { it.origin.matches(peerHost, peerPort) }
        val fingerprint = leaf.sha256Fingerprint()
        val matchesPin = pinForPeer != null && fingerprint.constantTimeEquals(pinForPeer.sha256)
        if (matchesPin && isCurrentlyValid(leaf)) return
        val failure = if (pinForPeer != null && !matchesPin) CertificateChangedException(original) else original
        record(leaf, peerHost, peerPort, changed = pinForPeer != null && !matchesPin, pinForPeer)
        throw failure
    }

    private fun isCurrentlyValid(leaf: X509Certificate): Boolean =
        try {
            leaf.checkValidity(java.util.Date(clock()))
            true
        } catch (e: CertificateException) {
            Log.w(TAG, "Pinned certificate is outside its validity period: ${e.javaClass.simpleName}")
            false
        }

    private fun record(
        leaf: X509Certificate,
        peerHost: String,
        peerPort: Int,
        changed: Boolean,
        pinForPeer: PinnedCertificate?,
    ) {
        val target = capture ?: return
        if (!target.origin.matches(peerHost, peerPort)) return
        target.record(CapturedCertificate(ServerCertificate.of(target.origin, leaf), changed, pinForPeer?.sha256))
    }

    private companion object {
        const val TAG = "PinAwareTrustManager"
    }
}

/** The platform's default trust manager (the network-security-config aware root trust manager on Android). */
internal val platformTrustManager: X509TrustManager by lazy {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
