# Design

## Context

See proposal.md (Why) for the motivation and specs/ for the behaviour. The design reuses these existing building blocks:

- **Album flow.** `AlbumDetailViewModel` owns the loaded listing (`detailState.photos`, `RemotePhoto.href`) and an `AlbumsRepositoryProvider`. The viewer (`albums/viewer`) shares the detail entry's ViewModel, and selection mode lives in `AlbumDetailScreen` (`selectedHrefs`).
- **Href hardening.** `resolveWebDavHref(root, href)` and `UnsafeHrefException` (`data/WebDavHref.kt`). `AlbumsRepository.baseFolderUrl` = config URL + normalized base folder.
- **Transport.** `WebDavClient` adds the origin-restricted `Authorization` header, uses `noRedirectClient` / `uploadClient` without a `callTimeout`, and runs calls through `executeCancellable()` (`data/CancellableCall.kt`). `LimitedInputStream` provides byte caps. The `pin-self-signed-certificate` sibling change will give `WebDavClient` a pinned-trust client factory.
- **Upload worker.** It provides several patterns to reuse:
  - validated queue files in `noBackupFilesDir` (`worker/QueueFiles.kt`) and atomic rewrites (`rewriteQueueFile()`)
  - per-entry done markers, and `interruptions` capped at `MAX_UPLOAD_RUN_ATTEMPTS` with a `runAttemptCount` backstop
  - `setForegroundOrFalse()`, and result notifications tagged with the queue file name
  - `joinNotificationTexts()`, and the process-scoped `UploadEnqueuer` in `KrhnlesApplication.appScope`
- **Removal path.** `VerifiedUploadsStore` records are written only by the upload worker. `DefaultRemovalCandidateChecker` needs a record plus an equal `SIZE` / `DATE_MODIFIED`.
- **Constraints.**
  - minSdk 35: an app may insert into `MediaStore.Images` and write, update and delete its *own* items without any storage permission.
  - The share target's raster allow-list `SharedImageType` exists in `util/ShareIntentParser.kt`.

## Goals / Non-Goals

**Goals:**
- One download path for both entry points: queue file → `DownloadWorker` → `WebDavClient.download()` → `LocalMediaWriter`.
- Every server-controlled input is re-validated in the worker at run time: hrefs, names and `Content-Type`.
- Nothing partial is ever visible, nothing is overwritten, and no permission is added.

**Non-Goals:**
- Videos, TIFF and SVG. They are skipped as unsupported until `show-videos-in-albums` and a follow-up extend `DownloadImageType` and add `MediaStore.Video` as a target collection.
- A "Retry failed" action, upload-history entries for downloads, and saving whole albums from the album list.
- Preserving the server's modification time. `DATE_TAKEN` comes from the MediaProvider's EXIF scan after publishing.
- Excluding saved photos from the local grid or from the "new photos" view. They are new local items, see Decision 9.
- Parallel transfers within a batch.

## Decisions

### 1. A queue file plus a serialized unique work chain

`DownloadEnqueuer` (`download/`, owned by `KrhnlesApplication`, application scope like `UploadEnqueuer`):

1. Reads the config once, under `withTimeoutOrNull(3 s)`. An invalid config shows the `download_not_configured` toast.
2. Under a `Mutex` acquired with a timeout, sweeps orphaned queue files (see below).
3. Writes `noBackupFilesDir/download_queue_<millis>_<uuid>.json`:
   ```json
   {"serverUrl": "...", "albumHref": "...", "albumFolder": "...", "entries": [{"href": "..."}], "interruptions": 0}
   ```
4. Awaits `enqueueUniqueWork("photo_download", APPEND_OR_REPLACE, request)`. The request is tagged `photo_download` and `download_queue:<name>`, has a `CONNECTED` constraint and an explicit exponential back-off, and is built only by `downloadWorkRequest()`. It deliberately ignores the Wi-Fi-only upload setting: every download is an explicit user action, and that setting is named and documented for uploads.
5. Shows the plural "Saving N photos" toast on Main. On failure it deletes the file and shows `download_enqueue_failed`.

The work input `Data` carries only `KEY_QUEUE_FILE`. The queue holds no credentials and no username. The server URL is non-sensitive (CLAUDE.md) and is needed for Decision 7. `WorkManager` `Data` is limited to 10 KB, while a selection can hold thousands of hrefs, so the hrefs go into the file. `noBackupFilesDir` is excluded from backup and device transfer automatically. The hrefs contain user folder names.

