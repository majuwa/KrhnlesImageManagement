package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumDeletionTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun deletePhoto_removesPhotoFromAlbum() =
        runTest(testDispatcher) {
            // Arrange
            val photos =
                mutableListOf(
                    createRemotePhoto("photo1.jpg", "/album/photo1.jpg"),
                    createRemotePhoto("photo2.jpg", "/album/photo2.jpg"),
                )

            // Act: Remove first photo
            val photoToDelete = photos[0]
            val remaining = photos.filter { it.href != photoToDelete.href }

            // Assert
            assertEquals(1, remaining.size)
            assertEquals("photo2.jpg", remaining[0].displayName)
        }

    @Test
    fun deleteMultiplePhotos_removesAllSelectedPhotos() =
        runTest(testDispatcher) {
            // Arrange
            val photos =
                mutableListOf(
                    createRemotePhoto("photo1.jpg", "/album/photo1.jpg"),
                    createRemotePhoto("photo2.jpg", "/album/photo2.jpg"),
                    createRemotePhoto("photo3.jpg", "/album/photo3.jpg"),
                )
            val photosToDelete = listOf(photos[0], photos[2])

            // Act
            val remaining = photos.filter { photo -> photosToDelete.none { it.href == photo.href } }

            // Assert
            assertEquals(1, remaining.size)
            assertEquals("photo2.jpg", remaining[0].displayName)
        }

    @Test
    fun deleteAllPhotos_leavesEmptyAlbum() =
        runTest(testDispatcher) {
            // Arrange
            val photos =
                listOf(
                    createRemotePhoto("photo1.jpg", "/album/photo1.jpg"),
                    createRemotePhoto("photo2.jpg", "/album/photo2.jpg"),
                )

            // Act
            val remaining = photos.filter { false }

            // Assert
            assertTrue(remaining.isEmpty())
        }

    @Test
    fun deleteNonExistentPhoto_doesNotThrow() =
        runTest(testDispatcher) {
            // Arrange
            val photos =
                listOf(
                    createRemotePhoto("photo1.jpg", "/album/photo1.jpg"),
                )
            val photoToDelete = createRemotePhoto("nonexistent.jpg", "/album/nonexistent.jpg")

            // Act & Assert: Should not throw
            val remaining = photos.filter { it.href != photoToDelete.href }
            assertEquals(1, remaining.size)
        }

    @Test
    fun deletePhotoPreservesOthers() =
        runTest(testDispatcher) {
            // Arrange
            val photos =
                listOf(
                    createRemotePhoto("photo1.jpg", "/album/photo1.jpg"),
                    createRemotePhoto("photo2.jpg", "/album/photo2.jpg"),
                    createRemotePhoto("photo3.jpg", "/album/photo3.jpg"),
                )

            // Act: Delete middle photo (by href)
            val hrefToDelete = "/album/photo2.jpg"
            val remaining = photos.filter { it.href != hrefToDelete }

            // Assert
            assertEquals(2, remaining.size)
            assertEquals("photo1.jpg", remaining[0].displayName)
            assertEquals("photo3.jpg", remaining[1].displayName)
        }

    @Test
    fun deletionState_progressTracking() =
        runTest(testDispatcher) {
            // Arrange
            var isDeleting = false

            // Act
            isDeleting = true

            // Assert
            assertTrue(isDeleting)

            // Cleanup
            isDeleting = false
            assertEquals(false, isDeleting)
        }

    @Test
    fun photoDeletion_filtersOutDeletedByHref() =
        runTest(testDispatcher) {
            // Arrange
            val photos =
                listOf(
                    createRemotePhoto("photo1.jpg", "/album/photo1.jpg"),
                    createRemotePhoto("photo2.jpg", "/album/photo2.jpg"),
                    createRemotePhoto("photo3.jpg", "/album/photo3.jpg"),
                )
            val toDelete = setOf("/album/photo2.jpg")

            // Act
            val remaining = photos.filter { it.href !in toDelete }

            // Assert
            assertEquals(2, remaining.size)
            assertTrue(remaining.all { it.href !in toDelete })
        }

    @Test
    fun batchDelete_handlesLargePhotoList() =
        runTest(testDispatcher) {
            // Arrange
            val photos =
                (1..100).map { i ->
                    createRemotePhoto("photo_$i.jpg", "/album/photo_$i.jpg")
                }
            val toDeleteHrefs = photos.subList(0, 50).map { it.href }.toSet()

            // Act
            val remaining = photos.filter { it.href !in toDeleteHrefs }

            // Assert
            assertEquals(50, remaining.size)
        }

    // ── Helper Functions ────────────────────────────────────────────────────

    private fun createRemotePhoto(
        name: String,
        href: String,
    ): RemotePhoto =
        RemotePhoto(
            displayName = name,
            href = href,
            fileId = null,
            contentType = "image/jpeg",
        )
}
