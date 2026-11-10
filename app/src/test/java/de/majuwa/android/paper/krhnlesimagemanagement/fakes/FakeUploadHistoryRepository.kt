package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** In-memory [UploadHistoryRepository] with settable entries, an optional hang / failure and a write counter. */
class FakeUploadHistoryRepository(
    initial: List<UploadHistoryEntry> = emptyList(),
) : UploadHistoryRepository {
    private val state = MutableStateFlow(initial)

    /** Reads of [entries] never emit (until cancelled). */
    var hang = false

    /** Reads of [entries] throw this. */
    var failure: Throwable? = null

    /** Number of [addEntry] / [removeEntry] / [clearAll] calls. */
    var writes = 0
        private set

    fun setEntries(entries: List<UploadHistoryEntry>) {
        state.value = entries
    }

    override val entries: Flow<List<UploadHistoryEntry>> =
        flow {
            failure?.let { throw it }
            if (hang) awaitCancellation()
            emit(state.value)
        }

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        writes++
        state.value = listOf(entry) + state.value
    }

    override suspend fun removeEntry(entryId: Long) {
        writes++
        state.value = state.value.filterNot { it.id == entryId }
    }

    override suspend fun clearAll() {
        writes++
        state.value = emptyList()
    }
}
