package de.majuwa.android.paper.krhnlesimagemanagement.ui.share

import android.app.Application
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.KrhnlesApplication
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.upload.ShareStaging
import de.majuwa.android.paper.krhnlesimagemanagement.upload.SkipReason
import de.majuwa.android.paper.krhnlesimagemanagement.upload.StageResult
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName
import de.majuwa.android.paper.krhnlesimagemanagement.util.parseSharedPhotos
import de.majuwa.android.paper.krhnlesimagemanagement.util.resolveSharedPhotos
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "ShareViewModel"

/** A config read that takes longer counts as configured (the worker reports a missing server). */
internal const val SHARE_CONFIG_TIMEOUT_MILLIS = 3_000L

/** What the share activity shows. */
sealed interface ShareUiState {
    /** Reading the configuration and the shared items' metadata (progress + Cancel). */
    data object Preparing : ShareUiState

    /** No valid server configuration: nothing is copied, the user is asked to set it up. */
    data object NotConfigured : ShareUiState

    /** The occasion dialog for [photoCount] photos; [skipped] / [notIncluded] items were dropped. */
    data class Ready(
        val photoCount: Int,
        val skipped: Int,
        val notIncluded: Int,
    ) : ShareUiState

    /** Copying photo [current] of [total] into the staging folder. */
    data class Copying(
        val current: Int,
        val total: Int,
    ) : ShareUiState

    /** The upload was handed to the enqueuer. */
    data object Done : ShareUiState

    /** The share stopped with [error]; nothing was queued. */
    data class Error(
        val error: ShareError,
    ) : ShareUiState

    /** The user cancelled; nothing was queued and the batch's staged files are deleted. */
    data object Cancelled : ShareUiState
}

enum class ShareError {
    /** The share carried no supported photo. */
    NoPhotos,

    /** Copying failed: no space left or a write error. */
    CopyFailed,

    /** Every photo was skipped while copying (unreadable, too large); nothing was queued. */
    NothingCopied,
}

/**
 * One-shot request to finish the activity after showing [error] or the notices for photos left
 * out while copying: [skipped] (unreadable or larger than the per-file cap) and [overShareLimit]
 * (did not fit into the per-share cap).
 */
data class ShareFinish(
    val error: ShareError? = null,
    val skipped: Int = 0,
    val overShareLimit: Int = 0,
)

private enum class Phase { IDLE, PREPARING, NOT_CONFIGURED, READY, COPYING, DONE, FAILED, CANCELLED }

/**
 * State of one share-target flow. The ViewModel outlives rotation, so the copy runs once.
 *
 * [start] reads the share (once per instance), [confirm] copies the photos into [ShareStaging]
 * while the sender's grant is still valid and then enqueues one upload through the process-scoped
 * enqueuer; re-entry is ignored (the phase acts as a latch). Cancel, Back, a failure and the
 * ViewModel being cleared delete the batch's staged files; an enqueue failure deletes them too.
 * Cancel never waits for a provider call: the copy is cancelled and `Cancelled` published at once,
 * the copy job's `finally` deletes the batch's files (an abandoned call deletes its own file).
 */
