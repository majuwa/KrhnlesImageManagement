package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeOnboardingRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialCorruption
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.StartDestination
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.StartupViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.isExistingUser
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.resolveStartDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(org.junit.runners.Parameterized::class)
class StartDestinationTruthTableTest(
    private val completed: Boolean,
    private val configured: Boolean,
    private val url: String,
    private val state: CredentialState,
    private val expected: StartDestination,
) {
    companion object {
        private val corrupted = CredentialState.Corrupted(CredentialCorruption.UNREADABLE)

        @JvmStatic
        @org.junit.runners.Parameterized.Parameters(name = "completed={0} configured={1} url={2} state={3} -> {4}")
        fun cases(): List<Array<Any>> =
            listOf(
                arrayOf(false, false, "", CredentialState.Missing, StartDestination.Onboarding),
                arrayOf(false, true, "https://x", CredentialState.Valid, StartDestination.Photos),
                arrayOf(false, false, "https://x", CredentialState.Missing, StartDestination.Photos),
                arrayOf(false, false, "", corrupted, StartDestination.Photos),
                arrayOf(false, false, "", CredentialState.Valid, StartDestination.Photos),
                arrayOf(false, false, "https://x", corrupted, StartDestination.Photos),
                arrayOf(true, false, "", CredentialState.Missing, StartDestination.Photos),
                arrayOf(true, true, "https://x", CredentialState.Valid, StartDestination.Photos),
                arrayOf(true, false, "https://x", corrupted, StartDestination.Photos),
                arrayOf(true, false, "", corrupted, StartDestination.Photos),
            )
    }

    @Test
    fun `resolves the expected destination`() {
        val existing = isExistingUser(configured, url, state)
        assertEquals(expected, resolveStartDestination(completed, existing))
    }

    @Test
    fun `whitespace-only url is not a stored url`() {
        assertFalse(isExistingUser(false, " ", CredentialState.Missing))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StartupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val configured = WebDavConfig("https://c.example/dav/", "u", "p", "")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `fresh install resolves to onboarding and does not set the flag`() =
        runTest(dispatcher) {
            val onboarding = FakeOnboardingRepository()
            val vm = StartupViewModel(app, onboarding, FakeCredentialRepository())
            assertNull(vm.destination.value)
            advanceUntilIdle()
            assertEquals(StartDestination.Onboarding, vm.destination.value)
            assertFalse(onboarding.isCompleted())
        }

    @Test
    fun `configured upgrade user goes to photos and flag is set silently`() =
        runTest(dispatcher) {
            val onboarding = FakeOnboardingRepository()
            val vm = StartupViewModel(app, onboarding, FakeCredentialRepository(configured))
            advanceUntilIdle()
            assertEquals(StartDestination.Photos, vm.destination.value)
            assertTrue(onboarding.isCompleted())
        }

    @Test
    fun `corrupted credentials upgrade user goes to photos and flag is set`() =
        runTest(dispatcher) {
            val onboarding = FakeOnboardingRepository()
            val creds = FakeCredentialRepository()
            creds.credentialStateOverride.value = CredentialState.Corrupted(CredentialCorruption.KEY_INVALIDATED)
            val vm = StartupViewModel(app, onboarding, creds)
            advanceUntilIdle()
            assertEquals(StartDestination.Photos, vm.destination.value)
            assertTrue(onboarding.isCompleted())
        }

    @Test
    fun `completed and logged out goes to photos without writing again`() =
        runTest(dispatcher) {
            val onboarding = FakeOnboardingRepository(initialCompleted = true)
            val vm = StartupViewModel(app, onboarding, FakeCredentialRepository())
            advanceUntilIdle()
            assertEquals(StartDestination.Photos, vm.destination.value)
            assertEquals(0, onboarding.markCompletedCalls)
        }

    @Test
    fun `failing flag write for an existing user still resolves to photos`() =
        runTest(dispatcher) {
            val onboarding = FakeOnboardingRepository().apply { failure = IllegalStateException("x") }
            val vm = StartupViewModel(app, onboarding, FakeCredentialRepository(configured))
            advanceUntilIdle()
            assertEquals(StartDestination.Photos, vm.destination.value)
        }

    @Test
    fun `hanging store falls back to photos after the timeout`() =
        runTest(dispatcher) {
            val hanging =
                object : de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingRepository {
                    override val completed =
                        kotlinx.coroutines.flow.flow<Boolean> {
                            CompletableDeferred<Unit>().await()
                        }

                    override suspend fun markCompleted() = Unit
                }
            val vm = StartupViewModel(app, hanging, FakeCredentialRepository(), timeoutMillis = 1_000)
            advanceTimeBy(500)
            assertNull(vm.destination.value)
            advanceTimeBy(600)
            advanceUntilIdle()
            assertEquals(StartDestination.Photos, vm.destination.value)
        }

    @Test
    fun `throwing store falls back to photos`() =
        runTest(dispatcher) {
            val throwing =
                object : de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingRepository {
                    override val completed = kotlinx.coroutines.flow.flow<Boolean> { error("boom") }

                    override suspend fun markCompleted() = Unit
                }
            val vm = StartupViewModel(app, throwing, FakeCredentialRepository())
            advanceUntilIdle()
            assertEquals(StartDestination.Photos, vm.destination.value)
        }

    @Test
    fun `later flow changes do not change the resolved destination`() =
        runTest(dispatcher) {
            val onboarding = FakeOnboardingRepository()
            val creds = FakeCredentialRepository()
            val vm = StartupViewModel(app, onboarding, creds)
            advanceUntilIdle()
            creds.save("https://c.example/dav/", "u", "p")
            onboarding.markCompleted()
            advanceUntilIdle()
            assertEquals(StartDestination.Onboarding, vm.destination.value)
        }
}
