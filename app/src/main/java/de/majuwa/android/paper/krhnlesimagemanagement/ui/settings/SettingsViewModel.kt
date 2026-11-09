package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.nextcloudlogin.LoginFlowState
import de.majuwa.android.nextcloudlogin.NextcloudLoginFlow
import de.majuwa.android.paper.krhnlesimagemanagement.KrhnlesApplication
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialSaveException
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateInfo
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.pinFor
import de.majuwa.android.paper.krhnlesimagemanagement.model.BlurSensitivity
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialCorruption
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState
import de.majuwa.android.paper.krhnlesimagemanagement.model.NewPhotosReminderSettings
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.NewPhotosReminderScheduler
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.ReminderSettingsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import kotlin.coroutines.cancellation.CancellationException

/** A user-facing Settings error; resolved to a string in Compose so a language switch re-renders it. */
enum class SettingsError(
    @StringRes val messageRes: Int,
) {
    ServerUrlRequired(R.string.error_server_url_required),
    FillAllFields(R.string.error_fill_all_fields),
    CredentialsSaveFailed(R.string.error_credentials_save_failed),
    TrustedCertificateClearFailed(R.string.error_trusted_certificate_clear_failed),
    LoginFailed(R.string.error_login_failed),
}

data class SettingsUiState(
    val autoDateFoldersEnabled: Boolean = false,
    val blurSensitivity: BlurSensitivity = BlurSensitivity.DEFAULT,
    val blurSensitivitySaveFailed: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val webDavUrl: String = "",
    val baseFolder: String = "",
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val isWaitingForBrowser: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val error: SettingsError? = null,
    val useManualConfig: Boolean = false,
    val manualUrl: String = "",
    val manualUsername: String = "",
    val manualPassword: String = "",
    /** True when the entered URL explicitly uses plain HTTP (credentials sent in cleartext). */
    val httpWarning: Boolean = false,
    /** True when uploads should be restricted to Wi-Fi (unmetered) connections. */
    val wifiOnly: Boolean = false,
    /** True when every upload is also verified by downloading it again (extra data). */
    val verifyByDownload: Boolean = false,
    /** The stored new-photos reminder setting (off / Weekly by default). */
    val newPhotosReminder: NewPhotosReminderSettings = NewPhotosReminderSettings(),
    /** Non-null when credentials are stored but cannot be decrypted; the UI explains why the user must log in again. */
    val credentialProblem: CredentialCorruption? = null,
    /** The trusted (pinned) certificate of the configured origin, if any. */
    val trustedCertificate: TrustedCertificateInfo? = null,
    /** A captured certificate awaiting the user's decision (from the connection test). */
    val certificateReview: CertificateReview? = null,
    /** True while confirming the trust or stopping it. */
    val trustInProgress: Boolean = false,
    /** True when storing or removing the pin failed or timed out. */
    val trustSaveFailed: Boolean = false,
)

/**
 * A certificate captured by the connection test. [changed] = a different certificate is pinned for
 * the origin; [previousSha256] is that pin. Compared by identity to tell whether a review is still current.
 */
class CertificateReview(
    val certificate: ServerCertificate,
    val changed: Boolean,
    val previousSha256: ByteString?,
)

private const val TAG = "SettingsViewModel"
private const val BLUR_RESTORE_TIMEOUT_MS = 3_000L
private const val CONFIG_READ_TIMEOUT_MS = 3_000L

class SettingsViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val credentialStore: CredentialRepository =
            CredentialStore(application),
        private val loginFlow: (String) -> Flow<LoginFlowState> = { url -> NextcloudLoginFlow().loginFlow(url) },
        private val uploadedPhotosRepository: UploadedPhotosRepositoryContract =
            UploadedPhotosStore(application),
        private val verifiedUploadsRepository: VerifiedUploadsRepositoryContract =
            VerifiedUploadsStore(application),
        private val trustedCertificates: TrustedCertificateRepository =
            TrustedCertificateStore(application),
        private val connectionTester: suspend (WebDavConfig, PinnedCertificate?) -> Result<Unit> = { config, pin ->
            WebDavClient(config, pin).testConnection()
        },
        /** The process-scoped reminder controller (see [defaultReminderController]). */
        val reminder: ReminderSettingsController = defaultReminderController(application, credentialStore),
    ) : AndroidViewModel(application) {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        private var pendingBlurSaves = 0

        /** Review confirmation and "Stop trusting" (guarded one-shots). */
        val certificateTrust: CertificateTrustController =
            CertificateTrustController(
                scope = viewModelScope,
                uiState = _uiState,
                credentialStore = credentialStore,
                trustedCertificates = trustedCertificates,
                onTrusted = { testConnection() },
            )

        // Raw stored certificate and configured URL; the state shows the certificate only for the configured origin.
        private var storedCertificate: TrustedCertificateInfo? = null
        private var configuredUrl: String = ""

        private val _openBrowserEvent = MutableStateFlow<String?>(null)
        val openBrowserEvent: StateFlow<String?> = _openBrowserEvent.asStateFlow()

        init {
            viewModelScope.launch {
                var lastConnection: Triple<String, String, String>? = null
                credentialStore.webDavConfig.collect { config ->
                    configuredUrl = config.url
                    val connection = Triple(config.url, config.username, config.password)
                    // A result (or a running test) for other credentials/server must not survive the change.
                    if (lastConnection != null && connection != lastConnection) cancelConnectionTest()
                    lastConnection = connection
                    _uiState.update {
                        it.copy(
                            webDavUrl = config.url,
                            username = config.username,
                            baseFolder = config.baseFolder,
                            isLoggedIn = config.isValid,
                            trustedCertificate = storedCertificate.pinnedFor(config.url),
                        )
                    }
                }
            }
            viewModelScope.launch {
                trustedCertificates.trustedCertificate.collect { stored ->
                    storedCertificate = stored
                    _uiState.update { it.copy(trustedCertificate = stored.pinnedFor(configuredUrl)) }
                }
            }
            viewModelScope.launch {
                credentialStore.credentialState.collect { state ->
                    _uiState.update {
                        it.copy(credentialProblem = (state as? CredentialState.Corrupted)?.reason)
                    }
                }
            }
            viewModelScope.launch {
                credentialStore.wifiOnly.collect { value ->
                    _uiState.update { it.copy(wifiOnly = value) }
                }
            }
            viewModelScope.launch {
                credentialStore.newPhotosReminder.collect { value ->
                    _uiState.update { it.copy(newPhotosReminder = value) }
                }
            }
            viewModelScope.launch {
                credentialStore.verifyByDownload.collect { value ->
                    _uiState.update { it.copy(verifyByDownload = value) }
                }
            }
            viewModelScope.launch {
                credentialStore.blurSensitivity.collect { level ->
                    // While saves are pending the optimistic value is newer than the stored one.
                    if (pendingBlurSaves == 0) _uiState.update { it.copy(blurSensitivity = level) }
                }
            }
            viewModelScope.launch {
                credentialStore.autoDateFoldersEnabled.collect { enabled ->
                    _uiState.update { it.copy(autoDateFoldersEnabled = enabled) }
                }
            }
        }

        fun onServerUrlChange(value: String) {
            _uiState.update { it.copy(serverUrl = value, error = null, httpWarning = isExplicitHttp(value)) }
        }

        fun onBaseFolderChange(value: String) {
            _uiState.update { it.copy(baseFolder = value) }
        }

        fun saveBaseFolder() {
            viewModelScope.launch {
                credentialStore.saveBaseFolder(_uiState.value.baseFolder)
            }
        }

        fun setWifiOnly(enabled: Boolean) {
            viewModelScope.launch {
                credentialStore.saveWifiOnly(enabled)
            }
        }

        /** The toggle shows the stored value, so a failed write only leaves it unchanged (logged by type). */
        fun setVerifyByDownload(enabled: Boolean) {
            viewModelScope.launch {
                runCatching { credentialStore.saveVerifyByDownload(enabled) }
                    .onFailure { if (it is CancellationException) throw it }
                    .onFailure { Log.w(TAG, "Saving verify by download failed: ${it.javaClass.simpleName}") }
            }
        }

        /**
         * Applies to the next blur scan only; the level is shown immediately and persisted in the
         * background. On failure the persisted level is restored and [SettingsUiState.blurSensitivitySaveFailed]
         * is set. Main-thread only: [pendingBlurSaves] makes the collector ignore (older) stored values while
         * a save is in flight, so a fast drag does not flicker back.
         */
        fun setBlurSensitivity(level: BlurSensitivity) {
            _uiState.update { it.copy(blurSensitivity = level, blurSensitivitySaveFailed = false) }
            pendingBlurSaves++
            viewModelScope.launch {
                var failed = false
                try {
                    runCatching { credentialStore.saveBlurSensitivity(level) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            Log.w(TAG, "Saving blur sensitivity failed: ${it.javaClass.simpleName}")
                            failed = true
                        }
                } finally {
                    pendingBlurSaves--
                }
                if (failed && pendingBlurSaves == 0) {
                    val persisted = readPersistedBlurSensitivity(credentialStore)
                    _uiState.update {
                        it.copy(blurSensitivity = persisted ?: it.blurSensitivity, blurSensitivitySaveFailed = true)
                    }
                }
            }
        }

        fun setAutoDateFoldersEnabled(enabled: Boolean) {
            _uiState.update { it.copy(autoDateFoldersEnabled = enabled) }
            viewModelScope.launch {
                credentialStore.saveAutoDateFolders(enabled)
            }
        }

        fun startLoginFlow() {
            val state = _uiState.value
            if (state.serverUrl.isBlank()) {
                _uiState.update { it.copy(error = SettingsError.ServerUrlRequired) }
                return
            }
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, error = null) }
                loginFlow(state.serverUrl).collect { loginState ->
                    when (loginState) {
                        is LoginFlowState.WaitingForBrowser -> {
                            _uiState.update { it.copy(isLoading = false, isWaitingForBrowser = true) }
                            _openBrowserEvent.value = loginState.loginUrl
                        }

                        is LoginFlowState.Authenticated -> {
                            val webDavUrl =
                                "${loginState.server}/remote.php/dav/files/${loginState.loginName}/"
                            val saved =
                                persistCredentials(webDavUrl, loginState.loginName, loginState.appPassword)
                            _uiState.update {
                                if (saved) {
                                    it.copy(isWaitingForBrowser = false, isLoggedIn = true, credentialProblem = null)
                                } else {
                                    it.copy(isLoading = false, isWaitingForBrowser = false)
                                }
                            }
                        }

                        is LoginFlowState.Failed -> {
                            // The library message is never shown or logged (English, untyped).
                            Log.w(TAG, "Login flow failed")
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isWaitingForBrowser = false,
                                    error = SettingsError.LoginFailed,
                                )
                            }
                        }
                    }
                }
            }
        }

        fun consumeBrowserEvent() {
            _openBrowserEvent.value = null
        }

        fun toggleManualConfig() {
            _uiState.update { it.copy(useManualConfig = !it.useManualConfig) }
        }

        fun onManualUrlChange(value: String) {
            _uiState.update { it.copy(manualUrl = value, error = null, httpWarning = isExplicitHttp(value)) }
        }

        fun onManualUsernameChange(value: String) {
            _uiState.update { it.copy(manualUsername = value, error = null) }
        }

        fun onManualPasswordChange(value: String) {
            _uiState.update { it.copy(manualPassword = value, error = null) }
        }

        fun saveManualConfig() {
            val state = _uiState.value
            val normalizedUrl = normalizeUrl(state.manualUrl)
            val config = WebDavConfig(normalizedUrl, state.manualUsername, state.manualPassword)
            if (!config.isValid) {
                _uiState.update { it.copy(error = SettingsError.FillAllFields) }
                return
            }
            viewModelScope.launch {
                if (persistCredentials(normalizedUrl, state.manualUsername, state.manualPassword)) {
                    _uiState.update {
                        it.copy(isLoggedIn = true, manualUrl = normalizedUrl, credentialProblem = null)
                    }
                }
            }
        }

        // Non-null while a connection test runs; used to drop overlapping requests (issue #22 / #41).
        // Released when the test completes (every failure becomes a result, WebDavClient bounds the
        // call with an overall timeout and cancels it with the coroutine), when it is cancelled by
        // logout or a credential/server change, or when viewModelScope is cancelled.
        private var testConnectionJob: Job? = null

        /**
         * Tests the stored WebDAV configuration once. The config is read with [first] — never
         * `collect` — so no subscription outlives the test. Calls while a test is running are ignored.
         */
        fun testConnection() {
            if (testConnectionJob?.isActive == true) return
            testConnectionJob =
                viewModelScope.launch {
                    _uiState.update { it.copy(isTesting = true, testResult = null, certificateReview = null) }
                    val result = runConnectionTest(credentialStore, trustedCertificates, connectionTester)
                    // A test cancelled by logout or a config change must never publish its stale result.
                    ensureActive()
                    val review = result.toReview()
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = result,
                            certificateReview = review,
                            trustSaveFailed = false,
                        )
                    }
                }
        }

        /** Cancels a running test and clears its result, e.g. because the credentials changed. */
        private fun cancelConnectionTest() {
            testConnectionJob?.cancel()
            testConnectionJob = null
            certificateTrust.cancel()
            _uiState.update {
                it.copy(isTesting = false, testResult = null, certificateReview = null, trustInProgress = false)
            }
        }

        fun logout() {
            cancelConnectionTest()
            viewModelScope.launch {
                // Verified records first: they make local photos removable. One failing clear never
                // skips the others.
                clearLogged("verified uploads") { verifiedUploadsRepository.clear() }
                // Through the controller's lock, before the credential clear: a change in flight finishes first,
                // then the setting is stored "off" and the check cancelled.
                clearLogged("reminder") { reminder.resetForLogout() }
                val pinCleared = clearLogged("trusted certificate") { trustedCertificates.clear() }
                clearLogged("credentials") { credentialStore.clear() }
                clearLogged("uploaded markers") { uploadedPhotosRepository.clear() }
                // A pin that could not be removed is reported; the next sign-in (no previous
                // configuration) clears it again before saving.
                _uiState.update { SettingsUiState(error = pinClearError(pinCleared)) }
            }
        }

        private fun pinClearError(cleared: Boolean): SettingsError? =
            if (cleared) null else SettingsError.TrustedCertificateClearFailed

        /**
         * Saves credentials; on failure shows a user-facing error and returns false instead of crashing.
         * A different server URL or username (or an unreadable previous config) first clears the
         * verified upload records: they belong to the old account and must never make a photo
         * removable against the new one.
         */
        private suspend fun persistCredentials(
            url: String,
            username: String,
            password: String,
        ): Boolean =
            try {
                val previous =
                    runCatching { withTimeoutOrNull(CONFIG_READ_TIMEOUT_MS) { credentialStore.webDavConfig.first() } }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                if (previous == null || previous.url != url || previous.username != username) {
                    clearLogged("verified uploads") { verifiedUploadsRepository.clear() }
                }
                // A pin belongs to one origin: another server (or an unreadable previous config) drops it
                // before the new credentials are written; same-origin changes keep it.
                val newOrigin = ServerOrigin.of(url)
                if (previous == null || newOrigin == null || ServerOrigin.of(previous.url) != newOrigin) {
                    if (!clearLogged("trusted certificate") { trustedCertificates.clear() }) {
                        _uiState.update { it.copy(error = pinClearError(false)) }
                    }
                }
                credentialStore.save(url, username, password)
                true
            } catch (e: CredentialSaveException) {
                Log.w(TAG, "Saving credentials failed", e)
                _uiState.update {
                    it.copy(error = SettingsError.CredentialsSaveFailed)
                }
                false
            }
    }

