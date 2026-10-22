package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import android.security.keystore.KeyPermanentlyInvalidatedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialKeyProvider
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * JVM-only stand-in for the Android Keystore (unavailable under Robolectric).
 *
 * Set [invalidated] to simulate a permanently invalidated key, or [keyFailure] for any other error:
 * [getOrCreateKey] then throws a fresh exception from the factory until [deleteKey] is called (or, with
 * [failuresAfterDelete], for that many further deletions).
 */
class SoftwareKeyProvider : CredentialKeyProvider {
    private var key: SecretKey? = null
    var keyFailure: (() -> Throwable)? = null
    var failuresAfterDelete = 0
    var deleteCount = 0
        private set

    var invalidated: Boolean
        get() = keyFailure != null
        set(value) {
            keyFailure = if (value) ({ KeyPermanentlyInvalidatedException() }) else null
        }

    override fun getOrCreateKey(): SecretKey {
        keyFailure?.let { throw it() }
        return key ?: generate().also { key = it }
    }

    override fun deleteKey() {
        deleteCount++
        key = null
        if (failuresAfterDelete > 0) failuresAfterDelete-- else keyFailure = null
    }

    /** Replaces the key with a new random one, as if the Keystore entry was lost and regenerated. */
    fun rotate() {
        key = generate()
    }

    private fun generate(): SecretKey = KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }.generateKey()

    private companion object {
        const val KEY_SIZE_BITS = 256
    }
}
