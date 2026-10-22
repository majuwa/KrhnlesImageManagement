package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

private const val HINT_AUTO_DATE_FOLDERS =
    "Skip the occasion dialog and upload into YYYY/MM-Month folders based on each photo's " +
        "capture date (for example 2026/05-May)."

private const val MSG_KEY_INVALIDATED =
    "Your stored credentials could not be decrypted because the device security settings changed. " +
        "Please log in again."
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
