package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositorySource
import de.majuwa.android.paper.krhnlesimagemanagement.model.BlurSensitivity
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurAnalyzer
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurScorer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        val error: AlbumsError,
    ) : BlurState

    data object Deleting : BlurState
}

/**
 * Blur review: scans the photos of an album for blurry images. Scoped to the blur review
 * back-stack entry, so the scan stops and its state is discarded when the review is left.
 * The album's photos, thumbnail URLs and repository come from [AlbumDetailViewModel] (passed per
 * call), as is the [BlurSensitivity] source (read once per scan); deletions run through its
 * [PhotoDeletionLauncher]. [blurScorerFactory] creates one scorer per scan (closed afterwards);
 * scoring runs on [analysisDispatcher].
 */
class BlurViewModel
    @JvmOverloads
    constructor(
        private val blurScorerFactory: () -> BlurScorer = { BlurAnalyzer() },
        private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : ViewModel() {
        private val _blurState = MutableStateFlow<BlurState>(BlurState.Idle)
        val blurState: StateFlow<BlurState> = _blurState.asStateFlow()

        /** The running scan; cancelled by [cancelScan], [resetBlurState] and the next scan. */
        private var scanJob: Job? = null

        /**
         * Starts the scan of [photos] for blurry images — only from [BlurState.Idle], so a screen
         * recreated (e.g. after rotation) while the scan runs, its results are shown or a
         * deletion runs keeps that state. Each photo's thumbnail comes from [thumbnailUrls] or,
         * if missing, the repository's thumbnail URL. The caller passes only a fully loaded album.
         */
        fun findBlurryPhotos(
            photos: List<RemotePhoto>,
            thumbnailUrls: Map<String, String>,
            repositories: AlbumsRepositorySource,
            sensitivity: Flow<BlurSensitivity>,
        ) {
            if (_blurState.value != BlurState.Idle) return
            startScan(photos, thumbnailUrls, repositories, sensitivity)
        }

        /**
         * Restarts the scan after it failed; ignored unless the state is [BlurState.Error] and
         * [photos] is not empty (an empty list means the album is not loaded, so the error is
         * kept instead of reporting "no blurry photos").
         */
        fun retryFindBlurryPhotos(
            photos: List<RemotePhoto>,
            thumbnailUrls: Map<String, String>,
            repositories: AlbumsRepositorySource,
            sensitivity: Flow<BlurSensitivity>,
        ) {
            if (_blurState.value !is BlurState.Error || photos.isEmpty()) return
            startScan(photos, thumbnailUrls, repositories, sensitivity)
        }

        private fun startScan(
            photos: List<RemotePhoto>,
            thumbnailUrls: Map<String, String>,
            repositories: AlbumsRepositorySource,
            sensitivitySource: Flow<BlurSensitivity>,
        ) {
            scanJob?.cancel()
            if (photos.isEmpty()) {
                _blurState.value = BlurState.NoneFound
                return
            }
            _blurState.value = BlurState.Scanning(0, photos.size)
            scanJob =
                viewModelScope.launch {
                    // Read after Scanning is published; a later change only affects the next scan.
                    val sensitivity = readSensitivity(sensitivitySource)
                    val outcome =
                        scanAlbumPhotos(
                            photos = photos,
                            thumbnailUrls = thumbnailUrls,
                            repositories = repositories,
                            analyzerFactory = { ScorerAnalyzer(blurScorerFactory(), analysisDispatcher) },
                            onProgress = { processed -> _blurState.value = BlurState.Scanning(processed, photos.size) },
                        )
                    ensureActive()
                    _blurState.value =
                        when (outcome) {
                            is PhotoScanOutcome.Failed -> {
                                BlurState.Error(outcome.error)
                            }

                            is PhotoScanOutcome.Completed -> {
                                val blurryScores = outcome.results.filterValues(sensitivity::isBlurry)
                                val blurry = photos.filter { it.href in blurryScores }
                                if (blurry.isEmpty()) BlurState.NoneFound else BlurState.Found(blurry, blurryScores)
                            }
                        }
                }
        }

        /**
         * Deletes [toDelete] photos found by the review via [launcher]. [blurState] is
         * [BlurState.Deleting] while it runs and [BlurState.Idle] afterwards; the result is
         * published by the launcher (album detail outcome).
         * Returns `false` (and does nothing) while another photo deletion is still running.
         */
        fun deleteBlurryPhotos(
            toDelete: List<RemotePhoto>,
            launcher: PhotoDeletionLauncher,
        ): Boolean =
            launcher.launchPhotoDeletion(
                toDelete = toDelete,
                onStart = { _blurState.value = BlurState.Deleting },
                // Only leave Deleting: never overwrite a newer scan started meanwhile.
                onFinish = { _blurState.compareAndSet(BlurState.Deleting, BlurState.Idle) },
            )

        /**
         * Stops a running scan and discards scan results (the album they belong to is gone, e.g.
         * after logout or a server change): the state becomes [BlurState.Idle] unless a deletion
         * is running — that one leaves [BlurState.Deleting] by itself.
         */
        fun cancelScan() {
            scanJob?.cancel()
            _blurState.update { if (it == BlurState.Deleting) it else BlurState.Idle }
        }

        fun resetBlurState() {
            scanJob?.cancel()
            _blurState.value = BlurState.Idle
        }

        /** The current level; [BlurSensitivity.DEFAULT] on timeout or failure (throws only on cancellation). */
        private suspend fun readSensitivity(source: Flow<BlurSensitivity>): BlurSensitivity =
            runCatching { withTimeoutOrNull(SENSITIVITY_TIMEOUT_MS) { source.first() } }
                .onFailure {
                    // Our own cancellation must propagate; a CancellationException thrown by the source
                    // while this job is still active is just a failed read.
                    currentCoroutineContext().ensureActive()
                }.getOrNull() ?: BlurSensitivity.DEFAULT

        private companion object {
            const val SENSITIVITY_TIMEOUT_MS = 3_000L
        }

        private class ScorerAnalyzer(
            private val scorer: BlurScorer,
            private val dispatcher: CoroutineDispatcher,
        ) : PhotoAnalyzer<Double> {
            override suspend fun analyze(bitmap: Bitmap): Double =
                withContext(dispatcher) { scorer.computeBlurScore(bitmap) }

            override fun close() = scorer.close()
        }
    }
