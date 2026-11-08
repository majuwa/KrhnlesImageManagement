# Tasks

## 1. Pure foundations: targets, names, types, failures

- [x] 1.1 Extract `baseFolderUrlOf(config): HttpUrl` from `AlbumsRepository` (config URL plus normalized base-folder segments) and use it in `AlbumsRepository`. Verify that the existing `AlbumsRepository*` / `WebDavHrefTest` suites pass unchanged.
- [x] 1.2 Add `download/DownloadTarget.kt` with `resolveDownloadTarget(config, albumHref, photoHref): HttpUrl?`. It resolves the album strictly below the base folder and the photo strictly below the album, rejects a trailing `/` and logs no href. Verify with `DownloadTargetTest`, covering:
  - valid percent-encoded Nextcloud hrefs
  - `..` / `%2e%2e` / encoded slash
  - other origin
  - photo in another album or equal to the album
  - album equal to or outside the base folder
  - base folder changed
- [x] 1.3 Add `download/DownloadNames.kt`:
  - `DownloadImageType`, with an extension map built from `SharedImageType`: jpg / jpeg, png, gif, webp, heic, heif, avif, bmp, dng
  - `downloadImageTypeOf(name)`
  - `downloadDisplayName(href, now, random)`: decoded last segment; reserved, control, Cf / bidi / separator and surrogate characters replaced; trimmed; ≤ 255 UTF-8 bytes with the extension kept; `photo_<millis>_<8hex>.<ext>` fallback
  - `albumFolderName(albumHref)`: one segment, ≤ 100 bytes, `Album` fallback

  Verify with `DownloadNamesTest`: RLO name, `:*?"<>|`, multi-byte truncation at the byte limit, dots / blank, leading dot (`.hidden` album / `.IMG.jpg` → no leading dot), svg / tiff / mp4 / no extension → null type, case-insensitive extensions.
- [x] 1.4 Add `data/DownloadFailure.kt`, with `enum DownloadFailure` covering the 12 reasons of the spec and `Throwable.toDownloadFailure()`. It branches on `WebDavHttpException.code` and exception types (3xx / Unsafe / UnexpectedContent → Rejected, 404 / 410, 401 / 403, SSL cause chain, IO kinds, `DownloadTooLargeException`, ENOSPC) and never on messages. Verify with a table-driven `DownloadFailureTest`, including that `CancellationException` is never mapped.

## 2. Transport: streaming download on a non-redirecting client

- [x] 2.1 Add `WebDavClient.download(url, maxBytes, sink)`:
  - runs on the existing `noRedirectClient` (pin-aware, no `callTimeout`, default read timeout); no new client
  - same-origin check → `UnsafeHrefException` without a request
  - `GET` with `Accept-Encoding: identity` through `executeCancellable`
  - non-2xx → `WebDavHttpException(code)`
  - `Content-Length > maxBytes` → `DownloadTooLargeException` before the sink runs
  - non-image, non-octet-stream `Content-Type` → `UnexpectedContentException`
  - body passed as `LimitedInputStream`
  - add `MAX_DOWNLOAD_BYTES` = 200 MiB

  Verify that it compiles and that the existing `WebDavClient*` tests pass.
- [x] 2.2 Add `WebDavClientDownloadTest` (MockWebServer). It covers:
  - streamed bytes are equal to the body
  - `Accept-Encoding: identity` is sent
  - `Authorization` is sent only to the configured origin, and a URL on another port → no request (`requestCount` 0)
  - 301 / 302 / 307 fail and the location is never requested
  - 404 → `WebDavHttpException(404)`
  - an announced length above the cap fails before the sink
  - a chunked body above the cap fails while streaming
  - `text/html` is rejected
  - cancellation while stalled before the first byte and mid-body returns within a hard few-second limit

  Verify that the test passes.
- [x] 2.3 Add a `NoBufferedDownloadRegressionTest` (source scan via `krhnles.moduleDir`). It checks that the download path files contain no `body.bytes()`, `body.string()`, `readBytes()` or `followRedirects(true)` / `followSslRedirects(true)`. Verify that it passes, and that it fails on a fixture string built by concatenation.
- [x] 2.4 Update the CLAUDE.md Security Conventions with a "Photo downloads" bullet: `resolveDownloadTarget`, the non-redirecting `noRedirectClient`, no `callTimeout`, `executeCancellable`, streaming with a 200 MiB cap, the `Content-Type` veto, and the pin-aware `WebDavClient(config, pinFor(url))` with a per-photo pin refresh. Verify that the bullet names every guard test from 2.2 and 2.3.

