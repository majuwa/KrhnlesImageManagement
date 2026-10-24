package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUploadedPhotosRepository : UploadedPhotosRepositoryContract {
    private val mutableUploadedIds = MutableStateFlow<Set<Long>>(emptySet())

    override val uploadedPhotoIds: Flow<Set<Long>> = mutableUploadedIds

    /** Number of [markAsUploaded] calls. */
    var markCalls: Int = 0
        private set

    override suspend fun markAsUploaded(photoIds: Set<Long>) {
        markCalls++
        mutableUploadedIds.value = mutableUploadedIds.value + photoIds
    }

    override suspend fun clear() {
        mutableUploadedIds.value = emptySet()
    }
}
