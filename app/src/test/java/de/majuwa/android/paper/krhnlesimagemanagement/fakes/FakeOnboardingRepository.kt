package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeOnboardingRepository(
    initialCompleted: Boolean = false,
) : OnboardingRepository {
    private val state = MutableStateFlow(initialCompleted)
    override val completed: Flow<Boolean> = state

    var markCompletedCalls = 0
        private set

    /** When non-null, [markCompleted] suspends until this is completed. */
    var gate: CompletableDeferred<Unit>? = null

    /** When non-null, [markCompleted] throws it instead of persisting. */
    var failure: Throwable? = null

    override suspend fun markCompleted() {
        markCompletedCalls++
        gate?.await()
        failure?.let { throw it }
        state.value = true
    }

    fun isCompleted(): Boolean = state.value
}
