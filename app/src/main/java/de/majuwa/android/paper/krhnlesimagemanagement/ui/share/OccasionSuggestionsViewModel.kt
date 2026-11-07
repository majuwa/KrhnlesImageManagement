package de.majuwa.android.paper.krhnlesimagemanagement.ui.share

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Time bounds of [OccasionSuggestionsViewModel]. */
data class OccasionSuggestionTimeouts(
    val historyReadMs: Long = 3_000L,
    val remoteMs: Long = 10_000L,
    val remoteMaxAgeMs: Long = 60_000L,
)

/**
 * Candidates for the occasion dialog: recent occasions from the upload history plus, best-effort,
 * the folders of the configured server. Every failure silently yields fewer suggestions; nothing
 * here ever reaches the UI as text.
 */
class OccasionSuggestionsViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val credentialRepo: CredentialRepository = CredentialStore(application),
        trustedCertificates: TrustedCertificateRepository = TrustedCertificateStore(application),
        private val history: UploadHistoryRepository = UploadHistoryStore(application),
        repoFactory: (WebDavConfig, PinnedCertificate?) -> AlbumsRepositoryContract =
            { config, pin -> AlbumsRepository(config, pin) },
        private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
        private val clock: () -> Long = SystemClock::elapsedRealtime,
        private val timeouts: OccasionSuggestionTimeouts = OccasionSuggestionTimeouts(),
    ) : AndroidViewModel(application) {
        private val repos = AlbumsRepositoryProvider(credentialRepo, trustedCertificates, repoFactory = repoFactory)

        private val _candidates = MutableStateFlow<List<String>>(emptyList())

        /** Merged, query-independent candidates (recent first, then server folders). */
        val candidates: StateFlow<List<String>> = _candidates.asStateFlow()

        private var recent: List<String> = emptyList()
        private var remote: List<String> = emptyList()
        private var remoteSource: AlbumsRepositoryContract? = null
        private var remoteLoadedAt = 0L
        private var loadJob: Job? = null
        private var generation = 0

        init {
            viewModelScope.launch {
                credentialRepo.webDavConfig
                    .map { Triple(it.url, it.username, it.normalizedBaseFolder) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { onConfigurationChanged() }
            }
        }

        override fun onCleared() {
            repos.close()
        }

        private fun onConfigurationChanged() {
            loadJob?.cancel()
            loadJob = null
            generation++
            clearRemote()
        }

        private fun clearRemote() {
            remote = emptyList()
            remoteSource = null
            _candidates.value = mergeOccasionCandidates(recent, emptyList())
        }

        /** Loads the suggestions for one dialog opening; ignored while a load runs. */
        fun load() {
            if (loadJob?.isActive == true) return
            val gen = ++generation
            loadJob =
                viewModelScope.launch {
                    loadRecent(gen)
                    loadRemote(gen)
                }
        }

        private fun isCurrent(gen: Int): Boolean = gen == generation

        private suspend fun readHistory(): List<UploadHistoryEntry> =
            try {
                withTimeoutOrNull(timeouts.historyReadMs) {
                    withContext(backgroundDispatcher) { history.entries.first() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "History read failed: ${e.javaClass.simpleName}")
                null
            } ?: emptyList()

        private suspend fun loadRecent(gen: Int) {
            val entries = readHistory()
            val names = withContext(backgroundDispatcher) { recentOccasionNames(entries) }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(gen)) return
            recent = names
            publish()
        }

        private suspend fun publish() {
            val r = recent
            val m = remote
            val merged = withContext(backgroundDispatcher) { mergeOccasionCandidates(r, m) }
            currentCoroutineContext().ensureActive()
            _candidates.value = merged
        }

        private suspend fun loadRemote(gen: Int) {
            try {
                withTimeoutOrNull(timeouts.remoteMs) { remoteStep(gen) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Remote suggestions failed: ${e.javaClass.simpleName}")
            }
        }

        private suspend fun remoteStep(gen: Int) {
            val repo =
                repos.get().getOrElse {
                    if (isCurrent(gen)) clearRemote()
                    return
                }
            if (!isCurrent(gen)) return
            if (repo !== remoteSource) {
                clearRemote()
            } else if (clock() - remoteLoadedAt < timeouts.remoteMaxAgeMs) {
                return
            }
            val albums =
                repo.listAlbums().getOrElse {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "Album listing failed: ${it.javaClass.simpleName}")
                    return
                }
            val names = withContext(backgroundDispatcher) { albums.mapNotNull(repo::folderNameOf) }
            val sameRepo = repos.get().getOrNull() === repo
            currentCoroutineContext().ensureActive()
            if (!sameRepo || !isCurrent(gen)) return
            remote = names
            remoteSource = repo
            remoteLoadedAt = clock()
            publish()
        }

        private companion object {
            const val TAG = "OccasionSuggestionsVM"
        }
    }
