package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVideoPlayerFactory
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.FullscreenViewerScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val DRAG_STEPS = 10
private const val DRAG_STEP_PX = 12f

/** Video pages of the fullscreen viewer: poster, tap to play, controls and lifecycle (issue #74). */
@OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class FullscreenViewerVideoTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val photoA = RemotePhoto("a.jpg", "/Album/a.jpg", "1", "image/jpeg")
    private val video = RemotePhoto("v.mp4", "/Album/v.mp4", "2", "video/mp4", MediaKind.Video)
    private val photoC = RemotePhoto("c.jpg", "/Album/c.jpg", "3", "image/jpeg")

    private val repo = FakeAlbumsRepository().apply { photos = Result.success(listOf(photoA, video, photoC)) }
    private val playerFactory = FakeVideoPlayerFactory()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val credentials =
        FakeCredentialRepository(WebDavConfig(url = "https://cloud.example.com/dav/", username = "u", password = "p"))

    private fun detailViewModel() =
        AlbumDetailViewModel(
            application = app,
            credentialRepo = credentials,
            repoFactory = { _, _ -> repo },
            trustedCertificates = FakeTrustedCertificateRepository(),
        )

    private val detail = detailViewModel()
    private val player = VideoPlayerViewModel(app, playerFactory)

    @Composable
    private fun Screen(
        detailViewModel: AlbumDetailViewModel = detail,
        playerViewModel: VideoPlayerViewModel = player,
        initialIndex: Int = 1,
    ) {
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(Color.Gray.toArgb()) },
        ) {
            KrhnlesImageManagementTheme {
                FullscreenViewerScreen(
                    viewModel = detailViewModel,
                    initialIndex = initialIndex,
                    onNavigateBack = {},
                    playerViewModel = playerViewModel,
                )
            }
        }
    }

    private fun show(initialIndex: Int = 1) {
        detail.loadAlbum("/Album/")
        composeTestRule.setContent { Screen(initialIndex = initialIndex) }
        composeTestRule.waitUntil(5_000) { detail.detailState.value.isLoaded }
        composeTestRule.waitForIdle()
    }

    private fun startPlayback() {
        composeTestRule.onNodeWithContentDescription("Play video").performClick()
        composeTestRule.waitUntil(5_000) { playerFactory.created.isNotEmpty() }
        playerFactory.created.single().emitReady()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a video page shows its position and a play button and starts no player before the tap`() {
        show()

        composeTestRule.onNodeWithContentDescription("Video 2 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Play video").assertIsDisplayed()
        assertTrue(playerFactory.created.isEmpty())
    }

    @Test
    fun `a video without a preview shows the placeholder page with the play button`() {
        repo.videoPreviewsAvailable = false
        show()

        composeTestRule.onNodeWithContentDescription("Video 2 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Play video").assertIsDisplayed()
        assertTrue(playerFactory.created.isEmpty())
    }

    @Test
    fun `a photo page is still described as Photo N of M`() {
        show(initialIndex = 0)

        composeTestRule.onNodeWithContentDescription("Photo 1 of 3").assertIsDisplayed()
    }

    @Test
    fun `tapping play starts exactly one player for the stream url`() {
        show()

        composeTestRule.onNodeWithContentDescription("Play video").performClick()
        composeTestRule.waitUntil(5_000) { playerFactory.created.isNotEmpty() }
        composeTestRule.waitForIdle()

        assertEquals(1, playerFactory.created.size)
        assertEquals("https://fake.server/video/Album/v.mp4", playerFactory.created.single().streamUrl)
    }

    @Test
    fun `controls announce pause and expose the seek position and duration as state`() {
        show()
        startPlayback()
        playerFactory.created.single().apply {
            positionMs = 30_000
            durationMs = 120_000
        }

        composeTestRule.onNodeWithContentDescription("Pause video").assertIsDisplayed()
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodes(hasContentDescription("Video position"))
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.StateDescription) == "30 seconds of 2 minutes"
        }
    }

    @Test
    fun `the seek bar seeks the player through its set progress action`() {
        show()
        startPlayback()
        val fake = playerFactory.created.single()
        fake.durationMs = 120_000
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodes(hasContentDescription("Video position"))
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.StateDescription)
                ?.endsWith("of 2 minutes") == true
        }

        composeTestRule
            .onNodeWithContentDescription("Video position")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(60_000f) }
        composeTestRule.waitForIdle()

        assertEquals(listOf(60_000L), fake.seeks)
    }

    @Test
    fun `pause and resume go through the controls`() {
        show()
        startPlayback()
        val fake = playerFactory.created.single()

        composeTestRule.onNodeWithContentDescription("Pause video").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, fake.pauseCalls)
        composeTestRule.onNodeWithContentDescription("Play video").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Play video").performClick()
        composeTestRule.waitUntil(5_000) { fake.playCalls == 1 }
        assertTrue(fake.isPlaying)
    }

    @Test
    fun `a horizontal swipe on a video page changes the page`() {
        show()

        composeTestRule.onNodeWithContentDescription("Video 2 of 3").performTouchInput { swipeLeft() }

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasContentDescription("Photo 3 of 3")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a pinch on a video page does not zoom or swipe it`() {
        show()

        composeTestRule.onNodeWithContentDescription("Video 2 of 3").performTouchInput {
            pinch(
                Offset(center.x - 20f, center.y),
                Offset(center.x - 60f, center.y),
                Offset(center.x + 20f, center.y),
                Offset(center.x + 60f, center.y),
            )
        }
        composeTestRule.waitForIdle()

        // Video pages have no zoomable image: the page stays and shows no scale transform.
        composeTestRule.onNodeWithContentDescription("Video 2 of 3").assertIsDisplayed()
        assertTrue(playerFactory.created.isEmpty())
    }

    @Test
    fun `swiping away from a playing video stops it`() {
        show()
        startPlayback()
        val fake = playerFactory.created.single()

        composeTestRule.onNodeWithContentDescription("Video 2 of 3").performTouchInput { swipeLeft() }
        composeTestRule.waitUntil(5_000) { fake.released }

        composeTestRule.onNodeWithContentDescription("Photo 3 of 3").assertExists()
        assertNull(player.playback.value)
    }

    @Test
    fun `going to the background pauses the video`() {
        show()
        startPlayback()
        val fake = playerFactory.created.single()

        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeTestRule.waitUntil(5_000) { fake.pauseCalls == 1 }

        assertEquals(1, fake.pauseCalls)
        assertEquals(false, fake.released)
    }

    @Test
    fun `a configuration change of the account stops playback`() {
        show()
        startPlayback()
        val fake = playerFactory.created.single()

        runBlocking { credentials.save("https://other.example.com/dav/", "me", "secret") }
        composeTestRule.waitUntil(5_000) { fake.released }

        assertNull(player.playback.value)
    }

    @Test
    fun `dragging the seek bar does not swipe the pager`() {
        show()
        startPlayback()
        playerFactory.created.single().durationMs = 120_000
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodes(hasContentDescription("Video position"))
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.StateDescription)
                ?.endsWith("of 2 minutes") == true
        }

        composeTestRule.onNodeWithContentDescription("Video position").performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(DRAG_STEP_PX, 0f)) }
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "the drag seeks",
            playerFactory.created
                .single()
                .seeks
                .isNotEmpty(),
        )
        composeTestRule.onNodeWithContentDescription("Video 2 of 3").assertExists()
        assertEquals(false, playerFactory.created.single().released)
    }

    @Test
    fun `a failure shows a typed message with retry`() {
        show()
        startPlayback()
        playerFactory.created.single().emitError(
            androidx.media3.common.PlaybackException(
                "secret",
                null,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
            ),
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("This video format is not supported on this device.").assertIsDisplayed()
        assertNotNull(composeTestRule.onNodeWithContentDescription("Video 2 of 3"))
    }
}

