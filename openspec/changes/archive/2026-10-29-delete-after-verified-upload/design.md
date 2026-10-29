# Design

## Context

See `proposal.md` for the motivation and `specs/upload-verification` / `specs/remove-uploaded-photos` for the behaviour.

Current state that shapes the approach:

- `UploadWorker.uploadFiles()` streams each pending entry with one `PUT` (`WebDavClient.uploadFile`, one-shot body, `uploadClient`: no redirects, no call timeout). A `2xx` is success: the queue file is rewritten atomically with `"uploaded": true` and the photo is marked in `UploadedPhotosStore`. The remote folder of entry `i` is `"${config.uploadPathPrefix}${queue.folderOf(i)}"`, the file name is `displayName`.
- `finish()` records history, writes a retry file with the failed entries and posts the completion notification; `uploadFailureMessage()` turns `UploadFailure`s into reason sentences.
- Nextcloud is detected only by URL (`buildNextcloudBase(config.url)`, `/remote.php/`), as for Memories.
- `minSdk` 35, `targetSdk` 37. Photos belong to other apps; trashing them needs `MediaStore.createTrashRequest(resolver, uris, true)`, a `PendingIntent` for a system Activity that must be started with `startIntentSenderForResult` from a visible Activity. One request shows one system confirmation for all its items; apps targeting API 36+ may pass at most 2000 uris per request.
- Nextcloud documents `X-Hash: md5|sha1|sha256|all` on `PUT`; the server hashes the content while writing it and answers with `X-Hash-SHA256`. `OC-Checksum` is stored unvalidated on a normal `PUT` and is useless as proof.

## Spike result (task 1, Pixel_Tablet AVD, API 35, scratch app targeting 35, not in the repo)

**GATE FAILED: a background worker cannot trash through `createTrashRequest`.** Resolved by the user (2026-10-28): removal becomes a manual menu action in the app, decided per photo, verified photos only, in-app confirmation plus the system dialog, no `MANAGE_MEDIA`, verification always on.

- Worker, app in background, `MANAGE_MEDIA` allowed (`appops`), `READ_MEDIA_IMAGES/VIDEO` granted, `canManageMedia() = true`: `createTrashRequest(...)` returns a PendingIntent; `PendingIntent.send()` plain and with `setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` both start MediaProvider's `PermissionActivity` (system log: `BAL_ALLOW_ALLOWLISTED_COMPONENT`, no "Background activity launch blocked" line, so BAL is not the blocker). But the activity finishes at once: `MediaProvider: NameNotFoundException: Missing calling package` in `PermissionActivity.resolveCallingAppInfo`. That package is only set by `startActivityForResult` / `startIntentSenderForResult`, which a worker cannot do. Re-query with `QUERY_ARG_MATCH_TRASHED`: `IS_TRASHED = 0` (both variants). `MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS` is API 36 and was not testable.
- Direct `ContentResolver.update(uri, IS_TRASHED=1)` from the worker with `MANAGE_MEDIA`: `RecoverableSecurityException` ("has no access"), nothing trashed. So there is no Activity-free path either.
- The same request from a foreground Activity via `startIntentSenderForResult` works with `MANAGE_MEDIA`: `RESULT_OK` in ~0.4 s, a translucent `PermissionActivity` flashes and closes, the item leaves the default MediaStore query (trashed).
- 1.2 answers: (a) `canManageMedia()` is true and the Activity trash runs with no dialog with only `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` + `MANAGE_MEDIA` (no `READ_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`): yes. (b) `ACCESS_MEDIA_LOCATION` needed to avoid the dialog: no (it was not granted in the successful run). (c) Partial access (`READ_MEDIA_VISUAL_USER_SELECTED` only): the trash is not silent; MediaProvider logs "No permission READ_MEDIA_IMAGES or MANAGE_EXTERNAL_STORAGE" and shows the "Allow ... to move this photo to trash?" dialog (Deny / Allow). (d) `lintDebug` on a manifest declaring `MANAGE_MEDIA`: no finding about the permission.
- Not covered: API 36 / 37 behaviour, a foreground-service worker (the missing calling package is independent of BAL, so it should not help), a real device with a different MediaProvider.
- Consequence for the options named in Risks: the removal must run inside a visible Activity (for example after upload completes while the app is visible, or from a notification action that opens a transparent Activity which calls `startIntentSenderForResult`); with `MANAGE_MEDIA` and full media access it is silent. Fully unattended removal is not possible. Decided: manual menu action (see D6).

