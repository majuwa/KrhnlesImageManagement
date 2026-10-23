package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.BlurSensitivity
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurReviewScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicateReviewScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicatesState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicatesViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.PhotoDeletionOutcome
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.photoDeletionFailureMessage
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.FullscreenViewerScreen
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurScorer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The outcome of a photo deletion must reach the user even when the screen that started it
 * (duplicate / blur review) has navigated back, and leaving a review mid-deletion is blocked
 * (issues #28 / #47).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoDeletionOutcomeUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val photoA = RemotePhoto("a.jpg", "/Album/a.jpg", "1", "image/jpeg")
    private val photoB = RemotePhoto("b.jpg", "/Album/b.jpg", "2", "image/jpeg")

    private val fakeRepo =
        FakeAlbumsRepository().apply {
            photos = Result.success(listOf(photoA, photoB))
            // Identical images → one duplicate group.
            downloadBitmapFactory = { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888) }
        }

    private fun credentials() =
        FakeCredentialRepository(
            WebDavConfig(url = "https://cloud.example.com/dav/", username = "user", password = "pass"),
        )

    private fun createViewModel(): AlbumDetailViewModel =
        AlbumDetailViewModel(
            application = RuntimeEnvironment.getApplication() as Application,
            credentialRepo = credentials(),
            repoFactory = { fakeRepo },
            // Thumbnail URLs resolved inline: several tests wait for the load before any content is set.
            backgroundDispatcher = Dispatchers.Unconfined,
        )

    /**
     * Review analysis must not hop to a real background pool here: its result would be posted to
     * Robolectric's paused main looper, which `waitUntil` never drains (only `waitForIdle` does),
     * so a scan finished after the last `waitForIdle` would never be resumed and the test would
     * pass or time out depending on timing. `Unconfined` runs the analysis inline on the main
     * thread, so the scan completes while the looper is drained (guarded by
     * `RobolectricAnalysisDispatcherRegressionTest`).
     */
    private val analysisDispatcher = Dispatchers.Unconfined

    private fun createDuplicatesViewModel(): DuplicatesViewModel =
        DuplicatesViewModel(analysisDispatcher = analysisDispatcher)

    private val resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `failure message uses the plural resource and is null without failures`() {
        assertNull(photoDeletionFailureMessage(resources, PhotoDeletionOutcome(0)))
        assertEquals("1 photo could not be deleted.", photoDeletionFailureMessage(resources, PhotoDeletionOutcome(1)))
        assertEquals("3 photos could not be deleted.", photoDeletionFailureMessage(resources, PhotoDeletionOutcome(3)))
    }

    @Test
    fun `album detail shows the failures of a deletion finished while it was not visible`() {
        fakeRepo.deletePhotoResult = Result.failure(RuntimeException("500"))
        val viewModel = createViewModel()
        // Deletion started and finished from the duplicate review, before the detail screen is shown again.
        viewModel.loadAlbum("/Album/")
        createDuplicatesViewModel().deletePhotos(listOf(photoB), viewModel)
        composeTestRule.waitUntil { viewModel.photoDeletionOutcome.value != null }

        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumDetailScreen(
                    viewModel,
                    "/Album/",
                    onOpenPhoto = {},
                    onFindDuplicates = {},
                    onFindBlurry = {},
                    onNavigateBack = {},
                )
            }
        }

        composeTestRule.waitUntilExactlyOneExists(hasText("1 photo could not be deleted."), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText("1 photo could not be deleted.").assertIsDisplayed()
        assertNull("outcome is consumed so it is shown only once", viewModel.photoDeletionOutcome.value)
    }

    @Test
    fun `album detail shows the failures of a selection-mode deletion`() {
        fakeRepo.deletePhotoResult = Result.failure(RuntimeException("500"))
        val viewModel = createViewModel()
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                AlbumDetailScreen(
                    viewModel,
                    "/Album/",
                    onOpenPhoto = {},
                    onFindDuplicates = {},
                    onFindBlurry = {},
                    onNavigateBack = {},
                )
            }
        }
        composeTestRule.waitUntil {
            viewModel.detailState.value.photos
                .isNotEmpty()
        }

        composeTestRule.runOnIdle { viewModel.deleteSelectedPhotos(listOf(photoA, photoB)) }

        composeTestRule.waitUntilExactlyOneExists(hasText("2 photos could not be deleted."), timeoutMillis = 5_000)
        composeTestRule.onNodeWithText("2 photos could not be deleted.").assertIsDisplayed()
    }

    @Test
    fun `album detail blocks Back and the top-bar buttons while a selection-mode deletion runs`() {
        val gate = CompletableDeferred<Unit>()
        fakeRepo.onDeletePhoto = { gate.await() }
        val viewModel = createViewModel()
        var leftScreen = 0
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                // Stands in for the NavHost: receives Back unless the screen consumes it.
                BackHandler { leftScreen++ }
                AlbumDetailScreen(
                    viewModel,
                    "/Album/",
                    onOpenPhoto = {},
                    onFindDuplicates = {},
                    onFindBlurry = {},
                    onNavigateBack = { leftScreen++ },
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.detailState.value.thumbnailUrls.size == 2
        }

        composeTestRule.onNodeWithContentDescription("Photo 1 of 2").performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("Photo 2 of 2").performClick()
        composeTestRule.onNodeWithContentDescription("Delete selected").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.waitUntil { viewModel.isDeletingPhotos.value }

        composeTestRule.onNodeWithContentDescription("Cancel selection").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Delete selected").assertIsNotEnabled()
        composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        assertEquals("Back must be blocked while deleting", 0, leftScreen)

        gate.complete(Unit)
        composeTestRule.waitUntil(timeoutMillis = 5_000) { !viewModel.isDeletingPhotos.value }
        composeTestRule.waitForIdle()

        assertEquals("the deletion was not cut short", listOf(photoA, photoB), fakeRepo.deletedPhotos)
        assertTrue(
            viewModel.detailState.value.photos
                .isEmpty(),
        )
        assertEquals(0, leftScreen)
        // Selection is cleared by the outcome; the album's back arrow works again.
        composeTestRule.onNodeWithContentDescription(resources.getString(R.string.cd_back)).assertIsEnabled()
    }

    @Test
    fun `fullscreen viewer describes the page by position and not by file name`() {
        val viewModel = createViewModel()
        composeTestRule.runOnIdle { viewModel.loadAlbum("/Album/") }
        composeTestRule.waitUntil { viewModel.detailState.value.isLoaded }
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                FullscreenViewerScreen(viewModel = viewModel, initialIndex = 1, onNavigateBack = {})
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Photo 2 of 2").assertExists()
        composeTestRule.onNodeWithContentDescription(photoB.displayName).assertDoesNotExist()
    }

    @Test
    fun `duplicate review blocks Back while deleting and navigates back once afterwards`() {
        val gate = CompletableDeferred<Unit>()
        fakeRepo.onDeletePhoto = { gate.await() }
        val viewModel = createViewModel()
        val duplicatesViewModel = createDuplicatesViewModel()
        var navigatedBack = 0
        composeTestRule.runOnIdle { viewModel.loadAlbum("/Album/") }
        composeTestRule.waitUntil {
            viewModel.detailState.value.photos
                .isNotEmpty()
        }
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                DuplicateReviewScreen(
                    viewModel = duplicatesViewModel,
                    detailViewModel = viewModel,
                    albumHref = "/Album/",
                    onNavigateBack = { navigatedBack++ },
                )
            }
        }
        composeTestRule.waitUntil(
            timeoutMillis = 5_000,
        ) { duplicatesViewModel.duplicatesState.value is DuplicatesState.Found }

        // Position within the duplicate group, never the file name.
        composeTestRule.onNodeWithContentDescription("Photo 1 of 2").assertExists()
        composeTestRule.onNodeWithContentDescription("Photo 2 of 2").assertExists()
        composeTestRule.onNodeWithContentDescription(photoA.displayName).assertDoesNotExist()

        composeTestRule.onNodeWithText("Delete 1 photo").performClick()
        composeTestRule.waitUntil { duplicatesViewModel.duplicatesState.value == DuplicatesState.Deleting }
        composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        assertEquals("Back must be blocked while deleting", 0, navigatedBack)

        gate.complete(Unit)
        composeTestRule.waitUntil { navigatedBack > 0 }
        composeTestRule.waitForIdle()

        assertEquals(1, navigatedBack)
        assertEquals(listOf(photoB), fakeRepo.deletedPhotos)
        assertEquals(PhotoDeletionOutcome(0), viewModel.photoDeletionOutcome.value)
        assertEquals("deleted photo leaves the shared album list", listOf(photoA), viewModel.detailState.value.photos)
    }

    @Test
    fun `blur review blocks Back while deleting and navigates back once afterwards`() {
        val gate = CompletableDeferred<Unit>()
        fakeRepo.onDeletePhoto = { gate.await() }
        val viewModel = createViewModel()
        val blurViewModel =
            BlurViewModel(
                blurScorerFactory =
                    {
                        object : BlurScorer {
                            override suspend fun computeBlurScore(bitmap: Bitmap): Double = 0.0

                            override fun close() = Unit
                        }
                    },
                analysisDispatcher = analysisDispatcher,
            )
        var navigatedBack = 0
        composeTestRule.runOnIdle { viewModel.loadAlbum("/Album/") }
        composeTestRule.waitUntil {
            viewModel.detailState.value.isLoaded
        }
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                BlurReviewScreen(
                    viewModel = blurViewModel,
                    detailViewModel = viewModel,
                    albumHref = "/Album/",
                    sensitivity = flowOf(BlurSensitivity.MEDIUM),
                    onNavigateBack = { navigatedBack++ },
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { blurViewModel.blurState.value is BlurState.Found }

        composeTestRule.onNodeWithContentDescription("Photo 1 of 2").assertExists()
        composeTestRule.onNodeWithContentDescription("Photo 2 of 2").assertExists()
        composeTestRule.onNodeWithContentDescription(photoA.displayName).assertDoesNotExist()

        composeTestRule.onNodeWithText("Delete 2 photos").performClick()
        composeTestRule.waitUntil { blurViewModel.blurState.value == BlurState.Deleting }
        composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        assertEquals("Back must be blocked while deleting", 0, navigatedBack)

        gate.complete(Unit)
        composeTestRule.waitUntil { navigatedBack > 0 }
        composeTestRule.waitForIdle()

        assertEquals(1, navigatedBack)
        assertEquals(listOf(photoA, photoB), fakeRepo.deletedPhotos)
        assertEquals(PhotoDeletionOutcome(0), viewModel.photoDeletionOutcome.value)
        assertTrue(
            viewModel.detailState.value.photos
                .isEmpty(),
        )
    }

    @Test
    fun `duplicate review drops its results and shows the album error after a logout`() {
        val credentials = credentials()
        val viewModel =
            AlbumDetailViewModel(
                application = RuntimeEnvironment.getApplication() as Application,
                credentialRepo = credentials,
                repoFactory = { fakeRepo },
                backgroundDispatcher = Dispatchers.Unconfined,
            )
        val duplicatesViewModel = createDuplicatesViewModel()
        composeTestRule.runOnIdle { viewModel.loadAlbum("/Album/") }
        composeTestRule.waitUntil { viewModel.detailState.value.isLoaded }
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                DuplicateReviewScreen(
                    viewModel = duplicatesViewModel,
                    detailViewModel = viewModel,
                    albumHref = "/Album/",
                    onNavigateBack = {},
                )
            }
        }
        composeTestRule.waitUntil(
            timeoutMillis = 5_000,
        ) { duplicatesViewModel.duplicatesState.value is DuplicatesState.Found }

        composeTestRule.runOnIdle { kotlinx.coroutines.runBlocking { credentials.clear() } }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            duplicatesViewModel.duplicatesState.value ==
                DuplicatesState.Idle
        }

        composeTestRule.onNodeWithText(resources.getString(R.string.albums_error_not_configured)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete 1 photo").assertDoesNotExist()
    }
}
