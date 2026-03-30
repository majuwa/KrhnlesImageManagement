package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
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
)

data class AlbumDetailState(
    val albumName: String = "",
    val photos: List<RemotePhoto> = emptyList(),
    val thumbnailUrls: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AlbumsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _albumsState = MutableStateFlow(AlbumsState())
    val albumsState: StateFlow<AlbumsState> = _albumsState.asStateFlow()

    private val _detailState = MutableStateFlow(AlbumDetailState())
    val detailState: StateFlow<AlbumDetailState> = _detailState.asStateFlow()

    // Cached repo instance — recreated only when loadAlbums() is called.
    private var repo: AlbumsRepository? = null

    private suspend fun getRepo(): AlbumsRepository =
        repo ?: run {
            val config = CredentialStore(getApplication<Application>()).webDavConfig.first()
            AlbumsRepository(config).also { repo = it }
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
        val albumName = albumHref.trimEnd('/').substringAfterLast('/')
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
}
