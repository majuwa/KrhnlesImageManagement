package de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "OnboardingViewModel"
private const val KEY_PAGE = "page"
const val ONBOARDING_PAGE_COUNT = 2
internal const val FINISH_TIMEOUT_MILLIS = 3_000L

/** Where to go after the completed flag was persisted. */
enum class OnboardingExit { Photos, Settings }

class OnboardingViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val repository: OnboardingRepository,
) : AndroidViewModel(application) {
    /** Used by the default `SavedStateViewModelFactory`; the page survives process death. */
    constructor(application: Application, savedStateHandle: SavedStateHandle) :
        this(application, savedStateHandle, OnboardingStore(application))

    private val _page = MutableStateFlow(savedStateHandle.get<Int>(KEY_PAGE) ?: 0)
    val page: StateFlow<Int> = _page.asStateFlow()

    private fun setPage(value: Int) {
        _page.value = value
        savedStateHandle[KEY_PAGE] = value
    }

    private val _isFinishing = MutableStateFlow(false)

    /** True while persisting and, once the exit was emitted, for good (the latch is never reset). */
    val isFinishing: StateFlow<Boolean> = _isFinishing.asStateFlow()

    private val _exit = MutableStateFlow<OnboardingExit?>(null)

    /** One-shot: set after the flag was persisted, cleared by [consumeExit]. */
    val exit: StateFlow<OnboardingExit?> = _exit.asStateFlow()

    private var finishJob: Job? = null

    /** Set when the exit was emitted; never reset so [consumeExit] cannot re-arm [finish]. */
    private var done = false

    fun next() {
        val current = page.value
        if (current < ONBOARDING_PAGE_COUNT - 1) setPage(current + 1)
    }

    /** Returns true if it moved to the previous page; false on the first page. */
    fun back(): Boolean {
        val current = page.value
        if (current <= 0) return false
        setPage(current - 1)
        return true
    }

    fun finish(target: OnboardingExit) {
        if (done || finishJob?.isActive == true) return
        _isFinishing.value = true
        finishJob =
            viewModelScope.launch {
                try {
                    persistFlag()
                    done = true
                    _exit.value = target
                } finally {
                    if (!done) _isFinishing.value = false
                }
            }
    }

    private suspend fun persistFlag() {
        try {
            val completed = withTimeoutOrNull(FINISH_TIMEOUT_MILLIS) { repository.markCompleted() }
            if (completed == null) Log.w(TAG, "Persisting onboarding flag timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Persisting onboarding flag failed: ${e.javaClass.simpleName}")
        }
    }

    fun consumeExit() {
        _exit.value = null
    }
}
