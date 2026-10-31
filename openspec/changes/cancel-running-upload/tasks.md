# Tasks

## 1. Shared notifications, strings and history flag

- [ ] 1.1 Move the progress / completion / failure notification builders and `notifyIfPermitted` out of `UploadWorker` into top-level functions in `worker/UploadNotifications.kt` taking a `Context` (behaviour unchanged, `RESULT_NOTIFICATION_ID` + queue-file-name tag, progress id 1001); verify the existing `UploadWorkerRunTest`, `UploadWorkerForegroundTest`, `UploadWorkerHistoryTest` and `UploadVideoTest` pass unchanged
- [ ] 1.2 Add strings `notification_action_cancel` ("Cancel"), `action_cancel_upload` ("Cancel upload"), `notification_upload_cancelled` ("Upload cancelled"), plural `notification_upload_cancelled_counts` and `upload_history_counts_cancelled`, and `CompletionSummary.cancelled`; the cancelled completion text says uploaded / not uploaded (reasons joined via `joinNotificationTexts()`), keeps the "Retry Failed (N)" action with `FLAG_IMMUTABLE`; verify with a notification unit test (title, text, action count, `PendingIntent.isImmutable`, `retry://` data URI)
- [ ] 1.3 Add `UploadHistoryEntry.cancelled` (default `false`); `UploadHistoryStore` writes `"cancelled": true` only when set and reads it strictly (`opt == true`); verify `UploadHistoryStoreTest` cases: round-trip, absent → false, `"true"` string / number → false, existing tolerant-read and dedupe tests still pass
- [ ] 1.4 Show cancelled entries as "Cancelled · X uploaded · Y not uploaded" in `UploadHistoryScreen` (no file name as content description); verify with a Compose test next to the existing history screen test, and update the Upload History bullet in `README.md`

## 2. Cancel detection and safe bookkeeping in the worker

- [ ] 2.1 Add the internal-constructor seam `userCancelled: () -> Boolean` (default `isStopped && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP`) and wrap `doWork()` after the queue parse in `try/catch (CancellationException)` that always rethrows; verify a test that a stop with the seam returning `false` (system stop) keeps the queue file with its `uploaded` markers and unchanged `interruptions`, writes no retry file, no history entry and no result notification
- [ ] 2.2 Run the per-item bookkeeping after a finished `PUT` (`withUploaded`, `rewriteQueueFile`, `markUploaded`, `recordOutcome`) in `withContext(NonCancellable)` with each DataStore write bounded (5 s); verify a test where the job is cancelled while the `PUT` response is returned: the item is marked in the queue file and `UploadedPhotosStore`, its verified record is stored, and it is not in the retry file
- [ ] 2.3 Mark terminal reporting (`finish()`, folder-creation failure, not configured, `interrupted()`) with a `terminal` flag and run it in `NonCancellable`; skip cancel finalization when set; verify a test that a cancel after the last item yields exactly one normal completion notification, one non-cancelled history entry and no retry file
- [ ] 2.4 Add a test that a cancel during a verification download leaves the item pending (in the retry file, not marked) and that no record exists at its target; verify with `UploadWorkerVerificationTest`-style setup

## 3. Cancelled-queue finalization

