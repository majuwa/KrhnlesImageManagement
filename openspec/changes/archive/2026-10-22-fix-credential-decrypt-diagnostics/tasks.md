# Tasks

## 1. Cipher extraction and failure classification

- [x] 1.1 Add `model/CredentialState.kt` (`CredentialState`, `CredentialCorruption`) and `data/CredentialCipher.kt` (`CredentialKeyProvider`, `AndroidKeystoreKeyProvider`, `DecryptResult`, `CredentialCipher` with format-compatible encrypt/decrypt and one-shot key recovery on `KeyPermanentlyInvalidatedException`); verify it compiles
- [x] 1.2 Add `CredentialCipherTest` (Robolectric, software AES key): round-trip, null → Missing, malformed Base64 / wrong IV length / truncated → UNREADABLE, tampered ciphertext and wrong key → UNREADABLE, KPIE and UnrecoverableKeyException → KEY_INVALIDATED, encrypt recovers once after KPIE (deleteKey called) and propagates a second failure; verify `./gradlew test` passes

## 2. CredentialStore state, logging and repository API

- [x] 2.1 Refactor `CredentialStore` to use `CredentialCipher` (internal injectable), log failures via `Log.w` without secrets, add `credentialState` flow; add `credentialState` to `CredentialRepository` and update `FakeCredentialRepository` and `TrackingCredentialRepository`; verify compile
- [x] 2.2 Add `CredentialStoreTest` (Robolectric, injected software key): Missing when empty, Valid after save, Corrupted(UNREADABLE) with garbage prefs and `isConfigured` false without throwing, Corrupted(KEY_INVALIDATED) with an invalidated key provider, Valid again after re-save, warning logged without the ciphertext (ShadowLog); verify `./gradlew test` passes

## 3. Settings UI feedback

- [x] 3.1 Add strings `error_credentials_key_invalidated` and `error_credentials_unreadable`; add `SettingsUiState.credentialProblem`, collect `credentialState` in `SettingsViewModel`, render a banner in `LoginContent`; verify `lintDebug` has no hardcoded-string issues
- [x] 3.2 Add `SettingsViewModelTest` cases (problem set for each reason, cleared after save) and `SettingsScreenTest` cases (each message shown); verify `./gradlew test` passes

## 4. Documentation and verification

- [x] 4.1 Update `docs/architecture.md` (credential storage section, package tree) and `CLAUDE.md` security conventions; verify the text matches the implementation
- [x] 4.2 Run `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport` and verify all pass

## 5. Code review fixes

- [x] 5.1 Emission trigger: `save()`/`clear()` write secure prefs first and bump `credentials_version` in the same DataStore edit; VM resets `credentialProblem` on successful save; regression tests with the real `CredentialStore` and long-lived `backgroundScope` collectors (same-URL re-login → Valid / configured; clear → Missing)
- [x] 5.2 Encrypt recovery: key retrieval + `cipher.init` inside the recovery block using the shared `classify()` predicate; tests for direct and `ProviderException`-wrapped `UnrecoverableKeyException` (deleteKey exactly once), persistent failure propagates, unrelated errors do not delete the key
- [x] 5.3 VM error handling: `CredentialSaveException` caught in `startLoginFlow`/`saveManualConfig`, `R.string.error_credentials_save_failed` shown; test that a failing save shows the error and a retry succeeds
- [x] 5.4 Atomic save: encrypt both values before any write; test that a failed encrypt leaves URL and prefs unchanged
- [x] 5.5 Single snapshot flow per emission with `flowOn(ioDispatcher)` and deduplicated failure logging; test that a persisting failure is logged once
- [x] 5.6 `UnexpectedKeyEntryException` for a non-secret-key entry, classified UNREADABLE; test
- [x] 5.7 Precise `decrypt()` KDoc (no broad RuntimeException catch)
- [x] 5.8 Re-run `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport`

## Workflow follow-up

- Run code review and security review on the diff and fix valid findings.
- Archive the change and sync the `credential-storage` spec into `openspec/specs`.
