package de.majuwa.android.paper.krhnlesimagemanagement.model

data class UploadHistoryEntry(
    val id: Long,
    val occasionName: String,
    val timestampMillis: Long,
    val photoCount: Int,
    val failedCount: Int,
)
