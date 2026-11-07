package de.majuwa.android.paper.krhnlesimagemanagement.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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

    // ── suggestions ─────────────────────────────────────────────────────────

    private val names = listOf("Summer Trip", "Winter", "Trip to Rome", "Alpha", "Beta", "Gamma", "Delta")

    private fun showWithSuggestions(list: List<String> = names) {
        rule.setContent {
            KrhnlesImageManagementTheme {
                OccasionDialog(
                    message = "Name it",
                    onDismiss = {},
                    onConfirm = { confirmed += it },
                    suggestions = list,
                )
            }
        }
    }

    private fun suggestion(name: String) = rule.onNode(hasClickAction() and hasText(name))

    private fun heading() = rule.onNodeWithText(rule.activity.getString(R.string.occasion_suggestions_heading))

    @Test
    fun emptyField_showsAtMostFiveSuggestionsAndHeading() {
        showWithSuggestions()

        suggestion("Summer Trip").assertExists()
        suggestion("Beta").assertExists()
        suggestion("Gamma").assertDoesNotExist()
        heading().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun noSuggestions_noHeading() {
        showWithSuggestions(emptyList())

        heading().assertDoesNotExist()
    }

    @Test
    fun typing_filtersCaseInsensitivelyPrefixFirst() {
        showWithSuggestions()

        type("TRIP")

        suggestion("Winter").assertDoesNotExist()
        suggestion("Trip to Rome").assertExists()
        suggestion("Summer Trip").assertExists()
        val tops =
            rule
                .onAllNodes(hasClickAction() and hasText("Trip", substring = true))
                .fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.Text].first().text }
        assertEquals(listOf("Trip to Rome", "Summer Trip"), tops)
    }

    @Test
    fun tappingSuggestion_fillsFieldWithoutConfirming() {
        showWithSuggestions()

        suggestion("Winter").performClick()

        rule.onNode(hasSetTextAction()).assertTextContains("Winter")
        assertEquals(emptyList<String>(), confirmed)
        val selection =
            rule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(6, selection.start)
        assertEquals(6, selection.end)
    }

    @Test
    fun tappingSuggestionThenUpload_confirmsIt() {
        showWithSuggestions()

        suggestion("Winter").performClick()
        upload().assertIsEnabled().performClick()

        assertEquals(listOf("Winter"), confirmed)
    }

    @Test
    fun invalidTypedName_stillShowsErrorWithSuggestions() {
        showWithSuggestions()

        type("a/b")

        errorText().assertExists()
        upload().assertIsNotEnabled()
    }

    @Test
    fun suggestion_hasButtonRoleOneLabelledClickActionAndFullText() {
        showWithSuggestions()

        val config = suggestion("Alpha").fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals("Alpha", config[SemanticsProperties.Text].single().text)
        assertEquals(
            rule.activity.getString(R.string.occasion_suggestion_use_label),
            config[SemanticsActions.OnClick].label,
        )
        assertEquals(false, config.contains(SemanticsProperties.ContentDescription))
    }

    @Test
    fun accessibilityClick_fillsField() {
        showWithSuggestions()

        rule.runOnUiThread {
            suggestion("Beta")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action
                ?.invoke()
        }
        rule.waitForIdle()

        rule.onNode(hasSetTextAction()).assertTextContains("Beta")
    }

    @Test
    fun tappedSuggestion_survivesStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            KrhnlesImageManagementTheme {
                OccasionDialog(
                    message = "Name it",
                    onDismiss = {},
                    onConfirm = { confirmed += it },
                    suggestions = names,
                )
            }
        }
        suggestion("Alpha").performClick()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNode(hasSetTextAction()).assertTextContains("Alpha")
    }
}