## Goals / Non-Goals

**Goals:**
- One verification seam (`UploadVerifier`) with a Nextcloud and a download strategy; new servers or algorithms plug in without touching the worker.
- No extra read of the local file and, on Nextcloud with `X-Hash`, no extra request.
- A removal that trashes a photo only if it is verified, unchanged on the device and still on the server, confirmed by the user each time.
- Fail closed: every unknown, malformed, legacy or failed check leads to "keep the photo".

**Non-Goals:**
- No automatic or background removal (impossible, see *Spike result*), no `MANAGE_MEDIA`, no removal of photos uploaded before this change (they have no verification record), no per-photo selection for removal (the action covers all candidates).
- No chunked / resumable uploads, no other hash algorithms.
- No change to server-side album deletion (`album-photo-deletion`); naming in code and strings uses "remove" / "trash", never "delete photo", to keep the two apart.

## Decisions

### D1: Strategy interface `UploadVerifier`
```
interface UploadVerifier {
    val requestsServerHash: Boolean          // adds "X-Hash: sha256" to the PUT
    suspend fun verify(target: RemoteFile, local: LocalDigest, put: UploadResponse): Verification
}
sealed interface Verification { Verified; Mismatch; Inconclusive; Unavailable }
```
- `NextcloudHashVerifier(fallback: UploadVerifier?)`: compares `put.serverSha256` with `local.sha256` (case-insensitive). A missing or malformed header (not 64 hex chars) delegates to `fallback`, or returns `Unavailable` without one.
- `DownloadVerifier(client)`: downloads the file and compares SHA-256 + size.
- `verifierFor(config, downloadEnabled)` picks: Nextcloud URL → `NextcloudHashVerifier(fallback = DownloadVerifier.takeIf { downloadEnabled })`; else `DownloadVerifier` if enabled, else `NoVerifier` (`Unavailable`).

Verification runs for every non-staged upload (user decision: always). The download setting is read once per run (`first()` with a timeout, fallback **off**: no extra data use when unknown). The worker only sees the interface. *Alternative:* a single class with `if (nextcloud)` branches. Rejected because separate strategies were requested and the fallback chain is clearer as composition.

### D2: Local digest while streaming
`uploadPhoto()` wraps the source stream in a `DigestInputStream` (SHA-256) plus a byte counter before passing it to `uploadFile`. After the `PUT` the digest describes exactly the sent bytes. Only non-staged entries are wrapped, and only *after* `uploadLengthOf()` has computed the length: the staged length is read from the `FileInputStream` itself, which a wrapper would hide (→ chunked transfer). *Alternative:* hashing the file before the upload (a second read, plus a time-of-check gap). Rejected.

