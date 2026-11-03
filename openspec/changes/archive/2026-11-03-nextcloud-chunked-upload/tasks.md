# Tasks

Every group ends green on `./gradlew test spotlessCheck detekt lintDebug` (no suppressions, no baseline). Tests live under `app/src/test/java/de/majuwa/android/paper/krhnlesimagemanagement/`.

## 1. Nextcloud chunking primitives (data layer)

- [x] 1.1 Move the path-segment rules of `WebDavClient.validatePathSegment()` into a shared internal function (same file or `data/NextcloudChunking.kt`) without behaviour change; verify `WebDavClientTest` and `UploadPathsTest` still pass.
- [x] 1.2 Add `data/NextcloudChunking.kt` with:
  - `nextcloudUploadsCollection(configUrl): HttpUrl?` (design D1: `…/remote.php/dav/files/<user>/…` → `…/remote.php/dav/uploads/<user>`, sub-path installs kept, invalid user segment / other shapes → null),
  - `isValidTransferId()` / `newTransferId()` (`krhnles-` + 32 lower-case hex),
  - `chunkName(n)` (5-digit zero-padded, 1..10000).

  Verify with a new `NextcloudChunkingTest` covering: login-flow URL, sub-path install, `/remote.php/webdav/`, plain WebDAV, `..` / encoded `/` / blank user segment, port preserved, transfer-id accept / reject cases, chunk names `00001` and `10000`.
- [x] 1.3 Add `assemblyClient` to `WebDavClient` (`uploadClient.newBuilder().readTimeout(30 min)`, constant `CHUNK_ASSEMBLY_READ_TIMEOUT_MINUTES`); verify in `WebDavClientTest` that it follows no redirects, has no call timeout and keeps the origin-restricted `Authorization` interceptor (cross-origin request gets no header).
- [x] 1.4 Add `BoundedInputStreamRequestBody` (one-shot, `contentLength = chunkLength`, copies exactly that many bytes, never closes the shared stream, `IOException` if the stream ends early); verify with unit tests for exact length, early EOF and that the underlying stream stays open.
- [x] 1.5 Add the four `WebDavClient` methods of design D2 (`createChunkedUpload`, `uploadChunk`, `finishChunkedUpload`, `abortChunkedUpload`), all through `executeCancellable`, segment-validated, rethrowing `CancellationException`, non-success → `WebDavHttpException(code)`. Verify with a new MockWebServer `WebDavClientChunkedTest`:
  - `MKCOL` 201 / 405 / 404 results; exact methods, paths and headers (`Destination` absolute target URL, `OC-Total-Length`, `Content-Length` per chunk);
  - `X-Hash: sha256` only on `MOVE` and only when requested, never on chunk `PUT`;
  - `X-Hash-SHA256` accepted only as 64 hex;
  - `MOVE` accepts only 201 / 204;
  - 302 on each method fails without a follow-up request;
  - 413 / 507 map through `toUploadFailure()`;
  - `DELETE` 404 counts as success;
  - coroutine cancellation during a slow chunk body cancels the call;
  - invalid transfer id / folder segment → failure with zero requests.

## 2. Persisted transfer state in the queue file

- [x] 2.1 Add `worker/ChunkedTransfer.kt` with `ChunkedTransferState` (fields of design D4), `ChunkedUploadPolicy` (threshold 50 MiB, chunk 10 MiB, max 10,000 chunks; `chunkBytesFor(total)` rounding up to whole MiB) and the `binding` hash function; verify with `ChunkedTransferTest` (chunk size at the 10,000-chunk boundary, 200 GiB file, binding changes with server / user / folder / file name).
- [x] 2.2 Extend `UploadQueueEntry` / `parseUploadQueue()` / `UploadQueue` with the optional `chunked` object:
  - strict typed reads; an invalid object (wrong types, out-of-range numbers, bad transfer id, non-hex hashes) is dropped and the file stays valid;
  - `withChunked(i, state?)`;
  - `withUploaded(i)` clears the state;
  - `toRetryJson()` strips it, `toProgressJson()` keeps it.

  Verify in `UploadQueueProgressTest` (round trip, files without the key, malformed variants of each field, retry JSON without progress, the existing `uploaded` / `interruptions` behaviour unchanged).

## 3. Chunked upload orchestration in the worker

