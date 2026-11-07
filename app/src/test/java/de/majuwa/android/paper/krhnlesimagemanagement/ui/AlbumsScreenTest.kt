package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.AlbumSort
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.ALBUM_SEARCH_TAG
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    private fun dialogTextField() = hasSetTextAction() and hasAnyAncestor(isDialog())

    private fun createViewModel(
        fakeRepo: FakeAlbumsRepository = FakeAlbumsRepository(),
        credentials: FakeCredentialRepository = FakeCredentialRepository(validConfig),
    ): AlbumsViewModel {
        val app = RuntimeEnvironment.getApplication() as Application
        return AlbumsViewModel(
            application = app,
            credentialRepo = credentials,
            repoFactory = { _, _ -> fakeRepo },
            trustedCertificates = FakeTrustedCertificateRepository(),
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
        composeTestRule.onNode(dialogTextField()).assertIsDisplayed()
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
        composeTestRule.onNode(dialogTextField()).performTextClearance()
        composeTestRule.onNode(dialogTextField()).performTextInput("Autumn 2025")
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
        composeTestRule.onNode(dialogTextField()).performTextClearance()
        composeTestRule.onNode(dialogTextField()).performTextInput(newName)
        composeTestRule.onAllNodesWithText("Rename")[0].performClick()
    }

    @Test
    fun `rename dialog is pre-filled with the current name`() {
        val fakeRepo = FakeAlbumsRepository()
        fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer 2025", "/Summer%202025/")))
        openRenameDialog(createViewModel(fakeRepo), "Summer 2025")

        composeTestRule.onNode(dialogTextField() and hasText("Summer 2025")).assertIsDisplayed()
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

        composeTestRule.onNode(dialogTextField()).performTextClearance()
        composeTestRule.onNode(dialogTextField()).performTextInput("Winter 2025")
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

    // ── search and sort ─────────────────────────────────────────────────────

    private fun repoWith(vararg albums: RemoteAlbum) =
        FakeAlbumsRepository().also { it.albums = Result.success(albums.toList()) }

    private fun show(viewModel: AlbumsViewModel) {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun search() = composeTestRule.onNodeWithTag(ALBUM_SEARCH_TAG)

    private fun queryText(): String =
        search()
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    /** True when [first] is laid out before [second] in the 2-column grid (reading order). */
    private fun isBefore(
        first: String,
        second: String,
    ): Boolean {
        val a = composeTestRule.onNodeWithText(first).getBoundsInRoot()
        val b = composeTestRule.onNodeWithText(second).getBoundsInRoot()
        return a.top < b.top || (a.top == b.top && a.left < b.left)
    }

    private val cafe = RemoteAlbum("Café", "/Cafe/")
    private val strasse = RemoteAlbum("Straße", "/Strasse/")
    private val summer = RemoteAlbum("Summer", "/Summer/")

    @Test
    fun `typing filters case and diacritic insensitively`() {
        show(createViewModel(repoWith(cafe, strasse, summer)))

        search().performTextInput("CAFE")
        composeTestRule.onNodeWithText("Café").assertIsDisplayed()
        composeTestRule.onNodeWithText("Summer").assertDoesNotExist()

        search().performTextClearance()
        search().performTextInput("strasse")
        composeTestRule.onNodeWithText("Straße").assertIsDisplayed()
        composeTestRule.onNodeWithText("Café").assertDoesNotExist()
    }

    @Test
    fun `no match shows the no match message and not the empty message`() {
        show(createViewModel(repoWith(summer)))

        search().performTextInput("zzz")

        composeTestRule.onNodeWithText("No albums match \u201Czzz\u201D").assertIsDisplayed()
        composeTestRule.onNodeWithText("No albums found.").assertDoesNotExist()
        search().assertIsDisplayed()
    }

    @Test
    fun `no match message is a polite live region`() {
        show(createViewModel(repoWith(summer)))

        search().performTextInput("zzz")

        composeTestRule
            .onNodeWithText("No albums match \u201Czzz\u201D")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `empty server list shows the empty message without a search field`() {
        show(createViewModel(repoWith()))

        composeTestRule.onNodeWithText("No albums found.").assertIsDisplayed()
        search().assertDoesNotExist()
    }

    @Test
    fun `clear button empties the query`() {
        show(createViewModel(repoWith(cafe, summer)))
        composeTestRule.onNodeWithContentDescription("Clear search").assertDoesNotExist()

        search().performTextInput("caf")
        composeTestRule.onNodeWithText("Summer").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Clear search").performClick()

        assertEquals("", queryText())
        composeTestRule.onNodeWithText("Summer").assertIsDisplayed()
    }

    @Test
    fun `an overlong query is capped at 255 characters`() {
        show(createViewModel(repoWith(summer)))

        search().performTextInput("a".repeat(300))

        assertEquals(255, queryText().length)
    }

    @Test
    fun `the cap never splits a surrogate pair`() {
        show(createViewModel(repoWith(summer)))

        search().performTextInput("a".repeat(254) + "\uD83D\uDE00")

        assertEquals("a".repeat(254), queryText())
    }

    @Test
    fun `query survives recreation`() {
        val viewModel = createViewModel(repoWith(cafe, summer))
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            KrhnlesImageManagementTheme {
                AlbumsScreen(viewModel = viewModel, onOpenAlbum = {})
            }
        }
        composeTestRule.waitForIdle()
        search().performTextInput("caf")

        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertEquals("caf", queryText())
        composeTestRule.onNodeWithText("Summer").assertDoesNotExist()
    }

    @Test
    fun `query and filter survive a refresh with a spinner in between`() {
        val repo = repoWith(cafe, summer)
        val viewModel = createViewModel(repo)
        show(viewModel)
        search().performTextInput("caf")
        val gate = CompletableDeferred<Unit>()
        repo.onListAlbums = { gate.await() }

        composeTestRule.onNodeWithContentDescription("Reload").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        gate.complete(Unit)
        composeTestRule.waitForIdle()

        assertEquals("caf", queryText())
        composeTestRule.onNodeWithText("Café").assertIsDisplayed()
        composeTestRule.onNodeWithText("Summer").assertDoesNotExist()
    }

    @Test
    fun `query survives leaving and re-entering the albums destination`() {
        val viewModel = createViewModel(repoWith(cafe, summer))
        lateinit var nav: NavHostController
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = "albums") {
                    composable(
                        "albums",
                    ) { AlbumsScreen(viewModel = viewModel, onOpenAlbum = { nav.navigate("detail") }) }
                    composable("other") {}
                    composable("detail") {}
                }
            }
        }
        composeTestRule.waitForIdle()
        search().performTextInput("caf")

        // Like the bottom bar: pop to the root saving state, then restore it.
        composeTestRule.runOnIdle {
            nav.navigate("other") {
                popUpTo("albums") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            nav.navigate("albums") {
                popUpTo("albums") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        composeTestRule.waitForIdle()
        assertEquals("caf", queryText())

        // A pushed and popped detail destination keeps it as well.
        composeTestRule.onNodeWithText("Café").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { nav.popBackStack() }
        composeTestRule.waitForIdle()
        assertEquals("caf", queryText())
        composeTestRule.onNodeWithText("Summer").assertDoesNotExist()
    }

    private fun manyAlbums() =
        (1..30).map { RemoteAlbum("Album %02d".format(it), "/a$it/", lastModified = (100 - it).toLong()) }

    @Test
    fun `choosing another sort after scrolling shows the first album of the new order`() {
        show(createViewModel(repoWith(*manyAlbums().toTypedArray())))
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        composeTestRule.onNodeWithText("Album 30").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Sort albums").performClick()
        composeTestRule.onNodeWithText("Last modified (newest first)").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Album 01").assertIsDisplayed()
    }

    @Test
    fun `typing after scrolling shows the first match`() {
        show(createViewModel(repoWith(*manyAlbums().toTypedArray())))
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)

        search().performTextInput("album 0")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Album 01").assertIsDisplayed()
    }

    @Test
    fun `sort menu items are radio buttons with the current option selected`() {
        show(createViewModel(repoWith(summer)))
        composeTestRule.onNodeWithContentDescription("Sort albums").performClick()

        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        composeTestRule.onNode(hasText("Name A\u2013Z") and radio).assertIsSelected()
        composeTestRule.onNode(hasText("Name Z\u2013A") and radio).assertIsNotSelected()
        composeTestRule.onNode(hasText("Last modified (newest first)") and radio).assertIsNotSelected()
        composeTestRule.onNode(hasText("Last modified (oldest first)") and radio).assertIsNotSelected()
    }

    @Test
    fun `sort button announces the current option`() {
        val credentials = FakeCredentialRepository(validConfig)
        credentials.emitStoredAlbumSort(AlbumSort.MODIFIED_OLDEST)
        show(createViewModel(repoWith(summer), credentials))

        composeTestRule
            .onNodeWithContentDescription("Sort albums")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Last modified (oldest first)"),
            )
    }

    @Test
    fun `choosing an option reorders the grid and saves it`() {
        val credentials = FakeCredentialRepository(validConfig)
        show(createViewModel(repoWith(RemoteAlbum("A", "/A/"), RemoteAlbum("B", "/B/")), credentials))
        assertTrue(isBefore("A", "B"))

        composeTestRule.onNodeWithContentDescription("Sort albums").performClick()
        composeTestRule.onNodeWithText("Name Z\u2013A").performClick()
        composeTestRule.waitForIdle()

        assertTrue(isBefore("B", "A"))
        assertEquals(AlbumSort.NAME_DESC, credentials.albumSortValue())
    }

    @Test
    fun `renamed album appears at its sorted position and is hidden when it no longer matches`() {
        val repo = repoWith(RemoteAlbum("Alpha", "/Alpha/"), RemoteAlbum("Beta", "/Beta/"))
        val viewModel = createViewModel(repo)
        show(viewModel)
        composeTestRule.onNodeWithText("Alpha").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.onNode(dialogTextField()).performTextClearance()
        composeTestRule.onNode(dialogTextField()).performTextInput("Zulu")
        composeTestRule.onAllNodesWithText("Rename")[0].performClick()
        composeTestRule.waitForIdle()
        // Server order still holds Zulu first, the sorted grid shows Beta before Zulu.
        assertEquals(
            "Zulu",
            viewModel.albumsState.value.albums
                .first()
                .displayName,
        )
        assertTrue(isBefore("Beta", "Zulu"))

        search().performTextInput("beta")
        composeTestRule.onNodeWithText("Zulu").assertDoesNotExist()
        composeTestRule.onNodeWithText("Beta").assertIsDisplayed()
        search().assertTextEquals("Search albums", "beta")
    }

    @Test
    fun `deleting from a filtered list deletes the picked album and keeps the query`() {
        val repo = repoWith(cafe, summer)
        show(createViewModel(repo))
        search().performTextInput("caf")

        composeTestRule.onNodeWithText("Café").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onAllNodesWithText("Delete")[0].performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(cafe), repo.deletedAlbums)
        assertEquals("caf", queryText())
    }

    @Test
    fun `refresh while renaming requests no listing`() {
        val repo = repoWith(summer)
        val gate = CompletableDeferred<Unit>()
        repo.onRenameAlbum = { gate.await() }
        val viewModel = createViewModel(repo)
        show(viewModel)
        val callsBefore = repo.listAlbumsCalls
        composeTestRule.onNodeWithText("Summer").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.onNode(dialogTextField()).performTextClearance()
        composeTestRule.onNode(dialogTextField()).performTextInput("Autumn")
        composeTestRule.onAllNodesWithText("Rename")[0].performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Reload").performClick()
        composeTestRule.waitForIdle()

        assertEquals(callsBefore, repo.listAlbumsCalls)
        gate.complete(Unit)
        composeTestRule.waitForIdle()
    }
}
