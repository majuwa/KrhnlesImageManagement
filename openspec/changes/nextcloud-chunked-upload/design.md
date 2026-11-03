# Design

## Context

See proposal.md for the motivation. Relevant current state:

- `UploadWorker.uploadPhoto()` opens each entry once (`openForUpload()` → `UploadSource.Original` / `Plain`, or the staged file). It reads the length with `uploadLengthOf()`, wraps the stream in `DigestCountingInputStream` only for verifiable originals, and calls `WebDavClient.uploadFile()`. That is one `PUT` through `executeCancellable` on `uploadClient`: no redirects, 5 min read timeout, no call timeout.
- Per-entry progress is the `uploaded` marker, written with `rewriteQueueFile()` (temp file + rename). A refused foreground service between entries ends the run with `Result.retry()` and counts `interruptions`. A WorkManager stop or process death leaves the queue file as it is, and the next run re-sends the interrupted entry from byte 0.
- Verification: `verifierFor()` picks `NextcloudHashVerifier` for `/remote.php/` URLs. `X-Hash: sha256` is sent only when the entry is hashed (original and verifiable), and `UploadResponse.serverSha256` is accepted only as 64 hex characters. `VerifiedUpload` is built only in `verifyUpload(…, original: UploadSource.Original, …)`.
- `applyUpload(target)` runs before every `PUT` attempt, and `recordOutcome()` runs after it.
- The Nextcloud login flow stores `https://host/remote.php/dav/files/<loginName>/` as the server URL. Manual users may enter `/remote.php/webdav/` or any other WebDAV URL.

Nextcloud server behaviour, read from `apps/dav` on Nextcloud `master`. It shapes several decisions below:

- `ChunkingV2Plugin` handles the request only with a distributed memcache and object storage that supports multipart upload. It accepts only numeric chunk names from 1 to 10000. Its `MOVE` returns 201 (new file) or 204 (overwritten) and never adds a hash header.
- On every other setup, the same client protocol runs through the classic upload folder and `ChunkingPlugin`. The `Destination` header on `MKCOL` and `PUT` is ignored. `MOVE` checks `OC-Total-Length` (400 on mismatch) and assembles the file through `Sabre\File::put`, which honours `X-Hash` on the `MOVE` request and answers `X-Hash-SHA256`.
- `UploadCleanup` deletes an upload folder once all of its files are older than `cache_chunk_gc_ttl` (24 h by default).

## Goals / Non-Goals

**Goals:**
- Large files reach Nextcloud despite body-size limits.
- An interrupted large file resumes at chunk granularity, across runs and within a run.
- Every existing upload guarantee stays in place: verification, records, redirects, cancellation, origin restriction, 413 / 507 messages, the interruption budget, and atomic queue rewrites.

**Non-Goals:**
- Parallel chunk uploads, adaptive chunk sizes, or bandwidth throttling.
- Chunking for plain WebDAV servers or `/remote.php/webdav/` URLs. Those would need the user id, which would mean a `current-user-principal` `PROPFIND`.
- Resuming from a retry file (user "Retry failed"): retries start a fresh transfer.
- Sending `X-OC-MTime` / `X-OC-CTime`, which the single `PUT` does not send either.
- The notification Cancel action itself. That belongs to the sibling change `cancel-running-upload` (already implemented on this branch); this change only plugs the abort into its `finalizeOnCancel` path (D7).

## Decisions

### D1 Eligibility and the uploads collection
`nextcloudUploadsCollection(configUrl): HttpUrl?` (new `data/NextcloudChunking.kt`) parses the configured URL with `HttpUrl`. It finds the path segments `remote.php`, `dav`, `files`, `<user>` and returns the same scheme, host and port with the prefix before `remote.php` (which covers sub-path installs) plus `remote.php/dav/uploads/<user>`. The user segment goes through the same `validatePathSegment()` rules (moved to a shared internal function). Any other shape, or an invalid segment, returns `null`, and the run uses a single `PUT`.

An entry is chunked iff the collection is non-null, the chunking-unavailable flag of the run is not set (D10), the length from `uploadLengthOf()` is known, and the length is at least `ChunkedUploadPolicy.thresholdBytes`.

*Alternatives considered:*
- Asking the server for the user id: one more request and an XML parse, only for rare manual URLs.
- Using `config.username`: the login name may differ from the id used in the files URL.

The files URL already contains the id the server accepts, so the uploads URL uses the same segment.

