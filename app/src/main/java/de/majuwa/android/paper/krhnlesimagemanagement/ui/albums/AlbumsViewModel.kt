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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class AlbumsState(
    val albums: List<RemoteAlbum> = emptyList(),
    val isLoading: Boolean = false,
    val error: AlbumsError? = null,
    val isDeletingAlbum: Boolean = false,
    val isRenamingAlbum: Boolean = false,
)

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

        private val _albumsState = MutableStateFlow(AlbumsState())
        val albumsState: StateFlow<AlbumsState> = _albumsState.asStateFlow()

        fun loadAlbums() {
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

        fun renameAlbum(
            album: RemoteAlbum,
            newName: String,
            onComplete: (success: Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                _albumsState.update { it.copy(isRenamingAlbum = true) }
                repos
                    .get()
                    .mapCatching { repository -> repository.renameAlbum(album, newName).getOrThrow() }
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
                    }.onFailure { t ->
                        if (t is CancellationException) throw t
                        _albumsState.update { it.copy(isRenamingAlbum = false) }
                        onComplete(false)
                    }
            }
        }

        private companion object {
            const val TAG = "AlbumsViewModel"
        }
    }
