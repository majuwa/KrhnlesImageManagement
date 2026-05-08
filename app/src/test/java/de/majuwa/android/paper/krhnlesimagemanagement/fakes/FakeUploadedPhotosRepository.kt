package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUploadedPhotosRepository : UploadedPhotosRepositoryContract {
    private val _uploadedIds = MutableStateFlow<Set<Long>>(emptySet())

    override val uploadedPhotoIds: Flow<Set<Long>> = _uploadedIds

    override suspend fun markAsUploaded(photoIds: Set<Long>) {
        _uploadedIds.value = _uploadedIds.value + photoIds
    }

    override suspend fun clear() {
        _uploadedIds.value = emptySet()
    }
}
