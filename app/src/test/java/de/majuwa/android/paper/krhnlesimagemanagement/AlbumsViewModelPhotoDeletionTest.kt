package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicatesState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.PhotoDeletionOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.coroutines.cancellation.CancellationException

/**
 * Exercises the three photo-deletion entry points of [AlbumsViewModel]
 * (duplicate review, blur review, album selection mode) against the same
 * behavioural contract (`album-photo-deletion` spec, issues #28 / #47).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class AlbumsViewModelPhotoDeletionTest(
    private val path: DeletePath,
) {
    enum class DeletePath { DUPLICATES, BLUR, SELECTION }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeAlbumsRepository
    private lateinit var viewModel: AlbumsViewModel

    private val photoA = RemotePhoto("a.jpg", "/Album/a.jpg", "1", "image/jpeg")
    private val photoB = RemotePhoto("b.jpg", "/Album/b.jpg", "2", "image/jpeg")
    private val photoC = RemotePhoto("c.jpg", "/Album/c.jpg", "3", "image/jpeg")
    private val allPhotos = listOf(photoA, photoB, photoC)

    private fun createViewModel(repoFactory: (WebDavConfig) -> AlbumsRepositoryContract): AlbumsViewModel =
        AlbumsViewModel(
            application = RuntimeEnvironment.getApplication() as Application,
            credentialRepo =
                FakeCredentialRepository(
                    WebDavConfig(
                        url = "https://cloud.example.com/remote.php/dav/files/user/",
                        username = "user",
                        password = "pass",
                    ),
                ),
            repoFactory = repoFactory,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeAlbumsRepository().apply { photos = Result.success(allPhotos) }
        viewModel = createViewModel { fakeRepo }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.loadAlbum() {
        viewModel.loadAlbum("/Album/")
        advanceUntilIdle()
        assertEquals(allPhotos.size, viewModel.detailState.value.thumbnailUrls.size)
    }

    private fun delete(photos: List<RemotePhoto>): Boolean =
        when (path) {
            DeletePath.DUPLICATES -> viewModel.deletePhotos(photos)
            DeletePath.BLUR -> viewModel.deleteBlurryPhotos(photos)
            DeletePath.SELECTION -> viewModel.deleteSelectedPhotos(photos)
        }

    private fun isBusy(): Boolean =
        when (path) {
            DeletePath.DUPLICATES -> viewModel.duplicatesState.value == DuplicatesState.Deleting
            DeletePath.BLUR -> viewModel.blurState.value == BlurState.Deleting
            DeletePath.SELECTION -> viewModel.isDeletingPhotos.value
        }

    private fun assertIdle() {
        when (path) {
            DeletePath.DUPLICATES -> assertEquals(DuplicatesState.Idle, viewModel.duplicatesState.value)
            DeletePath.BLUR -> assertEquals(BlurState.Idle, viewModel.blurState.value)
            DeletePath.SELECTION -> assertFalse(viewModel.isDeletingPhotos.value)
        }
    }

    private fun outcome(): PhotoDeletionOutcome? = viewModel.photoDeletionOutcome.value

    private fun remainingHrefs(): List<String> =
        viewModel.detailState.value.photos
            .map { it.href }

    private fun thumbnailHrefs(): Set<String> =
        viewModel.detailState.value.thumbnailUrls.keys
            .toSet()

    @Test
    fun `successful deletion removes photos and thumbnails and reports zero failures`() =
        runTest(testDispatcher) {
            loadAlbum()

            assertTrue(delete(listOf(photoA, photoB)))
            advanceUntilIdle()

            assertEquals(PhotoDeletionOutcome(0), outcome())
            assertEquals(listOf(photoA, photoB), fakeRepo.deletedPhotos)
            assertEquals(listOf(photoC.href), remainingHrefs())
            assertEquals(setOf(photoC.href), thumbnailHrefs())
            assertIdle()
        }

    @Test
    fun `partial failure attempts every photo and reports the failure count`() =
        runTest(testDispatcher) {
            loadAlbum()
            fakeRepo.deletePhotoResults = mapOf(photoB.href to Result.failure(RuntimeException("403")))

            delete(listOf(photoA, photoB, photoC))
            advanceUntilIdle()

            assertEquals(PhotoDeletionOutcome(1), outcome())
            assertEquals(allPhotos, fakeRepo.deletedPhotos)
            assertIdle()
        }

    @Test
    fun `partial failure keeps the failed photo and its thumbnail in the album`() =
        runTest(testDispatcher) {
            loadAlbum()
            fakeRepo.deletePhotoResults = mapOf(photoB.href to Result.failure(RuntimeException("403")))

            delete(listOf(photoA, photoB))
            advanceUntilIdle()

            assertEquals(listOf(photoB.href, photoC.href), remainingHrefs())
            assertEquals(setOf(photoB.href, photoC.href), thumbnailHrefs())
        }

    @Test
    fun `all deletions failing leaves the album unchanged`() =
        runTest(testDispatcher) {
            loadAlbum()
            fakeRepo.deletePhotoResult = Result.failure(RuntimeException("500"))

            delete(listOf(photoA, photoB))
            advanceUntilIdle()

            assertEquals(PhotoDeletionOutcome(2), outcome())
            assertEquals(allPhotos.map { it.href }, remainingHrefs())
            assertEquals(allPhotos.map { it.href }.toSet(), thumbnailHrefs())
            assertIdle()
        }

    @Test
    fun `empty list makes no server call and completes with zero failures`() =
        runTest(testDispatcher) {
            loadAlbum()

            assertTrue(delete(emptyList()))

            // Completes synchronously, without launching a coroutine.
            assertEquals(PhotoDeletionOutcome(0), outcome())
            assertIdle()
            advanceUntilIdle()
            assertTrue(fakeRepo.deletedPhotos.isEmpty())
            assertEquals(allPhotos.map { it.href }, remainingHrefs())
        }

    @Test
    fun `busy state is set while deleting and cleared before the outcome is published`() =
        runTest(testDispatcher) {
            loadAlbum()
            assertFalse(isBusy())

            // Unconfined collector: runs synchronously when the outcome is published.
            var busyWhenOutcomePublished: Boolean? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.photoDeletionOutcome.collect { value ->
                    if (value != null) busyWhenOutcomePublished = isBusy()
                }
            }

            delete(listOf(photoA))
            assertTrue("busy state must be set as soon as the deletion starts", isBusy())
            assertNull("no outcome before the deletion has run", outcome())

            advanceUntilIdle()

            assertEquals(false, busyWhenOutcomePublished)
            assertEquals(PhotoDeletionOutcome(0), outcome())
            assertIdle()
        }

    @Test
    fun `busy state is observable while the deletion is suspended`() =
        runTest(testDispatcher) {
            loadAlbum()
            var busyDuringDelete = false
            fakeRepo.onDeletePhoto = { busyDuringDelete = isBusy() }

            delete(listOf(photoA))
            advanceUntilIdle()

            assertTrue(busyDuringDelete)
            assertIdle()
        }

    @Test
    fun `a throwing repository counts the photo as failed instead of crashing`() =
        runTest(testDispatcher) {
            loadAlbum()
            fakeRepo.onDeletePhoto = { photo -> if (photo == photoA) error("boom") }

            delete(listOf(photoA, photoB))
            advanceUntilIdle()

            assertEquals(PhotoDeletionOutcome(1), outcome())
            assertEquals(listOf(photoA, photoB), fakeRepo.deletedPhotos)
            assertEquals(listOf(photoA.href, photoC.href), remainingHrefs())
            assertIdle()
        }

    @Test
    fun `failure to create the repository reports every photo as failed instead of crashing`() =
        runTest(testDispatcher) {
            viewModel = createViewModel { throw IllegalArgumentException("Invalid WebDAV URL") }

            delete(listOf(photoA, photoB))
            advanceUntilIdle()

            assertEquals(PhotoDeletionOutcome(2), outcome())
            assertFalse(isBusy())
            assertIdle()
        }

    @Test
    fun `cancellation stops the loop and is not reported as a failure`() =
        runTest(testDispatcher) {
            loadAlbum()
            fakeRepo.onDeletePhoto = { photo -> if (photo == photoA) throw CancellationException("cancelled") }

            delete(listOf(photoA, photoB, photoC))
            advanceUntilIdle()

            assertEquals("no photo after the cancellation is attempted", listOf(photoA), fakeRepo.deletedPhotos)
            assertNull("a cancelled deletion publishes no outcome", outcome())
            assertEquals(allPhotos.map { it.href }, remainingHrefs())
            assertIdle()
        }

    @Test
    fun `re-entry while a deletion is running is ignored`() =
        runTest(testDispatcher) {
            loadAlbum()
            val gate = CompletableDeferred<Unit>()
            fakeRepo.onDeletePhoto = { gate.await() }

            assertTrue(delete(listOf(photoA, photoB)))
            runCurrent()
            assertFalse("second call must be ignored", delete(listOf(photoA, photoB)))
            assertFalse(viewModel.deleteSelectedPhotos(listOf(photoC)))
            assertFalse(viewModel.deletePhotos(listOf(photoC)))
            assertFalse(viewModel.deleteBlurryPhotos(listOf(photoC)))
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("one DELETE per photo", listOf(photoA, photoB), fakeRepo.deletedPhotos)
            assertEquals(PhotoDeletionOutcome(0), outcome())
            assertIdle()

            // The guard is released once the deletion has finished.
            assertTrue(delete(listOf(photoC)))
            advanceUntilIdle()
            assertEquals(allPhotos, fakeRepo.deletedPhotos)
        }

    @Test
    fun `consuming the outcome clears it`() =
        runTest(testDispatcher) {
            loadAlbum()
            fakeRepo.deletePhotoResult = Result.failure(RuntimeException("500"))
            delete(listOf(photoA))
            advanceUntilIdle()
            assertEquals(PhotoDeletionOutcome(1), outcome())

            viewModel.consumePhotoDeletionOutcome()

            assertNull(outcome())
        }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun paths(): List<Array<Any>> = DeletePath.entries.map { arrayOf(it) }
    }
}
