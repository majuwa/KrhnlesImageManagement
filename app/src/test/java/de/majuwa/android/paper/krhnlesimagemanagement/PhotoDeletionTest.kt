package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.PhotoDeletionResult
import de.majuwa.android.paper.krhnlesimagemanagement.data.deletePhotosIndividually
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class PhotoDeletionTest {
    private val repo = FakeAlbumsRepository()
    private val photoA = RemotePhoto("a.jpg", "/Album/a.jpg", "1", "image/jpeg")
    private val photoB = RemotePhoto("b.jpg", "/Album/b.jpg", "2", "image/jpeg")
    private val photoC = RemotePhoto("c.jpg", "/Album/c.jpg", "3", "image/jpeg")

    @Test
    fun `all deletions succeed`() =
        runTest {
            val result = repo.deletePhotosIndividually(listOf(photoA, photoB))

            assertEquals(PhotoDeletionResult(setOf(photoA.href, photoB.href), 0), result)
        }

    @Test
    fun `partial failure reports only successful hrefs and counts failures`() =
        runTest {
            repo.deletePhotoResults = mapOf(photoB.href to Result.failure(RuntimeException("403")))

            val result = repo.deletePhotosIndividually(listOf(photoA, photoB, photoC))

            assertEquals(PhotoDeletionResult(setOf(photoA.href, photoC.href), 1), result)
        }

    @Test
    fun `all deletions fail`() =
        runTest {
            repo.deletePhotoResult = Result.failure(RuntimeException("500"))

            val result = repo.deletePhotosIndividually(listOf(photoA, photoB))

            assertEquals(PhotoDeletionResult(emptySet(), 2), result)
        }

    @Test
    fun `empty list makes no request`() =
        runTest {
            val result = repo.deletePhotosIndividually(emptyList())

            assertEquals(PhotoDeletionResult(emptySet(), 0), result)
            assertTrue(repo.deletedPhotos.isEmpty())
        }

    @Test
    fun `every photo is attempted in order even after a failure`() =
        runTest {
            repo.deletePhotoResults = mapOf(photoA.href to Result.failure(RuntimeException("403")))

            repo.deletePhotosIndividually(listOf(photoA, photoB, photoC))

            assertEquals(listOf(photoA, photoB, photoC), repo.deletedPhotos)
        }

    @Test
    fun `a thrown exception counts as one failure and does not stop the rest`() =
        runTest {
            repo.onDeletePhoto = { photo -> if (photo == photoB) error("boom") }

            val result = repo.deletePhotosIndividually(listOf(photoA, photoB, photoC))

            assertEquals(PhotoDeletionResult(setOf(photoA.href, photoC.href), 1), result)
        }

    @Test
    fun `a cancellation result is rethrown and not counted as failure`() =
        runTest {
            repo.deletePhotoResults = mapOf(photoA.href to Result.failure(CancellationException("cancelled")))

            try {
                repo.deletePhotosIndividually(listOf(photoA, photoB))
                fail("cancellation must be rethrown")
            } catch (expected: CancellationException) {
                assertEquals("cancelled", expected.message)
            }
            assertEquals(listOf(photoA), repo.deletedPhotos)
        }

    @Test
    fun `cancelling the calling coroutine stops before the next photo`() =
        runTest {
            repo.onDeletePhoto = { currentCoroutineContext().job.cancel() }
            var result: PhotoDeletionResult? = null

            launch { result = repo.deletePhotosIndividually(listOf(photoA, photoB, photoC)) }.join()

            assertEquals("only the first photo is attempted", listOf(photoA), repo.deletedPhotos)
            assertEquals("a cancelled deletion yields no result", null, result)
        }

    @Test
    fun `allFailed counts every photo and deletes nothing`() {
        assertEquals(PhotoDeletionResult(emptySet(), 3), PhotoDeletionResult.allFailed(listOf(photoA, photoB, photoC)))
    }
}
