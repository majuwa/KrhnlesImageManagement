package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.graphics.Bitmap
import de.majuwa.android.paper.krhnlesimagemanagement.model.BlurSensitivity
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

    /** Verify uploads by downloading them again (extra data); `false` while unset. */
    val verifyByDownload: Flow<Boolean>

    /** Chosen blur sensitivity; Medium while unset or when the stored value is unreadable. */
    val blurSensitivity: Flow<BlurSensitivity>

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

    suspend fun saveVerifyByDownload(enabled: Boolean)

    suspend fun saveBlurSensitivity(sensitivity: BlurSensitivity)

    suspend fun clear()
}

/** Persists whether the first-run onboarding was completed or skipped. */
interface OnboardingRepository {
    /** `false` while unset; also `false` when the store cannot be read. */
    val completed: Flow<Boolean>

    suspend fun markCompleted()
}

/** Credentials could not be encrypted or persisted; previously stored values are left unchanged. */
class CredentialSaveException(
    cause: Throwable,
) : Exception("Saving credentials failed: ${cause.javaClass.simpleName}", cause)

interface AlbumsRepositoryContract {
    suspend fun listAlbums(): Result<List<RemoteAlbum>>

    /**
     * Lists the photos of [albumHref] with one streamed PROPFIND (bounded memory, see
     * [PhotoListing]). [onProgress] receives the number of photos found so far, throttled; it may
     * be called on a background thread, so it must only write thread-safe state.
     */
    suspend fun listPhotos(
        albumHref: String,
        onProgress: (photosFound: Int) -> Unit = {},
    ): Result<PhotoListing>

    suspend fun renameAlbum(
        album: RemoteAlbum,
        newName: String,
    ): Result<RemoteAlbum>

    suspend fun thumbnailUrl(photo: RemotePhoto): String

    fun fullImageUrl(photo: RemotePhoto): String

    suspend fun downloadBitmapForHash(url: String): Bitmap

    suspend fun deletePhoto(photo: RemotePhoto): Result<Unit>

    suspend fun deleteAlbum(album: RemoteAlbum): Result<Unit>

    /** Releases background work (e.g. retries) of a repository that is dropped; no-op by default. */
    fun close() {}
}

/**
 * Photos of one album listing. [isTruncated] is `true` when the listing stopped at a size limit
 * (response bytes or photo count), so [photos] is only the first part of the album.
 */
data class PhotoListing(
    val photos: List<RemotePhoto>,
    val isTruncated: Boolean = false,
)

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

/**
 * Photos whose upload was verified byte-identical on the server (see [VerifiedUploadsStore]); the
 * only input of the "Remove uploaded photos from device" action.
 */
interface VerifiedUploadsRepositoryContract {
    /** Newest first; malformed stored elements are skipped. */
    val records: Flow<List<VerifiedUpload>>

    /** Stores [record], replacing an older record of the same photo id. */
    suspend fun recordVerified(record: VerifiedUpload)

    /**
     * One edit for a `PUT` to [target] (before it and after it, whatever its outcome): every record
     * at [target] is dropped whatever its id (the file there is no longer the one it describes),
     * the ids in [forgetIds] too, then [record] (if non-null, the verified result of this `PUT`) is
     * stored.
     */
    suspend fun applyUpload(
        target: UploadTarget,
        record: VerifiedUpload? = null,
        forgetIds: Set<Long> = emptySet(),
    )

    suspend fun forget(ids: Set<Long>)

    suspend fun clear()
}

/**
 * Remembers whether the one-time video access request for images-only users was made, whether
 * the system ever offered a rationale for video access (so a later denial without rationale is
 * known to be permanent, not a dismissed dialog) and whether the media location access was asked.
 */
interface MediaPermissionRepository {
    /** `false` while unset; also `false` when the store cannot be read. */
    val videoAccessRequested: Flow<Boolean>

    suspend fun markVideoAccessRequested()

    /** `false` while unset; also `false` when the store cannot be read. */
    val videoRationaleShown: Flow<Boolean>

    suspend fun markVideoRationaleShown()

    /**
     * Whether the app asked for `ACCESS_MEDIA_LOCATION` (asked once automatically, so users of an
     * older version are not asked again); `false` while unset and when the store cannot be read.
     */
    val mediaLocationRequested: Flow<Boolean>

    suspend fun markMediaLocationRequested()
}
