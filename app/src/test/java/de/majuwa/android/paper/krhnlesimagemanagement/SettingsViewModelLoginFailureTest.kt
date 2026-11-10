package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import de.majuwa.android.nextcloudlogin.LoginFlowState
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Every login-flow failure maps to the one generic typed error; the library message is never shown. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelLoginFailureTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(state: LoginFlowState) =
        SettingsViewModel(
            application = RuntimeEnvironment.getApplication() as Application,
            credentialStore = FakeCredentialRepository(WebDavConfig("https://c.example.com/dav/", "u", "p")),
            loginFlow = { flowOf(state) },
            trustedCertificates = FakeTrustedCertificateRepository(),
        )

    private fun failWith(message: String) =
        runTest(dispatcher) {
            val vm = viewModel(LoginFlowState.Failed(message))
            vm.onServerUrlChange("https://c.example.com")
            vm.startLoginFlow()
            advanceUntilIdle()
            assertEquals(SettingsError.LoginFailed, vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
            assertFalse(vm.uiState.value.isWaitingForBrowser)
        }

    @Test
    fun `failed login flow maps to the generic typed error`() = failWith("Server responded 500")

    @Test
    fun `login flow timeout maps to the generic typed error`() = failWith("Login timed out. Please try again.")
}
