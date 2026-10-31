package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaPermissionRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.holdUntilCancelled
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reports progress and runs until cancelled (stands in for a running upload with progress). */
class ProgressHoldingWorker(
    context: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setProgress(workDataOf(UploadWorker.KEY_PROGRESS to 1, UploadWorker.KEY_TOTAL to 3))
        reported.countDown()
        holdUntilCancelled()
    }

    companion object {
        var reported = CountDownLatch(1)
    }
}

/** The "Cancel upload" button of the real [PhotoGridScreen] cancels exactly the shown running upload. */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoGridCancelUploadTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var app: Application
    private lateinit var workManager: WorkManager

    /** WorkManager's executor of this test; shut down in [tearDown] so no threads outlive the test. */
    private val pool = Executors.newFixedThreadPool(4)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProgressHoldingWorker.reported = CountDownLatch(1)
        val config =
            Configuration
                .Builder()
                .setExecutor(pool)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: android.content.Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? =
                            if (workerClassName == ProgressHoldingWorker::class.java.name) {
                                ProgressHoldingWorker(appContext, workerParameters)
                            } else {
                                null
                            }
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config)
        workManager = WorkManager.getInstance(app)
    }

    @After
    fun tearDown() {
        runBlocking { workManager.cancelAllWork().await() }
        pool.shutdownNow()
    }

    private fun state(id: UUID) = workManager.getWorkInfoById(id).get()?.state

    private fun startUpload(tag: String = "photo_upload"): UUID {
        val request = OneTimeWorkRequestBuilder<ProgressHoldingWorker>().addTag(tag).build()
        runBlocking { workManager.enqueue(request).await() }
        return request.id
    }

    private fun showScreen(): PhotoGridViewModel {
        val viewModel =
            PhotoGridViewModel(
                app,
                FakeMediaRepository(),
                FakeCredentialRepository(initialConfig = WebDavConfig("https://cloud.example/dav/", "u", "p")),
                FakeUploadedPhotosRepository(),
                workManager,
                FakeMediaPermissionRepository(),
            )
        rule.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(Color.Gray.toArgb()) },
            ) {
                PhotoGridScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = {},
                    onNavigateToUploadHistory = {},
                    onStartUpload = {},
                )
            }
        }
        return viewModel
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) {
            // waitUntil {} does not drain the paused main looper; waitForIdle() does.
            rule.waitForIdle()
            Thread.sleep(50)
        }
        assertEquals(true, condition())
    }

    private fun cancelButtonShown() =
        runCatching {
            rule.onNodeWithText(app.getString(R.string.action_cancel_upload)).assertExists()
        }.isSuccess

    @Test
    fun tappingCancelUpload_cancelsTheRunningUpload() {
        val id = startUpload()
        assertEquals(true, ProgressHoldingWorker.reported.await(30, TimeUnit.SECONDS))
        showScreen()
        await(::cancelButtonShown)

        rule.onNodeWithText(app.getString(R.string.action_cancel_upload)).performClick()

        await { state(id) == WorkInfo.State.CANCELLED }
    }

    @Test
    fun noBannerWithoutARunningUpload() {
        showScreen()
        rule.waitForIdle()

        assertEquals(false, cancelButtonShown())
    }
}
