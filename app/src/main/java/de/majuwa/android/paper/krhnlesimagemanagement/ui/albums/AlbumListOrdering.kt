package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import de.majuwa.android.paper.krhnlesimagemanagement.model.AlbumSort
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Longest name prefix (UTF-16 units) that goes into a key: bounds the NFD cost of hostile combining-mark runs. */
internal const val MAX_KEY_NAME_LENGTH = 255

/** At most [MAX_KEY_NAME_LENGTH] chars, never ending in the middle of a surrogate pair. */
private fun String.boundedForKey(): String {
    if (length <= MAX_KEY_NAME_LENGTH) return this
    val end =
        if (this[MAX_KEY_NAME_LENGTH - 1].isHighSurrogate()) MAX_KEY_NAME_LENGTH - 1 else MAX_KEY_NAME_LENGTH
    return substring(0, end)
}

/**
 * Search / sort key of an album name, built from its first [MAX_KEY_NAME_LENGTH] chars only (a server
 * name may be hostile and NFD of long combining-mark runs is slow).
 *
 * Steps: lowercase, uppercase, lowercase (`Locale.ROOT`; approximates full case folding, `ß` and `ẞ`
 * become `ss`), then NFD with the combining marks removed (`é` -> `e`). Letters without a canonical
 * decomposition keep their form (`ł`, `ø`). Used for the album list only: occasion suggestions dedupe
 * real folder names and must keep "Cafe" and "Café" apart.
 */
internal fun albumNameKey(name: String): String =
    Normalizer
        .normalize(
            name
                .boundedForKey()
                .lowercase(Locale.ROOT)
                .uppercase(Locale.ROOT)
                .lowercase(Locale.ROOT),
            Normalizer.Form.NFD,
        ).replace(COMBINING_MARKS, "")

/** Albums paired with their precomputed [albumNameKey], built once per loaded list. */
internal class AlbumListIndex(
    albums: List<RemoteAlbum>,
) {
    internal class Entry(
        val album: RemoteAlbum,
        val key: String,
    )

    val entries: List<Entry> = albums.map { Entry(it, albumNameKey(it.displayName)) }
}

private val NAME_ASC_ORDER: Comparator<AlbumListIndex.Entry> =
    compareBy<AlbumListIndex.Entry> { it.key }
        .thenBy { it.album.displayName }
        .thenBy { it.album.href }

/** The indexed albums ordered by [sort]; total and independent of the input order. */
internal fun sortAlbums(
    index: AlbumListIndex,
    sort: AlbumSort,
): List<AlbumListIndex.Entry> =
    when (sort) {
        AlbumSort.NAME_ASC -> {
            index.entries.sortedWith(NAME_ASC_ORDER)
        }

        AlbumSort.NAME_DESC -> {
            index.entries.sortedWith(NAME_ASC_ORDER.reversed())
        }

        AlbumSort.MODIFIED_NEWEST, AlbumSort.MODIFIED_OLDEST -> {
            // A partition, not a reversed comparator: undated albums stay last in both directions.
            val (dated, undated) = index.entries.partition { it.album.lastModified != null }
            val byDate = compareBy<AlbumListIndex.Entry> { it.album.lastModified }
            val ordered =
                if (sort == AlbumSort.MODIFIED_NEWEST) byDate.reversed() else byDate
            dated.sortedWith(ordered.then(NAME_ASC_ORDER)) + undated.sortedWith(NAME_ASC_ORDER)
        }
    }

/** The entries of [sorted] whose name contains the trimmed [query] (folded); order is kept, blank keeps all. */
internal fun filterAlbums(
    sorted: List<AlbumListIndex.Entry>,
    query: String,
): List<AlbumListIndex.Entry> {
    val needle = albumNameKey(query.trim())
    return if (needle.isEmpty()) sorted else sorted.filter { it.key.contains(needle) }
}

/** The albums to show for [query] in the order of [sort]. */
internal fun visibleAlbums(
    index: AlbumListIndex,
    query: String,
    sort: AlbumSort,
): List<RemoteAlbum> = filterAlbums(sortAlbums(index, sort), query).map { it.album }
