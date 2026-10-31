# Tasks

## 1. Pure foundations: targets, names, types, failures

- [ ] 1.1 Extract `baseFolderUrlOf(config): HttpUrl` from `AlbumsRepository` (config URL plus normalized base-folder segments) and use it in `AlbumsRepository`. Verify that the existing `AlbumsRepository*` / `WebDavHrefTest` suites pass unchanged.
- [ ] 1.2 Add `download/DownloadTarget.kt` with `resolveDownloadTarget(config, albumHref, photoHref): HttpUrl?`. It resolves the album strictly below the base folder and the photo strictly below the album, rejects a trailing `/` and logs no href. Verify with `DownloadTargetTest`, covering:
  - valid percent-encoded Nextcloud hrefs
  - `..` / `%2e%2e` / encoded slash
  - other origin
  - photo in another album or equal to the album
  - album equal to or outside the base folder
  - base folder changed
- [ ] 1.3 Add `download/DownloadNames.kt`:
  - `DownloadImageType`, with an extension map built from `SharedImageType`: jpg / jpeg, png, gif, webp, heic, heif, avif, bmp, dng
  - `downloadImageTypeOf(name)`
  - `downloadDisplayName(href, now, random)`: decoded last segment; reserved, control, Cf / bidi / separator and surrogate characters replaced; trimmed; ≤ 255 UTF-8 bytes with the extension kept; `photo_<millis>_<8hex>.<ext>` fallback
  - `albumFolderName(albumHref)`: one segment, ≤ 100 bytes, `Album` fallback

  Verify with `DownloadNamesTest`: RLO name, `:*?"<>|`, multi-byte truncation at the byte limit, dots / blank, svg / tiff / mp4 / no extension → null type, case-insensitive extensions.
- [ ] 1.4 Add `data/DownloadFailure.kt`, with `enum DownloadFailure` covering the 12 reasons of the spec and `Throwable.toDownloadFailure()`. It branches on `WebDavHttpException.code` and exception types (3xx / Unsafe / UnexpectedContent → Rejected, 404 / 410, 401 / 403, SSL cause chain, IO kinds, `DownloadTooLargeException`, ENOSPC) and never on messages. Verify with a table-driven `DownloadFailureTest`, including that `CancellationException` is never mapped.

## 2. Transport: streaming download on a non-redirecting client

- [ ] 2.1 Add `WebDavClient.download(url, maxBytes, sink)`:
  - `downloadClient` derived from `noRedirectClient` (no `callTimeout`, default read timeout)
  - same-origin check → `UnsafeHrefException` without a request
  - `GET` with `Accept-Encoding: identity` through `executeCancellable`
  - non-2xx → `WebDavHttpException(code)`
  - `Content-Length > maxBytes` → `DownloadTooLargeException` before the sink runs
  - non-image, non-octet-stream `Content-Type` → `UnexpectedContentException`
  - body passed as `LimitedInputStream`
  - add `MAX_DOWNLOAD_BYTES` = 200 MiB

  Verify that it compiles and that the existing `WebDavClient*` tests pass.
- [ ] 2.2 Add `WebDavClientDownloadTest` (MockWebServer). It covers:
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
- [ ] 2.3 Add a `NoBufferedDownloadRegressionTest` (source scan via `krhnles.moduleDir`). It checks that the download path files contain no `body.bytes()`, `body.string()`, `readBytes()` or `followRedirects(true)` / `followSslRedirects(true)`. Verify that it passes, and that it fails on a fixture string built by concatenation.
- [ ] 2.4 Update the CLAUDE.md Security Conventions with a "Photo downloads" bullet: `resolveDownloadTarget`, the non-redirecting `downloadClient`, no `callTimeout`, `executeCancellable`, streaming with a 200 MiB cap, the `Content-Type` veto, and use of the pinned-trust client from `pin-self-signed-certificate` through `WebDavClient`. Verify that the bullet names every guard test from 2.2 and 2.3.

