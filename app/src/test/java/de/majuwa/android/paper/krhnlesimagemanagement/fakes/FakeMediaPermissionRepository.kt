package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaPermissionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeMediaPermissionRepository(
    initialRequested: Boolean = false,
    initialRationaleShown: Boolean = false,
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
}
