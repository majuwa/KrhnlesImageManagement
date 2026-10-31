package de.majuwa.android.paper.krhnlesimagemanagement.model

data class UploadHistoryEntry(
    val id: Long,
    val occasionName: String,
    val timestampMillis: Long,
    val photoCount: Int,
    /** For a [cancelled] run this is the number of items not uploaded (never called failed). */
    val failedCount: Int,
    /** The user cancelled the run; absent in entries of older versions = false. */
    val cancelled: Boolean = false,
)
