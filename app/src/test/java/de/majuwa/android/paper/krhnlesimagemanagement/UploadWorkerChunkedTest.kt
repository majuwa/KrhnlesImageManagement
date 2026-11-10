package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.work.ListenableWorker
import de.majuwa.android.paper.krhnlesimagemanagement.ChunkedUploadHarness.Companion.bytes
import de.majuwa.android.paper.krhnlesimagemanagement.ChunkedUploadHarness.Companion.chunkedOf
import de.majuwa.android.paper.krhnlesimagemanagement.ChunkedUploadHarness.Companion.code
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadTarget
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.worker.ChunkedUploadSettings
import de.majuwa.android.paper.krhnlesimagemanagement.worker.EMPTY_SHA256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nextcloud chunked upload in the worker (issue #69, design D5–D11): protocol, persisted progress
 * and resume, attempts within a run, fallbacks, cleanup of abandoned transfers and the user cancel.
 * Policy: threshold 20 bytes, chunks of 10 bytes, so a 25-byte video is sent as 10 + 10 + 5.
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerChunkedTest {
    private val h = ChunkedUploadHarness()
    private val content = bytes(25)

    @Before
    fun setUp() = h.setUp()

    @After
    fun tearDown() = h.tearDown()

    private fun singleVideoQueue(interruptions: Int = 0) =
        h.queueFile(h.video(1, content), interruptions = interruptions)

    @Test
    fun happyPath_mkcolThreeChunksAndMove_markedUploaded_noProgressLeft() {
        val queue = h.queueFile(h.video(1, content), h.video(2, bytes(3)))

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        val uploads = h.uploadRequests()
        assertEquals(listOf("MKCOL", "PUT", "PUT", "PUT", "MOVE"), uploads.map { it.method })
        assertEquals(listOf("00001", "00002", "00003", ".file"), uploads.drop(1).map { it.name })
        assertEquals(1, uploads.map { it.transferId }.toSet().size)
        val chunks = h.chunkPuts()
        assertEquals(listOf(10, 10, 5), chunks.map { it.body.size })
        assertTrue(content.contentEquals(chunks.fold(ByteArray(0)) { acc, r -> acc + r.body }))
        val destination = h.server.url("/remote.php/dav/files/u/Trip/clip1.mp4").toString()
        uploads.forEach {
            assertEquals(destination, it.headers["Destination"])
            assertEquals("25", it.headers["OC-Total-Length"])
        }
        assertEquals(listOf("10", "10", "5"), chunks.map { it.headers["Content-Length"] })
        assertTrue(content.contentEquals(h.serverFiles["/remote.php/dav/files/u/Trip/clip1.mp4"]))
        assertEquals(setOf(1L, 2L), h.uploadedIds())
        // The small second file is a single PUT; when it arrives, the first entry is booked without progress.
        val smallPut = h.filePuts().single()
        assertTrue(smallPut.path.endsWith("/clip2.mp4"))
        assertNull(chunkedOf(smallPut.queue, 0))
        assertTrue(JSONObject(smallPut.queue!!).getJSONArray("photos").getJSONObject(0).getBoolean("uploaded"))
        assertTrue(h.of("DELETE").isEmpty())
    }

    @Test
    fun progressIsSavedBeforeTheMkcolAndAfterEveryChunk() {
        h.run(singleVideoQueue())

        val uploads = h.uploadRequests()
        val mkcolState = chunkedOf(uploads[0].queue)
        assertNotNull("the new transfer is saved before its MKCOL", mkcolState)
        assertEquals(0, mkcolState!!.getInt("confirmedChunks"))
        assertEquals(uploads[0].transferId, mkcolState.getString("transferId"))
        assertEquals(EMPTY_SHA256, mkcolState.getString("prefixSha256"))
        assertEquals(listOf(0, 1, 2), uploads.drop(1).take(3).map { chunkedOf(it.queue)!!.getInt("confirmedChunks") })
        val beforeMove = chunkedOf(uploads.last().queue)!!
        assertEquals(3, beforeMove.getInt("confirmedChunks"))
        assertEquals(ChunkedUploadHarness.sha256(content), beforeMove.getString("prefixSha256"))
        assertFalse(beforeMove.getBoolean("original"))
    }

    @Test
    fun chunkPercentagesAreShownWithTheBatchCount() {
        h.run(singleVideoQueue())

        assertTrue(h.foregroundTexts.toString(), h.foregroundTexts.contains("0 / 1 · 40 %"))
        assertTrue(h.foregroundTexts.toString(), h.foregroundTexts.contains("0 / 1 · 80 %"))
        // No percentage after the last chunk.
        assertFalse(h.foregroundTexts.any { it.contains("100 %") })
    }

    @Test
    fun belowThreshold_isOneSinglePut() {
        h.run(h.queueFile(h.video(1, bytes(19))))

        assertEquals(1, h.filePuts().size)
        assertTrue(h.uploadRequests().isEmpty())
    }

    @Test
    fun plainWebDavUrl_isOneSinglePut() {
        h.serverPath = "/dav/"

        h.run(singleVideoQueue())

        assertEquals(1, h.filePuts().size)
        assertTrue(h.requests.none { it.path.contains("/uploads/") })
    }

    @Test
    fun legacyWebdavUrl_isOneSinglePut() {
        h.serverPath = "/remote.php/webdav/"

        h.run(singleVideoQueue())

        assertEquals(1, h.filePuts().size)
        assertTrue(h.requests.none { it.path.contains("/uploads/") })
    }

    @Test
    fun unknownLength_isOneSinglePut() {
        val uri = android.net.Uri.parse(ChunkedUploadHarness.videoUri(9))
        org.robolectric.Shadows
            .shadowOf(h.context.contentResolver)
            .registerInputStream(uri, java.io.ByteArrayInputStream(content))
        val entry =
            JSONObject()
                .put("id", 9L)
                .put("uri", uri.toString())
                .put("mimeType", "video/mp4")
                .put("displayName", "clip9.mp4")

        h.run(h.queueFile(entry))

        assertEquals(1, h.filePuts().size)
        assertEquals(
            25,
            h
                .filePuts()
                .single()
                .body.size,
        )
        assertTrue(h.uploadRequests().isEmpty())
    }

    @Test
    fun resumeAcrossRuns_afterASystemStop_sendsOnlyTheMissingChunks() {
        val queue = singleVideoQueue()
        h.userCancelled = { false }
        h.hold = { it.method == "PUT" && it.name == "00003" }

        h.runAndCancelWhenHeld(queue)

        assertEquals(2, chunkedOf(queue.readText())!!.getInt("confirmedChunks"))
        assertTrue(h.retryFiles().isEmpty())
        h.hold = { false }
        h.requests.clear()

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("MKCOL", "PUT", "MOVE"), h.uploadRequests().map { it.method })
        assertEquals("00003", h.chunkPuts().single().name)
        assertTrue(h.of("DELETE").isEmpty())
        assertTrue(content.contentEquals(h.serverFiles["/remote.php/dav/files/u/Trip/clip1.mp4"]))
        assertEquals(setOf(1L), h.uploadedIds())
    }

    @Test
    fun stopRightAfterANewMkcol_keepsTheTransferId_andTheNextRunContinuesIt() {
        val queue = singleVideoQueue()
        h.userCancelled = { false }
        h.hold = { it.method == "PUT" && it.name == "00001" }

        h.runAndCancelWhenHeld(queue)

        val saved = chunkedOf(queue.readText())!!
        assertEquals(0, saved.getInt("confirmedChunks"))
        val id = saved.getString("transferId")
        h.hold = { false }
        h.requests.clear()

        h.run(queue)

        val uploads = h.uploadRequests()
        assertTrue(uploads.all { it.transferId == id })
        assertEquals(listOf("MKCOL", "PUT", "PUT", "PUT", "MOVE"), uploads.map { it.method })
        assertEquals(setOf(1L), h.uploadedIds())
    }

    @Test
    fun foregroundRefusedBetweenChunks_retriesWithProgressKept_andCountsTheInterruption() {
        val queue = singleVideoQueue()
        h.foregroundFailsFrom = 2 // start (0) and chunk 1 (1) pass, after chunk 2 it is refused

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Retry)
        val json = JSONObject(queue.readText())
        assertEquals(1, json.getInt("interruptions"))
        assertEquals(2, chunkedOf(queue.readText())!!.getInt("confirmedChunks"))
        assertEquals(2, h.chunkPuts().size)
        assertTrue(h.of("DELETE").isEmpty())

        h.foregroundFailsFrom = null
        h.requests.clear()
        assertTrue(h.run(queue) is ListenableWorker.Result.Success)
        assertEquals(listOf("MKCOL", "PUT", "MOVE"), h.uploadRequests().map { it.method })
        assertEquals("00003", h.chunkPuts().single().name)
    }

    @Test
    fun fifthInterruption_givesUp_retryFileWithoutProgress_andOneDelete() {
        val queue = singleVideoQueue(interruptions = 4)
        h.foregroundFailsFrom = 1 // refused after chunk 1

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Failure)
        val delete = h.of("DELETE").single()
        assertEquals(h.chunkPuts().first().transferId, delete.transferId)
        assertTrue(h.uploadFolders.isEmpty())
        val retry = h.retryFiles().single()
        assertNull(chunkedOf(retry.readText()))
        assertFalse(retry.readText().contains("interruptions"))
        assertEquals("Upload failed", h.title(h.resultNotifications().single()))
    }

    @Test
    fun localFileChangedBetweenRuns_deletesTheOldTransfer_andStartsANewOneFromChunk1() {
        val queue = singleVideoQueue()
        h.foregroundFailsFrom = 1
        h.run(queue)
        val oldId = chunkedOf(queue.readText())!!.getString("transferId")
        val changed = bytes(25, seed = 7)
        h.setContent(1, changed)
        h.foregroundFailsFrom = null
        h.requests.clear()

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        val uploads = h.uploadRequests()
        assertEquals(listOf("MKCOL", "DELETE"), uploads.take(2).map { it.method })
        assertTrue(uploads.take(2).all { it.transferId == oldId })
        val fresh = uploads.drop(2)
        assertEquals(listOf("MKCOL", "PUT", "PUT", "PUT", "MOVE"), fresh.map { it.method })
        assertTrue(fresh.all { it.transferId != oldId })
        assertTrue(changed.contentEquals(h.serverFiles["/remote.php/dav/files/u/Trip/clip1.mp4"]))
    }

    @Test
    fun lengthChangedBetweenRuns_deletesTheOldTransfer_withoutResumingIt() {
        val queue = singleVideoQueue()
        h.foregroundFailsFrom = 1
        h.run(queue)
        val oldId = chunkedOf(queue.readText())!!.getString("transferId")
        val longer = bytes(27)
        h.setContent(1, longer)
        h.foregroundFailsFrom = null
        h.requests.clear()

        h.run(queue)

        val uploads = h.uploadRequests()
        assertEquals("DELETE", uploads.first().method)
        assertEquals(oldId, uploads.first().transferId)
        assertTrue(uploads.drop(1).all { it.transferId != oldId })
        assertEquals(listOf("00001", "00002", "00003"), h.chunkPuts().map { it.name })
        assertEquals("27", uploads.last().headers["OC-Total-Length"])
        assertTrue(longer.contentEquals(h.serverFiles["/remote.php/dav/files/u/Trip/clip1.mp4"]))
    }

    @Test
    fun serverLostTheFolder_restartsFromChunk1_withTheSameId() {
        val queue = singleVideoQueue()
        h.foregroundFailsFrom = 1
        h.run(queue)
        val id = chunkedOf(queue.readText())!!.getString("transferId")
        h.uploadFolders.clear() // the 24 h cleanup
        h.foregroundFailsFrom = null
        h.requests.clear()

        assertTrue(h.run(queue) is ListenableWorker.Result.Success)

        val uploads = h.uploadRequests()
        assertTrue(uploads.all { it.transferId == id })
        assertEquals(listOf("00001", "00002", "00003"), h.chunkPuts().map { it.name })
        assertTrue(h.of("DELETE").isEmpty())
    }

    @Test
    fun bindingChanged_sendsNoRequestForTheOldTransfer() {
        val queue = singleVideoQueue()
        h.foregroundFailsFrom = 1
        h.run(queue)
        val oldId = chunkedOf(queue.readText())!!.getString("transferId")
        h.serverPath = "/remote.php/dav/files/v/"
        h.foregroundFailsFrom = null
        h.requests.clear()

        assertTrue(h.run(queue) is ListenableWorker.Result.Success)

        assertTrue(h.requests.none { it.path.contains(oldId) })
        assertTrue(h.uploadRequests().all { it.path.startsWith("/remote.php/dav/uploads/v/") })
        assertEquals(3, h.chunkPuts().size)
    }

    @Test
    fun transient503OnAChunk_resumesWithoutResendingEarlierChunks() {
        val failures = AtomicInteger()
        h.override =
            { if (it.method == "PUT" && it.name == "00002" && failures.getAndIncrement() == 0) code(503) else null }

        val result = h.run(singleVideoQueue())

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("00001", "00002", "00002", "00003"), h.chunkPuts().map { it.name })
        assertEquals(listOf("MKCOL", "PUT", "PUT", "MKCOL", "PUT", "PUT", "MOVE"), h.uploadRequests().map { it.method })
        assertEquals(listOf(2_000L), h.sleeps)
        assertTrue(h.of("DELETE").isEmpty())
    }

    @Test
    fun move400_failsWithOther_deletesTheTransfer_notMarkedUploaded() {
        h.override = { if (it.method == "MOVE") code(400) else null }

        h.run(singleVideoQueue())

        assertEquals(1, h.of("MOVE").size)
        assertEquals(1, h.of("DELETE").size)
        assertTrue(h.uploadedIds().isEmpty())
        assertNull(chunkedOf(h.retryFiles().single().readText()))
        assertEquals(
            h.context.resources.getQuantityString(R.plurals.notification_upload_partial, 0, 0, 1),
            h.text(h.resultNotifications().single()),
        )
    }

    @Test
    fun move507_reportsServerStorageFull() {
        h.override = { if (it.method == "MOVE") code(507) else null }

        h.run(singleVideoQueue())

        assertEquals(1, h.of("MOVE").size)
        val text = h.text(h.resultNotifications().single())
        assertTrue(text, text.contains(h.context.getString(R.string.upload_failure_storage_full)))
    }

    @Test
    fun chunk413_reportsFileTooLarge() {
        h.override = { if (it.method == "PUT" && it.name == "00002") code(413) else null }

        h.run(singleVideoQueue())

        assertEquals(2, h.chunkPuts().size)
        assertEquals(1, h.of("DELETE").size)
        val text = h.text(h.resultNotifications().single())
        assertTrue(text, text.contains(h.context.getString(R.string.upload_failure_too_large)))
    }

    @Test
    fun moveIoFailure_thenMkcol405_resendsOnlyTheMove() {
        val moves = AtomicInteger()
        h.override = {
            // A malformed status line is an IOException (ProtocolException) that OkHttp never retries
            // itself (it would silently retry a dropped connection).
            if (it.method == "MOVE" && moves.getAndIncrement() == 0) {
                MockResponse().setStatus("garbage")
            } else {
                null
            }
        }

        val result = h.run(singleVideoQueue())

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf("MKCOL", "PUT", "PUT", "PUT", "MOVE", "MKCOL", "MOVE"),
            h.uploadRequests().map { it.method },
        )
        assertEquals(setOf(1L), h.uploadedIds())
    }

    @Test
    fun lostSession412_deletesAndStartsANewTransferFromChunk1() {
        val failures = AtomicInteger()
        h.override =
            { if (it.method == "PUT" && it.name == "00002" && failures.getAndIncrement() == 0) code(412) else null }

        val result = h.run(singleVideoQueue())

        assertTrue(result is ListenableWorker.Result.Success)
        val uploads = h.uploadRequests()
        val firstId = uploads.first().transferId
        val delete = h.of("DELETE").single()
        assertEquals(firstId, delete.transferId)
        val after = uploads.dropWhile { it.method != "DELETE" }.drop(1)
        assertEquals(listOf("MKCOL", "PUT", "PUT", "PUT", "MOVE"), after.map { it.method })
        assertTrue(after.all { it.transferId != firstId })
        assertEquals(listOf("00001", "00002", "00003"), after.filter { it.method == "PUT" }.map { it.name })
        assertTrue(h.sleeps.isEmpty())
    }

    @Test
    fun mkcol404OnANewTransfer_fallsBackToSinglePutsForTheRestOfTheRun() {
        h.override =
            { if (it.method == "MKCOL" && it.path.startsWith(ChunkedUploadHarness.UPLOADS_PREFIX)) code(404) else null }
        val queue = h.queueFile(h.video(1, content), h.video(2, bytes(30, seed = 3)))

        val result = h.run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, h.uploadRequests().size)
        assertEquals(listOf("clip1.mp4", "clip2.mp4"), h.filePuts().map { it.name })
        // The state saved before the MKCOL is gone when the single PUT starts.
        assertNull(chunkedOf(h.filePuts().first().queue, 0))
        assertEquals(setOf(1L, 2L), h.uploadedIds())
        assertTrue(h.of("DELETE").isEmpty())
    }

    @Test
    fun mkcol503ThenCreated_staysChunkedWithTheSameTransferId() {
        val mkcols = AtomicInteger()
        h.override = {
            if (it.method == "MKCOL" && it.path.startsWith(ChunkedUploadHarness.UPLOADS_PREFIX) &&
                mkcols.getAndIncrement() == 0
            ) {
                code(503)
            } else {
                null
            }
        }

        val result = h.run(singleVideoQueue())

        assertTrue(result is ListenableWorker.Result.Success)
        val uploads = h.uploadRequests()
        assertEquals(1, uploads.map { it.transferId }.toSet().size)
        assertEquals(listOf("MKCOL", "MKCOL", "PUT", "PUT", "PUT", "MOVE"), uploads.map { it.method })
        assertTrue(h.filePuts().isEmpty())
    }

    @Test
    fun redirectOnAChunk_failsWithoutAFollowUpRequest() {
        h.override = {
            if (it.method == "PUT" && it.name == "00001") {
                code(302).setHeader("Location", h.server.url("/elsewhere").toString())
            } else {
                null
            }
        }

        h.run(singleVideoQueue())

        assertTrue(h.requests.none { it.path.startsWith("/elsewhere") })
        assertEquals(1, h.chunkPuts().size)
        assertTrue(h.of("MOVE").isEmpty())
        assertTrue(h.uploadedIds().isEmpty())
    }

    @Test
    fun stagedSharedEntryAboveThreshold_isChunked_andNeverVerified() {
        org.robolectric.Shadows
            .shadowOf(h.context as android.app.Application)
            .grantPermissions(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        h.verifyByDownload = true
        val queue = h.queueFile(h.stagedEntry("shared.mp4", content))

        assertTrue(h.run(queue) is ListenableWorker.Result.Success)

        assertEquals(3, h.chunkPuts().size)
        assertNull(h.of("MOVE").single().headers["X-Hash"])
        assertTrue(h.of("GET").isEmpty())
        assertTrue(h.verified.current.isEmpty())
        assertTrue(content.contentEquals(h.serverFiles["/remote.php/dav/files/u/Trip/shared.mp4"]))
    }

    @Test
    fun userCancelDuringChunk2_finalizesTheQueueFirst_thenDeletesTheTransfer() {
        val queue = singleVideoQueue()
        h.userCancelled = { true }
        h.hold = { it.method == "PUT" && it.name == "00002" }
        val retryFilesAtDelete = mutableListOf<Int>()
        h.override = {
            if (it.method == "DELETE") retryFilesAtDelete += h.retryFiles().size
            null
        }

        val job = h.runAndCancelWhenHeld(queue)

        assertTrue(job.isCancelled)
        val delete = h.of("DELETE").single()
        assertEquals(h.chunkPuts().first().transferId, delete.transferId)
        // Claimed and reported before the DELETE was sent.
        assertEquals(listOf(1), retryFilesAtDelete)
        val retry = h.retryFiles().single()
        assertNull(chunkedOf(retry.readText()))
        assertEquals("Upload cancelled", h.title(h.resultNotifications().single()))
        assertFalse(queue.exists())
        assertEquals(1, h.history.state.value.size)
    }

    @Test
    fun userCancelBeforeTheSavedTransfersFirstRequest_deletesTheSavedTransfer() {
        val queue = singleVideoQueue()
        h.foregroundFailsFrom = 1
        h.run(queue)
        val savedId = chunkedOf(queue.readText())!!.getString("transferId")
        h.foregroundFailsFrom = null
        h.requests.clear()
        h.userCancelled = { true }
        val scope = CoroutineScope(Dispatchers.Default)
        lateinit var job: Job
        // Cancel at the start of the attempt (records dropped at the target), before its MKCOL.
        h.verifiedOverride = CancelOnApply(h.verified) { job.cancel() }

        job = h.start(queue, scope)
        runBlocking { job.join() }
        scope.cancel()

        assertTrue(h.uploadRequests().none { it.method == "MKCOL" })
        val delete = h.of("DELETE").single()
        assertEquals(savedId, delete.transferId)
        assertNull(chunkedOf(h.retryFiles().single().readText()))
    }

    @Test
    fun userCancelDuringTheBackOffWait_deletesTheSavedTransfer() {
        val queue = singleVideoQueue()
        h.userCancelled = { true }
        h.override = { if (it.method == "PUT" && it.name == "00002") code(503) else null }
        h.settings =
            ChunkedUploadSettings(
                policy = h.settings.policy,
                sleep = {
                    currentCoroutineContext().job.cancel()
                    awaitCancellation()
                },
                abortTimeoutMs = 5_000L,
                cancelAbortTimeoutMs = 5_000L,
            )
        val scope = CoroutineScope(Dispatchers.Default)

        val job = h.start(queue, scope)
        runBlocking { job.join() }
        scope.cancel()

        val id = h.chunkPuts().first().transferId
        assertEquals(id, h.of("DELETE").single().transferId)
        assertEquals("Upload cancelled", h.title(h.resultNotifications().single()))
        assertNull(chunkedOf(h.retryFiles().single().readText()))
    }

    @Test
    fun userCancelRightAfterMove_countsAsUploaded_andSendsNoDelete() {
        val queue = singleVideoQueue()
        h.userCancelled = { true }
        val scope = CoroutineScope(Dispatchers.Default)
        lateinit var job: Job
        h.uploadedOverride = CancellingUploadedPhotos(FakeUploadedPhotosRepository()) { job.cancel() }

        job = h.start(queue, scope)
        runBlocking { job.join() }
        scope.cancel()

        assertEquals(1, h.of("MOVE").size)
        assertTrue(h.of("DELETE").isEmpty())
        assertTrue(h.retryFiles().isEmpty())
        assertTrue(content.contentEquals(h.serverFiles["/remote.php/dav/files/u/Trip/clip1.mp4"]))
        val history =
            h.history.state.value
                .single()
        assertEquals(0, history.failedCount)
    }

    @Test
    fun deleteAnswering500_doesNotChangeTheResult() {
        h.override = {
            when (it.method) {
                "MOVE" -> code(400)
                "DELETE" -> code(500).setBody("internal server text")
                else -> null
            }
        }

        val result = h.run(singleVideoQueue())

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, h.of("DELETE").size)
        val text = h.text(h.resultNotifications().single())
        assertEquals(h.context.resources.getQuantityString(R.plurals.notification_upload_partial, 0, 0, 1), text)
        assertFalse(text.contains("internal server text"))
    }

    @Test
    fun hangingDelete_endsAfterTheInjectedBound_withTheSameResult() {
        h.settings =
            ChunkedUploadSettings(
                policy = h.settings.policy,
                sleep = {},
                abortTimeoutMs = 300L,
                cancelAbortTimeoutMs = 300L,
            )
        h.override = { if (it.method == "MOVE") code(400) else null }
        h.hold = { it.method == "DELETE" }

        val started = System.nanoTime()
        val result = h.run(singleVideoQueue())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("took $elapsedMs ms", elapsedMs < 20_000)
        assertEquals(1, h.of("DELETE").size)
        assertEquals(
            h.context.resources.getQuantityString(R.plurals.notification_upload_partial, 0, 0, 1),
            h.text(h.resultNotifications().single()),
        )
    }

    @Test
    fun queueFileWithInvalidProgress_uploadsFromTheStart() {
        val entry = h.video(1, content).put("chunked", JSONObject().put("transferId", "../evil"))
        val queue = h.queueFile(entry)

        assertTrue(h.run(queue) is ListenableWorker.Result.Success)

        assertTrue(h.requests.none { it.path.contains("evil") })
        assertEquals(3, h.chunkPuts().size)
        assertEquals(setOf(1L), h.uploadedIds())
    }

    @Test
    fun savedProgressOfAFileNowBelowTheThreshold_isDeleted_andTheFileIsOnePut() {
        val queue = singleVideoQueue()
        h.foregroundFailsFrom = 1
        h.run(queue)
        val oldId = chunkedOf(queue.readText())!!.getString("transferId")
        h.setContent(1, bytes(5))
        h.foregroundFailsFrom = null
        h.requests.clear()

        assertTrue(h.run(queue) is ListenableWorker.Result.Success)

        assertEquals(oldId, h.of("DELETE").single().transferId)
        assertEquals(1, h.filePuts().size)
        assertNull(chunkedOf(h.filePuts().single().queue))
    }
}

/** Runs [onFirstApply] before the first record update (the start of an upload attempt). */
private class CancelOnApply(
    private val delegate: VerifiedUploadsRepositoryContract,
    private val onFirstApply: () -> Unit,
) : VerifiedUploadsRepositoryContract by delegate {
    private var applies = 0

    override val records: Flow<List<VerifiedUpload>> get() = delegate.records

    override suspend fun applyUpload(
        target: UploadTarget,
        record: VerifiedUpload?,
        forgetIds: Set<Long>,
    ) {
        if (applies++ == 0) onFirstApply()
        delegate.applyUpload(target, record, forgetIds)
    }
}
