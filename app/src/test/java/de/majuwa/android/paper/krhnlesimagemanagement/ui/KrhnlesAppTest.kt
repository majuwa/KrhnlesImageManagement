package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.majuwa.android.paper.krhnlesimagemanagement.KrhnlesApp
import de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingStore
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeOnboardingRepository
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.StartupViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val STEP_ONE = "Step 1 of 2"
private const val STEP_TWO = "Step 2 of 2"

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class KrhnlesAppTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as Application

    private val hangingOnboarding =
        object : OnboardingRepository {
            override val completed: Flow<Boolean> = flow { CompletableDeferred<Unit>().await() }

            override suspend fun markCompleted() = Unit
        }

    private fun startup(onboarding: OnboardingRepository = FakeOnboardingRepository()) =
        StartupViewModel(app, onboarding, FakeCredentialRepository(), timeoutMillis = 600_000)

    private fun setApp(vm: StartupViewModel) =
        rule.setContent { KrhnlesImageManagementTheme { KrhnlesApp(onStartUpload = {}, startupViewModel = vm) } }

    @Test
    fun unresolvedStartShowsNeitherOnboardingNorPhotos() {
        setApp(startup(hangingOnboarding))
        rule.waitForIdle()
        rule.onNodeWithText(STEP_ONE).assertDoesNotExist()
        rule.onNodeWithText("Photos").assertDoesNotExist()
        rule.onNodeWithText("Albums").assertDoesNotExist()
    }

    @Test
    fun freshInstallShowsOnboardingWithoutNavigationBar() {
        setApp(startup())
        rule.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(STEP_ONE),
        )
        rule.onNodeWithText("Photos").assertDoesNotExist()
        rule.onNodeWithText("Albums").assertDoesNotExist()
    }

    @Test
    fun restoredRouteIsUsedWhileResolutionNeverFinishes() {
        val tester = StateRestorationTester(rule)
        var vm = startup()
        tester.setContent { KrhnlesImageManagementTheme { KrhnlesApp(onStartUpload = {}, startupViewModel = vm) } }
        rule.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(STEP_ONE),
        )
        rule.waitForIdle()

        vm = startup(hangingOnboarding)
        tester.emulateSavedInstanceStateRestore()
        rule.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(STEP_ONE),
        )
    }

    @Test
    fun backOnFirstOnboardingPageExitsWithoutPersisting() {
        setApp(startup())
        rule.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(STEP_ONE),
        )
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue(rule.activity.isFinishing)
        assertFalse(runBlocking { OnboardingStore(app).completed.first() })
    }

    @Test
    fun onboardingPageSurvivesActivityRecreation() {
        fun show() =
            rule.setContent {
                KrhnlesImageManagementTheme { PagedOnboarding() }
            }
        show()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText(STEP_TWO).assertExists()

        rule.activityRule.scenario.recreate()
        show()
        rule.onNodeWithText(STEP_TWO).assertExists()
    }

    @Composable
    private fun PagedOnboarding() {
        val nav = rememberNavController()
        NavHost(nav, startDestination = "onboarding") {
            composable("onboarding") {
                val vm: OnboardingViewModel = viewModel()
                OnboardingScreen(vm, onExit = {})
            }
        }
    }
}