### D2 Protocol and requests
New `WebDavClient` methods. All of them return `Result`, rethrow `CancellationException`, run through `executeCancellable`, and throw `WebDavHttpException(code)` for unexpected statuses:

- `createChunkedUpload(transferId, folder, fileName, totalLength)`: `MKCOL <collection>/<transferId>` with `Destination: <target URL>` and `OC-Total-Length`. Returns `Created` (201) or `AlreadyExists` (405). Any other status is an error.
- `uploadChunk(transferId, chunkNumber, folder, fileName, totalLength, stream, chunkLength)`: `PUT <collection>/<transferId>/<5-digit number>` with `Destination`, `OC-Total-Length` and `Content-Length = chunkLength`. The body is a one-shot `BoundedInputStreamRequestBody` that copies exactly `chunkLength` bytes from the shared stream and **does not close it**. A shorter stream fails with `IOException`. Success is any 2xx. Never sends `X-Hash`.
- `finishChunkedUpload(transferId, folder, fileName, totalLength, requestServerHash)`: `MOVE <collection>/<transferId>/.file` with `Destination` and `OC-Total-Length`, plus `X-Hash: sha256` only when `requestServerHash`. Accepts only 201 / 204. Returns `UploadResponse(serverSha256 = X-Hash-SHA256 if isSha256Hex)`.
- `abortChunkedUpload(transferId)`: `DELETE <collection>/<transferId>`. 2xx and 404 count as success.

The target URL is built by the existing `buildPathUrl(folderSegments + fileName)` after the same segment validation as `uploadFile`. Chunk names are zero-padded (`00001`). They are numeric for `ChunkingV2Plugin` and sort correctly for the classic assembly. Transfer ids are `krhnles-` + 32 lower-case hex digits (a random UUID without dashes), validated by `isValidTransferId()` before any request.

`MKCOL`, chunk `PUT` and `DELETE` run on `uploadClient`. `MOVE` runs on a new `assemblyClient`, which is `uploadClient.newBuilder().readTimeout(30 min)`. Nextcloud assembles a multi-GB file before answering, and 5 min would often time out. It shares the interceptor (origin-restricted auth) and the pool, and has no redirects and no call timeout. The existing `mkcol()` (follows redirects) is not reused.

*Alternative considered:* raising `uploadClient`'s read timeout for everything. That would also stretch normal `PUT`s and verification downloads.

### D3 Threshold and chunk size
`ChunkedUploadPolicy(thresholdBytes = 50 MiB, chunkBytes = 10 MiB, maxChunks = 10_000)` is injected into `UploadWorker` through the `ChunkedUploadSettings` test seam (D12) so tests can use tiny values. The default constructor keeps the default policy.

The chunk size of a transfer is `max(chunkBytes, ceil(total / maxChunks) rounded up to whole MiB)`. It is fixed when the transfer starts and persisted with it. 10 MiB is above Nextcloud's 5 MiB multipart minimum, far below every common proxy limit, and loses at most 10 MiB on an interruption.

The 50 MiB threshold keeps photos and short clips on the cheaper single `PUT`. It is still small enough that every file which can take minutes on mobile networks is resumable.

### D4 Persisted transfer state
`UploadQueueEntry` gains `chunked: ChunkedTransferState?`, serialized as the optional entry object `"chunked"`:

| key | type | meaning |
|---|---|---|
| `transferId` | string | `isValidTransferId` |
| `chunkBytes` | number | > 0 |
| `totalBytes` | number | > 0, the length the transfer was started with |
| `confirmedChunks` | number | 0..chunk count; chunks 1..n were answered 2xx |
| `prefixSha256` | string | 64 hex, SHA-256 of the first `min(n·chunkBytes, total)` bytes (hash of empty input for n = 0) |
| `original` | boolean | the source was an `UploadSource.Original` |
| `binding` | string | 64 hex, SHA-256 of `collectionUrl + "\n" + destinationUrl` |

`binding` lets the worker detect a changed server, account, base folder or user segment without storing the URL or user name in the file. The file lives in `noBackupFilesDir` anyway.

Parsing is strict (`opt` + type checks, value ranges: `chunkBytes` in `1..totalBytes`, the chunk count computed and compared with 10,000 as a `Long` before it becomes an `Int`, so a crafted count such as `totalBytes = 2^32 + 5`, `chunkBytes = 1` cannot wrap into range), but **an invalid `chunked` object is dropped, not fatal**. The entry uploads from the start, and the file stays valid. The progress is an optimization: dropping it never sends data to an unintended place, while treating the whole queue as malformed would delete the user's upload. This differs on purpose from the folder rule ("mixed / non-string → malformed and deleted"), where a wrong value would change the destination.

