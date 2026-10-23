package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeOnboardingRepository
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.FINISH_TIMEOUT_MILLIS
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingExit
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val app get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `next moves to the connect page and back returns`() {
        val vm = OnboardingViewModel(app, SavedStateHandle(), FakeOnboardingRepository())
        assertEquals(0, vm.page.value)
        assertFalse(vm.back())
        vm.next()
        assertEquals(1, vm.page.value)
        vm.next()
        assertEquals(1, vm.page.value)
        assertTrue(vm.back())
        assertEquals(0, vm.page.value)
    }

    @Test
    fun `page is restored from the saved state handle`() {
        val handle = SavedStateHandle(mapOf("page" to 1))
        val vm = OnboardingViewModel(app, handle, FakeOnboardingRepository())
        assertEquals(1, vm.page.value)
    }

    @Test
    fun `exit is emitted only after the flag was persisted`() =
        runTest(dispatcher) {
            val repo = FakeOnboardingRepository().apply { gate = CompletableDeferred() }
            val vm = OnboardingViewModel(app, SavedStateHandle(), repo)
            vm.finish(OnboardingExit.Settings)
            runCurrent()
            assertNull(vm.exit.value)
            assertTrue(vm.isFinishing.value)
            assertFalse(repo.isCompleted())

            repo.gate!!.complete(Unit)
            advanceUntilIdle()
            assertTrue(repo.isCompleted())
            assertEquals(OnboardingExit.Settings, vm.exit.value)
            vm.consumeExit()
            assertNull(vm.exit.value)
        }

    @Test
    fun `repeated finish persists and exits once`() =
        runTest(dispatcher) {
            val repo = FakeOnboardingRepository().apply { gate = CompletableDeferred() }
            val vm = OnboardingViewModel(app, SavedStateHandle(), repo)
            vm.finish(OnboardingExit.Photos)
            vm.finish(OnboardingExit.Settings)
            runCurrent()
            repo.gate!!.complete(Unit)
            advanceUntilIdle()
            vm.finish(OnboardingExit.Settings)
            advanceUntilIdle()
            assertEquals(1, repo.markCompletedCalls)
            assertEquals(OnboardingExit.Photos, vm.exit.value)
        }

    @Test
    fun `persist failure still exits and releases the guard`() =
        runTest(dispatcher) {
            val repo = FakeOnboardingRepository().apply { failure = java.io.IOException("disk") }
            val vm = OnboardingViewModel(app, SavedStateHandle(), repo)
            vm.finish(OnboardingExit.Photos)
            advanceUntilIdle()
            assertEquals(OnboardingExit.Photos, vm.exit.value)
        }

    @Test
    fun `finish stays latched after the exit was consumed`() =
        runTest(dispatcher) {
            val repo = FakeOnboardingRepository()
            val vm = OnboardingViewModel(app, SavedStateHandle(), repo)
            vm.finish(OnboardingExit.Photos)
            advanceUntilIdle()
            vm.consumeExit()
            vm.finish(OnboardingExit.Settings)
            advanceUntilIdle()
            assertEquals(1, repo.markCompletedCalls)
            assertNull(vm.exit.value)
            assertTrue(vm.isFinishing.value)
        }

    @Test
    fun `stalled persist times out and still exits`() =
        runTest(dispatcher) {
            val repo = FakeOnboardingRepository().apply { gate = CompletableDeferred() }
            val vm = OnboardingViewModel(app, SavedStateHandle(), repo)
            vm.finish(OnboardingExit.Settings)
            advanceTimeBy(FINISH_TIMEOUT_MILLIS - 1)
            assertNull(vm.exit.value)
            assertTrue(vm.isFinishing.value)
            advanceTimeBy(2)
            advanceUntilIdle()
            assertEquals(OnboardingExit.Settings, vm.exit.value)
            assertFalse(repo.isCompleted())
        }

    @Test
    fun `cancellation is not swallowed and releases the guard`() =
        runTest(dispatcher) {
            val repo = FakeOnboardingRepository().apply { failure = CancellationException("cancelled") }
            val vm = OnboardingViewModel(app, SavedStateHandle(), repo)
            vm.finish(OnboardingExit.Photos)
            advanceUntilIdle()
            assertNull(vm.exit.value)
            assertFalse(vm.isFinishing.value)
        }
}