- [x] 3.1 Add `worker/ChunkedUploader.kt` implementing one attempt as in design D5 (source opened via the existing `openEntry` / `openForUpload`; `uploadLengthOf()` before wrapping in `DigestCountingInputStream`; binding / length / original checks; `applyUploadLogged(target)` before the first request; `MKCOL` Created / AlreadyExists; prefix re-read and compare; per-chunk persist callback; EOF check; `MOVE` with `requestServerHash = hashed && verifier.requestsServerHash`). It returns the same `(Result<UploadResponse>, DigestCountingInputStream?)` shape as `put()` plus `Interrupted` / `Restart` outcomes. It never builds `VerifiedUpload`. Verify that `VerifiedRecordOnlyFromOriginalRegressionTest` passes unchanged.
- [x] 3.2 Add the attempt loop of design D6 (max 3 attempts per entry and run; transient = non-cancellation `IOException`, 408, 423, 429, 5xx except 501 / 507; injectable delay 2 s / 8 s; lost-upload 404 / 409 / 412 on any chunk `PUT` or `MOVE` → abort + `Restart`) and the run-wide fallback of design D10 (non-transient, non-413/507 `MKCOL` errors only; the state persisted before the `MKCOL` is cleared). Bundle `ChunkedUploadPolicy`, the delay and the abort bounds in the `ChunkedUploadSettings` test seam (design D12, `@VisibleForTesting internal` property, no new constructor parameter, no detekt suppression); user cancels keep using `userCancelled()` / `userCancelledOverride`, no new cancel predicate. The default WorkManager constructor stays. Verify that `UploadWorkerRunTest` / `UploadWorkerIntegrationTest` / `UploadWorkerCancelTest` still pass.
- [x] 3.3 Wire `UploadWorker.uploadPhoto()`: chunked when `nextcloudUploadsCollection` is non-null, the fallback flag is unset, and the length is known and ≥ threshold; otherwise the existing `put()`.
  - The per-chunk callback rewrites the queue file via `withChunked` + `rewriteQueueFile()` and calls `setForegroundOrFalse(buildForegroundInfo(…, filePercent))`.
  - `Interrupted` propagates as `UploadRun(interrupted = true)`.
  - A final failure aborts the transfer (design D8: `withTimeoutOrNull(30 s)`, binding must match, failures logged by type / code only) and clears the state.
  - A new transfer is persisted (`confirmedChunks = 0`) before its `MKCOL` (design D5 step 4).
  - `finish(gaveUp = true)` aborts the open transfers.
  - User cancel (`userCancelled()`, design D7): `finalizeOnCancel` first runs `finalizeCancelledQueue()`, then aborts the in-flight transfer and every binding-matched `chunked` state of the live queue (`NonCancellable`, ≤ 10 s each); a `MOVE` answered 2xx is booked via `bookOutcome()` and is never aborted afterwards.

  Keep `UploadWorker` within detekt limits by putting the logic in the new files.
