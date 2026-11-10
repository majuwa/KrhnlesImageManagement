package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "CertificateTrust"
private const val CONFIG_READ_TIMEOUT_MS = 3_000L
private const val TRUST_WRITE_TIMEOUT_MS = 5_000L

/**
 * Confirming a reviewed certificate and withdrawing trust, as guarded one-shots on the settings state.
 * The job is non-null only while it runs: the work is bounded by timeouts, cancelled by [cancel] (config
 * change / logout) and cleared when it completes; re-entry is ignored.
 */
class CertificateTrustController internal constructor(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val credentialStore: CredentialRepository,
    private val trustedCertificates: TrustedCertificateRepository,
    private val onTrusted: () -> Unit,
) {
    private var job: Job? = null

    /**
     * Stores the reviewed certificate as the pin, but only if [review] is still the current one, the
     * certificate is valid with a strong key (RSA >= 2048 / EC >= 256) and the configured server is still
     * the reviewed origin; then the connection test runs again.
     */
    fun confirm(review: CertificateReview) {
        // The review the user saw must still be the current one (a new test replaces it): never trust
        // a certificate other than the one that was confirmed.
        if (uiState.value.certificateReview !== review) return
        val certificate = review.certificate
        if (job?.isActive == true || !certificate.isValidAt(System.currentTimeMillis()) ||
            !certificate.hasStrongKey
        ) {
            return
        }
        launchGuarded {
            uiState.update { it.copy(trustInProgress = true, trustSaveFailed = false) }
            val stored = store(review)
            ensureActive()
            uiState.update {
                if (stored) {
                    it.copy(trustInProgress = false, certificateReview = null)
                } else {
                    it.copy(trustInProgress = false, trustSaveFailed = true)
                }
            }
            if (stored) onTrusted()
        }
    }

    /** Removes the pin; the next connection to a self-signed server fails again. */
    fun stop() {
        if (job?.isActive == true) return
        launchGuarded {
            uiState.update { it.copy(trustInProgress = true, trustSaveFailed = false) }
            val cleared =
                runCatching { withTimeoutOrNull(TRUST_WRITE_TIMEOUT_MS) { trustedCertificates.clear() } != null }
                    .onFailure { if (it is CancellationException) throw it }
                    .onFailure { Log.w(TAG, "Clearing the trusted certificate failed: ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
            ensureActive()
            uiState.update { it.copy(trustInProgress = false, trustSaveFailed = !cleared) }
        }
    }

    /** Cancels a running confirmation (the configuration changed or the user logged out). */
    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun launchGuarded(block: suspend CoroutineScope.() -> Unit) {
        val launched = scope.launch(block = block)
        job = launched
        launched.invokeOnCompletion { if (job === launched) job = null }
    }

    private suspend fun store(review: CertificateReview): Boolean {
        val config =
            runCatching { withTimeoutOrNull(CONFIG_READ_TIMEOUT_MS) { credentialStore.webDavConfig.first() } }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        val current = uiState.value.certificateReview === review
        if (config == null || !current || ServerOrigin.of(config.url) != review.certificate.origin) return false
        return runCatching {
            withTimeoutOrNull(TRUST_WRITE_TIMEOUT_MS) { trustedCertificates.trust(review.certificate) } != null
        }.onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(TAG, "Saving the trusted certificate failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }
}