private suspend fun readPersistedBlurSensitivity(credentialStore: CredentialRepository): BlurSensitivity? =
    runCatching { withTimeoutOrNull(BLUR_RESTORE_TIMEOUT_MS) { credentialStore.blurSensitivity.first() } }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()

private fun TrustedCertificateInfo?.pinnedFor(serverUrl: String): TrustedCertificateInfo? =
    this?.takeIf {
        pinFor(serverUrl) !=
            null
    }

private fun ConnectionTestResult.toReview(): CertificateReview? =
    when (this) {
        is ConnectionTestResult.UntrustedCertificate -> {
            CertificateReview(certificate, changed = false, previousSha256 = null)
        }

        is ConnectionTestResult.CertificateChanged -> {
            certificate?.let { CertificateReview(it, changed = true, previousSha256 = previousSha256) }
        }

        else -> {
            null
        }
    }

private fun normalizeUrl(url: String): String {
    val trimmed = url.trim()
    return if (trimmed.isNotBlank() && "://" !in trimmed) "https://$trimmed" else trimmed
}

private fun isExplicitHttp(url: String): Boolean = url.trim().lowercase().startsWith("http://")

/** Runs the check; returns a result for every outcome and only throws on cancellation. */
private suspend fun runConnectionTest(
    credentialStore: CredentialRepository,
    trustedCertificates: TrustedCertificateRepository,
    connectionTester: suspend (WebDavConfig, PinnedCertificate?) -> Result<Unit>,
): ConnectionTestResult =
    runCatching {
        val config = credentialStore.webDavConfig.first()
        if (!config.isValid) {
            ConnectionTestResult.NotConfigured
        } else {
            connectionTester(config, trustedCertificates.pinFor(config.url)).getOrThrow()
            ConnectionTestResult.Success
        }
    }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        // Log the type only: exception messages may contain server details (never shown in the UI).
        Log.w(TAG, "Connection test failed: ${failure.javaClass.simpleName}")
        failure.toConnectionTestResult()
    }

/** Runs one clear step; a failure is logged by type only and never skips the next one. True = it succeeded. */
private suspend fun clearLogged(
    what: String,
    clear: suspend () -> Unit,
): Boolean =
    runCatching { clear() }
        .onFailure { if (it is CancellationException) throw it }
        .onFailure { Log.w(TAG, "Clearing $what failed: ${it.javaClass.simpleName}") }
        .isSuccess

/**
 * The application's process-scoped controller when [store] is the application's own [CredentialStore] (the
 * production case); otherwise (an injected store, i.e. tests) a standalone one over that store, so the setting
 * and its schedule always belong to the same store.
 */
private fun defaultReminderController(
    application: Application,
    store: CredentialRepository,
): ReminderSettingsController =
    (application as? KrhnlesApplication)?.takeIf { store is CredentialStore }?.reminderController
        ?: ReminderSettingsController(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            store,
            NewPhotosReminderScheduler(application),
        )
