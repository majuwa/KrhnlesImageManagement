package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurAnalyzer
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurDetector
import de.majuwa.android.paper.krhnlesimagemanagement.util.DuplicateFinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumsState(
    val albums: List<RemoteAlbum> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isDeletingAlbum: Boolean = false,
)

data class AlbumDetailState(
    val albumName: String = "",
    val photos: List<RemotePhoto> = emptyList(),
    val thumbnailUrls: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    internal fun withPhotosDeleted(hrefs: Set<String>): AlbumDetailState =
        copy(
            photos = photos.filter { it.href !in hrefs },
            thumbnailUrls = thumbnailUrls.filterKeys { it !in hrefs },
        )
}

sealed interface DuplicatesState {
    data object Idle : DuplicatesState

    data class Loading(
        val processed: Int,
        val total: Int,
    ) : DuplicatesState

    data class Found(
        val groups: List<List<RemotePhoto>>,
    ) : DuplicatesState

    data object NoneFound : DuplicatesState

    data class Error(
        val message: String,
    ) : DuplicatesState

    data object Deleting : DuplicatesState
}

sealed interface BlurState {
    data object Idle : BlurState

    data class Scanning(
        val processed: Int,
        val total: Int,
    ) : BlurState

    data class Found(
        val blurryPhotos: List<RemotePhoto>,
        val scores: Map<String, Double>,
    ) : BlurState

    data object NoneFound : BlurState

    data class Error(
        val message: String,
    ) : BlurState

    data object Deleting : BlurState
}

