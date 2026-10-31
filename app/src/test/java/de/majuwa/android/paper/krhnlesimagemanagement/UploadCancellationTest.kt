package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.worker.CancelFinalization
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.finalizeCancelledQueue
import de.majuwa.android.paper.krhnlesimagemanagement.worker.parseQueue
import de.majuwa.android.paper.krhnlesimagemanagement.worker.queueFileTag
import de.majuwa.android.paper.krhnlesimagemanagement.worker.recoverCancelledUploads
import de.majuwa.android.paper.krhnlesimagemanagement.worker.shareStagingDir
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [finalizeCancelledQueue] (atomic claim, retry file, staged copies, history, notification) and
 * [recoverCancelledUploads] (start-up recovery, fail-closed) on their own, without a worker run.
 */
@RunWith(RobolectricTestRunner::class)
class UploadCancellationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val history = CancelTestHistory()

    @Before
    fun setUp() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        HoldingWorker.running = CountDownLatch(1)
        val config =
            Configuration
                .Builder()
                .setExecutor(Executors.newFixedThreadPool(4))
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? =
                            when (workerClassName) {
                                HoldingWorker::class.java.name -> HoldingWorker(appContext, workerParameters)
                                QuickWorker::class.java.name -> QuickWorker(appContext, workerParameters)
                                else -> null
                            }
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        runBlocking { WorkManager.getInstance(context).cancelAllWork().await() }
        listOf(context.filesDir, context.noBackupFilesDir).forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(".json") || it.name.endsWith(".tmp") }?.forEach { it.delete() }
        }
        shareStagingDir(context).deleteRecursively()
    }

    private fun queue(
        uploaded: List<Boolean>,
        dir: File = context.noBackupFilesDir,
        staged: List<String?> = uploaded.map { null },
        interruptions: Int = 0,
    ): File {
        val photos = JSONArray()
        uploaded.forEachIndexed { i, done ->
            val obj =
                JSONObject()
                    .put("uri", "content://media/external/images/media/${i + 1}")
                    .put("mimeType", "image/jpeg")
                    .put("displayName", "p$i.jpg")
            if (staged[i] == null) obj.put("id", i + 1L) else obj.put("staged", staged[i])
            if (done) obj.put("uploaded", true)
            photos.put(obj)
        }
        val json = JSONObject().put("folderName", "Trip").put("photos", photos)
        if (interruptions > 0) json.put("interruptions", interruptions)
        return File(dir, "upload_queue_1_${UUID.randomUUID()}.json").also { it.writeText(json.toString()) }
    }

    private fun retryFiles() =
        context.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("retry_queue_") && it.name.endsWith(".json") }

    private fun notifications() =
        context
            .getSystemService(android.app.NotificationManager::class.java)
            .activeNotifications
            .filter { it.id == UploadWorker.RESULT_NOTIFICATION_ID }

    private fun finalize(
        file: File,
        failures: Set<UploadFailure> = emptySet(),
    ) = runBlocking { finalizeCancelledQueue(context, file, checkNotNull(parseQueue(file)), failures, history) }

    @Test
    fun claimsTheQueueByRename_andRewritesItAsTheRetryFileOfTheRemainingEntries() {
        val file = queue(listOf(true, true, false, false), interruptions = 3)
        file.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(6))

        val result = finalize(file)

        val retry = (result as CancelFinalization.Resumable).retryFile
        assertFalse(file.exists())
        assertEquals(listOf(retry), retryFiles())
        assertEquals(listOf("p2.jpg", "p3.jpg"), UploadCancelHarness.names(retry))
        val json = JSONObject(retry.readText())
        assertFalse(json.has("interruptions"))
        assertEquals(listOf(false, false), UploadCancelHarness.markers(retry))
        // The 7-day staging window starts at the cancel.
        assertTrue(System.currentTimeMillis() - retry.lastModified() < TimeUnit.MINUTES.toMillis(1))
        val entry = history.state.value.single()
        assertEquals(4, entry.photoCount)
        assertEquals(2, entry.failedCount)
        assertTrue(entry.cancelled)
        val posted = notifications().single()
        assertEquals(file.name, posted.tag)
        assertEquals(
            "Retry Failed (2)",
            posted.notification.actions
                .single()
                .title
                .toString(),
        )
    }

    @Test
    fun aSecondFinalization_losesTheClaim_andDoesNothing() {
        val file = queue(listOf(true, false))
        val parsed = checkNotNull(parseQueue(file))

        val first = runBlocking { finalizeCancelledQueue(context, file, parsed, emptySet(), history) }
        val second = runBlocking { finalizeCancelledQueue(context, file, parsed, emptySet(), history) }

        assertTrue(first is CancelFinalization.Resumable)
        assertEquals(CancelFinalization.NotClaimed, second)
        assertEquals(1, retryFiles().size)
        assertEquals(1, history.state.value.size)
        assertEquals(1, notifications().size)
    }

    @Test
    fun concurrentFinalizations_produceExactlyOneRetryFile() {
        val file = queue(listOf(false, false, false))
        val parsed = checkNotNull(parseQueue(file))

        val results =
            runBlocking {
                (1..8)
                    .map {
                        CoroutineScope(Dispatchers.Default).async {
                            finalizeCancelledQueue(context, file, parsed, emptySet(), history)
                        }
                    }.awaitAll()
            }

        assertEquals(1, results.count { it is CancelFinalization.Resumable })
        assertEquals(7, results.count { it == CancelFinalization.NotClaimed })
        assertEquals(1, retryFiles().size)
        assertEquals(1, history.state.value.size)
        assertEquals(1, notifications().size)
    }

    @Test
    fun nothingPending_reportsTheBatchAsCompleteAndDeletesTheQueue() {
        val file = queue(listOf(true, true))

        val result = finalize(file)

        assertEquals(CancelFinalization.Completed, result)
        assertTrue(retryFiles().isEmpty())
        assertFalse(file.exists())
        val entry = history.state.value.single()
        assertFalse(entry.cancelled)
        assertEquals(0, entry.failedCount)
        val posted = notifications().single()
        assertEquals(
            "Upload complete",
            posted.notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE)
                .toString(),
        )
        assertTrue(posted.notification.actions.isNullOrEmpty())
    }

    @Test
    fun stagedCopiesOfUploadedEntriesAreDeleted_thoseOfPendingEntriesStay() {
        shareStagingDir(context).mkdirs()
        val names = List(3) { UUID.randomUUID().toString() }
        names.forEach { File(shareStagingDir(context), it).writeText("x") }
        val file = queue(listOf(true, false, false), staged = names)

        val retry = (finalize(file) as CancelFinalization.Resumable).retryFile

        assertFalse(File(shareStagingDir(context), names[0]).exists())
        assertTrue(File(shareStagingDir(context), names[1]).exists())
        assertTrue(File(shareStagingDir(context), names[2]).exists())
        assertTrue(retry.readText().contains(names[1]) && retry.readText().contains(names[2]))
        assertFalse(retry.readText().contains(names[0]))
    }

    @Test
    fun nothingPending_deletesAllStagedCopies() {
        shareStagingDir(context).mkdirs()
        val names = List(2) { UUID.randomUUID().toString() }
        names.forEach { File(shareStagingDir(context), it).writeText("x") }
        val file = queue(listOf(true, true), staged = names)

        assertEquals(CancelFinalization.Completed, finalize(file))

        assertTrue(names.none { File(shareStagingDir(context), it).exists() })
    }

    @Test
    fun aFailingHistoryWrite_neverBlocksTheNotificationOrTheRetryFile() {
        history.onAdd = { error("disk full") }
        val file = queue(listOf(false, false))

        val result = finalize(file)

        assertTrue(result is CancelFinalization.Resumable)
        assertEquals(1, notifications().size)
        assertEquals(1, retryFiles().size)
    }

    @Test
    fun theReasonOfAFailedItemFollowsTheCounts() {
        val file = queue(listOf(true, false))

        finalize(file, failures = setOf(UploadFailure.TooLarge))

        val text =
            notifications()
                .single()
                .notification.extras
                .getCharSequence(
                    android.app.Notification.EXTRA_TEXT,
                ).toString()
        assertEquals("1 uploaded, 1 not uploaded. A file is too large for the server.", text)
    }

    // ── start-up recovery ────────────────────────────────────────────────────────────────────

    private val workManager get() = WorkManager.getInstance(context)

    private fun request(
        queueFile: File,
        worker: Class<out androidx.work.ListenableWorker> = HoldingWorker::class.java,
        delayHours: Long = 0,
    ): OneTimeWorkRequest {
        val builder =
            if (worker == QuickWorker::class.java) {
                OneTimeWorkRequestBuilder<QuickWorker>()
            } else {
                OneTimeWorkRequestBuilder<HoldingWorker>()
            }
        return builder
            .addTag("photo_upload")
            .addTag(queueFileTag(queueFile.name))
            .apply { if (delayHours > 0) setInitialDelay(delayHours, TimeUnit.HOURS) }
            .build()
    }

    private fun waitForState(
        id: UUID,
        expected: WorkInfo.State,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (workManager.getWorkInfoById(id).get()?.state != expected &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(20)
        }
        assertEquals(expected, workManager.getWorkInfoById(id).get()?.state)
    }

    private fun cancelledWorkFor(file: File) {
        val request = request(file)
        runBlocking { workManager.enqueue(request).await() }
        assertTrue(HoldingWorker.running.await(30, TimeUnit.SECONDS))
        waitForState(request.id, WorkInfo.State.RUNNING)
        runBlocking { workManager.cancelWorkById(request.id).await() }
        waitForState(request.id, WorkInfo.State.CANCELLED)
    }

    private fun recover() = runBlocking { recoverCancelledUploads(context, workManager, history, graceMs = 0) }

    @Test
    fun recovery_finalizesACancelledOrphan_once() {
        val orphan = queue(listOf(true, false, false))
        cancelledWorkFor(orphan)

        val recovered = recover()
        val again = recover()

        assertEquals(1, recovered.size)
        assertTrue(again.isEmpty())
        assertEquals(listOf("p1.jpg", "p2.jpg"), UploadCancelHarness.names(recovered.single()))
        assertFalse(orphan.exists())
        assertEquals(1, retryFiles().size)
        assertEquals(1, history.state.value.size)
        assertTrue(
            history.state.value
                .single()
                .cancelled,
        )
        assertEquals(1, notifications().size)
    }

    @Test
    fun recovery_leavesUnfinishedAndSucceededWorkUntouched() {
        val waiting = queue(listOf(false))
        val waitingRequest = request(waiting, delayHours = 1)
        runBlocking { workManager.enqueue(waitingRequest).await() }
        val done = queue(listOf(false))
        val doneRequest = request(done, worker = QuickWorker::class.java)
        runBlocking { workManager.enqueue(doneRequest).await() }
        waitForState(doneRequest.id, WorkInfo.State.SUCCEEDED)

        assertTrue(recover().isEmpty())

        assertTrue(waiting.exists())
        assertTrue(done.exists())
        assertTrue(retryFiles().isEmpty())
        assertTrue(history.state.value.isEmpty())
    }

    @Test
    fun recovery_leavesAQueueWithoutAnyKnownWorkUntouched() {
        val legacy = queue(listOf(false, false))

        assertTrue(recover().isEmpty())

        assertTrue(legacy.exists())
        assertTrue(retryFiles().isEmpty())
    }

    @Test
    fun recovery_leavesAQueueUntouchedWhenItsWorkStateIsUnknown() {
        val unknown = queue(listOf(false))
        val failing = mockk<WorkManager>()
        every { failing.getWorkInfosByTagFlow(any()) } returns flow { throw IllegalStateException("boom") }

        val recovered = runBlocking { recoverCancelledUploads(context, failing, history, graceMs = 0) }

        assertTrue(recovered.isEmpty())
        assertTrue(unknown.exists())
    }

    @Test
    fun recovery_leavesAQueueUntouchedWhenTheWorkStateTimesOut() {
        val unknown = queue(listOf(false))
        val silent = mockk<WorkManager>()
        every { silent.getWorkInfosByTagFlow(any()) } returns flow { kotlinx.coroutines.awaitCancellation() }

        val recovered = runBlocking { recoverCancelledUploads(context, silent, history, graceMs = 0) }

        assertTrue(recovered.isEmpty())
        assertTrue(unknown.exists())
    }

    @Test
    fun recovery_ignoresFilesThatAreNotQueueFiles() {
        val stray = File(context.noBackupFilesDir, "notes.json").apply { writeText("{}") }
        val strayTmp = File(context.noBackupFilesDir, "upload_queue_1_x.json.tmp").apply { writeText("{}") }

        assertTrue(recover().isEmpty())

        assertTrue(stray.exists() && strayTmp.exists())
    }

    @Test
    fun recovery_aWorkerThatFinalizedItself_leavesNothingToDo() {
        val file = queue(listOf(true, false))
        cancelledWorkFor(file)
        // The worker of this process finalized in the meantime.
        finalize(file)

        assertTrue(recover().isEmpty())

        assertEquals(1, retryFiles().size)
        assertEquals(1, history.state.value.size)
    }
}
