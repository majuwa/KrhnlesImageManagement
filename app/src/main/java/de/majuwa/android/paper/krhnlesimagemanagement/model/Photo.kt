package de.majuwa.android.paper.krhnlesimagemanagement.model

import android.net.Uri
import java.time.LocalDate

/** Which MediaStore collection a [Photo] came from; never derived from the mime type (it can be null). */
enum class MediaKind { Image, Video }

/** A local media item (photo or video) of the grid; videos are marked by [mediaKind]. */
data class Photo(
    /** Grid / upload id: the raw MediaStore id for images, [mediaItemId] (flagged) for videos. */
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateTaken: LocalDate,
    val size: Long,
    val mimeType: String,
    /**
     * Name of the app-private staged copy (shared photos only, see `ShareStaging`); the upload reads
     * that copy instead of [uri]. Null for MediaStore photos.
     */
    val stagedName: String? = null,
    /** Set by the query that produced the item ([MediaKind.Image] for shared photos). */
    val mediaKind: MediaKind = MediaKind.Image,
    /** Video duration in milliseconds; null when unknown (and for images). */
    val durationMs: Long? = null,
) {
    /** True unless the media store had no capture date ([UNKNOWN_DATE]). */
    val hasKnownDate: Boolean get() = dateTaken.isKnownDate()

    val isVideo: Boolean get() = mediaKind == MediaKind.Video

    companion object {
        /** Sentinel for media without a capture date (MediaStore `DATE_TAKEN` null or 0). */
        val UNKNOWN_DATE: LocalDate = LocalDate.EPOCH

        /**
         * Set in the ids of videos: MediaStore image and video `_ID`s overlap, so a video's id is
         * `rawId or VIDEO_ID_FLAG`. The value stays positive (queue `>= 0` = markable, `-1` = no id).
         */
        const val VIDEO_ID_FLAG: Long = 1L shl 62
    }
}

/** The one place that checks the [Photo.UNKNOWN_DATE] sentinel. */
fun LocalDate.isKnownDate(): Boolean = this != Photo.UNKNOWN_DATE

/**
 * The collision-free [Photo.id] of the MediaStore row [rawId] of [kind]: images keep their raw id,
 * videos get [Photo.VIDEO_ID_FLAG] set. Null (row skipped) for a negative raw id or one
 * `>= 2^62`, which would collide with the flag.
 */
fun mediaItemId(
    rawId: Long,
    kind: MediaKind,
): Long? =
    when {
        rawId < 0L || rawId >= Photo.VIDEO_ID_FLAG -> null
        kind == MediaKind.Video -> rawId or Photo.VIDEO_ID_FLAG
        else -> rawId
    }
