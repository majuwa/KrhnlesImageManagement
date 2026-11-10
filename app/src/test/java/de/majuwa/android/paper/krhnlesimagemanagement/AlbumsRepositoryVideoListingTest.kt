package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.PhotoListing
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindListingLimits
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavEntry
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.memoriesAwareDispatcher
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.model.imagesOnly
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Classification of videos in album listings and the shared item cap (issue #74). */
@RunWith(RobolectricTestRunner::class)
class AlbumsRepositoryVideoListingTest {
    private lateinit var server: MockWebServer
    private val album = "/remote.php/dav/files/user/Summer25/"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun entry(
        name: String,
        type: String,
        directory: Boolean = false,
    ) = WebDavEntry(
        href = "$album$name",
        displayName = name,
        contentType = type,
        isDirectory = directory,
        fileId = null,
        lastModified = "",
    )

    private fun kind(
        name: String,
        type: String,
        directory: Boolean = false,
    ) = AlbumsRepository.classifyRemoteMedia(entry(name, type, directory))

    @Test
    fun `allow-listed video content types are videos`() {
        assertEquals(MediaKind.Video, kind("a.bin", "video/mp4"))
        assertEquals(MediaKind.Video, kind("a", "video/x-matroska"))
        assertEquals(MediaKind.Video, kind("a", "video/3gpp2"))
    }

    @Test
    fun `content type parameters are stripped and case is ignored`() {
        assertEquals(MediaKind.Video, kind("a", "Video/QuickTime; charset=binary"))
    }

    @Test
    fun `video extension counts only for a blank or octet-stream type`() {
        assertEquals(MediaKind.Video, kind("clip.MOV", ""))
        assertEquals(MediaKind.Video, kind("clip.mkv", "application/octet-stream"))
        assertNull(kind("notes.mp4", "text/plain"))
    }

    @Test
    fun `unsupported containers are not listed`() {
        assertNull(kind("a.wmv", "video/x-ms-wmv"))
        assertNull(kind("a.avi", ""))
        assertNull(kind("a.ts", ""))
    }

    @Test
    fun `directories are never listed, even with a media type`() {
        assertNull(kind("x.mp4", "", directory = true))
        assertNull(kind("x", "image/jpeg", directory = true))
        assertNull(kind("x", "video/mp4", directory = true))
    }

    @Test
    fun `image classification keeps precedence`() {
        assertEquals(MediaKind.Image, kind("photo.jpg", "video/mp4"))
        assertEquals(MediaKind.Image, kind("a", "image/png"))
        assertEquals(MediaKind.Image, kind("a.HEIC", ""))
    }

    @Test
    fun `imagesOnly drops videos`() {
        val image = RemotePhoto("a", "/a", null, "image/jpeg")
        val video = RemotePhoto("b", "/b", null, "video/mp4", MediaKind.Video)
        assertTrue(video.isVideo)
        assertFalse(image.isVideo)
        assertEquals(listOf(image), listOf(image, video).imagesOnly())
    }

    // ── Listing ────────────────────────────────────────────────────────────

    private fun xmlEntry(
        href: String,
        type: String?,
        collection: Boolean = type == null,
    ) = "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
        (if (collection) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/>") +
        (if (type != null) "<d:getcontenttype>$type</d:getcontenttype>" else "") +
        "</d:prop></d:propstat></d:response>"

    private fun listing(
        entries: List<String>,
        maxPhotos: Int = PropfindListingLimits().maxPhotos,
    ): PhotoListing {
        val body =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">" +
                (listOf(xmlEntry(album, null)) + entries).joinToString("") + "</d:multistatus>"
        server.dispatcher =
            memoriesAwareDispatcher(
                MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(body),
            )
        val repo =
            AlbumsRepository(
                WebDavConfig(server.url("/remote.php/dav/files/user/").toString(), "u", "p"),
                null,
                PropfindListingLimits(maxPhotos = maxPhotos),
            )
        return runBlocking { repo.listPhotos(album) }.getOrThrow()
    }

    @Test
    fun `listing marks videos and keeps server order`() {
        val result =
            listing(
                listOf(
                    xmlEntry("${album}a.jpg", "image/jpeg"),
                    xmlEntry("${album}b.mp4", "video/mp4"),
                    xmlEntry("${album}c.txt", "text/plain"),
                    xmlEntry("${album}d.mov", ""),
                ),
            )
        assertEquals(listOf("a.jpg", "b.mp4", "d.mov"), result.photos.map { it.displayName })
        assertEquals(listOf(false, true, true), result.photos.map { it.isVideo })
    }

    @Test
    fun `a collection with an image content type is not listed`() {
        val result =
            listing(
                listOf(
                    xmlEntry("${album}sub/", "image/jpeg", collection = true),
                    xmlEntry("${album}a.jpg", "image/jpeg"),
                ),
            )
        assertEquals(listOf("a.jpg"), result.photos.map { it.displayName })
    }

    @Test
    fun `the cap counts photos and videos together in server order`() {
        val result =
            listing(
                listOf(
                    xmlEntry("${album}1.jpg", "image/jpeg"),
                    xmlEntry("${album}2.mp4", "video/mp4"),
                    xmlEntry("${album}3.jpg", "image/jpeg"),
                    xmlEntry("${album}4.mp4", "video/mp4"),
                ),
                maxPhotos = 3,
            )
        assertEquals(listOf("1.jpg", "2.mp4", "3.jpg"), result.photos.map { it.displayName })
        assertTrue(result.isTruncated)
    }

    @Test
    fun `a video beyond the cap truncates and a lone video is not an empty truncated album`() {
        val result = listing(listOf(xmlEntry("${album}1.mp4", "video/mp4"), xmlEntry("${album}2.mp4", "video/mp4")), 1)
        assertTrue(result.isTruncated)
        assertEquals(1, result.photos.size)
    }

    @Test
    fun `duplicate video hrefs are dropped and unsafe ones skipped`() {
        val result =
            listing(
                listOf(
                    xmlEntry("${album}1.mp4", "video/mp4"),
                    xmlEntry("${album}1.mp4", "video/mp4"),
                    xmlEntry("/remote.php/dav/files/user/Other/2.mp4", "video/mp4"),
                    xmlEntry("$album../3.mp4", "video/mp4"),
                ),
            )
        assertEquals(listOf("1.mp4"), result.photos.map { it.displayName })
        assertFalse(result.isTruncated)
    }

    private fun deleteVideo(code: Int): Result<Unit> {
        server.enqueue(MockResponse().setResponseCode(code))
        val repo =
            AlbumsRepository(WebDavConfig(server.url("/remote.php/dav/files/user/").toString(), "u", "p"), null)
        val video = RemotePhoto("v.mp4", "${album}v.mp4", "7", "video/mp4", MediaKind.Video)
        return runBlocking { repo.deletePhoto(video) }
    }

    @Test
    fun `deleting a video that is already gone counts as deleted`() {
        assertTrue(deleteVideo(404).isSuccess)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `deleting a video that the server refuses fails`() {
        assertTrue(deleteVideo(403).isFailure)
    }
}