## 3. MediaStore output: pending items owned by the app

- [x] 3.1 Extend the test `fakes/FakeMediaStoreProvider` for `insert` into `images/media`:
  - records `RELATIVE_PATH`, `DISPLAY_NAME`, `MIME_TYPE`, `IS_PENDING` and `OWNER_PACKAGE_NAME`
  - uniquifies taken names as ` (1)`, ` (2)`
  - `openFile(uri, "w")` backed by a temp file
  - `update` of `IS_PENDING` and `delete`
  - an injectable insert / write failure, and ENOSPC

  Existing query behaviour must be unchanged. Verify that all existing grid, removal and worker tests using the fake still pass.
- [x] 3.2 Add `data/LocalMediaWriter.kt`:
  - `insertPending(relativePath, displayName, mimeType)` into `MediaStore.Images` (`VOLUME_EXTERNAL_PRIMARY`), giving a `PendingMediaItem`
  - `write {}`, `publish()` (`IS_PENDING = 0`) and `discard()` (delete in `NonCancellable`, the only delete)
  - `discardLeftover(uri)`: only for a validated `media` / `images/media/<id>` uri that is still pending and owned by this package
  - `freeBytes()` (allocatable bytes, falling back to `usableSpace`)
  - ENOSPC → `StorageFull`

  Verify with `LocalMediaWriterTest` on the extended fake:
  - publish clears pending
  - discard removes the item
  - a leftover is discarded only when it is pending and owned by this package (published, foreign, other-authority and video uris are refused)
  - a taken name gets a unique variant and the original is untouched
- [x] 3.3 Add `DownloadOwnItemsOnlyRegressionTest` (source scan). It checks:
  - in the download path files, `delete(` appears only inside `LocalMediaWriter.discard` / `discardLeftover`
  - no `createDeleteRequest` and no `MANAGE_MEDIA` / `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` in the manifest

  Verify that it passes and that `NoHardDeleteLocalMediaRegressionTest` stays unchanged and green.
- [x] 3.4 Document `LocalMediaWriter` in the CLAUDE.md coding standards (a new rule 11 "Saving album photos": pending insert, `Pictures/KroehnSync/<album>/`, discard-only deletes, no permission) and in the docs/architecture.md package tree. Verify that both name the guard tests from 3.2 and 3.3.

## 4. Download queue file

- [x] 4.1 Add `worker/DownloadQueue.kt`:
  - `downloadQueueFilesDir` = `noBackupFilesDir`
  - `isDownloadQueueFile(context, file)`: regex `download_queue_[0-9]+_[0-9a-f-]+\.json` and canonical parent, no legacy dir
  - strict parse with `opt` + `is String` of `serverUrl`, `albumHref`, `entries[].href`, optional `downloaded`, optional `pendingItem` and `interruptions` (clamped to `0..MAX_DOWNLOAD_RUN_ATTEMPTS`)
  - serialization for `rewriteQueueFile()`
  - no credential fields

  Verify with `DownloadQueueTest`: round trip, a non-string or missing field → malformed, a subdirectory / traversal / upload-queue name rejected, interruptions clamping (negative, Double, huge), and no key containing "user" / "password" / "token" in the written JSON.

## 5. Download worker and notifications

- [x] 5.1 Add the strings and plurals to `values/strings.xml`:
  - `download_saving` (plural), `download_saved_count` / `download_failed_count` (plurals)
  - `download_complete` / `download_partial` / `download_failed` / `download_cancelled`
  - one `download_failure_*` per `DownloadFailure`
  - `download_not_configured`, `download_enqueue_failed`
  - `channel_name_downloads` / `channel_desc_downloads`
  - `cd_save_to_device`, `action_cancel_download`

  Also add the `ic_notification_download` monochrome vector drawable (next to `ic_notification_upload`), and a `downloadFailureMessage(resources, failures)` helper using `joinNotificationTexts()`. Verify with a Robolectric test that every `DownloadFailure` resolves to a non-blank string and that the plurals format for 1 and 3.
