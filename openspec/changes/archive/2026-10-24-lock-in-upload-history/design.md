## Context

Issue #11 acceptance criteria vs. code (verified):
- Persisted across restarts: met (`UploadHistoryStore`, DataStore `upload_history`, JSON array).
- Newest first: met (`sortedByDescending`; tested).
- Folder, timestamp, success/failure counts: met in `UploadHistoryItem`.
- Clear individually or all: met (delete icon, confirm dialog).
- Entry point: met (History icon in the Photos top bar; route `photos/history`).
Gaps: backup exclusion, id collisions, unbounded growth, corruption handler, test quality, no UI test. `decodeEntries` returns an empty list and logs only the exception type (verified). A corrupt file read by DataStore itself (`CorruptionException`) has no handler, unlike `uploaded_photos`.

## Goals / Non-Goals

**Goals:** close the gaps above with minimal change. **Non-Goals:** per-file results, Room, logout clearing, pagination, accessibility polish.

## Decisions

1. **Backup exclusion**: add `UPLOAD_HISTORY_DATASTORE_NAME = "upload_history"` (internal const, like `UPLOADED_PHOTOS_DATASTORE_NAME`), used by the delegate and `BackupRulesTest`; exclude in `backup_rules.xml` and both extraction sections. Rename the three `BackupRulesTest` methods and their KDoc so they no longer mention only the credentials/uploaded-photos files. Already-uploaded backups cannot be purged; acceptable.
2. **Not cleared on logout** (decided): history is a user-facing local log, not credentials or server-bound state; the user has Clear all. Documented in `docs/architecture.md`.
3. **Logging**: type-only in store and worker; keep. Dedupe logging also logs the type only.
4. **Unique id in the store**: `addEntry` computes, inside the same `edit`, `id = maxOf(entry.id, maxExistingId + 1)` when the id collides with a stored one. Atomic because there is a single process-wide delegate and DataStore serializes edits. Model and JSON format unchanged; the worker keeps passing `now`.
5. **Dedupe on read**: `decodeEntries` keeps the newest entry per id (sorted by timestamp descending, then `distinctBy { it.id }`; logs only the number of dropped entries); the dropped duplicate is removed on the next write, so already-persisted duplicates cannot crash `LazyColumn` and `removeEntry` removes just one.
6. **Retention**: cap 500, applied lazily in `addEntry` after sorting by timestamp, inside the same `edit`. An existing store with more than 500 entries is trimmed on the next `addEntry`, not on read.
7. **Corruption handler**: `internal val uploadHistoryCorruptionHandler = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }`, used by the delegate (like `uploaded_photos`); the log is a convenience.
8. **Testability**: `UploadHistoryStore` gets an `internal constructor(DataStore<Preferences>)`; the public `(Context)` constructor uses the process-wide delegate (`ViewModelFactoryRegressionTest` stays green). Tests use `PreferenceDataStoreFactory` on a `TemporaryFolder`; the existing `UploadHistoryStoreTest` that drives the real delegate under `runBlocking` is replaced, and `uploadHistoryDataStore` becomes `private` once no test uses it. "Persists across instances" cancels and joins the first DataStore's scope before reopening the file (never two active DataStores on one file). The screen test injects a fake `UploadHistoryRepository` through the existing `@JvmOverloads` ViewModel constructor.

## Risks / Trade-offs

- Dropping entries over 500 is silent; acceptable for a log.
- Unique-id adjustment differs from the timestamp only on collision; sorting uses `timestampMillis`, so order is unaffected.
- Clock moving backwards: a new entry can have a smaller timestamp than stored ones and be sorted below them, so it may be the one trimmed at the cap. Accepted.
- Plural-less `upload_history_counts` is kept (existing `tools:ignore`); not in scope.
