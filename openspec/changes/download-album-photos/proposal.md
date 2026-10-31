# Proposal

## Why

Feature request: *"Remote albums can be browsed, renamed, reviewed and deleted from, but a photo can never be saved back to the device. Add 'Save to device' in the fullscreen viewer and in album selection mode."*

Today the only way back from the server to the phone is a browser or another app. Users who lost a local copy, switched phones or want to share one album photo through a messenger have to leave the app. The album flow already has the listing, the hardened href handling and the background work patterns to do this safely.

## What Changes

- **Save to device in the viewer**: the fullscreen viewer's overlay shows a "Save to device" button for the current photo.
- **Save to device in selection mode**: the album detail selection top bar shows a "Save to device" action next to Delete. It saves every selected photo and then clears the selection.
- **Background download**: saving runs in WorkManager, so it continues when the app is closed. It runs as a foreground `dataSync` job with a progress notification and a Cancel action. Batches run one after another. The worker input holds only a validated queue-file path (`download_queue_<millis>_<uuid>.json` in `noBackupFilesDir`), never credentials. After a system stop or a refused foreground service, the work resumes without downloading finished files again. Refusals are capped like uploads.
- **Hardened transfer**: before any request, each photo href is resolved with `resolveWebDavHref` strictly below its album, and the album strictly below the configured base folder. An unsafe href fails with `UnsafeHrefException` and sends nothing. The request uses the origin-restricted `WebDavClient` (and so the pinned-trust client from `pin-self-signed-certificate`). It never follows redirects, runs through `executeCancellable` and streams the body (never `body.bytes()`). The size cap is 200 MiB per file, checked against `Content-Length` and while streaming. A non-image `Content-Type` such as an HTML login page is rejected.
- **MediaStore output, no storage permission**: files are inserted into `MediaStore.Images` with `IS_PENDING = 1` and published only once complete. They go to `Pictures/KroehnSync/<sanitized album>/`. The display name is sanitized from the href's last segment. The MIME type comes from an extension allow-list of raster images (the share target's types). A failed or cancelled item is deleted, so no partial file is left. Nothing is overwritten, because MediaStore makes names unique. No permission is added (minSdk 35).
- **Images only for now**: videos, SVG, TIFF and other types are skipped as "unsupported type". Video download is a follow-up once `show-videos-in-albums` lists videos.
- **Result notification**: its own channel, ids (foreground 1003, result 1004 tagged with the queue file name) and small icon. It shows plural counts of saved and failed photos, plus each distinct failure reason resolved from a typed `DownloadFailure` to a string resource. Raw exception text never appears.
- **Not marked as uploaded**: saved photos are new local items. They are not added to the uploaded-photo markers or the verified-upload records. So they never show the cloud badge and are never candidates for "Remove uploaded photos from device".
- **Configuration changes stop downloads**: if the server URL changes or the user logs out while a batch waits or runs, the rest of the batch is not requested.
- **Documentation**: README.md, CLAUDE.md, docs/architecture.md and docs/requirements.md are updated.

## Capabilities

### New Capabilities
- `album-photo-download`: saving remote album photos to the device: entry points, background execution, transfer hardening, MediaStore output, progress / result notifications, cancellation, resumption and typed failures.

### Modified Capabilities
- `uploaded-photo-markers`: adds that photos saved from an album are never marked as uploaded.
- `remove-uploaded-photos`: adds that photos saved from an album are never removal candidates.
- `webdav-href-validation`: adds that photo downloads are requested only for hrefs strictly below their album, which is strictly below the base folder, and fail without a request otherwise.

## Impact

- **New code**:
  - `worker/DownloadWorker.kt`, `worker/DownloadQueue.kt` (queue file format, name validation next to `QueueFiles.kt`)
  - `download/DownloadEnqueuer.kt` (process-scoped, like `UploadEnqueuer`), `download/DownloadNames.kt` (album folder / file name sanitization, `DownloadImageType`), `download/DownloadTarget.kt` (`resolveDownloadTarget()`)
  - `data/LocalMediaWriter.kt` (pending insert / publish / discard of the app's own MediaStore items), `data/DownloadFailure.kt`
- **Changed code**:
  - `WebDavClient` (new streaming `download()` on a non-redirecting client)
  - `AlbumsRepository` (shares a `baseFolderUrlOf(config)` helper)
  - `AlbumDetailViewModel` (`saveToDevice()`, injectable requester), `AlbumDetailScreen` (selection action), `FullscreenViewerScreen` (overlay button)
  - `KrhnlesApplication` (`downloadEnqueuer`), `strings.xml` (strings and plurals), a new notification drawable
- **Tests**: new worker / client / enqueuer / naming / href tests with MockWebServer and an extended `FakeMediaStoreProvider` (insert, write, update, delete, name uniquifying). Regression additions in `RemovalCandidateCheckerTest` and `ViewModelFactoryRegressionTest`.
- **No new permissions, dependencies or DataStores.** The queue files live in `noBackupFilesDir`, so they are excluded from backup automatically.
- **Sibling changes**:
  - `pin-self-signed-certificate`: downloads must use its client factory, because they go through `WebDavClient`.
  - `show-videos-in-albums`: until a follow-up extends the allow-list, videos are skipped as unsupported.
  - `cancel-running-upload`: separate work and notifications, no shared cancel path.
  - `nextcloud-chunked-upload`: unaffected.
  - `german-translation`: lands last and must translate the new strings.
  - `album-search-and-sort`: selection semantics unchanged; saving uses the selected hrefs whatever the sort.
  - `new-photos-reminder`: saved photos are new, not-uploaded local items. That change should not remind about them; they can be recognized by `OWNER_PACKAGE_NAME` = this app or the `Pictures/KroehnSync/` path.
  - `occasion-folder-suggestions`, `readme-roadmap-cleanup`: no interaction, apart from README edits that may need merging.
