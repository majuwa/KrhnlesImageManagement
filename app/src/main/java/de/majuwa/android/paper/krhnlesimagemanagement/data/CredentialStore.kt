package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "credentials",
)

class CredentialStore(
    private val context: Context,
) : CredentialRepository {
    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_BASE_FOLDER = stringPreferencesKey("base_folder")
    }

    private val encryptedPrefs by lazy {
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context,
            "krhnles_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val serverUrl: Flow<String?> = context.credentialDataStore.data.map { it[KEY_SERVER_URL] }

    // Username is read from encrypted prefs, but piggybacks on DataStore emissions
    // (DataStore is always updated alongside encrypted prefs in save()).
    val username: Flow<String?> = context.credentialDataStore.data.map { username() }
    val baseFolder: Flow<String> = context.credentialDataStore.data.map { it[KEY_BASE_FOLDER] ?: "" }

    override val isConfigured: Flow<Boolean> =
        context.credentialDataStore.data.map { prefs ->
            val url = prefs[KEY_SERVER_URL]
            !url.isNullOrBlank() && !username().isNullOrBlank() && !password().isNullOrBlank()
        }

    override val webDavConfig: Flow<WebDavConfig> =
        context.credentialDataStore.data.map { prefs ->
            WebDavConfig(
                url = prefs[KEY_SERVER_URL] ?: "",
                username = username() ?: "",
                password = password() ?: "",
                baseFolder = prefs[KEY_BASE_FOLDER] ?: "",
            )
        }

    override fun password(): String? = encryptedPrefs.getString("password", null)

    fun username(): String? = encryptedPrefs.getString("username", null)

    override suspend fun save(
        serverUrl: String,
        username: String,
        password: String,
    ) {
        context.credentialDataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl
        }
        encryptedPrefs
            .edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    override suspend fun saveBaseFolder(folder: String) {
        context.credentialDataStore.edit { it[KEY_BASE_FOLDER] = folder.trim('/') }
    }

    override suspend fun clear() {
        context.credentialDataStore.edit { it.clear() }
        encryptedPrefs.edit().clear().apply()
    }

    override fun isLoggedIn(): Boolean = !password().isNullOrBlank()
}
