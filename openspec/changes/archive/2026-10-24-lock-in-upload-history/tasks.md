## 1. Privacy: backup exclusion

- [x] 1.1 Add internal `UPLOAD_HISTORY_DATASTORE_NAME` and use it in the `uploadHistoryDataStore` delegate
- [x] 1.2 Exclude `datastore/upload_history.preferences_pb` in `backup_rules.xml` and the `cloud-backup` and `device-transfer` sections of `data_extraction_rules.xml`
- [x] 1.3 Extend `BackupRulesTest` expected list in all three tests; rename the three test methods and their KDoc to cover the history file

## 2. Store hardening

- [x] 2.1 Add `internal constructor(DataStore<Preferences>)`; public `(Context)` constructor uses the process-wide delegate
- [x] 2.2 Add `internal val uploadHistoryCorruptionHandler` (`ReplaceFileCorruptionHandler { emptyPreferences() }`) and use it in the delegate
- [x] 2.3 In `addEntry`, within the same `edit`: make colliding ids unique (`maxOf(entry.id, maxExisting + 1)`) and cap the list at the 500 newest entries
- [x] 2.4 `decodeEntries` keeps the newest entry per id (stable sort by timestamp desc, then `distinctBy`, log exception/type only)

## 3. Tests

- [x] 3.1 Replace `UploadHistoryStoreTest` with `PreferenceDataStoreFactory` on a `TemporaryFolder` (no real delegate under `runBlocking`); cases: same-timestamp ids distinct, delete removes one, cap 500 (existing >500 trimmed on next add), corrupt `.preferences_pb` with the handler gives empty list, corrupt JSON gives empty, invalid entries filtered, persisted duplicate ids give no duplicates and `removeEntry` removes only one, persistence across instances (cancel and join the first DataStore's scope before reopening); make `uploadHistoryDataStore` private
- [x] 3.2 Add a source-scan regression test like `UploadedPhotosCorruptionHandlerRegressionTest` for `uploadHistoryCorruptionHandler`
- [x] 3.3 Add `UploadHistoryScreenTest` (Robolectric Compose) with a fake `UploadHistoryRepository` via the `@JvmOverloads` ViewModel constructor: newest-first order, counts text, empty state, persisted duplicates render, delete, clear-all with confirm
- [x] 3.4 Worker tests only for real gaps (check first, reference existing `UploadWorkerRunTest` / `UploadWorkerForegroundTest` / `UploadQueueHistoryLabelTest` coverage instead of duplicating): a persist failure does not change the result or notification; a retry run adds its own entry

## 4. Docs and verification

- [x] 4.1 Update `docs/architecture.md` (history store, backup exclusion, retention, ids, not cleared on logout) and the `CLAUDE.md` backup note
- [x] 4.2 Run `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport`

## 5. Review fixes

- [x] 5.1 Cap the single-folder history label at `MAX_FOLDER_HISTORY_LABEL_LENGTH` (1000); per-photo label stays at 200; `UploadQueueHistoryLabelTest` updated
- [x] 5.2 Deterministic newest-wins dedupe on read (stable sort by timestamp desc, then `distinctBy`), logging only the dropped count; store test, design, spec and docs updated
- [x] 5.3 Rename the misleading screen test to `singleEntryRendersOnce`
- [x] 5.4 Comment the theoretical `Long.MAX_VALUE` overflow in `addEntry`
- [x] 5.5 Document in `docs/architecture.md` and `README.md` that history survives logout
- [x] 5.6 Re-run `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport` and `openspec validate`
