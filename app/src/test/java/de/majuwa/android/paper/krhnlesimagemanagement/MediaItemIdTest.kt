package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.mediaItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Image and video MediaStore ids overlap; the video flag keeps grid / upload ids apart (issues #15 / #34). */
class MediaItemIdTest {
    @Test
    fun `image ids are unchanged`() {
        assertEquals(42L, mediaItemId(42L, MediaKind.Image))
        assertEquals(0L, mediaItemId(0L, MediaKind.Image))
    }

    @Test
    fun `video ids do not collide with image ids of the same raw id`() {
        val video = mediaItemId(42L, MediaKind.Video)
        assertNotEquals(mediaItemId(42L, MediaKind.Image), video)
        assertEquals((1L shl 62) + 42, video)
    }

    @Test
    fun `video ids are positive`() {
        val largest = mediaItemId(Photo.VIDEO_ID_FLAG - 1, MediaKind.Video)!!
        assertTrue(largest > 0L)
        assertTrue(mediaItemId(1L, MediaKind.Video)!! > 0L)
    }

    @Test
    fun `raw ids at or above 2^62 and negative ids are skipped`() {
        assertNull(mediaItemId(1L shl 62, MediaKind.Video))
        assertNull(mediaItemId(1L shl 62, MediaKind.Image))
        assertNull(mediaItemId(Long.MAX_VALUE, MediaKind.Video))
        assertNull(mediaItemId(-1L, MediaKind.Image))
    }
}
