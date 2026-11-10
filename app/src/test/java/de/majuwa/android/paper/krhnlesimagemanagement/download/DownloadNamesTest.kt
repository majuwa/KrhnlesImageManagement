package de.majuwa.android.paper.krhnlesimagemanagement.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DownloadNamesTest {
    private fun name(
        href: String,
        now: Long = 1234L,
    ) = downloadDisplayName("/dav/Album/$href", now, Random(1))

    @Test
    fun `an encoded name keeps its text and loses the bidi override`() {
        val result = name("Caf%C3%A9%20%E2%80%AE gpj.jpg")
        assertNotNull(result)
        assertTrue(result!!, result.contains("Café"))
        assertFalse(result.contains('‮'))
        assertTrue(result.endsWith(".jpg"))
    }

    @Test
    fun `reserved characters are replaced`() {
        assertEquals("a_b_c_d_e_f_g_h.png", name("a%3Ab%2Ac%3Fd%22e%3Cf%3Eg%7Ch.png"))
    }

    @Test
    fun `an encoded slash or backslash never makes a path`() {
        val result = name("a%2F..%2Fb%5Cc.jpg")!!
        assertFalse(result.contains('/'))
        assertFalse(result.contains('\\'))
    }

    @Test
    fun `control and line separators are replaced`() {
        assertEquals("a_b_c.jpg", name("a%00b%E2%80%A8c.jpg"))
    }

    @Test
    fun `an unpaired surrogate is replaced but an emoji stays`() {
        assertEquals("x_y", sanitizeSegment("x\uD800y"))
        assertEquals("x_y", sanitizeSegment("x\uDC00y"))
        assertEquals("😀.jpg", name("%F0%9F%98%80.jpg"))
    }

    @Test
    fun `a leading dot is removed so the photo is not hidden`() {
        assertEquals("IMG.jpg", name(".IMG.jpg"))
        assertEquals("IMG.jpg", name("...%20IMG.jpg"))
    }

    @Test
    fun `a long multi byte name is cut at the byte limit and keeps its extension`() {
        val result = name("%C3%A4".repeat(300) + ".jpg")!!
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MAX_DOWNLOAD_NAME_BYTES)
        assertTrue(result.endsWith(".jpg"))
        assertTrue(result.removeSuffix(".jpg").all { it == 'ä' })
    }

    @Test
    fun `the name limit leaves room for a pending prefix and a uniqueness suffix`() {
        val result = name("a".repeat(400) + ".jpeg")!!

        assertEquals(200, MAX_DOWNLOAD_NAME_BYTES)
        // MediaProvider adds ".pending-<expiry>-" in front and " (n)" before the extension.
        val withProviderAdditions = ".pending-1700000000-" + result.removeSuffix(".jpeg") + " (99).jpeg"
        assertTrue(withProviderAdditions.toByteArray(Charsets.UTF_8).size <= 255)
    }

    @Test
    fun `a blank or dot stem gets a generated name`() {
        assertEquals("photo_1234_${expectedHex()}.jpg", name(".jpg"))
        assertEquals("photo_1234_${expectedHex()}.jpg", name("%20%20.jpg"))
    }

    private fun expectedHex() =
        Random(1)
            .nextInt()
            .toUInt()
            .toString(16)
            .padStart(8, '0')

    @Test
    fun `unsupported types have no name`() {
        listOf("drawing.svg", "scan.tiff", "clip.mp4", "noextension", "a.jpg.exe", "x.").forEach {
            assertNull(it, name(it))
        }
        assertNull(downloadImageTypeOf("drawing.svg"))
        assertNull(downloadImageTypeOf("clip.mp4"))
        assertNull(downloadImageTypeOf("noextension"))
    }

    @Test
    fun `extensions are case insensitive and map to the allow list`() {
        assertEquals(DownloadImageType.JPEG, downloadImageTypeOf("A.JPG"))
        assertEquals(DownloadImageType.JPEG, downloadImageTypeOf("a.jpeg"))
        assertEquals(DownloadImageType.HEIC, downloadImageTypeOf("a.HEIC"))
        assertEquals(DownloadImageType.HEIF, downloadImageTypeOf("a.heif"))
        assertEquals("image/x-adobe-dng", downloadImageTypeOf("a.dng")?.mimeType)
        assertEquals("a.jpg", name("a.JPG"))
        listOf("png", "gif", "webp", "avif", "bmp").forEach { assertNotNull(downloadImageTypeOf("a.$it")) }
    }

    @Test
    fun `album folder names are one safe segment`() {
        assertEquals("Summer 2026", albumFolderName("/dav/Photos/Summer%202026/"))
        assertEquals("hidden", albumFolderName("/dav/Photos/.hidden/"))
        assertEquals("Album", albumFolderName("/dav/Photos/../"))
        assertEquals("Album", albumFolderName("/"))
        assertEquals("Album", albumFolderName("/dav/%20%20/"))
        assertEquals("a_b_c", albumFolderName("/dav/a%3Ab%2Ac/"))
        assertFalse(albumFolderName("/dav/x%E2%80%AEy/").contains('‮'))
    }

    @Test
    fun `an album folder name is at most 100 bytes`() {
        val result = albumFolderName("/dav/" + "%C3%A4".repeat(200) + "/")
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MAX_ALBUM_FOLDER_BYTES)
    }
}
