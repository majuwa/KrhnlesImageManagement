package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.net.Uri
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoGridViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeMedia: FakeMediaRepository
    private lateinit var fakeCredentials: FakeCredentialRepository
    private lateinit var viewModel: PhotoGridViewModel

    private val today = LocalDate.of(2026, 4, 2)
    private val yesterday = LocalDate.of(2026, 4, 1)

    private fun photo(
        id: Long,
        name: String,
        date: LocalDate,
    ) = Photo(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = name,
        dateTaken = date,
        size = 1024,
        mimeType = "image/jpeg",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeMedia = FakeMediaRepository()
        fakeCredentials =
            FakeCredentialRepository(
                WebDavConfig("https://x.com/dav/", "u", "p"),
            )
        val app = RuntimeEnvironment.getApplication() as Application
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        viewModel =
            PhotoGridViewModel(
                application = app,
                mediaRepository = fakeMedia,
                credentialStore = fakeCredentials,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── loadPhotos ──────────────────────────────────────────────────────────

    @Test
    fun `loadPhotos groups photos by date descending`() =
        runTest {
            fakeMedia.photosToReturn =
                listOf(
                    photo(1, "a.jpg", today),
                    photo(2, "b.jpg", yesterday),
                    photo(3, "c.jpg", today),
                )

            viewModel.loadPhotos()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals(2, state.photosByDate.size)
            val dates = state.photosByDate.keys.toList()
            assertEquals(today, dates[0])
            assertEquals(yesterday, dates[1])
            assertEquals(2, state.photosByDate[today]!!.size)
            assertEquals(1, state.photosByDate[yesterday]!!.size)
        }

    @Test
    fun `loadPhotos sets error on failure`() =
        runTest {
            fakeMedia.shouldThrow = RuntimeException("No permission")

            viewModel.loadPhotos()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals("No permission", state.error)
        }

    // ── togglePhotoSelection ────────────────────────────────────────────────

    @Test
    fun `togglePhotoSelection adds and removes photo`() {
        viewModel.togglePhotoSelection(1L)
        assertTrue(1L in viewModel.uiState.value.selectedPhotoIds)

        viewModel.togglePhotoSelection(1L)
        assertFalse(1L in viewModel.uiState.value.selectedPhotoIds)
    }

    @Test
    fun `togglePhotoSelection supports multiple photos`() {
        viewModel.togglePhotoSelection(1L)
        viewModel.togglePhotoSelection(2L)
        viewModel.togglePhotoSelection(3L)

        assertEquals(setOf(1L, 2L, 3L), viewModel.uiState.value.selectedPhotoIds)

        viewModel.togglePhotoSelection(2L)
        assertEquals(setOf(1L, 3L), viewModel.uiState.value.selectedPhotoIds)
    }

    // ── toggleDateSelection ─────────────────────────────────────────────────

    @Test
    fun `toggleDateSelection selects all photos for a date`() =
        runTest {
            fakeMedia.photosToReturn =
                listOf(
                    photo(1, "a.jpg", today),
                    photo(2, "b.jpg", today),
                    photo(3, "c.jpg", yesterday),
                )
            viewModel.loadPhotos()
            advanceUntilIdle()

            viewModel.toggleDateSelection(today)
            assertEquals(setOf(1L, 2L), viewModel.uiState.value.selectedPhotoIds)
        }

    @Test
    fun `toggleDateSelection deselects all when all already selected`() =
        runTest {
            fakeMedia.photosToReturn =
                listOf(
                    photo(1, "a.jpg", today),
                    photo(2, "b.jpg", today),
                )
            viewModel.loadPhotos()
            advanceUntilIdle()

            viewModel.toggleDateSelection(today)
            assertEquals(setOf(1L, 2L), viewModel.uiState.value.selectedPhotoIds)

            viewModel.toggleDateSelection(today)
            assertTrue(
                viewModel.uiState.value.selectedPhotoIds
                    .isEmpty(),
            )
        }

    // ── clearSelection ──────────────────────────────────────────────────────

    @Test
    fun `clearSelection empties selection`() {
        viewModel.togglePhotoSelection(1L)
        viewModel.togglePhotoSelection(2L)
        viewModel.clearSelection()
        assertTrue(
            viewModel.uiState.value.selectedPhotoIds
                .isEmpty(),
        )
    }

    // ── onUploadRequested ───────────────────────────────────────────────────

    @Test
    fun `onUploadRequested shows not-configured dialog when not configured`() =
        runTest {
            val unconfiguredCreds = FakeCredentialRepository(WebDavConfig())
            val app = RuntimeEnvironment.getApplication() as Application
            val vm =
                PhotoGridViewModel(
                    application = app,
                    mediaRepository = fakeMedia,
                    credentialStore = unconfiguredCreds,
                )
            advanceUntilIdle()

            vm.onUploadRequested()
            assertTrue(vm.uiState.value.showNotConfiguredDialog)
            assertFalse(vm.uiState.value.showOccasionDialog)
        }

    // ── dismissDialogs ──────────────────────────────────────────────────────

    @Test
    fun `dismissOccasionDialog clears flag`() {
        viewModel.dismissOccasionDialog()
        assertFalse(viewModel.uiState.value.showOccasionDialog)
    }

    @Test
    fun `dismissNotConfiguredDialog clears flag`() {
        viewModel.dismissNotConfiguredDialog()
        assertFalse(viewModel.uiState.value.showNotConfiguredDialog)
    }

    // ── getSelectedPhotos ───────────────────────────────────────────────────

    @Test
    fun `getSelectedPhotos returns only selected photos`() =
        runTest {
            fakeMedia.photosToReturn =
                listOf(
                    photo(1, "a.jpg", today),
                    photo(2, "b.jpg", today),
                    photo(3, "c.jpg", yesterday),
                )
            viewModel.loadPhotos()
            advanceUntilIdle()

            viewModel.togglePhotoSelection(1L)
            viewModel.togglePhotoSelection(3L)

            val selected = viewModel.getSelectedPhotos()
            assertEquals(2, selected.size)
            assertEquals(setOf(1L, 3L), selected.map { it.id }.toSet())
        }
}
