package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.OperationCanceledException
import android.provider.MediaStore
import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.download.DOWNLOAD_RELATIVE_ROOT
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.mediaItemId
import de.majuwa.android.paper.krhnlesimagemanagement.util.abandonableCall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Most rows scanned per media kind; above it the count is shown as a lower bound. */
internal const val MAX_REMINDER_SCAN_ROWS = 50_000

/** The whole query of one media kind is abandoned after this time. */
internal const val REMINDER_QUERY_TIMEOUT_MS = 30_000L

private const val TAG = "NewMediaQuery"

/** What a [NewMediaSource] found. */
internal sealed interface NewMediaQueryResult {
    /** [capReached]: at least one kind had more rows than [MAX_REMINDER_SCAN_ROWS]. */
    data class Rows(
        val rows: List<NewMediaRow>,
        val capReached: Boolean,
    ) : NewMediaQueryResult

    /** A kind did not answer within the time limit. */
    data object TimedOut : NewMediaQueryResult
}

/** The media index as the reminder worker sees it. */
internal fun interface NewMediaSource {
    suspend fun query(
        kinds: Set<MediaKind>,
        baselineMillis: Long,
    ): NewMediaQueryResult
}

/**
 * Reads only ids and `DATE_ADDED` of recent rows (no names, sizes, paths or file contents), at most
 * [MAX_REMINDER_SCAN_ROWS] per kind, each kind through [abandonableCall] so a provider that never answers
 * cannot hold the worker. Photos this app saved are excluded by owner and by [DOWNLOAD_RELATIVE_ROOT].
 */
internal class NewMediaQuery(
    private val contentResolver: ContentResolver,
    private val ownPackage: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = REMINDER_QUERY_TIMEOUT_MS,
    private val rowCap: Int = MAX_REMINDER_SCAN_ROWS,
) : NewMediaSource {
    private sealed interface KindResult {
        data class Done(
            val rows: List<NewMediaRow>,
            val capReached: Boolean,
        ) : KindResult

        data object Timeout : KindResult
    }

    override suspend fun query(
        kinds: Set<MediaKind>,
        baselineMillis: Long,
    ): NewMediaQueryResult {
        val rows = mutableListOf<NewMediaRow>()
        var capReached = false
        for (kind in kinds) {
            val result =
                abandonableCall(ioDispatcher, timeoutMillis) { signal ->
                    runKind(kind, baselineMillis, signal)
                } ?: return NewMediaQueryResult.TimedOut
            when (result) {
                is KindResult.Timeout -> {
                    return NewMediaQueryResult.TimedOut
                }

                is KindResult.Done -> {
                    rows += result.rows
                    capReached = capReached || result.capReached
                }
            }
        }
        return NewMediaQueryResult.Rows(rows, capReached)
    }

    private fun runKind(
        kind: MediaKind,
        baselineMillis: Long,
        signal: android.os.CancellationSignal,
    ): KindResult =
        try {
            readRows(kind, baselineMillis, signal)
        } catch (e: OperationCanceledException) {
            // A cancelled signal is not a coroutine cancellation: it is the timeout.
            Log.w(TAG, "Reminder query cancelled: ${e.javaClass.simpleName}")
            KindResult.Timeout
        } catch (e: SecurityException) {
            Log.w(TAG, "Reminder query not permitted: ${e.javaClass.simpleName}")
            KindResult.Done(emptyList(), false)
        }

    private fun readRows(
        kind: MediaKind,
        baselineMillis: Long,
        signal: android.os.CancellationSignal,
    ): KindResult {
        val collection = collectionOf(kind)
        val selection = reminderSelection(secondsOf(baselineMillis), ownPackage, listOf(DOWNLOAD_RELATIVE_ROOT))
        val args =
            Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selection.args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, rowCap + 1)
            }
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        val rows = mutableListOf<NewMediaRow>()
        var seen = 0
        contentResolver.query(collection, projection, args, signal)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (seen <= rowCap && cursor.moveToNext()) {
                seen++
                // The row beyond the cap only proves that the cap was reached.
                if (seen <= rowCap) rowOf(cursor, id, added, kind)?.let(rows::add)
            }
        }
        return KindResult.Done(rows, seen > rowCap)
    }

    private fun rowOf(
        cursor: android.database.Cursor,
        idColumn: Int,
        addedColumn: Int,
        kind: MediaKind,
    ): NewMediaRow? =
        if (cursor.isNull(addedColumn)) {
            null
        } else {
            mediaItemId(cursor.getLong(idColumn), kind)?.let { NewMediaRow(it, cursor.getLong(addedColumn)) }
        }

    private fun collectionOf(kind: MediaKind): Uri =
        when (kind) {
            MediaKind.Image -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaKind.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
}
