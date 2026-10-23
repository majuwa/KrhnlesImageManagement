# Proposal

## Why

Issues #19 / #38: after a partial upload (for example 3 of 20 photos fail on a network blip) the user must be able to retry only the failed photos. The code already writes a retry queue file and shows a "Retry Failed (Y)" notification action handled by `RetryUploadReceiver`, but the flow has gaps: a retry ignores the Wi-Fi-only setting (and the original upload reads the setting through a `stateIn(initialValue = false)` that can race), an unreadable photo URI crashes the whole worker instead of counting as a failed photo, result notifications reuse the foreground id 1001 and the retry `PendingIntent` is shared by all batches (auto date folders) so a later batch overwrites an earlier one, a missing or corrupt queue file crashes the worker, the receiver trusts any file path under `filesDir`, the folder-creation-failure notification shows raw exception text, and the worker cannot be tested end to end because it builds its Keystore-backed dependencies itself.

## What Changes

- Make the existing retry flow meet the acceptance criteria reliably; no new UI screen, badge or setting.
- A photo whose content URI cannot be opened (`SecurityException`, `FileNotFoundException`, `IllegalArgumentException`) counts as a failed photo and stays in the retry queue; progress updates also run for failed photos.
- Retry and the original upload honour the Wi-Fi-only setting through one shared `uploadConstraints(wifiOnly)` helper and a bounded read (3 s, fallback unmetered). `RetryUploadReceiver` uses `goAsync()` with its own scope; Enqueueing moves into a process-scoped `UploadEnqueuer` (no `stateIn(initialValue = false)`, no work in `lifecycleScope`), and a worker started on a metered network after Wi-Fi-only was switched on backs off with `Result.retry()`.
- Result notifications (success, partial, failure, folder-creation failure) never use the foreground notification id 1001; each batch gets its own notification tag (queue file name) under one fixed result id, and the retry `PendingIntent` is unique per retry file (data URI), so retry actions of several batches never overwrite one another.
- The receiver cancels the notification first, then validates the file (`retry_queue_<hex-uuid>.json`, parent exactly `filesDir`); the worker applies the same validation to `KEY_QUEUE_FILE`, and a missing or malformed queue file ends the worker with `Result.failure()` (corrupt file deleted).
- The folder-creation-failure notification shows an upload-specific, typed, localized message (invalid folder name, folder not found / no permission, not configured, connection problems, generic) instead of `exception.message` or album wording; the occasion dialogs reject `.` / `..` / `/` / `\` names inline.
- The retry work is unique per retry file (`KEEP`) and awaited before the receiver finishes.
- Share target hardening: only foreign `content://` URIs are accepted (`file://` and the app's own authorities are rejected, also in the worker).
- Test seam: `UploadWorker` receives its `CredentialRepository` and `WebDavClient` factory through an internal constructor (used with `TestListenableWorkerBuilder.setWorkerFactory`), enabling real worker tests against MockWebServer.
- Documented limitation: retries realistically work for MediaStore URIs (share-target grants end when `MainActivity` finishes); every retry adds its own history entry.

## Capabilities

### New Capabilities
- `failed-photo-retry`: retrying only the failed photos of a partial upload from the completion notification (same folder, same network constraint, tolerant of unreadable photos).

### Modified Capabilities

## Impact

- `worker/UploadWorker.kt`, `worker/RetryUploadReceiver.kt`, new `worker/UploadConstraints.kt`, `MainActivity.kt`, `KrhnlesApplication.kt`, `upload/UploadEnqueuer.kt`, `worker/QueueFiles.kt`, `worker/FolderCreationError.kt`, `util/ShareIntentParser.kt`, `util/UploadPaths.kt`, `ui/share/OccasionDialog.kt`, `data/WebDavClient.kt` (`createDirectory` throws `WebDavHttpException` so failures can be classified), `res/values/strings.xml` (`upload_error_*`, `error_invalid_occasion_name`).
- Tests: `UploadWorkerIntegrationTest` (real worker runs), `RetryUploadReceiverTest` (via `handleRetry`), `UploadConstraintsTest`, `UploadEnqueuerTest`, `OccasionDialogTest`, `FolderCreationErrorTest`, `UploadPathsTest`, `ShareIntentParserTest`; `docs/architecture.md`, `README.md`, `CLAUDE.md` updated.
- No new dependency, no manifest change, no stored-data migration (queue file JSON format unchanged).
