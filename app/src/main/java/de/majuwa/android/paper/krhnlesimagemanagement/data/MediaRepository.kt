package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MediaRepository(
    private val contentResolver: ContentResolver,
) : MediaRepositoryContract {
    override suspend fun loadPhotos(): List<Photo> =
        withContext(Dispatchers.IO) {
            val photos = mutableListOf<Photo>()
            val projection =
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.MIME_TYPE,
                )
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            contentResolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val uri =
                            ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id,
                            )
                        val name = cursor.getString(nameColumn) ?: "unknown"
                        val dateMillis = if (cursor.isNull(dateColumn)) null else cursor.getLong(dateColumn)
                        val date = dateTakenOf(dateMillis)
                        val size = cursor.getLong(sizeColumn)
                        val mimeType = cursor.getString(mimeColumn) ?: "image/*"

                        photos.add(
                            Photo(
                                id = id,
                                uri = uri,
                                displayName = name,
                                dateTaken = date,
                                size = size,
                                mimeType = mimeType,
                            ),
                        )
                    }
                }
            photos
        }

    internal companion object {
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
