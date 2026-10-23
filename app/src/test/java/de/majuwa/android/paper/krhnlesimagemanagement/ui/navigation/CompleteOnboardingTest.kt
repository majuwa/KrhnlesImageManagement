package de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.majuwa.android.paper.krhnlesimagemanagement.completeOnboarding
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingExit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompleteOnboardingTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var nav: NavHostController

    private fun host(start: String) {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = start) {
                composable("onboarding") {}
                composable("photos") {}
                composable("settings") {}
            }
        }
        rule.waitForIdle()
    }

    private fun stack() = nav.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun skipLeavesPhotosAsRoot() {
        host("onboarding")
        rule.runOnIdle { nav.completeOnboarding(OnboardingExit.Photos) }
        rule.waitForIdle()
        assertEquals(listOf("photos"), stack())
    }

    @Test
    fun connectNowLeavesPhotosWithSettingsOnTop() {
        host("onboarding")
        rule.runOnIdle { nav.completeOnboarding(OnboardingExit.Settings) }
        rule.waitForIdle()
        assertEquals(listOf("photos", "settings"), stack())
    }

    @Test
    fun backFromSettingsLandsOnPhotosAndNeverOnboarding() {
        host("onboarding")
        rule.runOnIdle { nav.completeOnboarding(OnboardingExit.Settings) }
        rule.waitForIdle()
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        assertEquals("photos", nav.currentDestination?.route)
        assertEquals(listOf("photos"), stack())
    }
}
