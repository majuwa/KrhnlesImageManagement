package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AlbumsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val validConfig =
        WebDavConfig(
            url = "https://cloud.example.com/dav/",
            username = "user",
            password = "pass",
        )

    private fun createViewModel(fakeRepo: FakeAlbumsRepository = FakeAlbumsRepository()): AlbumsViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        return AlbumsViewModel(
            application = app,
            credentialRepo = FakeCredentialRepository(validConfig),
            repoFactory = { fakeRepo },
        )
    }

    @Test
    fun `shows Albums title in top bar`() {
        val viewModel = createViewModel()
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }
        composeTestRule.onNodeWithText("Albums").assertIsDisplayed()
    }

    @Test
    fun `shows album names after loading`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums =
            Result.success(
                listOf(
                    RemoteAlbum("Summer 2025", "/Summer25/"),
                    RemoteAlbum("Winter 2026", "/Winter26/"),
                ),
            )
        val viewModel = createViewModel(fakeRepo)

        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Summer 2025").assertIsDisplayed()
        composeTestRule.onNodeWithText("Winter 2026").assertIsDisplayed()
    }

    @Test
    fun `shows empty message when no albums`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(emptyList())
        val viewModel = createViewModel(fakeRepo)

        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No albums found.").assertIsDisplayed()
    }

    @Test
    fun `shows error message on load failure`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.failure(java.net.ConnectException("Connection refused"))
        val viewModel = createViewModel(fakeRepo)

        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }

        composeTestRule.waitForIdle()
        // Localized, user-friendly text instead of the raw exception message.
        composeTestRule
            .onNodeWithText("Could not reach the server. Check your internet connection and that the server is online.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Connection refused").assertDoesNotExist()
    }

    @Test
    fun `tapping album triggers onOpenAlbum callback`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums =
            Result.success(
                listOf(RemoteAlbum("Summer", "/Summer/")),
            )
        val viewModel = createViewModel(fakeRepo)
        var openedHref = ""

        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = { openedHref = it })
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Summer").performClick()
        assertEquals("/Summer/", openedHref)
    }

    @Test
    fun `has reload button in top bar`() {
        val viewModel = createViewModel()
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }
        composeTestRule.onNodeWithContentDescription("Reload").assertIsDisplayed()
    }

    @Test
    fun `long pressing album exposes rename and unchanged names show friendly message`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        val viewModel = createViewModel(fakeRepo)

        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Summer 2025").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Rename")[0].performClick()
        composeTestRule.onNodeWithText("Please choose a different album name.").assertIsDisplayed()
    }

    @Test
    fun `renaming album updates the album list without reloading`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        val viewModel = createViewModel(fakeRepo)

        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Summer 2025").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Autumn 2025")
        composeTestRule.onAllNodesWithText("Rename")[0].performClick()

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Autumn 2025").assertIsDisplayed()
        assertEquals(1, fakeRepo.renamedAlbums.size)
        assertEquals("Summer 2025", fakeRepo.renamedAlbums[0].first.displayName)
        assertEquals("Autumn 2025", fakeRepo.renamedAlbums[0].second)
    }

    private fun openRenameDialog(
        viewModel: AlbumsViewModel,
        albumName: String,
    ) {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(albumName).performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Rename").performClick()
    }

    private fun confirmRename(newName: String) {
        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(newName)
        composeTestRule.onAllNodesWithText("Rename")[0].performClick()
    }

    @Test
    fun `rename dialog is pre-filled with the current name`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        openRenameDialog(createViewModel(fakeRepo), "Summer 2025")

        composeTestRule.onNode(hasSetTextAction() and hasText("Summer 2025")).assertIsDisplayed()
    }

    @Test
    fun `blank name shows the blank message and sends nothing`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        openRenameDialog(createViewModel(fakeRepo), "Summer 2025")

        confirmRename("   ")

        composeTestRule.onNodeWithText("Please enter an album name.").assertIsDisplayed()
        assertEquals(0, fakeRepo.renamedAlbums.size)
    }

    @Test
    fun `unchanged name also matches the decoded href segment`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer%202025", "/Summer%202025/")))
        openRenameDialog(createViewModel(fakeRepo), "Summer%202025")

        confirmRename("  Summer 2025 ")

        composeTestRule.onNodeWithText("Please choose a different album name.").assertIsDisplayed()
        assertEquals(0, fakeRepo.renamedAlbums.size)
    }

    @Test
    fun `invalid characters show the invalid message and send nothing`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        openRenameDialog(createViewModel(fakeRepo), "Summer 2025")

        confirmRename("a/b")

        composeTestRule.onNodeWithText(invalidNameMessage()).assertIsDisplayed()
        assertEquals(0, fakeRepo.renamedAlbums.size)
    }

    @Test
    fun `name over 255 bytes shows the too long message and sends nothing`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        openRenameDialog(createViewModel(fakeRepo), "Summer 2025")

        confirmRename("\u00e4".repeat(128))

        composeTestRule.onNodeWithText("The name is too long (at most 255 bytes).").assertIsDisplayed()
        assertEquals(0, fakeRepo.renamedAlbums.size)
    }

    @Test
    fun `rename outcome is consumed only after the snackbar was shown`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        fakeRepo.renameAlbumResult = Result.failure(WebDavHttpException(412, "exists"))
        val viewModel = createViewModel(fakeRepo)
        openRenameDialog(viewModel, "Summer 2025")
        val message = "An album with this name already exists. Please choose a different name."

        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Winter 2025")
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onAllNodesWithText("Rename")[0].performClick()
        composeTestRule.waitUntil { viewModel.renameOutcome.value != null }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithText(message).assertIsDisplayed()
        assertNotNull("outcome must survive while the snackbar is visible", viewModel.renameOutcome.value)

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntil(timeoutMillis = 10_000) { viewModel.renameOutcome.value == null }
    }

    private fun invalidNameMessage() =
        RuntimeEnvironment
            .getApplication()
            .getString(R.string.error_album_name_invalid)

    @Test
    fun `existing target name shows the already exists message and keeps the list`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        fakeRepo.renameAlbumResult = Result.failure(WebDavHttpException(412, "exists"))
        openRenameDialog(createViewModel(fakeRepo), "Summer 2025")

        confirmRename("Winter 2025")

        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText("An album with this name already exists. Please choose a different name.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Summer 2025").assertIsDisplayed()
    }

    @Test
    fun `spinner replaces the grid while renaming`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        fakeRepo.onRenameAlbum = { awaitCancellation() }
        openRenameDialog(createViewModel(fakeRepo), "Summer 2025")

        confirmRename("Autumn 2025")

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Summer 2025").assertDoesNotExist()
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }
}