- [x] 5.2 Add `worker/DownloadWorker.kt`:
  - a public `(context, params)` constructor, and an internal one with `CredentialRepository`, `TrustedCertificateRepository`, a `(WebDavConfig, PinnedCertificate?) -> WebDavClient` factory, writer factory, free-space provider and clock; `getForegroundInfo()` overridden
  - the album folder derived from `albumHref` via `albumFolderName()`; the pin re-read per entry, client rebuilt when it changed
  - validated queue file, else success with no request
  - discard a leftover `pendingItem`
  - per entry: bounded config re-check (URL equal and valid), `resolveDownloadTarget`, type / name, free-space check, `setForegroundOrFalse`, `download` into a pending item, `publish`, `downloaded` marker via `rewriteQueueFile()`
  - stop rules: AccessDenied / Certificate / StorageFull / ConfigurationChanged, and 5 consecutive Connection / ServerError failures
  - interruptions cap and backstop
  - user cancel vs. system stop via `stopReason`
  - `Result.success()` for every finished batch, `Result.retry()` only when interrupted

  Verify that it compiles and that 5.3–5.5 pass.
- [x] 5.3 Add the notifications:
  - `download_channel` (IMPORTANCE_LOW)
  - foreground / progress id 1004 with determinate progress and a Cancel action (`createCancelPendingIntent`)
  - result id 1005 tagged with the queue file name, gated on `POST_NOTIFICATIONS`
  - `ForegroundInfo` type `dataSync`
  - small icon `ic_notification_download`
  - `createCancelPendingIntent(id)` only in `worker/DownloadNotifications.kt`

  Verify with `DownloadWorkerNotificationTest`: ids and tag, channel, small icon, text for complete / partial / failed / cancelled, and that an upload result (1002) and a download result coexist.
- [x] 5.4 Add `DownloadWorkerRunTest`: the real worker via `TestListenableWorkerBuilder`, MockWebServer, `FakeCredentialRepository`, extended `FakeMediaStoreProvider`, `doWork()` called directly. It covers:
  - **success:** items published with the right `RELATIVE_PATH` / name / MIME / bytes; queue deleted; no `UploadedPhotosStore` or `VerifiedUploadsStore` write
  - **404 and 500:** item discarded, counts and reasons
  - **size cap:** announced and streamed; item discarded, the next photo still saved
  - **unsupported `.mp4` / `.svg`:** no request
  - **unsafe href:** no request
  - **redirect:** not followed
  - **401:** batch stopped after the first request
  - **5 consecutive 503:** stop
  - **config URL changed or logged out mid-batch:** no further request
  - **ENOSPC:** stop, nothing partial
  - **foreign queue path:** nothing happens
  - **work input:** contains only the queue path
  - **pin:** the client factory receives `pinFor(serverUrl)`; a pin change between entries rebuilds the client

  Verify that the test passes.
- [x] 5.5 Add `DownloadWorkerCancelTest`:
  - **user cancel mid-body** (stalled MockWebServer body, `stopReason` cancelled by app): pending item discarded, earlier items kept, queue deleted, cancelled notification with counts, returns within a hard limit
  - **system stop:** queue kept with markers, and the re-run downloads only the remaining entries
  - **leftover `pendingItem` from a killed run:** discarded on resume
  - **refused foreground:** `Result.retry()` and `interruptions` incremented; gives up at the cap and at the backstop with the `Interrupted` reason

  Verify that the test passes.
- [x] 5.6 Add a "Saving album photos" section to docs/architecture.md and add the worker to the package tree. Cover the queue format, chain semantics (APPEND_OR_REPLACE, never `failure()`, cancel cascade), notification ids 1004 / 1005, stop rules, user cancel vs. system stop, and interruptions. Verify that the section references every test class from 5.1–5.5.

## 6. Enqueuing

- [x] 6.0 The user approved the guard change of design Decision 1 ("Existing guard"). Narrow `NoUploadWorkCancelOutsideCancelPathRegressionTest`: `ExistingWorkPolicy.APPEND_OR_REPLACE` allowed only in `download/DownloadEnqueuer.kt`, `createCancelPendingIntent` added to the cancel APIs and allowed only in `worker/DownloadNotifications.kt`, `REPLACE` still forbidden everywhere. Add planted-copy cases for both allowances (allowed in their file, flagged in any other file), and update the CLAUDE.md rule 4 wording to match. Verify that the test passes and still fails on the planted violations.
- [x] 6.1 Add `download/DownloadEnqueuer.kt`, implementing `DownloadRequester`. It is owned by `KrhnlesApplication.downloadEnqueuer` and runs in `appScope`. It:
  - reads the config once (`withTimeoutOrNull(3 s)`); if not valid → `download_not_configured` toast and no file
  - under a `Mutex` with a timeout, sweeps orphaned queue files: `getWorkInfosByTag("download_queue:<name>")` bounded to 5 s, fail-closed; finished or no work → `discardLeftover(pendingItem)` first, then delete
  - writes the queue file
  - awaits `enqueueUniqueWork("photo_download", APPEND_OR_REPLACE, downloadWorkRequest(file))` inside the mutex (CONNECTED only, exponential back-off, tags `photo_download` / `download_queue:<name>`, never `photo_upload`)
  - shows the plural toast on Main; on failure deletes the file and shows `download_enqueue_failed`
  - lets no exception escape the scope except cancellation

  Verify that it compiles.
- [x] 6.2 Add `DownloadEnqueuerTest` (injected scope, dispatchers, `WorkManagerTestInitHelper`). It covers:
  - the file is in `noBackupFilesDir` with a valid name and no credentials
  - the input `Data` has only the queue path
  - unique work name, policy and tags (no `photo_upload` tag)
  - a new batch after a user-cancelled one still runs (not cancelled by the cancelled prerequisite)
  - no Wi-Fi-only constraint even when the setting is on
  - not configured → no file, no work
  - an enqueue failure deletes the file
  - the sweep deletes a file whose work was cancelled (and discards its recorded `pendingItem`), keeps one with enqueued / running work, and deletes nothing on timeout

  Verify that the test passes.

## 7. Entry points in the album flow

- [x] 7.1 Add `AlbumDetailViewModel.saveToDevice(photos)`:
  - keeps only hrefs in the current `detailState.photos` of `currentHref`
  - is a no-op while not loaded, or on `NotConfigured` / `ConfigurationChanged`
  - calls the injected `DownloadRequester` with `(albumHref, hrefs)` (a `@JvmOverloads` default from `KrhnlesApplication`)

  Verify with `AlbumDetailViewModelSaveTest`: stale photos dropped, nothing during a config change, album href passed, videos passed through (the worker reports them). Also verify that `ViewModelFactoryRegressionTest` still creates the ViewModel with the default factory.
- [x] 7.2 Add a Save action to `AlbumDetailScreen` `SelectionTopBar` (`Icons.Default.Download`, `cd_save_to_device`, disabled while `isDeletingPhotos`). It calls `saveToDevice` with the selected photos and clears the selection. Verify with a Robolectric Compose test using a fake requester: the action exists with the localized label, it is disabled while deleting, it passes exactly the selected photos, and the selection is cleared.
- [x] 7.3 Add a top-end `IconButton` to the `FullscreenViewerScreen` overlay, shown only on non-video pages. It saves the current page's photo and keeps a `rememberSaveable` set of hrefs saved in this viewer session, which disables the button for them. Verify with a Robolectric Compose test (`LocalAsyncImagePreviewHandler` + `LocalInspectionMode`):
  - the button has `cd_save_to_device` and no file-name description
  - a tap requests exactly the current page's photo
  - a second tap on the same photo is ignored
  - after swiping, the next photo can be saved
  - a video page shows no Save button

  Also verify that `NoFilenameContentDescriptionRegressionTest` passes.
- [x] 7.4 Update user-facing docs:
  - README.md "Features" / "Usage": Save to device from the viewer and selection mode, saved to `Pictures/KroehnSync/<album>`, images only, background with Cancel
  - docs/requirements.md: new rows A-008 (save selected album photos to the device), V-013 (save the current photo from the viewer; A-006 / A-007 and V-010 – V-012 are taken) and U-/N- rows for the background download, caps and no-permission constraint

  Verify that the statuses read "Implemented" only after 7.1–7.3 are green.

## 8. Guarantees toward uploads and removal

- [x] 8.1 Extend `RemovalCandidateCheckerTest`. An item saved by the download writer, with the same display name and size as a verified, recorded photo, is not a candidate, and the recorded original still is. A download-only device yields `NothingToRemove`. Verify that the test passes.
- [x] 8.2 Add an assertion to `DownloadWorkerRunTest` that after a successful batch the `UploadedPhotosStore` set and the `VerifiedUploadsStore` records (both pre-seeded through their test constructors) are unchanged. Verify that the test passes.
- [x] 8.3 Document in CLAUDE.md rule 11 and docs/architecture.md that downloads never touch uploaded markers or verified records, and why. Verify that the wording matches the `uploaded-photo-markers` / `remove-uploaded-photos` deltas.

