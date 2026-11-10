package de.majuwa.android.paper.krhnlesimagemanagement.ui.share

import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.util.exceedsAlbumNameLimit
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName
import java.util.Locale

/** Most suggestions the occasion dialog shows at once. */
const val MAX_OCCASION_SUGGESTIONS = 5

/** Most server folder names considered as suggestions (after ordering). */
const val MAX_REMOTE_OCCASION_NAMES = 1_000

internal fun String.suggestionKey(): String = lowercase(Locale.ROOT)

/** A four-digit name is an auto-date year folder (`YYYY/MM/DD`), never a suggested occasion. */
private val yearFolderName = Regex("[0-9]{4}")

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
 * present (case-insensitively) are appended in alphabetical order. Remote names that are four
 * digits (auto-date year folders) are not suggested; at most [MAX_REMOTE_OCCASION_NAMES] remote
 * names are considered (after ordering).
 */
fun mergeOccasionCandidates(
    recent: List<String>,
    remote: List<String>,
): List<String> {
    val sortedRemote =
        remote
            .filterNot { yearFolderName.matches(it) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER.thenComparator { a, b -> a.compareTo(b) })
            .take(MAX_REMOTE_OCCASION_NAMES)
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
 * hidden; a case-only variant is shown. At most [limit]. [keys] are the precomputed
 * [suggestionKey]s of [candidates] (same order), so typing does not lowercase every candidate again.
 */
fun filterOccasionSuggestions(
    candidates: List<String>,
    query: String,
    limit: Int = MAX_OCCASION_SUGGESTIONS,
    keys: List<String> = candidates.map { it.suggestionKey() },
): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return candidates.take(limit)
    val key = q.suggestionKey()
    val matches = candidates.indices.filter { keys[it].contains(key) && candidates[it] != q }
    val (prefix, rest) = matches.partition { keys[it].startsWith(key) }
    return (prefix + rest).take(limit).map { candidates[it] }
}
