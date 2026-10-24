package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

data class AlbumsState(
    val albums: List<RemoteAlbum> = emptyList(),
    val isLoading: Boolean = false,
    val error: AlbumsError? = null,
    val isDeletingAlbum: Boolean = false,
    val isRenamingAlbum: Boolean = false,
)

/** Result of one rename, reported once to the Albums screen. */
sealed interface RenameOutcome {
    data class Success(
        val newName: String,
    ) : RenameOutcome

    data class Failure(
        val error: AlbumsError,
    ) : RenameOutcome
}

/**
 * Albums tab: the list of remote albums, renaming and deleting whole albums.
 * Scoped to the Albums tab back-stack entry. The photos of one album live in
 * [AlbumDetailViewModel]; the reviews in [DuplicatesViewModel] / [BlurViewModel].
 */
class AlbumsViewModel
    @JvmOverloads
    constructor(
        application: Application,
        credentialRepo: CredentialRepository =
            CredentialStore(application),
        repoFactory: (WebDavConfig) -> AlbumsRepositoryContract =
            { config -> AlbumsRepository(config) },
    ) : AndroidViewModel(application) {
        private val repos = AlbumsRepositoryProvider(credentialRepo, repoFactory = repoFactory)

        override fun onCleared() {
            repos.close()
        }

        private val _albumsState = MutableStateFlow(AlbumsState())
        val albumsState: StateFlow<AlbumsState> = _albumsState.asStateFlow()

        private val _renameOutcome = MutableStateFlow<RenameOutcome?>(null)

        /** One-shot result of the last rename; consumed by the screen via [consumeRenameOutcome]. */
        val renameOutcome: StateFlow<RenameOutcome?> = _renameOutcome.asStateFlow()

        private var renameJob: Job? = null

        /** Reloads the album list; ignored while a rename runs (a reload would race its in-place update). */
        fun loadAlbums() {
            if (renameJob?.isActive == true) return
            startLoad()
        }

        private fun startLoad() {
            viewModelScope.launch {
                _albumsState.update { it.copy(isLoading = true, error = null) }
                repos
                    .get()
                    .mapCatching { repository -> repository.listAlbums().getOrThrow() }
                    .onSuccess { albums ->
                        _albumsState.update { it.copy(albums = albums, isLoading = false) }
                    }.onFailure { t ->
                        if (t is CancellationException) throw t
                        // Type only: exception messages may contain server details.
                        Log.w(TAG, "Failed to load albums: ${t.javaClass.simpleName}")
                        _albumsState.update { it.copy(isLoading = false, error = t.toAlbumsError()) }
                    }
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
                repos
                    .get()
                    .mapCatching { repository -> repository.deleteAlbum(album).getOrThrow() }
                    .onSuccess {
                        _albumsState.update { s ->
                            s.copy(
                                albums = s.albums.filter { it.href != album.href },
                                isDeletingAlbum = false,
                            )
                        }
                        onComplete(true)
                    }.onFailure { t ->
                        if (t is CancellationException) throw t
                        _albumsState.update { it.copy(isDeletingAlbum = false) }
                        onComplete(false)
                    }
            }
        }

        /**
         * Renames [album] to [newName]. A guarded one-shot: ignored while a rename runs, bounded by
         * [RENAME_TIMEOUT_MILLIS] (a timeout cancels the call, reports [AlbumsError.RenameTimedOut] and
         * reloads the list because the server may still have completed the MOVE; an unexpected 2xx
         * ([AlbumsError.RenamePartial]) reloads likewise), every failure becomes
         * a [RenameOutcome], and the busy flag is always released. The result is published once via
         * [renameOutcome]; the UI calls [consumeRenameOutcome].
         */
        fun renameAlbum(
            album: RemoteAlbum,
            newName: String,
        ) {
            if (renameJob?.isActive == true) return
            if (!isValidOccasionName(newName)) {
                _renameOutcome.value = RenameOutcome.Failure(AlbumsError.InvalidName)
                return
            }
            renameJob =
                viewModelScope.launch {
                    _albumsState.update { it.copy(isRenamingAlbum = true) }
                    var reload = false
                    try {
                        val result =
                            withTimeoutOrNull(RENAME_TIMEOUT_MILLIS) {
                                runCatching {
                                    repos
                                        .get()
                                        .getOrThrow()
                                        .renameAlbum(album, newName)
                                        .getOrThrow()
                                }.onFailure { if (it is CancellationException) throw it }
                            }
                        ensureActive()
                        if (result == null) {
                            reload = true
                            _renameOutcome.value = RenameOutcome.Failure(AlbumsError.RenameTimedOut)
                        } else {
                            result
                                .onSuccess { renamed ->
                                    _albumsState.update { state ->
                                        state.copy(
                                            albums =
                                                state.albums.mapNotNull { existing ->
                                                    when {
                                                        existing.href == album.href -> renamed

                                                        // A server that overwrote: avoids duplicate grid keys.
                                                        existing.href == renamed.href -> null

                                                        else -> existing
                                                    }
                                                },
                                        )
                                    }
                                    _renameOutcome.value = RenameOutcome.Success(renamed.displayName)
                                }.onFailure { t ->
                                    // Type only: exception messages may contain server details.
                                    Log.w(TAG, "Failed to rename album: ${t.javaClass.simpleName}")
                                    val error = t.toRenameAlbumError()
                                    // The server may have changed something: show the real state.
                                    reload = error == AlbumsError.RenamePartial
                                    _renameOutcome.value = RenameOutcome.Failure(error)
                                }
                        }
                    } finally {
                        _albumsState.update { it.copy(isRenamingAlbum = false) }
                    }
                    if (reload) startLoad()
                }
        }

        /** Marks the current [renameOutcome] as shown. */
        fun consumeRenameOutcome() {
            _renameOutcome.value = null
        }

        companion object {
            /** Upper bound for one rename (repository lookup + MOVE). */
            const val RENAME_TIMEOUT_MILLIS = 30_000L
            private const val TAG = "AlbumsViewModel"
        }
    }
