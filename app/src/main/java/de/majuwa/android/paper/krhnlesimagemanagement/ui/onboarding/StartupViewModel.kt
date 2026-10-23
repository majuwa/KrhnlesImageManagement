package de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "StartupViewModel"
internal const val START_RESOLUTION_TIMEOUT_MILLIS = 3_000L

/** Resolves once whether the app starts on onboarding or the photo grid. `null` while resolving. */
class StartupViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val onboardingRepository: OnboardingRepository = OnboardingStore(application),
        private val credentialRepository: CredentialRepository = CredentialStore(application),
        private val timeoutMillis: Long = START_RESOLUTION_TIMEOUT_MILLIS,
    ) : AndroidViewModel(application) {
        private val _destination = MutableStateFlow<StartDestination?>(null)
        val destination: StateFlow<StartDestination?> = _destination.asStateFlow()

        init {
            viewModelScope.launch { _destination.value = resolve() }
        }

        private suspend fun resolve(): StartDestination {
            val result =
                try {
                    withTimeoutOrNull(timeoutMillis) { read() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Resolving start destination failed: ${e.javaClass.simpleName}")
                    null
                }
            return result ?: StartDestination.Photos
        }

        private suspend fun read(): StartDestination {
            val completed = onboardingRepository.completed.first()
            // One snapshot of both flows (one Keystore decrypt pass, consistent values).
            val (config, credentialState) =
                combine(credentialRepository.webDavConfig, credentialRepository.credentialState) { config, state ->
                    config to state
                }.first()
            val existing =
                isExistingUser(
                    configured = config.isValid,
                    storedServerUrl = config.url,
                    credentialState = credentialState,
                )
            if (!completed && existing) markCompletedSilently()
            return resolveStartDestination(completed, existing)
        }

        private fun markCompletedSilently() {
            viewModelScope.launch {
                try {
                    onboardingRepository.markCompleted()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Persisting onboarding flag failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }
