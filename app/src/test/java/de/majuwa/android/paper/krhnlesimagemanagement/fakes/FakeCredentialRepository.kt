package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialSaveException
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class FakeCredentialRepository(
    initialConfig: WebDavConfig = WebDavConfig(),
    initialWifiOnly: Boolean = false,
    initialAutoDateFoldersEnabled: Boolean = false,
) : CredentialRepository {
    private val configState = MutableStateFlow(initialConfig)
    private val wifiOnlyState = MutableStateFlow(initialWifiOnly)
    private val autoDateFoldersState = MutableStateFlow(initialAutoDateFoldersEnabled)

    override val autoDateFoldersEnabled: Flow<Boolean> = autoDateFoldersState
    override val webDavConfig: Flow<WebDavConfig> = configState

    override val isConfigured: Flow<Boolean> =
        configState.map { it.isValid }

    override val wifiOnly: Flow<Boolean> = wifiOnlyState

    /** Overrides the derived credential state when non-null (e.g. to simulate corruption). */
    val credentialStateOverride = MutableStateFlow<CredentialState?>(null)

    override val credentialState: Flow<CredentialState> =
        combine(configState, credentialStateOverride) { config, override ->
            override ?: if (config.isValid) CredentialState.Valid else CredentialState.Missing
        }

    override fun password(): String? = configState.value.password.ifBlank { null }

    override fun isLoggedIn(): Boolean = !password().isNullOrBlank()

    /** When non-null, [save] throws this instead of storing anything (e.g. Keystore failure after retry). */
    var saveFailure: CredentialSaveException? = null

    override suspend fun save(
        serverUrl: String,
        username: String,
        password: String,
    ) {
        saveFailure?.let { throw it }
        credentialStateOverride.value = null
        configState.value =
            configState.value.copy(
                url = serverUrl,
                username = username,
                password = password,
            )
    }

    override suspend fun saveBaseFolder(folder: String) {
        configState.value = configState.value.copy(baseFolder = folder.trim('/'))
    }

    override suspend fun saveWifiOnly(wifiOnly: Boolean) {
        wifiOnlyState.value = wifiOnly
    }

    /** Test helper to read the current wi-fi only value synchronously. */
    fun wifiOnlyValue(): Boolean = wifiOnlyState.value

    override suspend fun saveAutoDateFolders(enabled: Boolean) {
        autoDateFoldersState.value = enabled
    }

    override suspend fun clear() {
        configState.value = WebDavConfig()
        credentialStateOverride.value = null
        wifiOnlyState.value = false
        autoDateFoldersState.value = false
    }
}
