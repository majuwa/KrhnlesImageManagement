package de.majuwa.android.paper.krhnlesimagemanagement.data.tls

import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext

/** The trust inputs of a server client: the [pin] for the configured origin and an optional [capture]. */
data class ServerTrust(
    val pin: PinnedCertificate?,
    val capture: CertificateCapture? = null,
)

/**
 * The only place that builds a server-facing [OkHttpClient]: system trust OR the pinned leaf of the
 * pinned origin (see [PinAwareTrustManager] / [PinAwareHostnameVerifier]). Each call creates its own
 * `SSLContext`, so a client never shares TLS sessions with one built under another pin, and OkHttp
 * never pools connections across them.
 */
fun serverHttpClientBuilder(trust: ServerTrust): OkHttpClient.Builder =
    serverHttpClientBuilder(PinAwareTrustManager(trust.pin, trust.capture), trust.pin)

/**
 * This client (same interceptors, timeouts, pool) with a new trust snapshot [trust], e.g. a capturing
 * one for a single connection test. The TLS setup is replaced entirely, never merged.
 */
fun OkHttpClient.withServerTrust(trust: ServerTrust): OkHttpClient {
    val trustManager = PinAwareTrustManager(trust.pin, trust.capture)
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(trustManager), null)
    return newBuilder()
        .sslSocketFactory(context.socketFactory, trustManager)
        .hostnameVerifier(PinAwareHostnameVerifier(trust.pin))
        .build()
}

internal fun serverHttpClientBuilder(
    trustManager: PinAwareTrustManager,
    pin: PinnedCertificate?,
): OkHttpClient.Builder {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(trustManager), null)
    return OkHttpClient
        .Builder()
        .sslSocketFactory(context.socketFactory, trustManager)
        .hostnameVerifier(PinAwareHostnameVerifier(pin))
}
