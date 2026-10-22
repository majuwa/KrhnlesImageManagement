package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialCorruption
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableEntryException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "krhnles_master_key"
private const val KEY_SIZE_BITS = 256
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH_BITS = 128
private const val GCM_IV_LENGTH_BYTES = 12
private const val BYTE_MASK = 0xFF

/** Supplies the AES key used to encrypt stored credentials. */
interface CredentialKeyProvider {
    fun getOrCreateKey(): SecretKey

    /** Permanently removes the current key so that the next [getOrCreateKey] creates a fresh one. */
    fun deleteKey()
}

/**
 * The Keystore holds an entry under the credential key alias that is not an AES secret key. The entry is
 * readable, it is just the wrong kind, so this is classified as [CredentialCorruption.UNREADABLE] rather
 * than as a lost / invalidated key.
 */
class UnexpectedKeyEntryException(
    message: String,
) : KeyStoreException(message)

/** Production key provider backed by the Android Keystore. */
class AndroidKeystoreKeyProvider : CredentialKeyProvider {
    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun getOrCreateKey(): SecretKey {
        keyStore().getEntry(KEY_ALIAS, null)?.let { entry ->
            return (entry as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw UnexpectedKeyEntryException("Keystore entry is not a secret key")
        }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return keyGen.generateKey()
    }

    override fun deleteKey() {
        keyStore().deleteEntry(KEY_ALIAS)
    }
}

/** Outcome of decrypting one stored credential value. */
sealed interface DecryptResult {
    /** No value was stored. */
    data object Missing : DecryptResult

    data class Success(
        val value: String,
    ) : DecryptResult

    /**
     * A value was stored but could not be decrypted. [causeType] is the simple class name of the
     * underlying exception (or a short description for format violations) — safe to log.
     */
    data class Failure(
        val reason: CredentialCorruption,
        val causeType: String,
    ) : DecryptResult
}

/**
 * AES-256-GCM encryption of credential strings.
 *
 * Stored layout: `Base64([ivLen (1 byte)] [iv] [ciphertext+tag])`, unchanged from earlier versions.
 */
class CredentialCipher(
    private val keyProvider: CredentialKeyProvider,
) {
    /**
     * Encrypts [plaintext]. If obtaining the key or initialising the cipher fails with an error that
     * [decrypt] would classify as [CredentialCorruption.KEY_INVALIDATED] (permanently invalidated or
     * unrecoverable key, possibly wrapped), the key is deleted and a fresh one is generated. This recovery
     * is attempted exactly once; any other failure, or a second failure, propagates.
     */
    fun encrypt(plaintext: String): String {
        val cipher =
            try {
                initEncryptCipher()
            } catch (e: GeneralSecurityException) {
                recoverEncryptCipher(e)
            } catch (e: ProviderException) {
                recoverEncryptCipher(e)
            }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        iv.copyInto(combined, destinationOffset = 1)
        ciphertext.copyInto(combined, destinationOffset = 1 + iv.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypts [encoded]. A missing value yields [DecryptResult.Missing]; malformed Base64 / layout and every
     * [GeneralSecurityException], [ProviderException] or [IOException] raised by the key provider or the
     * cipher are mapped to [DecryptResult.Failure]. Other (unexpected) runtime exceptions are not caught.
     */
    fun decrypt(encoded: String?): DecryptResult {
        if (encoded == null) return DecryptResult.Missing
        val combined =
            try {
                Base64.getDecoder().decode(encoded)
            } catch (e: IllegalArgumentException) {
                return DecryptResult.Failure(CredentialCorruption.UNREADABLE, e.javaClass.simpleName)
            }
        val ivLen = if (combined.isEmpty()) 0 else combined[0].toInt() and BYTE_MASK
        val ciphertextStart = 1 + ivLen
        if (ivLen != GCM_IV_LENGTH_BYTES || combined.size <= ciphertextStart) {
            return DecryptResult.Failure(CredentialCorruption.UNREADABLE, "InvalidFormat")
        }
        return try {
            val iv = combined.copyOfRange(1, ciphertextStart)
            val ciphertext = combined.copyOfRange(ciphertextStart, combined.size)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            DecryptResult.Success(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            DecryptResult.Failure(classify(e), e.javaClass.simpleName)
        } catch (e: ProviderException) {
            // The Keystore reports some key failures as unchecked ProviderExceptions.
            DecryptResult.Failure(classify(e), e.javaClass.simpleName)
        } catch (e: IOException) {
            // KeyStore.load() failed — the key cannot be reached.
            DecryptResult.Failure(classify(e), e.javaClass.simpleName)
        }
    }

    private fun initEncryptCipher(): Cipher =
        Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateKey())
        }

    private fun recoverEncryptCipher(failure: Exception): Cipher {
        if (classify(failure) != CredentialCorruption.KEY_INVALIDATED) throw failure
        keyProvider.deleteKey()
        return retryInitEncryptCipher(failure)
    }

    private fun retryInitEncryptCipher(originalFailure: Exception): Cipher =
        try {
            initEncryptCipher()
        } catch (e: GeneralSecurityException) {
            throw e.apply { addSuppressed(originalFailure) }
        } catch (e: ProviderException) {
            throw e.apply { addSuppressed(originalFailure) }
        }

    private fun classify(error: Throwable): CredentialCorruption {
        var current: Throwable? = error
        while (current != null) {
            if (isKeyLoss(current)) return CredentialCorruption.KEY_INVALIDATED
            current = current.cause?.takeIf { it !== current }
        }
        return CredentialCorruption.UNREADABLE
    }

    private fun isKeyLoss(error: Throwable): Boolean =
        error is KeyPermanentlyInvalidatedException ||
            error is UnrecoverableKeyException ||
            error is UnrecoverableEntryException
}
