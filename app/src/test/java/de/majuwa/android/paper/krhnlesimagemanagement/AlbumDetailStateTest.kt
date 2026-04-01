package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDetailStateTest {
    private val photo1 = RemotePhoto("img1.jpg", "/photos/img1.jpg", null, "image/jpeg")
    private val photo2 = RemotePhoto("img2.jpg", "/photos/img2.jpg", null, "image/jpeg")
    private val photo3 = RemotePhoto("img3.jpg", "/photos/img3.jpg", "42", "image/jpeg")

    private val baseState =
        AlbumDetailState(
            photos = listOf(photo1, photo2, photo3),
            thumbnailUrls =
                mapOf(
                    photo1.href to "url1",
                    photo2.href to "url2",
                    photo3.href to "url3",
                ),
        )

    @Test
    fun `withPhotosDeleted removes matching photo and its thumbnail url`() {
        val result = baseState.withPhotosDeleted(setOf(photo1.href))

        assertEquals(listOf(photo2, photo3), result.photos)
        assertFalse(result.thumbnailUrls.containsKey(photo1.href))
        assertTrue(result.thumbnailUrls.containsKey(photo2.href))
        assertTrue(result.thumbnailUrls.containsKey(photo3.href))
    }

    @Test
    fun `withPhotosDeleted with empty set leaves state unchanged`() {
        val result = baseState.withPhotosDeleted(emptySet())

        assertEquals(baseState.photos, result.photos)
        assertEquals(baseState.thumbnailUrls, result.thumbnailUrls)
    }

    @Test
    fun `withPhotosDeleted with all hrefs produces empty photos and urls`() {
        val result = baseState.withPhotosDeleted(setOf(photo1.href, photo2.href, photo3.href))

        assertTrue(result.photos.isEmpty())
        assertTrue(result.thumbnailUrls.isEmpty())
    }

    @Test
    fun `withPhotosDeleted with unknown href leaves state unchanged`() {
        val result = baseState.withPhotosDeleted(setOf("/photos/unknown.jpg"))

        assertEquals(baseState.photos, result.photos)
        assertEquals(baseState.thumbnailUrls, result.thumbnailUrls)
    }

    @Test
    fun `withPhotosDeleted preserves other AlbumDetailState fields`() {
        val result =
            baseState
                .copy(albumName = "Summer", isLoading = false)
                .withPhotosDeleted(setOf(photo1.href))

        assertEquals("Summer", result.albumName)
        assertFalse(result.isLoading)
    }
}
