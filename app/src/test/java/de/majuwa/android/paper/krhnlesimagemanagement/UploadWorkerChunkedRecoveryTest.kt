package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.work.ListenableWorker
import de.majuwa.android.paper.krhnlesimagemanagement.ChunkedUploadHarness.Companion.bytes
import de.majuwa.android.paper.krhnlesimagemanagement.ChunkedUploadHarness.Companion.chunkedOf
import de.majuwa.android.paper.krhnlesimagemanagement.ChunkedUploadHarness.Companion.code
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateInfo
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.ChunkedUploadSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okio.ByteString.Companion.decodeHex
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chunked upload in the worker, cases from the review of #69: transient failures keep the progress
 * and interrupt the run (design D6), a lost upload at the `MOVE` restarts the file at most once per
 * run (D5), and saved transfers are aborted on a cancel before the configuration is known and on a
 * folder-creation failure (D7 / D8). Same harness and tiny policy as [UploadWorkerChunkedTest].
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerChunkedRecoveryTest {
    private val h = ChunkedUploadHarness()
    private val content = bytes(25)

    @Before
    fun setUp() = h.setUp()

    @After
    fun tearDown() = h.tearDown()

    private fun singleVideoQueue(interruptions: Int = 0) =
        h.queueFile(h.video(1, content), interruptions = interruptions)

    @Test
    fun threeTransient503s_keepTheProgress_andRetryTheRunAsAnInterruption() {
        val queue = singleVideoQueue()
        h.override = { if (it.method == "PUT" && it.name == "00002") code(503) else null }

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(listOf("00001", "00002", "00002", "00002"), h.chunkPuts().map { it.name })
        assertEquals(listOf(2_000L, 8_000L), h.sleeps)
        assertTrue(h.of("DELETE").isEmpty())
        val json = JSONObject(queue.readText())
        assertEquals(1, json.getInt("interruptions"))
        assertEquals(1, chunkedOf(queue.readText())!!.getInt("confirmedChunks"))
        assertTrue(h.retryFiles().isEmpty())
        assertTrue(h.resultNotifications().isEmpty())
        assertTrue(h.uploadedIds().isEmpty())
    }

    /**
     * Regression (#69 review): a network outage longer than the in-run back-off (2 s + 8 s) used to
     * discard every confirmed chunk (DELETE + final failure). The progress is kept, the run is
     * retried and the next run sends only the missing chunks of the same transfer.
     */
    @Test
    fun networkOutage_keepsTheConfirmedChunks_andTheNextRunResumesThem() {
        val queue = singleVideoQueue()
        var offline = true
        h.override = {
            // A malformed status line is an IOException OkHttp never retries itself.
            if (offline && it.method == "PUT" && it.name == "00002") MockResponse().setStatus("garbage") else null
        }

        assertTrue(h.run(queue) is ListenableWorker.Result.Retry)

        val saved = chunkedOf(queue.readText())!!
        assertEquals(1, saved.getInt("confirmedChunks"))
        assertTrue(h.of("DELETE").isEmpty())
        assertTrue(h.retryFiles().isEmpty())
        offline = false
        h.requests.clear()

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("MKCOL", "PUT", "PUT", "MOVE"), h.uploadRequests().map { it.method })
        assertEquals(listOf("00002", "00003"), h.chunkPuts().map { it.name })
        assertTrue(h.uploadRequests().all { it.transferId == saved.getString("transferId") })
        assertTrue(content.contentEquals(h.serverFiles["/remote.php/dav/files/u/Trip/clip1.mp4"]))
        assertEquals(setOf(1L), h.uploadedIds())
    }

    @Test
    fun transientFailuresAtTheInterruptionCap_giveUp_deleteTheTransfer_retryFileWithoutProgress() {
        h.override = { if (it.method == "PUT" && it.name == "00001") code(503) else null }

        val result = h.run(singleVideoQueue(interruptions = 4))

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(3, h.chunkPuts().size)
        assertEquals(1, h.of("DELETE").size)
        assertTrue(h.uploadFolders.isEmpty())
        val retry = h.retryFiles().single()
        assertNull(chunkedOf(retry.readText()))
        assertFalse(retry.readText().contains("interruptions"))
        assertTrue(h.uploadedIds().isEmpty())
        assertEquals("Upload failed", h.title(h.resultNotifications().single()))
    }

    private fun assertLostAtMoveTwice_failsAfterOneRestart(status: Int) {
        h.override = { if (it.method == "MOVE") code(status) else null }

        val result = h.run(singleVideoQueue())

        assertTrue(result is ListenableWorker.Result.Success)
        // One restart of the whole file, then a final failure (no third transfer).
        assertEquals(2, h.of("MOVE").size)
        assertEquals(6, h.chunkPuts().size)
        assertEquals(
            2,
            h
                .uploadRequests()
                .map { it.transferId }
                .toSet()
                .size,
        )
        assertEquals(2, h.of("DELETE").size)
        assertTrue(h.uploadFolders.isEmpty())
        assertTrue(h.uploadedIds().isEmpty())
        assertNull(chunkedOf(h.retryFiles().single().readText()))
        assertEquals(
            h.context.resources.getQuantityString(R.plurals.notification_upload_partial, 0, 0, 1),
            h.text(h.resultNotifications().single()),
        )
    }

    @Test
    fun move404Twice_restartsTheFileOnce_thenFails() = assertLostAtMoveTwice_failsAfterOneRestart(404)

    @Test
    fun move409Twice_restartsTheFileOnce_thenFails() = assertLostAtMoveTwice_failsAfterOneRestart(409)

    @Test
    fun move412Twice_restartsTheFileOnce_thenFails() = assertLostAtMoveTwice_failsAfterOneRestart(412)

    @Test
    fun move412Once_restartsTheFile_andUploadsIt() {
        val moves = AtomicInteger()
        h.override = { if (it.method == "MOVE" && moves.getAndIncrement() == 0) code(412) else null }

        val result = h.run(singleVideoQueue())

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(2, h.of("MOVE").size)
        assertEquals(1, h.of("DELETE").size)
        assertEquals(setOf(1L), h.uploadedIds())
        assertTrue(content.contentEquals(h.serverFiles["/remote.php/dav/files/u/Trip/clip1.mp4"]))
    }

    /** Runs once with a refused foreground service after chunk 1; returns the saved transfer id. */
    private fun savedTransfer(queue: File): String {
        h.foregroundFailsFrom = 1
        h.run(queue)
        h.foregroundFailsFrom = null
        h.requests.clear()
        return chunkedOf(queue.readText())!!.getString("transferId")
    }

    private val pin = PinnedCertificate(ServerOrigin("cloud.example", 8443), "ab".repeat(32).decodeHex())
    private val seenPins = CopyOnWriteArrayList<PinnedCertificate?>()

    /** An `https` origin with a stored pin; the clients still talk to the mock server, named after their pin. */
    private fun withStoredPin(): FakeTrustedCertificateRepository {
        val info =
            TrustedCertificateInfo(
                ServerCertificate(pin.origin, pin.sha256, "CN=a", "CN=b", 0L, Long.MAX_VALUE / 2),
                1L,
            )
        h.credentialConfig = WebDavConfig("https://cloud.example:8443/remote.php/dav/files/u/", "u", "p", "")
        h.clientFactory = { _, p ->
            seenPins += p
            WebDavClient(WebDavConfig(h.config().url, if (p == null) "nopin" else "withpin", "p", ""), p)
        }
        return FakeTrustedCertificateRepository(info).also { h.trusted = it }
    }

    @Test
    fun pinRemovedAfterTheFirstEntry_theGiveUpAbortUsesTheClientWithoutThePin() {
        val trusted = withStoredPin()
        h.uploadedOverride =
            object : UploadedPhotosRepositoryContract by h.uploaded {
                override suspend fun markAsUploaded(photoIds: Set<Long>) {
                    h.uploaded.markAsUploaded(photoIds)
                    trusted.clear()
                }
            }
        h.override = { if (it.method == "PUT" && it.name == "00001") code(503) else null }
        // Entry 1 is one PUT; entry 2 starts a chunked transfer whose chunks fail transiently at the cap.
        val queue = h.queueFile(h.video(1, bytes(10)), h.video(2, content, "clip2.mp4"), interruptions = 4)

        val result = h.run(queue)

        assertTrue(result.toString(), result is ListenableWorker.Result.Failure)
        assertEquals(listOf<PinnedCertificate?>(pin, null), seenPins.toList())
        val delete = h.of("DELETE").single()
        assertEquals(Credentials.basic("nopin", "p"), delete.headers["Authorization"])
    }

    @Test
    fun userCancelBeforeTheConfigIsKnown_theAborterReadsAndUsesThePin() =
        assertCancelBeforeTheConfigIsKnown_deletesTheSavedTransfer(prepare = { withStoredPin() }) { repo, cancel ->
            object : CredentialRepository by repo {
                override val wifiOnly: Flow<Boolean> =
                    flow {
                        seenPins.clear()
                        cancel()
                        awaitCancellation()
                    }
            }
        }.also { assertEquals(listOf<PinnedCertificate?>(pin), seenPins.toList()) }

    private fun assertCancelBeforeTheConfigIsKnown_deletesTheSavedTransfer(
        prepare: () -> Unit = {},
        wrap: (CredentialRepository, suspend () -> Unit) -> CredentialRepository,
    ) {
        prepare()
        val queue = singleVideoQueue()
        val savedId = savedTransfer(queue)
        h.userCancelled = { true }
        val scope = CoroutineScope(Dispatchers.Default)
        val started = CompletableDeferred<Job>()
        h.credentials = { wrap(it) { started.await().cancel() } }

        val job = h.start(queue, scope)
        started.complete(job)
        runBlocking { job.join() }
        scope.cancel()

        assertTrue(job.isCancelled)
        assertEquals(listOf("DELETE"), h.requests.map { it.method })
        assertEquals(savedId, h.of("DELETE").single().transferId)
        assertNull(chunkedOf(h.retryFiles().single().readText()))
        assertEquals("Upload cancelled", h.title(h.resultNotifications().single()))
    }

    @Test
    fun userCancelWhileWifiOnlyIsRead_stillDeletesTheSavedTransfer() =
        assertCancelBeforeTheConfigIsKnown_deletesTheSavedTransfer { repo, cancel ->
            object : CredentialRepository by repo {
                override val wifiOnly: Flow<Boolean> =
                    flow {
                        cancel()
                        awaitCancellation()
                    }
            }
        }

    @Test
    fun userCancelWhileTheConfigIsRead_stillDeletesTheSavedTransfer() =
        assertCancelBeforeTheConfigIsKnown_deletesTheSavedTransfer { repo, cancel ->
            val reads = AtomicInteger()
            object : CredentialRepository by repo {
                override val webDavConfig: Flow<WebDavConfig> =
                    flow {
                        // Only the run's read is cancelled; the cancel path reads the config again.
                        if (reads.getAndIncrement() == 0) {
                            cancel()
                            awaitCancellation()
                        }
                        emitAll(repo.webDavConfig)
                    }
            }
        }

    @Test
    fun userCancelBeforeTheConfigIsKnown_sendsNothingForATransferOfAnotherAccount() {
        val queue = singleVideoQueue()
        savedTransfer(queue)
        h.serverPath = "/remote.php/dav/files/v/"
        h.userCancelled = { true }
        val scope = CoroutineScope(Dispatchers.Default)
        val started = CompletableDeferred<Job>()
        h.credentials = { repo ->
            object : CredentialRepository by repo {
                override val wifiOnly: Flow<Boolean> =
                    flow {
                        started.await().cancel()
                        awaitCancellation()
                    }
            }
        }

        val job = h.start(queue, scope)
        started.complete(job)
        runBlocking { job.join() }
        scope.cancel()

        assertTrue(h.requests.isEmpty())
        assertEquals("Upload cancelled", h.title(h.resultNotifications().single()))
    }

    @Test
    fun folderCreationFailure_deletesTheSavedTransfer_beforeTheFailureIsReported() {
        val queue = singleVideoQueue()
        val savedId = savedTransfer(queue)
        val notificationsAtDelete = mutableListOf<Int>()
        h.override = {
            when {
                it.method == "DELETE" -> {
                    notificationsAtDelete += h.resultNotifications().size
                    null
                }

                it.method == "MKCOL" && !it.path.startsWith(ChunkedUploadHarness.UPLOADS_PREFIX) -> {
                    code(403)
                }

                else -> {
                    null
                }
            }
        }

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(savedId, h.of("DELETE").single().transferId)
        assertEquals(listOf(0), notificationsAtDelete)
        assertTrue(h.uploadRequests().none { it.method != "DELETE" })
        assertEquals("Upload failed", h.title(h.resultNotifications().single()))
    }

    @Test
    fun savedTransferWithAnotherChunkSize_isDeleted_andStartedAgain() {
        val queue = singleVideoQueue()
        val oldId = savedTransfer(queue)
        h.settings =
            ChunkedUploadSettings(
                policy = h.settings.policy.copy(chunkBytes = 5L),
                sleep = { h.sleeps += it },
                abortTimeoutMs = 5_000L,
                cancelAbortTimeoutMs = 5_000L,
            )

        assertTrue(h.run(queue) is ListenableWorker.Result.Success)

        val uploads = h.uploadRequests()
        assertEquals("DELETE", uploads.first().method)
        assertEquals(oldId, uploads.first().transferId)
        assertTrue(uploads.drop(1).all { it.transferId != oldId })
        assertEquals(List(5) { 5 }, h.chunkPuts().map { it.body.size })
        assertTrue(content.contentEquals(h.serverFiles["/remote.php/dav/files/u/Trip/clip1.mp4"]))
    }
}
