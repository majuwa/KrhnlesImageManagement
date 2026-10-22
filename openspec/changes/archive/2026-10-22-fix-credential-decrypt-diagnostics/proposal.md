# Proposal

## Why

Fixes GitHub issue #31 (duplicate: #50). `CredentialStore.decryptValue()` swallows every decryption failure via `runCatching { … }.getOrNull()`. A permanently invalidated Keystore key, a lost/regenerated key, a malformed ciphertext or an AES-GCM tag mismatch all collapse into `null`, which makes `isConfigured` emit `false`. The user is silently logged out with no explanation, and nothing is logged, so the failure cannot be diagnosed.

## What Changes

- Decryption failures are logged as warnings (exception type and which field failed; never the plaintext or ciphertext).
- Credential reads distinguish three states: credentials **missing**, **valid**, or **corrupted** (with a reason: key invalidated vs. unreadable data). This is exposed as a `credentialState` flow on the credential repository.
- The Settings screen shows a clear, localized message when stored credentials exist but cannot be decrypted, telling the user why they were logged out and to log in again. Invalidated keys get the dedicated message from the issue ("…device security settings changed…").
- Saving credentials after a key was permanently invalidated or became unrecoverable (also when wrapped in another exception, and also when raised while fetching the key) recovers automatically by discarding the dead key and generating a new one, so re-login actually works.
- Saving is atomic with respect to encryption: both values are encrypted before anything is written, so a failed save leaves the previous credentials and server URL untouched. A save that still fails shows a localized error in Settings instead of crashing.
- Credential-derived flows (`credentialState`, `isConfigured`, `webDavConfig`) are re-emitted after every save/clear — even when the server URL is unchanged — so long-lived observers see a re-login immediately instead of only after a process restart.
- Behaviour on failure stays fail-closed: corrupted credentials are never used for uploads and the app does not crash.

## Capabilities

### New Capabilities
- `credential-storage`: How the app persists WebDAV credentials and reports their health (missing / valid / corrupted), including diagnostics and user feedback when stored credentials can no longer be decrypted.

### Modified Capabilities
<!-- none -->

## Impact

- `data/CredentialStore.kt` (decrypt/encrypt logic extracted into a testable cipher helper, new `credentialState` flow, logging, key-recovery on save).
- `data/Repositories.kt` (`CredentialRepository.credentialState`, `CredentialSaveException`), new `model/CredentialState.kt`.
- `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt`, `res/values/strings.xml`.
- Test fakes implementing `CredentialRepository` (`FakeCredentialRepository`, `TrackingCredentialRepository` in `SettingsViewModelTest`).
- No new dependencies; stored data format is unchanged (existing ciphertexts remain readable).
