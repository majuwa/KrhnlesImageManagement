package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.NextcloudAuthRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.model.LoginFlowState
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val webDavUrl: String = "",
    val baseFolder: String = "",
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val isWaitingForBrowser: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val error: String? = null,
    val useManualConfig: Boolean = false,
    val manualUrl: String = "",
    val manualUsername: String = "",
    val manualPassword: String = "",
    /** True when the entered URL explicitly uses plain HTTP (credentials sent in cleartext). */
    val httpWarning: Boolean = false,
)

class SettingsViewModel(
    application: Application,
    private val credentialStore: CredentialRepository =
        CredentialStore(application),
    private val authRepository: NextcloudAuthRepository =
        NextcloudAuthRepository(),
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _openBrowserEvent = MutableStateFlow<String?>(null)
    val openBrowserEvent: StateFlow<String?> = _openBrowserEvent.asStateFlow()

    init {
        viewModelScope.launch {
            credentialStore.webDavConfig.collect { config ->
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
                        credentialStore.save(
                            webDavUrl,
                            loginState.loginName,
                            loginState.appPassword,
                        )
                        _uiState.update {
                            it.copy(isWaitingForBrowser = false, isLoggedIn = true)
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
            credentialStore.save(normalizedUrl, state.manualUsername, state.manualPassword)
            _uiState.update { it.copy(isLoggedIn = true, manualUrl = normalizedUrl) }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            credentialStore.webDavConfig.collect { config ->
                if (!config.isValid) {
                    _uiState.update { it.copy(isTesting = false, testResult = "Not configured.") }
                    return@collect
                }
                val client = WebDavClient(config)
                val result = client.testConnection()
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        testResult =
                            if (result.isSuccess) {
                                "Connection successful!"
                            } else {
                                "Failed: ${result.exceptionOrNull()?.message}"
                            },
                    )
                }
                return@collect
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            credentialStore.clear()
            _uiState.update { SettingsUiState() }
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.isNotBlank() && "://" !in trimmed) "https://$trimmed" else trimmed
    }

    private fun isExplicitHttp(url: String): Boolean = url.trim().lowercase().startsWith("http://")
}
