package de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val WHILE_SUBSCRIBED_TIMEOUT_MS = 5_000L

data class UploadProgress(
    val current: Int,
    val total: Int,
)

data class PhotoGridUiState(
    val photosByDate: Map<LocalDate, List<Photo>> = emptyMap(),
    val selectedPhotoIds: Set<Long> = emptySet(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val showOccasionDialog: Boolean = false,
    val showNotConfiguredDialog: Boolean = false,
    val uploadProgress: UploadProgress? = null,
)

class PhotoGridViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val mediaRepository: MediaRepositoryContract =
            MediaRepository(application.contentResolver),
        private val credentialStore: CredentialRepository =
            CredentialStore(application),
        private val workManager: WorkManager =
            WorkManager.getInstance(application),
    ) : AndroidViewModel(application) {
        private val _uiState = MutableStateFlow(PhotoGridUiState())
        val uiState: StateFlow<PhotoGridUiState> = _uiState.asStateFlow()

        // Observe active upload progress so the UI can show it while the app is open.
        // Android 12+ suppresses foreground-service notifications while the app is visible;
        // observing WorkInfo here is the correct in-app substitute.
        val uploadProgress: StateFlow<UploadProgress?> =
            workManager
                .getWorkInfosByTagFlow("photo_upload")
                .map { infos ->
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    running?.progress?.let { data ->
                        val current = data.getInt(UploadWorker.KEY_PROGRESS, 0)
                        val total = data.getInt(UploadWorker.KEY_TOTAL, 0)
                        if (total > 0) UploadProgress(current, total) else null
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(WHILE_SUBSCRIBED_TIMEOUT_MS), null)

        val isConfigured: StateFlow<Boolean> =
            credentialStore.isConfigured
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(WHILE_SUBSCRIBED_TIMEOUT_MS), false)

        fun loadPhotos() {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, error = null) }
                val result = runCatching { mediaRepository.loadPhotos() }
                result
                    .onSuccess { photos ->
                        val grouped =
                            photos
                                .groupBy { it.dateTaken }
                                .toSortedMap(compareByDescending { it })
                        _uiState.update {
                            it.copy(photosByDate = grouped, isLoading = false)
                        }
                    }.onFailure { throwable ->
                        _uiState.update {
                            it.copy(isLoading = false, error = throwable.message ?: "Failed to load photos")
                        }
                    }
            }
        }

        fun togglePhotoSelection(photoId: Long) {
            _uiState.update { state ->
                val newSelection =
                    if (photoId in state.selectedPhotoIds) {
                        state.selectedPhotoIds - photoId
                    } else {
                        state.selectedPhotoIds + photoId
                    }
                state.copy(selectedPhotoIds = newSelection)
            }
        }

        fun toggleDateSelection(date: LocalDate) {
            _uiState.update { state ->
                val photosForDate = state.photosByDate[date] ?: return@update state
                val photoIds = photosForDate.map { it.id }.toSet()
                val allSelected = photoIds.all { it in state.selectedPhotoIds }
                val newSelection =
                    if (allSelected) {
                        state.selectedPhotoIds - photoIds
                    } else {
                        state.selectedPhotoIds + photoIds
                    }
                state.copy(selectedPhotoIds = newSelection)
            }
        }

        fun clearSelection() {
            _uiState.update { it.copy(selectedPhotoIds = emptySet()) }
        }

        /** Called when the FAB is tapped. Shows occasion dialog or not-configured dialog. */
        fun onUploadRequested() {
            if (isConfigured.value) {
                _uiState.update { it.copy(showOccasionDialog = true) }
            } else {
                _uiState.update { it.copy(showNotConfiguredDialog = true) }
            }
        }

        fun dismissOccasionDialog() {
            _uiState.update { it.copy(showOccasionDialog = false) }
        }

        fun dismissNotConfiguredDialog() {
            _uiState.update { it.copy(showNotConfiguredDialog = false) }
        }

        fun refreshPhotos() {
            viewModelScope.launch {
                _uiState.update { it.copy(isRefreshing = true, error = null) }
                val result = runCatching { mediaRepository.loadPhotos() }
                result
                    .onSuccess { photos ->
                        val grouped =
                            photos
                                .groupBy { it.dateTaken }
                                .toSortedMap(compareByDescending { it })
                        _uiState.update {
                            it.copy(photosByDate = grouped, isRefreshing = false)
                        }
                    }.onFailure { throwable ->
                        _uiState.update {
                            it.copy(isRefreshing = false, error = throwable.message ?: "Failed to load photos")
                        }
                    }
            }
        }

        fun getSelectedPhotos(): List<Photo> {
            val state = _uiState.value
            return state.photosByDate.values
                .flatten()
                .filter { it.id in state.selectedPhotoIds }
        }
    }
