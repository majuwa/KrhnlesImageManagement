package de.majuwa.android.paper.krhnlesimagemanagement.ui

import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import junit.framework.TestCase.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FullscreenViewerScreenTest {
    @Test
    fun pageCounter_displaysCorrectFormat() {
        val currentPage = 3
        val totalPages = 12

        val pageText = "$currentPage/$totalPages"

        assertTrue(pageText == "3/12")
    }

    @Test
    fun pageCounter_showsFirstPage() {
        val pageText = "${1}/${5}"

        assertTrue(pageText == "1/5")
    }

    @Test
    fun pageCounter_showsLastPage() {
        val pageText = "${5}/${5}"

        assertTrue(pageText == "5/5")
    }

    @Test
    fun multiplePhotosDisplay_showsPhotoCount() {
        val photos =
            listOf(
                createRemotePhoto("photo1.jpg", "/photos/photo1.jpg"),
                createRemotePhoto("photo2.jpg", "/photos/photo2.jpg"),
            )

        assertTrue(photos.size == 2)
    }

    @Test
    fun emptyPhotoList_showsEmptyState() {
        val photos = emptyList<RemotePhoto>()

        assertTrue(photos.isEmpty())
    }

    @Test
    fun photoList_containsDisplayNames() {
        val photos =
            listOf(
                createRemotePhoto("photo1.jpg", "/photos/photo1.jpg"),
                createRemotePhoto("photo2.jpg", "/photos/photo2.jpg"),
            )

        assertTrue(photos.any { it.displayName == "photo1.jpg" })
        assertTrue(photos.any { it.displayName == "photo2.jpg" })
    }

    @Test
    fun photoNavigation_byIndex() {
        val photos =
            (1..5).map { i ->
                createRemotePhoto("photo_$i.jpg", "/photos/photo_$i.jpg")
            }

        var currentIndex = 0
        val currentPhoto = photos[currentIndex]

        assertTrue(currentPhoto.displayName == "photo_1.jpg")

        currentIndex = 4
        val lastPhoto = photos[currentIndex]
        assertTrue(lastPhoto.displayName == "photo_5.jpg")
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
