package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.nextcloudlogin.LoginFlowState
import de.majuwa.android.nextcloudlogin.NextcloudLoginFlow
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialSaveException
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialCorruption
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class SettingsUiState(
    val autoDateFoldersEnabled: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val webDavUrl: String = "",
    val baseFolder: String = "",
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val isWaitingForBrowser: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val error: String? = null,
    val useManualConfig: Boolean = false,
    val manualUrl: String = "",
    val manualUsername: String = "",
    val manualPassword: String = "",
    /** True when the entered URL explicitly uses plain HTTP (credentials sent in cleartext). */
    val httpWarning: Boolean = false,
    /** True when uploads should be restricted to Wi-Fi (unmetered) connections. */
    val wifiOnly: Boolean = false,
    /** Non-null when credentials are stored but cannot be decrypted; the UI explains why the user must log in again. */
    val credentialProblem: CredentialCorruption? = null,
)

private const val TAG = "SettingsViewModel"

class SettingsViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val credentialStore: CredentialRepository =
            CredentialStore(application),
        private val authRepository: NextcloudLoginFlow =
            NextcloudLoginFlow(),
        private val uploadedPhotosRepository: UploadedPhotosRepositoryContract =
            UploadedPhotosStore(application),
        private val connectionTester: suspend (WebDavConfig) -> Result<Unit> = { config ->
            WebDavClient(config).testConnection()
        },
    ) : AndroidViewModel(application) {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        private val _openBrowserEvent = MutableStateFlow<String?>(null)
        val openBrowserEvent: StateFlow<String?> = _openBrowserEvent.asStateFlow()

        init {
            viewModelScope.launch {
                var lastConnection: Triple<String, String, String>? = null
                credentialStore.webDavConfig.collect { config ->
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
                        )
                    }
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

        fun setAutoDateFoldersEnabled(enabled: Boolean) {
            _uiState.update { it.copy(autoDateFoldersEnabled = enabled) }
            viewModelScope.launch {
                credentialStore.saveAutoDateFolders(enabled)
            }
        }

        fun startLoginFlow() {
            val state = _uiState.value
            if (state.serverUrl.isBlank()) {
                _uiState.update { it.copy(error = "Server URL is required") }
                return
            }
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, error = null) }
                authRepository.loginFlow(state.serverUrl).collect { loginState ->
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
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isWaitingForBrowser = false,
                                    error = loginState.message,
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
                _uiState.update { it.copy(error = "Please fill in all fields.") }
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
                    _uiState.update { it.copy(isTesting = true, testResult = null) }
                    val result = runConnectionTest()
                    // A test cancelled by logout or a config change must never publish its stale result.
                    ensureActive()
                    _uiState.update { it.copy(isTesting = false, testResult = result) }
                }
        }

        /** Runs the check; returns a result for every outcome and only throws on cancellation. */
        private suspend fun runConnectionTest(): ConnectionTestResult =
            runCatching {
                val config = credentialStore.webDavConfig.first()
                if (!config.isValid) {
                    ConnectionTestResult.NotConfigured
                } else {
                    connectionTester(config).getOrThrow()
                    ConnectionTestResult.Success
                }
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                // Log the type only: exception messages may contain server details (never shown in the UI).
                Log.w(TAG, "Connection test failed: ${failure.javaClass.simpleName}")
                failure.toConnectionTestResult()
            }

        /** Cancels a running test and clears its result, e.g. because the credentials changed. */
        private fun cancelConnectionTest() {
            testConnectionJob?.cancel()
            testConnectionJob = null
            _uiState.update { it.copy(isTesting = false, testResult = null) }
        }

        fun logout() {
            cancelConnectionTest()
            viewModelScope.launch {
                credentialStore.clear()
                uploadedPhotosRepository.clear()
                _uiState.update { SettingsUiState() }
            }
        }

        /** Saves credentials; on failure shows a user-facing error and returns false instead of crashing. */
        private suspend fun persistCredentials(
            url: String,
            username: String,
            password: String,
        ): Boolean =
            try {
                credentialStore.save(url, username, password)
                true
            } catch (e: CredentialSaveException) {
                Log.w(TAG, "Saving credentials failed", e)
                _uiState.update {
                    it.copy(error = getApplication<Application>().getString(R.string.error_credentials_save_failed))
                }
                false
            }

        private fun normalizeUrl(url: String): String {
            val trimmed = url.trim()
            return if (trimmed.isNotBlank() && "://" !in trimmed) "https://$trimmed" else trimmed
        }

        private fun isExplicitHttp(url: String): Boolean = url.trim().lowercase().startsWith("http://")
    }
