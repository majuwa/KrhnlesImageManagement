package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositorySource
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.PhotoDeletionResult
import de.majuwa.android.paper.krhnlesimagemanagement.data.deletePhotosIndividually
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

data class AlbumDetailState(
    val albumName: String = "",
    val photos: List<RemotePhoto> = emptyList(),
    val thumbnailUrls: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val error: AlbumsError? = null,
    /** `true` once the photos and thumbnail URLs of the current album have been loaded successfully. */
    val isLoaded: Boolean = false,
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
 * [AlbumDetailViewModel.consumePhotoDeletionOutcome].
 */
data class PhotoDeletionOutcome(
    val failureCount: Int,
)

/**
 * Runs photo deletions for every entry point (album selection mode, duplicate review, blur
 * review) through one orchestration, so all of them share one re-entry guard, one album photo
 * list and one outcome. Implemented by [AlbumDetailViewModel]; the review ViewModels only supply
 * their busy-state transitions.
 */
interface PhotoDeletionLauncher {
    /**
     * Deletes [toDelete]. Ignored (returns `false`, nothing is called) while another photo
     * deletion is still running. Otherwise [onStart] runs synchronously, [onFinish] runs when the
     * deletion ends — always, also on failure or cancellation — before the outcome is published,
     * and `true` is returned.
     */
    fun launchPhotoDeletion(
        toDelete: List<RemotePhoto>,
        onStart: () -> Unit,
        onFinish: () -> Unit,
    ): Boolean
}

/**
 * Album detail: photos and thumbnails of one album, full-image URLs, selection-mode deletion and
 * the shared photo-deletion orchestration ([PhotoDeletionLauncher]) with its one-shot
 * [photoDeletionOutcome]. Scoped to the album detail back-stack entry and shared with the
 * fullscreen viewer, duplicate review and blur review opened from it (the reviews also use its
 * [repositories]).
 *
 * Observes the WebDAV configuration: after logout or a server / account change, a running load
 * and deletion are cancelled, the cached repository is dropped and the album's photos are cleared
 * ([AlbumsError.NotConfigured] / [AlbumsError.ConfigurationChanged]), so nothing is listed or
 * deleted on the old server with the old credentials.
 */
class AlbumDetailViewModel
    @JvmOverloads
    constructor(
        application: Application,
        credentialRepo: CredentialRepository =
            CredentialStore(application),
        repoFactory: (WebDavConfig) -> AlbumsRepositoryContract =
            { config -> AlbumsRepository(config) },
    ) : AndroidViewModel(application),
        PhotoDeletionLauncher {
        private val repos = AlbumsRepositoryProvider(credentialRepo, repoFactory = repoFactory)

        /**
         * This album's repository (one OkHttp client per album flow), shared with the duplicate and
         * blur review opened from it. Always reflects the current configuration.
         */
        val repositories: AlbumsRepositorySource get() = repos

        private val _detailState = MutableStateFlow(AlbumDetailState())
        val detailState: StateFlow<AlbumDetailState> = _detailState.asStateFlow()

        private val _isDeletingPhotos = MutableStateFlow(false)

        /** `true` while a selection-mode deletion ([deleteSelectedPhotos]) runs. */
        val isDeletingPhotos: StateFlow<Boolean> = _isDeletingPhotos.asStateFlow()

        private val _photoDeletionOutcome = MutableStateFlow<PhotoDeletionOutcome?>(null)

        /** Result of the last finished photo deletion until [consumePhotoDeletionOutcome] is called. */
        val photoDeletionOutcome: StateFlow<PhotoDeletionOutcome?> = _photoDeletionOutcome.asStateFlow()

        /** The running photo deletion (any entry point); re-entry is ignored while it is active. */
        private var photoDeletionJob: Job? = null

        /** The running load; replaced by the next load. */
        private var loadJob: Job? = null

        /** Href of the album this ViewModel loads / has loaded; target of [reload]. */
        private var currentHref: String? = null

        init {
            // Logout / server or account change while this album is open: stop using the old
            // server and credentials and drop the photos that belong to it.
            viewModelScope.launch {
                credentialRepo.webDavConfig
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { config -> onConfigurationChanged(config) }
            }
        }

        /**
         * Loads [albumHref] unless it is already loaded or loading without an error, so a
         * recreated screen (rotation) never reloads — which could resurrect photos while a
         * deletion runs. A different album, or the same one after an error, is loaded. Use
         * [reload] to force a reload.
         */
        fun loadAlbum(albumHref: String) {
            if (albumHref == currentHref && _detailState.value.error == null) return
            startLoad(albumHref)
        }

        /** Reloads the current album (Retry); ignored before any album has been requested. */
        fun reload() {
            currentHref?.let { startLoad(it) }
        }

        private fun startLoad(albumHref: String) {
            currentHref = albumHref
            val albumName =
                java.net.URLDecoder.decode(
                    albumHref.trimEnd('/').substringAfterLast('/'),
                    "UTF-8",
                )
            _detailState.update { AlbumDetailState(albumName = albumName, isLoading = true) }
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    try {
                        val repository = repos.get().getOrThrow()
                        val photos = repository.listPhotos(albumHref).getOrThrow()
                        _detailState.update { it.copy(photos = photos, isLoading = false) }
                        val urls = photos.associate { photo -> photo.href to repository.thumbnailUrl(photo) }
                        _detailState.update { it.copy(thumbnailUrls = urls, isLoaded = true) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Type only: exception messages may contain server details.
                        Log.w(TAG, "Failed to load album photos: ${e.javaClass.simpleName}")
                        _detailState.update { it.copy(isLoading = false, error = e.toAlbumsError()) }
                    }
                }
        }

        private fun onConfigurationChanged(config: WebDavConfig) {
            loadJob?.cancel()
            photoDeletionJob?.cancel()
            repos.invalidate()
            val error = if (config.isValid) AlbumsError.ConfigurationChanged else AlbumsError.NotConfigured
            _detailState.update { AlbumDetailState(albumName = it.albumName, error = error) }
        }

        /** Full-size image URL of [photo], or `""` before the album has been loaded. */
        fun fullImageUrl(photo: RemotePhoto): String = repos.current?.fullImageUrl(photo) ?: ""

        /**
         * Deletes [toDelete] photos directly from the album view (not via a review).
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
         * released when the job ends; every request is bounded by the OkHttp connect/read timeouts).
         * [onStart] runs synchronously, then [deletePhotosIndividually] deletes [toDelete] (photos that
         * are not in the currently loaded album — e.g. a review result that survived a server /
         * account change — are not deleted and count as failed), only the
         * photos that were actually deleted are removed from [detailState], [onFinish] resets the
         * caller's busy state (always, in `finally`) and the failure count is published as
         * [photoDeletionOutcome]. Any non-cancellation failure (e.g. an invalid server URL when the
         * repository is created) counts every photo as failed instead of crashing; cancellation
         * publishes nothing (also when a configuration change cancels it). The deletion runs in this
         * ViewModel's scope, so it outlives the review screen that started it; every screen that
         * starts one blocks leaving while it runs.
         */
        override fun launchPhotoDeletion(
            toDelete: List<RemotePhoto>,
            onStart: () -> Unit,
            onFinish: () -> Unit,
        ): Boolean {
            if (photoDeletionJob?.isCompleted == false) return false
            onStart()
            if (toDelete.isEmpty()) {
                onFinish()
                _photoDeletionOutcome.value = PhotoDeletionOutcome(failureCount = 0)
                return true
            }
            // onFinish must run exactly once, also when the job is cancelled (configuration
            // change, ViewModel cleared) before its body was ever dispatched: the body's finally
            // covers the normal and cancelled-while-running paths, invokeOnCompletion the rest.
            val finished = AtomicBoolean(false)
            val finishOnce = { if (finished.compareAndSet(false, true)) onFinish() }
            val job =
                viewModelScope.launch {
                    val failureCount =
                        try {
                            deletePhotosOrReportAllFailed(toDelete).let { deletion ->
                                _detailState.update { s -> s.withPhotosDeleted(deletion.deletedHrefs) }
                                deletion.failureCount
                            }
                        } finally {
                            finishOnce()
                        }
                    _photoDeletionOutcome.value = PhotoDeletionOutcome(failureCount)
                }
            job.invokeOnCompletion { finishOnce() }
            photoDeletionJob = job
            return true
        }

        /**
         * Runs the deletion; returns a result for every outcome and only throws on cancellation.
         * Photos that are not part of the currently loaded album (e.g. a review result that
         * survived a server / account change) are never deleted and count as failed.
         */
        private suspend fun deletePhotosOrReportAllFailed(requested: List<RemotePhoto>): PhotoDeletionResult {
            currentCoroutineContext().ensureActive()
            val currentHrefs = _detailState.value.photos.mapTo(HashSet()) { it.href }
            val (toDelete, stale) = requested.partition { it.href in currentHrefs }
            if (toDelete.isEmpty()) return PhotoDeletionResult.allFailed(stale)
            val result =
                runCatching { repos.get().getOrThrow().deletePhotosIndividually(toDelete) }
                    .getOrElse { failure ->
                        if (failure is CancellationException) throw failure
                        // Log the type only: exception messages may contain server details.
                        Log.w(TAG, "Photo deletion failed: ${failure.javaClass.simpleName}")
                        PhotoDeletionResult.allFailed(toDelete)
                    }
            return result.copy(failureCount = result.failureCount + stale.size)
        }

        private companion object {
            const val TAG = "AlbumDetailViewModel"
        }
    }
