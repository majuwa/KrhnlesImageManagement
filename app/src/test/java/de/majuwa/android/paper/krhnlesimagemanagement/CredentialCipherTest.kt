package de.majuwa.android.paper.krhnlesimagemanagement

import android.security.keystore.KeyPermanentlyInvalidatedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialCipher
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialKeyProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.DecryptResult
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnexpectedKeyEntryException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.SoftwareKeyProvider
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialCorruption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.SecretKey

/** Regression tests for issue #31 / #50: decryption failures must be classified, never swallowed into null. */
@RunWith(RobolectricTestRunner::class)
class CredentialCipherTest {
    private lateinit var keys: SoftwareKeyProvider
    private lateinit var cipher: CredentialCipher

    @Before
    fun setup() {
        keys = SoftwareKeyProvider()
        cipher = CredentialCipher(keys)
    }

    @Test
    fun `encrypt then decrypt round-trips including unicode`() {
        val secret = "pässwörd-🔐-123"
        assertEquals(DecryptResult.Success(secret), cipher.decrypt(cipher.encrypt(secret)))
    }

    @Test
    fun `encrypting the same value twice uses fresh IVs`() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `null input is missing, not a failure`() {
        assertEquals(DecryptResult.Missing, cipher.decrypt(null))
    }

    @Test
    fun `value encoded with android NO_WRAP layout stays readable`() {
        // The stored format must remain [ivLen][iv][ciphertext] in standard Base64 without line breaks.
        val encoded = cipher.encrypt("legacy")
        val raw = Base64.getDecoder().decode(encoded)
        assertEquals(12, raw[0].toInt())
        assertTrue('\n' !in encoded)
    }

    @Test
    fun `invalid base64 is unreadable`() {
        assertUnreadable(cipher.decrypt("%%% not base64 %%%"))
    }

    @Test
    fun `empty payload is unreadable`() {
        assertUnreadable(cipher.decrypt(""))
    }

    @Test
    fun `wrong iv length is unreadable`() {
        val raw = Base64.getDecoder().decode(cipher.encrypt("x"))
        raw[0] = 16
        assertUnreadable(cipher.decrypt(Base64.getEncoder().encodeToString(raw)))
    }

    @Test
    fun `truncated payload without ciphertext is unreadable`() {
        val raw = Base64.getDecoder().decode(cipher.encrypt("x")).copyOfRange(0, 13)
        assertUnreadable(cipher.decrypt(Base64.getEncoder().encodeToString(raw)))
    }

    @Test
    fun `tampered ciphertext fails GCM authentication and is unreadable`() {
        val raw = Base64.getDecoder().decode(cipher.encrypt("secret"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        val result = cipher.decrypt(Base64.getEncoder().encodeToString(raw))
        assertUnreadable(result)
        assertEquals("AEADBadTagException", (result as DecryptResult.Failure).causeType)
    }

    @Test
    fun `value encrypted with a lost key is unreadable`() {
        val encoded = cipher.encrypt("secret")
        keys.rotate()
        assertUnreadable(cipher.decrypt(encoded))
    }

    @Test
    fun `permanently invalidated key is reported as key invalidated`() {
        val encoded = cipher.encrypt("secret")
        keys.invalidated = true
        val result = cipher.decrypt(encoded)
        assertEquals(CredentialCorruption.KEY_INVALIDATED, (result as DecryptResult.Failure).reason)
        assertEquals("KeyPermanentlyInvalidatedException", result.causeType)
    }

    @Test
    fun `unrecoverable key wrapped in a provider exception is reported as key invalidated`() {
        val encoded = cipher.encrypt("secret")
        val throwing = CredentialCipher(ThrowingKeyProvider(ProviderException(UnrecoverableKeyException("gone"))))
        val result = throwing.decrypt(encoded)
        assertEquals(CredentialCorruption.KEY_INVALIDATED, (result as DecryptResult.Failure).reason)
    }

    @Test
    fun `other keystore errors are unreadable and never thrown`() {
        val encoded = cipher.encrypt("secret")
        assertUnreadable(CredentialCipher(ThrowingKeyProvider(KeyStoreException("boom"))).decrypt(encoded))
    }

    @Test
    fun `failure cause type never contains the stored value`() {
        val encoded = cipher.encrypt("top-secret")
        keys.rotate()
        val result = cipher.decrypt(encoded) as DecryptResult.Failure
        assertTrue(encoded !in result.causeType)
        assertTrue("top-secret" !in result.causeType)
    }

    @Test
    fun `encrypt recovers once from an invalidated key by regenerating it`() {
        keys.invalidated = true
        val encoded = cipher.encrypt("new-password")
        assertEquals(1, keys.deleteCount)
        assertEquals(DecryptResult.Success("new-password"), cipher.decrypt(encoded))
    }

    @Test
    fun `encrypt propagates when the regenerated key is invalid too`() {
        keys.invalidated = true
        keys.failuresAfterDelete = 1
        val error = assertThrows(KeyPermanentlyInvalidatedException::class.java) { cipher.encrypt("x") }
        assertEquals(1, keys.deleteCount)
        assertEquals(1, error.suppressed.size)
    }

    @Test
    fun `encrypt recovers once from a directly thrown unrecoverable key`() {
        keys.keyFailure = { UnrecoverableKeyException("gone") }
        val encoded = cipher.encrypt("new-password")
        assertEquals(1, keys.deleteCount)
        assertEquals(DecryptResult.Success("new-password"), cipher.decrypt(encoded))
    }

    @Test
    fun `encrypt recovers once from an unrecoverable key wrapped in a provider exception`() {
        keys.keyFailure = { ProviderException(UnrecoverableKeyException("gone")) }
        val encoded = cipher.encrypt("new-password")
        assertEquals(1, keys.deleteCount)
        assertEquals(DecryptResult.Success("new-password"), cipher.decrypt(encoded))
    }

    @Test
    fun `encrypt propagates a wrapped unrecoverable key that persists after regeneration`() {
        keys.keyFailure = { ProviderException(UnrecoverableKeyException("gone")) }
        keys.failuresAfterDelete = 1
        val error = assertThrows(ProviderException::class.java) { cipher.encrypt("x") }
        assertEquals(1, keys.deleteCount)
        assertEquals(1, error.suppressed.size)
    }

    @Test
    fun `encrypt does not delete the key for unrelated keystore errors`() {
        keys.keyFailure = { KeyStoreException("boom") }
        assertThrows(KeyStoreException::class.java) { cipher.encrypt("x") }
        assertEquals(0, keys.deleteCount)
    }

    @Test
    fun `keystore entry that is not a secret key is unreadable, not key invalidated`() {
        val encoded = cipher.encrypt("secret")
        val result =
            CredentialCipher(ThrowingKeyProvider(UnexpectedKeyEntryException("Keystore entry is not a secret key")))
                .decrypt(encoded)
        assertUnreadable(result)
        assertEquals("UnexpectedKeyEntryException", (result as DecryptResult.Failure).causeType)
    }

    private fun assertUnreadable(result: DecryptResult) {
        assertTrue("expected failure but was $result", result is DecryptResult.Failure)
        assertEquals(CredentialCorruption.UNREADABLE, (result as DecryptResult.Failure).reason)
    }

    private class ThrowingKeyProvider(
        private val error: Throwable,
    ) : CredentialKeyProvider {
        override fun getOrCreateKey(): SecretKey = throw error

        override fun deleteKey() = Unit
    }
}
