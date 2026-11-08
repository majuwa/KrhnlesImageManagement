package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeDownloadRequester
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowToast

/** "Save to device" in the album's selection mode. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class AlbumDetailSaveTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val photoA = RemotePhoto("a.jpg", "/Album/a.jpg", "1", "image/jpeg")
    private val photoB = RemotePhoto("b.jpg", "/Album/b.jpg", "2", "image/jpeg")
    private val photoC = RemotePhoto("c.jpg", "/Album/c.jpg", "3", "image/jpeg")
    private val requester = FakeDownloadRequester()
    private val repo = FakeAlbumsRepository().apply { photos = Result.success(listOf(photoA, photoB, photoC)) }
    private val viewModel =
        AlbumDetailViewModel(
            application = RuntimeEnvironment.getApplication() as Application,
            credentialRepo = FakeCredentialRepository(WebDavConfig("https://cloud.example.com/dav/", "u", "p")),
            repoFactory = { _, _ -> repo },
            backgroundDispatcher = Dispatchers.Unconfined,
            trustedCertificates = FakeTrustedCertificateRepository(),
            downloadRequester = requester,
        )

    private fun show() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumDetailScreen(viewModel, "/Album/", {}, {}, {}, {})
            }
        }
        composeTestRule.waitUntil(5_000) { viewModel.detailState.value.thumbnailUrls.size == 3 }
        composeTestRule.onNodeWithContentDescription("Photo 1 of 3").performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("Photo 3 of 3").performClick()
    }

    @Test
    fun `save passes exactly the selected photos and clears the selection`() {
        show()

        composeTestRule.onNodeWithContentDescription("Save to device").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Save to device").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("/Album/" to listOf("/Album/a.jpg", "/Album/c.jpg")), requester.requests)
        assertEquals(0, composeTestRule.onAllNodesWithContentDescription("Save to device").fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithContentDescription("Cancel selection").fetchSemanticsNodes().size)
    }

    @Test
    fun `a save that requests nothing tells the user and keeps the selection`() {
        val credentials = FakeCredentialRepository(WebDavConfig("https://cloud.example.com/dav/", "u", "p"))
        val vm =
            AlbumDetailViewModel(
                application = RuntimeEnvironment.getApplication() as Application,
                credentialRepo = credentials,
                repoFactory = { _, _ -> repo },
                backgroundDispatcher = Dispatchers.Unconfined,
                trustedCertificates = FakeTrustedCertificateRepository(),
                downloadRequester = requester,
            )
        composeTestRule.setContent {
            KrhnlesImageManagementTheme { AlbumDetailScreen(vm, "/Album/", {}, {}, {}, {}) }
        }
        composeTestRule.waitUntil(5_000) { vm.detailState.value.thumbnailUrls.size == 3 }
        composeTestRule.onNodeWithContentDescription("Photo 1 of 3").performTouchInput { longClick() }
        // The configuration changes while the selection is open: the loaded album is dropped.
        credentials.setConfig(WebDavConfig())
        composeTestRule.waitUntil(5_000) { vm.detailState.value.error != null }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Save to device").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Nothing was requested", emptyList<Pair<String, List<String>>>(), requester.requests)
        assertEquals(
            "These photos cannot be saved right now. Reload the album and try again.",
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals(1, composeTestRule.onAllNodesWithContentDescription("Save to device").fetchSemanticsNodes().size)
    }

    @Test
    fun `save is not offered outside selection mode`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme { AlbumDetailScreen(viewModel, "/Album/", {}, {}, {}, {}) }
        }
        composeTestRule.waitUntil(5_000) { viewModel.detailState.value.isLoaded }
        composeTestRule.waitForIdle()

        assertEquals(0, composeTestRule.onAllNodesWithContentDescription("Save to device").fetchSemanticsNodes().size)
    }

    @Test
    fun `save is disabled while a deletion runs`() {
        val gate = CompletableDeferred<Unit>()
        repo.onDeletePhoto = { gate.await() }
        show()

        composeTestRule.runOnIdle { viewModel.deleteSelectedPhotos(listOf(photoA)) }
        composeTestRule.waitUntil(5_000) { viewModel.isDeletingPhotos.value }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Save to device").assertIsNotEnabled()
        gate.complete(Unit)
        composeTestRule.waitUntil(5_000) { !viewModel.isDeletingPhotos.value }
    }
}
