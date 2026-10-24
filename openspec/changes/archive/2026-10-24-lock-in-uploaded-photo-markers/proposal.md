## Why

GitHub issues #13 and #32 (duplicates) ask for duplicate-upload prevention: remember which local photos were uploaded, mark them in the grid, offer a "show only new" filter, and reset the tracking when the server config is cleared. Verification against the code shows the feature exists (`UploadedPhotosStore`, worker marking per successful entry, cloud badge with TalkBack state, funnel filter, reset in `SettingsViewModel.logout()`). Two things are missing: no test exercises the real `UploadedPhotosStore` (restart persistence is only covered through fakes), and the `uploaded_photos` DataStore is not excluded from backup, so after a restore or device transfer the stored MediaStore ids point to different photos and the badge would mark the wrong ones, violating "badge shown for the correct photos only". This change locks the behaviour in and closes that hole.

## What Changes

- Add an `uploaded-photo-markers` capability spec covering marking after success, persistence, correct-photos-only (incl. videos, failed photos, shared photos), re-upload stays allowed, the "show only new" filter, and reset on logout.
- Exclude `datastore/uploaded_photos.preferences_pb` from cloud backup and device transfer (`backup_rules.xml`, both sections of `data_extraction_rules.xml`); extend `BackupRulesTest`.
- Add a `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }` to the `uploaded_photos` delegate so an unreadable file yields an empty set instead of crashing the grid / worker.
- Add `UploadedPhotosStoreTest` (plain JVM, real DataStore on a temp file): empty by default, marks accumulate, survive a restart (second DataStore on the same file), `clear()` empties, non-numeric entries skipped, empty `markAsUploaded` is a no-op, corrupt file -> empty set and `markAsUploaded` works afterwards.
- Document in `docs/architecture.md` and `README.md`.
- Closes #13 and #32 (both).
- Out of scope (not in the criteria): confirmation dialog when the selection contains uploaded photos (the issue comment says re-upload to another album must stay possible, and it is), resetting marks on a server change without logout (in the logged-in UI the server can only change via logout), a manual "reset marks" action, MediaStore id reuse / content hashes, per-server tracking, persisting the filter state.

## Capabilities

### New Capabilities
- `uploaded-photo-markers`: local tracking of uploaded photos, grid badge, "show only new" filter, reset and backup exclusion.

### Modified Capabilities

None. `photo-grid-accessibility` and `video-support` stay as they are and are referenced.

## Impact

- Code: `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`; `UploadedPhotosStore` (shared file-name constant, internal test constructor, corruption handler; no other behaviour change).
- Tests: new `UploadedPhotosStoreTest`, `BackupRulesTest` (class comment and test names updated: no longer only credentials + onboarding).
- Docs: `docs/architecture.md`, `README.md`.
- Behaviour change: after a restore / device transfer the uploaded markers start empty.
