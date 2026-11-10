package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.KrhnlesApp
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeOnboardingRepository
import de.majuwa.android.paper.krhnlesimagemanagement.openPhotosForLaunchRequest
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.AppLaunchRequest
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.LaunchRequestViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.StartupViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val FILTER_ON = "New only"

/** A launch request reaches the photo grid of the real host and is applied exactly once. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class KrhnlesAppLaunchRequestTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val requests = MutableStateFlow<AppLaunchRequest?>(null)
    private var consumed = 0

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
    }

    private fun startup(completed: Boolean) =
        StartupViewModel(app, FakeOnboardingRepository(completed), FakeCredentialRepository(), timeoutMillis = 600_000)

    private fun setApp(
        completed: Boolean = true,
        tester: StateRestorationTester? = null,
    ) {
        val content: @Composable () -> Unit = {
            KrhnlesImageManagementTheme {
                KrhnlesApp(
                    onStartUpload = {},
                    startupViewModel = startup(completed),
                    launchRequests = requests,
                    onLaunchRequestConsumed = {
                        consumed++
                        requests.value = null
                    },
                )
            }
        }
        if (tester != null) tester.setContent(content) else rule.setContent(content)
    }

    @Test
    fun `a pending request on cold start opens the grid with the filter on and is consumed`() {
        requests.value = AppLaunchRequest.ShowNewMedia
        setApp()

        rule.waitUntilAtLeastOneExists(hasText(FILTER_ON), timeoutMillis = 5_000)
        rule.waitForIdle()
        assertNull(requests.value)
        assertEquals(1, consumed)
    }

    @Test
    fun `a request while Settings is shown opens the grid and Back does not return to Settings`() {
        setApp()
        rule.waitUntilAtLeastOneExists(hasText("Photos"), timeoutMillis = 5_000)
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Connect to Nextcloud", substring = true).or(hasText("Settings")), 5_000)

        requests.value = AppLaunchRequest.ShowNewMedia
        rule.waitUntilAtLeastOneExists(hasText(FILTER_ON), timeoutMillis = 5_000)
        rule.waitForIdle()

        assertNull(requests.value)
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        // The grid is the root: Back leaves the app instead of showing Settings again.
        assertTrue(rule.activity.isFinishing)
    }

    @Test
    fun `a filter the user cleared after the request stays off after recreation`() {
        val tester = StateRestorationTester(rule)
        requests.value = AppLaunchRequest.ShowNewMedia
        setApp(tester = tester)
        rule.waitUntilAtLeastOneExists(hasText(FILTER_ON), timeoutMillis = 5_000)
        rule.waitForIdle()
        assertEquals(1, consumed)
        // The user turns the filter off again (the chip clears it).
        rule.onNodeWithText(FILTER_ON).performClick()
        rule.waitUntilDoesNotExist(hasText(FILTER_ON), timeoutMillis = 5_000)

        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        // A re-applied request would show the chip again.
        rule.onNodeWithText(FILTER_ON).assertDoesNotExist()
        assertEquals(1, consumed)
        assertNull(requests.value)
    }

    @Test
    fun `the onboarding start drops the request and keeps the onboarding`() {
        requests.value = AppLaunchRequest.ShowNewMedia
        setApp(completed = false)

        rule.waitUntilAtLeastOneExists(hasText("Step 1 of 2"), timeoutMillis = 5_000)
        rule.waitForIdle()
        assertNull(requests.value)
        assertEquals(1, consumed)
        rule.onNodeWithText(FILTER_ON).assertDoesNotExist()
        assertFalse(rule.activity.isFinishing)
    }

    // ── The navigation primitive with a running album screen ────────────────
    // Limitation: the real host cannot be used here, because KrhnlesApp creates AlbumDetailViewModel with the
    // default factory (real repositories, no seam for a suspending deletion fake). The tests below therefore
    // pin the navigation primitive (`openPhotosForLaunchRequest`) with a stand-in ViewModel.

    /** A stand-in for the album detail's ViewModel: holds a "running deletion" and records `onCleared`. */
    class DetailViewModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun `navigation primitive with a stand-in view model keeps the album subtree saved and restorable`() {
        lateinit var nav: NavHostController
        var detail: DetailViewModel? = null
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "photos") {
                composable("photos") { androidx.compose.material3.Text("grid") }
                composable("albums") { androidx.compose.material3.Text("albums") }
                composable("albums/detail?href={href}") {
                    val vm: DetailViewModel = viewModel()
                    DisposableEffect(vm) {
                        detail = vm
                        onDispose {}
                    }
                    androidx.compose.material3.Text("detail")
                }
            }
        }
        rule.runOnIdle {
            nav.navigate("albums")
            nav.navigate("albums/detail?href=x")
        }
        rule.waitUntilAtLeastOneExists(hasText("detail"), 5_000)
        val heldViewModel = detail

        rule.runOnIdle {
            nav.openPhotosForLaunchRequest()
        }
        rule.waitForIdle()
        rule.waitUntilAtLeastOneExists(hasText("grid"), 5_000)

        assertFalse(
            "the album's view model must survive (a running deletion is not cancelled)",
            heldViewModel!!.cleared,
        )

        // The Albums tab restores the saved subtree, including the detail screen with the same view model.
        rule.runOnIdle {
            nav.navigate("albums") {
                launchSingleTop = true
                restoreState = true
                popUpTo("photos") { saveState = true }
            }
        }
        rule.waitUntilAtLeastOneExists(hasText("detail"), 5_000)
        assertTrue(detail === heldViewModel)
        assertFalse(heldViewModel.cleared)
    }

    @Test
    fun `from Settings the request pops to the grid without saving`() {
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "photos") {
                composable("photos") { androidx.compose.material3.Text("grid") }
                composable("settings") { androidx.compose.material3.Text("settings") }
            }
        }
        rule.runOnIdle { nav.navigate("settings") }
        rule.waitUntilAtLeastOneExists(hasText("settings"), 5_000)

        rule.runOnIdle { nav.openPhotosForLaunchRequest() }
        rule.waitUntilAtLeastOneExists(hasText("grid"), 5_000)

        assertEquals(listOf("photos"), nav.currentBackStack.value.mapNotNull { it.destination.route })
    }

    @Test
    fun `launch request view model is the one source of the pending request`() {
        val viewModel = LaunchRequestViewModel()
        viewModel.offer(AppLaunchRequest.ShowNewMedia)
        rule.setContent {
            val pending by viewModel.pending.collectAsStateWithLifecycle()
            androidx.compose.material3.Text(if (pending == null) "none" else "pending")
        }
        rule.onNodeWithText("pending").assertExists()
        viewModel.consume()
        rule.waitForIdle()
        rule.onNodeWithText("none").assertExists()
    }
}