`worker/DownloadQueue.kt` provides:
- `isDownloadQueueFile(context, file)`: the name regex `download_queue_[0-9]+_[0-9a-f-]+\.json`, and a canonical parent equal to `noBackupFilesDir`. There is no legacy `filesDir`, because the format is new.
- A strict parser using `opt` + `is String`. A malformed file is deleted and the work ends.
- A writer that updates progress through `rewriteQueueFile()`.

**Why a serialized chain (APPEND_OR_REPLACE):**
- One foreground notification id (1004) suffices, because two download workers never run at once.
- Batches finish in start order.
- The device is not flooded with parallel transfers.

**Consequences, handled explicitly:**
- **No cascade on failure.** A failed predecessor would fail its dependents. The worker therefore returns `Result.success()` for every finished batch, including all-failed and malformed-queue batches. It returns `Result.retry()` only for interruptions and never returns `Result.failure()`.
- **Cancel cascades.** Cancelling one work cancels the batches queued after it. The spec states this ("stops the running batch and the batches queued after it"). Their queue files are never opened by a worker. `DownloadEnqueuer` therefore sweeps download queue files whose `download_queue:<name>` work is not unfinished, using `getWorkInfosByTag` bounded to 5 s. The sweep fails closed: on a timeout or error it deletes nothing. It runs before writing a new file, under the same mutex, so it cannot race the enqueue.

**Alternatives considered:**
- *Independent parallel works.* They need one foreground id per work, and WorkManager has no notification tag for `ForegroundInfo`, so ids would collide.
- *One long-lived worker draining a directory.* Starting it races against a worker that is about to finish (`KEEP`).
- *Cooperative per-batch cancel through a custom receiver.* It needs a process-local cancel signal plus a persisted marker, which is more machinery than the single limitation it removes.
- *Passing hrefs in `Data`.* Not possible because of the 10 KB limit.

### 2. Entry points, and filtering against the loaded listing

`AlbumDetailViewModel.saveToDevice(photos: List<RemotePhoto>)`:
- Keeps only photos whose href is in the current `detailState.photos`, with `currentHref` as the album (the same rule as `launchPhotoDeletion`).
- Ignores calls while the album is not loaded or has `NotConfigured` / `ConfigurationChanged`.
- Hands `(albumHref, albumName, hrefs)` to an injected `DownloadRequester`. The interface is implemented by `DownloadEnqueuer`. The ViewModel gets it from a `@JvmOverloads` default via `(application as KrhnlesApplication).downloadEnqueuer`, so `ViewModelFactoryRegressionTest` keeps passing.

ViewModels never reference each other. The viewer calls the shared detail ViewModel it already holds.

UI:
- **`AlbumDetailScreen`.** `SelectionTopBar` gets a `Save` action (`Icons.Default.Download`, `cd_save_to_device`), disabled while `isDeletingPhotos`. Selection is cleared after the call.
- **`FullscreenViewerScreen`.** The overlay gets a top-end `IconButton`. A `rememberSaveable` set of hrefs saved in this viewer session disables the button for those photos, which prevents accidental double taps. Saving again later is allowed and yields a uniquified copy.
- Toasts come from the enqueuer, as for uploads, so a viewer that is left at once still gets feedback.

### 3. Transfer: `WebDavClient.download()`

```kotlin
suspend fun download(url: HttpUrl, maxBytes: Long, sink: (DownloadBody) -> Unit): Result<Unit>
```

- Runs on `downloadClient` = `noRedirectClient` with the default 60 s read timeout and no `callTimeout`. It inherits the origin-restricted interceptor and, once `pin-self-signed-certificate` lands, the pinned trust, because it is derived from the same base client.
- Requires `url.hasSameOriginAs(config.url)` as defence in depth, else `UnsafeHrefException` without a request.
- Sends `GET` with `Accept-Encoding: identity`, so the stored bytes arrive and `Content-Length` is the file size.
- Runs through `executeCancellable`: Cancel or a WorkManager stop aborts a blocked read at once.
- Handles the response as follows:
  - non-2xx → `WebDavHttpException(code)`, and 3xx included, because redirects are never followed
  - `Content-Length > maxBytes` → `DownloadTooLargeException` before the sink is called
  - a `Content-Type` that is present, not `image/*` and not `application/octet-stream` → `UnexpectedContentException`
  - otherwise it calls `sink` with `contentLength` and `LimitedInputStream(body.byteStream(), maxBytes, ::DownloadTooLargeException)`
