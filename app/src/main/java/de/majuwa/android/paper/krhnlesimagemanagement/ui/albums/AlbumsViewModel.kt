package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.PhotoDeletionResult
import de.majuwa.android.paper.krhnlesimagemanagement.data.deletePhotosIndividually
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurAnalyzer
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurDetector
import de.majuwa.android.paper.krhnlesimagemanagement.util.DuplicateFinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class AlbumsState(
    val albums: List<RemoteAlbum> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isDeletingAlbum: Boolean = false,
    val isRenamingAlbum: Boolean = false,
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

/**
 * One-shot result of a finished photo deletion (any entry point), consumed by the album detail
 * screen — the screen that is visible after the duplicate / blur review navigates back — via
 * [AlbumsViewModel.consumePhotoDeletionOutcome].
 */
data class PhotoDeletionOutcome(
    val failureCount: Int,
)

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

class AlbumsViewModel
    @JvmOverloads
    constructor(
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

        private val _photoDeletionOutcome = MutableStateFlow<PhotoDeletionOutcome?>(null)

        /** Result of the last finished photo deletion until [consumePhotoDeletionOutcome] is called. */
        val photoDeletionOutcome: StateFlow<PhotoDeletionOutcome?> = _photoDeletionOutcome.asStateFlow()

        /** The running photo deletion (any entry point); re-entry is ignored while it is active. */
        private var photoDeletionJob: Job? = null

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
                        Log.e(TAG, "Failed to load albums", t)
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
                        Log.e(TAG, "Failed to load photos for $albumHref", t)
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
                        } catch (e: Exception) {
                            Log.w("AlbumsViewModel", "Failed to hash photo: ${photo.href}", e)
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
         * Deletes [toDelete] photos found by the duplicate review. [duplicatesState] is
         * [DuplicatesState.Deleting] while it runs and [DuplicatesState.Idle] afterwards; the
         * result is published as [photoDeletionOutcome].
         * Returns `false` (and does nothing) while another photo deletion is still running.
         */
        fun deletePhotos(toDelete: List<RemotePhoto>): Boolean =
            launchPhotoDeletion(
                toDelete = toDelete,
                onStart = { _duplicatesState.value = DuplicatesState.Deleting },
                // Only leave Deleting: never overwrite a newer scan started meanwhile.
                onFinish = { _duplicatesState.compareAndSet(DuplicatesState.Deleting, DuplicatesState.Idle) },
            )

        /**
         * Deletes [toDelete] photos directly from the album view (not via the duplicates flow).
         * [isDeletingPhotos] is `true` while it runs; the result is published as
         * [photoDeletionOutcome].
         * Returns `false` (and does nothing) while another photo deletion is still running.
         */
        fun deleteSelectedPhotos(toDelete: List<RemotePhoto>): Boolean =
            launchPhotoDeletion(
                toDelete = toDelete,
                onStart = { _isDeletingPhotos.value = true },
                onFinish = { _isDeletingPhotos.value = false },
            )

        /** Clears [photoDeletionOutcome] once the UI has shown it. */
        fun consumePhotoDeletionOutcome() {
            _photoDeletionOutcome.value = null
        }

        /**
         * Shared orchestration for every photo-deletion entry point (issues #28 / #47).
         *
         * Ignored (returns `false`) while another photo deletion is still running (one-shot guard,
         * released when the job ends; every request is bounded by the OkHttp connect/read timeouts). [onStart] runs
         * synchronously, then [deletePhotosIndividually] deletes [toDelete], only the photos that
         * were actually deleted are removed from [detailState], [onFinish] resets the busy state
         * (always, in `finally`) and the failure count is published as [photoDeletionOutcome].
         * Any non-cancellation failure (e.g. an invalid server URL in [getRepo]) counts every
         * photo as failed instead of crashing; cancellation publishes nothing.
         */
        private fun launchPhotoDeletion(
            toDelete: List<RemotePhoto>,
            onStart: () -> Unit,
            onFinish: () -> Unit,
        ): Boolean {
            if (photoDeletionJob?.isActive == true) return false
            onStart()
            if (toDelete.isEmpty()) {
                onFinish()
                _photoDeletionOutcome.value = PhotoDeletionOutcome(failureCount = 0)
                return true
            }
            photoDeletionJob =
                viewModelScope.launch {
                    val failureCount =
                        try {
                            deletePhotosOrReportAllFailed(toDelete).let { deletion ->
                                _detailState.update { s -> s.withPhotosDeleted(deletion.deletedHrefs) }
                                deletion.failureCount
                            }
                        } finally {
                            onFinish()
                        }
                    _photoDeletionOutcome.value = PhotoDeletionOutcome(failureCount)
                }
            return true
        }

        /** Runs the deletion; returns a result for every outcome and only throws on cancellation. */
        private suspend fun deletePhotosOrReportAllFailed(toDelete: List<RemotePhoto>): PhotoDeletionResult =
            runCatching { getRepo().deletePhotosIndividually(toDelete) }
                .getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    // Log the type only: exception messages may contain server details.
                    Log.w(TAG, "Photo deletion failed: ${failure.javaClass.simpleName}")
                    PhotoDeletionResult.allFailed(toDelete)
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

        fun renameAlbum(
            album: RemoteAlbum,
            newName: String,
            onComplete: (success: Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                _albumsState.update { it.copy(isRenamingAlbum = true) }
                val r = getRepo()
                r
                    .renameAlbum(album, newName)
                    .onSuccess { renamedAlbum ->
                        _albumsState.update { state ->
                            state.copy(
                                albums =
                                    state.albums.map { existing ->
                                        if (existing.href == album.href) renamedAlbum else existing
                                    },
                                isRenamingAlbum = false,
                            )
                        }
                        onComplete(true)
                    }.onFailure {
                        _albumsState.update { it.copy(isRenamingAlbum = false) }
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
                        } catch (e: Exception) {
                            Log.w("AlbumsViewModel", "Failed to analyze blur for: ${photo.href}", e)
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

        /**
         * Deletes [toDelete] photos found by the blur review. [blurState] is [BlurState.Deleting]
         * while it runs and [BlurState.Idle] afterwards; the result is published as
         * [photoDeletionOutcome].
         * Returns `false` (and does nothing) while another photo deletion is still running.
         */
        fun deleteBlurryPhotos(toDelete: List<RemotePhoto>): Boolean =
            launchPhotoDeletion(
                toDelete = toDelete,
                onStart = { _blurState.value = BlurState.Deleting },
                // Only leave Deleting: never overwrite a newer scan started meanwhile.
                onFinish = { _blurState.compareAndSet(BlurState.Deleting, BlurState.Idle) },
            )

        fun resetBlurState() {
            _blurState.value = BlurState.Idle
        }

        private companion object {
            const val TAG = "AlbumsViewModel"
        }
    }
