package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "credentials",
)

class CredentialStore(
    private val context: Context,
) {
    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_BASE_FOLDER = stringPreferencesKey("base_folder")
    }

    private val encryptedPrefs by lazy {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "krhnles_secure",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val serverUrl: Flow<String?> = context.credentialDataStore.data.map { it[KEY_SERVER_URL] }
    val username: Flow<String?> = context.credentialDataStore.data.map { it[KEY_USERNAME] }
    val baseFolder: Flow<String> = context.credentialDataStore.data.map { it[KEY_BASE_FOLDER] ?: "" }

    val isConfigured: Flow<Boolean> =
        context.credentialDataStore.data.map { prefs ->
            val url = prefs[KEY_SERVER_URL]
            !url.isNullOrBlank() && !password().isNullOrBlank()
        }

    val webDavConfig: Flow<WebDavConfig> =
        context.credentialDataStore.data.map { prefs ->
            WebDavConfig(
                url = prefs[KEY_SERVER_URL] ?: "",
                username = prefs[KEY_USERNAME] ?: "",
                password = password() ?: "",
                baseFolder = prefs[KEY_BASE_FOLDER] ?: "",
            )
        }

    fun password(): String? = encryptedPrefs.getString("password", null)

    suspend fun save(
        serverUrl: String,
        username: String,
        password: String,
    ) {
        context.credentialDataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl
            prefs[KEY_USERNAME] = username
        }
        encryptedPrefs.edit().putString("password", password).apply()
    }

    suspend fun saveBaseFolder(folder: String) {
        context.credentialDataStore.edit { it[KEY_BASE_FOLDER] = folder.trim('/') }
    }

    suspend fun clear() {
        context.credentialDataStore.edit { it.clear() }
        encryptedPrefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean = !password().isNullOrBlank()
}
