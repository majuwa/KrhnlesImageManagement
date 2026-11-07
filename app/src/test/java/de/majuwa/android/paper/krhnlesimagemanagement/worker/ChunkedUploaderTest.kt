package de.majuwa.android.paper.krhnlesimagemanagement.worker

import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateChangedException
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.tls.SelfSignedServer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * [ChunkedUploader] on its own (review of #69): which requests a failing attempt sends for a saved
 * transfer, and that a non-I/O exception of the provider fails the file instead of escaping.
 */
@RunWith(RobolectricTestRunner::class)
class ChunkedUploaderTest {
    private val server = MockWebServer()
    private val methods = CopyOnWriteArrayList<String>()
    private val persisted = CopyOnWriteArrayList<ChunkedTransferState?>()
    private val content = ByteArray(25) { it.toByte() }
    private lateinit var client: WebDavClient
    private lateinit var request: ChunkedRequest
    private lateinit var binding: String

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    methods += request.method.orEmpty()
                    val code =
                        when (request.method) {
                            "MKCOL" -> 405

                            // the saved upload folder still exists
                            "DELETE" -> 204

                            else -> 201
                        }
                    return MockResponse().setResponseCode(code)
                }
            }
        server.start()
        client = WebDavClient(WebDavConfig(server.url("/remote.php/dav/files/u/").toString(), "u", "p", ""), null)
        val locations = checkNotNull(client.chunkedLocations("Trip", "clip.mp4"))
        request = ChunkedRequest("Trip", "clip.mp4", locations, requestServerHash = false)
        binding = chunkedBinding(locations.collectionUrl, locations.destinationUrl)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun saved(
        binding: String,
        confirmedChunks: Int = 1,
    ) = ChunkedTransferState(
        transferId = "krhnles-0123456789abcdef0123456789abcdef",
        chunkBytes = 10L,
        totalBytes = 25L,
        confirmedChunks = confirmedChunks,
        prefixSha256 = sha256(content.copyOf(10)),
        original = false,
        binding = binding,
    )

    private fun uploader(
        saved: ChunkedTransferState?,
        open: suspend () -> OpenedEntry?,
    ) = ChunkedUploader(
        client,
        ChunkedUploadSettings(
            policy = ChunkedUploadPolicy(thresholdBytes = 20L, chunkBytes = 10L),
            sleep = {},
            abortTimeoutMs = 5_000L,
            cancelAbortTimeoutMs = 5_000L,
        ),
        request,
        ChunkedCallbacks(
            open = open,
            lengthOf = { content.size.toLong() },
            beginAttempt = {},
            persist = { persisted += it },
            chunkConfirmed = { true },
        ),
        saved,
    )

    private fun opened(stream: InputStream) = OpenedEntry(UploadSource.Plain(stream), before = null, original = null)

    @Test
    fun unopenableFile_withATransferOfAnotherBinding_sendsNoRequest_andClearsTheProgress() {
        val uploader = uploader(saved("cd".repeat(32))) { null }

        val result = runBlocking { uploader.upload() }

        assertTrue(result is ChunkedResult.Failed)
        assertEquals(0, server.requestCount)
        assertNull(uploader.state)
        assertEquals(listOf<ChunkedTransferState?>(null), persisted)
    }

    @Test
    fun unopenableFile_withATransferOfThisBinding_deletesIt() {
        val uploader = uploader(saved(binding)) { null }

        val result = runBlocking { uploader.upload() }

        assertTrue(result is ChunkedResult.Failed)
        assertEquals(listOf("DELETE"), methods)
        assertNull(uploader.state)
    }

    @Test
    fun securityExceptionWhileThePrefixIsReRead_failsTheFile_andDeletesTheTransfer() {
        val uploader = uploader(saved(binding)) { opened(ThrowingAfter(content, 4, SecurityException("revoked"))) }

        val result = runBlocking { uploader.upload() }

        assertTrue(result.toString(), result is ChunkedResult.Failed)
        assertTrue((result as ChunkedResult.Failed).cause is SecurityException)
        assertEquals(listOf("MKCOL", "DELETE"), methods)
        assertNull(uploader.state)
        assertEquals(listOf<ChunkedTransferState?>(null), persisted)
    }

    @Test
    fun securityExceptionFromOpen_failsTheFile() {
        val uploader = uploader(saved(binding)) { throw SecurityException("revoked") }

        val result = runBlocking { uploader.upload() }

        assertTrue((result as ChunkedResult.Failed).cause is SecurityException)
        assertEquals(listOf("DELETE"), methods)
    }

    @Test
    fun resumeWithAMatchingPrefix_sendsOnlyTheMissingChunks() {
        val uploader = uploader(saved(binding)) { opened(ByteArrayInputStream(content)) }

        val result = runBlocking { uploader.upload() }

        assertTrue(result is ChunkedResult.Uploaded)
        assertEquals(listOf("MKCOL", "PUT", "PUT", "MOVE"), methods)
    }

    @Test
    fun certificateFailures_areNotTransient_butAPlainSslResetIs() {
        val changed = CertificateChangedException(CertificateException("x"))
        assertFalse(SSLHandshakeException("x").apply { initCause(changed) }.isTransientChunkFailure())
        assertFalse(changed.isTransientChunkFailure())
        assertFalse(CertificateException("x").isTransientChunkFailure())
        assertFalse(SSLHandshakeException("x").isTransientChunkFailure())
        assertFalse(SSLPeerUnverifiedException("x").isTransientChunkFailure())
        assertFalse(java.io.IOException("wrapped", changed).isTransientChunkFailure())
        // A connection reset while reading is an SSLException too and must stay retryable.
        assertTrue(SSLException("Connection reset").isTransientChunkFailure())
        assertTrue(java.io.IOException("offline").isTransientChunkFailure())
    }

    @Test
    fun untrustedServerCertificate_failsTheFileAtOnce_withoutRetriesOrAnInterruption() {
        val tls = SelfSignedServer()
        try {
            val tlsClient =
                WebDavClient(WebDavConfig(tls.url("/remote.php/dav/files/u/"), "u", "p", ""), null)
            val locations = checkNotNull(tlsClient.chunkedLocations("Trip", "clip.mp4"))
            val sleeps = mutableListOf<Long>()
            val uploader =
                ChunkedUploader(
                    tlsClient,
                    ChunkedUploadSettings(
                        policy = ChunkedUploadPolicy(thresholdBytes = 20L, chunkBytes = 10L),
                        sleep = { sleeps += it },
                        abortTimeoutMs = 5_000L,
                        cancelAbortTimeoutMs = 5_000L,
                    ),
                    ChunkedRequest("Trip", "clip.mp4", locations, requestServerHash = false),
                    ChunkedCallbacks(
                        open = { opened(ByteArrayInputStream(content)) },
                        lengthOf = { content.size.toLong() },
                        beginAttempt = {},
                        persist = {},
                        chunkConfirmed = { true },
                    ),
                    null,
                )

            val result = runBlocking { uploader.upload() }

            assertTrue(result.toString(), result is ChunkedResult.Failed)
            assertTrue(sleeps.isEmpty())
            assertEquals(0, tls.server.requestCount)
        } finally {
            tls.shutdown()
        }
    }

    /** Reads [bytes] normally for [okBytes] bytes, then throws [failure]. */
    private class ThrowingAfter(
        bytes: ByteArray,
        private val okBytes: Int,
        private val failure: RuntimeException,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var read = 0

        override fun read(): Int {
            if (read >= okBytes) throw failure
            read++
            return delegate.read()
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (read >= okBytes) throw failure
            val n = delegate.read(b, off, minOf(len, okBytes - read))
            if (n > 0) read += n
            return n
        }
    }

    private companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    }
}
