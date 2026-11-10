package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo

class FakeMediaRepository : MediaRepositoryContract {
    var photosToReturn: List<Photo> = emptyList()
    var shouldThrow: Throwable? = null
    var loadCount = 0

    override suspend fun loadPhotos(): List<Photo> {
        loadCount++
        shouldThrow?.let { throw it }
        return photosToReturn
    }
}
