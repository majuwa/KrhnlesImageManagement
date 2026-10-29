package de.majuwa.android.paper.krhnlesimagemanagement.util

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/** Size in bytes and modification time (seconds) of one MediaStore item, as the MediaStore reports them. */
data class MediaFileState(
    val size: Long,
    val dateModified: Long,
)

private val MEDIA_FILE_STATE_PROJECTION =
    arrayOf(
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.IS_TRASHED,
        MediaStore.MediaColumns.IS_PENDING,
    )

private const val COLUMN_SIZE = 0
private const val COLUMN_DATE_MODIFIED = 1
private const val COLUMN_IS_TRASHED = 2
private const val COLUMN_IS_PENDING = 3

/**
 * `SIZE` and `DATE_MODIFIED` of the MediaStore item [uri]. A query on an item uri returns trashed
 * and pending rows whatever the query arguments say, so `IS_TRASHED` / `IS_PENDING` are projected
 * and a row with either set (or null) counts as missing. Null when the item is missing, trashed or
 * pending, a column is null or negative, or the query fails for any reason: callers treat that as
 * "not provably the same file".
 */
fun ContentResolver.mediaFileStateOf(uri: Uri): MediaFileState? =
    try {
        query(uri, MEDIA_FILE_STATE_PROJECTION, null, null, null)?.use { it.toMediaFileState() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Fail closed for every provider failure (SecurityException, SQLiteException, a crashed provider…).
        Log.w("MediaFileState", "MediaStore query failed: ${e.javaClass.simpleName}")
        null
    }

private fun Cursor.toMediaFileState(): MediaFileState? {
    val complete = moveToFirst() && MEDIA_FILE_STATE_PROJECTION.indices.none(::isNull)
    if (!complete || getInt(COLUMN_IS_TRASHED) != 0 || getInt(COLUMN_IS_PENDING) != 0) return null
    return MediaFileState(getLong(COLUMN_SIZE), getLong(COLUMN_DATE_MODIFIED))
        .takeIf { it.size >= 0L && it.dateModified >= 0L }
}