class ShareViewModel internal constructor(
    application: Application,
    private val credentialRepository: CredentialRepository,
    private val staging: ShareStaging,
    private val enqueue: (UploadBatch, suspend () -> Unit) -> Unit,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val configTimeoutMillis: Long = SHARE_CONFIG_TIMEOUT_MILLIS,
) : AndroidViewModel(application) {
    /** Used by the default `AndroidViewModelFactory`. */
    constructor(application: Application) : this(
        application,
        CredentialStore(application),
        ShareStaging(application),
        { batch, onFailure ->
            (application as KrhnlesApplication).uploadEnqueuer.enqueue(listOf(batch), onFailure)
        },
    )

    private val _uiState = MutableStateFlow<ShareUiState>(ShareUiState.Preparing)
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    private val _finish = MutableStateFlow<ShareFinish?>(null)

    /** One-shot: set when the activity should finish, cleared by [consumeFinish]. */
    val finish: StateFlow<ShareFinish?> = _finish.asStateFlow()

    private val phase = AtomicReference(Phase.IDLE)
    private var photos: List<Photo> = emptyList()
    private var prepareJob: Job? = null
    private var copyJob: Job? = null

    /** Reads the share [intent]; only the first call per ViewModel has an effect. */
    fun start(intent: Intent) {
        if (!phase.compareAndSet(Phase.IDLE, Phase.PREPARING)) return
        val app = getApplication<Application>()
        val items = parseSharedPhotos(intent, app.packageName)
        if (items.uris.isEmpty()) {
            stop(Phase.PREPARING, ShareError.NoPhotos)
            return
        }
        val intentType = intent.type
        viewModelScope.launch(workDispatcher) { staging.sweep() }
        prepareJob =
            viewModelScope.launch {
                if (!isConfigured()) {
                    if (phase.compareAndSet(Phase.PREPARING, Phase.NOT_CONFIGURED)) {
                        _uiState.value = ShareUiState.NotConfigured
                    }
                    return@launch
                }
                val resolved =
                    resolveSharedPhotos(items.uris, intentType, app.contentResolver, dispatcher = workDispatcher)
                if (resolved.photos.isEmpty()) {
                    stop(Phase.PREPARING, ShareError.NoPhotos)
                    return@launch
                }
                photos = resolved.photos
                if (phase.compareAndSet(Phase.PREPARING, Phase.READY)) {
                    _uiState.value =
                        ShareUiState.Ready(
                            photoCount = resolved.photos.size,
                            skipped = items.rejected + resolved.rejected,
                            notIncluded = items.truncated,
                        )
                }
            }
    }

    private suspend fun isConfigured(): Boolean =
        try {
            withTimeoutOrNull(configTimeoutMillis) { credentialRepository.webDavConfig.first().isValid } ?: true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Config read failed: ${e.javaClass.simpleName}")
            true
        }

    /** Copies the photos and enqueues one upload into [occasionName]; ignored unless the dialog is shown. */
    fun confirm(occasionName: String) {
        if (!isValidOccasionName(occasionName)) return
        if (!phase.compareAndSet(Phase.READY, Phase.COPYING)) return
        val batch = photos
        _uiState.value = ShareUiState.Copying(current = 0, total = batch.size)
        copyJob = viewModelScope.launch(workDispatcher) { copyAndEnqueue(occasionName.trim(), batch) }
    }

    private suspend fun copyAndEnqueue(
        occasionName: String,
        batch: List<Photo>,
    ) {
        val staged = mutableListOf<Photo>()
        var handedOff = false
        try {
            val budget = staging.newBudget() ?: return stop(Phase.COPYING, ShareError.CopyFailed)
            val skips = mutableListOf<SkipReason>()
            for ((index, photo) in batch.withIndex()) {
                _uiState.value = ShareUiState.Copying(current = index + 1, total = batch.size)
                val resolver = getApplication<Application>().contentResolver
                // No suspension between stage() returning and recording the name: no orphan.
                when (val result = staging.stage(resolver, photo.uri, photo.size, budget)) {
                    is StageResult.Staged -> {
                        staged += photo.copy(stagedName = result.name)
                    }

                    is StageResult.Skipped -> {
                        skips += result.reason
                    }

                    StageResult.Failed -> {
                        return stop(Phase.COPYING, ShareError.CopyFailed)
                    }
                }
            }
            if (staged.isEmpty()) return stop(Phase.COPYING, ShareError.NothingCopied)
            currentCoroutineContext().ensureActive()
            // Cancel may race on the main thread: whoever moves the phase first wins.
            if (!phase.compareAndSet(Phase.COPYING, Phase.DONE)) return
            handedOff = true
            enqueue(UploadBatch.Occasion(occasionName, staged), enqueueFailureHandler(staged.stagedNames()))
            _uiState.value = ShareUiState.Done
            val overShareLimit = skips.count { it == SkipReason.ShareLimit }
            _finish.value = ShareFinish(skipped = skips.size - overShareLimit, overShareLimit = overShareLimit)
        } finally {
            if (!handedOff) staging.delete(staged.stagedNames())
        }
    }

    /** Runs in the application scope if enqueuing fails: removes the copies and tells the user. */
    private fun enqueueFailureHandler(names: List<String>): suspend () -> Unit {
        val staging = staging
        val app = getApplication<Application>()
        return {
            withContext(NonCancellable + Dispatchers.IO) { staging.delete(names) }
            withContext(Dispatchers.Main) {
                Toast.makeText(app, R.string.share_enqueue_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stop(
        from: Phase,
        error: ShareError,
    ) {
        if (!phase.compareAndSet(from, Phase.FAILED)) return
        _uiState.value = ShareUiState.Error(error)
        _finish.value = ShareFinish(error = error)
    }

    /**
     * Cancel / Back: stops a running preparation or copy and finishes without uploading. Never
     * waits for the cancelled job (a provider call may block): the copy job's `finally` deletes the
     * batch's staged files once it has unwound.
     */
    fun cancel() {
        val previous = phase.getAndUpdate { if (it in CANCELLABLE) Phase.CANCELLED else it }
        if (previous !in CANCELLABLE) return // Done / failed / cancelled: the finish is already requested.
        prepareJob?.cancel()
        copyJob?.cancel()
        publishCancelled()
    }

    private fun publishCancelled() {
        _uiState.value = ShareUiState.Cancelled
        _finish.value = ShareFinish()
    }

    fun consumeFinish() {
        _finish.value = null
    }

    override fun onCleared() {
        // viewModelScope is already cancelled; the copy's finally deletes the batch's staged files.
        copyJob?.cancel()
    }

    private companion object {
        val CANCELLABLE = setOf(Phase.IDLE, Phase.PREPARING, Phase.NOT_CONFIGURED, Phase.READY, Phase.COPYING)
    }
}

private fun List<Photo>.stagedNames(): List<String> = mapNotNull { it.stagedName }
