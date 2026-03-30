package de.majuwa.android.paper.krhnlesimagemanagement.model

sealed interface UploadState {
    data object Idle : UploadState

    data class Uploading(
        val progress: Int,
        val total: Int,
    ) : UploadState

    data class Success(
        val count: Int,
    ) : UploadState

    data class Failure(
        val message: String,
    ) : UploadState
}