## 3. MediaStore output: pending items owned by the app

- [ ] 3.1 Extend the test `fakes/FakeMediaStoreProvider` for `insert` into `images/media`:
  - records `RELATIVE_PATH`, `DISPLAY_NAME`, `MIME_TYPE`, `IS_PENDING` and `OWNER_PACKAGE_NAME`
  - uniquifies taken names as ` (1)`, ` (2)`
  - `openFile(uri, "w")` backed by a temp file
  - `update` of `IS_PENDING` and `delete`
  - an injectable insert / write failure, and ENOSPC

  Existing query behaviour must be unchanged. Verify that all existing grid, removal and worker tests using the fake still pass.
- [ ] 3.2 Add `data/LocalMediaWriter.kt`:
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
- [ ] 3.3 Add `DownloadOwnItemsOnlyRegressionTest` (source scan). It checks:
  - in the download path files, `delete(` appears only inside `LocalMediaWriter.discard` / `discardLeftover`
  - no `createDeleteRequest` and no `MANAGE_MEDIA` / `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` in the manifest

  Verify that it passes and that `NoHardDeleteLocalMediaRegressionTest` stays unchanged and green.
- [ ] 3.4 Document `LocalMediaWriter` in the CLAUDE.md coding standards (a new rule 11 "Saving album photos": pending insert, `Pictures/KroehnSync/<album>/`, discard-only deletes, no permission) and in the docs/architecture.md package tree. Verify that both name the guard tests from 3.2 and 3.3.

## 4. Download queue file

- [ ] 4.1 Add `worker/DownloadQueue.kt`:
  - `downloadQueueFilesDir` = `noBackupFilesDir`
  - `isDownloadQueueFile(context, file)`: regex `download_queue_[0-9]+_[0-9a-f-]+\.json` and canonical parent, no legacy dir
  - strict parse with `opt` + `is String` of `serverUrl`, `albumHref`, `albumFolder`, `entries[].href`, optional `downloaded`, optional `pendingItem` and `interruptions` (clamped to `0..MAX_DOWNLOAD_RUN_ATTEMPTS`)
  - serialization for `rewriteQueueFile()`
  - no credential fields

  Verify with `DownloadQueueTest`: round trip, a non-string or missing field → malformed, a subdirectory / traversal / upload-queue name rejected, interruptions clamping (negative, Double, huge), and no key containing "user" / "password" / "token" in the written JSON.

## 5. Download worker and notifications

- [ ] 5.1 Add the strings and plurals to `values/strings.xml`:
  - `download_saving` (plural), `download_saved_count` / `download_failed_count` (plurals)
  - `download_complete` / `download_partial` / `download_failed` / `download_cancelled`
  - one `download_failure_*` per `DownloadFailure`
  - `download_not_configured`, `download_enqueue_failed`
  - `channel_name_downloads` / `channel_desc_downloads`
  - `cd_save_to_device`, `action_cancel_download`

  Also add the `ic_stat_download` monochrome vector drawable, and a `downloadFailureMessage(resources, failures)` helper using `joinNotificationTexts()`. Verify with a Robolectric test that every `DownloadFailure` resolves to a non-blank string and that the plurals format for 1 and 3.
- [ ] 5.2 Add `worker/DownloadWorker.kt`:
  - a public `(context, params)` constructor, and an internal one with `CredentialRepository`, client factory, writer factory, free-space provider and clock
  - validated queue file, else success with no request
  - discard a leftover `pendingItem`
  - per entry: bounded config re-check (URL equal and valid), `resolveDownloadTarget`, type / name, free-space check, `setForegroundOrFalse`, `download` into a pending item, `publish`, `downloaded` marker via `rewriteQueueFile()`
  - stop rules: AccessDenied / Certificate / StorageFull / ConfigurationChanged, and 5 consecutive Connection / ServerError failures
  - interruptions cap and backstop
  - user cancel vs. system stop via `stopReason`
  - `Result.success()` for every finished batch, `Result.retry()` only when interrupted

  Verify that it compiles and that 5.3–5.5 pass.
