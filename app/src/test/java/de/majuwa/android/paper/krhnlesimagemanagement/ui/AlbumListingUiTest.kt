package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurReviewScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicateReviewScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicatesViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Loading count and truncation notices of the streamed album listing (issues #27 / #46). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class AlbumListingUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val photoA = RemotePhoto("a.jpg", "/Album/a.jpg", "1", "image/jpeg")
    private val photoB = RemotePhoto("b.jpg", "/Album/b.jpg", "2", "image/jpeg")

    private val fakeRepo =
        FakeAlbumsRepository().apply {
            photos = Result.success(listOf(photoA, photoB))
            downloadBitmapFactory = { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888) }
        }

    private val resources get() = RuntimeEnvironment.getApplication().resources

    private fun createViewModel(): AlbumDetailViewModel =
        AlbumDetailViewModel(
            application = RuntimeEnvironment.getApplication() as Application,
            credentialRepo =
                FakeCredentialRepository(
                    WebDavConfig(url = "https://cloud.example.com/dav/", username = "user", password = "pass"),
                ),
            repoFactory = { fakeRepo },
        )

    private fun showAlbum(viewModel: AlbumDetailViewModel) {
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
    }

    @Test
    fun `album shows the running count as a polite live region while loading`() {
        val gate = CompletableDeferred<Unit>()
        fakeRepo.onListPhotos = { onProgress ->
            onProgress(150)
            gate.await()
        }
        showAlbum(createViewModel())

        val text = resources.getQuantityString(R.plurals.album_loading_photos, 150, 150)
        composeTestRule.waitUntilAtLeastOneExists(hasText(text))
        composeTestRule
            .onNodeWithText(text)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        gate.complete(Unit)
        composeTestRule.waitUntilDoesNotExist(hasText(text))
    }

    @Test
    fun `truncated album shows a notice above the photos`() {
        fakeRepo.photosTruncated = true
        showAlbum(createViewModel())

        val notice = resources.getQuantityString(R.plurals.album_truncated_notice, 2, 2)
        composeTestRule.waitUntilAtLeastOneExists(hasText(notice))
        composeTestRule.onNodeWithText(notice).assertIsDisplayed()
    }

    @Test
    fun `complete album shows no truncation notice`() {
        val viewModel = createViewModel()
        showAlbum(viewModel)
        composeTestRule.waitUntil { viewModel.detailState.value.isLoaded }

        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(resources.getQuantityString(R.plurals.album_truncated_notice, 2, 2))
            .assertDoesNotExist()
    }

    @Test
    fun `duplicate review of a truncated album warns that results may be incomplete`() {
        fakeRepo.photosTruncated = true
        val detailViewModel = createViewModel()
        val duplicatesViewModel = DuplicatesViewModel(analysisDispatcher = Dispatchers.Unconfined)
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                DuplicateReviewScreen(
                    viewModel = duplicatesViewModel,
                    detailViewModel = detailViewModel,
                    albumHref = "/Album/",
                    onNavigateBack = {},
                )
            }
        }

        val notice = resources.getString(R.string.review_truncated_notice)
        composeTestRule.waitUntilAtLeastOneExists(hasText(notice))
        composeTestRule.onNodeWithText(notice).assertIsDisplayed()
    }

    @Test
    fun `blur review of a truncated album warns that results may be incomplete`() {
        fakeRepo.photosTruncated = true
        val detailViewModel = createViewModel()
        val blurViewModel = BlurViewModel(analysisDispatcher = Dispatchers.Unconfined)
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                BlurReviewScreen(
                    viewModel = blurViewModel,
                    detailViewModel = detailViewModel,
                    albumHref = "/Album/",
                    onNavigateBack = {},
                )
            }
        }

        val notice = resources.getString(R.string.review_truncated_notice)
        composeTestRule.waitUntilAtLeastOneExists(hasText(notice))
        composeTestRule.onNodeWithText(notice).assertIsDisplayed()
    }

    @Test
    fun `blur review of a complete album shows no truncation notice`() {
        val detailViewModel = createViewModel()
        val blurViewModel = BlurViewModel(analysisDispatcher = Dispatchers.Unconfined)
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                BlurReviewScreen(
                    viewModel = blurViewModel,
                    detailViewModel = detailViewModel,
                    albumHref = "/Album/",
                    onNavigateBack = {},
                )
            }
        }
        composeTestRule.waitUntil { detailViewModel.detailState.value.isLoaded }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(resources.getString(R.string.review_truncated_notice)).assertDoesNotExist()
    }

    @Test
    fun `truncated album without photos says it could not be loaded completely instead of empty`() {
        fakeRepo.photos = Result.success(emptyList())
        fakeRepo.photosTruncated = true
        showAlbum(createViewModel())

        val notice = resources.getString(R.string.album_truncated_empty_notice)
        composeTestRule.waitUntilAtLeastOneExists(hasText(notice))
        composeTestRule.onNodeWithText(notice).assertIsDisplayed()
        composeTestRule.onNodeWithText(resources.getString(R.string.empty_album_photos)).assertDoesNotExist()
    }

    @Test
    fun `complete empty album shows the empty message`() {
        fakeRepo.photos = Result.success(emptyList())
        showAlbum(createViewModel())

        val empty = resources.getString(R.string.empty_album_photos)
        composeTestRule.waitUntilAtLeastOneExists(hasText(empty))
        composeTestRule.onNodeWithText(resources.getString(R.string.album_truncated_empty_notice)).assertDoesNotExist()
    }
}
