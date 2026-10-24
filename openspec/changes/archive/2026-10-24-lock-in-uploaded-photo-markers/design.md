## Context

Verified against the code (met unless noted):
- Record after success: `UploadWorker` calls `uploadedPhotos.markUploaded(entry.id)` right after each successful entry (id < 0 skipped); failures not marked (`UploadWorkerRunTest`: `setOf(1L, 3L)`, retry run marks only new successes).
- Badge + TalkBack: `PhotoItem` shows the cloud icon (decorative) and `stateDescription` "..., already uploaded" (`PhotoGridAccessibilityTest`); correct photo/video distinction tested in `PhotoGridPermissionTest.uploadedVideoWithTheSameRawIdAsAPhoto...`.
- Filter: `PhotoGridViewModel.toggleShowOnlyNewPhotos()` + funnel button (hidden in selection mode or when nothing is uploaded); covered by `PhotoGridViewModelTest` (hide, restore, empty groups, no-op).
- Reset: `SettingsViewModel.logout()` clears the store; `SettingsViewModelTest` covers it.
- Re-upload: nothing blocks selecting uploaded items (matches the issue comment; no dialog needed).
- Gap 1: `UploadedPhotosStore` itself has no test; persistence is only proven via fakes.
- Gap 2: `backup_rules.xml` / `data_extraction_rules.xml` exclude credentials and `app_preferences` but not `uploaded_photos`; a restored set maps ids to different photos on another device.

## Goals / Non-Goals

**Goals:** spec, real-store tests, backup exclusion, corruption handler, docs.
**Non-Goals:** confirmation dialogs, server-change reset (in the logged-in UI the server only changes via logout; exception: the corrupted-credentials login path keeps old markers), manual reset, content hashes, per-server tracking, filter persistence (see proposal).

## Decisions

- Exclude the file rather than re-key: ids are only meaningful on the device that produced them, and the existing backup-exclusion pattern (`BackupRulesTest`) already guards sibling files. Trade-off: after a reinstall with restored backup the badges are lost, which is the safe direction (no wrong badge).
- Store construction: `class UploadedPhotosStore internal constructor(private val dataStore: DataStore<Preferences>)` plus public `constructor(context: Context) : this(context.applicationContext.uploadedPhotosDataStore)`. KDoc: the internal constructor is for tests; production uses the process delegate.
- Corruption: the delegate gets `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }`; an unreadable file means an empty set (markers are a convenience, losing them is safe).
- `UploadedPhotosStoreTest` is a plain JVM test (like `MediaPermissionStoreTest`): `TemporaryFolder` + `PreferenceDataStoreFactory.create(scope = ..., produceFile = ...)` under `runTest(UnconfinedTestDispatcher())`, with `backgroundScope` as the DataStore scope (the restart test uses its own `Job`-backed scope instead). Never the real process delegate, never `runBlocking`.
- Restart test: write via DataStore A in its own `CoroutineScope(Job() + dispatcher)`, `cancel()` + `join()` that job, then create DataStore B on the same file and read. Never two active DataStores on one file.
- Non-numeric entries are seeded with `dataStore.edit { it[stringSetPreferencesKey("uploaded_ids")] = setOf("x", "5") }`; expect only 5.
- Corrupt-file test: write garbage bytes to the file before creating the DataStore with the handler; expect an empty set and a working `markAsUploaded` afterwards.
- A shared constant `UPLOADED_PHOTOS_DATASTORE_NAME` (next to `CREDENTIALS_DATASTORE_NAME`) is REQUIRED; `BackupRulesTest` uses it and covers all three places (`backup_rules.xml`, `cloud-backup`, `device-transfer`).
- No suppressions; no behaviour change besides the backup rule.

## Risks / Trade-offs

- A worker already running at logout may call `markAsUploaded` after `clear()`: rare, documented, not handled.
- MediaStore ids are AUTOINCREMENT and only reused after a media DB rebuild: accepted.
- Users who relied on restored markers lose them once; acceptable and documented.