- [ ] 5.3 Add the notifications:
  - `download_channel` (IMPORTANCE_LOW)
  - foreground / progress id 1003 with determinate progress and a Cancel action (`createCancelPendingIntent`)
  - result id 1004 tagged with the queue file name, gated on `POST_NOTIFICATIONS`
  - `ForegroundInfo` type `dataSync`
  - small icon `ic_stat_download`

  Verify with `DownloadWorkerNotificationTest`: ids and tag, channel, small icon, text for complete / partial / failed / cancelled, and that an upload result (1002) and a download result coexist.
- [ ] 5.4 Add `DownloadWorkerRunTest`: the real worker via `TestListenableWorkerBuilder`, MockWebServer, `FakeCredentialRepository`, extended `FakeMediaStoreProvider`, `doWork()` called directly. It covers:
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

  Verify that the test passes.
- [ ] 5.5 Add `DownloadWorkerCancelTest`:
  - **user cancel mid-body** (stalled MockWebServer body, `stopReason` cancelled by app): pending item discarded, earlier items kept, queue deleted, cancelled notification with counts, returns within a hard limit
  - **system stop:** queue kept with markers, and the re-run downloads only the remaining entries
  - **leftover `pendingItem` from a killed run:** discarded on resume
  - **refused foreground:** `Result.retry()` and `interruptions` incremented; gives up at the cap and at the backstop with the `Interrupted` reason

  Verify that the test passes.
- [ ] 5.6 Add a "Saving album photos" section to docs/architecture.md and add the worker to the package tree. Cover the queue format, chain semantics (APPEND_OR_REPLACE, never `failure()`, cancel cascade), notification ids 1003 / 1004, stop rules, user cancel vs. system stop, and interruptions. Verify that the section references every test class from 5.1–5.5.

## 6. Enqueuing

- [ ] 6.1 Add `download/DownloadEnqueuer.kt`, implementing `DownloadRequester`. It is owned by `KrhnlesApplication.downloadEnqueuer` and runs in `appScope`. It:
  - reads the config once (`withTimeoutOrNull(3 s)`); if not valid → `download_not_configured` toast and no file
  - under a `Mutex` with a timeout, sweeps orphaned queue files: `getWorkInfosByTag("download_queue:<name>")` bounded to 5 s, fail-closed
  - writes the queue file
  - awaits `enqueueUniqueWork("photo_download", APPEND_OR_REPLACE, downloadWorkRequest(file))` (CONNECTED only, exponential back-off, tags)
  - shows the plural toast on Main; on failure deletes the file and shows `download_enqueue_failed`
  - lets no exception escape the scope except cancellation

  Verify that it compiles.
- [ ] 6.2 Add `DownloadEnqueuerTest` (injected scope, dispatchers, `WorkManagerTestInitHelper`). It covers:
  - the file is in `noBackupFilesDir` with a valid name and no credentials
  - the input `Data` has only the queue path
  - unique work name, policy and tags
  - no Wi-Fi-only constraint even when the setting is on
  - not configured → no file, no work
  - an enqueue failure deletes the file
  - the sweep deletes a file whose work was cancelled, keeps one with enqueued / running work, and deletes nothing on timeout

  Verify that the test passes.

## 7. Entry points in the album flow

- [ ] 7.1 Add `AlbumDetailViewModel.saveToDevice(photos)`:
  - keeps only hrefs in the current `detailState.photos` of `currentHref`
  - is a no-op while not loaded, or on `NotConfigured` / `ConfigurationChanged`
  - calls the injected `DownloadRequester` (a `@JvmOverloads` default from `KrhnlesApplication`)

  Verify with `AlbumDetailViewModelSaveTest`: stale photos dropped, nothing during a config change, album href and name passed. Also verify that `ViewModelFactoryRegressionTest` still creates the ViewModel with the default factory.
