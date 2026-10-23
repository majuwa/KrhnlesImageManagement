package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeOnboardingRepository
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingExit
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val OVERVIEW_TITLE = "Back up your photos your way"
private const val CONNECT_TITLE = "Connect your server"

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repo: FakeOnboardingRepository
    private lateinit var vm: OnboardingViewModel
    private val exits = mutableListOf<OnboardingExit>()

    @Before
    fun setUp() {
        repo = FakeOnboardingRepository()
        exits.clear()
    }

    private fun show(page: Int = 0) {
        vm =
            OnboardingViewModel(
                RuntimeEnvironment.getApplication() as Application,
                SavedStateHandle(mapOf("page" to page)),
                repo,
            )
        rule.setContent {
            KrhnlesImageManagementTheme { OnboardingScreen(vm, onExit = { exits += it }) }
        }
    }

    private fun hasPaneTitle(title: String) =
        SemanticsMatcher("paneTitle=$title") { it.config.getOrNull(SemanticsProperties.PaneTitle) == title }

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun overviewPageHasHeadingPaneTitleAndActions() {
        show()
        rule.onNode(isHeading and hasText(OVERVIEW_TITLE)).assertExists()
        rule.onNode(hasPaneTitle(OVERVIEW_TITLE)).assertExists()
        rule.onNodeWithText("Step 1 of 2").assertExists()
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.onNodeWithText("Skip").assertIsEnabled()
    }

    @Test
    fun nextShowsConnectPageWithCta() {
        show()
        rule.onNodeWithText("Next").performClick()
        rule.onNode(isHeading and hasText(CONNECT_TITLE)).assertExists()
        rule.onNode(hasPaneTitle(CONNECT_TITLE)).assertExists()
        rule.onNodeWithText("Step 2 of 2").assertExists()
        rule.onNodeWithText("Connect now").assertIsEnabled()
        rule.onNodeWithText("Skip").assertIsEnabled()
    }

    @Test
    fun skipOnOverviewPersistsThenExitsToPhotos() {
        show()
        rule.onNodeWithText("Skip").performClick()
        rule.waitForIdle()
        assertTrue(repo.isCompleted())
        assertEquals(listOf(OnboardingExit.Photos), exits)
    }

    @Test
    fun skipOnConnectPersistsThenExitsToPhotos() {
        show(page = 1)
        rule.onNodeWithText("Skip").performClick()
        rule.waitForIdle()
        assertTrue(repo.isCompleted())
        assertEquals(listOf(OnboardingExit.Photos), exits)
    }

    @Test
    fun connectNowPersistsThenExitsToSettings() {
        show(page = 1)
        rule.onNodeWithText("Connect now").performClick()
        rule.waitForIdle()
        assertTrue(repo.isCompleted())
        assertEquals(listOf(OnboardingExit.Settings), exits)
    }

    @Test
    fun backOnConnectPageReturnsToOverviewWithoutPersisting() {
        show(page = 1)
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNode(isHeading and hasText(OVERVIEW_TITLE)).assertExists()
        assertFalse(repo.isCompleted())
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun backOnOverviewPageExitsAppWithoutPersisting() {
        show()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue(rule.activity.isFinishing)
        assertFalse(repo.isCompleted())
        assertEquals(0, repo.markCompletedCalls)
    }

    @Test
    fun actionsAreDisabledWhileFinishing() {
        repo.gate = kotlinx.coroutines.CompletableDeferred()
        show(page = 1)
        rule.onNodeWithText("Connect now").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Connect now").assertIsNotEnabled()
        rule.onNodeWithText("Skip").assertIsNotEnabled()
    }
}