- Never calls `body.bytes()` or `string()`.

`MAX_DOWNLOAD_BYTES` = 200 MiB. Phone JPEGs and HEICs are far below that, and DNGs from current phones are 20–80 MB. The share target's 100 MiB cap protects the staging folder, not the gallery, so a higher bound is acceptable here.

Href resolution is a pure function in `download/DownloadTarget.kt`:
```kotlin
resolveDownloadTarget(config, albumHref, photoHref): HttpUrl?
```
- `album = resolveWebDavHref(baseFolderUrlOf(config), albumHref)`, then `resolveWebDavHref(album-as-collection, photoHref)`. The result must not end in `/`.
- `baseFolderUrlOf()` is extracted from `AlbumsRepository` so both use one definition.
- It is called by the worker for every entry at run time, against the current config. A `null` result → `DownloadFailure.Rejected`, with no request and no logged href.

**Alternatives considered:**
- *Reuse `AlbumsRepository` and its OkHttp client.* That client follows redirects, and the repository is scoped to a ViewModel, not the worker.
- *`downloadSha256` on `uploadClient`.* Its 5-minute read timeout is meant for servers that finalise an upload.

### 4. Output: `LocalMediaWriter` (data/), only the app's own pending items

```kotlin
insertPending(relativePath, displayName, mimeType): PendingMediaItem   // IS_PENDING = 1
PendingMediaItem.write { out -> ... }                                   // openOutputStream(uri, "w")
PendingMediaItem.publish()                                              // update IS_PENDING = 0
PendingMediaItem.discard()                                              // contentResolver.delete(uri)
```

- **Inserting.**
  - The target is `MediaStore.Images.Media.getContentUri(VOLUME_EXTERNAL_PRIMARY)`.
  - `RELATIVE_PATH` = `Pictures/KroehnSync/<albumFolder>/`. `KroehnSync` is a non-localized constant, so it is unaffected by `german-translation`.
  - `DISPLAY_NAME` is sanitized and `MIME_TYPE` comes from the allow-list.
  - `insert` never overwrites: MediaProvider appends ` (1)`, ` (2)`, … to a taken name.
- **Pending item record.** Before writing, the worker records the item uri in the queue file (`"pendingItem"`, via `rewriteQueueFile()`) and clears it after `publish()` / `discard()`.
- **Discarding.** `discard()` runs in `withContext(NonCancellable)` from a `finally`, on every failure and cancellation.
- **Resumed run.** A resumed run discards a leftover `pendingItem` first, but only if three checks pass:
  1. it passes a `validatedMediaUri()`-style check: authority `media`, `<volume>/images/media/<id>`
  2. it is still `IS_PENDING = 1`
  3. `OWNER_PACKAGE_NAME` = our package
  
  The MediaProvider also expires abandoned pending items after about 7 days.
- **Scope of deletion.** `discard()` is the only `delete` in the download path, and it only takes uris this app inserted. It is not on the removal path that `NoHardDeleteLocalMediaRegressionTest` guards, and that test stays unchanged. A new `DownloadOwnItemsOnlyRegressionTest` pins that `LocalMediaWriter` is the only download-path file calling `delete(`, and only from `discard()`. It also pins that `createDeleteRequest`, `MANAGE_MEDIA` and storage permissions stay absent.
- **Free space.** Before writing, an announced length larger than the primary volume's free space minus a 200 MiB margin → `StorageFull`. Free space is `StorageManager.getAllocatableBytes` for the volume uuid, falling back to `Environment.getExternalStorageDirectory().usableSpace`. An `IOException` whose cause is `ErrnoException(ENOSPC)` while writing → `StorageFull` as well.

**Naming** (`download/DownloadNames.kt`, pure):
- **`DownloadImageType`.** The extension map is built from `SharedImageType`: jpg/jpeg → JPEG, png, gif, webp, heic → HEIC, heif → HEIF, avif, bmp, dng. The extension decides the type; the response `Content-Type` only vetoes (Decision 3).
- **`downloadDisplayName(href)`.**
  - Takes the decoded last segment (`HttpUrl.pathSegments.last()`).
  - Replaces `/ \ : * ? " < > |`, ISO control characters and Unicode Cf / bidi / line / paragraph separators and unpaired surrogates with `_`, and trims spaces and dots.
  - Truncates the stem so that the whole name is ≤ 255 UTF-8 bytes with the extension kept.
  - A blank stem gets `photo_<millis>_<8 hex>.<ext>`.
