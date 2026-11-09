package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeReminderScheduler
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The reminder rows of the real [SettingsScreen]; permission requests go through a fake registry. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ReminderSettingUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private val requests = mutableListOf<String>()
    private var grantOnRequest = false
    private val store = FakeCredentialRepository(WebDavConfig("https://cloud.example.com/dav/", "u", "p", "Photos"))
    private val scheduler = FakeReminderScheduler()

    private val registry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                requests += input as String
                if (grantOnRequest) shadowOf(app).grantPermissions(input)
                dispatchResult(requestCode, grantOnRequest)
            }
        }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun show() {
        val viewModel =
            SettingsViewModel(
                application = app,
                credentialStore = store,
                trustedCertificates = FakeTrustedCertificateRepository(),
                reminderScheduler = scheduler,
            )
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                KrhnlesImageManagementTheme { SettingsScreen(viewModel = viewModel, onNavigateBack = {}) }
            }
        }
        composeTestRule.waitUntilAtLeastOneExists(hasText("Connected"), timeoutMillis = 5_000)
    }

    private fun toggle() {
        composeTestRule.onNodeWithTag("newPhotosReminderRow").performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `row is one switch node and the interval is hidden while off`() {
        show()
        composeTestRule.onNodeWithTag("newPhotosReminderRow").performScrollTo().assertIsOff()
        composeTestRule
            .onNodeWithTag("newPhotosReminderRow")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        composeTestRule
            .onAllNodes(isToggleable() and hasText("Remind me about new photos"))
            .assertCountEquals(1)
        composeTestRule
            .onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag("newPhotosReminderRow")), useUnmergedTree = true)
            .assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("reminderInterval_Weekly").assertCountEquals(0)
    }

    @Test
    fun `enabling shows Weekly selected with radio semantics and selecting another updates`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        toggle()

        composeTestRule.onNodeWithTag("newPhotosReminderRow").assertIsOn()
        composeTestRule.onNodeWithTag("reminderInterval_Weekly").performScrollTo().assertIsSelected()
        listOf("Weekly", "Every 2 weeks", "Monthly").forEach {
            composeTestRule.onNodeWithText(it).assertExists()
        }
        composeTestRule
            .onNodeWithTag("reminderInterval_Monthly")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

        composeTestRule.onNodeWithTag("reminderInterval_Monthly").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("reminderInterval_Monthly").assertIsSelected()
        assertEquals(listOf("schedule(Weekly)", "schedule(Monthly)"), scheduler.calls)
        assertEquals(ReminderInterval.Monthly, store.savedReminders.last().second)
    }

    @Test
    fun `the permission is requested only when missing`() {
        grantOnRequest = true
        show()
        toggle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), requests)

        // Now granted: turning it off and on again asks nothing.
        toggle()
        toggle()
        assertEquals(1, requests.size)
    }

    @Test
    fun `a denial keeps the reminder on and shows the hint`() {
        grantOnRequest = false
        show()
        toggle()

        composeTestRule.onNodeWithTag("newPhotosReminderRow").assertIsOn()
        composeTestRule.onNodeWithText("Open notification settings").performScrollTo().assertExists()
        assertTrue(store.savedReminders.single().first)
    }

    @Test
    fun `granted permission means no request and no hint`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        toggle()

        assertTrue(requests.isEmpty())
        composeTestRule.onAllNodesWithText("Open notification settings").assertCountEquals(0)
    }

    @Test
    fun `the hint action opens the app notification settings`() {
        show()
        toggle()
        composeTestRule.onNodeWithText("Open notification settings").performScrollTo().performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `the hint disappears after notifications were enabled and Settings resumes`() {
        show()
        toggle()
        composeTestRule.onNodeWithText("Open notification settings").assertExists()

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Open notification settings").assertCountEquals(0)
    }
}
