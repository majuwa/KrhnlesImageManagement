package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeShareProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.ProviderGate
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.ShareTestHarness
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.ShareTestHarness.Companion.share
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.ShareTestHarness.Companion.waitUntil
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareFinish
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareUiState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.upload.StagingLimits
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ShareViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var harness: ShareTestHarness

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeShareProvider::class.java, FakeShareProvider.AUTHORITY)
        harness = ShareTestHarness(app, temp.newFolder("share_staging"), temp.newFolder("queues"))
    }

    @After
    fun tearDown() {
        FakeShareProvider.items.clear()
    }

    private fun ready(
        vararg uris: Uri,
        vm: ShareViewModel = harness.viewModel(),
    ): ShareViewModel {
        vm.start(share(*uris))
        waitUntil { vm.uiState.value is ShareUiState.Ready }
        return vm
    }

    private fun confirmAndWaitForFinish(vm: ShareViewModel): ShareFinish {
        vm.confirm("Trip")
        waitUntil { vm.finish.value != null }
        return requireNotNull(vm.finish.value)
    }

    @Test
    fun notConfigured_showsTheSetupPrompt_andCopiesNothing() {
        val vm = harness.viewModel(FakeCredentialRepository())

        vm.start(share(harness.image("1")))
        waitUntil { vm.uiState.value == ShareUiState.NotConfigured }
        vm.confirm("Trip")
        waitUntil { true }

        assertEquals(ShareUiState.NotConfigured, vm.uiState.value)
        assertTrue(harness.stagedFiles().isEmpty())
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun configReadTimeout_countsAsConfigured() {
        val hanging =
            object : CredentialRepository by FakeCredentialRepository() {
                override val webDavConfig: Flow<WebDavConfig> = flow { CompletableDeferred<Unit>().await() }
            }
        val timed =
            ShareViewModel(
                app,
                hanging,
                harness.staging,
                { batch, _ -> harness.enqueued += batch },
                workDispatcher = Dispatchers.IO,
                configTimeoutMillis = 50,
            )

        timed.start(share(harness.image("1")))

        // The timeout runs on the main looper, whose clock only advances when told to.
        waitUntil {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            timed.uiState.value is ShareUiState.Ready
        }
    }

    @Test
    fun noSupportedUri_finishesWithNoPhotosImmediately() {
        val vm = harness.viewModel()

        vm.start(share(Uri.parse("file:///sdcard/a.jpg")))

        assertEquals(ShareUiState.Error(ShareError.NoPhotos), vm.uiState.value)
        assertEquals(ShareFinish(error = ShareError.NoPhotos), vm.finish.value)
    }

    @Test
    fun onlyNonImageItems_finishWithNoPhotos() {
        val vm = harness.viewModel()

        vm.start(share(harness.image("1", type = "application/pdf")))

        waitUntil { vm.finish.value != null }
        assertEquals(ShareFinish(error = ShareError.NoPhotos), vm.finish.value)
    }

    @Test
    fun ready_countsAcceptedSkippedAndNotIncludedItems() {
        val mixed =
            ready(
                harness.image("1"),
                harness.image("2", type = "application/pdf"),
                Uri.parse("file:///sdcard/a.jpg"),
                harness.image("3"),
            )
        assertEquals(ShareUiState.Ready(photoCount = 2, skipped = 2, notIncluded = 0), mixed.uiState.value)

        val many = ready(*Array(150) { harness.image("m$it") })
        assertEquals(ShareUiState.Ready(photoCount = 100, skipped = 0, notIncluded = 50), many.uiState.value)
    }

    @Test
    fun startTwice_readsTheShareOnce() {
        val vm = ready(harness.image("1"))

        vm.start(share(harness.image("2"), harness.image("3")))
        waitUntil { true }

        assertEquals(1, (vm.uiState.value as ShareUiState.Ready).photoCount)
    }

    @Test
    fun confirm_stagesEveryPhoto_andEnqueuesExactlyOneOccasionUpload() {
        val vm = ready(harness.image("1"), harness.image("2"))

        vm.confirm("  Trip  ")
        vm.confirm("Trip")
        waitUntil { vm.finish.value != null }
        vm.confirm("Trip")
        waitUntil { true }

        assertEquals(ShareFinish(), vm.finish.value)
        assertEquals(ShareUiState.Done, vm.uiState.value)
        val batch = harness.enqueued.single() as UploadBatch.Occasion
        assertEquals("Trip", batch.folderName)
        assertEquals(listOf("1.jpg", "2.jpg"), batch.photos.map { it.displayName })
        assertTrue(batch.photos.all { it.id == -1L })
        val stagedNames = batch.photos.map { requireNotNull(it.stagedName) }
        assertEquals(stagedNames.toSet(), harness.stagedFiles().map { it.name }.toSet())
    }

    @Test
    fun invalidOccasion_isIgnored() {
        val vm = ready(harness.image("1"))

        vm.confirm("..")
        waitUntil { true }

        assertTrue(vm.uiState.value is ShareUiState.Ready)
    }

    @Test
    fun autoDateFoldersOn_shareStillUsesTheOccasionFolder() {
        val vm = ready(harness.image("1"), vm = harness.viewModel(harness.configured(autoDate = true)))

        confirmAndWaitForFinish(vm)

        assertEquals("Trip", (harness.enqueued.single() as UploadBatch.Occasion).folderName)
    }

    @Test
    fun unreadableItem_isSkippedAndReported_theOthersAreQueued() {
        val vm = ready(harness.image("1"), harness.image("2", content = null), harness.image("3"))

        val finish = confirmAndWaitForFinish(vm)

        assertEquals(ShareFinish(skipped = 1), finish)
        assertEquals(
            listOf("1.jpg", "3.jpg"),
            harness.enqueued
                .single()
                .photos
                .map { it.displayName },
        )
    }

    @Test
    fun noItemCopied_failsWithNothingCopied_notCopyFailed_andQueuesNothing() {
        val vm = ready(harness.image("1", content = null), harness.image("2", content = null))

        val finish = confirmAndWaitForFinish(vm)

        assertEquals(ShareFinish(error = ShareError.NothingCopied), finish)
        assertEquals(ShareUiState.Error(ShareError.NothingCopied), vm.uiState.value)
        assertTrue(harness.enqueued.isEmpty())
        assertTrue(harness.stagedFiles().isEmpty())
    }

    @Test
    fun itemsOverTheShareBudget_areReportedSeparately() {
        val limited =
            ShareTestHarness(
                app,
                temp.newFolder("limited_staging"),
                temp.newFolder("limited_queues"),
                limits = StagingLimits(maxFileBytes = 1_000, maxShareBytes = 1_500, safetyMarginBytes = 0),
            )
        val vm = limited.viewModel()
        vm.start(
            share(
                limited.image("1", content = ByteArray(800)),
                limited.image("2", content = ByteArray(800)),
                limited.image("3", content = null),
            ),
        )
        waitUntil { vm.uiState.value is ShareUiState.Ready }

        val finish = confirmAndWaitForFinish(vm)

        assertEquals(ShareFinish(skipped = 1, overShareLimit = 1), finish)
        assertEquals(
            1,
            limited.enqueued
                .single()
                .photos.size,
        )
    }

    @Test
    fun noFreeSpace_failsWithCopyFailed_andLeavesNoFiles() {
        harness.freeBytes = 0
        val vm = ready(harness.image("1"))

        assertEquals(ShareFinish(error = ShareError.CopyFailed), confirmAndWaitForFinish(vm))
        assertTrue(harness.stagedFiles().isEmpty())
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun cancelDuringCopy_stopsIt_deletesTheBatchFiles_andQueuesNothing() {
        val (gated, gate) = harness.gatedImage("2")
        val vm = ready(harness.image("1"), gated)

        vm.confirm("Trip")
        assertTrue(gate.started.await(5, TimeUnit.SECONDS))
        assertEquals(ShareUiState.Copying(current = 2, total = 2), vm.uiState.value)
        vm.cancel()
        waitUntil { vm.finish.value != null }

        assertEquals(ShareFinish(), vm.finish.value)
        assertEquals(ShareUiState.Cancelled, vm.uiState.value)
        waitUntil { gate.cancelled && harness.stagedFiles().isEmpty() }
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun cancelWhileTheProviderIgnoresTheSignal_publishesCancelledAtOnce() {
        val (gated, gate) = harness.gatedImage("2", honoursCancel = false)
        try {
            val vm = ready(harness.image("1"), gated)
            vm.confirm("Trip")
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))

            vm.cancel()

            // Synchronous: no join of the copy job blocked in the provider's open.
            assertEquals(ShareUiState.Cancelled, vm.uiState.value)
            assertEquals(ShareFinish(), vm.finish.value)
            waitUntil { gate.cancelled && harness.stagedFiles().isEmpty() }
            gate.release()
            Thread.sleep(100)
            assertTrue(harness.stagedFiles().isEmpty())
            assertTrue(harness.enqueued.isEmpty())
        } finally {
            gate.release()
        }
    }

    @Test
    fun cancelWhilePreparing_finishesWithoutReadingFurther() {
        val gate = ProviderGate(honoursCancel = false)
        FakeShareProvider.items["slow"] = FakeShareProvider.Item(name = "slow.jpg", queryGate = gate)
        try {
            val vm = harness.viewModel()
            vm.start(share(FakeShareProvider.uri("slow")))
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            assertEquals(ShareUiState.Preparing, vm.uiState.value)

            vm.cancel()

            assertEquals(ShareUiState.Cancelled, vm.uiState.value)
            assertTrue(harness.enqueued.isEmpty())
        } finally {
            gate.release()
        }
    }

    @Test
    fun cancelInTheDialog_finishesWithoutCopying() {
        val vm = ready(harness.image("1"))

        vm.cancel()
        vm.confirm("Trip")
        waitUntil { true }

        assertEquals(ShareFinish(), vm.finish.value)
        assertTrue(harness.stagedFiles().isEmpty())
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun enqueueFailure_deletesTheStagedFiles_andShowsTheFailureMessage() {
        harness.failEnqueue = true
        val vm = ready(harness.image("1"), harness.image("2"))

        confirmAndWaitForFinish(vm)

        waitUntil { harness.stagedFiles().isEmpty() && ShadowToast.getLatestToast() != null }
        assertEquals(app.getString(R.string.share_enqueue_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun clearingTheViewModelDuringCopy_deletesTheBatchFiles() {
        val (gated, gate) = harness.gatedImage("2")
        val store = ViewModelStore()
        val vm =
            ViewModelProvider(
                store,
                viewModelFactory { initializer { harness.viewModel() } },
            )[ShareViewModel::class.java]
        ready(harness.image("1"), gated, vm = vm)

        vm.confirm("Trip")
        assertTrue(gate.started.await(5, TimeUnit.SECONDS))
        store.clear()

        waitUntil { gate.cancelled && harness.stagedFiles().isEmpty() }
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun consumeFinish_clearsTheOneShotFinish() {
        val vm = harness.viewModel()
        vm.start(share(Uri.parse("file:///sdcard/a.jpg")))

        vm.consumeFinish()

        assertNull(vm.finish.value)
    }
}
