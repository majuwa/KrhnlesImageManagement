# Design

## Context

`CredentialStore` (see proposal.md - Why) encrypts username/password with an AES-256-GCM key held in the Android Keystore under `krhnles_master_key`, storing `Base64([ivLen][iv][ciphertext])` in the `krhnles_secure` SharedPreferences. `decryptValue()` returns `null` on any failure, which is indistinguishable from "never saved". The Keystore itself is not available under Robolectric, so the crypto path is currently untested. `minSdk` is 35.

## Goals / Non-Goals

**Goals:**
- Make decryption outcomes explicit (missing / success / failure with reason) and unit-testable on the JVM.
- Log failures, expose `credentialState`, surface a localized Settings message.
- Make re-login work after a permanent key invalidation.

**Non-Goals:**
- Changing the on-disk format or key parameters (existing data must stay readable).
- Automatically deleting corrupted ciphertexts on read (the user's next login overwrites them; logout already clears them).
- Adding crash reporting / telemetry.

## Decisions

1. **Extract `CredentialCipher` with an injectable `CredentialKeyProvider`.** `CredentialCipher.encrypt(plaintext)` / `decrypt(encoded): DecryptResult` contain the format logic; `AndroidKeystoreKeyProvider` implements `getOrCreateKey()` and `deleteKey()`. Tests inject a software AES key provider. *Alternative:* mocking `KeyStore`/`Cipher` statics with MockK — brittle and doesn't exercise real GCM behaviour.
2. **`DecryptResult` sealed type**: `Missing`, `Success(value)`, `Failure(reason, cause)`. Format violations (bad Base64, wrong IV length, too short), `AEADBadTagException`/other `GeneralSecurityException`s, and `UnexpectedKeyEntryException` (the alias holds an entry that is not a secret key — a distinct `KeyStoreException` subclass, not an `UnrecoverableKeyException`) map to `UNREADABLE`; `KeyPermanentlyInvalidatedException` and `UnrecoverableKeyException` (anywhere in the cause chain) map to `KEY_INVALIDATED`. *Alternative:* classify a GCM tag mismatch as key-invalidated (it also happens when the key was silently regenerated) — rejected because it is equally consistent with tampered/corrupted data; the "unreadable" message still asks the user to log in again.
3. **`CredentialState` model** (`model/CredentialState.kt`): `Missing`, `Valid`, `Corrupted(reason: CredentialCorruption)`. Computed from the two decrypt results: any failure → `Corrupted` (key-invalidated wins over unreadable); both missing → `Missing`; otherwise `Valid` (a single missing field with the other valid is reported as `Missing` since no value is corrupted — `isConfigured` already handles incompleteness). Exposed as `CredentialRepository.credentialState: Flow<CredentialState>`.
   `decrypt()` maps missing values, format violations and every `GeneralSecurityException` / `ProviderException` / `IOException` to a result; other runtime exceptions are deliberately not caught (a broad catch would need a detekt suppression) and the KDoc says so precisely.
4. **Logging** (once per distinct failure per field; repeated reads of the same failure do not log again until the field's outcome changes) in `CredentialStore` via `Log.w(TAG, "Failed to decrypt stored <field> (<reason>): <ExceptionClass>")`. The throwable is not attached because Keystore exception messages can include key alias/provider details that add no value; the class name and reason are sufficient. No ciphertext/plaintext is ever logged.
5. **Key recovery on encrypt**: key retrieval (`getOrCreateKey()`) *and* `cipher.init(ENCRYPT_MODE, key)` run inside the recovery block. If either throws a `GeneralSecurityException`/`ProviderException` that `classify()` maps to `KEY_INVALIDATED` (the same cause-chain predicate decrypt uses), call `deleteKey()` and retry exactly once; the original failure is attached as suppressed to a second failure, which propagates. Errors not classified as key loss propagate without deleting the key. Decrypt never deletes the key (it would destroy the other, possibly still-valid, value).
6. **UI**: `SettingsUiState.credentialProblem: CredentialCorruption?` set from `credentialState`, and reset to `null` directly when a save succeeds (so the banner disappears even if a flow emission lags). A save that throws `CredentialSaveException` sets `error` to `R.string.error_credentials_save_failed` (resolved via the `Application`), resets the loading/browser flags and keeps the user logged out so they can retry. `LoginContent` shows an error-container banner with `R.string.error_credentials_key_invalidated` or `R.string.error_credentials_unreadable`. The ViewModel stays string-free (enum → string resource mapping happens in Compose), following the strings.xml rule.

7. **Snapshot flow on IO**: one private `snapshot: Flow<CredentialSnapshot>` maps each DataStore emission to `serverUrl`, `baseFolder` and the two `DecryptResult`s, decrypted once per emission and `flowOn(ioDispatcher)` (injectable via the internal constructor, default `Dispatchers.IO`) so Keystore work stays off the main thread. `credentialState`, `isConfigured`, `webDavConfig` and `username` all derive from it. *Alternative:* `shareIn`/`stateIn` to share across collectors — rejected because the store has no lifecycle scope.
8. **Emission trigger**: the encrypted values live in SharedPreferences, which DataStore does not observe. Every `save()` / `clear()` therefore bumps a `credentials_version` long in the same DataStore edit (for `clear()` the old value is read before clearing), guaranteeing an emission even when the server URL is unchanged.
9. **Atomic save ordering**: `save()` runs on `ioDispatcher`: encrypt username and password → `commit()` both to the secure prefs (result checked) → DataStore edit (`server_url` + version bump). An encryption failure therefore changes nothing; the DataStore emission is guaranteed to observe the new prefs. Encryption/IO failures are wrapped in `CredentialSaveException`. `clear()` uses `apply()` (in-memory update is synchronous, so the subsequent emission already sees cleared prefs).

## Risks / Trade-offs

- [Each collector of a derived flow decrypts once per emission] → Acceptable; emissions are infrequent and logging is deduplicated.
- [Secure prefs committed but the following DataStore edit fails] → New credentials with the previous URL; the save reports an error and the user retries. Encryption failures, the realistic case, leave everything unchanged.
- [`UnrecoverableKeyException` may also mean a transient Keystore issue] → Message still asks for re-login, which is the only recovery available anyway.
- [Constructor injection for the cipher widens `CredentialStore` API] → The parameter is `internal` with a production default, so call sites do not change.

## Migration Plan

No data migration. Rollback is a plain revert; stored data format is unchanged.
