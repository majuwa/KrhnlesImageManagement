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
- The notification Cancel action itself. That belongs to the sibling change `cancel-running-upload`.

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
`ChunkedUploadPolicy(thresholdBytes = 50 MiB, chunkBytes = 10 MiB, maxChunks = 10_000)` is injected into `UploadWorker` through its internal constructor so tests can use tiny values. The default constructor keeps the default policy.

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

Parsing is strict (`opt` + type checks, value ranges), but **an invalid `chunked` object is dropped, not fatal**. The entry uploads from the start, and the file stays valid. The progress is an optimization: dropping it never sends data to an unintended place, while treating the whole queue as malformed would delete the user's upload. This differs on purpose from the folder rule ("mixed / non-string → malformed and deleted"), where a wrong value would change the destination.

- `withUploaded(i)` clears the state.
- `toRetryJson()` strips it, so retry files never carry progress.
- `toProgressJson()` keeps it.
- A new `withChunked(i, state?)` updates the entry, followed by `rewriteQueueFile()`.

A failed rewrite only loses resume granularity, the same as an unsaved `uploaded` marker today.

### D5 One attempt (`ChunkedUploader.attempt`)
New `worker/ChunkedUploader.kt` keeps `UploadWorker` within detekt's size limits. An attempt does the following, in order:

1. Read `before` (`mediaFileStateOf`, verifiable entries) and open the source exactly as today (`openEntry`). Inside the stream's `use`, read `length = uploadLengthOf(…)` first, then wrap the stream in `DigestCountingInputStream`. A chunked transfer always needs the prefix hash, but the digest is used for verification only when the source is `Original` and verifiable.
2. Compute `binding`. Keep the saved state only if `transferId`, `binding`, `totalBytes == length` and `original` all match. If the binding matches but something else does not, `abortChunkedUpload` the old id. If the binding differs, send no request for the old id (it may belong to another account), clear the state and persist.
3. `verifiedUploads.applyUploadLogged(target)` (records at the target are dropped before the first request of the attempt, as before every `PUT`).
4. Saved state: `MKCOL` → `AlreadyExists` continues; `Created` means the server lost the folder, so reset to `confirmedChunks = 0` with the same id. No state: new id, `MKCOL` must be `Created`. On `AlreadyExists` for a fresh id, or any non-413/507 HTTP error on a fresh id, see D10.
5. Read the confirmed prefix through the digest stream into a discard buffer and compare `sha256Hex()` with `prefixSha256`. On mismatch: abort the transfer, clear the state, persist, and end the attempt as `Restart`. The next attempt reopens the file.
6. For each remaining chunk: send it with `uploadChunk`. After a 2xx, save `confirmedChunks + 1` and the new `prefixSha256` (the `sha256Hex()` snapshot is clone based and already exists). Then, except after the last chunk, call `onChunkConfirmed(percent)`, which updates the notification through `setForegroundOrFalse`. A refusal ends the attempt as `Interrupted`.
7. After the last chunk, require EOF (`read()` returns -1). Extra bytes mean the file grew, which is handled like a prefix mismatch.
8. `finishChunkedUpload(…, requestServerHash = hashed && verifier.requestsServerHash)`. The `UploadResponse` and the digest stream go back to `uploadPhoto`, which calls the unchanged `verifyUpload()` for originals. A verified record can therefore still only come from `UploadSource.Original`, and the digest covers the whole file read once in this attempt. The state is cleared when the entry is marked uploaded.

A resumed transfer that gets 404 / 409 / 412 on its first chunk `PUT` or on `MOVE` has lost its server-side upload, for example because the distributed cache dropped the v2 session. That attempt ends as `Restart` after an abort.

### D6 Attempts within a run and failure classes
`ChunkedUploader.upload()` runs up to `MAX_CHUNKED_ATTEMPTS_PER_RUN = 3` attempts. `Restart` and transient failures start the next attempt:

- transient: `IOException` that is not a cancellation, or `WebDavHttpException` with 408, 423, 429 or 5xx;
- wait between attempts: 2 s, then 8 s (`delay`, cancellable).

Other failures end the entry at once and map through `toUploadFailure()`: 413 → `TooLarge`, 507 → `StorageFull`, anything else (including 3xx and 400 on `MOVE`) → `Other`. On a final failure the worker sends a best-effort abort (D8), clears the state and persists. The entry then goes into the retry file like any failed entry.

*Alternative considered:* keep the state in the retry file so a manual retry resumes. That was rejected. Retries can happen days later, after the server's 24 h cleanup. It would also add progress to the retry-file contract (`handleRetry`, `ShareStaging.sweep` age logic) for little gain.

### D7 Interruptions and stops
- **`Interrupted`** (refused foreground between chunks): `uploadFiles` returns `UploadRun(interrupted = true)` with the progress saved. `interrupted()` is unchanged. Below the cap the run returns `Result.retry()` with the queue kept, and the next run resumes. At the cap, `finish(gaveUp = true)` puts the entry into the retry file and aborts its transfer (D8).
- **WorkManager stop or process death**: the coroutine is cancelled inside `executeCancellable`. The saved state stays in the queue file and the next run resumes. No abort is sent.
- **User cancel**: `stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP`, read through an injectable `isCancelledByApp: () -> Boolean` so that tests do not need restricted `stop()` APIs. The cancellation handler of the chunked attempt runs the abort in `withContext(NonCancellable)` and then rethrows. This hook works whatever the sibling `cancel-running-upload` does with the queue file.

