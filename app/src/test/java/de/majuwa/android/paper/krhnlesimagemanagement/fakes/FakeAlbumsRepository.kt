package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import android.graphics.Bitmap
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto

class FakeAlbumsRepository : AlbumsRepositoryContract {
    var albums: Result<List<RemoteAlbum>> = Result.success(emptyList())
    var photos: Result<List<RemotePhoto>> = Result.success(emptyList())
    var deletePhotoResult: Result<Unit> = Result.success(Unit)
    var deleteAlbumResult: Result<Unit> = Result.success(Unit)
    var downloadBitmapResult: Bitmap? = null

    val deletedPhotos = mutableListOf<RemotePhoto>()
    val deletedAlbums = mutableListOf<RemoteAlbum>()

    override suspend fun listAlbums(): Result<List<RemoteAlbum>> = albums

    override suspend fun listPhotos(albumHref: String): Result<List<RemotePhoto>> = photos

    override suspend fun thumbnailUrl(photo: RemotePhoto): String = "https://fake.server/thumb/${photo.href}"

    override fun fullImageUrl(photo: RemotePhoto): String = "https://fake.server/full/${photo.href}"

    override suspend fun downloadBitmapForHash(url: String): Bitmap =
        downloadBitmapResult ?: error("No bitmap configured in FakeAlbumsRepository")

    override suspend fun deletePhoto(photo: RemotePhoto): Result<Unit> {
        deletedPhotos.add(photo)
        return deletePhotoResult
    }

    override suspend fun deleteAlbum(album: RemoteAlbum): Result<Unit> {
        deletedAlbums.add(album)
        return deleteAlbumResult
    }
}
