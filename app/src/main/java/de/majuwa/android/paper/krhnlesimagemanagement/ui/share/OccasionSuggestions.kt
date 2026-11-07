package de.majuwa.android.paper.krhnlesimagemanagement.ui.share

import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.util.exceedsAlbumNameLimit
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName
import java.util.Locale

/** Most suggestions the occasion dialog shows at once. */
const val MAX_OCCASION_SUGGESTIONS = 5

private fun String.suggestionKey(): String = lowercase(Locale.ROOT)

/**
 * Recent occasion folder names from the upload history, newest first and deduplicated
 * case-insensitively (the newest spelling wins). Only names usable as one occasion folder are
 * kept: this also drops auto-date labels (`2026/05/14`, always containing `/`), blank labels and
 * over-long names without a kind field in the history.
 */
fun recentOccasionNames(entries: List<UploadHistoryEntry>): List<String> =
    entries
        .sortedWith(compareByDescending<UploadHistoryEntry> { it.timestampMillis }.thenByDescending { it.id })
        .map { it.occasionName.trim() }
        .filter { isValidOccasionName(it) && !exceedsAlbumNameLimit(it) }
        .distinctBy { it.suggestionKey() }

/**
 * Merges [recent] names with the [remote] folder names: recent names keep their rank; a recent name
 * is replaced by the server's spelling only if the server has no exact match; remote names not yet
 * present (case-insensitively) are appended in alphabetical order.
 */
fun mergeOccasionCandidates(
    recent: List<String>,
    remote: List<String>,
): List<String> {
    val sortedRemote = remote.sortedWith(String.CASE_INSENSITIVE_ORDER.thenComparator { a, b -> a.compareTo(b) })
    val remoteSet = sortedRemote.toSet()
    val firstByKey = LinkedHashMap<String, String>()
    sortedRemote.forEach { firstByKey.putIfAbsent(it.suggestionKey(), it) }
    val result = ArrayList<String>()
    val seen = HashSet<String>()
    recent.forEach { name ->
        val chosen = if (name in remoteSet) name else firstByKey[name.suggestionKey()] ?: name
        if (seen.add(chosen.suggestionKey())) result += chosen
    }
    firstByKey.forEach { (key, name) -> if (seen.add(key)) result += name }
    return result
}

/**
 * The suggestions shown for the typed [query]: all candidates for a blank query, otherwise those
 * containing it case-insensitively (prefix matches first). A candidate equal to the typed text is
 * hidden; a case-only variant is shown. At most [limit].
 */
fun filterOccasionSuggestions(
    candidates: List<String>,
    query: String,
    limit: Int = MAX_OCCASION_SUGGESTIONS,
): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return candidates.take(limit)
    val key = q.suggestionKey()
    val matches = candidates.filter { it.suggestionKey().contains(key) && it != q }
    val (prefix, rest) = matches.partition { it.suggestionKey().startsWith(key) }
    return (prefix + rest).take(limit)
}
