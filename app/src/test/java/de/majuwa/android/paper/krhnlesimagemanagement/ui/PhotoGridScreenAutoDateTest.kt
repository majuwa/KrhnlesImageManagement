package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaPermissionRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/** The real [PhotoGridScreen] with fakes: auto date preview vs occasion dialog (issues #18 / #37). */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoGridScreenAutoDateTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var workManager: WorkManager
    private val started = mutableListOf<List<UploadBatch>>()

    private val apr30 = LocalDate.of(2026, 4, 30)
    private val may1 = LocalDate.of(2026, 5, 1)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        workManager = WorkManager.getInstance(app)
    }

    private fun photo(
        id: Long,
        date: LocalDate,
    ) = Photo(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = "$id.jpg",
        dateTaken = date,
        size = 1L,
        mimeType = "image/jpeg",
    )

    private fun string(id: Int) = app.getString(id)

    private fun setScreen(
        photos: List<Photo>,
        autoDate: Boolean,
    ): PhotoGridViewModel {
        val media = FakeMediaRepository().apply { photosToReturn = photos }
        val credentials =
            FakeCredentialRepository(
                initialConfig = WebDavConfig("https://cloud.example/dav/", "u", "p", "Photos"),
                initialAutoDateFoldersEnabled = autoDate,
            )
        val viewModel =
            PhotoGridViewModel(
                app,
                media,
                credentials,
                FakeUploadedPhotosRepository(),
                workManager,
                FakeMediaPermissionRepository(),
            )
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(Color.Gray.toArgb()) },
            ) {
                PhotoGridScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = {},
                    onNavigateToUploadHistory = {},
                    onStartUpload = { started += it },
                )
            }
        }
        // waitUntil {} does not drain the paused main looper; waitForIdle() does.
        composeTestRule.waitForIdle()
        assertTrue("configuration not loaded", viewModel.isConfigured.value)
        assertEquals("Photos", viewModel.uploadBaseFolder.value)
        assertEquals(autoDate, viewModel.autoDateFoldersEnabled.value)
        return viewModel
    }

    /** Selects every photo through the grid's toggleable item nodes (one photo per day here). */
    private fun selectAllThroughGrid(count: Int) {
        val items = composeTestRule.onAllNodes(hasContentDescription("Photo 1 of 1", substring = true))
        repeat(count) { items[it].performClick() }
        composeTestRule.waitForIdle()
    }

    private fun tapUpload() {
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_upload)).performClick()
        composeTestRule.waitForIdle()
    }

    private fun previewTitle() = composeTestRule.onAllNodesWithText(string(R.string.dialog_auto_date_preview_title))

    @Test
    fun autoDateOn_previewListsBothDaysAndConfirmStartsOneBatch() {
        val viewModel = setScreen(listOf(photo(1, apr30), photo(2, may1)), autoDate = true)
        selectAllThroughGrid(2)

        tapUpload()

        previewTitle().assertCountEquals(1)
        composeTestRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeTestRule.onNodeWithText("• Photos/2026/04/30/").assertIsDisplayed()
        composeTestRule.onNodeWithText("• Photos/2026/05/01/").assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.action_upload)).performClick()
        composeTestRule.waitForIdle()

        val batch = started.single().single() as UploadBatch.PerPhoto
        assertEquals(listOf(1L, 2L), batch.photos.map { it.id })
        assertEquals(listOf("2026/04/30", "2026/05/01"), batch.folders)
        assertTrue(
            viewModel.uiState.value.selectedPhotoIds
                .isEmpty(),
        )
        previewTitle().assertCountEquals(0)
    }

    @Test
    fun autoDateOn_cancelStartsNothingAndKeepsSelection() {
        val viewModel = setScreen(listOf(photo(1, apr30), photo(2, may1)), autoDate = true)
        selectAllThroughGrid(2)
        tapUpload()

        composeTestRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(started.isEmpty())
        assertEquals(setOf(1L, 2L), viewModel.uiState.value.selectedPhotoIds)
        previewTitle().assertCountEquals(0)
    }

    @Test
    fun autoDateOn_previewWithFortyDaysScrollsToTheLastPathAndKeepsUploadVisible() {
        val days = (0L until 40L).map { LocalDate.of(2026, 1, 1).plusDays(it) }
        val viewModel = setScreen(days.mapIndexed { i, day -> photo(i.toLong() + 1, day) }, autoDate = true)
        // The lazy grid composes only visible items, so the 40 days are selected via the header callback.
        days.forEach(viewModel::toggleDateSelection)
        composeTestRule.waitForIdle()
        tapUpload()

        composeTestRule.onNodeWithText("• Photos/2026/02/09/").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_upload)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_cancel)).assertIsDisplayed()
        composeTestRule.onNodeWithText("• Photos/2026/01/01/").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun autoDateOff_showsOccasionDialogAndConfirmStartsOneOccasionBatch() {
        val viewModel = setScreen(listOf(photo(1, apr30), photo(2, may1)), autoDate = false)
        selectAllThroughGrid(2)

        tapUpload()

        previewTitle().assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.label_occasion_name)).assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Trip")
        composeTestRule.onNodeWithText(string(R.string.action_upload)).performClick()
        composeTestRule.waitForIdle()

        val batch = started.single().single() as UploadBatch.Occasion
        assertEquals("Trip", batch.folderName)
        assertEquals(setOf(1L, 2L), batch.photos.map { it.id }.toSet())
        assertTrue(
            viewModel.uiState.value.selectedPhotoIds
                .isEmpty(),
        )
    }
}
