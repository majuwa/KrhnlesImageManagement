package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
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

        @Volatile
        var imageRows: List<Map<String, Any?>> = emptyList()

        @Volatile
        var videoRows: List<Map<String, Any?>> = emptyList()

        @Volatile
        var videoFailure: RuntimeException? = null

        fun reset() {
            imageRows = emptyList()
            videoRows = emptyList()
            videoFailure = null
        }

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
