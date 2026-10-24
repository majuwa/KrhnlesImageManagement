package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.ui.uploadhistory.UploadHistoryScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.uploadhistory.UploadHistoryViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeHistory(
    initial: List<UploadHistoryEntry>,
) : UploadHistoryRepository {
    val state = MutableStateFlow(initial)

    // The repository contract lists newest first (the store sorts); the screen renders in order.
    override val entries = state.map { list -> list.sortedByDescending { it.timestampMillis } }

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        state.value = state.value + entry
    }

    override suspend fun removeEntry(entryId: Long) {
        state.value = state.value.filterNot { it.id == entryId }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class UploadHistoryScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun entry(
        id: Long,
        name: String,
        time: Long,
        photos: Int = 5,
        failed: Int = 1,
    ) = UploadHistoryEntry(id, name, time, photos, failed)

    private fun show(history: FakeHistory) {
        val viewModel = UploadHistoryViewModel(ApplicationProvider.getApplicationContext<Application>(), history)
        rule.setContent {
            KrhnlesImageManagementTheme {
                UploadHistoryScreen(viewModel = viewModel, onNavigateBack = {})
            }
        }
        rule.waitForIdle()
    }

    private fun str(id: Int) = rule.activity.getString(id)

    @Test
    fun listsNewestFirstWithCounts() {
        show(FakeHistory(listOf(entry(1, "Older", 1_000), entry(2, "Newest", 3_000), entry(3, "Middle", 2_000))))

        rule.onNodeWithText("Newest").assertExists()
        rule.onAllNodesWithText(rule.activity.getString(R.string.upload_history_counts, 4, 1)).assertCountEquals(3)
        val tops =
            listOf("Newest", "Middle", "Older").map {
                rule
                    .onNodeWithText(it)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun emptyStateHidesClearAll() {
        show(FakeHistory(emptyList()))

        rule.onNodeWithText(str(R.string.empty_upload_history)).assertExists()
        rule.onAllNodesWithContentDescription(str(R.string.cd_clear_upload_history)).assertCountEquals(0)
    }

    @Test
    fun singleEntryRendersOnce() {
        show(FakeHistory(listOf(entry(1, "Trip", 1_000))))

        rule.onAllNodesWithText("Trip").assertCountEquals(1)
    }

    @Test
    fun deleteRemovesOnlyThatEntry() {
        show(FakeHistory(listOf(entry(1, "Keep", 1_000), entry(2, "Drop", 2_000))))

        rule.onAllNodesWithContentDescription(str(R.string.cd_delete_upload_history_entry))[0].performClick()
        rule.waitForIdle()

        rule.onAllNodesWithText("Drop").assertCountEquals(0)
        rule.onNodeWithText("Keep").assertExists()
    }

    @Test
    fun clearAllNeedsConfirmation() {
        show(FakeHistory(listOf(entry(1, "A", 1_000), entry(2, "B", 2_000))))

        rule.onNodeWithContentDescription(str(R.string.cd_clear_upload_history)).performClick()
        rule.onNodeWithText(str(R.string.dialog_clear_upload_history_title)).assertExists()
        rule.onNodeWithText(str(R.string.action_cancel)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("A").assertExists()

        rule.onNodeWithContentDescription(str(R.string.cd_clear_upload_history)).performClick()
        rule.onNodeWithText(str(R.string.action_clear_all)).performClick()
        rule.waitForIdle()

        rule.onNodeWithText(str(R.string.empty_upload_history)).assertExists()
    }
}