- [x] 3.4 Add the string `notification_uploading_file_progress` (`"%1$d / %2$d · %3$d %%"`) to `values/strings.xml` and the `filePercent` variant of `buildProgressNotification()`; verify with a Robolectric test (`UploadNotificationsTest`) of the notification text with and without a percentage, and that the percentage variant still carries the "Cancel" action of `cancel-running-upload`. `lintDebug` must report no `StringFormatInvalid`.
- [x] 3.5 Add `UploadWorkerChunkedTest` (Robolectric + MockWebServer dispatcher by method / path, `FakeMediaStoreProvider`, tiny `ChunkedUploadPolicy`, existing fakes). Each case verifies:
  - **Happy path**: a 25-unit video → `MKCOL`, 3 chunk `PUT`s with correct bodies, `MOVE`; the entry is marked uploaded and the queue file has no `chunked` left.
  - **Below threshold, plain WebDAV URL, `/remote.php/webdav/` URL and unknown length**: exactly one `PUT` and no request to `/uploads/`.
  - **Resume across runs**: the first run is cancelled (not by the app) after chunk 2; the queue file holds `confirmedChunks = 2`; the second run sends `MKCOL` (405), chunks 3..n and `MOVE`, never chunks 1–2; no `DELETE`.
  - **Resume after a foreground refusal between chunks**: `Result.retry()`, `interruptions` incremented, progress kept.
  - **Fifth interruption**: give-up notification, retry file without `chunked`, one `DELETE`.
  - **Local file changed between runs** (different bytes, same length): `DELETE` of the old transfer and a new transfer id from chunk 1.
  - **Length changed**: same as above.
  - **Server lost the folder** (`MKCOL` 201 on resume): restart from chunk 1 with the same id.
  - **Binding changed** (other server URL): no request with the old id.
  - **Transient 503 on a chunk then success**: earlier chunks are not re-sent.
  - **Three transient failures**: entry failed, `DELETE`, retry file without progress.
  - **`MOVE` failure 400**: entry failed with "Other", `DELETE` sent, not marked uploaded.
  - **`MOVE` 507 and chunk 413**: notification reasons "server storage full" / "file too large for the server".
  - **`MOVE` IOException, then `MKCOL` 405 on the next attempt**: only `MOVE` is re-sent.
  - **User cancel during chunk 2** (`userCancelledOverride = { true }`): "Upload cancelled" notification and one retry file without `chunked` (finalized exactly once), then one `DELETE` of the transfer; the cancellation propagates.
  - **User cancel before the saved transfer's first request** (queue file with `chunked`, cancel during the prefix re-read or the back-off wait): one `DELETE` of the saved transfer id.
  - **User cancel right after `MOVE` 201**: entry marked uploaded, no `DELETE`.
  - **`DELETE` answering 500 or hanging**: the result and notification are unchanged; the hang ends after the injected small abort bound (MockWebServer that never answers, real time; not `runTest` virtual time).
  - **Lost session**: a chunk `PUT` answered 412 → `DELETE`, then the next attempt uses a new transfer id from chunk 1.
  - **`MKCOL` 404 on a new transfer**: this and the next large entry use a single `PUT`; the state persisted before the `MKCOL` is gone from the queue file.
  - **`MKCOL` 503 then 201 on a new transfer**: chunked with the same transfer id, no fallback.
  - **Stop right after a new `MKCOL`** (not by the app): the queue file holds the transfer id with `confirmedChunks = 0`; the next run's `MKCOL` 405 continues it.
  - **Staged shared entry above threshold**: chunked, never verified.
  - **3xx on a chunk**: fails without a follow-up request.
- [x] 3.6 Extend `UploadWorkerVerificationTest` for chunked entries (grant `ACCESS_MEDIA_LOCATION`, register the `setRequireOriginal` stream):
  - `MOVE` with a matching `X-Hash-SHA256` → record written via `applyUpload`, no download;
  - differing hash → `UploadFailure.Verification`;
  - no hash and download on → `GET` verifies;
  - no hash and download off → unverified, no record;
  - chunk `PUT`s never carry `X-Hash`;
  - resumed transfer → the record's size equals the whole file and the hash covers prefix + rest;
  - `Plain` source (no permission) → no `X-Hash` on `MOVE`, no record;
  - permission granted between runs (`original` changed) → restart from chunk 1.

  Also verify that `applyUpload(target)` runs before the `MKCOL` of every attempt (records at the target dropped even when the attempt fails).

## 4. Documentation

- [x] 4.1 Update `docs/architecture.md`:
  - replace "Nextcloud chunked upload v2 / resume is a follow-up" in the Videos section with a "Chunked upload (issue #69)" subsection: eligibility, protocol, state keys, resume checks, attempts, abort / cleanup, `assemblyClient`, `X-Hash` on `MOVE` and its fallback;
  - add a Key Decisions entry (lenient `chunked` parsing, no progress in retry files, prefix re-read instead of persisted digest state).

  Verify the section names every constant.
- [x] 4.2 Update `docs/requirements.md`: add an Upload requirement row, e.g. U-006 "Large files on Nextcloud are uploaded in resumable chunks (issue #69)". Verify it is marked Implemented only once 3.5 / 3.6 pass.
- [x] 4.3 Update `README.md` (Features / Upload Options): large videos on Nextcloud (at least 50 MiB) upload in 10 MiB chunks, continue after interruptions, show a percentage, and work despite server body-size limits. Plain WebDAV keeps single uploads. Remove any roadmap line about chunked uploads if `readme-roadmap-cleanup` has not already.
- [x] 4.4 Update `CLAUDE.md` rule 4 (and rule 10 where `X-Hash` is described): when chunked upload is used, the `chunked` queue-entry object and its lenient parsing, transfer-id / chunk-name rules, `assemblyClient` (30 min read timeout, no redirects, never a `callTimeout`), `X-Hash` only on `MOVE` never on chunk `PUT`s, prefix re-read through `DigestCountingInputStream`, abort rules (on a user cancel only after `finalizeCancelledQueue()`, never after a booked `MOVE`), `ChunkedUploadSettings` injection for tests, and the new test classes as guards. Verify the text matches the implemented constant names.

