package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.worker.CancelUploadReceiver
import de.majuwa.android.paper.krhnlesimagemanagement.worker.cancelRunningUpload
import de.majuwa.android.paper.krhnlesimagemanagement.worker.handleCancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
 * Suspends until the worker is cancelled, with a timer that keeps the coroutine reachable: a coroutine
 * parked in `awaitCancellation()` can have its future garbage collected, which fails the work.
 */
suspend fun holdUntilCancelled(): Nothing {
    while (true) delay(HOLD_POLL_MS)
}

private const val HOLD_POLL_MS = 100L

/** A worker that runs until it is cancelled (stands in for a running upload). */
class HoldingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        running.countDown()
        holdUntilCancelled()
    }

    companion object {
        var running = CountDownLatch(1)
    }
}

/** A worker that finishes at once. */
class QuickWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

/**
 * [cancelRunningUpload] and [CancelUploadReceiver] against a real (test) WorkManager with real
 * executors: only upload work that is running right now is cancelled.
 */
@RunWith(RobolectricTestRunner::class)
class CancelUploadReceiverTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        runBlocking { workManager.cancelAllWork().await() }
    }

    private fun holdingRequest(tag: String?): OneTimeWorkRequest =
        androidx.work
            .OneTimeWorkRequestBuilder<HoldingWorker>()
            .apply { if (tag != null) addTag(tag) }
            .build()

    private fun startRunning(tag: String?): UUID {
        val request = holdingRequest(tag)
        runBlocking { workManager.enqueue(request).await() }
        assertTrue("the work never started", HoldingWorker.running.await(30, TimeUnit.SECONDS))
        waitForState(request.id, WorkInfo.State.RUNNING)
        return request.id
    }

    private fun state(id: UUID) = workManager.getWorkInfoById(id).get()?.state

    private fun waitForState(
        id: UUID,
        expected: WorkInfo.State,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (state(id) != expected && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(expected, state(id))
    }

    private fun cancelIntent(extra: String?) =
        Intent(context, CancelUploadReceiver::class.java).apply {
            if (extra != null) putExtra(CancelUploadReceiver.EXTRA_WORK_ID, extra)
        }

    @Test
    fun runningUploadWork_isCancelled() {
        val id = startRunning("photo_upload")

        assertTrue(runBlocking { cancelRunningUpload(workManager, id) })

        waitForState(id, WorkInfo.State.CANCELLED)
    }

    @Test
    fun enqueuedUploadWork_isLeftAlone() {
        val request =
            androidx.work
                .OneTimeWorkRequestBuilder<HoldingWorker>()
                .addTag("photo_upload")
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
        runBlocking { workManager.enqueue(request).await() }

        assertFalse(runBlocking { cancelRunningUpload(workManager, request.id) })

        assertEquals(WorkInfo.State.ENQUEUED, state(request.id))
    }

    @Test
    fun finishedUploadWork_isLeftAlone() {
        val request =
            androidx.work
                .OneTimeWorkRequestBuilder<QuickWorker>()
                .addTag("photo_upload")
                .build()
        runBlocking { workManager.enqueue(request).await() }
        waitForState(request.id, WorkInfo.State.SUCCEEDED)

        assertFalse(runBlocking { cancelRunningUpload(workManager, request.id) })

        assertEquals(WorkInfo.State.SUCCEEDED, state(request.id))
    }

    @Test
    fun unknownId_isIgnored() {
        assertFalse(runBlocking { cancelRunningUpload(workManager, UUID.randomUUID()) })
    }

    @Test
    fun runningWorkThatIsNotAnUpload_isNeverCancelled() {
        val id = startRunning(tag = "something_else")

        assertFalse(runBlocking { cancelRunningUpload(workManager, id) })

        assertEquals(WorkInfo.State.RUNNING, state(id))
    }

    @Test
    fun handleCancel_withAValidIdCancelsTheRunningUpload() {
        val id = startRunning("photo_upload")

        runBlocking { handleCancel(cancelIntent(id.toString()), workManager) }

        waitForState(id, WorkInfo.State.CANCELLED)
    }

    @Test
    fun theBroadcastReachesTheManifestReceiver_andCancelsTheRunningUpload() {
        val id = startRunning("photo_upload")

        // The explicit broadcast of the notification action, delivered through the manifest entry.
        context.sendBroadcast(cancelIntent(id.toString()))
        shadowOf(Looper.getMainLooper()).idle()

        waitForState(id, WorkInfo.State.CANCELLED)
    }

    @Test
    fun theBroadcastWithoutAWorkId_isIgnoredWithoutCrashing() {
        val id = startRunning("photo_upload")

        context.sendBroadcast(cancelIntent(null))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)

        assertEquals(WorkInfo.State.RUNNING, state(id))
    }

    @Test
    fun handleCancel_ignoresMissingAndMalformedIdsWithoutCrashing() {
        val id = startRunning("photo_upload")

        runBlocking {
            handleCancel(cancelIntent(null), workManager)
            handleCancel(cancelIntent(""), workManager)
            handleCancel(cancelIntent("not-a-uuid"), workManager)
            handleCancel(cancelIntent("1234"), workManager)
        }

        assertEquals(WorkInfo.State.RUNNING, state(id))
    }

    @Test
    fun handleCancel_withTheIdOfNonUploadWork_changesNothing() {
        val id = startRunning(tag = null)

        runBlocking { handleCancel(cancelIntent(id.toString()), workManager) }

        assertEquals(WorkInfo.State.RUNNING, state(id))
    }

    @Test
    fun handleCancel_doesNotCancelAnotherUploadThatIsNotTheTarget() {
        val first = startRunning("photo_upload")
        HoldingWorker.running = CountDownLatch(1)
        val second = startRunning("photo_upload")

        runBlocking { handleCancel(cancelIntent(first.toString()), workManager) }

        waitForState(first, WorkInfo.State.CANCELLED)
        assertEquals(WorkInfo.State.RUNNING, state(second))
    }

    @Test
    fun handleCancel_touchesNoFile() {
        val id = startRunning("photo_upload")
        val before = queueDirNames()

        runBlocking { handleCancel(cancelIntent(id.toString()), workManager) }

        val after = queueDirNames()
        assertEquals(before, after)
    }

    private fun queueDirNames() =
        listOf(context.noBackupFilesDir, context.filesDir).flatMap { dir -> dir.list().orEmpty().toList() }.sorted()

    @Test
    fun receiverIsRegisteredButNotExported() {
        val manifest = File(System.getProperty("krhnles.moduleDir"), "src/main/AndroidManifest.xml").readText()
        val receiver =
            Regex("""<receiver[^>]*CancelUploadReceiver[^>]*/>""", RegexOption.DOT_MATCHES_ALL).find(manifest)
        assertTrue("CancelUploadReceiver is not declared", receiver != null)
        assertTrue(receiver!!.value.contains("""android:exported="false""""))
        assertFalse(receiver.value.contains("intent-filter"))
    }
}
