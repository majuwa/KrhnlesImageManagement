package de.majuwa.android.paper.krhnlesimagemanagement.tls

import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateCapture
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateChangedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinAwareTrustManager
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.serverHttpClientBuilder
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.sha256Fingerprint
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class PinAwareTrustManagerTest {
    private lateinit var server: MockWebServer
    private val ca =
        HeldCertificate
            .Builder()
            .certificateAuthority(0)
            .commonName("Test CA")
            .build()
    private val systemTrust =
        HandshakeCertificates
            .Builder()
            .addTrustedCertificate(ca.certificate)
            .build()
            .trustManager

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun selfSigned(
        vararg names: String,
        notAfterOffsetMs: Long = DAY,
        notBeforeOffsetMs: Long = -DAY,
    ): HeldCertificate {
        val now = System.currentTimeMillis()
        return HeldCertificate
            .Builder()
            .commonName("self-signed")
            .apply { names.forEach(::addSubjectAlternativeName) }
            .validityInterval(now + notBeforeOffsetMs, now + notAfterOffsetMs)
            .build()
    }

    private fun serve(cert: HeldCertificate) {
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server.useHttps(handshake.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
    }

    private fun pinFor(
        cert: HeldCertificate,
        host: String = "localhost",
        port: Int = server.port,
    ) = PinnedCertificate(ServerOrigin(host, port), cert.certificate.sha256Fingerprint())

    private fun client(
        pin: PinnedCertificate?,
        capture: CertificateCapture? = null,
    ): OkHttpClient =
        serverHttpClientBuilder(PinAwareTrustManager(pin, capture, systemTrust), pin)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress())
                },
            ).connectTimeout(5, TimeUnit.SECONDS)
            .build()

    private fun get(
        client: OkHttpClient,
        host: String = "localhost",
        port: Int = server.port,
    ): String =
        client
            .newCall(Request.Builder().url("https://$host:$port/").build())
            .execute()
            .use { it.body.string() }

    private fun assertFails(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: SSLException) {
            return e
        }
        fail("expected an SSLException")
        error("unreachable")
    }

    private fun causes(t: Throwable) = generateSequence(t) { it.cause }

    @Test
    fun `system trusted certificate is accepted`() {
        val leaf =
            HeldCertificate
                .Builder()
                .commonName(
                    "localhost",
                ).addSubjectAlternativeName("localhost")
                .signedBy(ca)
                .build()
        serve(leaf)
        assertEquals("ok", get(client(null)))
    }

    @Test
    fun `self signed without pin is rejected and captured`() {
        serve(selfSigned("localhost"))
        val capture = CertificateCapture(ServerOrigin("localhost", server.port))
        assertFails { get(client(null, capture)) }
        assertNotNull(capture.value)
        assertEquals(false, capture.value!!.changed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `pinned leaf is accepted on configured host and port`() {
        val leaf = selfSigned("localhost")
        serve(leaf)
        val capture = CertificateCapture(ServerOrigin("localhost", server.port))
        assertEquals("ok", get(client(pinFor(leaf), capture)))
        assertNull(capture.value)
    }

    @Test
    fun `pinned leaf without matching SAN is accepted on the configured host`() {
        val leaf = selfSigned()
        serve(leaf)
        assertEquals("ok", get(client(pinFor(leaf))))
    }

    @Test
    fun `different self signed certificate is reported as changed`() {
        val pinned = selfSigned("localhost")
        serve(selfSigned("localhost"))
        val capture = CertificateCapture(ServerOrigin("localhost", server.port))
        val failure = assertFails { get(client(pinFor(pinned), capture)) }
        assertTrue(causes(failure).any { it is CertificateChangedException })
        assertEquals(true, capture.value!!.changed)
        assertEquals(pinFor(pinned).sha256, capture.value!!.previousSha256)
    }

    @Test
    fun `pinned leaf on another host is rejected`() {
        val leaf = selfSigned("localhost", "other.test")
        serve(leaf)
        assertFails { get(client(pinFor(leaf)), host = "other.test") }
    }

    @Test
    fun `pinned leaf on another port is rejected`() {
        val leaf = selfSigned("localhost")
        serve(leaf)
        assertFails { get(client(pinFor(leaf, port = server.port + 1))) }
    }

    @Test
    fun `expired pinned leaf is rejected`() {
        val leaf = selfSigned("localhost", notAfterOffsetMs = -1_000L, notBeforeOffsetMs = -DAY)
        serve(leaf)
        assertFails { get(client(pinFor(leaf))) }
    }

    @Test
    fun `not yet valid pinned leaf is rejected`() {
        val leaf = selfSigned("localhost", notBeforeOffsetMs = DAY, notAfterOffsetMs = 2 * DAY)
        serve(leaf)
        assertFails { get(client(pinFor(leaf))) }
    }

    @Test
    fun `system trusted certificate with wrong SAN is rejected`() {
        val leaf =
            HeldCertificate
                .Builder()
                .commonName(
                    "x",
                ).addSubjectAlternativeName("elsewhere.test")
                .signedBy(ca)
                .build()
        serve(leaf)
        assertFails { get(client(null)) }
    }

    @Test
    fun `second client after pin removal fails like a client that never trusted the pin`() {
        val leaf = selfSigned("localhost")
        serve(leaf)
        assertEquals("ok", get(client(pinFor(leaf))))
        assertFails { get(client(null)) }
    }

    @Test
    fun `capture ignores other hosts`() {
        val leaf = selfSigned("localhost", "other.test")
        serve(leaf)
        val capture = CertificateCapture(ServerOrigin("elsewhere.test", server.port))
        assertFails { get(client(null, capture), host = "other.test") }
        assertNull(capture.value)
    }

    @Test
    fun `origins only exist for https`() {
        assertNull(ServerOrigin.of("http://localhost:8080/"))
        assertNotNull(ServerOrigin.of("https://Localhost/"))
        assertEquals(443, ServerOrigin.of("https://localhost")!!.port)
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
