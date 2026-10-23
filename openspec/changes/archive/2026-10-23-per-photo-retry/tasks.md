# Tasks

## 1. Test seam and constraints

- [x] 1.1 Give `UploadWorker` an internal constructor taking `CredentialRepository` and a `WebDavClient` factory, plus the public `(Context, WorkerParameters)` constructor for WorkManager. Verify: existing tests green; worker tests build it via `TestListenableWorkerBuilder.setWorkerFactory`.
- [x] 1.2 Add `uploadConstraints(wifiOnly)` and `readWifiOnly(flow, timeoutMs)` (`worker/UploadConstraints.kt`). Verify: `UploadConstraintsTest` (true -> UNMETERED, false -> NOT_REQUIRED, slow / throwing flow -> UNMETERED, cancellation rethrown).
- [x] 1.3 Process-scoped `UploadEnqueuer` (appScope, bounded `readWifiOnly`, queue files, awaited enqueue, toast on Main); `MainActivity` calls it from both paths, share path finishes synchronously; `ShareReceiverScreen` confirms once. Verify: `UploadEnqueuerTest` (caller scope cancelled -> still enqueued; one work per batch; empty ignored), `OccasionDialogTest` (double confirm -> one callback).

## 2. Worker robustness

- [x] 2.1 Per-photo read tolerance (narrow try around `openInputStream`, progress also on failure). Verify: worker test with content provider throwing `SecurityException` / `FileNotFoundException`: remaining photos upload, notification counts correct, retry file has exactly the failed entries and the same `folderName`.
- [x] 2.2 Validate `KEY_QUEUE_FILE` (name pattern, parent == `filesDir`); missing / malformed queue -> `Result.failure()`, malformed file deleted. Verify: worker tests for missing file, malformed JSON, path in subdirectory.
- [x] 2.3 Typed folder-creation failure: `createDirectory` throws `WebDavHttpException`, worker uses `toAlbumsError()` + `albumsErrorMessage()`, no raw message in notification or `Data`. Verify: worker test (MKCOL 403) asserts notification text equals the access-denied string and contains no raw HTTP text; `WebDavClientTest` updated.
- [x] 2.4 Worker tests against MockWebServer: partial failure writes retry file with only failed entries; retry run uploads only listed photos to the stored folder (incl. `2026/05-May`), deletes the file, marks only newly succeeded ids in `UploadedPhotosStore`.

## 3. Notification identity and receiver

- [x] 3.1 Result notifications use `notify(tag = queueFile.name, id = RESULT_NOTIFICATION_ID)`; retry `PendingIntent` gets data URI `retry://<retryFileName>`; tag + id in the extras. Verify: tests: id != 1001, two batches -> two notifications with different tags, distinct retry intents and PendingIntents, no action for `failed = 0`, action title `Retry Failed (N)`, tag / id in extras equal the posted ones.
- [x] 3.2 `RetryUploadReceiver`: `goAsync()` + per-broadcast scope, `internal suspend fun handleRetry(...)`: cancel notification (tag + id) first, validate, apply constraints, `enqueueUniqueWork(retryFile, KEEP)` awaited, `photo_upload` tag; `finish()` in `finally`. Verify in `RetryUploadReceiverTest` via `handleRetry`: wifiOnly true -> UNMETERED, false -> no requirement, slow / throwing flow -> UNMETERED, notification cancelled exactly by extra tag + id, enqueue persisted on return, double `handleRetry` -> one work, missing file -> nothing enqueued but notification cancelled, subdirectory / wrong name / outside path / missing extra rejected.

## 4. Review follow-ups

- [x] 4.1 Upload-specific folder-creation errors (`FolderCreationError`, `upload_error_*`), folder path validation in the worker, occasion-name validation in `OccasionDialog` (shared by photo grid and share target). Verify: `FolderCreationErrorTest`, worker tests (MKCOL 403 / 404, folder `..` without request, unconfigured), `OccasionDialogTest`, `UploadPathsTest`.
- [x] 4.2 Progress advances for failed photos (`setProgressUpdater` records 1, 2, 3). Wi-Fi-only checked in `doWork()` (`Result.retry()` on a metered network). Verify: worker tests.
- [x] 4.3 Share URI policy (`isAllowedPhotoUri`) in `parseSharedPhotos` and `UploadWorker.openPhoto`; logs without throwables. Verify: `ShareIntentParserTest`, `UploadPathsTest`, worker test with a `file://` entry.
- [x] 4.4 Move `isWorkerQueueFile` / `isRetryQueueFile` to `worker/QueueFiles.kt`; `uploadConstraints` / `readWifiOnly` internal.

## 5. Documentation and checks

- [x] 5.1 Update `docs/architecture.md` (retry queue file, per-photo failure handling, Wi-Fi-only, notification ids, receiver, test seam, MediaStore-only limitation, history entry per retry), `README.md`, and one-line `CLAUDE.md` convention. Verify: diff review.
- [x] 5.2 Run `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport` (no suppressions). Verify: exit code 0.
