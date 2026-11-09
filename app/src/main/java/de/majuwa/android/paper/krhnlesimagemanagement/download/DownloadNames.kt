package de.majuwa.android.paper.krhnlesimagemanagement.download

import de.majuwa.android.paper.krhnlesimagemanagement.data.percentDecodedOrNull
import de.majuwa.android.paper.krhnlesimagemanagement.util.SharedImageType
import java.util.Locale
import kotlin.random.Random

/** Folder below `Pictures/` that holds every saved album folder. Not localized on purpose. */
internal const val DOWNLOAD_ROOT_FOLDER = "KroehnSync"

/** `RELATIVE_PATH` prefix of everything saved to the device; the new-photos reminder never counts it. */
internal const val DOWNLOAD_RELATIVE_ROOT = "Pictures/$DOWNLOAD_ROOT_FOLDER/"

/**
 * Longest saved file name, in UTF-8 bytes. Below the usual 255-byte file-system limit on purpose:
 * MediaProvider adds ` (n)` for a taken name and `.pending-<expiry>-` while an item is pending.
 */
internal const val MAX_DOWNLOAD_NAME_BYTES = 200

/** Longest album folder name, in UTF-8 bytes. */
internal const val MAX_ALBUM_FOLDER_BYTES = 100

private const val FALLBACK_ALBUM = "Album"
private const val RANDOM_HEX_LENGTH = 8
private const val HEX_RADIX = 16
private const val REPLACEMENT = "_"
private val reservedChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * The raster image types a download may save, built from the share target's allow-list
 * ([SharedImageType]). The file-name extension decides the type; the response `Content-Type` only
 * vetoes. Videos, SVG, TIFF and everything else have no type.
 */
internal enum class DownloadImageType(
    val shared: SharedImageType,
    val extensions: List<String>,
) {
    JPEG(SharedImageType.JPEG, SharedImageType.JPEG.extensions),
    PNG(SharedImageType.PNG, SharedImageType.PNG.extensions),
    GIF(SharedImageType.GIF, SharedImageType.GIF.extensions),
    WEBP(SharedImageType.WEBP, SharedImageType.WEBP.extensions),

    // HEIC / HEIF list each other as aliases in the share allow-list; here each extension has one owner.
    HEIC(SharedImageType.HEIC, listOf("heic")),
    HEIF(SharedImageType.HEIF, listOf("heif")),
    AVIF(SharedImageType.AVIF, SharedImageType.AVIF.extensions),
    BMP(SharedImageType.BMP, SharedImageType.BMP.extensions),
    DNG(SharedImageType.DNG, SharedImageType.DNG.extensions),
    ;

    val mimeType: String get() = shared.mimeType

    companion object {
        private val byExtension: Map<String, DownloadImageType> =
            entries.flatMap { type -> type.extensions.map { it to type } }.toMap()

        fun ofExtension(extension: String): DownloadImageType? = byExtension[extension.lowercase(Locale.ROOT)]
    }
}

/** The type of a file called [name] by its extension (case-insensitive), or `null` if it is not allow-listed. */
internal fun downloadImageTypeOf(name: String): DownloadImageType? =
    name
        .substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.let(DownloadImageType::ofExtension)

/**
 * The display name a photo with the server path [href] is saved under, or `null` if its extension
 * is not an allow-listed image type (nothing is saved then). It comes from the percent-decoded last
 * path segment: path separators, reserved characters, control, format (bidi), line / paragraph /
 * space separator characters and unpaired surrogates are replaced, spaces and dots are trimmed at
 * both ends (a leading dot would hide the photo from the gallery), and the stem is cut so that the
 * whole name has at most [MAX_DOWNLOAD_NAME_BYTES] UTF-8 bytes with the extension kept. Nothing
 * usable left gives `photo_<now>_<8 hex>.<ext>`.
 */
internal fun downloadDisplayName(
    href: String,
    now: Long,
    random: Random = Random.Default,
): String? {
    val decoded = lastSegmentOf(href)?.percentDecodedOrNull().orEmpty()
    if (downloadImageTypeOf(decoded) == null) return null
    val rawExtension = decoded.substringAfterLast('.')
    val extension = rawExtension.lowercase(Locale.ROOT)
    val maxStemBytes = MAX_DOWNLOAD_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size - 1
    val stem =
        sanitizeSegment(decoded.dropLast(rawExtension.length + 1))
            .trimSpacesAndDots()
            .truncatedToBytes(maxStemBytes)
            .trimSpacesAndDots()
    if (stem.isEmpty()) {
        val hex =
            random
                .nextInt()
                .toUInt()
                .toString(HEX_RADIX)
                .padStart(RANDOM_HEX_LENGTH, '0')
        return "photo_${now}_$hex.$extension"
    }
    return "$stem.$extension"
}

/**
 * The folder name of the album at [albumHref] below `Pictures/KroehnSync/`: its last path segment,
 * sanitized like a file name to exactly one segment of at most [MAX_ALBUM_FOLDER_BYTES] UTF-8
 * bytes, never starting with a dot; `Album` when nothing usable remains.
 */
internal fun albumFolderName(albumHref: String): String =
    lastSegmentOf(albumHref)
        ?.percentDecodedOrNull()
        ?.let(::sanitizeSegment)
        ?.trimSpacesAndDots()
        ?.truncatedToBytes(MAX_ALBUM_FOLDER_BYTES)
        ?.trimSpacesAndDots()
        ?.takeIf { it.isNotEmpty() }
        ?: FALLBACK_ALBUM

/** The last non-empty `/`-separated piece of the raw [href]. */
private fun lastSegmentOf(href: String): String? = href.split('/').lastOrNull { it.isNotEmpty() }

/** Replaces every character that is unsafe in a single path segment (see [downloadDisplayName]). */
internal fun sanitizeSegment(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        val width = Character.charCount(codePoint)
        val unpairedSurrogate = width == 1 && Character.isSurrogate(text[i])
        out.append(if (isUnsafe(codePoint) || unpairedSurrogate) REPLACEMENT else text.substring(i, i + width))
        i += width
    }
    return out.toString()
}

private fun isUnsafe(codePoint: Int): Boolean =
    (codePoint <= Character.MAX_VALUE.code && codePoint.toChar() in reservedChars) ||
        Character.isISOControl(codePoint) ||
        when (Character.getType(codePoint).toByte()) {
            Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            Character.SPACE_SEPARATOR -> codePoint != ' '.code
            else -> false
        }

private fun String.trimSpacesAndDots(): String = trim { it == ' ' || it == '.' }

/** At most [maxBytes] UTF-8 bytes, cut between code points. */
private fun String.truncatedToBytes(maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < length) {
        val codePoint = codePointAt(end)
        val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    return substring(0, end)
}
