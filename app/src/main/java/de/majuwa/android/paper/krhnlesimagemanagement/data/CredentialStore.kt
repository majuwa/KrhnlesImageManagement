package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "credentials",
)

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "krhnles_master_key"
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH_BITS = 128
private const val PREFS_FILE_NAME = "krhnles_secure"

class CredentialStore(
    private val context: Context,
) : CredentialRepository {
    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_BASE_FOLDER = stringPreferencesKey("base_folder")
    }

    private val securePrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
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

    override fun password(): String? = decryptValue(securePrefs.getString("password", null))

    fun username(): String? = decryptValue(securePrefs.getString("username", null))

    override suspend fun save(
        serverUrl: String,
        username: String,
        password: String,
    ) {
        context.credentialDataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl
        }
        securePrefs
            .edit()
            .putString("username", encryptValue(username))
            .putString("password", encryptValue(password))
            .apply()
    }

    override suspend fun saveBaseFolder(folder: String) {
        context.credentialDataStore.edit { it[KEY_BASE_FOLDER] = folder.trim('/') }
    }

    override suspend fun clear() {
        context.credentialDataStore.edit { it.clear() }
        securePrefs.edit().clear().apply()
    }

    override fun isLoggedIn(): Boolean = !password().isNullOrBlank()

    // ── Keystore encryption helpers ─────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getEntry(KEY_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return keyGen.generateKey()
    }

    private fun encryptValue(plaintext: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Layout: [ivLen (1 byte)] [iv] [ciphertext]
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        iv.copyInto(combined, destinationOffset = 1)
        ciphertext.copyInto(combined, destinationOffset = 1 + iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptValue(encoded: String?): String? {
        if (encoded == null) return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 2) return null
            val ivLen = combined[0].toInt() and 0xFF
            if (ivLen !in 12..16) return null
            val ciphertextStart = 1 + ivLen
            if (combined.size <= ciphertextStart) return null
            val iv = combined.copyOfRange(1, ciphertextStart)
            val ciphertext = combined.copyOfRange(ciphertextStart, combined.size)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }
}
