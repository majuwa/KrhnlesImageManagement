package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.graphics.Bitmap
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.Flow

interface CredentialRepository {
    val autoDateFoldersEnabled: Flow<Boolean>
    val isConfigured: Flow<Boolean>

    /** Health of the stored credentials; distinguishes "never saved" from "saved but undecryptable". */
    val credentialState: Flow<CredentialState>
    val webDavConfig: Flow<WebDavConfig>
    val wifiOnly: Flow<Boolean>

    fun password(): String?

    fun isLoggedIn(): Boolean

    /** @throws CredentialSaveException if the credentials could not be encrypted or persisted. */
    suspend fun save(
        serverUrl: String,
        username: String,
        password: String,
    )

    suspend fun saveBaseFolder(folder: String)

    suspend fun saveWifiOnly(wifiOnly: Boolean)

    suspend fun saveAutoDateFolders(enabled: Boolean)

    suspend fun clear()
}

/** Credentials could not be encrypted or persisted; previously stored values are left unchanged. */
class CredentialSaveException(
    cause: Throwable,
) : Exception("Saving credentials failed: ${cause.javaClass.simpleName}", cause)

interface AlbumsRepositoryContract {
    suspend fun listAlbums(): Result<List<RemoteAlbum>>

    suspend fun listPhotos(albumHref: String): Result<List<RemotePhoto>>

    suspend fun renameAlbum(
        album: RemoteAlbum,
        newName: String,
    ): Result<RemoteAlbum>

    suspend fun thumbnailUrl(photo: RemotePhoto): String

    fun fullImageUrl(photo: RemotePhoto): String

    suspend fun downloadBitmapForHash(url: String): Bitmap

    suspend fun deletePhoto(photo: RemotePhoto): Result<Unit>

    suspend fun deleteAlbum(album: RemoteAlbum): Result<Unit>
}

interface MediaRepositoryContract {
    suspend fun loadPhotos(): List<Photo>
}

interface UploadHistoryRepository {
    val entries: Flow<List<UploadHistoryEntry>>

    suspend fun addEntry(entry: UploadHistoryEntry)

    suspend fun removeEntry(entryId: Long)

    suspend fun clearAll()
}

interface UploadedPhotosRepositoryContract {
    val uploadedPhotoIds: Flow<Set<Long>>

    suspend fun markAsUploaded(photoIds: Set<Long>)

    suspend fun clear()
}