- [ ] 3.1 Implement `finalizeCancelledQueue()` in new `worker/UploadCancellation.kt` (design D4: claim by atomic rename into `noBackupFilesDir/retry_queue_<uuid>.json`, rewrite with `toRetryJson(pending)`, refresh mtime, `deleteStagedFiles` for uploaded entries' staged copies, history `cancelled = true`, cancelled notification tagged with the original queue file name; `deleteQueueFile()` when nothing is pending; `NonCancellable` + 10 s bound); verify unit tests for each step including a lost claim (second call does nothing, exactly one retry file)
- [ ] 3.2 Call it from the worker's user-cancel branch with the in-memory queue and failures; verify `UploadWorkerCancelTest` (Robolectric, MockWebServer holding the 3rd `PUT` of 5, job cancelled with the seam returning `true`): retry file has exactly items 3–5 with their folders and no `uploaded` / `interruptions`, items 1–2 marked and verified records kept, input queue file gone, one cancelled history entry (5 / 3), notification "Upload cancelled" with "Retry Failed (3)" on `RESULT_NOTIFICATION_ID` with the queue-file-name tag
- [ ] 3.3 Verify cancel before the first item (during folder creation and before `setForeground`): no `PUT` reached the server, the retry file contains every item, history 5 / 5 cancelled
- [ ] 3.4 Verify resume without double upload: run the worker on the retry file from 3.2; the server receives only items 3–5, items 1–2 are received exactly once over both runs, the retry history entry covers 3 items
- [ ] 3.5 Verify an auto date (per-photo folder) queue and a legacy `filesDir` queue are finalized into `noBackupFilesDir` with folders preserved, and a cancelled retry run (input `retry_queue_*`) produces a new retry file with a new unique work name
- [ ] 3.6 Verify interruptions: a queue with `interruptions = 4` cancelled by the user yields a retry file without `interruptions`, and `interrupted()` is never called on the cancel path
- [ ] 3.7 Verify staged entries (`UploadWorkerStagedTest` style): staged copies of uploaded items are deleted, those of pending items exist and are referenced by the retry file, the retry uploads them from the staged copy, and `ShareStaging.sweep()` keeps them (fresh retry mtime)
- [ ] 3.8 Document the cancel path in `docs/architecture.md` (Retry of failed photos section: stop-reason rule, non-cancellable bookkeeping, claim by rename, wording) and in `CLAUDE.md` item 4 (user cancel only via `STOP_REASON_CANCELLED_BY_APP`, never counted as interruption, finalization only through `finalizeCancelledQueue()`); verify the docs name the guarding tests

## 4. Cancel entry points

- [ ] 4.1 Implement `cancelRunningUpload(workManager, workId)` (bounded `getWorkInfoById`, cancel only `photo_upload` work in state `RUNNING`, `cancelWorkById().await()`, failures logged by type only) in `worker/UploadCancellation.kt`; verify unit tests with a test `WorkManager`: running upload → cancelled; enqueued, finished, unknown id, untagged work → untouched
- [ ] 4.2 Add `CancelUploadReceiver` (`goAsync()`, IO scope, `finish()` in `finally`, `internal suspend fun handleCancel`) and register it with `android:exported="false"`; verify `CancelUploadReceiverTest` (missing / malformed `EXTRA_WORK_ID` ignored without crash, valid id cancels) and a manifest assertion that the receiver is not exported
- [ ] 4.3 Add the Cancel action (explicit intent, request code 2002, `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`, data URI `cancel-upload://<workId>`) to the progress notification and `getForegroundInfo()`; verify a notification test (one action, immutable, explicit component, data URI contains the worker id, small icon unchanged)
- [ ] 4.4 Add `workId` to `UploadProgress`, a guarded one-shot `cancelUpload()` in `PhotoGridViewModel` (`Job` re-entry guard, bounded, never throws) and a "Cancel upload" button in `UploadProgressBanner`; verify `PhotoGridViewModelTest` (re-entry ignored, failure swallowed) and a Compose test that tapping the button calls the cancel for the shown work id; `ViewModelFactoryRegressionTest` still passes
- [ ] 4.5 Add the end-to-end test with a real test `WorkManager` (`WorkManagerTestInitHelper`, worker factory with fakes, multi-thread executor): enqueue, hold the 2nd `PUT`, `cancelWorkById` → work `CANCELLED`, the worker saw `STOP_REASON_CANCELLED_BY_APP` without the seam, and the retry file / notification of 3.2 exist
- [ ] 4.6 Update `README.md` (how to cancel, what stays uploaded, resume via "Retry Failed", partial file note) and `docs/requirements.md` (new Upload requirement row "A running upload can be cancelled and resumed later", status Implemented); verify the README wording uses "cancel", not "fail", for cancelled items

## 5. Start-up recovery of cancelled queues

- [ ] 5.1 Add the per-queue tag `upload_queue_file:<queueFileName>` in `uploadWorkRequest()` (still the only request builder, used by `UploadEnqueuer` and `handleRetry`); verify `UploadConstraintsTest` / `UploadEnqueuerTest` / `RetryUploadReceiverTest` assert both tags
- [ ] 5.2 Implement `recoverCancelledUploads()` (one snapshot of queue files accepted by `QueueFiles.kt` and their tagged `WorkInfo`s, bounded 5 s; candidates only if all infos are `CANCELLED`; 30 s grace; `finalizeCancelledQueue()` per remaining candidate) and launch it once from `KrhnlesApplication.appScope`; verify tests: cancelled orphan → retry file + one notification + one history entry; unfinished, succeeded, empty (legacy / pruned), timeout → untouched; worker finalizing concurrently → exactly one retry file
- [ ] 5.3 Document the recovery and the per-queue tag in `docs/architecture.md` and `CLAUDE.md` item 4; verify the text states the fail-closed cases

## 6. Regression guard

- [ ] 6.1 Add `NoUploadWorkCancelOutsideCancelPathRegressionTest` (source scan via `krhnles.moduleDir`: WorkManager cancel APIs only in `worker/UploadCancellation.kt`, no `ExistingWorkPolicy.REPLACE` / `APPEND_OR_REPLACE` in main sources); verify it fails on a planted violation in a temp copy and passes on the tree, and list it in `CLAUDE.md`

## 7. Sibling integration

- [ ] 7.1 If `nextcloud-chunked-upload` has been applied before this change, delete the in-flight upload-session directory on a user cancel inside the cancel path (bounded, `NonCancellable`, before the claim, failures logged by type only) and keep it on a system stop, with a MockWebServer test for both; otherwise add the same contract to `docs/architecture.md` as a requirement for that change; verify by the test or the documented note
## 8. Integration checks

- [ ] 8.1 Run `./gradlew test` and verify all tests pass
- [ ] 8.2 Run `./gradlew spotlessCheck`, `./gradlew detekt` and `./gradlew lintDebug` and verify zero issues (no suppressions, no baseline)
- [ ] 8.3 Run `./gradlew jacocoTestReport` and verify `worker/UploadCancellation.kt` and `CancelUploadReceiver` are covered in the HTML report
- [ ] 8.4 Manually on a device (API 35+): start an upload of ~20 photos, tap Cancel in the notification mid-batch, check the cancelled notification and history entry, tap "Retry Failed" and confirm only the remaining photos are uploaded; repeat from the in-app banner and with Wi-Fi only toggled off/on to confirm a constraint stop is not reported as cancelled

## Workflow follow-up

- `german-translation` (implemented last) must translate `notification_action_cancel`, `action_cancel_upload`, `notification_upload_cancelled`, `notification_upload_cancelled_counts` and `upload_history_counts_cancelled`.
- Archive the change after review; the archive creates `openspec/specs/upload-cancellation/spec.md` and updates `upload-history` and `share-target`.
