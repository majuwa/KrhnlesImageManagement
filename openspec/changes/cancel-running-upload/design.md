# Design

## Context

See proposal.md (Why). Current state that shapes the approach:

- `UploadWorker` (`CoroutineWorker`) reads a validated queue file (`validQueueFile()` / `isWorkerQueueFile()`), creates folders, then loops over `pendingIndices`. After each successful item it rewrites the queue atomically (`rewriteQueueFile()`, `"uploaded": true`), marks the photo (`UploadedPhotosStore`) and applies the verified-upload record (`applyUpload(target, …)` before and after each `PUT`). `finish()` records history, writes a retry file for the pending items (`toRetryJson()` resets `interruptions`) and posts the result notification (`RESULT_NOTIFICATION_ID`, tag = input queue file name); `doWork()` then calls `deleteQueueFile(keep = staged of the retry file)`.
- A refused foreground service goes through `interrupted()` (counted `interruptions`, `Result.retry()`, queue kept). A WorkManager stop (constraints, etc.) today simply cancels the coroutine: the `CancellationException` propagates, nothing is deleted, WorkManager re-runs the work and finished items are skipped.
- `WebDavClient.uploadFile` runs through `executeCancellable`, so cancelling the worker coroutine already calls `Call.cancel()` on the running `PUT`; `CancellationException` is rethrown everywhere (`.onFailure { if (it is CancellationException) throw it }`).
- Nothing in the app calls a WorkManager cancel API. Initial uploads use plain `enqueue()` with tag `photo_upload`; retries use `enqueueUniqueWork(retryFileName, KEEP)`.
- `minSdk` is 35: the `dataSync` foreground service has a time limit, which WorkManager 2.10+ reports as `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` and re-schedules.
- WorkManager semantics relied on (2.9+, project uses 2.12): `cancelWorkById()` marks the work `CANCELLED` (terminal, never re-run, returned `Result` ignored) and, if it is running, calls `ListenableWorker.stop(STOP_REASON_CANCELLED_BY_APP)`, which sets `stopReason` / `isStopped` *before* `onStopped()` cancels the `CoroutineWorker` job. Every other stop reason re-enqueues the work.

## Goals / Non-Goals

**Goals:**
- One reliable, testable decision "user cancel vs. system stop" inside the worker.
- Exactly-once finalization of a cancelled queue (no duplicate retry files, no lost remaining items), also if the worker never gets to finalize.
- Reuse the existing retry machinery (retry file format, `RetryUploadReceiver`, unique retry work, staging sweep) unchanged.

**Non-Goals:**
- Cancelling work that is enqueued but not running (no UI shows it; Out of scope in proposal).
- Deleting a partially written remote file (a remote DELETE could remove a pre-existing good file with the same name; the retry overwrites it).
- Changing interruption counting, back-off, or Wi-Fi-only behaviour.

## Decisions

### D1 — Cancel by work id through an own immutable broadcast, only for RUNNING work
The progress notification (`buildProgressNotification()` and `getForegroundInfo()`) gets one action: an explicit `Intent` to the new `CancelUploadReceiver` (`android:exported="false"`), `PendingIntent.getBroadcast(…, REQUEST_CODE_CANCEL = 2002, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)`, unique data URI `cancel-upload://<workId>` (identity ignores extras), extra `EXTRA_WORK_ID` = the worker's own `id`. The receiver uses `goAsync()` + fresh `CoroutineScope(SupervisorJob() + Dispatchers.IO)` + `finish()` in `finally` (same shape as `RetryUploadReceiver`) and delegates to `internal suspend fun handleCancel(context, intent, workManager)`, which delegates to the shared `cancelRunningUpload(workManager, workId)`:
1. parse the UUID (malformed → ignore, log type only);
2. read `getWorkInfoById(id)` bounded (5 s, like `hasUnfinishedRetryWork`);
3. cancel only if the info exists, has tag `UPLOAD_WORK_TAG` and `state == RUNNING`; `cancelWorkById(id).await()` so the cancel is persisted before `finish()`.

The receiver touches no file: all queue-file handling stays in the worker (which validates its own queue file via `validQueueFile()`) and in the recovery (which only lists names accepted by `worker/QueueFiles.kt`). The in-app banner calls the same `cancelRunningUpload()` from `PhotoGridViewModel` as a guarded one-shot (CLAUDE.md rule 6: `cancelJob` re-entry guard, bounded, failures swallowed into a log, never stale state); `UploadProgress` gains the `workId` of the running `WorkInfo`.

*Alternatives:* `WorkManager.createCancelPendingIntent(id)` — it targets WorkManager's foreground service and (as far as known for 2.x) creates a mutable `PendingIntent` on API 31+, and it would also cancel enqueued work, which creates orphans (D5); rejected. Cancel by per-queue tag — would also hit non-running work; rejected. Passing the queue file path — unnecessary, the work id plus the tag check identify the target and the worker owns its file.

