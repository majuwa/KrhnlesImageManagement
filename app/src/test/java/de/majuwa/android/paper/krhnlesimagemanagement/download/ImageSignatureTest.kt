package de.majuwa.android.paper.krhnlesimagemanagement.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSignatureTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun DownloadImageType.accepts(head: ByteArray) = matchesSignature(head.copyOf(SIGNATURE_BYTES), head.size)

    private fun ftyp(brand: String) = bytes(0, 0, 0, 0x18) + ascii("ftyp$brand") + bytes(0, 0, 0, 0)

    @Test
    fun `every allow listed type accepts its own signature`() {
        assertTrue(DownloadImageType.JPEG.accepts(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00)))
        assertTrue(DownloadImageType.PNG.accepts(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)))
        assertTrue(DownloadImageType.GIF.accepts(ascii("GIF87a")))
        assertTrue(DownloadImageType.GIF.accepts(ascii("GIF89a....")))
        assertTrue(DownloadImageType.WEBP.accepts(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WEBPVP8 ")))
        assertTrue(DownloadImageType.HEIC.accepts(ftyp("heic")))
        assertTrue(DownloadImageType.HEIF.accepts(ftyp("mif1")))
        assertTrue(DownloadImageType.AVIF.accepts(ftyp("avif")))
        assertTrue(DownloadImageType.BMP.accepts(ascii("BM") + bytes(1, 2)))
        assertTrue(DownloadImageType.DNG.accepts(bytes(0x49, 0x49, 0x2A, 0x00, 8)))
        assertTrue(DownloadImageType.DNG.accepts(bytes(0x4D, 0x4D, 0x00, 0x2A, 0)))
    }

    @Test
    fun `html svg text and an empty body are never an image`() {
        val notImages =
            listOf(ascii("<html>login</html>"), ascii("<?xml version=\"1.0\"?><svg"), ascii("hello"), ByteArray(0))

        DownloadImageType.entries.forEach { type ->
            notImages.forEach { assertFalse("$type ${String(it)}", type.accepts(it)) }
        }
    }

    @Test
    fun `a signature of another type is refused`() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        assertFalse(DownloadImageType.JPEG.accepts(png))
        assertFalse(DownloadImageType.PNG.accepts(bytes(0xFF, 0xD8, 0xFF)))
        assertFalse(DownloadImageType.HEIC.accepts(ftyp("avif")))
        assertFalse(DownloadImageType.AVIF.accepts(ftyp("heic")))
        assertFalse(DownloadImageType.WEBP.accepts(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WAVE")))
    }

    @Test
    fun `a body shorter than its signature is refused`() {
        assertFalse(DownloadImageType.JPEG.accepts(bytes(0xFF, 0xD8)))
        assertFalse(DownloadImageType.PNG.accepts(bytes(0x89, 0x50)))
        assertFalse(DownloadImageType.WEBP.accepts(ascii("RIFF")))
        assertFalse(DownloadImageType.HEIC.accepts(ascii("ftyp")))
    }

    @Test
    fun `only the bytes actually read count`() {
        // The buffer holds a JPEG start, but nothing was read into it.
        assertFalse(DownloadImageType.JPEG.matchesSignature(bytes(0xFF, 0xD8, 0xFF), 0))
    }
}
