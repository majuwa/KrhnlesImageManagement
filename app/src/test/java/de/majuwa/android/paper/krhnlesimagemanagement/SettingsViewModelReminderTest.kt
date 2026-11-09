package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeReminderScheduler
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVerifiedUploadsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.ReminderSettingsController
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelReminderTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var credentials: FakeCredentialRepository
    private val scheduler = FakeReminderScheduler()
    private lateinit var controller: ReminderSettingsController
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        credentials = FakeCredentialRepository(WebDavConfig("https://c.example.com/dav/", "u", "p"))
        controller = ReminderSettingsController(CoroutineScope(SupervisorJob() + dispatcher), credentials, scheduler)
        viewModel =
            SettingsViewModel(
                application = RuntimeEnvironment.getApplication() as Application,
                credentialStore = credentials,
                uploadedPhotosRepository = FakeUploadedPhotosRepository(),
                verifiedUploadsRepository = FakeVerifiedUploadsRepository(),
                trustedCertificates = FakeTrustedCertificateRepository(),
                reminder = controller,
            )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `enable saves then schedules with the interval`() =
        runTest {
            viewModel.reminder.change(true, ReminderInterval.EveryTwoWeeks)
            advanceUntilIdle()

            assertEquals(listOf(true to ReminderInterval.EveryTwoWeeks), credentials.savedReminders)
            assertEquals(listOf("schedule(EveryTwoWeeks)"), scheduler.calls)
            assertTrue(viewModel.uiState.value.newPhotosReminder.enabled)
            assertEquals(ReminderInterval.EveryTwoWeeks, viewModel.uiState.value.newPhotosReminder.interval)
        }

    @Test
    fun `interval change updates, disable cancels`() =
        runTest {
            viewModel.reminder.change(true, ReminderInterval.Weekly)
            viewModel.reminder.change(true, ReminderInterval.Monthly)
            viewModel.reminder.change(false, ReminderInterval.Monthly)
            advanceUntilIdle()

            assertEquals(listOf("schedule(Weekly)", "schedule(Monthly)", "cancel"), scheduler.calls)
            assertFalse(viewModel.uiState.value.newPhotosReminder.enabled)
        }

    @Test
    fun `a failing save calls no scheduler`() =
        runTest {
            credentials.reminderSaveFailure = java.io.IOException("disk")
            viewModel.reminder.change(true, ReminderInterval.Weekly)
            advanceUntilIdle()

            assertTrue(scheduler.calls.isEmpty())
            assertFalse(viewModel.uiState.value.newPhotosReminder.enabled)
        }

    @Test
    fun `quick on off on reaches the scheduler in order and ends scheduled`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            scheduler.gate = gate
            viewModel.reminder.change(true, ReminderInterval.Weekly)
            viewModel.reminder.change(false, ReminderInterval.Weekly)
            viewModel.reminder.change(true, ReminderInterval.Weekly)
            // runCurrent, not advanceUntilIdle: virtual time must not run into the lock timeout.
            dispatcher.scheduler.runCurrent()
            // Held back inside the first call: the others wait for the lock, not racing it.
            assertEquals(listOf("schedule(Weekly)"), scheduler.calls)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("schedule(Weekly)", "cancel", "schedule(Weekly)"), scheduler.calls)
            assertTrue(viewModel.uiState.value.newPhotosReminder.enabled)
        }

    @Test
    fun `logout cancels the check and resets the setting`() =
        runTest {
            viewModel.reminder.change(true, ReminderInterval.Monthly)
            advanceUntilIdle()
            scheduler.calls.clear()

            viewModel.logout()
            advanceUntilIdle()

            assertTrue("cancel" in scheduler.calls)
            assertFalse(viewModel.uiState.value.newPhotosReminder.enabled)
            assertEquals(ReminderInterval.Weekly, viewModel.uiState.value.newPhotosReminder.interval)
        }

    @Test
    fun `leaving Settings between the save and the schedule still schedules`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            scheduler.gate = gate
            viewModel.reminder.change(true, ReminderInterval.Weekly)
            dispatcher.scheduler.runCurrent()
            // Saved, now held back inside the schedule call; the screen goes away.
            assertTrue(credentials.savedReminders.isNotEmpty())
            viewModel.viewModelScope.cancel()

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("schedule(Weekly)"), scheduler.completed)
            assertTrue(credentials.newPhotosReminderValue().enabled)
        }

    @Test
    fun `the start reconcile waits for a change in flight and sees its value, once per process`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            scheduler.gate = gate
            viewModel.reminder.change(true, ReminderInterval.Monthly)
            val first = controller.reconcileOnce()
            assertTrue(first != null)
            assertEquals(null, controller.reconcileOnce())
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf("schedule(Monthly)"), scheduler.calls)

            gate.complete(Unit)
            advanceUntilIdle()

            // Never the stale "off" it would have read without the lock.
            assertEquals(listOf("schedule(Monthly)", "reconcile(true,Monthly)"), scheduler.calls)
        }

    @Test
    fun `logout after a change in flight ends disabled with cancel as the last scheduler call`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            scheduler.gate = gate
            viewModel.reminder.change(true, ReminderInterval.Weekly)
            dispatcher.scheduler.runCurrent()
            viewModel.logout()
            dispatcher.scheduler.runCurrent()

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("schedule(Weekly)", "cancel"), scheduler.calls)
            assertFalse(credentials.newPhotosReminderValue().enabled)
        }

    @Test
    fun `logout resets the reminder even when the screen is left while it runs`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            scheduler.gate = gate
            viewModel.logout()
            dispatcher.scheduler.runCurrent()
            viewModel.viewModelScope.cancel()

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("cancel"), scheduler.completed)
        }
}
