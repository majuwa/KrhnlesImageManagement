package de.majuwa.android.paper.krhnlesimagemanagement.download

/** Number of leading bytes [matchesSignature] needs; read before anything is stored. */
internal const val SIGNATURE_BYTES = 32

private const val FTYP_OFFSET = 4
private const val BRAND_OFFSET = 8
private const val BRAND_LENGTH = 4
private const val FTYP_MIN_LENGTH = BRAND_OFFSET + BRAND_LENGTH
private const val RIFF_WEBP_OFFSET = 8
private const val RIFF_MIN_LENGTH = 12

private val heifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")
private val avifBrands = setOf("avif", "avis", "mif1", "msf1")

private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val jpegSignature = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

/**
 * True if the first [length] bytes of [head] are a plausible start of a file of this type: JPEG
 * `FF D8 FF`, PNG signature, `GIF87a` / `GIF89a`, `RIFF....WEBP`, an ISO-BMFF `ftyp` box with a
 * HEIC / HEIF / AVIF major brand, `BM`, or a TIFF header (DNG). Only a veto against content that
 * is not the announced image (HTML, SVG, an archive, text); it does not validate the file.
 */
internal fun DownloadImageType.matchesSignature(
    head: ByteArray,
    length: Int,
): Boolean {
    val bytes = head.copyOf(length.coerceIn(0, head.size))
    return when (this) {
        DownloadImageType.JPEG -> {
            bytes.startsWith(jpegSignature)
        }

        DownloadImageType.PNG -> {
            bytes.startsWith(pngSignature)
        }

        DownloadImageType.GIF -> {
            bytes.startsWith("GIF87a".toByteArray()) || bytes.startsWith("GIF89a".toByteArray())
        }

        DownloadImageType.WEBP -> {
            bytes.size >= RIFF_MIN_LENGTH &&
                bytes.startsWith("RIFF".toByteArray()) &&
                bytes.sliceArray(RIFF_WEBP_OFFSET until RIFF_MIN_LENGTH).contentEquals("WEBP".toByteArray())
        }

        DownloadImageType.HEIC, DownloadImageType.HEIF -> {
            bytes.hasFtypBrandIn(heifBrands)
        }

        DownloadImageType.AVIF -> {
            bytes.hasFtypBrandIn(avifBrands)
        }

        DownloadImageType.BMP -> {
            bytes.startsWith("BM".toByteArray())
        }

        DownloadImageType.DNG -> {
            bytes.startsWith(byteArrayOf(0x49, 0x49, 0x2A, 0x00)) ||
                bytes.startsWith(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A))
        }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.hasFtypBrandIn(brands: Set<String>): Boolean =
    size >= FTYP_MIN_LENGTH &&
        String(this, FTYP_OFFSET, BRAND_LENGTH, Charsets.ISO_8859_1) == "ftyp" &&
        String(this, BRAND_OFFSET, BRAND_LENGTH, Charsets.ISO_8859_1) in brands