- [ ] 7.2 Add a Save action to `AlbumDetailScreen` `SelectionTopBar` (`Icons.Default.Download`, `cd_save_to_device`, disabled while `isDeletingPhotos`). It calls `saveToDevice` with the selected photos and clears the selection. Verify with a Robolectric Compose test using a fake requester: the action exists with the localized label, it is disabled while deleting, it passes exactly the selected photos, and the selection is cleared.
- [ ] 7.3 Add a top-end `IconButton` to the `FullscreenViewerScreen` overlay. It saves the current page's photo and keeps a `rememberSaveable` set of hrefs saved in this viewer session, which disables the button for them. Verify with a Robolectric Compose test (`LocalAsyncImagePreviewHandler` + `LocalInspectionMode`):
  - the button has `cd_save_to_device` and no file-name description
  - a tap requests exactly the current page's photo
  - a second tap on the same photo is ignored
  - after swiping, the next photo can be saved

  Also verify that `NoFilenameContentDescriptionRegressionTest` passes.
- [ ] 7.4 Update user-facing docs:
  - README.md "Features" / "Usage": Save to device from the viewer and selection mode, saved to `Pictures/KroehnSync/<album>`, images only, background with Cancel
  - docs/requirements.md: new rows A-006 (save selected album photos to the device), V-010 (save the current photo from the viewer) and U-/N- rows for the background download, caps and no-permission constraint

  Verify that the statuses read "Implemented" only after 7.1–7.3 are green.

## 8. Guarantees toward uploads and removal

- [ ] 8.1 Extend `RemovalCandidateCheckerTest`. An item saved by the download writer, with the same display name and size as a verified, recorded photo, is not a candidate, and the recorded original still is. A download-only device yields `NothingToRemove`. Verify that the test passes.
- [ ] 8.2 Add an assertion to `DownloadWorkerRunTest` (or a dedicated `DownloadDoesNotMarkUploadedTest`) that after a successful batch the `UploadedPhotosStore` set and the `VerifiedUploadsStore` records are unchanged. Also verify that the `DownloadWorker` constructor does not accept either store, by a compile-level check or a reflection test.
- [ ] 8.3 Document in CLAUDE.md rule 11 and docs/architecture.md that downloads never touch uploaded markers or verified records, and why. Verify that the wording matches the `uploaded-photo-markers` / `remove-uploaded-photos` deltas.

## 9. Integration checks

- [ ] 9.1 Run `./gradlew test` and verify that all tests pass.
- [ ] 9.2 Run `./gradlew spotlessCheck`, `./gradlew detekt` (no new issues, or ask the user) and `./gradlew lintDebug` (zero issues, no suppressions), and verify that each passes.
- [ ] 9.3 Run `./gradlew jacocoTestReport` and verify that the new `download/`, `worker/Download*` and `data/LocalMediaWriter` classes appear with coverage in the HTML report.
- [ ] 9.4 Manual device check (API 35+):
  - save from the viewer and from selection mode
  - the files appear in the gallery under `Pictures/KroehnSync/<album>/`
  - Cancel mid-transfer leaves no partial file
  - airplane mode mid-batch resumes later
  - concurrent upload and download notifications do not replace each other

  Record the result in the PR.

## Workflow follow-up

- Coordinate with `pin-self-signed-certificate`: whichever lands second makes sure `WebDavClient.downloadClient` is derived from the pinned-trust client.
- Coordinate with `show-videos-in-albums`: a follow-up extends `DownloadImageType` with video types targeting `MediaStore.Video`.
- `german-translation` (last) translates the new `download_*` strings and plurals. The `KroehnSync` folder constant stays untranslated.
- Archive the change after review, and verify the archived `album-photo-download` spec.
