## 1. Backup exclusion

- [x] 1.1 Add `UPLOADED_PHOTOS_DATASTORE_NAME = "uploaded_photos"` (internal/public const next to the other store names) and use it in `UploadedPhotosStore`
- [x] 1.2 Exclude `datastore/uploaded_photos.preferences_pb` in `backup_rules.xml` and in the `cloud-backup` and `device-transfer` sections of `data_extraction_rules.xml`
- [x] 1.3 Extend `BackupRulesTest.expected` with the shared constant (all three sections); update its class comment and test names that mention only credentials and the onboarding flag

## 2. Store tests

- [x] 2.1 `UploadedPhotosStore`: `internal constructor(dataStore: DataStore<Preferences>)` + public `constructor(context)` using the process delegate (KDoc: internal ctor is for tests); add `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }` to the delegate
- [x] 2.2 `UploadedPhotosStoreTest` (plain JVM, `TemporaryFolder` + `PreferenceDataStoreFactory.create`, `runTest(UnconfinedTestDispatcher())`): empty by default; `markAsUploaded` accumulates; empty set is a no-op; restart (DataStore A in own scope, cancel + join, DataStore B on same file); `clear()` empties; non-numeric entries (seeded via `edit` on `uploaded_ids`) skipped; video-flagged ids (`1L shl 62`) round-trip; garbage file -> empty set and `markAsUploaded` works afterwards

## 3. Verify existing coverage

- [x] 3.1 Confirm `UploadWorkerRunTest`, `UploadWorkerStagedTest`, `PhotoGridViewModelTest`, `PhotoGridAccessibilityTest`, `PhotoGridPermissionTest`, `SettingsViewModelTest` cover every scenario in `specs/uploaded-photo-markers`; shared photo id -1 not marked is covered by `UploadWorkerStagedTest` (`markCalls == 0`); no new case planned

## 4. Docs and checks

- [x] 4.1 `docs/architecture.md`: note the backup exclusion in the Uploaded Badge section / persistence decisions
- [x] 4.2 `README.md`: Duplicate-Upload Prevention paragraph: re-uploading marked photos stays possible; markers are not restored from backup or on a new device
- [x] 4.3 `./gradlew test`, `spotlessCheck`, `detekt`, `lintDebug`, `jacocoTestReport` pass

## 5. Review fixes

- [x] 5.1 `uploadedPhotosCorruptionHandler` extracted (internal, stateless, shared by delegate and test); `UploadedPhotosCorruptionHandlerRegressionTest` source-scans the delegate block
- [x] 5.2 `CLAUDE.md` backup-exclusion bullet covers the markers DataStore, the handler and the test constructor
- [x] 5.3 `BackupRulesTest` KDoc mentions the markers; `design.md` aligned with `backgroundScope` use
