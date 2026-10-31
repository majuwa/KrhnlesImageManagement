package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.mediaItemId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MediaRepository(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaRepositoryContract {
    /**
     * Loads the device's images and videos, merged and ordered by raw `DATE_TAKEN`, newest first
     * (stable, so equal dates keep images before videos). Without video access (a
     * [SecurityException] from the video query) only the images are returned.
     */
    override suspend fun loadPhotos(): List<Photo> =
        withContext(ioDispatcher) {
            val images = queryMedia(MediaKind.Image)
            val videos =
                try {
                    queryMedia(MediaKind.Video)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Video query not permitted: ${e.javaClass.simpleName}")
                    emptyList()
                }
            (images + videos)
                .sortedByDescending { it.rawDateTaken ?: 0L }
                .map { it.photo }
        }

    private class Row(
        val photo: Photo,
        val rawDateTaken: Long?,
    )

    private fun queryMedia(kind: MediaKind): List<Row> {
        val collection =
            when (kind) {
                MediaKind.Image -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaKind.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        val projection =
            buildList {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.DATE_TAKEN)
                add(MediaStore.MediaColumns.SIZE)
                add(MediaStore.MediaColumns.MIME_TYPE)
                if (kind == MediaKind.Video) add(MediaStore.Video.VideoColumns.DURATION)
            }.toTypedArray()
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
        val rows = mutableListOf<Row>()
        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val columns = Columns(cursor, kind)
            while (cursor.moveToNext()) {
                cursor.toRow(columns, kind, collection)?.let(rows::add)
            }
        }
        return rows
    }

    private class Columns(
        cursor: Cursor,
        kind: MediaKind,
    ) {
        val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val date = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val duration =
            if (kind == MediaKind.Video) cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) else -1
    }

    /** The current row, or null if its raw id would collide with the video flag (never in practice). */
    private fun Cursor.toRow(
        columns: Columns,
        kind: MediaKind,
        collection: Uri,
    ): Row? {
        val rawId = getLong(columns.id)
        val id = mediaItemId(rawId, kind) ?: return null
        val dateMillis = longOrNull(columns.date)
        val mimeType =
            getString(columns.mime)?.takeIf { it.isNotBlank() }
                ?: if (kind == MediaKind.Video) FALLBACK_VIDEO_MIME else FALLBACK_IMAGE_MIME
        val photo =
            Photo(
                id = id,
                uri = ContentUris.withAppendedId(collection, rawId),
                displayName = getString(columns.name) ?: "unknown",
                dateTaken = dateTakenOf(dateMillis),
                size = getLong(columns.size),
                mimeType = mimeType,
                mediaKind = kind,
                durationMs = if (columns.duration >= 0) longOrNull(columns.duration) else null,
            )
        return Row(photo, dateMillis)
    }

    private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

    internal companion object {
        private const val TAG = "MediaRepository"
        private const val FALLBACK_IMAGE_MIME = "image/*"

        /** Content type of a video whose `MIME_TYPE` is null or blank. */
        const val FALLBACK_VIDEO_MIME = "video/*"

        /**
         * A missing (null) or 0 `DATE_TAKEN` has no capture date and maps to [Photo.UNKNOWN_DATE] (no
         * zone conversion). Negative values are real pre-1970 dates and keep their date. Other values
         * are converted to a date in [zone] (the device default when the photo list loads); this is
         * the capture day used for the grid's date header and the auto date folder.
         */
        fun dateTakenOf(
            dateMillis: Long?,
            zone: ZoneId = ZoneId.systemDefault(),
        ): LocalDate =
            if (dateMillis == null || dateMillis == 0L) {
                Photo.UNKNOWN_DATE
            } else {
                Instant
                    .ofEpochMilli(dateMillis)
                    .atZone(zone)
                    .toLocalDate()
            }
    }
}
