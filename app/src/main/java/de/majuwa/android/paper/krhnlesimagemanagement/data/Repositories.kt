package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.graphics.Bitmap
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.Flow

interface CredentialRepository {
    val isConfigured: Flow<Boolean>
    val webDavConfig: Flow<WebDavConfig>

    fun password(): String?

    fun isLoggedIn(): Boolean

    suspend fun save(
        serverUrl: String,
        username: String,
        password: String,
    )

    suspend fun saveBaseFolder(folder: String)

    suspend fun clear()
}

interface AlbumsRepositoryContract {
    suspend fun listAlbums(): Result<List<RemoteAlbum>>

    suspend fun listPhotos(albumHref: String): Result<List<RemotePhoto>>

    suspend fun thumbnailUrl(photo: RemotePhoto): String

    fun fullImageUrl(photo: RemotePhoto): String

    suspend fun downloadBitmapForHash(url: String): Bitmap

    suspend fun deletePhoto(photo: RemotePhoto): Result<Unit>

    suspend fun deleteAlbum(album: RemoteAlbum): Result<Unit>
}

interface MediaRepositoryContract {
    suspend fun loadPhotos(): List<Photo>
}
