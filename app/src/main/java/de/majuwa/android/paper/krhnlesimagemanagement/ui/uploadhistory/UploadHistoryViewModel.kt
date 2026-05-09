package de.majuwa.android.paper.krhnlesimagemanagement.ui.uploadhistory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val WHILE_SUBSCRIBED_TIMEOUT_MS = 5_000L

class UploadHistoryViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val uploadHistoryRepository: UploadHistoryRepository =
            UploadHistoryStore(application),
    ) : AndroidViewModel(application) {
        val entries: StateFlow<List<UploadHistoryEntry>> =
            uploadHistoryRepository.entries.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(WHILE_SUBSCRIBED_TIMEOUT_MS),
                initialValue = emptyList(),
            )

        fun removeEntry(entryId: Long) {
            viewModelScope.launch {
                uploadHistoryRepository.removeEntry(entryId)
            }
        }

        fun clearAll() {
            viewModelScope.launch {
                uploadHistoryRepository.clearAll()
            }
        }
    }
