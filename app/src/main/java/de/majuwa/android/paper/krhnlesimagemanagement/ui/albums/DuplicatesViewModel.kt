package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositorySource
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.util.DuplicateFinder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val error: AlbumsError,
    ) : DuplicatesState

    data object Deleting : DuplicatesState
}

/**
 * Duplicate review: scans the photos of an album for near-duplicates. Scoped to the duplicate
 * review back-stack entry, so the scan stops and its state is discarded when the review is left.
 * The album's photos, thumbnail URLs and repository come from [AlbumDetailViewModel] (passed per
 * call); deletions run through its [PhotoDeletionLauncher]. Hashing runs on [analysisDispatcher].
 */
class DuplicatesViewModel
    @JvmOverloads
    constructor(
        private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : ViewModel() {
        private val _duplicatesState = MutableStateFlow<DuplicatesState>(DuplicatesState.Idle)
        val duplicatesState: StateFlow<DuplicatesState> = _duplicatesState.asStateFlow()

        /** The running scan; cancelled by [cancelScan], [resetDuplicatesState] and the next scan. */
        private var scanJob: Job? = null

        /**
         * Starts the scan of [photos] for duplicates — only from [DuplicatesState.Idle], so a screen
         * recreated (e.g. after rotation) while the scan runs, its results are shown or a deletion
         * runs keeps that state. Each photo's thumbnail comes from [thumbnailUrls] or, if missing,
         * the repository's thumbnail URL. The caller passes only a fully loaded album.
         */
        fun findDuplicates(
            photos: List<RemotePhoto>,
            thumbnailUrls: Map<String, String>,
            repositories: AlbumsRepositorySource,
        ) {
            if (_duplicatesState.value != DuplicatesState.Idle) return
            startScan(photos, thumbnailUrls, repositories)
        }

        /**
         * Restarts the scan after it failed; ignored unless the state is [DuplicatesState.Error]
         * and [photos] is not empty (an empty list means the album is not loaded, so the error
         * is kept instead of reporting "no duplicates").
         */
        fun retryFindDuplicates(
            photos: List<RemotePhoto>,
            thumbnailUrls: Map<String, String>,
            repositories: AlbumsRepositorySource,
        ) {
            if (_duplicatesState.value !is DuplicatesState.Error || photos.isEmpty()) return
            startScan(photos, thumbnailUrls, repositories)
        }

        private fun startScan(
            photos: List<RemotePhoto>,
            thumbnailUrls: Map<String, String>,
            repositories: AlbumsRepositorySource,
        ) {
            scanJob?.cancel()
            if (photos.isEmpty()) {
                _duplicatesState.value = DuplicatesState.NoneFound
                return
            }
            _duplicatesState.value = DuplicatesState.Loading(0, photos.size)
            scanJob =
                viewModelScope.launch {
                    val outcome =
                        scanAlbumPhotos(
                            photos = photos,
                            thumbnailUrls = thumbnailUrls,
                            repositories = repositories,
                            analyzerFactory = { DHashAnalyzer(analysisDispatcher) },
                            onProgress = { processed ->
                                _duplicatesState.value = DuplicatesState.Loading(processed, photos.size)
                            },
                        )
                    ensureActive()
                    _duplicatesState.value =
                        when (outcome) {
                            is PhotoScanOutcome.Failed -> {
                                DuplicatesState.Error(outcome.error)
                            }

                            is PhotoScanOutcome.Completed -> {
                                val groups = DuplicateFinder.groupDuplicates(photos, outcome.results)
                                if (groups.isEmpty()) DuplicatesState.NoneFound else DuplicatesState.Found(groups)
                            }
                        }
                }
        }

        /**
         * Deletes [toDelete] photos found by the review via [launcher]. [duplicatesState] is
         * [DuplicatesState.Deleting] while it runs and [DuplicatesState.Idle] afterwards; the result
         * is published by the launcher (album detail outcome).
         * Returns `false` (and does nothing) while another photo deletion is still running.
         */
        fun deletePhotos(
            toDelete: List<RemotePhoto>,
            launcher: PhotoDeletionLauncher,
        ): Boolean =
            launcher.launchPhotoDeletion(
                toDelete = toDelete,
                onStart = { _duplicatesState.value = DuplicatesState.Deleting },
                // Only leave Deleting: never overwrite a newer scan started meanwhile.
                onFinish = { _duplicatesState.compareAndSet(DuplicatesState.Deleting, DuplicatesState.Idle) },
            )

        /**
         * Stops a running scan and discards scan results (the album they belong to is gone, e.g.
         * after logout or a server change): the state becomes [DuplicatesState.Idle] unless a
         * deletion is running — that one leaves [DuplicatesState.Deleting] by itself.
         */
        fun cancelScan() {
            scanJob?.cancel()
            _duplicatesState.update { if (it == DuplicatesState.Deleting) it else DuplicatesState.Idle }
        }

        fun resetDuplicatesState() {
            scanJob?.cancel()
            _duplicatesState.value = DuplicatesState.Idle
        }

        private class DHashAnalyzer(
            private val dispatcher: CoroutineDispatcher,
        ) : PhotoAnalyzer<Long> {
            override suspend fun analyze(bitmap: Bitmap): Long =
                withContext(dispatcher) { DuplicateFinder.computeDHash(bitmap) }
        }
    }
