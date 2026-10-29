package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore

/**
 * A `media` authority provider for Robolectric tests of `MediaRepository`: one [MatrixCursor] per
 * collection (Images / Video), built from [imageRows] / [videoRows] with the requested projection.
 * [videoFailure] is thrown by the video query (e.g. a [SecurityException] without video access).
 */
class FakeMediaStoreProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        itemRows[uri.toString()]?.let { row -> return itemCursor(uri, projection, row) }
        val rows =
            when (uri) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI -> {
                    imageRows
                }

                MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> {
                    videoFailure?.let { throw it }
                    videoRows
                }

                else -> {
                    return null
                }
            }
        val columns = projection ?: ALL_COLUMNS
        return MatrixCursor(columns).apply {
            // A real provider sorts by DATE_TAKEN DESC; the fake keeps the given order.
            rows.forEach { row -> addRow(columns.map { row[it] }.toTypedArray()) }
        }
    }

    /**
     * Single-item query with query args (the production trash confirmation passes
     * `QUERY_ARG_MATCH_TRASHED`). Like the platform, a query on an item uri returns trashed and
     * pending rows whatever the query args say, so callers must read `IS_TRASHED` / `IS_PENDING`.
     */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? {
        val row = itemRows[uri.toString()] ?: return super.query(uri, projection, queryArgs, cancellationSignal)
        return itemCursor(uri, projection, row)
    }

    private fun itemCursor(
        uri: Uri,
        projection: Array<String>?,
        row: Map<String, Any?>,
    ): Cursor {
        itemQueries.merge(uri.toString(), 1, Int::plus)
        itemFailures[uri.toString()]?.let { throw it }
        val columns = projection ?: arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        return MatrixCursor(columns).apply { addRow(columns.map { row[it] }.toTypedArray()) }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ) = 0

    companion object {
        const val AUTHORITY = MediaStore.AUTHORITY

        private val ALL_COLUMNS =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.Video.VideoColumns.DURATION,
            )

        /** MediaStore item rows by uri string (`content://media/external/images/media/<id>`). */
        val itemRows = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()

        @Volatile
        var imageRows: List<Map<String, Any?>> = emptyList()

        @Volatile
        var videoRows: List<Map<String, Any?>> = emptyList()

        @Volatile
        var videoFailure: RuntimeException? = null

        /** Thrown by the item query of a uri (e.g. a crashed provider for one item). */
        val itemFailures = java.util.concurrent.ConcurrentHashMap<String, RuntimeException>()

        /** Number of item queries per uri string. */
        val itemQueries = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun reset() {
            itemRows.clear()
            itemFailures.clear()
            itemQueries.clear()
            imageRows = emptyList()
            videoRows = emptyList()
            videoFailure = null
        }

        /** The per-item row for [itemRows]: the values the upload / removal checks read. */
        fun item(
            size: Long,
            dateModified: Long,
            trashed: Boolean = false,
            pending: Boolean = false,
        ): Map<String, Any?> =
            mapOf(
                MediaStore.MediaColumns.SIZE to size,
                MediaStore.MediaColumns.DATE_MODIFIED to dateModified,
                MediaStore.MediaColumns.IS_TRASHED to if (trashed) 1 else 0,
                MediaStore.MediaColumns.IS_PENDING to if (pending) 1 else 0,
            )

        /** One cursor row; null values stay SQL NULL. */
        fun row(
            id: Long,
            name: String,
            dateTaken: Long?,
            mimeType: String?,
            size: Long = 100L,
            durationMs: Long? = null,
        ): Map<String, Any?> =
            mapOf(
                MediaStore.MediaColumns._ID to id,
                MediaStore.MediaColumns.DISPLAY_NAME to name,
                MediaStore.MediaColumns.DATE_TAKEN to dateTaken,
                MediaStore.MediaColumns.SIZE to size,
                MediaStore.MediaColumns.MIME_TYPE to mimeType,
                MediaStore.Video.VideoColumns.DURATION to durationMs,
            )
    }
}
