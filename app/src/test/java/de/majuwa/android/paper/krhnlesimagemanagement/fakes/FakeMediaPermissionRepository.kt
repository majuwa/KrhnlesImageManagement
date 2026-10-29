package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaPermissionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

class FakeMediaPermissionRepository(
    initialRequested: Boolean = false,
    initialRationaleShown: Boolean = false,
    initialLocationRequested: Boolean = false,
) : MediaPermissionRepository {
    private val state = MutableStateFlow(initialRequested)
    override val videoAccessRequested: Flow<Boolean> = state

    override suspend fun markVideoAccessRequested() {
        state.value = true
    }

    fun isRequested(): Boolean = state.value

    private val rationaleState = MutableStateFlow(initialRationaleShown)
    override val videoRationaleShown: Flow<Boolean> = rationaleState

    override suspend fun markVideoRationaleShown() {
        rationaleState.value = true
    }

    fun isRationaleShown(): Boolean = rationaleState.value

    /** Emits nothing until set to a value (null = the stored flag is not read yet). */
    val locationState = MutableStateFlow<Boolean?>(initialLocationRequested)
    override val mediaLocationRequested: Flow<Boolean> = locationState.filterNotNull()

    override suspend fun markMediaLocationRequested() {
        locationState.value = true
    }

    fun isLocationRequested(): Boolean = locationState.value == true
}
