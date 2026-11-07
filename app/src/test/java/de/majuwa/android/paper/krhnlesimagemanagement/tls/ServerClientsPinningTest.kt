package de.majuwa.android.paper.krhnlesimagemanagement.tls

import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateReviewException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedClientHolder
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerTrust
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.serverHttpClientBuilder
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.sha256Fingerprint
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.net.ssl.SSLException

/** Every server client kind applies the same pin: the pinned origin is accepted, another origin is rejected. */
@RunWith(RobolectricTestRunner::class)
class ServerClientsPinningTest {
    private val servers = mutableListOf<SelfSignedServer>()

    @After
    fun tearDown() = servers.forEach { it.shutdown() }

    private fun start(
        certificate: HeldCertificate? = null,
        body: String = "ok",
    ): SelfSignedServer =
        (if (certificate != null) SelfSignedServer(certificate, body = body) else SelfSignedServer(body = body))
            .also(servers::add)

    private fun pin(
        server: SelfSignedServer,
        port: Int = server.port,
    ) = PinnedCertificate(ServerOrigin(SelfSignedServer.HOST, port), server.certificate.certificate.sha256Fingerprint())

    private fun config(server: SelfSignedServer) = WebDavConfig(server.url("/remote.php/dav/files/user/"), "user", "pw")

    private fun get(
        client: OkHttpClient,
        server: SelfSignedServer,
    ): Int = client.newCall(Request.Builder().url(server.url()).build()).execute().use { it.code }

    private fun clients(pin: PinnedCertificate?): Map<String, OkHttpClient> {
        val webDav = WebDavClient(WebDavConfig("https://localhost/", "u", "p"), pin)
        return mapOf(
            "webdav" to webDav.httpClient,
            "upload" to webDav.uploadClient,
            "assembly" to webDav.assemblyClient,
            "factory" to serverHttpClientBuilder(ServerTrust(pin)).build(),
            "holder" to PinnedClientHolder({ pin }) { serverHttpClientBuilder(ServerTrust(it)).build() }.client(),
        )
    }

    @Test
    fun `pinned origin is accepted by every client kind`() {
        val server = start()
        clients(pin(server)).forEach { (kind, client) -> assertEquals(kind, 200, get(client, server)) }
    }

    @Test
    fun `another origin is rejected by every client kind`() {
        val server = start()
        clients(pin(server, port = server.port + 1)).forEach { (kind, client) ->
            val failed = runCatching { get(client, server) }.exceptionOrNull()
            assertTrue("$kind: $failed", failed is SSLException)
        }
    }

    @Test
    fun `no pin rejects a self signed server for every client kind`() {
        val server = start()
        clients(null).forEach { (kind, client) ->
            assertTrue(kind, runCatching { get(client, server) }.exceptionOrNull() is SSLException)
        }
        assertEquals(0, server.server.requestCount)
    }

    @Test
    fun `albums repository lists over a pinned connection and fails without the pin`() =
        runBlocking {
            val xml =
                "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response>" +
                    "<d:href>/remote.php/dav/files/user/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/>" +
                    "</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                    "</d:response></d:multistatus>"
            val server = start(body = xml)
            assertTrue(AlbumsRepository(config(server), pin(server)).listAlbums().isSuccess)
            assertFalse(AlbumsRepository(config(server), null).listAlbums().isSuccess)
        }

    @Test
    fun `connection test of a pinned server succeeds`() =
        runBlocking {
            val server = start()
            assertTrue(WebDavClient(config(server), pin(server)).testConnection().isSuccess)
        }

    @Test
    fun `connection test of an unpinned self signed server carries the certificate and sends nothing`() =
        runBlocking {
            val server = start()
            val failure = WebDavClient(config(server), null).testConnection().exceptionOrNull()
            val review = failure as CertificateReviewException
            assertEquals(false, review.changed)
            assertEquals(server.certificate.certificate.sha256Fingerprint(), review.certificate.sha256)
            assertEquals(ServerOrigin(SelfSignedServer.HOST, server.port), review.certificate.origin)
            assertEquals(0, server.server.requestCount)
        }

    @Test
    fun `connection test after the certificate was replaced reports the previous fingerprint`() =
        runBlocking {
            val server = start()
            val old =
                HeldCertificate
                    .Builder()
                    .commonName("localhost")
                    .addSubjectAlternativeName("localhost")
                    .build()
            val oldPin =
                PinnedCertificate(ServerOrigin(SelfSignedServer.HOST, server.port), old.certificate.sha256Fingerprint())
            val review =
                WebDavClient(
                    config(server),
                    oldPin,
                ).testConnection().exceptionOrNull() as CertificateReviewException
            assertEquals(true, review.changed)
            assertEquals(oldPin.sha256, review.previousSha256)
            assertEquals(server.certificate.certificate.sha256Fingerprint(), review.certificate.sha256)
        }

    @Test
    fun `holder keeps the client for the same pin and evicts the pool for a new one`() {
        val server = start()
        var current: PinnedCertificate? = pin(server)
        val holder = PinnedClientHolder({ current }) { serverHttpClientBuilder(ServerTrust(it)).build() }
        val first = holder.client()
        assertSame(first, holder.client())
        assertEquals(200, get(first, server))
        assertNotNull(first.connectionPool.connectionCount().takeIf { it > 0 })
        current = null
        val second = holder.client()
        assertNotSame(first, second)
        assertEquals(0, first.connectionPool.connectionCount())
        assertTrue(runCatching { get(second, server) }.exceptionOrNull() is SSLException)
    }
}
