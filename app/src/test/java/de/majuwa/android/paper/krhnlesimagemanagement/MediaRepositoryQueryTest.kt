package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider.Companion.row
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Images and videos from separate MediaStore collections are merged into one list (issues #15 / #34). */
@RunWith(RobolectricTestRunner::class)
class MediaRepositoryQueryTest {
    private lateinit var context: Context

    private val ten = Instant.parse("2026-05-14T10:00:00Z").toEpochMilli()
    private val eleven = Instant.parse("2026-05-14T11:00:00Z").toEpochMilli()
    private val twelve = Instant.parse("2026-05-14T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FakeMediaStoreProvider.reset()
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
    }

    @After
    fun tearDown() = FakeMediaStoreProvider.reset()

    private fun load(): List<Photo> = runBlocking { MediaRepository(context.contentResolver).loadPhotos() }

    @Test
    fun `photos and videos are merged by capture time newest first`() {
        FakeMediaStoreProvider.imageRows =
            listOf(row(1, "noon.jpg", twelve, "image/jpeg"), row(2, "ten.jpg", ten, "image/jpeg"))
        FakeMediaStoreProvider.videoRows = listOf(row(3, "eleven.mp4", eleven, "video/mp4", durationMs = 65_000))

        val photos = load()

        assertEquals(listOf("noon.jpg", "eleven.mp4", "ten.jpg"), photos.map { it.displayName })
        assertEquals(listOf(MediaKind.Image, MediaKind.Video, MediaKind.Image), photos.map { it.mediaKind })
    }

    @Test
    fun `an image and a video with the same raw id stay separate items`() {
        FakeMediaStoreProvider.imageRows = listOf(row(42, "a.jpg", ten, "image/jpeg"))
        FakeMediaStoreProvider.videoRows = listOf(row(42, "b.mp4", eleven, "video/mp4"))

        val photos = load()

        assertEquals(2, photos.map { it.id }.toSet().size)
        val video = photos.single { it.isVideo }
        val image = photos.single { !it.isVideo }
        assertEquals(42L, image.id)
        assertEquals((1L shl 62) + 42, video.id)
        assertEquals(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                .buildUpon()
                .appendPath("42")
                .build(),
            video.uri,
        )
        assertEquals(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                .buildUpon()
                .appendPath("42")
                .build(),
            image.uri,
        )
    }

    @Test
    fun `a video row without mime type is a video with the generic video type`() {
        FakeMediaStoreProvider.videoRows = listOf(row(7, "clip", ten, null))
        FakeMediaStoreProvider.imageRows = listOf(row(8, "pic", eleven, null))

        val photos = load()

        val video = photos.single { it.displayName == "clip" }
        assertTrue(video.isVideo)
        assertEquals("video/*", video.mimeType)
        assertEquals("image/*", photos.single { it.displayName == "pic" }.mimeType)
    }

    @Test
    fun `a video without capture date gets the unknown date`() {
        FakeMediaStoreProvider.videoRows =
            listOf(row(1, "nodate.mp4", null, "video/mp4"), row(2, "zero.mp4", 0L, "video/mp4"))

        val photos = load()

        assertTrue(photos.all { it.dateTaken == Photo.UNKNOWN_DATE && it.isVideo })
        assertTrue(photos.none { it.hasKnownDate })
    }

    @Test
    fun `duration is read for videos and null when missing`() {
        FakeMediaStoreProvider.videoRows =
            listOf(row(1, "a.mp4", twelve, "video/mp4", durationMs = 65_000), row(2, "b.mp4", ten, "video/mp4"))
        FakeMediaStoreProvider.imageRows = listOf(row(3, "c.jpg", eleven, "image/jpeg"))

        val byName = load().associateBy { it.displayName }

        assertEquals(65_000L, byName.getValue("a.mp4").durationMs)
        assertNull(byName.getValue("b.mp4").durationMs)
        assertNull(byName.getValue("c.jpg").durationMs)
    }

    @Test
    fun `a video query without permission yields only the images`() {
        FakeMediaStoreProvider.imageRows = listOf(row(1, "a.jpg", ten, "image/jpeg"))
        FakeMediaStoreProvider.videoRows = listOf(row(2, "b.mp4", eleven, "video/mp4"))
        FakeMediaStoreProvider.videoFailure = SecurityException("no video access")

        val photos = load()

        assertEquals(listOf("a.jpg"), photos.map { it.displayName })
        assertFalse(photos.single().isVideo)
    }

    @Test
    fun `a raw id at the video flag is skipped`() {
        FakeMediaStoreProvider.videoRows =
            listOf(row(1L shl 62, "huge.mp4", ten, "video/mp4"), row(5, "ok.mp4", eleven, "video/mp4"))

        assertEquals(listOf("ok.mp4"), load().map { it.displayName })
    }
}