## 9. Integration checks

- [x] 9.1 Run `./gradlew test` and verify that all tests pass.
- [x] 9.2 Run `./gradlew spotlessCheck`, `./gradlew detekt` (no new issues, or ask the user) and `./gradlew lintDebug` (zero issues, no suppressions), and verify that each passes.
- [x] 9.3 Run `./gradlew jacocoTestReport` and verify that the new `download/`, `worker/Download*` and `data/LocalMediaWriter` classes appear with coverage in the HTML report.
- [ ] 9.4 Manual device check (API 35+):
  - save from the viewer and from selection mode
  - the files appear in the gallery under `Pictures/KroehnSync/<album>/`
  - Cancel mid-transfer leaves no partial file
  - airplane mode mid-batch resumes later
  - concurrent upload and download notifications do not replace each other

  Record the result in the PR.

## 10. Review fixes

- [x] 10.1 `Certificate` only for `CertificateException` / `SSLHandshakeException` / `SSLPeerUnverifiedException` (shared `hasCertificateFailure()`); a plain `SSLException` is `Connection`. `DownloadFailureTest`.
- [x] 10.2 A `Connection` failure without a validated network ends the run with `Result.retry()`, queue and markers kept, no interruption counted (`networkAvailable` seam, backstop on `runAttemptCount`). `DownloadWorkerRunTest`.
- [x] 10.3 `DownloadWorkerCancelWorkManagerTest`: a real user cancel (`cancelWorkById`) is recognized by the real rule and finalizes the queue; a constraint stop keeps it.
- [x] 10.4 A pending-item record is cleared only after a real delete; `providerCall` catches every non-cancellation exception; every writer failure is a `LocalMediaWriteException`. `LocalMediaWriterTest`, `DownloadWorkerRunTest`.
- [x] 10.5 Content checks fail closed: unparseable `Content-Type`, non-`identity` `Content-Encoding`, only allow-listed image subtypes, unknown length only when chunked (HTTP/1.x). `WebDavClientDownloadTest`.
- [x] 10.6 File names are at most 200 bytes. `DownloadNamesTest`.
- [x] 10.7 A stop found while handling a refused foreground service is rethrown before an interruption is counted. `DownloadWorkerRunTest`.
- [x] 10.8 Viewer marks a photo saved only when a download was requested; both entry points tell the user when nothing was requested; the selection stays; a saved button has a "Saved" state description. `AlbumDetailViewModelSaveTest`, `AlbumDetailSaveTest`, `FullscreenViewerSaveTest`.
- [x] 10.9 Only the lock wait is bounded (30 s), the sweep separately (60 s). `DownloadEnqueuerTest`.
- [x] 10.10 Free space is re-checked every 4 MiB while streaming; an unknown length needs more than the margin free. `DownloadWorkerRunTest`.
- [x] 10.11 First-bytes signature check per file type before the pending item is inserted. `ImageSignatureTest`, `DownloadWorkerRunTest`.
- [x] 10.12 The queue carries an account fingerprint (SHA-256 of URL and user name); a mismatch fails the rest with `ConfigurationChanged`; an absent field is URL-bound (documented in the design). `DownloadQueueTest`, `DownloadEnqueuerTest`, `DownloadWorkerRunTest`.
- [x] 10.13 The sweep skips queue files younger than 30 s. `DownloadEnqueuerTest`.
- [x] 10.14 An unrecorded pending item is discarded and the photo fails with `WriteFailed` before any byte is written. `DownloadWorkerRunTest`.
- [x] 10.15 Info: README notes that saved copies keep their location data; the cancel-guard test asserts the allowed files never reference upload work; `DownloadOwnItemsOnlyRegressionTest` catches a delete on any receiver name; tautological test cases removed.

## Workflow follow-up

- A follow-up (not this change) may extend `DownloadImageType` with video types targeting `MediaStore.Video` and a larger cap, and then show the viewer button on video pages.
- `german-translation` (last) translates the new `download_*` strings and plurals. The `KroehnSync` folder constant stays untranslated.
- Archive the change after review, and verify the archived `album-photo-download` spec.
