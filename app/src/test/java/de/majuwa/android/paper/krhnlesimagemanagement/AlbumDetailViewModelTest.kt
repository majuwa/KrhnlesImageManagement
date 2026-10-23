package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.PhotoDeletionOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

/**
 * Album detail state of [AlbumDetailViewModel] (split from `AlbumsViewModel`, issues #29 / #48).
 * The photo-deletion contract is covered by [AlbumPhotoDeletionTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlbumDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeAlbumsRepository
    private lateinit var credentials: FakeCredentialRepository
    private lateinit var viewModel: AlbumDetailViewModel

    private val beach = RemotePhoto("beach.jpg", "/Summer/beach.jpg", "1", "image/jpeg")
    private val sunset = RemotePhoto("sunset.jpg", "/Summer/sunset.jpg", "2", "image/jpeg")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeAlbumsRepository()
        credentials =
            FakeCredentialRepository(
                WebDavConfig(
                    url = "https://cloud.example.com/remote.php/dav/files/user/",
                    username = "user",
                    password = "pass",
                ),
            )
        viewModel = createViewModel()
    }

    private fun createViewModel(repoFactory: (WebDavConfig) -> AlbumsRepositoryContract = { fakeRepo }) =
        AlbumDetailViewModel(
            application = RuntimeEnvironment.getApplication() as Application,
            credentialRepo = credentials,
            repoFactory = repoFactory,
            backgroundDispatcher = testDispatcher,
        )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadAlbum populates detail state with photos`() =
        runTest {
            fakeRepo.photos = Result.success(listOf(beach, sunset))

            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertEquals("Summer", detail.albumName)
            assertEquals(2, detail.photos.size)
            assertFalse(detail.isLoading)
        }

    @Test
    fun `loadAlbum decodes the album name and sets loading synchronously`() {
        viewModel.loadAlbum("/remote.php/dav/files/user/My%20Trip/")

        val detail = viewModel.detailState.value
        assertEquals("My Trip", detail.albumName)
        assertTrue(detail.isLoading)
    }

    @Test
    fun `loadAlbum loads a thumbnail URL for every photo`() =
        runTest {
            fakeRepo.photos = Result.success(listOf(beach, sunset))

            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            assertEquals(
                mapOf(
                    beach.href to "https://fake.server/thumb/${beach.href}",
                    sunset.href to "https://fake.server/thumb/${sunset.href}",
                ),
                viewModel.detailState.value.thumbnailUrls,
            )
        }

    @Test
    fun `loadAlbum sets error on failure`() =
        runTest {
            fakeRepo.photos = Result.failure(WebDavHttpException(404, "PROPFIND failed: HTTP 404"))

            viewModel.loadAlbum("/Missing/")
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertEquals("typed error instead of raw exception text", AlbumsError.NotFound, detail.error)
            assertFalse(detail.isLoading)
        }

    @Test
    fun `loadAlbum with a repository factory throwing IllegalArgumentException shows an error instead of crashing`() =
        runTest {
            // e.g. a restored album detail entry whose stored server URL is no longer valid
            viewModel = createViewModel { throw IllegalArgumentException("Invalid WebDAV URL") }

            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertEquals(AlbumsError.InvalidServerUrl, detail.error)
            assertFalse(detail.isLoading)
        }

    @Test
    fun `loadAlbum without stored credentials shows not configured`() =
        runTest {
            credentials.clear() // e.g. restored album detail entry after logout

            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            assertEquals(AlbumsError.NotConfigured, viewModel.detailState.value.error)
            assertTrue(
                viewModel.detailState.value.photos
                    .isEmpty(),
            )
        }

    @Test
    fun `logout while the album is open clears its photos and stops using the old repository`() =
        runTest {
            fakeRepo.photos = Result.success(listOf(beach, sunset))
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            credentials.clear()
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertEquals(AlbumsError.NotConfigured, detail.error)
            assertTrue(detail.photos.isEmpty())
            assertTrue(detail.thumbnailUrls.isEmpty())
            assertEquals("no URLs of the old server", "", viewModel.fullImageUrl(beach))
            assertTrue(viewModel.repositories.get().isFailure)
            assertTrue(
                "accepted, but nothing is deleted with the old credentials",
                viewModel.deleteSelectedPhotos(listOf(beach)),
            )
            advanceUntilIdle()
            assertTrue(fakeRepo.deletedPhotos.isEmpty())
            assertEquals(PhotoDeletionOutcome(1), viewModel.photoDeletionOutcome.value)
        }

    @Test
    fun `a server change while the album is open clears it and cancels a running deletion`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.photos = Result.success(listOf(beach, sunset))
            fakeRepo.onDeletePhoto = { gate.await() }
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()
            viewModel.deleteSelectedPhotos(listOf(beach, sunset))
            advanceUntilIdle()

            credentials.save("https://other.example.com/remote.php/dav/files/me/", "me", "secret")
            advanceUntilIdle()

            assertEquals(AlbumsError.ConfigurationChanged, viewModel.detailState.value.error)
            assertTrue(
                viewModel.detailState.value.photos
                    .isEmpty(),
            )
            assertEquals("deletion stopped before the next photo", listOf(beach), fakeRepo.deletedPhotos)
            assertFalse("busy state is released", viewModel.isDeletingPhotos.value)
            assertNull("a cancelled deletion publishes nothing", viewModel.photoDeletionOutcome.value)
        }

    @Test
    fun `saving the same configuration again keeps the album`() =
        runTest {
            fakeRepo.photos = Result.success(listOf(beach))
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            credentials.save("https://cloud.example.com/remote.php/dav/files/user/", "user", "pass")
            advanceUntilIdle()

            assertNull(viewModel.detailState.value.error)
            assertEquals(listOf(beach), viewModel.detailState.value.photos)
        }

    @Test
    fun `loading another album replaces the previous album's state`() =
        runTest {
            fakeRepo.photos = Result.failure(WebDavHttpException(404, "PROPFIND failed: HTTP 404"))
            viewModel.loadAlbum("/Missing/")
            advanceUntilIdle()

            fakeRepo.photos = Result.success(listOf(beach))
            viewModel.loadAlbum("/Summer/")

            val loading = viewModel.detailState.value
            assertEquals("Summer", loading.albumName)
            assertNull("error of the previous album is cleared", loading.error)
            assertTrue(loading.photos.isEmpty())
            advanceUntilIdle()
            assertEquals(listOf(beach), viewModel.detailState.value.photos)
        }

    @Test
    fun `fullImageUrl is empty before the album is loaded and the repository URL afterwards`() =
        runTest {
            assertEquals("", viewModel.fullImageUrl(beach))

            fakeRepo.photos = Result.success(listOf(beach))
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            assertEquals("https://fake.server/full/${beach.href}", viewModel.fullImageUrl(beach))
        }

    @Test
    fun `deleteSelectedPhotos updates isDeletingPhotos flag`() =
        runTest {
            val photo = RemotePhoto("a.jpg", "/a.jpg", "1", "image/jpeg")
            fakeRepo.photos = Result.success(listOf(photo))

            viewModel.loadAlbum("/Album/")
            advanceUntilIdle()

            viewModel.isDeletingPhotos.test {
                assertEquals(false, awaitItem()) // initial

                viewModel.deleteSelectedPhotos(listOf(photo))

                assertEquals(true, awaitItem()) // deleting
                assertEquals(false, awaitItem()) // done
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `launchPhotoDeletion runs the caller's start and finish hooks without touching the selection flag`() =
        runTest {
            fakeRepo.photos = Result.success(listOf(beach))
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()
            val events = mutableListOf<String>()

            val started =
                viewModel.launchPhotoDeletion(
                    toDelete = listOf(beach),
                    onStart = { events += "start" },
                    onFinish = { events += "finish:${viewModel.photoDeletionOutcome.value}" },
                )
            assertEquals(listOf("start"), events)
            assertTrue(viewModel.isDeletingPhotos.value.not())
            advanceUntilIdle()

            assertTrue(started)
            assertEquals("finish runs before the outcome is published", listOf("start", "finish:null"), events)
            assertTrue(
                viewModel.detailState.value.photos
                    .isEmpty(),
            )
            assertFalse(viewModel.isDeletingPhotos.value)
        }

    @Test
    fun `loadAlbum for the already loaded album is a no-op but reload reloads it`() =
        runTest {
            fakeRepo.photos = Result.success(listOf(beach))
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            fakeRepo.photos = Result.success(listOf(beach, sunset))
            viewModel.loadAlbum("/Summer/") // screen recreated
            advanceUntilIdle()
            assertEquals(listOf(beach), viewModel.detailState.value.photos)

            viewModel.reload()
            advanceUntilIdle()
            assertEquals(listOf(beach, sunset), viewModel.detailState.value.photos)
        }

    @Test
    fun `loadAlbum after an error loads again`() =
        runTest {
            fakeRepo.photos = Result.failure(java.io.IOException("down"))
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()
            assertTrue(viewModel.detailState.value.error != null)

            fakeRepo.photos = Result.success(listOf(beach))
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            assertNull(viewModel.detailState.value.error)
            assertTrue(viewModel.detailState.value.isLoaded)
        }

    @Test
    fun `a screen recreated during a deletion does not reload and resurrect deleted photos`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.photos = Result.success(listOf(beach, sunset))
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()
            fakeRepo.onDeletePhoto = { gate.await() }
            viewModel.deleteSelectedPhotos(listOf(beach))
            runCurrent()

            viewModel.loadAlbum("/Summer/") // rotation while deleting
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(sunset), viewModel.detailState.value.photos)
        }

    @Test
    fun `reload before any album was requested does nothing`() =
        runTest {
            viewModel.reload()
            advanceUntilIdle()

            assertEquals(AlbumDetailState(), viewModel.detailState.value)
        }

    @Test
    fun `a deletion cancelled before it was dispatched still releases the busy state`() =
        runTest {
            fakeRepo.photos = Result.success(listOf(beach))
            val store = ViewModelStore()
            val scoped =
                ViewModelProvider(store, viewModelFactory { initializer { createViewModel() } })[
                    AlbumDetailViewModel::class.java,
                ]
            scoped.loadAlbum("/Summer/")
            advanceUntilIdle()

            scoped.deleteSelectedPhotos(listOf(beach))
            assertTrue(scoped.isDeletingPhotos.value)
            store.clear() // cancelled before runCurrent() could dispatch the deletion
            advanceUntilIdle()

            assertFalse("busy state must not stay stuck", scoped.isDeletingPhotos.value)
            assertTrue(fakeRepo.deletedPhotos.isEmpty())
        }

    // ── Streamed listing (issues #27 / #46) ─────────────────────────────────

    @Test
    fun `the running count is shown while loading and the photos are published once at the end`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.photos = Result.success(listOf(beach, sunset))
            fakeRepo.onListPhotos = { onProgress ->
                onProgress(100)
                gate.await()
            }

            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            val loading = viewModel.detailState.value
            assertTrue(loading.isLoading)
            assertEquals(100, loading.loadedCount)
            assertTrue("nothing published before the listing completes", loading.photos.isEmpty())
            assertFalse(loading.isLoaded)

            gate.complete(Unit)
            advanceUntilIdle()

            val loaded = viewModel.detailState.value
            assertFalse(loaded.isLoading)
            assertTrue(loaded.isLoaded)
            assertEquals(listOf(beach, sunset), loaded.photos)
            assertEquals(2, loaded.thumbnailUrls.size)
            assertEquals(2, loaded.loadedCount)
            assertFalse(loaded.isTruncated)
        }

    @Test
    fun `a truncated listing is loaded and flagged`() =
        runTest {
            fakeRepo.photos = Result.success(listOf(beach))
            fakeRepo.photosTruncated = true

            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertTrue(detail.isLoaded)
            assertTrue(detail.isTruncated)
            assertNull(detail.error)
        }

    @Test
    fun `reload while the album is still loading does not start a second listing`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.photos = Result.success(listOf(beach))
            fakeRepo.onListPhotos = { gate.await() }
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            viewModel.reload() // e.g. Retry in a review while the album is loading
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fakeRepo.listPhotosCalls)
            assertTrue(viewModel.detailState.value.isLoaded)
        }

    @Test
    fun `logout during loading publishes no photos`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.photos = Result.success(listOf(beach))
            fakeRepo.onListPhotos = { gate.await() }
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            credentials.clear()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertEquals(AlbumsError.NotConfigured, detail.error)
            assertTrue(detail.photos.isEmpty())
            assertEquals(0, detail.loadedCount)
            assertFalse(detail.isLoaded)
        }

    @Test
    fun `late progress of a listing cancelled by logout does not leak into the reload after login`() =
        runTest {
            var reportProgress: (Int) -> Unit = {}
            fakeRepo.photos = Result.success(listOf(beach))
            fakeRepo.onListPhotos = { onProgress ->
                reportProgress = onProgress
                awaitCancellation()
            }
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()
            val staleProgress = reportProgress

            credentials.clear()
            advanceUntilIdle()
            credentials.save("https://cloud.example.com/remote.php/dav/files/user/", "user", "pass")
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            fakeRepo.onListPhotos = { gate.await() }
            viewModel.reload() // Retry after logging in again: a new load is running
            advanceUntilIdle()
            assertTrue(viewModel.detailState.value.isLoading)

            staleProgress(500) // a late callback from the listing cancelled by the logout

            assertEquals("only the generation guard rejects it", 0, viewModel.detailState.value.loadedCount)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.detailState.value.isLoaded)
        }

    @Test
    fun `a failure of a listing cancelled by logout does not overwrite NotConfigured`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.photos = Result.success(listOf(beach))
            // Like a blocked read whose Call.cancel() surfaces as a parser / IO failure, not as cancellation.
            fakeRepo.onListPhotos = {
                withContext(NonCancellable) { gate.await() }
                throw IOException("Canceled")
            }
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            credentials.clear()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertEquals(AlbumsError.NotConfigured, detail.error)
            assertFalse(detail.isLoading)
        }

    @Test
    fun `a failure of a replaced load does not overwrite the next album`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.photos = Result.success(listOf(beach))
            fakeRepo.onListPhotos = {
                withContext(NonCancellable) { gate.await() }
                throw IOException("Canceled")
            }
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            val winterGate = CompletableDeferred<Unit>()
            fakeRepo.onListPhotos = { winterGate.await() }
            viewModel.loadAlbum("/Winter/")
            gate.complete(Unit)
            advanceUntilIdle()

            val detail = viewModel.detailState.value
            assertEquals("Winter", detail.albumName)
            assertNull(detail.error)
            assertTrue(detail.isLoading)
            winterGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.detailState.value.isLoaded)
        }

    @Test
    fun `progress of a replaced load does not leak into the next album`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var firstProgress: (Int) -> Unit = {}
            fakeRepo.photos = Result.success(listOf(beach))
            fakeRepo.onListPhotos = { onProgress ->
                firstProgress = onProgress
                gate.await()
            }
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            fakeRepo.onListPhotos = { gate.await() }
            viewModel.loadAlbum("/Winter/")
            firstProgress(300)

            assertEquals("Winter", viewModel.detailState.value.albumName)
            assertEquals(0, viewModel.detailState.value.loadedCount)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.detailState.value.isLoaded)
        }
}
