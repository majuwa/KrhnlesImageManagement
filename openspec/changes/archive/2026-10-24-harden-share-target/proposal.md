## Why

Issues #16 and #35 ask for an Android share target. `MainActivity` already declares `SEND` / `SEND_MULTIPLE` filters for `image/*` and shows the occasion dialog, but the flow is unreliable: the read grant a sender attaches to a shared URI is revoked when the receiving activity is finished/destroyed, and the activity calls `finish()` right after enqueuing, so the WorkManager upload (possibly started later, on Wi-Fi, or after process death) can fail with `SecurityException`. The exported entry point also trusts its input more than it should (no mime check, no count or size bound, provider queries on the main thread, no not-configured handling). Shared entries additionally get `id = index` in `ShareIntentParser`, so the worker's `markAsUploaded` can mark unrelated MediaStore photos.

## What Changes

- Copy shared photos into app-private staging storage (`noBackupFilesDir/share_staging`) while the grant is still valid (before `finish()`) and upload from there. Copying is cancellable, shows "Preparing x of n", is bounded in space, and cleans up after itself.
- Read shared URIs from `EXTRA_STREAM`; keep only image items (verified content type), at most 100 per share, each at most 100 MiB, 1 GiB per share; show a message when items were dropped.
- Query provider metadata and copy off the main thread; keep the share state in a ViewModel so rotation neither loses nor repeats the copy.
- Shared entries use `id = -1` so the worker never marks MediaStore rows (fixes an existing bug).
- If no server is configured, show a Toast/dialog instead of the occasion dialog. A share with no usable photo shows a Toast and finishes.
- Unchanged: the filters, the occasion dialog (also when auto date folders are on), `isAllowedPhotoUri` (only foreign `content://`), and returning to the sharing app by `finish()`.

## Capabilities

### New Capabilities
- `share-target`: receiving images from the system share sheet and uploading them reliably.

### Modified Capabilities
None. (`failed-photo-retry` behaviour is only touched indirectly: staged photos can be retried.)

## Impact

- `MainActivity`, `ui/share/` (new `ShareViewModel`), `util/ShareIntentParser.kt`, new staging helper, `upload/UploadEnqueuer.kt`, `worker/UploadQueue.kt`, `worker/UploadWorker.kt`, `worker/QueueFiles.kt`, `strings.xml`, `README.md`, `CLAUDE.md` (rule 4: staged exception), `docs/architecture.md`.
- Queue file format gains an optional per-entry `staged` name (older files keep working).
- Not in scope: new filters, video, ClipData fallback, a separate share activity, per-photo folders for shares, `takePersistableUriPermission` (share grants are not persistable), `FileProvider`.
