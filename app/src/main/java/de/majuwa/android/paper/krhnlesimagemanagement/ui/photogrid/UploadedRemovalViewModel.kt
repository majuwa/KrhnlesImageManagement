package de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.DefaultRemovalCandidateChecker
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaTrasher
import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaStoreTrasher
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCandidate
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCandidateChecker
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCheck
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.chunkForTrash
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "UploadedRemoval"
private const val READ_TIMEOUT_MS = 3_000L
private const val CONFIRM_TIMEOUT_MS = 30_000L

/** A confirmation older than this repeats the full check (incl. the server) before anything is trashed. */
internal const val REMOVAL_CONFIRM_MAX_AGE_MS = 10 * 60 * 1_000L

/** One bounded one-shot read; a timeout or any failure is null (fail closed), cancellation is rethrown. */
private suspend fun <T> readBounded(read: suspend () -> T): T? =
    runCatching { withTimeoutOrNull(READ_TIMEOUT_MS) { read() } }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()

/** What the "Remove uploaded photos from device" action is doing; the screen renders it. */
sealed interface RemovalState {
    data object Idle : RemovalState

    /** Checking every recorded photo on the device and on the server. */
    data class Checking(
        val done: Int,
        val total: Int,
    ) : RemovalState

    /** [count] photos passed every check; waiting for the user's "Move N photos to trash?" answer. */
    data class Confirm(
        val count: Int,
    ) : RemovalState

    /** No photo is removable: explain which photos can be removed. */
    data object NothingToRemove : RemovalState

    /** No usable server configuration to check against. */
    data object NotConfigured : RemovalState

    /** The server failed or timed out too often in a row; the check stopped and every photo is kept. */
    data object ServerUnreachable : RemovalState

    /** Trash requests are running (the system dialog is up or the result is being confirmed). */
    data object Trashing : RemovalState
}

/** The one-shot result of a removal, consumed by the screen. */
sealed interface RemovalOutcome {
    /** [count] photos are confirmed in the system trash. */
    data class Trashed(
        val count: Int,
    ) : RemovalOutcome

    /** The user declined the system dialog; nothing was confirmed in the trash. */
    data object Cancelled : RemovalOutcome

    /** The request could not be created or nothing could be confirmed. */
    data object Failed : RemovalOutcome
}

/**
 * The "Remove uploaded photos from device" action (design D6). Plain state machine: [start] checks
 * (guarded one-shot job, cancellable), [confirm] re-reads the records and the configuration,
 * re-checks (locally, or fully when the confirmation is older than [REMOVAL_CONFIRM_MAX_AGE_MS]) and
 * exposes one trash request at a time through [pendingRequest]; the screen launches it with
 * `StartIntentSenderForResult` and reports [onTrashResult]. The reported count is confirmed by
 * re-querying the MediaStore and excludes photos that were already in the trash. Every job owns a
 * generation: a cancelled job's late writes (its cancellation handler, a progress callback) never
 * touch the state of a newer one. Never references another ViewModel.
 */
class UploadedRemovalViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val verifiedUploads: VerifiedUploadsRepositoryContract = VerifiedUploadsStore(application),
        private val credentials: CredentialRepository = CredentialStore(application),
        private val checker: RemovalCandidateChecker =
            DefaultRemovalCandidateChecker(application.contentResolver, application.packageName),
        private val trasher: LocalMediaTrasher = MediaStoreTrasher(application.contentResolver),
        /** Matches trashed uris against the records off the main thread; tests pass a deterministic one. */
        private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
        /** Monotonic clock (ms) that dates the confirmation. */
        private val clock: () -> Long = SystemClock::elapsedRealtime,
    ) : AndroidViewModel(application) {
        private val _state = MutableStateFlow<RemovalState>(RemovalState.Idle)
        val state: StateFlow<RemovalState> = _state.asStateFlow()

        private val _pendingRequest = MutableStateFlow<IntentSenderRequest?>(null)

        /** The next system trash request to launch; cleared by [onRequestLaunched]. */
        val pendingRequest: StateFlow<IntentSenderRequest?> = _pendingRequest.asStateFlow()

        private val _outcome = MutableStateFlow<RemovalOutcome?>(null)
        val outcome: StateFlow<RemovalOutcome?> = _outcome.asStateFlow()

        /** Bumped by every new job and by [cancel]; progress callbacks may read it from other threads. */
        private val generation = AtomicInteger(0)
        private var workJob: Job? = null
        private var candidates: List<RemovalCandidate> = emptyList()
        private var confirmShownAt = 0L
        private var remainingChunks: List<List<RemovalCandidate>> = emptyList()
        private var requestedUris: List<Uri> = emptyList()

        /** Candidates already in the trash before the first request: never counted, never forgotten. */
        private var alreadyTrashed: Set<Uri> = emptySet()
        private var awaitingResult = false

        private fun isCurrent(gen: Int): Boolean = generation.get() == gen

        private fun publish(
            gen: Int,
            state: RemovalState,
        ) {
            if (isCurrent(gen)) _state.value = state
        }

        private fun progress(
            gen: Int,
            done: Int,
            total: Int,
        ) {
            _state.update {
                if (isCurrent(gen) &&
                    it is RemovalState.Checking
                ) {
                    RemovalState.Checking(done, total)
                } else {
                    it
                }
            }
        }

        /** Starts the check; ignored unless the action is idle (and while a job runs). */
        fun start() {
            if (_state.value != RemovalState.Idle || workJob?.isActive == true) return
            val gen = generation.incrementAndGet()
            _state.value = RemovalState.Checking(0, 0)
            workJob =
                viewModelScope.launch {
                    try {
                        runCheck(gen)
                    } catch (e: CancellationException) {
                        publish(gen, RemovalState.Idle)
                        throw e
                    }
                }
        }

        private suspend fun runCheck(gen: Int) {
            val config = credentials.readValidConfig()
            if (config == null) {
                publish(gen, RemovalState.NotConfigured)
                return
            }
            val records = readBounded { verifiedUploads.records.first() }.orEmpty()
            if (records.isEmpty()) {
                publish(gen, RemovalState.NothingToRemove)
                return
            }
            publish(gen, RemovalState.Checking(0, records.size))
            val result = checker.checkOrKeepAll(records, config) { done, total -> progress(gen, done, total) }
            if (!isCurrent(gen)) return
            when {
                result.serverUnreachable -> {
                    _state.value = RemovalState.ServerUnreachable
                }

                result.candidates.isEmpty() -> {
                    _state.value = RemovalState.NothingToRemove
                }

                else -> {
                    candidates = result.candidates
                    confirmShownAt = clock()
                    _state.value = RemovalState.Confirm(result.candidates.size)
                }
            }
        }

        /** Cancels a running check, or dismisses a confirmation or an explanation; nothing is removed. */
        fun cancel() {
            when (_state.value) {
                is RemovalState.Checking -> {
                    generation.incrementAndGet()
                    workJob?.cancel()
                    workJob = null
                    candidates = emptyList()
                    _state.value = RemovalState.Idle
                }

                is RemovalState.Confirm,
                RemovalState.NothingToRemove,
                RemovalState.NotConfigured,
                RemovalState.ServerUnreachable,
                -> {
                    candidates = emptyList()
                    _state.value = RemovalState.Idle
                }

                // Nothing to cancel; a running trash request is not cancellable from here.
                RemovalState.Idle, RemovalState.Trashing -> {
                    return
                }
            }
        }

        /**
         * The user accepted "Move N photos to trash?": re-validate the candidates against the store and
         * the configuration, re-check them (fully when the confirmation is stale), then request the
         * first chunk.
         */
        fun confirm() {
            if (_state.value !is RemovalState.Confirm || workJob?.isActive == true) return
            val gen = generation.incrementAndGet()
            val pending = candidates
            candidates = emptyList()
            val stale = clock() - confirmShownAt > REMOVAL_CONFIRM_MAX_AGE_MS
            _state.value = if (stale) RemovalState.Checking(0, pending.size) else RemovalState.Trashing
            workJob =
                viewModelScope.launch {
                    try {
                        runConfirm(gen, pending, stale)
                    } catch (e: CancellationException) {
                        resetTrashing(gen)
                        throw e
                    }
                }
        }

        private suspend fun runConfirm(
            gen: Int,
            pending: List<RemovalCandidate>,
            stale: Boolean,
        ) {
            val config = credentials.readValidConfig()
            if (config == null) {
                publish(gen, RemovalState.NotConfigured)
                return
            }
            val records = readBounded { verifiedUploads.records.first() }
            if (records == null) {
                finishWithoutRequest(gen, RemovalOutcome.Failed)
                return
            }
            val still = withContext(computeDispatcher) { stillRecorded(pending, records, config) }
            val current =
                if (stale) {
                    val result =
                        checker.checkOrKeepAll(
                            still.map { it.record },
                            config,
                        ) { done, total -> progress(gen, done, total) }
                    if (result.serverUnreachable) {
                        publish(gen, RemovalState.ServerUnreachable)
                        return
                    }
                    result.candidates
                } else {
                    checker.recheckLocalOrNull(still)
                }
            if (current == null) {
                finishWithoutRequest(gen, RemovalOutcome.Failed)
                return
            }
            requestTrash(gen, current)
        }

        /** Snapshots what is already in the trash, then requests the rest chunk by chunk. */
        private suspend fun requestTrash(
            gen: Int,
            current: List<RemovalCandidate>,
        ) {
            if (current.isEmpty()) {
                publish(gen, RemovalState.NothingToRemove)
                return
            }
            publish(gen, RemovalState.Trashing)
            val already = trasher.trashedAmongBounded(current.map { it.uri })
            if (already == null) {
                finishWithoutRequest(gen, RemovalOutcome.Failed)
                return
            }
            val toRequest = current.filterNot { it.uri in already }
            if (toRequest.isEmpty() || !isCurrent(gen)) {
                publish(gen, RemovalState.NothingToRemove)
                return
            }
            alreadyTrashed = already
            remainingChunks = toRequest.chunkedForTrash()
            requestedUris = emptyList()
            launchNextChunk()
        }

        private fun finishWithoutRequest(
            gen: Int,
            outcome: RemovalOutcome,
        ) {
            if (!isCurrent(gen)) return
            _outcome.value = outcome
            _state.value = RemovalState.Idle
        }

        /** Builds the request for the next chunk, or finishes when there is none / it cannot be built. */
        private suspend fun launchNextChunk() {
            val chunk = remainingChunks.firstOrNull()
            if (chunk == null) {
                finish(stoppedByUser = false)
                return
            }
            remainingChunks = remainingChunks.drop(1)
            val uris = chunk.map { it.uri }
            val request =
                runCatching { trasher.createRequest(uris) }
                    .onFailure { if (it is CancellationException) throw it }
                    .onFailure { Log.w(TAG, "Trash request failed: ${it.javaClass.simpleName}") }
                    .getOrNull()
            if (request == null) {
                finish(stoppedByUser = false)
            } else {
                requestedUris = requestedUris + uris
                awaitingResult = true
                _pendingRequest.value = request
            }
        }

        /** The screen started the system dialog; the result arrives through [onTrashResult]. */
        fun onRequestLaunched() {
            _pendingRequest.value = null
        }

        /** The result of a system trash dialog; stale or unexpected results are ignored. */
        fun onTrashResult(resultCode: Int) {
            if (_state.value != RemovalState.Trashing || !awaitingResult) return
            awaitingResult = false
            _pendingRequest.value = null
            val gen = generation.get()
            workJob =
                viewModelScope.launch {
                    try {
                        if (resultCode == Activity.RESULT_OK) {
                            launchNextChunk()
                        } else {
                            // Declined: no further chunk is requested; what is already trashed still counts.
                            remainingChunks = emptyList()
                            finish(stoppedByUser = true)
                        }
                    } catch (e: CancellationException) {
                        resetTrashing(gen)
                        throw e
                    }
                }
        }

        /**
         * Confirms the result through the MediaStore, forgets the photos this action trashed and
         * publishes the outcome. Photos that were in the trash before the request are not counted.
         */
        private suspend fun finish(stoppedByUser: Boolean) {
            val trashed = trasher.trashedAmongBounded(requestedUris)?.minus(alreadyTrashed)
            requestedUris = emptyList()
            alreadyTrashed = emptySet()
            if (!trashed.isNullOrEmpty()) forgetTrashed(trashed)
            _outcome.value =
                when {
                    !trashed.isNullOrEmpty() -> RemovalOutcome.Trashed(trashed.size)
                    stoppedByUser -> RemovalOutcome.Cancelled
                    else -> RemovalOutcome.Failed
                }
            _state.value = RemovalState.Idle
        }

        /** Forgets the records of [trashed] (bounded read, matched off the main thread). */
        private suspend fun forgetTrashed(trashed: Set<Uri>) {
            val records = readBounded { verifiedUploads.records.first() }
            if (records == null) {
                Log.w(TAG, "Trashed photos not forgotten: records unreadable")
                return
            }
            val ids =
                withContext(computeDispatcher) {
                    val uris = trashed.mapTo(HashSet()) { it.toString() }
                    records.filter { it.uri in uris }.mapTo(HashSet()) { it.id }
                }
            runCatching { verifiedUploads.forget(ids) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(TAG, "Forgetting trashed photos failed: ${it.javaClass.simpleName}") }
        }

        /** Marks the outcome as shown. */
        fun consumeOutcome() {
            _outcome.value = null
        }

        private fun resetTrashing(gen: Int) {
            if (!isCurrent(gen)) return
            awaitingResult = false
            remainingChunks = emptyList()
            requestedUris = emptyList()
            alreadyTrashed = emptySet()
            _pendingRequest.value = null
            if (_state.value == RemovalState.Trashing || _state.value is RemovalState.Checking) {
                _state.value = RemovalState.Idle
            }
        }

        override fun onCleared() {
            workJob?.cancel()
        }
    }