- `withUploaded(i)` clears the state.
- `toRetryJson()` strips it, so retry files never carry progress.
- `toProgressJson()` keeps it.
- A new `withChunked(i, state?)` updates the entry, followed by `rewriteQueueFile()`.

A failed rewrite only loses resume granularity, the same as an unsaved `uploaded` marker today.

### D5 One attempt (`ChunkedUploader.attempt`)
New `worker/ChunkedUploader.kt` keeps `UploadWorker` within detekt's size limits. An attempt does the following, in order:

1. Read `before` (`mediaFileStateOf`, verifiable entries) and open the source exactly as today (`openEntry`). Inside the stream's `use`, read `length = uploadLengthOf(…)` first, then wrap the stream in `DigestCountingInputStream`. A chunked transfer always needs the prefix hash, but the digest is used for verification only when the source is `Original` and verifiable.
2. Compute `binding`. Keep the saved state only if `transferId`, `binding`, `totalBytes == length`, `original` and `chunkBytes == policy.chunkBytesFor(length)` all match. If the binding matches but something else does not, `abortChunkedUpload` the old id. If the binding differs, send no request for the old id (it may belong to another account), clear the state and persist.
3. `verifiedUploads.applyUploadLogged(target)` (records at the target are dropped before the first request of the attempt, as before every `PUT`).
4. Saved state: `MKCOL` → `AlreadyExists` continues; `Created` means the server lost the folder, so reset to `confirmedChunks = 0` with the same id. No state: new id, which is **persisted (`confirmedChunks = 0`) before the `MKCOL` is sent**, so a stop, a give-up or a user cancel right after the `MKCOL` can still resume or abort it instead of orphaning the folder; `MKCOL` must then be `Created`. On `AlreadyExists` for a fresh id, or any non-transient (D6), non-413/507 HTTP error on a fresh id, see D10. A non-transient, non-413/507 HTTP error on the `MKCOL` of a *saved* transfer is handled the same way (abort best effort, clear and persist the state, D10). (Nextcloud's `ChunkingV2Plugin` starts its backend session in `afterMkcol`, which Sabre does not reach when the folder already exists and answers 405, so a resume `MKCOL` never resets a v2 session.)
5. Read the confirmed prefix through the digest stream into a discard buffer and compare `sha256Hex()` with `prefixSha256`. On mismatch: abort the transfer, clear the state, persist, and end the attempt as `Restart`. The next attempt reopens the file.
6. For each remaining chunk: send it with `uploadChunk`. After a 2xx, save `confirmedChunks + 1` and the new `prefixSha256` (the `sha256Hex()` snapshot is clone based and already exists). Then, except after the last chunk, call `onChunkConfirmed(percent)`, which updates the notification through `setForegroundOrFalse`. A refusal ends the attempt as `Interrupted`.
7. After the last chunk, require EOF (`read()` returns -1). Extra bytes mean the file grew, which is handled like a prefix mismatch.
8. `finishChunkedUpload(…, requestServerHash = hashed && verifier.requestsServerHash)`. The `UploadResponse` and the digest stream go back to `uploadPhoto`, which calls the unchanged `verifyUpload()` for originals. A verified record can therefore still only come from `UploadSource.Original`, and the digest covers the whole file read once in this attempt. The state is cleared when the entry is marked uploaded.

A transfer (resumed or started in this attempt) that gets 404 / 409 / 412 on a chunk `PUT` or on `MOVE` has lost its server-side upload, for example because the distributed cache dropped the v2 session (`ChunkingV2Plugin` answers a chunk `PUT` without session metadata with 412) or the 24 h inactivity cleanup removed the folder. That attempt ends as `Restart` after an abort. When it happens at the `MOVE` (every chunk was confirmed), the restart sends the whole file again, so it is allowed at most once per file and run (`MAX_MOVE_LOST_RESTARTS_PER_RUN = 1`); a second 404 / 409 / 412 on a `MOVE` in the same run fails the file (abort, retry file). A server that answers every `MOVE` that way therefore costs at most two transfers of the file per run, and the failed file only comes back through a manual retry. A fallback to a single `PUT` was not chosen: it would make the lost-`MOVE` path a second upload mode with its own verification and 413 handling for a case that indicates a broken server. A `MOVE` whose v2 session was lost falls through to the classic assembly of an (empty) folder and is rejected by the `OC-Total-Length` check with 400, so `OC-Total-Length` on the `MOVE` is load-bearing and must never be dropped; that 400 is a final failure (D6), and the next manual retry starts a fresh transfer.

### D6 Attempts within a run and failure classes
`ChunkedUploader.upload()` runs up to `MAX_CHUNKED_ATTEMPTS_PER_RUN = 3` attempts. `Restart` and transient failures start the next attempt:

- transient: `IOException` that is not a cancellation, or `WebDavHttpException` with 408, 423, 429 or 5xx except 501 and 507 (501 on a new `MKCOL` means "no uploads endpoint", D10, elsewhere it is a final failure; 507 "storage full" is a final failure, retrying it within seconds cannot help);
- wait between attempts: 2 s, then 8 s (`delay`, cancellable).

**When the last attempt fails transiently, the progress is kept** (no abort, no state cleared): `upload()` returns `ChunkedResult.Interrupted(state)`, the same result as a refused foreground service (D7). The run ends with `UploadRun(interrupted = true)`, below the `interruptions` cap the queue (with the progress) is kept and the work returns `Result.retry()` with the explicit exponential back-off of `uploadWorkRequest()` (60 s, doubling), and the interruption is counted. At the cap the run gives up like any interruption (retry file, abort of the transfer). A network outage longer than the 10 s of in-run waits therefore never discards confirmed chunks. (Review of the first implementation: it aborted the transfer and failed the file, so a short outage cost every confirmed chunk of a multi-GB video.)

*Decision: no `NetworkType.CONNECTED` constraint.* Adding it to `uploadConstraints()` would make WorkManager wait for a network instead of the back-off, but it changes every upload request (also single `PUT`s and retries, existing tests of `uploadConstraints(false)` = no constraint) and makes a constraint stop out of an outage, which by the existing rule never counts and would never give up. The back-off plus the `interruptions` cap is bounded and needs no change to the enqueued work. It can be revisited separately.

Each attempt maps an `IOException` thrown inside it (a read of the local file; failed requests are `Result`s) to transient and any other non-cancellation exception (for example a `SecurityException` from the provider while the prefix is re-read) to a final failure; nothing but a cancellation escapes the uploader.

Other failures end the entry at once and map through `toUploadFailure()`: 413 → `TooLarge`, 507 → `StorageFull`, anything else (including 3xx and 400 on `MOVE`) → `Other`. On a final failure the worker sends a best-effort abort (D8), clears the state and persists. The entry then goes into the retry file like any failed entry.

*Alternative considered:* keep the state in the retry file so a manual retry resumes. That was rejected. Retries can happen days later, after the server's 24 h cleanup. It would also add progress to the retry-file contract (`handleRetry`, `ShareStaging.sweep` age logic) for little gain.

### D7 Interruptions and stops
- **`Interrupted`** (refused foreground between chunks): `uploadFiles` returns `UploadRun(interrupted = true)` with the progress saved. `interrupted()` is unchanged. Below the cap the run returns `Result.retry()` with the queue kept, and the next run resumes. At the cap, `finish(gaveUp = true)` puts the entry into the retry file and aborts its transfer (D8).
- **WorkManager stop or process death**: the coroutine is cancelled inside `executeCancellable`. The saved state stays in the queue file and the next run resumes. No abort is sent.
- **User cancel**: detected **only** through the existing `userCancelled()` (`isStopped && stopReason == STOP_REASON_CANCELLED_BY_APP`, test seam `userCancelledOverride`, CLAUDE.md rule 4). No second cancel predicate or constructor parameter is added. The abort runs in the existing `doWork()` cancel path (`finalizeOnCancel`), not in a separate handler inside the attempt:
  - the queue is claimed and reported by `finalizeCancelledQueue()` **first** (its retry file comes from `toRetryJson()` and therefore never carries `chunked`), so the "Upload cancelled" notification is never delayed by a slow `DELETE`;
  - then every open transfer is aborted (D8): the in-flight one and every `chunked` state in the worker's live queue whose binding matches, so a cancel before the attempt reached its `MKCOL` (prefix re-read, folder creation, back-off wait) does not orphan a saved transfer. The aborter is set right after the run read its configuration; a cancel that comes earlier (during the Wi-Fi-only or the configuration read) reads the configuration in the cancel path (`cancelAborter()`, `NonCancellable`, bounded by 3 s, only when the queue holds progress) and aborts the binding-matched transfers with it;
  - a `MOVE` answered 2xx is booked through the same non-cancellable `bookOutcome()` as a finished `PUT` and its transfer is no longer "open": a cancel right after it never sends a `DELETE` and the entry counts as uploaded.
  If the process dies between the cancel and `finalizeOnCancel`, `recoverCancelledUploads()` finalizes the queue on the next start without any network request; the server's 24 h cleanup removes that transfer.

### D8 Cleanup
`abortTransfer(state)` = `withContext(NonCancellable) { withTimeoutOrNull(bound) { client.abortChunkedUpload(id) } }`, never throwing. The bound is 30 s inside a run and at most 10 s on the user-cancel path (the work is already `CANCELLED` there and the process may be cached / killed at any time); both are injectable for tests. It is sent only when the binding matches the current config: `ChunkedUploader.discard()` compares the saved transfer's binding with its own (also when the file could not be opened and the saved state is dropped) and otherwise only clears the state. Failures are logged by exception type and HTTP code only, and never change the entry result or the notification.

It is called on:
- a final entry failure,
- a give-up,
- a folder-creation failure (before the failure is reported; that run writes no retry file),
- a user cancel,
- a prefix, source or length mismatch,
- a lost server-side upload (before restart).

The server's 24 h `UploadCleanup` is the backstop for transfers the app can no longer reach: queue files deleted as malformed, a changed account, a downgrade to an older app version, a cancel finalized by `recoverCancelledUploads()` after process death, and a `MKCOL` whose answer was lost.

### D9 Verification and records
Nothing changes in `UploadVerifier`, `verifierFor()`, `NextcloudHashVerifier` or `VerifiedUploadsStore`. The `MOVE` carries `X-Hash: sha256` under the same condition as today's `PUT`.

On regular Nextcloud storage the `MOVE` answer carries `X-Hash-SHA256` of the assembled file, and the existing comparison applies. On object storage (`ChunkingV2Plugin`) and older servers it does not, and `NextcloudHashVerifier`'s fallback (download if enabled, else `Unavailable`) applies unchanged.

The `before.size == local.sizeBytes` provability check applies to the whole-file digest. `applyUpload(target)` runs before the first request of every attempt (D5 step 3), and `recordOutcome()` runs after the entry as today. Staged (shared) entries can be chunked too (threshold permitting) and stay unverified.

### D10 Uploads endpoint unavailable
If the `MKCOL` of a **new** transfer fails with `WebDavHttpException` and a code that is neither transient (D6: 408, 423, 429, 5xx except 501 / 507) nor 413 / 507 (for example 404, 405, 403, 400, 501, 3xx), the worker clears the state persisted before the `MKCOL` (D5 step 4), sets a run-wide `chunkingUnavailable` flag and uploads this and all later entries with a single `PUT`. Transient codes and transport errors follow D6 (the transfer id persisted before the `MKCOL` is reused by the next attempt); only when the attempts are used up does the entry fail. 413 / 507 fail the entry. The flag is not persisted, so the next run tries again.

### D11 Progress notification
`buildProgressNotification(current, total, filePercent: Int? = null)` uses the existing `"$current / $total"` form when `filePercent` is null. Otherwise it uses the new string `notification_uploading_file_progress` = `"%1$d / %2$d · %3$d %%"`, and the progress bar stays per batch. The update happens per confirmed chunk through `setForegroundOrFalse`, which is also the budget check of D7. The "Cancel" action that `cancel-running-upload` put on the progress notification (immutable explicit broadcast to `CancelUploadReceiver`) must be present on the percentage variant too; this change's tests (`UploadNotificationsTest`) cover it.

### D12 Testability and guards
- `ChunkedUploadPolicy`, an injectable `delay` for D6 (default `kotlinx.coroutines.delay`) and the abort bounds of D8 are bundled in one test seam of `UploadWorker` (`ChunkedUploadSettings`, an `@VisibleForTesting internal` property, so the constructor stays within detekt's parameter limit without a suppression). User cancels in tests use the existing `userCancelledOverride`. The default WorkManager constructor is unchanged.
- Timeouts of real OkHttp calls (a hanging `DELETE`) are tested with a small injected bound and a MockWebServer that never answers, not with `runTest` virtual time: virtual time does not advance a blocking socket read.
- MockWebServer dispatchers answer by method and path.
- `VerifiedRecordOnlyFromOriginalRegressionTest` already scans every main source. It must keep passing unchanged, which proves that `ChunkedUploader.kt` never constructs `VerifiedUpload` or `UploadSource.Original`.

## Risks / Trade-offs

- **[MOVE outcome unknown after a read timeout or connection loss]** → The next attempt's `MKCOL` decides. `AlreadyExists` re-sends the `MOVE`. `Created` (folder gone because the `MOVE` completed) restarts from chunk 1, which overwrites the file with identical bytes and may cost bandwidth and one extra Nextcloud file version. The 30 min assembly read timeout makes this rare.
- **[Re-reading the prefix costs local I/O]** (for example 4 GB at about 200 MB/s ≈ 20 s per resume) → This is local only, with no network traffic. It is required because `MessageDigest` state cannot be persisted, and it gives the "local file changed" check for free.
- **[A file changes without changing size or prefix (tail edit)]** → Verification of originals (server hash or download) catches it as a mismatch. Unverified entries have the same exposure as today's single `PUT`.
- **[Mixed redacted / original content]** → `original` is part of the state, so a change in permission restarts the transfer.
- **[X-Hash on MOVE depends on server version and storage]** → Absence falls back exactly as for a `PUT` without the header. It is never treated as a mismatch.
- **[Queue file rewritten per chunk]** (≤ 10,000 small atomic writes per file) → This is negligible next to 10 MiB of network I/O per chunk.
- **[Server's v2 session metadata lost (memcache eviction)]** → Handled as a lost upload (412 on a chunk `PUT`), with an abort and a restart. If it is lost right before the `MOVE`, the `MOVE` fails with 400 (size check against `OC-Total-Length`) and the entry fails for this run; the retry starts a fresh transfer. A wrongly assembled file is never accepted.
- **[Chunk size lower bound]** → Nextcloud v2 requires chunks of at least 5 MB except the last. Production `chunkBytes` (10 MiB) satisfies it; tiny sizes are for MockWebServer tests only.
- **[Orphaned upload folders when the app cannot send DELETE]** (process killed during cancel, account switched) → The server's 24 h cleanup removes them.
- **[Stop or cancel during the post-`MOVE` verification download]** (known limit) → The `MOVE` succeeded and the transfer state is cleared, but the entry is booked only after `verifyUpload()`. A stop or user cancel while a download verification runs leaves the file unbooked: the next run (or the retry file) uploads it again from the start. This is the same as for a single `PUT` stopped during its verification and costs only bandwidth; the server copy is overwritten with identical bytes.
- **[Repeated transient failures cost interruptions]** → A long outage, or a server that keeps answering 503 to chunk requests, uses up the `interruptions` budget (5 runs with exponential back-off) and then gives up with the "stopped repeatedly" reason. The confirmed chunks are kept until then.
- **[Follow-ups from the security review, not fixed here]** (known limits, no confidentiality impact):
  - (I2) Transfers left on the server until Nextcloud's own cleanup: queues finalized by `recoverCancelledUploads()` after process death, and a process death between the cancel claim and the `DELETE`. (The third case of the finding, a user cancel before the run read its configuration, is fixed: `cancelAborter()`, D7.) Possible follow-up: abort binding-matched transfers in the recovery.
  - (I3) The binding covers the uploads collection and destination URLs but not the login name, so a credential change on the same URL resumes the same transfer id. The URL's user segment is the Nextcloud user id and the server rejects a foreign uploads collection (that path ends in D10's fallback with a best-effort abort).
  - (I4) A logout or server change does not stop a running chunked upload: the run keeps its configuration snapshot and continues with the old credentials to the old origin, as a single `PUT` already does, but for much longer (hours for a multi-GB file). Records stay guarded by the configuration re-read. Possible follow-up: re-read the configuration every N chunks and stop when URL or user changed.

## Migration Plan

- No migration. The `chunked` key is optional: existing queue and retry files parse unchanged and upload from the start.
- An older app version that reads a queue file with `chunked` ignores the key and uploads with a single `PUT`. The orphaned upload folder is removed by the server cleanup.
- Rollback = revert. Queue files with `chunked` stay parseable by the old parser.

## Open Questions

- Should the threshold be lower (for example 20 MiB) for users on slow mobile uplinks? This is a constant in `ChunkedUploadPolicy` and can be tuned after field feedback without changing specs or tasks.
- `X-Hash-SHA256` on the `MOVE` was confirmed from Nextcloud `master` sources only. The implementer should record in the PR which Nextcloud version was checked manually (it is optional by design either way).
