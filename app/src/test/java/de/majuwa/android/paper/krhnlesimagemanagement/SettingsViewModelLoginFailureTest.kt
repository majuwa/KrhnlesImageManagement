package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

    @Test
    fun `failed login flow maps to the generic typed error`() =
        runTest {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            try {
                val viewModel =
                    SettingsViewModel(
                        application = RuntimeEnvironment.getApplication() as Application,
                        credentialStore =
                            FakeCredentialRepository(
                                WebDavConfig("https://c.example.com/dav/", "u", "p"),
                            ),
                        trustedCertificates = FakeTrustedCertificateRepository(),
                    )
                viewModel.onServerUrlChange(server.url("/").toString())
                viewModel.startLoginFlow()
                val deadline = System.nanoTime() + 10_000_000_000L
                while (viewModel.uiState.value.error == null && System.nanoTime() < deadline) {
                    advanceUntilIdle()
                    Thread.sleep(20)
                }
                assertEquals(SettingsError.LoginFailed, viewModel.uiState.value.error)
                assertFalse(viewModel.uiState.value.isLoading)
            } finally {
                server.shutdown()
            }
        }
}