/** The configured server, or null when it is missing, invalid or unreadable. */
private suspend fun CredentialRepository.readValidConfig(): WebDavConfig? =
    readBounded { webDavConfig.first() }?.takeIf { it.isValid }

/** The full check; a failing or throwing check keeps every photo (never a partial list). */
private suspend fun RemovalCandidateChecker.checkOrKeepAll(
    records: List<VerifiedUpload>,
    config: WebDavConfig,
    onProgress: (done: Int, total: Int) -> Unit,
): RemovalCheck =
    runCatching { check(records, config, onProgress) }
        .getOrElse {
            if (it is CancellationException) throw it
            Log.w(TAG, "Removal check failed: ${it.javaClass.simpleName}")
            RemovalCheck(emptyList())
        }

/** The local re-check; null when it fails (nothing is requested then). */
private suspend fun RemovalCandidateChecker.recheckLocalOrNull(
    candidates: List<RemovalCandidate>,
): List<RemovalCandidate>? =
    runCatching { recheckLocal(candidates) }
        .onFailure { if (it is CancellationException) throw it }
        .onFailure { Log.w(TAG, "Local recheck failed: ${it.javaClass.simpleName}") }
        .getOrNull()

/**
 * The candidates whose record is still stored unchanged and that belong to the configured server
 * (a logout, an account change, a new upload or a forget meanwhile drops them).
 */
private fun stillRecorded(
    pending: List<RemovalCandidate>,
    records: List<VerifiedUpload>,
    config: WebDavConfig,
): List<RemovalCandidate> {
    val byId = records.associateBy { it.id }
    return pending.filter { byId[it.record.id] == it.record && it.record.serverUrl == config.url }
}

/** The confirmed-trashed subset of [uris], bounded; null when it cannot be confirmed. */
private suspend fun LocalMediaTrasher.trashedAmongBounded(uris: List<Uri>): Set<Uri>? =
    runCatching { withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { trashedAmong(uris) } }
        .onFailure { if (it is CancellationException) throw it }
        .onFailure { Log.w(TAG, "Trash confirmation failed: ${it.javaClass.simpleName}") }
        .getOrNull()

/** In request order, in chunks of at most `MAX_TRASH_REQUEST_URIS`. */
private fun List<RemovalCandidate>.chunkedForTrash(): List<List<RemovalCandidate>> {
    val byUri = associateBy { it.uri }
    return chunkForTrash(map { it.uri }).map { chunk -> chunk.mapNotNull(byUri::get) }
}
