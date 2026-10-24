package de.majuwa.android.paper.krhnlesimagemanagement.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.OccasionDialog
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class OccasionDialogTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val confirmed = mutableListOf<String>()

    private fun showDialog() {
        rule.setContent {
            KrhnlesImageManagementTheme {
                OccasionDialog(message = "Name it", onDismiss = {}, onConfirm = { confirmed += it })
            }
        }
    }

    private fun type(text: String) = rule.onNode(hasSetTextAction()).performTextInput(text)

    private fun upload() = rule.onNodeWithText(rule.activity.getString(R.string.action_upload))

    private fun errorText() = rule.onNodeWithText(rule.activity.getString(R.string.error_invalid_occasion_name))

    @Test
    fun validNameEnablesUploadWithoutError() {
        showDialog()

        type("Summer 2026")

        upload().assertIsEnabled()
        errorText().assertDoesNotExist()
        upload().performClick()
        assertEquals(listOf("Summer 2026"), confirmed)
    }

    @Test
    fun blankNameDisablesUploadWithoutError() {
        showDialog()

        upload().assertIsNotEnabled()
        errorText().assertDoesNotExist()
    }

    private fun assertRejected(name: String) {
        showDialog()

        type(name)

        errorText().assertExists()
        upload().assertIsNotEnabled()
        upload().performClick()
        assertEquals(emptyList<String>(), confirmed)
    }

    @Test
    fun doubleDotNameIsRejected() = assertRejected("..")

    @Test
    fun singleDotNameIsRejected() = assertRejected(".")

    @Test
    fun slashNameIsRejected() = assertRejected("a/b")

    @Test
    fun backslashNameIsRejected() = assertRejected("a\\b")

    @Test
    fun typedName_survivesStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            KrhnlesImageManagementTheme {
                OccasionDialog(message = "Name it", onDismiss = {}, onConfirm = { confirmed += it })
            }
        }
        type("Wedding")

        restoration.emulateSavedInstanceStateRestore()

        rule.onNode(hasSetTextAction()).assertTextContains("Wedding")
        upload().assertIsEnabled().performClick()
        assertEquals(listOf("Wedding"), confirmed)
    }
}
