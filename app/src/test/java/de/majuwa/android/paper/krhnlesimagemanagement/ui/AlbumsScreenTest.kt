package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import org.junit.Assert.assertEquals
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
        fakeRepo.albums = Result.failure(RuntimeException("Connection refused"))
        val viewModel = createViewModel(fakeRepo)

        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Connection refused").assertIsDisplayed()
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
        composeTestRule.onAllNodesWithText("Rename")[1].performClick()
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
        composeTestRule.onAllNodesWithText("Rename")[1].performClick()

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Autumn 2025").assertIsDisplayed()
        assertEquals(1, fakeRepo.renamedAlbums.size)
        assertEquals("Summer 2025", fakeRepo.renamedAlbums[0].first.displayName)
        assertEquals("Autumn 2025", fakeRepo.renamedAlbums[0].second)
    }
}
