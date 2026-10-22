# credential-storage Specification

## Purpose

Defines how the app stores WebDAV credentials at rest and how it reports and recovers from credentials that exist but can no longer be decrypted.

## Requirements

### Requirement: Credential health is reported as missing, valid, or corrupted
The system SHALL expose the health of the stored credentials as one of: missing (nothing stored), valid (all stored values decrypt), or corrupted (at least one stored value exists but cannot be decrypted). A corrupted state SHALL carry a reason: key invalidated, or unreadable data.

#### Scenario: No credentials stored
- **WHEN** no username or password has been saved
- **THEN** the credential state is missing

#### Scenario: Credentials decrypt successfully
- **WHEN** a username and password were saved and both decrypt
- **THEN** the credential state is valid

#### Scenario: Stored ciphertext is malformed or fails authentication
- **WHEN** a stored credential value is not valid encrypted data, or its authentication tag does not verify
- **THEN** the credential state is corrupted with reason unreadable data

#### Scenario: Keystore entry is not a secret key
- **WHEN** the Keystore entry under the credential key alias exists but is not a secret key
- **THEN** the credential state is corrupted with reason unreadable data

#### Scenario: Encryption key permanently invalidated
- **WHEN** decrypting a stored credential fails because the Keystore key was permanently invalidated or is unrecoverable
- **THEN** the credential state is corrupted with reason key invalidated

### Requirement: Corrupted credentials fail closed
The system SHALL treat corrupted credentials as not configured: they SHALL NOT be used for WebDAV requests, and reading them SHALL NOT crash the app.

#### Scenario: Corrupted password
- **WHEN** the stored password cannot be decrypted
- **THEN** the app reports it is not configured and no exception propagates to the caller

### Requirement: Decryption failures are logged without secrets
The system SHALL log a warning when a credential decryption failure occurs, identifying the affected field and the failure type, and SHALL NOT log plaintext or ciphertext values. A failure that persists unchanged across repeated reads SHALL be logged only once until that field's outcome changes.

#### Scenario: Failure is logged
- **WHEN** decrypting a stored credential fails
- **THEN** a warning is logged naming the field and the failure type, and the log contains neither the stored ciphertext nor any plaintext

#### Scenario: Persisting failure is not logged repeatedly
- **WHEN** the same stored credential fails to decrypt on several consecutive reads
- **THEN** exactly one warning is logged for that field

### Requirement: User is told why stored credentials were lost
When the credential state is corrupted, the Settings screen SHALL show a user-facing message explaining that stored credentials could not be decrypted and that the user must log in again. For reason key invalidated it SHALL state that device security settings changed.

#### Scenario: Key invalidated message
- **WHEN** the user opens Settings and the credential state is corrupted with reason key invalidated
- **THEN** the screen shows "Your stored credentials could not be decrypted because the device security settings changed. Please log in again."

#### Scenario: Unreadable data message
- **WHEN** the user opens Settings and the credential state is corrupted with reason unreadable data
- **THEN** the screen shows a message that stored credentials could not be read and the user must log in again

#### Scenario: Message disappears after re-login
- **WHEN** the user logs in again successfully after a corrupted state
- **THEN** the credential state becomes valid and the message is no longer shown

### Requirement: Saving recovers from an invalidated encryption key
When saving credentials fails because the encryption key is permanently invalidated or unrecoverable — whether raised while obtaining the key or initialising the cipher, directly or wrapped in another exception — the system SHALL discard that key once, generate a new one, and save the credentials with it. Failures not caused by key loss SHALL NOT discard the key.

#### Scenario: Re-login after key invalidation
- **WHEN** the encryption key has been permanently invalidated and the user saves new credentials
- **THEN** the credentials are stored successfully and subsequently decrypt as valid

#### Scenario: Unrecoverable key reported while obtaining the key
- **WHEN** obtaining the key throws an unrecoverable-key error, directly or wrapped in a provider exception, and the user saves new credentials
- **THEN** the key is discarded exactly once and the credentials are stored successfully

### Requirement: Saving credentials is atomic and failures are reported
The system SHALL encrypt all credential values before writing any of them, so that a failed save leaves the previously stored credentials and server URL unchanged. A save that fails SHALL show a user-facing error in Settings and SHALL NOT crash the app; the user SHALL be able to retry.

#### Scenario: Encryption fails during save
- **WHEN** encrypting the new credentials fails
- **THEN** the previously stored username, password and server URL are unchanged

#### Scenario: Save fails after key recovery
- **WHEN** saving fails even after the one-time key recovery
- **THEN** Settings shows an error that the credentials could not be stored, the user stays logged out, and the app does not crash

### Requirement: Observers see credential changes immediately
After credentials are saved or cleared, every active observer of the credential state, the configured flag and the WebDAV configuration SHALL receive the updated values, including when the server URL did not change.

#### Scenario: Re-login with the same server URL
- **WHEN** the stored credentials are corrupted, an observer is active, and the user saves credentials with the same server URL
- **THEN** the observer receives credential state valid and configured true without restarting the app
