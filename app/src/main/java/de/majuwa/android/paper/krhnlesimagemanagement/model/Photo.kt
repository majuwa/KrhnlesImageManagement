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
)
