package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import app.cash.turbine.test
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicatesState
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlbumsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeAlbumsRepository
    private lateinit var fakeCredentials: FakeCredentialRepository
    private lateinit var viewModel: AlbumsViewModel

    private val validConfig =
        WebDavConfig(
            url = "https://cloud.example.com/remote.php/dav/files/user/",
            username = "user",
            password = "pass",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeAlbumsRepository()
        fakeCredentials = FakeCredentialRepository(validConfig)
        val app = RuntimeEnvironment.getApplication() as Application
        viewModel =
            AlbumsViewModel(
                application = app,
                credentialRepo = fakeCredentials,
                repoFactory = { fakeRepo },
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── loadAlbums ──────────────────────────────────────────────────────────

    @Test
    fun `loadAlbums updates state with albums on success`() =
        runTest {
            val albums =
                listOf(
                    RemoteAlbum("Summer", "/Summer/"),
                    RemoteAlbum("Winter", "/Winter/"),
                )
            fakeRepo.albums = Result.success(albums)

            viewModel.loadAlbums()
            advanceUntilIdle()

            val state = viewModel.albumsState.value
            assertFalse(state.isLoading)
            assertEquals(2, state.albums.size)
            assertEquals("Summer", state.albums[0].displayName)
        }

    @Test
    fun `loadAlbums sets loading state`() =
        runTest {
            fakeRepo.albums = Result.success(emptyList())

            viewModel.albumsState.test {
                assertEquals(AlbumsState(), awaitItem()) // initial

                viewModel.loadAlbums()

                val loading = awaitItem()
                assertTrue(loading.isLoading)

                val done = awaitItem()
                assertFalse(done.isLoading)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `loadAlbums sets error on failure`() =
        runTest {
            fakeRepo.albums = Result.failure(RuntimeException("Network error"))

            viewModel.loadAlbums()
            advanceUntilIdle()

            val state = viewModel.albumsState.value
            assertFalse(state.isLoading)
            assertEquals("Network error", state.error)
        }

    // ── loadAlbum (detail) ──────────────────────────────────────────────────

    @Test
    fun `loadAlbum populates detail state with photos`() =
        runTest {
            val photos =
                listOf(
                    RemotePhoto("beach.jpg", "/Summer/beach.jpg", "1", "image/jpeg"),
                    RemotePhoto("sunset.jpg", "/Summer/sunset.jpg", "2", "image/jpeg"),
                )
            fakeRepo.photos = Result.success(photos)

            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertEquals("Summer", detail.albumName)
            assertEquals(2, detail.photos.size)
            assertFalse(detail.isLoading)
        }

    @Test
    fun `loadAlbum sets error on failure`() =
        runTest {
            fakeRepo.photos = Result.failure(RuntimeException("Not found"))

            viewModel.loadAlbum("/Missing/")
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertEquals("Not found", detail.error)
            assertFalse(detail.isLoading)
        }

    // ── deletePhotos ────────────────────────────────────────────────────────

    @Test
    fun `deletePhotos removes photos from detail state`() =
        runTest {
            val photos =
                listOf(
                    RemotePhoto("a.jpg", "/a.jpg", "1", "image/jpeg"),
                    RemotePhoto("b.jpg", "/b.jpg", "2", "image/jpeg"),
                    RemotePhoto("c.jpg", "/c.jpg", "3", "image/jpeg"),
                )
            fakeRepo.photos = Result.success(photos)

            viewModel.loadAlbum("/Album/")
            advanceUntilIdle()

            var callbackFailures = -1
            viewModel.deletePhotos(listOf(photos[0], photos[1])) { failures ->
                callbackFailures = failures
            }
            advanceUntilIdle()

            assertEquals(0, callbackFailures)
            assertEquals(1, viewModel.detailState.value.photos.size)
            assertEquals(
                "/c.jpg",
                viewModel.detailState.value.photos[0]
                    .href,
            )
            assertEquals(2, fakeRepo.deletedPhotos.size)
        }

    @Test
    fun `deletePhotos reports failures`() =
        runTest {
            fakeRepo.deletePhotoResult = Result.failure(RuntimeException("403"))
            val photo = RemotePhoto("a.jpg", "/a.jpg", "1", "image/jpeg")
            fakeRepo.photos = Result.success(listOf(photo))

            viewModel.loadAlbum("/Album/")
            advanceUntilIdle()

            var callbackFailures = -1
            viewModel.deletePhotos(listOf(photo)) { callbackFailures = it }
            advanceUntilIdle()

            assertEquals(1, callbackFailures)
        }

    @Test
    fun `deletePhotos with empty list resets to Idle immediately`() =
        runTest {
            var callbackFailures = -1
            viewModel.deletePhotos(emptyList()) { callbackFailures = it }
            advanceUntilIdle()

            assertEquals(0, callbackFailures)
            assertEquals(DuplicatesState.Idle, viewModel.duplicatesState.value)
        }

    // ── deleteAlbum ─────────────────────────────────────────────────────────

    @Test
    fun `deleteAlbum removes album from albums state on success`() =
        runTest {
            val albums =
                listOf(
                    RemoteAlbum("Summer", "/Summer/"),
                    RemoteAlbum("Winter", "/Winter/"),
                )
            fakeRepo.albums = Result.success(albums)

            viewModel.loadAlbums()
            advanceUntilIdle()

            var success = false
            viewModel.deleteAlbum(albums[0]) { success = it }
            advanceUntilIdle()

            assertTrue(success)
            assertEquals(1, viewModel.albumsState.value.albums.size)
            assertEquals(
                "Winter",
                viewModel.albumsState.value.albums[0]
                    .displayName,
            )
        }

    @Test
    fun `deleteAlbum reports failure`() =
        runTest {
            fakeRepo.deleteAlbumResult = Result.failure(RuntimeException("Forbidden"))
            fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer", "/Summer/")))

            viewModel.loadAlbums()
            advanceUntilIdle()

            var success = true
            viewModel.deleteAlbum(RemoteAlbum("Summer", "/Summer/")) { success = it }
            advanceUntilIdle()

            assertFalse(success)
        }

    // ── deleteSelectedPhotos ────────────────────────────────────────────────

    @Test
    fun `deleteSelectedPhotos updates isDeletingPhotos flag`() =
        runTest {
            val photo = RemotePhoto("a.jpg", "/a.jpg", "1", "image/jpeg")
            fakeRepo.photos = Result.success(listOf(photo))

            viewModel.loadAlbum("/Album/")
            advanceUntilIdle()

            viewModel.isDeletingPhotos.test {
                assertEquals(false, awaitItem()) // initial

                viewModel.deleteSelectedPhotos(listOf(photo)) {}

                assertEquals(true, awaitItem()) // deleting
                assertEquals(false, awaitItem()) // done
                cancelAndConsumeRemainingEvents()
            }
        }

    // ── resetDuplicatesState / resetBlurState ───────────────────────────────

    @Test
    fun `resetDuplicatesState sets state to Idle`() {
        viewModel.resetDuplicatesState()
        assertEquals(DuplicatesState.Idle, viewModel.duplicatesState.value)
    }
}
