package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialCorruption
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.ConcurrentHashMap

internal const val CREDENTIALS_DATASTORE_NAME = "credentials"

private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = CREDENTIALS_DATASTORE_NAME,
)

internal const val PREFS_FILE_NAME = "krhnles_secure"
private const val FIELD_USERNAME = "username"
private const val FIELD_PASSWORD = "password"
private const val TAG = "CredentialStore"

/**
 * Both credential fields decrypted once for a single DataStore emission. All credential-derived flows map
 * from this snapshot so each emission costs one decryption per field per collector.
 */
private data class CredentialSnapshot(
    val serverUrl: String?,
    val baseFolder: String,
    val username: DecryptResult,
    val password: DecryptResult,
) {
    val usernameValue: String? get() = (username as? DecryptResult.Success)?.value
    val passwordValue: String? get() = (password as? DecryptResult.Success)?.value
}

class CredentialStore internal constructor(
    private val context: Context,
    private val cipher: CredentialCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialRepository {
    constructor(context: Context) : this(context, CredentialCipher(AndroidKeystoreKeyProvider()))

    private companion object {
        val KEY_AUTO_DATE_FOLDERS = booleanPreferencesKey("auto_date_folders")
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_BASE_FOLDER = stringPreferencesKey("base_folder")
        val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")

        /**
         * Bumped in every DataStore edit that accompanies a change of the encrypted prefs, so that the
         * credential flows re-read (and re-decrypt) them even when no DataStore value changed.
         */
        val KEY_CREDENTIALS_VERSION = longPreferencesKey("credentials_version")
    }

    private val securePrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    /** Last failure logged per field; a failure is logged once until the field's outcome changes. */
    private val lastLoggedFailure = ConcurrentHashMap<String, DecryptResult.Failure>()

    private val snapshot: Flow<CredentialSnapshot> =
        context.credentialDataStore.data
            .map { prefs ->
                CredentialSnapshot(
                    serverUrl = prefs[KEY_SERVER_URL],
                    baseFolder = prefs[KEY_BASE_FOLDER] ?: "",
                    username = decryptField(FIELD_USERNAME),
                    password = decryptField(FIELD_PASSWORD),
                )
            }.flowOn(ioDispatcher)

    val serverUrl: Flow<String?> = context.credentialDataStore.data.map { it[KEY_SERVER_URL] }
    override val autoDateFoldersEnabled: Flow<Boolean> =
        context.credentialDataStore.data.map { it[KEY_AUTO_DATE_FOLDERS] ?: false }

    val username: Flow<String?> = snapshot.map { it.usernameValue }
    val baseFolder: Flow<String> = context.credentialDataStore.data.map { it[KEY_BASE_FOLDER] ?: "" }

    override val wifiOnly: Flow<Boolean> = context.credentialDataStore.data.map { it[KEY_WIFI_ONLY] ?: false }

    override val isConfigured: Flow<Boolean> =
        snapshot.map {
            !it.serverUrl.isNullOrBlank() && !it.usernameValue.isNullOrBlank() && !it.passwordValue.isNullOrBlank()
        }

    override val webDavConfig: Flow<WebDavConfig> =
        snapshot.map {
            WebDavConfig(
                url = it.serverUrl ?: "",
                username = it.usernameValue ?: "",
                password = it.passwordValue ?: "",
                baseFolder = it.baseFolder,
            )
        }

    override val credentialState: Flow<CredentialState> = snapshot.map { credentialStateOf(it.username, it.password) }

    override fun password(): String? = (decryptField(FIELD_PASSWORD) as? DecryptResult.Success)?.value

    fun username(): String? = (decryptField(FIELD_USERNAME) as? DecryptResult.Success)?.value

    /**
     * Encrypts both values first, then commits them to the secure prefs, and only then writes the server URL
     * (plus a [KEY_CREDENTIALS_VERSION] bump) to DataStore. A failed encryption therefore leaves all
     * previously stored values (including the server URL) untouched, and the DataStore emission is
     * guaranteed to observe the new prefs.
     *
     * @throws CredentialSaveException if encryption or persisting fails.
     */
    override suspend fun save(
        serverUrl: String,
        username: String,
        password: String,
    ) {
        withContext(ioDispatcher) {
            try {
                val encryptedUsername = cipher.encrypt(username)
                val encryptedPassword = cipher.encrypt(password)
                val committed =
                    securePrefs
                        .edit()
                        .putString(FIELD_USERNAME, encryptedUsername)
                        .putString(FIELD_PASSWORD, encryptedPassword)
                        .commit()
                if (!committed) throw IOException("Writing encrypted credentials failed")
                context.credentialDataStore.edit { prefs ->
                    prefs[KEY_SERVER_URL] = serverUrl
                    bumpCredentialsVersion(prefs)
                }
            } catch (e: GeneralSecurityException) {
                throw CredentialSaveException(e)
            } catch (e: ProviderException) {
                throw CredentialSaveException(e)
            } catch (e: IOException) {
                throw CredentialSaveException(e)
            }
        }
    }

    override suspend fun saveBaseFolder(folder: String) {
        context.credentialDataStore.edit { it[KEY_BASE_FOLDER] = folder.trim('/') }
    }

    override suspend fun saveWifiOnly(wifiOnly: Boolean) {
        context.credentialDataStore.edit { it[KEY_WIFI_ONLY] = wifiOnly }
    }

    override suspend fun saveAutoDateFolders(enabled: Boolean) {
        context.credentialDataStore.edit { it[KEY_AUTO_DATE_FOLDERS] = enabled }
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            // apply() updates the in-memory prefs synchronously, so the DataStore emission below
            // already observes the cleared values; the disk write may complete in the background.
            securePrefs.edit().clear().apply()
            context.credentialDataStore.edit { prefs ->
                // Read the version before clearing so the bump always produces a new value (and an emission).
                val version = prefs[KEY_CREDENTIALS_VERSION] ?: 0L
                prefs.clear()
                prefs[KEY_CREDENTIALS_VERSION] = version + 1
            }
        }
    }

    override fun isLoggedIn(): Boolean = !password().isNullOrBlank()

    private fun bumpCredentialsVersion(prefs: MutablePreferences) {
        prefs[KEY_CREDENTIALS_VERSION] = (prefs[KEY_CREDENTIALS_VERSION] ?: 0L) + 1
    }

    private fun decryptField(field: String): DecryptResult {
        val result = cipher.decrypt(securePrefs.getString(field, null))
        if (result is DecryptResult.Failure) {
            if (lastLoggedFailure.put(field, result) != result) {
                // Never log the ciphertext or plaintext — only which field failed and why.
                Log.w(TAG, "Failed to decrypt stored $field (${result.reason}): ${result.causeType}")
            }
        } else {
            lastLoggedFailure.remove(field)
        }
        return result
    }

    private fun credentialStateOf(vararg results: DecryptResult): CredentialState {
        val reasons = results.filterIsInstance<DecryptResult.Failure>().map { it.reason }
        return when {
            CredentialCorruption.KEY_INVALIDATED in reasons -> {
                CredentialState.Corrupted(CredentialCorruption.KEY_INVALIDATED)
            }

            reasons.isNotEmpty() -> {
                CredentialState.Corrupted(CredentialCorruption.UNREADABLE)
            }

            results.all { it is DecryptResult.Success } -> {
                CredentialState.Valid
            }

            else -> {
                CredentialState.Missing
            }
        }
    }
}
