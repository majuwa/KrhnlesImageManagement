package de.majuwa.android.paper.krhnlesimagemanagement.model

/** Why stored credentials exist but cannot be decrypted. */
enum class CredentialCorruption {
    /** The Keystore key was permanently invalidated or is unrecoverable (e.g. device security settings changed). */
    KEY_INVALIDATED,

    /** The stored data is malformed or fails authentication (tampered, truncated, or encrypted with another key). */
    UNREADABLE,
}

/** Health of the credentials persisted on the device. */
sealed interface CredentialState {
    /** Nothing (or not everything) has been stored, but nothing stored is corrupted. */
    data object Missing : CredentialState

    /** All stored credential values decrypt successfully. */
    data object Valid : CredentialState

    /** At least one stored value exists but cannot be decrypted. */
    data class Corrupted(
        val reason: CredentialCorruption,
    ) : CredentialState
}
