package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialCorruption
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

private const val HINT_AUTO_DATE_FOLDERS =
    "Skip the occasion dialog and upload into YYYY/MM-Month folders based on each photo's " +
        "capture date (for example 2026/05-May)."

private const val MSG_KEY_INVALIDATED =
    "Your stored credentials could not be decrypted because the device security settings changed. " +
        "Please log in again."
private const val MSG_CERTIFICATE =
    "Secure connection failed: the server's certificate is not trusted (it may be self-signed or expired)."
private const val MSG_UNKNOWN_HOST = "Server not found. Check the server address and your internet connection."
private const val MSG_TIMEOUT = "The server did not respond in time. Please try again."
private const val MSG_AUTH = "Access denied. Check your username and app password, then log in again."
private const val MSG_NOT_FOUND = "WebDAV folder not found on the server. Check the server address."
private const val MSG_UNKNOWN_ERROR = "Connection test failed. Please try again."

private const val MSG_UNREADABLE =
    "Your stored credentials could not be read and have been ignored. Please log in again."

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createLoggedInViewModel(): SettingsViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        return SettingsViewModel(
            application = app,
            credentialStore =
                FakeCredentialRepository(
                    WebDavConfig("https://cloud.example.com/dav/", "testuser", "pass", "Photos"),
                ),
        )
    }

    private fun createLoggedOutViewModel(): SettingsViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        return SettingsViewModel(
            application = app,
            credentialStore = FakeCredentialRepository(WebDavConfig()),
        )
    }

    private fun createLoggedInViewModel(tester: suspend (WebDavConfig) -> Result<Unit>): SettingsViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        return SettingsViewModel(
            application = app,
            credentialStore =
                FakeCredentialRepository(
                    WebDavConfig("https://cloud.example.com/dav/", "testuser", "pass", "Photos"),
                ),
            connectionTester = tester,
        )
    }

    private fun clickTestConnection(viewModel: SettingsViewModel) {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = viewModel, onNavigateBack = {})
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(hasText("Connected"), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText("Test Connection").performScrollTo().performClick()
    }

    // ── Connection test result (issue #22 / #41) ─────────────────────────────

    @Test
    fun `shows localized success message after successful connection test`() {
        clickTestConnection(createLoggedInViewModel { Result.success(Unit) })
        composeTestRule.waitUntilAtLeastOneExists(hasText("Connection successful!"), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText("Connection successful!").performScrollTo().assertIsDisplayed()
    }

    private fun assertFailureMessage(
        failure: Throwable,
        expected: String,
    ) {
        clickTestConnection(createLoggedInViewModel { Result.failure(failure) })
        composeTestRule.waitUntilAtLeastOneExists(hasText(expected), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        // Raw exception text must never reach the UI.
        failure.message?.let { composeTestRule.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
        composeTestRule.onAllNodesWithText("Failed:", substring = true).assertCountEquals(0)
    }

    @Test
    fun `shows certificate message instead of raw TLS exception`() =
        assertFailureMessage(
            SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor not found"),
            MSG_CERTIFICATE,
        )

    @Test
    fun `shows unknown host message`() =
        assertFailureMessage(UnknownHostException("Unable to resolve host cloud.example.com"), MSG_UNKNOWN_HOST)

    @Test
    fun `shows timeout message`() = assertFailureMessage(SocketTimeoutException("timeout"), MSG_TIMEOUT)

    @Test
    fun `shows auth message instead of raw HTTP 401 text`() =
        assertFailureMessage(WebDavHttpException(401, "Connection failed: HTTP 401 Unauthorized"), MSG_AUTH)

    @Test
    fun `shows not found message for HTTP 404`() =
        assertFailureMessage(WebDavHttpException(404, "Connection failed: HTTP 404 Not Found"), MSG_NOT_FOUND)

    @Test
    fun `shows HTTP code for other server errors`() =
        assertFailureMessage(
            WebDavHttpException(503, "Connection failed: HTTP 503 Service Unavailable"),
            "The server responded with an error (HTTP 503). Please try again later.",
        )

    @Test
    fun `shows generic message for unknown failures without detail`() =
        assertFailureMessage(IllegalStateException(), MSG_UNKNOWN_ERROR)

    // ── Logged-in state ─────────────────────────────────────────────────────

    @Test
    fun `shows Settings title`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = createLoggedInViewModel(), onNavigateBack = {})
            }
        }
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `shows Connected status when logged in`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = createLoggedInViewModel(), onNavigateBack = {})
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(hasText("Connected"), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText("Connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("User: testuser").assertIsDisplayed()
    }

    @Test
    fun `shows Test Connection and Disconnect buttons when logged in`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = createLoggedInViewModel(), onNavigateBack = {})
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(hasText("Connected"), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText("Test Connection").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Disconnect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `shows Save folder button when logged in`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = createLoggedInViewModel(), onNavigateBack = {})
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(hasText("Connected"), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText("Save folder").assertIsDisplayed()
    }

    @Test
    fun `shows auto date folders setting when logged in`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = createLoggedInViewModel(), onNavigateBack = {})
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(hasText("Connected"), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText("Auto date folders").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText(HINT_AUTO_DATE_FOLDERS)
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ── Logged-out state ────────────────────────────────────────────────────

    @Test
    fun `shows login prompt when not connected`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = createLoggedOutViewModel(), onNavigateBack = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Connect to Nextcloud").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect via Browser").assertIsDisplayed()
    }

    @Test
    fun `shows manual config section on toggle`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = createLoggedOutViewModel(), onNavigateBack = {})
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(
            hasText("Manual WebDAV config"),
            timeoutMillis = 5_000,
        )
        composeTestRule.onNodeWithText("Manual WebDAV config").performClick()
        composeTestRule.waitUntilAtLeastOneExists(
            hasText("Save & Connect"),
            timeoutMillis = 5_000,
        )
        composeTestRule.onNodeWithText("Save & Connect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `shows Wi-Fi only toggle when logged in`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = createLoggedInViewModel(), onNavigateBack = {})
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(hasText("Connected"), timeoutMillis = 5_000)
        composeTestRule
            .onNodeWithText("Upload on Wi-Fi only")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ── Credential corruption (issue #31 / #50) ─────────────────────────────

    private fun createCorruptedViewModel(reason: CredentialCorruption): SettingsViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        val repo = FakeCredentialRepository(WebDavConfig())
        repo.credentialStateOverride.value = CredentialState.Corrupted(reason)
        return SettingsViewModel(application = app, credentialStore = repo)
    }

    @Test
    fun `shows security settings changed message when key was invalidated`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(
                    viewModel = createCorruptedViewModel(CredentialCorruption.KEY_INVALIDATED),
                    onNavigateBack = {},
                )
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(hasText(MSG_KEY_INVALIDATED), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText(MSG_KEY_INVALIDATED).assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect via Browser").assertIsDisplayed()
    }

    @Test
    fun `shows unreadable message when stored credentials are corrupted`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(
                    viewModel = createCorruptedViewModel(CredentialCorruption.UNREADABLE),
                    onNavigateBack = {},
                )
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(hasText(MSG_UNREADABLE), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText(MSG_UNREADABLE).assertIsDisplayed()
    }

    @Test
    fun `shows no credential message when nothing is stored`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                SettingsScreen(viewModel = createLoggedOutViewModel(), onNavigateBack = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(MSG_KEY_INVALIDATED).assertDoesNotExist()
        composeTestRule.onNodeWithText(MSG_UNREADABLE).assertDoesNotExist()
    }
}
