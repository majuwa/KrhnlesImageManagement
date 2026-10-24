package de.majuwa.android.paper.krhnlesimagemanagement.model

import android.net.Uri
import java.time.LocalDate

data class Photo(
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
) {
    /** True unless the media store had no capture date ([UNKNOWN_DATE]). */
    val hasKnownDate: Boolean get() = dateTaken.isKnownDate()

    companion object {
        /** Sentinel for media without a capture date (MediaStore `DATE_TAKEN` null or 0). */
        val UNKNOWN_DATE: LocalDate = LocalDate.EPOCH
    }
}

/** The one place that checks the [Photo.UNKNOWN_DATE] sentinel. */
fun LocalDate.isKnownDate(): Boolean = this != Photo.UNKNOWN_DATE
