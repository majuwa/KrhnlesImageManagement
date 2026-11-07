package de.majuwa.android.paper.krhnlesimagemanagement.tls

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress

/** A started HTTPS [MockWebServer] presenting a self-signed certificate for `localhost`. */
internal class SelfSignedServer(
    val certificate: HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(HOST)
            .addSubjectAlternativeName(HOST)
            .build(),
    responses: Int = 20,
    body: String = "ok",
) {
    val server = MockWebServer()

    init {
        server.useHttps(
            HandshakeCertificates
                .Builder()
                .heldCertificate(certificate)
                .build()
                .sslSocketFactory(),
            false,
        )
        repeat(responses) { server.enqueue(MockResponse().setBody(body)) }
        server.start(InetAddress.getByName(HOST), 0)
    }

    val port: Int get() = server.port

    fun url(path: String = "/"): String = "https://$HOST:$port$path"

    companion object {
        /** A literal address: independent of how `localhost` resolves inside the Robolectric sandbox. */
        const val HOST = "127.0.0.1"
    }

    fun shutdown() = server.shutdown()
}