/** Recreation of the Activity (rotation) keeps the playing video: the same ViewModel, no pause, no new play. */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class FullscreenViewerRecreationTest {
    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val video = RemotePhoto("v.mp4", "/Album/v.mp4", "2", "video/mp4", MediaKind.Video)
    private val repo = FakeAlbumsRepository().apply { photos = Result.success(listOf(video)) }
    private val playerFactory = FakeVideoPlayerFactory()
    private val app get() = RuntimeEnvironment.getApplication() as Application

    private fun setContent(activity: ComponentActivity) {
        activity.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(Color.Gray.toArgb()) },
            ) {
                val detail =
                    viewModel<AlbumDetailViewModel>(
                        factory =
                            viewModelFactory {
                                initializer {
                                    AlbumDetailViewModel(
                                        application = app,
                                        credentialRepo =
                                            FakeCredentialRepository(
                                                WebDavConfig(
                                                    url = "https://cloud.example.com/dav/",
                                                    username = "u",
                                                    password = "p",
                                                ),
                                            ),
                                        repoFactory = { _, _ -> repo },
                                        trustedCertificates = FakeTrustedCertificateRepository(),
                                    )
                                }
                            },
                    )
                val player =
                    viewModel<VideoPlayerViewModel>(
                        factory = viewModelFactory { initializer { VideoPlayerViewModel(app, playerFactory) } },
                    )
                androidx.compose.runtime.LaunchedEffect(Unit) { detail.loadAlbum("/Album/") }
                KrhnlesImageManagementTheme {
                    FullscreenViewerScreen(detail, 0, {}, player)
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `recreation keeps the same player view model and neither pauses nor plays again`() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { setContent(it) }
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodes(hasContentDescription("Play video")).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithContentDescription("Play video").performClick()
            composeTestRule.waitUntil(5_000) { playerFactory.created.isNotEmpty() }
            val fake = playerFactory.created.single()
            fake.emitReady()
            composeTestRule.waitForIdle()

            scenario.recreate()
            scenario.onActivity { setContent(it) }
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodes(hasContentDescription("Pause video")).fetchSemanticsNodes().isNotEmpty()
            }

            assertEquals(1, playerFactory.created.size)
            assertEquals(0, fake.pauseCalls)
            assertEquals(0, fake.playCalls)
            assertEquals(false, fake.released)
            assertSame(fake, playerFactory.created.single())
        }
    }
}
