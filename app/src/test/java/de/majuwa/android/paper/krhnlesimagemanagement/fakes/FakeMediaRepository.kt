package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo

class FakeMediaRepository : MediaRepositoryContract {
    var photosToReturn: List<Photo> = emptyList()
    var shouldThrow: Throwable? = null

    override suspend fun loadPhotos(): List<Photo> {
        shouldThrow?.let { throw it }
        return photosToReturn
    }
}