### D8 Cleanup
`abortTransfer(state)` = `withTimeoutOrNull(30 s) { client.abortChunkedUpload(id) }`. It is sent only when the binding matches the current config. Failures are logged by exception type and HTTP code only, and never change the entry result or the notification.

It is called on:
- a final entry failure,
- a give-up,
- a user cancel,
- a prefix, source or length mismatch,
- a lost server-side upload (before restart).

The server's 24 h `UploadCleanup` is the backstop for transfers the app can no longer reach: queue files deleted as malformed, a changed account, a downgrade to an older app version.

### D9 Verification and records
Nothing changes in `UploadVerifier`, `verifierFor()`, `NextcloudHashVerifier` or `VerifiedUploadsStore`. The `MOVE` carries `X-Hash: sha256` under the same condition as today's `PUT`.

On regular Nextcloud storage the `MOVE` answer carries `X-Hash-SHA256` of the assembled file, and the existing comparison applies. On object storage (`ChunkingV2Plugin`) and older servers it does not, and `NextcloudHashVerifier`'s fallback (download if enabled, else `Unavailable`) applies unchanged.

The `before.size == local.sizeBytes` provability check applies to the whole-file digest. `applyUpload(target)` runs before the first request of every attempt (D5 step 3), and `recordOutcome()` runs after the entry as today. Staged (shared) entries can be chunked too (threshold permitting) and stay unverified.

### D10 Uploads endpoint unavailable
If the `MKCOL` of a **new** transfer fails with `WebDavHttpException` and a code other than 413 / 507 (404, 405, 403, 501, 3xx …), the worker sets a run-wide `chunkingUnavailable` flag and uploads this and all later entries with a single `PUT`. 413 / 507 fail the entry. Transport errors are transient (D6). The flag is not persisted, so the next run tries again.

### D11 Progress notification
`buildProgressNotification(current, total, filePercent: Int? = null)` uses the existing `"$current / $total"` form when `filePercent` is null. Otherwise it uses the new string `notification_uploading_file_progress` = `"%1$d / %2$d · %3$d %%"`, and the progress bar stays per batch. The update happens per confirmed chunk through `setForegroundOrFalse`, which is also the budget check of D7. If `cancel-running-upload` adds an action to the progress notification, it must stay on this variant too, and that change's tests cover it.

### D12 Testability and guards
- `ChunkedUploadPolicy`, `isCancelledByApp` and an injectable `delay` for D6 (default `kotlinx.coroutines.delay`) are internal constructor parameters of `UploadWorker`.
- MockWebServer dispatchers answer by method and path.
- `VerifiedRecordOnlyFromOriginalRegressionTest` already scans every main source. It must keep passing unchanged, which proves that `ChunkedUploader.kt` never constructs `VerifiedUpload` or `UploadSource.Original`.

## Risks / Trade-offs

- **[MOVE outcome unknown after a read timeout or connection loss]** → The next attempt's `MKCOL` decides. `AlreadyExists` re-sends the `MOVE`. `Created` (folder gone because the `MOVE` completed) restarts from chunk 1, which overwrites the file with identical bytes and may cost bandwidth and one extra Nextcloud file version. The 30 min assembly read timeout makes this rare.
- **[Re-reading the prefix costs local I/O]** (for example 4 GB at about 200 MB/s ≈ 20 s per resume) → This is local only, with no network traffic. It is required because `MessageDigest` state cannot be persisted, and it gives the "local file changed" check for free.
- **[A file changes without changing size or prefix (tail edit)]** → Verification of originals (server hash or download) catches it as a mismatch. Unverified entries have the same exposure as today's single `PUT`.
- **[Mixed redacted / original content]** → `original` is part of the state, so a change in permission restarts the transfer.
- **[X-Hash on MOVE depends on server version and storage]** → Absence falls back exactly as for a `PUT` without the header. It is never treated as a mismatch.
- **[Queue file rewritten per chunk]** (≤ 10,000 small atomic writes per file) → This is negligible next to 10 MiB of network I/O per chunk.
- **[Server's v2 session metadata lost (memcache eviction)]** → Handled as a lost upload (412), with an abort and a restart.
- **[Orphaned upload folders when the app cannot send DELETE]** (process killed during cancel, account switched) → The server's 24 h cleanup removes them.

## Migration Plan

- No migration. The `chunked` key is optional: existing queue and retry files parse unchanged and upload from the start.
- An older app version that reads a queue file with `chunked` ignores the key and uploads with a single `PUT`. The orphaned upload folder is removed by the server cleanup.
- Rollback = revert. Queue files with `chunked` stay parseable by the old parser.

## Open Questions

- Should the threshold be lower (for example 20 MiB) for users on slow mobile uplinks? This is a constant in `ChunkedUploadPolicy` and can be tuned after field feedback without changing specs or tasks.
- `X-Hash-SHA256` on the `MOVE` was confirmed from Nextcloud `master` sources only. The implementer should record in the PR which Nextcloud version was checked manually (it is optional by design either way).
