package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import android.graphics.Bitmap
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.PhotoListing
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.util.exceedsAlbumNameLimit
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName
import okhttp3.Call

class FakeAlbumsRepository : AlbumsRepositoryContract {
    var albums: Result<List<RemoteAlbum>> = Result.success(emptyList())
    var photos: Result<List<RemotePhoto>> = Result.success(emptyList())
    var renameAlbumResult: Result<RemoteAlbum>? = null
    var deletePhotoResult: Result<Unit> = Result.success(Unit)

    /** Per-photo overrides keyed by [RemotePhoto.href]; falls back to [deletePhotoResult]. */
    var deletePhotoResults: Map<String, Result<Unit>> = emptyMap()

    /** Invoked (and may suspend or throw) on every [deletePhoto] call before the result is returned. */
    var onDeletePhoto: suspend (RemotePhoto) -> Unit = {}
    var deleteAlbumResult: Result<Unit> = Result.success(Unit)
    var downloadBitmapResult: Bitmap? = null

    /** Creates a fresh bitmap per download (callers recycle it); takes precedence over [downloadBitmapResult]. */
    var downloadBitmapFactory: (() -> Bitmap)? = null

    /** Number of [close] calls. */
    var closeCalls = 0
        private set

    override fun close() {
        closeCalls++
    }

    val deletedPhotos = mutableListOf<RemotePhoto>()
    val deletedAlbums = mutableListOf<RemoteAlbum>()
    val renamedAlbums = mutableListOf<Pair<RemoteAlbum, String>>()

    /** Number of [listAlbums] calls. */
    var listAlbumsCalls = 0
        private set

    /** Invoked (and may suspend or throw) on every [listAlbums] call before the result is returned. */
    var onListAlbums: suspend () -> Unit = {}

    override suspend fun listAlbums(): Result<List<RemoteAlbum>> {
        listAlbumsCalls++
        onListAlbums()
        return albums
    }

    /** Maps an album to its folder name; the default passes [RemoteAlbum.displayName] through the validity checks. */
    var folderNameMapper: (RemoteAlbum) -> String? = { album ->
        album.displayName.takeIf {
            isValidOccasionName(it) && !exceedsAlbumNameLimit(it) && it == it.trim()
        }
    }

    override fun folderNameOf(album: RemoteAlbum): String? = folderNameMapper(album)

    /** Invoked (and may suspend or throw) on every [renameAlbum] call before the result is returned. */
    var onRenameAlbum: suspend () -> Unit = {}

    /** Marks a successful [listPhotos] result as truncated. */
    var photosTruncated: Boolean = false

    /** Invoked (and may suspend, report progress or throw) on every [listPhotos] call before the result. */
    var onListPhotos: suspend (onProgress: (Int) -> Unit) -> Unit = {}

    /** Number of [listPhotos] calls. */
    var listPhotosCalls = 0
        private set

    override suspend fun listPhotos(
        albumHref: String,
        onProgress: (photosFound: Int) -> Unit,
    ): Result<PhotoListing> {
        listPhotosCalls++
        onListPhotos(onProgress)
        return photos.map { PhotoListing(it, isTruncated = photosTruncated) }
    }

    override suspend fun renameAlbum(
        album: RemoteAlbum,
        newName: String,
    ): Result<RemoteAlbum> {
        renamedAlbums += album to newName
        onRenameAlbum()
        return renameAlbumResult
            ?: Result.success(
                album.copy(
                    displayName = newName,
                    href = "/${newName.replace(' ', '_')}/",
                ),
            )
    }

    /** `false` simulates a server without video previews: videos get no thumbnail / full image URL. */
    var videoPreviewsAvailable = true

    /** Every photo passed to [thumbnailUrl], in call order. */
    val thumbnailRequests = mutableListOf<RemotePhoto>()

    override suspend fun thumbnailUrl(photo: RemotePhoto): String? {
        thumbnailRequests += photo
        return if (photo.isVideo && !videoPreviewsAvailable) null else "https://fake.server/thumb/${photo.href}"
    }

    override fun fullImageUrl(photo: RemotePhoto): String? =
        if (photo.isVideo && !videoPreviewsAvailable) null else "https://fake.server/full/${photo.href}"

    /** `null` simulates an unsafe href: [videoStreamUrl] then yields no URL. */
    var videoStreamUrlOverride: String? = null
    var videoStreamUrlAvailable = true

    override fun videoStreamUrl(photo: RemotePhoto): String? =
        when {
            !photo.isVideo || !videoStreamUrlAvailable -> null
            else -> videoStreamUrlOverride ?: "https://fake.server/video${photo.href}"
        }

    /** The factory handed to the player; the default fails every call (no network in fakes). */
    var videoCalls: Call.Factory = Call.Factory { error("no network in FakeAlbumsRepository") }

    override fun videoCallFactory(): Call.Factory = videoCalls

    /** Every URL passed to [downloadBitmapForHash], in call order. */
    val downloadedUrls = mutableListOf<String>()

    /** Invoked (and may suspend or throw) on every [downloadBitmapForHash] call before the bitmap is returned. */
    var onDownloadBitmap: suspend (String) -> Unit = {}

    override suspend fun downloadBitmapForHash(url: String): Bitmap {
        downloadedUrls += url
        onDownloadBitmap(url)
        return downloadBitmapFactory?.invoke() ?: downloadBitmapResult
            ?: error("No bitmap configured in FakeAlbumsRepository")
    }

    override suspend fun deletePhoto(photo: RemotePhoto): Result<Unit> {
        deletedPhotos.add(photo)
        onDeletePhoto(photo)
        return deletePhotoResults[photo.href] ?: deletePhotoResult
    }

    override suspend fun deleteAlbum(album: RemoteAlbum): Result<Unit> {
        deletedAlbums.add(album)
        return deleteAlbumResult
    }
}
