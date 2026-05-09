package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUploadedPhotosRepository : UploadedPhotosRepositoryContract {
    private val mutableUploadedIds = MutableStateFlow<Set<Long>>(emptySet())

    override val uploadedPhotoIds: Flow<Set<Long>> = mutableUploadedIds

    override suspend fun markAsUploaded(photoIds: Set<Long>) {
        mutableUploadedIds.value = mutableUploadedIds.value + photoIds
    }

    override suspend fun clear() {
        mutableUploadedIds.value = emptySet()
    }
}
