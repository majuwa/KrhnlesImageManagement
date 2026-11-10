## Why

Feature request (a GitHub issue could not be created, so the issue text is recorded here):

> **Cancel a running upload.** The upload progress notification has no Cancel action, and nothing in `worker/` ever cancels upload work. A user who picked the wrong folder, or selected hundreds of photos by mistake, cannot stop the upload: it keeps sending (possibly over mobile data) until every item is done. Add a Cancel action to the foreground progress notification (and consider the in-app progress / upload history). Cancelling must stop the running request at once, keep everything that was already uploaded (queue `"uploaded": true` markers, uploaded-photo markers, verified-upload records), turn the remaining items into a retry file so the user can resume them with "Retry Failed", and report the run as *cancelled* (not failed) in the result notification and the upload history. Shared (staged) photos must follow the existing retry / sweep rules, and a user cancel must never be confused with a system stop (constraint, foreground-service timeout, preemption), which WorkManager re-runs.

Today the only ways to stop an upload are force-stopping the app or clearing its data, which loses the retry state and leaves staged copies behind.

## What Changes

- The foreground progress notification gets a **Cancel** action (immutable, explicit broadcast `PendingIntent` to a new non-exported `CancelUploadReceiver` that uses `goAsync()`), and the in-app upload progress banner on the Photos tab gets a **Cancel upload** button. Both cancel only *running* upload work (tag `photo_upload`), identified by its work id; no confirmation dialog, because the cancel is resumable.
- `UploadWorker` recognises a user cancel only when WorkManager stopped it with `STOP_REASON_CANCELLED_BY_APP`; every other stop reason (constraints, `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`, preemption, quota, system processing, unknown) keeps today's behaviour: queue file and progress kept, WorkManager re-runs, nothing reported, no interruption counted.
- On a user cancel the running `PUT` is aborted (coroutine cancellation → `executeCancellable` → `Call.cancel()`), the bookkeeping of an item whose `PUT` already finished completes without interruption, and the queue is finalized once (atomic claim): the remaining items (not yet uploaded, failed, or aborted mid-`PUT`) become a retry file with a reset interruption counter; already uploaded items stay marked and are never sent again.
- New "Upload cancelled" result notification (`RESULT_NOTIFICATION_ID`, tag = input queue file name) with the counts of uploaded / not uploaded items and the existing "Retry Failed (N)" action; a cancel after the last item reports the batch as complete.
- Upload history records cancelled runs (optional `cancelled` flag, absent = `false`) and shows them as "Cancelled · X uploaded · Y not uploaded".
- Staged copies of shared photos: those of items that were uploaded are deleted at once, those of the remaining items stay with the retry file (7-day retry window from the cancel).
- Start-up recovery for the rare case that a cancel lands while the work is between runs or the process dies before the worker finalized: a queue file whose work (new per-queue work tag) is `CANCELLED` is finalized like a cancelled run.
- Lock-in tests (cancel mid-batch, before the first item, after the last item, resume from the retry file without double upload, system stop vs. user cancel, staged files, recovery, receiver validation, `PendingIntent` immutability) and docs (`README.md`, `CLAUDE.md`, `docs/architecture.md`, `docs/requirements.md`).
- Out of scope: cancelling uploads that are queued but not running (e.g. waiting for Wi-Fi), deleting partially written files on the server, a cancel-all action, cancelling from the upload history screen.

## Capabilities

### New Capabilities
- `upload-cancellation`: user-initiated cancellation of a running background upload (notification and in-app entry points), the guarantees for already uploaded and remaining items, how a user cancel is told apart from a system stop, the cancelled result, and recovery of cancelled queues.

### Modified Capabilities
- `upload-history`: cancelled runs are recorded (one entry each) and displayed as cancelled with uploaded / not-uploaded counts.
- `share-target`: staged copies of photos uploaded before a cancel are deleted when the cancelled queue becomes its retry file, not only on queue-file deletion.

## Impact

- Code: `worker/UploadWorker.kt` (cancel handling, non-cancellable bookkeeping and terminal reporting, Cancel action on the progress notification), new `worker/CancelUploadReceiver.kt`, new `worker/UploadCancellation.kt` (finalize + claim + recovery), `worker/UploadConstraints.kt` (`uploadWorkRequest()` adds a per-queue tag), notification builders moved to a shared top-level file so the recovery can post the cancelled result, `data/UploadHistoryStore.kt` + `model/UploadHistoryEntry.kt` (`cancelled`), `ui/uploadhistory/UploadHistoryScreen.kt`, `ui/photogrid/PhotoGridViewModel.kt` / `PhotoGridScreen.kt` (banner button), `KrhnlesApplication.kt` (recovery at start), `AndroidManifest.xml` (receiver, not exported), `res/values/strings.xml`.
- No new dependency; WorkManager 2.12 already exposes `ListenableWorker.getStopReason()` / `WorkInfo.STOP_REASON_*`.
- Sibling changes: **nextcloud-chunked-upload** also changes `UploadWorker`; its upload-session (chunk) directory must be deleted on a *user* cancel (best effort, bounded, in the cancel path defined here) and must be kept on a system stop. **german-translation** (implemented last) must translate the new strings. **download-album-photos** may add its own worker / cancel; this receiver only ever cancels `photo_upload` work. No overlap with pin-self-signed-certificate, occasion-folder-suggestions, show-videos-in-albums, new-photos-reminder (keeps its own notification ids; never `RESULT_NOTIFICATION_ID` / 1001), album-search-and-sort, readme-roadmap-cleanup (README sections touched here: Videos / Uploads / Upload History).
