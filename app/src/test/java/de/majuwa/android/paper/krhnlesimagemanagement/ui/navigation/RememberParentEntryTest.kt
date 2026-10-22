package de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for GitHub issues #24 / #43: album sub-screens resolved the shared
 * Albums back-stack entry with `remember(ROUTE_ALBUMS)` (a constant key) behind a lint
 * suppression. These tests pin the contract of [rememberParentEntry]: the lookup is keyed on
 * the current entry (re-resolved when it changes), cached across recompositions, and the
 * resolved entry hands out the parent-scoped ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
class RememberParentEntryTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** Stand-in for the parent-scoped AlbumsViewModel. */
    class SharedViewModel : ViewModel()

    @Test
    fun `re-resolves parent entry after the parent entry was replaced`() {
        lateinit var navController: NavHostController
        var key by mutableStateOf<NavBackStackEntry?>(null)
        var resolved: NavBackStackEntry? = null

        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = PARENT) {
                composable(PARENT) {}
                composable(CHILD) {}
            }
            key?.let { resolved = rememberParentEntry(navController, it, PARENT) }
        }

        composeTestRule.runOnIdle { navController.navigate(CHILD) }
        composeTestRule.runOnIdle { key = navController.currentBackStackEntry }
        val firstParent = composeTestRule.runOnIdle { navController.getBackStackEntry(PARENT) }
        composeTestRule.runOnIdle { assertSame(firstParent, resolved) }

        // Rebuild the back stack: the old parent (and its child) are removed, a new parent is created.
        composeTestRule.runOnIdle {
            navController.navigate(PARENT) { popUpTo(PARENT) { inclusive = true } }
        }
        composeTestRule.runOnIdle { navController.navigate(CHILD) }
        composeTestRule.runOnIdle { key = navController.currentBackStackEntry }

        composeTestRule.runOnIdle {
            val currentParent = navController.getBackStackEntry(PARENT)
            assertNotEquals(firstParent.id, currentParent.id)
            assertSame(
                "Parent entry must be re-resolved when the current entry changes, not kept stale",
                currentParent,
                resolved,
            )
        }
    }

    @Test
    fun `child and parent share one ViewModel, also after the parent entry is recreated`() {
        lateinit var navController: NavHostController
        var parentVm: SharedViewModel? = null
        var childVm: SharedViewModel? = null

        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = PARENT) {
                composable(PARENT) { parentVm = viewModel() }
                composable(CHILD) { entry ->
                    val parentEntry = rememberParentEntry(navController, entry, PARENT)
                    childVm = viewModel(viewModelStoreOwner = parentEntry)
                }
            }
        }

        composeTestRule.runOnIdle { navController.navigate(CHILD) }
        val firstVm = composeTestRule.runOnIdle { childVm }
        composeTestRule.runOnIdle { assertSame(parentVm, firstVm) }

        composeTestRule.runOnIdle {
            navController.navigate(PARENT) { popUpTo(PARENT) { inclusive = true } }
        }
        composeTestRule.runOnIdle { navController.navigate(CHILD) }

        composeTestRule.runOnIdle {
            // Sanity check that the scenario really produced a new parent-scoped ViewModel.
            // This does not detect a constant remember key: NavHost composes every entry in
            // its own composition, so the child's remember slot is new anyway. The keying is
            // covered by the re-resolve test above.
            assertNotSame("Recreated parent entry should own a new ViewModel", firstVm, childVm)
            assertSame("Child must share the live parent's ViewModel", parentVm, childVm)
        }
    }

    @Test
    fun `caches the lookup across recompositions with the same current entry`() {
        val parentEntry = mockk<NavBackStackEntry>()
        val navController = mockk<NavController>()
        every { navController.getBackStackEntry(PARENT) } returns parentEntry
        val firstChild = mockk<NavBackStackEntry>()
        val secondChild = mockk<NavBackStackEntry>()
        var currentEntry by mutableStateOf(firstChild)
        var tick by mutableIntStateOf(0)
        var recompositions = 0
        var resolved: NavBackStackEntry? = null

        composeTestRule.setContent {
            // Reading tick forces this scope to recompose whenever it changes.
            recompositions = tick + 1
            resolved = rememberParentEntry(navController, currentEntry, PARENT)
        }

        repeat(FORCED_RECOMPOSITIONS) { composeTestRule.runOnIdle { tick++ } }
        composeTestRule.runOnIdle {
            assertEquals(FORCED_RECOMPOSITIONS + 1, recompositions)
            assertSame(parentEntry, resolved)
            verify(exactly = 1) { navController.getBackStackEntry(PARENT) }
        }

        composeTestRule.runOnIdle { currentEntry = secondChild }
        composeTestRule.runOnIdle {
            verify(exactly = 2) { navController.getBackStackEntry(PARENT) }
        }
    }

    private companion object {
        const val FORCED_RECOMPOSITIONS = 3
        const val PARENT = "parent"
        const val CHILD = "parent/child"
    }
}
