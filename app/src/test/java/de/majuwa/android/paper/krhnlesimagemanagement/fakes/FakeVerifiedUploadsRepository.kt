package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadTarget
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.isAt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeVerifiedUploadsRepository(
    initial: List<VerifiedUpload> = emptyList(),
) : VerifiedUploadsRepositoryContract {
    private val state = MutableStateFlow(initial)

    override val records: Flow<List<VerifiedUpload>> = state

    /** Current records, newest first as stored. */
    val current: List<VerifiedUpload> get() = state.value

    /** Every id set passed to [forget], in order. */
    val forgotten = mutableListOf<Set<Long>>()

    /** When non-null, [recordVerified] and a recording [applyUpload] throw this (e.g. a full disk). */
    var recordFailure: Throwable? = null

    /** When non-null, [clear] throws this. */
    var clearFailure: Throwable? = null

    /** Number of [clear] calls (also failed ones). */
    var clearCalls = 0
        private set

    override suspend fun recordVerified(record: VerifiedUpload) {
        recordFailure?.let { throw it }
        state.value = listOf(record) + state.value.filterNot { it.id == record.id }
    }

    override suspend fun applyUpload(
        target: UploadTarget,
        record: VerifiedUpload?,
        forgetIds: Set<Long>,
    ) {
        if (record != null) recordFailure?.let { throw it }
        val kept = state.value.filterNot { it.isAt(target) || it.id in forgetIds || it.id == record?.id }
        state.value = listOfNotNull(record) + kept
    }

    override suspend fun forget(ids: Set<Long>) {
        forgotten += ids
        state.value = state.value.filterNot { it.id in ids }
    }

    override suspend fun clear() {
        clearCalls++
        clearFailure?.let { throw it }
        state.value = emptyList()
    }

    /** Replaces the stored records (e.g. a record that changed meanwhile). */
    fun replaceAll(records: List<VerifiedUpload>) {
        state.value = records
    }
}
