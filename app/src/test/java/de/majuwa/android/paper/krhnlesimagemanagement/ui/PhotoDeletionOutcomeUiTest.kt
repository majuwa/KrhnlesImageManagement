package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicateReviewScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicatesState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.PhotoDeletionOutcome
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.photoDeletionFailureMessage
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun createViewModel(): AlbumsViewModel =
        AlbumsViewModel(
            application = RuntimeEnvironment.getApplication() as Application,
            credentialRepo =
                FakeCredentialRepository(
                    WebDavConfig(url = "https://cloud.example.com/dav/", username = "user", password = "pass"),
                ),
            repoFactory = { fakeRepo },
        )

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
        viewModel.deletePhotos(listOf(photoB))
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
    fun `duplicate review blocks Back while deleting and navigates back once afterwards`() {
        val gate = CompletableDeferred<Unit>()
        fakeRepo.onDeletePhoto = { gate.await() }
        val viewModel = createViewModel()
        var navigatedBack = 0
        composeTestRule.runOnIdle { viewModel.loadAlbum("/Album/") }
        composeTestRule.waitUntil {
            viewModel.detailState.value.photos
                .isNotEmpty()
        }
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                DuplicateReviewScreen(viewModel = viewModel, onNavigateBack = { navigatedBack++ })
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.duplicatesState.value is DuplicatesState.Found }

        composeTestRule.onNodeWithText("Delete 1 photo").performClick()
        composeTestRule.waitUntil { viewModel.duplicatesState.value == DuplicatesState.Deleting }
        composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        assertEquals("Back must be blocked while deleting", 0, navigatedBack)

        gate.complete(Unit)
        composeTestRule.waitUntil { navigatedBack > 0 }
        composeTestRule.waitForIdle()

        assertEquals(1, navigatedBack)
        assertEquals(listOf(photoB), fakeRepo.deletedPhotos)
        assertEquals(PhotoDeletionOutcome(0), viewModel.photoDeletionOutcome.value)
    }
}
