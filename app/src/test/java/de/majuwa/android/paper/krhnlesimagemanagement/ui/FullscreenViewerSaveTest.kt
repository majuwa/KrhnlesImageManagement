package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeDownloadRequester
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVideoPlayerFactory
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.FullscreenViewerScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** "Save to device" in the fullscreen viewer: photos only, once per photo and viewer session. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class FullscreenViewerSaveTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val photoA = RemotePhoto("secret-name-a.jpg", "/Album/a.jpg", "1", "image/jpeg")
    private val photoB = RemotePhoto("secret-name-b.jpg", "/Album/b.jpg", "2", "image/jpeg")
    private val video = RemotePhoto("v.mp4", "/Album/v.mp4", "3", "video/mp4", MediaKind.Video)
    private val requester = FakeDownloadRequester()
    private val repo = FakeAlbumsRepository().apply { photos = Result.success(listOf(photoA, photoB, video)) }
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val detail =
        AlbumDetailViewModel(
            application = app,
            credentialRepo =
                FakeCredentialRepository(WebDavConfig("https://cloud.example.com/dav/", "u", "p")),
            repoFactory = { _, _ -> repo },
            trustedCertificates = FakeTrustedCertificateRepository(),
            downloadRequester = requester,
        )
    private val player = VideoPlayerViewModel(app, FakeVideoPlayerFactory())

    @Composable
    private fun Screen(initialIndex: Int) {
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(Color.Gray.toArgb()) },
        ) {
            KrhnlesImageManagementTheme {
                FullscreenViewerScreen(detail, initialIndex, {}, player)
            }
        }
    }

    private fun show(initialIndex: Int = 0) {
        detail.loadAlbum("/Album/")
        composeTestRule.setContent { Screen(initialIndex) }
        composeTestRule.waitUntil(5_000) { detail.detailState.value.isLoaded }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the button has a localized label and no file name`() {
        show()

        composeTestRule.onNodeWithContentDescription("Save to device").assertIsEnabled()
        composeTestRule.onAllNodesWithContentDescription(photoA.displayName).assertCountEquals0()
        assertTrue(
            composeTestRule
                .onAllNodesWithContentDescription("Save to device")
                .fetchSemanticsNodes()
                .size == 1,
        )
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() =
        assertEquals(0, fetchSemanticsNodes().size)

    @Test
    fun `a tap requests exactly the current photo`() {
        show(initialIndex = 1)

        composeTestRule.onNodeWithContentDescription("Save to device").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("/Album/" to listOf("/Album/b.jpg")), requester.requests)
    }

    @Test
    fun `a second tap on the same photo is ignored`() {
        show()

        composeTestRule.onNodeWithContentDescription("Save to device").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Save to device").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Save to device").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, requester.requests.size)
    }

    @Test
    fun `after swiping the next photo can be saved`() {
        show()
        composeTestRule.onNodeWithContentDescription("Save to device").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Photo 1 of 3").performTouchInput { swipeLeft() }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasContentDescription("Photo 2 of 3")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Save to device").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Save to device").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            listOf(listOf("/Album/a.jpg"), listOf("/Album/b.jpg")),
            requester.requests.map { it.second },
        )
    }

    @Test
    fun `a video page shows no save button`() {
        show(initialIndex = 2)

        composeTestRule.onAllNodesWithContentDescription("Save to device").assertCountEquals0()
    }

    @Test
    fun `the saved photos survive a recreation of the viewer`() {
        val restoration = StateRestorationTester(composeTestRule)
        detail.loadAlbum("/Album/")
        restoration.setContent { Screen(0) }
        composeTestRule.waitUntil(5_000) { detail.detailState.value.isLoaded }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Save to device").performClick()
        composeTestRule.waitForIdle()

        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Save to device").assertIsNotEnabled()
        assertEquals(1, requester.requests.size)
    }
}
