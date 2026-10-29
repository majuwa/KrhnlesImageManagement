package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.DownloadVerifier
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalDigest
import de.majuwa.android.paper.krhnlesimagemanagement.data.NextcloudHashVerifier
import de.majuwa.android.paper.krhnlesimagemanagement.data.NoVerifier
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemoteDigest
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemoteFile
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemoteFileTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadResponse
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadVerifier
import de.majuwa.android.paper.krhnlesimagemanagement.data.Verification
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.data.verifierFor
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class UploadVerifierTest {
    private val target = RemoteFile("Photos/2026/05/14", "a.jpg")
    private val hash = "ab".repeat(32)
    private val local = LocalDigest(hash, 100L)

    private class RecordingDownload(
        private val result: Result<RemoteDigest>,
    ) {
        var calls = 0
        var lastMax = -1L

        val verifier =
            DownloadVerifier { _, _, max ->
                calls++
                lastMax = max
                result
            }
    }

    // ── NextcloudHashVerifier ───────────────────────────────────────────────

    @Test
    fun `nextcloud verifies a matching server hash case-insensitively without a request`() =
        runTest {
            val fallback = RecordingDownload(Result.success(RemoteDigest(hash, 100L)))
            val verifier = NextcloudHashVerifier(fallback.verifier)

            assertTrue(verifier.requestsServerHash)
            assertEquals(Verification.Verified, verifier.verify(target, local, UploadResponse(hash)))
            assertEquals(Verification.Verified, verifier.verify(target, local, UploadResponse(hash.uppercase())))
            assertEquals(0, fallback.calls)
        }

    @Test
    fun `nextcloud reports a different server hash as mismatch and does not fall back`() =
        runTest {
            val fallback = RecordingDownload(Result.success(RemoteDigest(hash, 100L)))
            val verifier = NextcloudHashVerifier(fallback.verifier)

            assertEquals(Verification.Mismatch, verifier.verify(target, local, UploadResponse("cd".repeat(32))))
            assertEquals(0, fallback.calls)
        }

    @Test
    fun `nextcloud without a server hash falls back to the download when there is one`() =
        runTest {
            val fallback = RecordingDownload(Result.success(RemoteDigest(hash, 100L)))
            assertEquals(
                Verification.Verified,
                NextcloudHashVerifier(fallback.verifier).verify(target, local, UploadResponse(null)),
            )
            assertEquals(1, fallback.calls)
            assertEquals(100L, fallback.lastMax)
        }

    @Test
    fun `nextcloud treats a malformed server hash like a missing one`() =
        runTest {
            assertEquals(
                Verification.Unavailable,
                NextcloudHashVerifier().verify(target, local, UploadResponse("not-a-hash")),
            )
        }

    @Test
    fun `nextcloud without a server hash and without fallback is unavailable`() =
        runTest {
            assertEquals(Verification.Unavailable, NextcloudHashVerifier().verify(target, local, UploadResponse(null)))
        }

    // ── DownloadVerifier ────────────────────────────────────────────────────

    @Test
    fun `download verifies matching hash and size and requests no server hash`() =
        runTest {
            val download = RecordingDownload(Result.success(RemoteDigest(hash.uppercase(), 100L)))
            assertFalse(download.verifier.requestsServerHash)
            assertEquals(Verification.Verified, download.verifier.verify(target, local, UploadResponse(null)))
        }

    @Test
    fun `download reports a different hash or size as mismatch`() =
        runTest {
            assertEquals(
                Verification.Mismatch,
                RecordingDownload(Result.success(RemoteDigest("cd".repeat(32), 100L)))
                    .verifier
                    .verify(target, local, UploadResponse(null)),
            )
            assertEquals(
                Verification.Mismatch,
                RecordingDownload(Result.success(RemoteDigest(hash, 99L)))
                    .verifier
                    .verify(target, local, UploadResponse(null)),
            )
        }

    @Test
    fun `download oversize is a mismatch`() =
        runTest {
            assertEquals(
                Verification.Mismatch,
                RecordingDownload(Result.failure(RemoteFileTooLargeException()))
                    .verifier
                    .verify(target, local, UploadResponse(null)),
            )
        }

    @Test
    fun `download I O errors, timeouts and non-2xx are inconclusive`() =
        runTest {
            listOf(
                IOException("boom"),
                SocketTimeoutException("slow"),
                WebDavHttpException(404, "gone"),
                WebDavHttpException(302, "moved"),
                IllegalArgumentException("bad segment"),
            ).forEach { failure ->
                assertEquals(
                    failure.toString(),
                    Verification.Inconclusive,
                    RecordingDownload(Result.failure(failure)).verifier.verify(target, local, UploadResponse(null)),
                )
            }
        }

    @Test
    fun `no verifier is unavailable and requests nothing`() =
        runTest {
            assertFalse(NoVerifier.requestsServerHash)
            assertEquals(Verification.Unavailable, NoVerifier.verify(target, local, UploadResponse(hash)))
        }

    // ── verifierFor ─────────────────────────────────────────────────────────

    private val nextcloud = WebDavConfig("https://cloud.example.com/remote.php/dav/files/u/", "u", "p")
    private val plain = WebDavConfig("https://dav.example.com/files/", "u", "p")

    private fun select(
        config: WebDavConfig,
        download: Boolean,
        sentinel: UploadVerifier,
    ): UploadVerifier = verifierFor(config, download) { sentinel }

    @Test
    fun `plain webdav without the download setting is not verified`() {
        assertSame(NoVerifier, select(plain, false, NoVerifier))
    }

    @Test
    fun `plain webdav with the download setting uses the download strategy`() {
        val download = RecordingDownload(Result.failure(IOException())).verifier
        assertSame(download, select(plain, true, download))
        assertFalse(download.requestsServerHash)
    }

    @Test
    fun `nextcloud requests the server hash with or without the download setting`() =
        runTest {
            val off = select(nextcloud, false, NoVerifier)
            assertTrue(off is NextcloudHashVerifier)
            assertTrue(off.requestsServerHash)
            // No fallback: a missing header is unavailable.
            assertEquals(Verification.Unavailable, off.verify(target, local, UploadResponse(null)))

            val download = RecordingDownload(Result.success(RemoteDigest(hash, 100L)))
            val on = select(nextcloud, true, download.verifier)
            assertTrue(on is NextcloudHashVerifier)
            // Fallback: a missing header downloads.
            assertEquals(Verification.Verified, on.verify(target, local, UploadResponse(null)))
            assertEquals(1, download.calls)
        }
}
