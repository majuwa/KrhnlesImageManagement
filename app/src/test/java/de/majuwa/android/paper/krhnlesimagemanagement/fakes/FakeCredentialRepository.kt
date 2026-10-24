package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialSaveException
import de.majuwa.android.paper.krhnlesimagemanagement.model.BlurSensitivity
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class FakeCredentialRepository(
    initialConfig: WebDavConfig = WebDavConfig(),
    initialWifiOnly: Boolean = false,
    initialAutoDateFoldersEnabled: Boolean = false,
    initialBlurSensitivity: BlurSensitivity = BlurSensitivity.MEDIUM,
) : CredentialRepository {
    private val configState = MutableStateFlow(initialConfig)
    private val wifiOnlyState = MutableStateFlow(initialWifiOnly)
    private val autoDateFoldersState = MutableStateFlow(initialAutoDateFoldersEnabled)

    private val blurSensitivityState = MutableStateFlow(initialBlurSensitivity)

    override val blurSensitivity: Flow<BlurSensitivity> = blurSensitivityState
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

    /** Every value passed to [saveAutoDateFolders], in order. */
    val savedAutoDateFolders = mutableListOf<Boolean>()

    override suspend fun saveAutoDateFolders(enabled: Boolean) {
        savedAutoDateFolders += enabled
        autoDateFoldersState.value = enabled
    }

    /** Number of [saveBlurSensitivity] calls (also failed ones). */
    var blurSaveCount: Int = 0
        private set

    /** When non-null, [saveBlurSensitivity] throws this instead of storing anything. */
    var blurSaveFailure: Throwable? = null

    /** When non-null, [saveBlurSensitivity] suspends until it completes (simulates a slow disk write). */
    var blurSaveGate: CompletableDeferred<Unit>? = null

    override suspend fun saveBlurSensitivity(sensitivity: BlurSensitivity) {
        blurSaveCount++
        blurSaveGate?.await()
        blurSaveFailure?.let { throw it }
        blurSensitivityState.value = sensitivity
    }

    /** Test helper: emits a stored level without counting as a save (e.g. an older persisted value). */
    fun emitStoredBlurSensitivity(sensitivity: BlurSensitivity) {
        blurSensitivityState.value = sensitivity
    }

    /** Test helper to read the current blur sensitivity synchronously. */
    fun blurSensitivityValue(): BlurSensitivity = blurSensitivityState.value

    override suspend fun clear() {
        configState.value = WebDavConfig()
        credentialStateOverride.value = null
        wifiOnlyState.value = false
        autoDateFoldersState.value = false
        blurSensitivityState.value = BlurSensitivity.MEDIUM
    }
}
