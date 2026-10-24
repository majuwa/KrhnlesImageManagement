## Why

Issue #11 (upload history) is already implemented: `UploadHistoryStore`, `UploadHistoryEntry`, `UploadHistoryScreen` and a history icon on the Photos tab exist, and `UploadWorker` records one entry per run. All four acceptance criteria are met in code, but nothing is specified, the store test drives the real process-wide DataStore delegate, there is no UI test, and three robustness/privacy gaps exist: the history file is included in cloud backup and device transfer although it holds user-written occasion names, entry ids are `System.currentTimeMillis()` (a collision crashes `LazyColumn` on duplicate keys and makes one delete remove both entries), and the log grows without bound.

## What Changes

- Exclude `datastore/upload_history.preferences_pb` from `backup_rules.xml` and both `data_extraction_rules.xml` sections (shared name constant, `BackupRulesTest` extended in all three places, test method names and KDoc renamed to cover the history file too).
- The store guarantees a collision-free id inside the same `edit` in `addEntry`, so list keys and single deletes are unambiguous; entries are also de-duplicated by id on read.
- Corrupt history file: explicit `uploadHistoryCorruptionHandler` (`ReplaceFileCorruptionHandler`), covered by a store test and a source-scan regression test.
- Retain at most the newest 500 entries; trimmed lazily on the next `addEntry`.
- Lock-in tests: store tests with an injected DataStore (`internal` constructor + `PreferenceDataStoreFactory` on a `TemporaryFolder`), a Compose UI test (fake repository) for newest-first order, counts, empty state, delete and clear-all, and worker tests for the remaining gaps.
- Docs: `docs/architecture.md` (data store, backup exclusion, retention, no clearing on logout) and the `CLAUDE.md` backup note.
- Out of scope: per-file results on tap, clearing history on logout (decided: not cleared; documented), accessibility polish (merged node, named delete action), Room.

## Capabilities

### New Capabilities
- `upload-history`: persisted, newest-first log of upload runs with counts, individual and bulk removal, bounded size, excluded from backup.

### Modified Capabilities

## Impact

- `data/UploadHistoryStore.kt`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`
- Tests: `UploadHistoryStoreTest`, `BackupRulesTest`, new `UploadHistoryScreenTest`, new corruption-handler regression test, worker test additions
- `docs/architecture.md`, `CLAUDE.md`