### D2 — User cancel = `isStopped && stopReason == STOP_REASON_CANCELLED_BY_APP`, nothing else
`doWork()` wraps everything after the queue parse in `try { … } catch (e: CancellationException) { if (userCancelled()) finalizeOnCancel(); throw e }`. `userCancelled` is an internal-constructor seam defaulting to `{ isStopped && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP }`. Every other reason (constraint reasons, `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`, preemption, quota, system processing, `STOP_REASON_UNKNOWN`) and a cancellation without `isStopped` rethrow unchanged: queue file and progress stay, WorkManager re-runs, no notification, no history, no interruption counted (exactly today's behaviour, now pinned by tests).

Why reliable: the app is the only party that can produce `CANCELLED_BY_APP`, and after this change the only app code that cancels upload work is `cancelRunningUpload()` (guarded by a source-scan regression test, D9). Uploads never use `ExistingWorkPolicy.REPLACE` (which would also stop with `CANCELLED_BY_APP`); the same test forbids it for upload work. Moreover, a `CANCELLED_BY_APP` stop is terminal — WorkManager will never re-run that work — so finalizing is required for correctness whatever triggered it (otherwise the queue would be orphaned).

*Alternatives:* a "cancel requested" marker file written by the receiver — adds a second source of truth and a file the receiver would have to validate, and does not help when the work is not running; rejected. Checking `!isActive` only — cannot tell a system stop from a cancel; rejected.

### D3 — A finished `PUT` is always booked; terminal reporting is never split
- After `uploadPhoto()` returns, the per-item bookkeeping (`withUploaded` + `rewriteQueueFile` + `markUploaded` + `recordOutcome`) runs in `withContext(NonCancellable)`, each DataStore write bounded (`withTimeoutOrNull(5 s)`) so the block always ends. Cancellation then surfaces at the next suspension point (`setProgress` / `setForegroundOrFalse` / next `PUT`). This guarantees "transfer finished as the cancel arrives → marked uploaded, not re-sent".
- If the cancel lands during verification (e.g. the download strategy), the item is *not* uploaded (pending) — conservative: it is re-sent on resume; its target records were already dropped before the `PUT`.
- Terminal reporting paths (`finish()`, the folder-creation failure, not configured, `interrupted()`) set a `terminal` flag and run in `NonCancellable`; `finalizeOnCancel()` is skipped when `terminal` is set. So a cancel after the last item produces the normal completion (spec "Nothing left"), never a second history entry.

### D4 — Finalization claims the queue by atomic rename into the retry file
`finalizeCancelledQueue(context, queueFile, queue, failures, deps)` in new `worker/UploadCancellation.kt`, used by the worker (D2, with its in-memory queue) and by the recovery (D5, queue parsed from disk):
1. **Claim**: `queueFile.renameTo(File(queueFilesDir(ctx), "retry_queue_<uuid>.json"))`. Rename is atomic; the loser of a concurrent finalization sees `false` and does nothing. Right after the rename the file is already a valid retry file (the parser accepts progress files; uploaded entries are skipped), so a crash at any later step loses nothing. A legacy `filesDir` queue moves to `noBackupFilesDir` (same filesystem).
2. If no item is pending: `deleteQueueFile(retryFile)` (the one deletion helper; deletes its staged copies) and report the batch as complete (D6).
3. Else `rewriteQueueFile(retryFile, queue.toRetryJson(pending))` (drops uploaded entries; `interruptions` absent = 0, so a user cancel never uses the interruption budget; a failed rewrite leaves the valid claimed file) and `setLastModified(now)` (7-day staging window starts at the cancel).
4. Delete the staged copies of uploaded entries with the existing `deleteStagedFiles(shareStagingDir, uploadedStaged - pendingStaged)`.
5. History (`cancelled = true`, bounded write) and the cancelled notification (D6) with `notificationTag = original queue file name` and the retry file path.

The whole function runs in `NonCancellable`, bounded by `withTimeoutOrNull(CANCEL_FINALIZE_TIMEOUT_MS = 10 s)` around its suspending parts.

*Alternatives:* reuse `writeRetryQueueFile()` + `deleteQueueFile(keep)` as in `finish()` — two steps, so a crash between them or a concurrent second finalizer yields two retry files with the same items (double upload); rejected. A lock/marker file via `createNewFile()` — needs stale-lock handling after process death; rejected.

### D5 — Start-up recovery for orphaned cancelled queues
Orphans arise when (a) the cancel races a run that just returned `Result.retry()` (observed `RUNNING`, cancelled while `ENQUEUED`, so `doWork()` never runs again) or (b) the process dies between the cancel and the end of `finalizeOnCancel()`. To find a queue's work, `uploadWorkRequest()` adds a per-queue tag `upload_queue_file:<queueFileName>` (both initial uploads and retries; still the one request builder).
`recoverCancelledUploads()` runs once per process from `KrhnlesApplication.appScope` (IO):
1. take one snapshot: list upload / retry queue files (names accepted by `QueueFiles.kt`, both parents) and, per file, `getWorkInfosByTag(tag)` bounded (5 s);
2. candidates: tag infos non-empty and **all** `CANCELLED`; unknown state, timeout, empty (legacy work without the tag, or pruned after WorkManager's retention) or any unfinished / other finished state → untouched (fail-closed);
3. wait `CANCEL_RECOVERY_GRACE_MS` (30 s, > the 10 s finalize bound and the 5 s bookkeeping bounds), then for each candidate that still exists, parse and `finalizeCancelledQueue()` (claim by rename → exactly once even if a worker finalizes concurrently).

The grace period guarantees that a worker of this process that was cancelled before the snapshot has finished its bounded bookkeeping and finalize, so no worker write can recreate a claimed queue file.

### D6 — Result wording and notification
New strings: title `notification_upload_cancelled` ("Upload cancelled"), plural `notification_upload_cancelled_counts` ("%1$d uploaded, %2$d not uploaded" with quantity on the not-uploaded count), failure reasons of the cancelled run (worker path only) appended via `joinNotificationTexts()` / `notification_text_with_reason`, never concatenated. The action is the existing "Retry Failed (N)" to `RetryUploadReceiver` (same extras, `retry://<retryFileName>` data URI, `FLAG_IMMUTABLE`), so resume = existing retry (unique work per retry file, Wi-Fi-only, staged copies). Notification builders move from `UploadWorker` members to top-level `worker/UploadNotifications.kt` functions taking a `Context` so the recovery can post the same notification; `CompletionSummary` gains `cancelled: Boolean`. Progress action label `notification_action_cancel` ("Cancel"); banner button `action_cancel_upload` ("Cancel upload"). Result data / work output are irrelevant for a cancelled work (WorkManager ignores them).

### D7 — Upload history `cancelled` flag
`UploadHistoryEntry.cancelled: Boolean = false`; the store writes `"cancelled": true` only when set and reads it strictly (`opt("cancelled") == true`, anything else → false), so entries of older versions and malformed values stay "not cancelled" and the existing tolerant read is unchanged. `failedCount` holds the not-uploaded count. The screen shows `upload_history_counts_cancelled` ("Cancelled · %1$d uploaded · %2$d not uploaded") for cancelled entries.

### D8 — Interaction with nextcloud-chunked-upload
That change adds per-item upload sessions on Nextcloud. Contract defined here: on a **user** cancel (D2) the in-flight session directory is deleted best-effort inside `finalizeOnCancel()` before the claim (bounded, `NonCancellable`, failures logged by type only, never blocking the claim); on a **system** stop the session is kept for the re-run. Whichever change is applied second wires this and adds the test (task 7.1).

### D9 — Guarding the single cancel path
Source-scan regression test `NoUploadWorkCancelOutsideCancelPathRegressionTest` (module dir via `krhnles.moduleDir`): in main sources, `cancelWorkById` / `cancelAllWorkByTag` / `cancelUniqueWork` / `cancelAllWork` appear only in `worker/UploadCancellation.kt`, and `ExistingWorkPolicy.REPLACE` / `ExistingWorkPolicy.APPEND_OR_REPLACE` never appear.

## Risks / Trade-offs

- [WorkManager sets `stopReason` after cancelling the coroutine in some version] → the end-to-end test with a real test `WorkManager` (`cancelWorkById` during a held `PUT`) fails; it pins the ordering for 2.12 and future upgrades.
- [Partial file left on a plain WebDAV server after an aborted `PUT`] → records at the target are dropped before every `PUT`, the item is resumed and overwrites it; documented in README. No remote delete (could remove a pre-existing file).
- [Cancel tapped on a stale notification while the run is between attempts] → ignored (not `RUNNING`); the next run shows a fresh Cancel action.
- [WorkManager prunes finished work (~1 day) before the next app start] → recovery sees no infos and keeps the orphan (fail-closed); its staged copies follow the sweep as today. Accepted for this rare double race.
- [An item whose verification was cancelled is uploaded twice] → only that one item, overwriting the same path; accepted to keep "uploaded" meaning "verified or verification not applicable".
- [Accidental tap on Cancel] → no data loss; one tap on "Retry Failed" resumes.

## Migration Plan

No data migration. Queue / retry files keep their format; history entries without `cancelled` read as not cancelled. Work enqueued by an older version lacks the per-queue tag: it can still be cancelled (D1 needs only `photo_upload`), and the worker finalizes it; only the start-up recovery skips it. Rollback: an older version ignores the `cancelled` history field and treats the claimed retry files like any other retry file.

## Open Questions

- Should the cancelled result's action read "Retry Failed (N)" (as requested, reuses the existing string) or a dedicated "Upload remaining (N)"? String choice only; same receiver and spec behaviour.
- Should a later change offer cancelling *queued* uploads (e.g. waiting for Wi-Fi) from the upload history? Would build on the per-queue tag added here.