class AlbumsViewModel(
    application: Application,
    private val credentialRepo: CredentialRepository =
        CredentialStore(application),
    private val repoFactory: (WebDavConfig) -> AlbumsRepositoryContract =
        { config -> AlbumsRepository(config) },
) : AndroidViewModel(application) {
    private val _albumsState = MutableStateFlow(AlbumsState())
    val albumsState: StateFlow<AlbumsState> = _albumsState.asStateFlow()

    private val _detailState = MutableStateFlow(AlbumDetailState())
    val detailState: StateFlow<AlbumDetailState> = _detailState.asStateFlow()

    private val _duplicatesState = MutableStateFlow<DuplicatesState>(DuplicatesState.Idle)
    val duplicatesState: StateFlow<DuplicatesState> = _duplicatesState.asStateFlow()

    private val _blurState = MutableStateFlow<BlurState>(BlurState.Idle)
    val blurState: StateFlow<BlurState> = _blurState.asStateFlow()

    private val _isDeletingPhotos = MutableStateFlow(false)
    val isDeletingPhotos: StateFlow<Boolean> = _isDeletingPhotos.asStateFlow()

    // Cached repo instance — recreated only when loadAlbums() is called.
    private var repo: AlbumsRepositoryContract? = null

    private suspend fun getRepo(): AlbumsRepositoryContract =
        repo ?: run {
            val config = credentialRepo.webDavConfig.first()
            repoFactory(config).also { repo = it }
        }

    fun loadAlbums() {
        viewModelScope.launch {
            _albumsState.update { it.copy(isLoading = true, error = null) }
            repo = null // force fresh repo so config changes are picked up
            getRepo()
                .listAlbums()
                .onSuccess { albums ->
                    _albumsState.update { it.copy(albums = albums, isLoading = false) }
                }.onFailure { t ->
                    _albumsState.update {
                        it.copy(isLoading = false, error = t.message ?: "Failed to load albums")
                    }
                }
        }
    }

    fun loadAlbum(albumHref: String) {
        val albumName =
            java.net.URLDecoder.decode(
                albumHref.trimEnd('/').substringAfterLast('/'),
                "UTF-8",
            )
        _detailState.update { AlbumDetailState(albumName = albumName, isLoading = true) }
        viewModelScope.launch {
            val r = getRepo()
            r
                .listPhotos(albumHref)
                .onSuccess { photos ->
                    _detailState.update { it.copy(photos = photos, isLoading = false) }
                    loadThumbnails(photos)
                }.onFailure { t ->
                    _detailState.update {
                        it.copy(isLoading = false, error = t.message ?: "Failed to load photos")
                    }
                }
        }
    }

    private fun loadThumbnails(photos: List<RemotePhoto>) {
        viewModelScope.launch {
            val r = getRepo()
            val urls = photos.associate { photo -> photo.href to r.thumbnailUrl(photo) }
            _detailState.update { it.copy(thumbnailUrls = urls) }
        }
    }

    fun fullImageUrl(photo: RemotePhoto): String = repo?.fullImageUrl(photo) ?: ""

    // ── Duplicate detection ───────────────────────────────────────────────────

    fun findDuplicates() {
        if (_duplicatesState.value is DuplicatesState.Loading) return
        val photos = _detailState.value.photos
        if (photos.isEmpty()) {
            _duplicatesState.value = DuplicatesState.NoneFound
            return
        }
        viewModelScope.launch {
            _duplicatesState.value = DuplicatesState.Loading(0, photos.size)
            try {
                val r = getRepo()
                val hashes = mutableMapOf<String, Long>()
                photos.forEachIndexed { index, photo ->
                    try {
                        val url =
                            _detailState.value.thumbnailUrls[photo.href]
                                ?: r.thumbnailUrl(photo)
                        val bitmap = r.downloadBitmapForHash(url)
                        hashes[photo.href] = DuplicateFinder.computeDHash(bitmap)
                        bitmap.recycle()
                    } catch (_: Exception) {
                        // Skip photos that fail to download or decode
                    }
                    _duplicatesState.value = DuplicatesState.Loading(index + 1, photos.size)
                }
                val groups = DuplicateFinder.groupDuplicates(photos, hashes)
                _duplicatesState.value =
                    if (groups.isEmpty()) {
                        DuplicatesState.NoneFound
                    } else {
                        DuplicatesState.Found(groups)
                    }
            } catch (e: IllegalStateException) {
                _duplicatesState.value =
                    DuplicatesState.Error(e.message ?: "Failed to analyse duplicates")
            } catch (e: java.io.IOException) {
                _duplicatesState.value =
                    DuplicatesState.Error(e.message ?: "Failed to analyse duplicates")
            }
        }
    }

    /**
     * Deletes [toDelete] photos from the server, removes them from [detailState], then
     * resets [duplicatesState] to [DuplicatesState.Idle] and calls [onComplete] with
     * the number of individual deletion failures.
     */
    fun deletePhotos(
        toDelete: List<RemotePhoto>,
        onComplete: (failureCount: Int) -> Unit,
    ) {
        if (toDelete.isEmpty()) {
            _duplicatesState.value = DuplicatesState.Idle
            onComplete(0)
            return
        }
        viewModelScope.launch {
            _duplicatesState.value = DuplicatesState.Deleting
            val r = getRepo()
            var failures = 0
            toDelete.forEach { photo -> r.deletePhoto(photo).onFailure { failures++ } }
            val deletedHrefs = toDelete.map { it.href }.toSet()
            _detailState.update { s -> s.withPhotosDeleted(deletedHrefs) }
            _duplicatesState.value = DuplicatesState.Idle
            onComplete(failures)
        }
    }

    /**
     * Deletes [toDelete] photos directly from the album view (not via the duplicates flow).
     * Updates [isDeletingPhotos] during the operation and calls [onComplete] with the number
     * of individual deletion failures.
     */
    fun deleteSelectedPhotos(
        toDelete: List<RemotePhoto>,
        onComplete: (failureCount: Int) -> Unit,
    ) {
        if (toDelete.isEmpty()) {
            onComplete(0)
            return
        }
        viewModelScope.launch {
            _isDeletingPhotos.value = true
            val r = getRepo()
            var failures = 0
            toDelete.forEach { photo -> r.deletePhoto(photo).onFailure { failures++ } }
            val deletedHrefs = toDelete.map { it.href }.toSet()
            _detailState.update { s -> s.withPhotosDeleted(deletedHrefs) }
            _isDeletingPhotos.value = false
            onComplete(failures)
        }
    }

    /**
     * Deletes the entire [album] from the server and removes it from [albumsState].
     * Calls [onComplete] with `true` on success, `false` on failure.
     */
    fun deleteAlbum(
        album: RemoteAlbum,
        onComplete: (success: Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            _albumsState.update { it.copy(isDeletingAlbum = true) }
            val r = getRepo()
            r
                .deleteAlbum(album)
                .onSuccess {
                    _albumsState.update { s ->
                        s.copy(
                            albums = s.albums.filter { it.href != album.href },
                            isDeletingAlbum = false,
                        )
                    }
                    onComplete(true)
                }.onFailure {
                    _albumsState.update { it.copy(isDeletingAlbum = false) }
                    onComplete(false)
                }
        }
    }

    fun resetDuplicatesState() {
        _duplicatesState.value = DuplicatesState.Idle
    }

    // ── Blur detection ───────────────────────────────────────────────────────

    fun findBlurryPhotos() {
        if (_blurState.value is BlurState.Scanning) return
        val photos = _detailState.value.photos
        if (photos.isEmpty()) {
            _blurState.value = BlurState.NoneFound
            return
        }
        viewModelScope.launch {
            _blurState.value = BlurState.Scanning(0, photos.size)
            val analyzer = BlurAnalyzer()
            try {
                val r = getRepo()
                val blurry = mutableListOf<RemotePhoto>()
                val scores = mutableMapOf<String, Double>()
                photos.forEachIndexed { index, photo ->
                    try {
                        val url =
                            _detailState.value.thumbnailUrls[photo.href]
                                ?: r.thumbnailUrl(photo)
                        val bitmap = r.downloadBitmapForHash(url)
                        val score = analyzer.computeBlurScore(bitmap)
                        bitmap.recycle()
                        if (score < BlurDetector.DEFAULT_THRESHOLD) {
                            blurry += photo
                            scores[photo.href] = score
                        }
                    } catch (_: Exception) {
                        // Skip photos that fail to download or decode
                    }
                    _blurState.value = BlurState.Scanning(index + 1, photos.size)
                }
                _blurState.value =
                    if (blurry.isEmpty()) {
                        BlurState.NoneFound
                    } else {
                        BlurState.Found(blurry, scores)
                    }
            } catch (e: IllegalStateException) {
                _blurState.value = BlurState.Error(e.message ?: "Failed to analyse photos for blur")
            } catch (e: java.io.IOException) {
                _blurState.value = BlurState.Error(e.message ?: "Failed to analyse photos for blur")
            } finally {
                analyzer.close()
            }
        }
    }

    fun deleteBlurryPhotos(
        toDelete: List<RemotePhoto>,
        onComplete: (failureCount: Int) -> Unit,
    ) {
        if (toDelete.isEmpty()) {
            _blurState.value = BlurState.Idle
            onComplete(0)
            return
        }
        viewModelScope.launch {
            _blurState.value = BlurState.Deleting
            val r = getRepo()
            var failures = 0
            toDelete.forEach { photo -> r.deletePhoto(photo).onFailure { failures++ } }
            val deletedHrefs = toDelete.map { it.href }.toSet()
            _detailState.update { s -> s.withPhotosDeleted(deletedHrefs) }
            _blurState.value = BlurState.Idle
            onComplete(failures)
        }
    }

    fun resetBlurState() {
        _blurState.value = BlurState.Idle
    }
}