- **`albumFolderName(albumHref)`.** Sanitizes the same way to one segment of at most 100 UTF-8 bytes. `.`, `..` or blank → `Album`.
- The display name from PROPFIND is not used for paths.

**Alternatives considered:**
- *`DownloadManager`.* It cannot add our origin-checked auth, follows redirects, cannot apply our caps or discard on cancel, and its result is outside our control.
- *Writing to app-private storage, then copying.* That doubles I/O and space.
- *SAF folder picker.* It needs extra UI and a persisted grant for each save, while MediaStore needs no permission.

### 5. Worker loop, failures and stop rules

`DownloadWorker(context, params)` has a public constructor for WorkManager and an internal test constructor taking `CredentialRepository`, `(WebDavConfig) -> WebDavClient`, a `LocalMediaWriter` factory, a free-space provider and a clock. For each pending entry, in order:

1. Re-check the config. This is a bounded read (3 s) with `isValid` and `url == queue.serverUrl`; otherwise the rest fails with `ConfigurationChanged`.
2. Resolve the target (Decision 3), then the type and name (Decision 4).
3. `setForegroundOrFalse(progress)`.
4. Download into a pending item. Then publish, and mark the entry `"downloaded": true` via `rewriteQueueFile()`. A failed marker write is logged; a re-run may save that photo again as a uniquified copy (accepted).

`Throwable.toDownloadFailure()` (`data/DownloadFailure.kt`) branches on types and codes, never on messages:

| Cause | `DownloadFailure` |
|---|---|
| `UnsafeHrefException`, 3xx, `UnexpectedContentException` | `Rejected` |
| 404 / 410 | `NotFound` |
| 401 / 403 | `AccessDenied` |
| SSL / `GeneralSecurityException` in the cause chain | `Certificate` |
| `UnknownHost` / `Connect` / `InterruptedIOException` / other `IOException` | `Connection` |
| other HTTP | `ServerError` |
| `DownloadTooLargeException` | `TooLarge` |
| ENOSPC | `StorageFull` |
| MediaStore insert / write / update failure | `WriteFailed` |

`Unsupported`, `ConfigurationChanged` and `Interrupted` are set directly. `CancellationException` is always rethrown.

Stop rules (they keep a bad batch from hammering the server):
- `AccessDenied`, `Certificate`, `StorageFull` and `ConfigurationChanged` fail the remaining entries with the same reason.
- `MAX_DOWNLOAD_CONSECUTIVE_SERVER_FAILURES` (5) `Connection` / `ServerError` failures in a row do the same; any success or other failure resets the count.
- A lost network is a WorkManager constraint stop (Decision 6), not a failure.

Result notification:
- Id `DOWNLOAD_RESULT_NOTIFICATION_ID` (1005), tag = queue file name.
- Title: `download_complete` / `download_partial` / `download_failed` / `download_cancelled`.
- Text: `R.plurals.download_saved_count` / `download_failed_count`, plus one `download_failure_*` sentence per distinct reason, joined via `joinNotificationTexts()`.
- Shown through a `notifyIfPermitted`-style check of `POST_NOTIFICATIONS`.
- The counts cover the whole batch, including entries saved by earlier runs.

Notification channel and glyph:
- Progress and foreground use id 1004 in channel `download_channel` (IMPORTANCE_LOW, `channel_name_downloads`), with small icon `ic_stat_download`.
- The Cancel action is `WorkManager.createCancelPendingIntent(id)`.
- 1001 / 1002 stay with uploads, so an upload and a download never replace each other's notifications.

### 6. Cancellation vs. system stop, interruptions

The worker body runs inside `try/finally`. On `CancellationException`, the current pending item is discarded (`NonCancellable`). Then:

- **User cancel.** `isStopped && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP` is our Cancel action, or a cascade from it. The queue file is deleted and a `download_cancelled` result with the counts is posted.
- **System stop.** For any other stop reason (constraint lost, system) the queue file, with its done markers, is kept. WorkManager re-runs the work.