### D3: `WebDavClient` changes
- `uploadFile(..., requestServerHash: Boolean = false): Result<UploadResponse>` with `UploadResponse(serverSha256: String?)`; the header is read from the `PUT` response only and validated as `[0-9a-fA-F]{64}`. Existing callers keep working through the default.
- `downloadSha256(folderName, fileName, maxBytes): Result<RemoteDigest>`: a `GET` built with the same `buildPathUrl` segments and validation as the `PUT`, on `uploadClient` (no redirects, so any 3xx is a non-2xx failure), through `executeCancellable`, with the body read through `LimitedInputStream(maxBytes = local size)`. Exceeding the cap is `Mismatch` (not `Inconclusive`), because the server holds more bytes than were sent; a `Content-Length` above the local size is a `Mismatch` without reading the body. The over-cap case has its own exception type (passed as `LimitedInputStream`'s `tooLarge`) so it is never confused with a network `IOException` (→ `Inconclusive`). Like uploads, the download has no call timeout (large videos); "timeout" means `uploadClient`'s per-read timeout. It runs only after a `2xx` `PUT`.
- `remoteFileSize(folderName, fileName): Result<Long?>`: a `HEAD` with the same segment validation, `Accept-Encoding: identity` (so `Content-Length` is the stored size, not a compressed one), on `noRedirectClient` (3xx fails), cancellable (`executeCancellable`). `2xx` → `Content-Length` or `null` if absent; non-2xx → `WebDavHttpException(code)`.
- The auth interceptor's same-origin rule applies to all three.

### D4: Outcomes map onto the existing success / failure model
| Verification | Counted as | Marked uploaded | Verified record |
|---|---|---|---|
| `Verified` | uploaded | yes | written |
| `Unavailable` (no strategy, MediaStore `SIZE` / `DATE_MODIFIED` missing, or `SIZE` ≠ streamed bytes) | uploaded | yes | none |
| `Mismatch` / `Inconclusive` | failed (`UploadFailure.Verification`, own reason string) | no | none (an old record of the id is removed) |

The server copy of a failed verification stays and is overwritten by the retry's `PUT`. A size mismatch with MediaStore (file changed during the upload, or stale metadata) is `Unavailable`, not a failure: the server copy matches what was sent, but the local file is not provably the same; counting it as failed would fail such photos on every retry. *Alternative:* counting a mismatch as uploaded-but-unverified. Rejected: a corrupt server copy is a failed backup and the user should get the retry action.

`SIZE` and `DATE_MODIFIED` come from a MediaStore query on the entry uri made just before the stream is opened (after `isAllowedPhotoUri`; a trashed or pending item counts as missing, see D6). Only a stream opened as the original (D10) can reach `Verified`; any other stream is `Unavailable`.

### D5: `VerifiedUploadsStore`
- Preferences DataStore `verified_uploads` (`VERIFIED_UPLOADS_DATASTORE_NAME`), one string-set key holding one JSON object per photo: `id`, `uri`, `size`, `dateModified`, `serverUrl` (the configured URL, non-sensitive, already stored unencrypted), `folder` (the exact remote folder string passed to the `PUT`), `fileName`, `verifiedAt`. No credentials, no username.
- Parsed strictly (`opt` + type checks, `id >= 0`); a malformed element is skipped (= not removable). Keyed by `id`, newest `verifiedAt` wins on read and on write; at most `MAX_VERIFIED_UPLOADS` (20,000) records, the oldest dropped.
- Writes: `applyUpload(target, record, forgetIds)` around every `PUT` (D11) — after `markUploaded` for a success (a failed write is logged by type and only means the photo is not removable); `forget(ids)` after a confirmed trash; `clear()` first on logout (each clear of the logout in its own `runCatching`) and before saving credentials with another server URL or username (D11).
- `records` are parsed with `flowOn(parseDispatcher)` (default `Dispatchers.Default`, injectable), never on the main thread.
- Delegate with `verifiedUploadsCorruptionHandler` (`ReplaceFileCorruptionHandler` → empty, pinned by a regression test), internal `DataStore<Preferences>` constructor for tests, excluded in `backup_rules.xml` and both `data_extraction_rules.xml` sections (`BackupRulesTest`). Losing the store is safe: nothing becomes removable.

*Alternative:* keeping verification records in queue / retry files. Rejected: those files are deleted when the batch ends, and the removal happens later, from the app.

### D6: Removal flow (photos screen)
- `PhotoGridScreen`'s top bar gets an overflow menu (`MoreVert`, localized content description) with "Remove uploaded photos from device", disabled while a removal runs.
- New `UploadedRemovalViewModel` (`AndroidViewModel`, creatable by the default factory, `ViewModelFactoryRegressionTest`; never references `PhotoGridViewModel`) with an injectable `RemovalCandidateChecker` and `LocalMediaTrasher` for tests. States: `Idle` → `Checking(done, total)` → `Confirm(candidates)` / `NothingToRemove` → `Trashing` → outcome. The check is a guarded one-shot job (re-entry ignored, Cancel button, cancelled on `onCleared`).
- `RemovalCandidateChecker.check(records, config)`: per record (bounded parallelism 4, each server call under `withTimeoutOrNull(30 s)`, every failure → kept, `CancellationException` rethrown): `serverUrl` equals the current config URL; candidate validation (D7); MediaStore query by uri with `SIZE` and `DATE_MODIFIED` equal to the record (an item-uri query returns trashed and pending rows whatever the query arguments say, so `IS_TRASHED` / `IS_PENDING` are projected and 1 counts as missing); `remoteFileSize()` returns `2xx` with a size equal to the record. Missing or invalid config → nothing to remove with a "not configured" message.
- Confirmation: an in-app dialog "Move N photos to trash?" (plural, mentions the system trash and that photos can be restored for about 30 days). `NothingToRemove` shows an explanation instead (only verified, unchanged photos still on the server are removed) and never opens the system dialog.
- Trash: on confirm the ViewModel re-reads the records and the configuration (bounded) and keeps only candidates whose record is still stored unchanged and whose `serverUrl` is the configured one; it re-runs the full check when the confirmation is older than 10 minutes, else the local MediaStore part (time passed while the dialog was open; a failing re-check is `Failed`); it snapshots which candidates are already in the trash (they are not requested and never counted or forgotten), then splits the candidates into requests of at most `MAX_TRASH_REQUEST_URIS` = 2000 and exposes one `IntentSenderRequest` at a time; the screen launches it with `rememberLauncherForActivityResult(StartIntentSenderForResult())`. `RESULT_OK` → next chunk; anything else stops (remaining chunks are not requested).
- Confirmation of the result: after the last request (or a stop) the trasher queries the requested uris one by one (a failing uri is "not confirmed") and counts `IS_TRASHED = 1` minus the snapshot; `RESULT_CANCELED` with nothing newly trashed is `Cancelled`. The outcome (`Trashed(n)` / `Cancelled` / `Failed`) is a one-shot state consumed by the screen (snackbar with `pluralStringResource`), the trashed ids are removed from `VerifiedUploadsStore`, and the screen asks the grid ViewModel to reload.
- Every job (check, confirm) owns a generation; `cancel()` bumps it, so a cancelled job's cancellation handler or late progress callback never overwrites the state of a job started right after.
- The check stops after 5 consecutive server failures or timeouts (I/O, `5xx`, `408`, `429`; other answers reset the count): the remaining calls are cancelled, every photo is kept and a localized "Server not reachable" dialog is shown.
- Process death while the system dialog is open loses the ViewModel state; the result is then ignored. That is safe: trashed items no longer pass the MediaStore check, so they are never requested again.

### D7: Candidate validation
Fail closed; an invalid record is skipped, never trashed. The uri must pass `isAllowedPhotoUri`, have the MediaStore authority `media`, be an item of the `images` or `video` collection (`content://media/<volume>/images|video/media/<rawId>`), and its `rawId` must equal the record `id` without `Photo.VIDEO_ID_FLAG`, with the flag set exactly for `video` uris. The `folder` / `fileName` are re-validated by `WebDavClient`'s segment validation before the `HEAD` (failure → kept).

### D10: Originals and the media location (review finding)
Without `ACCESS_MEDIA_LOCATION`, MediaProvider redacts the location of another app's photo opened through `ContentResolver` (EXIF GPS and the ISO-BMFF location boxes are zeroed, the size stays the same). The redacted copy verifies byte for byte, and removing the local photo would destroy its location. User decision D-A: request `ACCESS_MEDIA_LOCATION` with the media permissions (never an access permission; upgrade users with media access are asked once — persisted `media_location_requested` plus a per-session guard, never before the flag is read, never partial-access users, no banner) and open verifiable entries through `MediaStore.setRequireOriginal(uri)` when it is granted (`worker/UploadSource.kt`, `UploadSource.Original`). Without the permission, or when that open throws `UnsupportedOperationException` / `SecurityException` / `FileNotFoundException` / `IllegalArgumentException`, the plain uri is uploaded (`UploadSource.Plain`) and the result is forced `Unavailable`: no record. A record is built only from an `UploadSource.Original` (pinned by a source-scanning regression test). *Alternative:* comparing EXIF before removal. Rejected: the redacted bytes are what was verified, there is nothing to compare against.

### D11: Account and path binding of records (review finding)
- A record says "the file at `(serverUrl, folder, fileName)` is this photo". Another `PUT` to that path (another photo with the same display name, a staged photo, a failed attempt) replaces the server file, so every `PUT` attempt calls `applyUpload(UploadTarget, …)` before the `PUT` (drops every record at the target, so a run that dies mid-`PUT` leaves none) and after it in one edit with the photo's own update (drops records of other ids at the target, stores the verified record or forgets the id). Folder and file name are compared case-insensitively (some servers are), which only ever forgets more.
- Before recording, the worker re-reads the configuration (`first()`, 3 s) and records nothing if it is invalid or its URL or username differs from the run's.
- `SettingsViewModel` clears the store before saving credentials whose URL or username differs from the stored ones (or when they cannot be read); the username itself is never stored in a record.

### D8: Settings
`CredentialStore` gets `verifyByDownload` (`verify_by_download`, default false) as a plain `edit` without a `credentials_version` bump, like `wifiOnly`; it shares the credentials DataStore's backup exclusion. `SettingsScreen` shows the toggle with an explanation that it downloads every upload again (extra data) and is only needed for servers without Nextcloud hash support.

### D9: Notification
`UploadFailure.Verification` gets a reason string ("could not be verified on the server") in `uploadFailureMessage()`; nothing else in the notification changes.

## Risks / Trade-offs

- [The server file changes or disappears between the `HEAD` and the trash] → Very small window, the trash keeps the local copy restorable for about 30 days.
- [`HEAD` checks only the size, not the content, at removal time] → The content was verified at upload time; re-downloading every photo before removal would cost the same data again. Accepted.
- [Server lies about `X-Hash-SHA256`] → Out of scope (the server is trusted with the data anyway).
- [Download verification doubles traffic for large videos] → Off by default, explained in Settings, runs under the upload's Wi-Fi-only constraint.
- [Same-name overwrite: another client writes the same path between `PUT` and download] → The download then mismatches → failed → retry, which overwrites the other client's file; this is an existing `PUT` behaviour that the change does not make worse. A later overwrite by another client with a different size makes the `HEAD` check keep the photo. Overwrites by this app are handled by D11.
- [Two upload works running at the same time write the same path] → The after-`PUT` edit of the later-finishing work drops the other record, but if the work whose `PUT` finished first records last, its record survives while the server holds the other photo; with equal sizes it could be trashed. Rare (two concurrent batches, same folder, same name, same size, inverse completion order); accepted, the trash keeps the photo restorable for about 30 days.
- [The verification download has no overall time limit, like the `PUT`] → Only `uploadClient`'s per-read timeout bounds it (large videos); it is cancelled with the work.
- [`recordVerified` / `applyUpload` rewrite the whole store per photo] → Accepted performance cost: at most 20,000 records are parsed and written per edit, off the main thread; an edit that changes nothing writes nothing.
- [Always-on `X-Hash` on Nextcloud] → A server that ignores the header simply does not answer it (`Unavailable`, or download fallback); a mismatch fails the upload, which is the point.
- [Trash is not freed space until it empties] → Accepted trade-off for recoverability.
- [MediaStore `DATE_MODIFIED` has second precision] → An edit within the same second with the same size could slip through. Accepted: the upload's hash matched the content at upload time.
- [Many records make the check slow, or a slow / hostile server stalls it] → Bounded parallelism, progress and Cancel; at most 20,000 records; 5 consecutive server failures or timeouts stop the check.
- [API 36 / 37 behaviour of `createTrashRequest` was not covered by the spike (only the API 35 AVD)] → The Activity path is the documented one; manual check 8.4 on a current device.

## Migration Plan

No data migration. Old queue and retry files are unchanged. Photos uploaded before this change have no verification record and are never offered for removal. A downgraded app ignores the new DataStore file and setting.

## Open Questions

- Exact wording of the menu item, dialogs and notification reason (resolved during implementation with the string resources).

## Implementation notes and deviations

- `verifierFor(config, downloadEnabled, client)` takes the `WebDavClient` (the download strategy needs it); an internal overload with a factory lambda is used by the tests.
- An `Unavailable` re-upload also forgets the photo's older `VerifiedUpload` record (not only a failed verification): the server copy was replaced by content that is not verified, so the photo must not stay removable on the strength of the old record. Fail closed.
- The removal check treats a timeout / failure of one server call as "keep"; a failing or throwing `check()` as a whole also ends in "nothing to remove" (never a partial list).
- `UploadedRemovalViewModel` exposes the next system request as `pendingRequest` and is told about launch (`onRequestLaunched`) and result (`onTrashResult`) by the screen, so a recreated screen never launches a request twice. The result snackbar runs in the screen's own coroutine scope (consuming the outcome changes the effect key and would otherwise cancel it).
- Trash confirmation is `LocalMediaTrasher.trashedAmong(uris): Set<Uri>` (not a bare count) so exactly the confirmed photos are forgotten.
- `UploadWorker` helpers moved to top level (`parseQueue`, `logUnreadable`, `VerifiedUploadsRepositoryContract.update`) and `SettingsViewModel.runConnectionTest` to a top-level function to stay below detekt's `TooManyFunctions` limit without suppression.
- `NoHardDeleteLocalMediaRegressionTest` additionally pins: no `.delete(` / `createDeleteRequest` / `MANAGE_MEDIA` on the removal path and in the manifest.
- `lintDebug` reported `AndroidGradlePluginVersion` (AGP 9.5.0 available). User decision D-B: AGP bumped from 9.4.1 to 9.5.0 in `gradle/libs.versions.toml`.
- `RemovalCandidateChecker.check()` returns `RemovalCheck(candidates, serverUnreachable)` instead of a bare list.

## Review findings (code and security review, fixed)

1. Location redaction (critical) → D10. 2. Trashed / pending item rows are returned by item-uri queries whatever the query args say → `IS_TRASHED` / `IS_PENDING` projected, already-trashed snapshot in `confirm()`, `FakeMediaStoreProvider` returns such rows like the platform. 3. Same-path overwrite → D11. 4. Stale confirm → store / config re-read, full re-check after 10 minutes (D6). 5. Account binding and logout race → D11, logout clears the store first, each clear isolated. 6. Main-thread CPU → `Set` matching on `computeDispatcher`, `flowOn` for `records`. 7. Crash / convention fixes: guarded `recheckLocal`, per-uri `trashedAmong`, bounded `forgetTrashed` read rethrowing cancellation, `mediaFileStateOf` maps every non-cancellation exception to null, `isAllowedPhotoUri` before any MediaStore query in the worker, guarded `setVerifyByDownload`. 8. Cancel / start race → generations (D6). 9. Slow / hostile server → 5-failure abort (D6, Risks). 10. `NoHardDeleteLocalMediaRegressionTest` pins `createTrashRequest(…, true)` and forbids `createDeleteRequest` in every main source. 11. Added tests: work cancelled during the verification download, `remoteFileSize` cancellation, a recreated screen never launches the system request twice.

