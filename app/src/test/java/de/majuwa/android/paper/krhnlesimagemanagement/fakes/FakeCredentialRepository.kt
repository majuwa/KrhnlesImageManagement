package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeCredentialRepository(
    initialConfig: WebDavConfig = WebDavConfig(),
) : CredentialRepository {
    private val configState = MutableStateFlow(initialConfig)

    override val webDavConfig: Flow<WebDavConfig> = configState

    override val isConfigured: Flow<Boolean> =
        configState.map { it.isValid }

    override fun password(): String? = configState.value.password.ifBlank { null }

    override fun isLoggedIn(): Boolean = !password().isNullOrBlank()

    override suspend fun save(
        serverUrl: String,
        username: String,
        password: String,
    ) {
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

    override suspend fun clear() {
        configState.value = WebDavConfig()
    }
}
