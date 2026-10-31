package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaPermissionRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/** The guarded one-shot behind the banner's "Cancel upload" button. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoGridViewModelCancelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<UUID>()
    private var gate: CompletableDeferred<Unit>? = null
    private var failure: Throwable? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): PhotoGridViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        return PhotoGridViewModel(
            app,
            FakeMediaRepository(),
            FakeCredentialRepository(),
            FakeUploadedPhotosRepository(),
            WorkManager.getInstance(app),
            FakeMediaPermissionRepository(),
        ) { _, id ->
            calls += id
            gate?.await()
            failure?.let { throw it }
            true
        }
    }

    @Test
    fun cancelUpload_cancelsTheGivenWork() =
        runTest {
            val id = UUID.randomUUID()
            val vm = viewModel()

            vm.cancelUpload(id)
            advanceUntilIdle()

            assertEquals(listOf(id), calls)
        }

    @Test
    fun cancelUpload_ignoresAReentryWhileTheFirstIsRunning() =
        runTest {
            val id = UUID.randomUUID()
            gate = CompletableDeferred()
            val vm = viewModel()

            vm.cancelUpload(id)
            advanceUntilIdle()
            vm.cancelUpload(id)
            vm.cancelUpload(UUID.randomUUID())
            advanceUntilIdle()
            assertEquals(listOf(id), calls)

            // Once the first one is done, a new tap is handled again.
            gate?.complete(Unit)
            advanceUntilIdle()
            vm.cancelUpload(id)
            advanceUntilIdle()
            assertEquals(listOf(id, id), calls)
        }

    @Test
    fun cancelUpload_swallowsAFailure_andStaysUsable() =
        runTest {
            failure = IllegalStateException("WorkManager gone")
            val id = UUID.randomUUID()
            val vm = viewModel()

            vm.cancelUpload(id)
            advanceUntilIdle()
            failure = null
            vm.cancelUpload(id)
            advanceUntilIdle()

            assertEquals(listOf(id, id), calls)
        }
}