## 5. Integration check

- [x] 5.1 Run `./gradlew test spotlessCheck detekt lintDebug` and `./gradlew jacocoTestReport`; verify all pass with zero lint issues and no new detekt findings, and that the jacoco report covers `ChunkedUploader`, `NextcloudChunking` and the new `WebDavClient` methods.
- [ ] 5.2 Manual check against a real Nextcloud instance: upload a video larger than 600 MiB behind an nginx proxy with `client_max_body_size 512m`. Kill the app mid-transfer and confirm the resume skips confirmed chunks. Note in the PR whether the `MOVE` answered `X-Hash-SHA256` and which Nextcloud version and storage were used.
  - Archived on 2026-11-03 with this manual check still open (accepted by the user); do it before the release.

## 6. Review fixes (code and security review of 971be23)

- [x] 6.1 Network outage keeps the progress (code review #1, design D6): when the last of the `MAX_CHUNKED_ATTEMPTS_PER_RUN` attempts fails transiently, `ChunkedUploader.upload()` keeps the saved state and returns `ChunkedResult.Interrupted`, so the run ends through the existing interruption path (`Result.retry()`, counted in `interruptions`, give-up + abort at the cap). No `NetworkType.CONNECTED` constraint (decision in D6). Verify in the new `UploadWorkerChunkedRecoveryTest` (split from `UploadWorkerChunkedTest`, which hit detekt's `LargeClass`): `networkOutage_keepsTheConfirmedChunks_andTheNextRunResumesThem`, `threeTransient503s_keepTheProgress_andRetryTheRunAsAnInterruption`, `transientFailuresAtTheInterruptionCap_giveUp_deleteTheTransfer_retryFileWithoutProgress`.
- [x] 6.2 At most one lost-upload restart per file and run at the `MOVE` (code review #2 / security L2, design D5, `MAX_MOVE_LOST_RESTARTS_PER_RUN`); a second 404 / 409 / 412 on the `MOVE` is a final failure (no single-`PUT` fallback, see D5). Verify `move404Twice_…`, `move409Twice_…`, `move412Twice_…` and `move412Once_restartsTheFile_andUploadsIt`.
- [x] 6.3 `ChunkedUploader.discard()` sends a `DELETE` only for its own binding (code review #4 / security L1). Verify in the new `ChunkedUploaderTest`: an unopenable file with progress of another binding sends no request and clears the state; with its own binding one `DELETE`.
- [x] 6.4 An attempt maps any non-cancellation, non-`IOException` exception to a final failure (code review #5). Verify in `ChunkedUploaderTest`: a `SecurityException` from the provider stream during the prefix re-read, and one from `open()`.
- [x] 6.5 A user cancel before the run read its configuration still aborts saved, binding-matched transfers (`cancelAborter()`), and a folder-creation failure aborts the queue's open transfers before it is reported (code review #6). Verify `userCancelWhileWifiOnlyIsRead_…`, `userCancelWhileTheConfigIsRead_…`, `userCancelBeforeTheConfigIsKnown_sendsNothingForATransferOfAnotherAccount`, `folderCreationFailure_deletesTheSavedTransfer_beforeTheFailureIsReported`.
- [x] 6.6 `ChunkedTransferState` consistency (security I1): the chunk count is compared with `MAX_CHUNK_NUMBER` as a `Long` before `toInt()`, `chunkBytes` must be in `1..totalBytes`, `confirmedBytes` cannot overflow, and a saved transfer is resumed only with `chunkBytes == policy.chunkBytesFor(length)`. Verify the crafted objects in `UploadQueueProgressTest.invalidChunkedProgress_isDroppedAndTheQueueStaysValid`, `ChunkedTransferTest` and `savedTransferWithAnotherChunkSize_isDeleted_andStartedAgain`.
- [x] 6.7 Document as known limits (design Risks, spec scenario "Stopped during the verification after the MOVE"): a stop / cancel during the post-`MOVE` verification download re-uploads the file (code review #3); security I2 / I3 / I4 as follow-ups.
- [x] 6.8 Update `docs/architecture.md` and `CLAUDE.md` rule 4; run `./gradlew test spotlessCheck detekt lintDebug`.

## Workflow follow-up

- Archive the change after review and merge (`openspec archive nextcloud-chunked-upload`), syncing the `chunked-upload` spec and the `upload-verification` deltas.
