package de.majuwa.android.paper.krhnlesimagemanagement.util

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import java.time.LocalDate

/**
 * Extracts shared image [Uri]s from an [Intent] with [Intent.ACTION_SEND] or
 * [Intent.ACTION_SEND_MULTIPLE] and converts them into [Photo] instances.
 *
 * Returns an empty list when the intent is not a share intent or carries no image URIs.
 */
fun parseSharedPhotos(
    intent: Intent,
    contentResolver: ContentResolver,
): List<Photo> {
    val uris: List<Uri> =
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) listOf(uri) else emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            }
            else -> emptyList()
        }

    val today = LocalDate.now()
    return uris.map { uri ->
        val meta = queryUriMetadata(contentResolver, uri)
        Photo(
            id = uri.hashCode().toLong(),
            uri = uri,
            displayName = meta.displayName,
            dateTaken = today,
            size = meta.size,
            mimeType = meta.mimeType ?: intent.type ?: "image/*",
        )
    }
}

private data class UriMetadata(
    val displayName: String,
    val size: Long,
    val mimeType: String?,
)

private fun queryUriMetadata(
    contentResolver: ContentResolver,
    uri: Uri,
): UriMetadata {
    var displayName = uri.lastPathSegment ?: "image"
    var size = 0L
    val mimeType = contentResolver.getType(uri)

    contentResolver
        .query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }

    return UriMetadata(displayName, size, mimeType)
}
