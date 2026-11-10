package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.provider.MediaStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.MediaAccess

private const val MILLIS_PER_SECOND = 1000L

/** One media row the reminder query returned: the collision-free media id and `DATE_ADDED` in seconds. */
internal data class NewMediaRow(
    val id: Long,
    val dateAddedSeconds: Long,
)

/**
 * The outcome of counting. [isLowerBound] is set when the row cap was reached (the real count may be
 * higher); [hasNewSinceLastReminder] is false when every counted item was already there at the last reminder.
 */
internal data class NewMediaCount(
    val total: Int,
    val hasNewSinceLastReminder: Boolean,
    val isLowerBound: Boolean,
)

/**
 * The media kinds the app can read right now: images with image access, videos with video access, both
 * with partial (selected-photos) access, which makes MediaStore return only the selected items.
 * `ACCESS_MEDIA_LOCATION` is no access permission and never changes the result.
 */
internal fun queryableKinds(access: MediaAccess): Set<MediaKind> =
    buildSet {
        if (access.images || access.userSelected) add(MediaKind.Image)
        if (access.video || access.userSelected) add(MediaKind.Video)
    }

/** A `QUERY_ARG_SQL_SELECTION` with its `QUERY_ARG_SQL_SELECTION_ARGS`. */
internal data class ReminderSelection(
    val selection: String,
    val args: Array<String>,
) {
    override fun equals(other: Any?): Boolean =
        other is ReminderSelection && selection == other.selection && args.contentEquals(other.args)

    override fun hashCode(): Int = 31 * selection.hashCode() + args.contentHashCode()
}

/**
 * The selection of the reminder query. The `IS NULL` branches are required: in SQL `NULL != x` and
 * `NULL NOT LIKE x` are NULL, which would silently drop every row without an owner or a path. Pending and
 * trashed rows are excluded explicitly (MediaStore's default does the same; spelled out so the rule is
 * visible and testable). Arguments: baseline, own package, then one `<prefix>%` per excluded prefix.
 */
internal fun reminderSelection(
    baselineSeconds: Long,
    ownPackage: String,
    excludedPathPrefixes: List<String>,
): ReminderSelection {
    val clauses =
        buildList {
            add("${MediaStore.MediaColumns.DATE_ADDED} > ?")
            add("${MediaStore.MediaColumns.IS_PENDING} = 0")
            add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
            add(
                "(${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL OR " +
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} != ?)",
            )
            excludedPathPrefixes.forEach {
                add(
                    "(${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?)",
                )
            }
        }
    val args = listOf(baselineSeconds.toString(), ownPackage) + excludedPathPrefixes.map { "$it%" }
    return ReminderSelection(clauses.joinToString(" AND "), args.toTypedArray())
}

/** Whole seconds of [millis] (floor), the resolution of `DATE_ADDED`. */
internal fun secondsOf(millis: Long): Long = Math.floorDiv(millis, MILLIS_PER_SECOND)

/**
 * Counts the [rows] added after [baselineMillis] whose id is not in [uploadedIds]. `DATE_ADDED` has second
 * resolution, so "after" means a later whole second than the baseline's. A [lastShownMillis] of 0 means
 * no reminder yet.
 */
internal fun countNewMedia(
    rows: List<NewMediaRow>,
    uploadedIds: Set<Long>,
    baselineMillis: Long,
    lastShownMillis: Long,
    rowCapReached: Boolean,
): NewMediaCount {
    val baselineSeconds = secondsOf(baselineMillis)
    val counted = rows.filter { it.dateAddedSeconds > baselineSeconds && it.id !in uploadedIds }
    val shownSeconds = secondsOf(lastShownMillis)
    return NewMediaCount(
        total = counted.size,
        hasNewSinceLastReminder = lastShownMillis <= 0L || counted.any { it.dateAddedSeconds > shownSeconds },
        isLowerBound = rowCapReached,
    )
}

/**
 * The later of [sinceMillis] (when the reminder was turned on) and the newest upload run that uploaded at
 * least one file (`photoCount > failedCount`, a cancelled run included). A failed-only run never moves it.
 */
internal fun reminderBaseline(
    sinceMillis: Long,
    history: List<UploadHistoryEntry>,
): Long {
    val lastUpload = history.filter { it.photoCount > it.failedCount }.maxOfOrNull { it.timestampMillis }
    return maxOf(sinceMillis, lastUpload ?: sinceMillis)
}
