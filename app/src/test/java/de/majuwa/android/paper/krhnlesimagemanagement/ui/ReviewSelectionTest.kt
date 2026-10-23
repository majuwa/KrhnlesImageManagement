package de.majuwa.android.paper.krhnlesimagemanagement.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.rememberReviewSelection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `rememberReviewSelection` keeps the user's choices across recreation and re-seeds per scan result. */
@RunWith(RobolectricTestRunner::class)
class ReviewSelectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var scanning by mutableStateOf(false)
    private var defaultSelection by mutableStateOf<Set<String>?>(setOf("a", "b"))

    private fun StateRestorationTester.showSelection() =
        setContent {
            var selection by rememberReviewSelection(scanning, defaultSelection)
            Text(
                text = selection.sorted().joinToString(","),
                modifier =
                    Modifier
                        .testTag("selection")
                        .clickable { selection = selection - "a" },
            )
        }

    @Test
    fun `selection is pre-filled once and survives recreation`() {
        val tester = StateRestorationTester(composeTestRule)
        tester.showSelection()
        composeTestRule.onNodeWithTag("selection").assertTextEquals("a,b")

        composeTestRule.onNodeWithTag("selection").performClick() // user deselects "a"
        composeTestRule.onNodeWithTag("selection").assertTextEquals("b")

        tester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithTag("selection").assertTextEquals("b")
    }

    @Test
    fun `selection is seeded again after a new scan such as Retry`() {
        val tester = StateRestorationTester(composeTestRule)
        tester.showSelection()
        composeTestRule.onNodeWithTag("selection").performClick()
        composeTestRule.onNodeWithTag("selection").assertTextEquals("b")

        // Retry: the scan restarts, then delivers a new result.
        composeTestRule.runOnIdle {
            scanning = true
            defaultSelection = null
        }
        composeTestRule.waitForIdle()
        tester.emulateSavedInstanceStateRestore() // rotation while scanning
        composeTestRule.runOnIdle {
            scanning = false
            defaultSelection = setOf("c", "d")
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("selection").assertTextEquals("c,d")
    }
}