A refused foreground service (`setForegroundOrFalse` returns false) increments the top-level `interruptions`, which is clamped to `0..MAX_DOWNLOAD_RUN_ATTEMPTS` (5) on read, via `rewriteQueueFile()`, and the worker returns `Result.retry()`. The worker gives up in these cases:
- at `interruptions + 1 >= 5`
- when the counter cannot be persisted and `runAttemptCount >= 5`
- always, at the backstop `runAttemptCount >= 50`

Giving up ends with an `Interrupted` reason for the rest. This mirrors the upload worker, but as its own constants, so the two can evolve separately; `cancel-running-upload` / `nextcloud-chunked-upload` may touch the upload ones.

### 7. Configuration binding without credentials

The queue stores the server URL only. Before each entry the worker requires a valid config with the same URL, and every href is re-resolved against the current base folder. So:
- after a logout, nothing is requested
- after a server change, the URL mismatch stops the batch
- after a base-folder change, the hrefs are no longer below the base and are rejected

A different account on the same server with Nextcloud paths (`/files/<user>/`) also fails the href check. On a generic WebDAV server with user-independent paths, the new account's credentials authorize the request; this is acceptable, because the request uses the current user's own credentials.

### 8. Not marked as uploaded, never a removal candidate

The download worker does not depend on `UploadedPhotosStore` or `VerifiedUploadsStore`; its constructor does not even take them.

Reasons not to mark saved photos as uploaded:
- **The marker's meaning.** It means "uploaded from this device" and is never re-validated against the server. Marking a download would claim a backup that the server can lose (deleted or renamed album) without the device noticing.
- **The filter.** "Show only new photos" would hide a photo the user just asked to have on the device.
- **The verified records.** They feed the removal action. A download must never make the only local copy of a photo removable. Removal requires a record with a matching MediaStore id, `SIZE` and `DATE_MODIFIED`, and saved items have fresh ids that no record holds.

`RemovalCandidateCheckerTest` gains a case where a saved item exists with the same name and size as a recorded photo, and only the recorded original is a candidate.

### 9. Saved photos in the local grid

`MediaRepository` lists all images, so saved photos appear in the grid like any gallery photo, without a badge. Re-uploading them would create a server duplicate; that is the user's explicit choice, as for any re-upload. Hiding them (by `OWNER_PACKAGE_NAME`) is deferred; see Open Questions and the `new-photos-reminder` interaction in the proposal.

## Risks / Trade-offs

- [Cancel also cancels queued batches (cascade)] → stated in the spec and the notification wording; orphaned queue files are swept fail-closed at the next enqueue.
- [Process death between `publish()` and the `downloaded` marker write] → that photo is saved again as a uniquified copy on resume. The window is tiny and the outcome is visible and harmless (no overwrite).
- [A server serving non-image bytes under an image extension with `application/octet-stream`] → the gallery shows an undecodable item. No content sniffing is done in v1; the `Content-Type` veto catches the common HTML case (captive portal, login page).
- [The `dataSync` foreground budget (Android 15: 6 h / 24 h) is shared with uploads] → refusals use the interruption cap, and progress is kept for later runs.
- [`getAllocatableBytes` may not reflect the shared volume on every device] → the ENOSPC mapping is the backstop; the margin keeps the device usable.
- [The extension decides the type, so a wrong server extension (PNG named `.jpg`)] → the MediaProvider stores the declared MIME; galleries decode by content. Accepted.
- [Unsupported types are only discovered by the worker] → they are reported in the result notification as "unsupported type", which keeps one source of truth; the UI does not pre-filter.

## Migration Plan

Additive only: new work tag, queue file pattern, channel, notification ids and strings. No stored data changes. Rollback: removing the feature leaves at most orphaned `download_queue_*.json` files in `noBackupFilesDir`, which are harmless, plus saved photos that the user keeps.

## Open Questions

- Folder name `Pictures/KroehnSync/`: confirm the brand spelling, for example `Pictures/Kroehnles Image Management/`. It is one constant and changes neither the spec structure nor the tasks.
- Should a later change hide app-saved photos from the local grid or from `new-photos-reminder` (by `OWNER_PACKAGE_NAME`)? This is out of scope here.
- Should the per-file cap become a setting, for example for RAW files above 200 MiB? It is a constant for now.
