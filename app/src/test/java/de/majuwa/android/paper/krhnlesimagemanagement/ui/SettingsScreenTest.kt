package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
        composeTestRule.onNodeWithText("Test Connection").assertIsDisplayed()
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
}
