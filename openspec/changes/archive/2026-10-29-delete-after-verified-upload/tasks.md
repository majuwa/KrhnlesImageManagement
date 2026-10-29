# Tasks

## 1. Spike: background trash with Media management access (gate)

- [x] 1.1 On an API 35+ device or emulator, test whether a background `CoroutineWorker` can complete `MediaStore.createTrashRequest` with `MANAGE_MEDIA`. Verify by the `IS_TRASHED` re-query and logcat. Result: no (design.md, *Spike result*).
- [x] 1.2 Record the permission findings (`READ_EXTERNAL_STORAGE`, `ACCESS_MEDIA_LOCATION`, partial access, lint) in `design.md`. Verify by the *Spike result* section.
- [x] 1.3 Gate failed → revisited with the user. Decision (2026-10-28): manual menu action, per photo, verified photos only, in-app plus system confirmation, no `MANAGE_MEDIA`, verification always on. Verify by the updated proposal / design / specs.

## 2. Settings: download-verification preference

- [x] 2.1 Add a `verifyByDownload` flow and setter to `CredentialStore` (plain `edit`, key `verify_by_download`, default false, no `credentials_version` bump) and to its contract and fakes. Verify with unit tests: default false, value persists, writing it does not re-emit the credential snapshot flow.
- [x] 2.2 Add the toggle with the extra-data explanation to `SettingsScreen` / `SettingsViewModel` (string resources only, accessible toggle). Verify with a Robolectric Compose test: toggling persists the value.

## 3. WebDavClient: server hash, verification download, size check

- [x] 3.1 Extend `uploadFile` with `requestServerHash` (sends `X-Hash: sha256`) and return `UploadResponse(serverSha256)`, accepting only a 64-hex `X-Hash-SHA256`. Update callers. Verify with `WebDavClientTest` (MockWebServer): header sent only when requested, a valid value returned, malformed / absent → null, existing upload tests still pass.
- [x] 3.2 Add `downloadSha256(folderName, fileName, maxBytes)` per design D3. Verify with `WebDavClientTest`: matching body, longer body / larger `Content-Length` → oversize result (distinct from I/O errors), 3xx / 404 fail, cancellation cancels the call, `Authorization` only on the configured origin.
- [x] 3.3 Add `remoteFileSize(folderName, fileName)` (`HEAD`, `Accept-Encoding: identity`, no redirects, cancellable, segment validation). Verify with `WebDavClientTest`: size returned, absent `Content-Length` → null, 404 / 3xx fail, invalid segment → failure without a request.

## 4. Verification strategies

- [x] 4.1 Implement `UploadVerifier`, `Verification` (`Verified` / `Mismatch` / `Inconclusive` / `Unavailable`), `NextcloudHashVerifier` (with optional fallback), `DownloadVerifier`, `NoVerifier` and `verifierFor(config, downloadEnabled)` per design D1, reusing the existing Nextcloud URL detection. Verify with unit tests covering every row of the selection (Nextcloud with/without header, with/without fallback; plain WebDAV with/without download) and every outcome (case-insensitive match, mismatch, oversize → mismatch, I/O / timeout / non-2xx → inconclusive).
- [x] 4.2 Add a SHA-256 + byte-counting stream wrapper. Verify with a unit test that its digest equals `MessageDigest` over the same bytes and that it closes the wrapped stream.

## 5. VerifiedUploadsStore

- [x] 5.1 Implement `VerifiedUploadsStore` per design D5 (record JSON, strict parse, newest wins, cap 20,000, `recordVerified` / `forget` / `clear`, corruption handler, internal test constructor) with a contract and a fake. Verify with unit tests: round trip, malformed elements skipped, dedupe by id, cap drops the oldest, `forget` / `clear`, no credential fields; `VerifiedUploadsCorruptionHandlerRegressionTest`.
- [x] 5.2 Exclude `datastore/verified_uploads.preferences_pb` in `backup_rules.xml` and both `data_extraction_rules.xml` sections. Verify with `BackupRulesTest` (`VERIFIED_UPLOADS_DATASTORE_NAME`).
- [x] 5.3 Clear the store on logout next to the uploaded markers. Verify with a `SettingsViewModel` test.

## 6. Worker: verify while uploading

- [x] 6.1 In `UploadWorker`, read the download setting once per run (timeout, fallback off), query MediaStore `SIZE` / `DATE_MODIFIED` before opening each non-staged entry, wrap the stream after `uploadLengthOf()` (design D2), pass `requestServerHash`, run the verifier, map outcomes per design D4 (new `UploadFailure.Verification` with its reason string) and write / forget the record. Verify with Robolectric worker tests (`UploadWorkerRunTest` style, MockWebServer, fake media, fake store): verified entry recorded, mismatch / inconclusive count as failed, are not marked, land in the retry file and show the reason; `Unavailable` (incl. size mismatch) counts as uploaded without a record and is not retried; plain WebDAV without the setting sends no hash header and makes no download; staged uploads keep their `Content-Length` and are never verified or recorded.
- [x] 6.2 Verify interruption behaviour: a foreground refusal after a verified entry keeps its record and does not re-upload it. Verify with an `UploadWorkerForegroundTest` addition.

## 7. Removal action

- [x] 7.1 Implement candidate validation (design D7) as a pure function and `RemovalCandidateChecker` (design D6: server URL, MediaStore check, `HEAD` size, bounded parallelism, per-call timeout, fail closed, cancellable). Verify with unit tests: each invalid uri / id variant skipped, edited / missing / trashed photo kept, server 404 / other size / timeout / missing config kept, other server URL kept, cancellation propagates.
- [x] 7.2 Implement `LocalMediaTrasher` (production: `createTrashRequest` per ≤ 2000 uris → `IntentSenderRequest`, confirmation re-query with `QUERY_ARG_MATCH_TRASHED`; fake for tests). Verify with unit tests of the chunking and the confirmed count (Robolectric where the MediaStore is needed).
- [x] 7.3 Implement `UploadedRemovalViewModel` (design D6 states, guarded one-shot job, local re-check before the request, chunk sequencing, one-shot outcome, `forget` of trashed ids). Verify with ViewModel unit tests: nothing to remove, confirm → chunks in order, `RESULT_CANCELED` stops, confirmed count reported, re-entry ignored, cancel during the check. Add it to `ViewModelFactoryRegressionTest`.
- [x] 7.4 Add the overflow menu, progress / Cancel, the confirmation and explanation dialogs, the `StartIntentSenderForResult` launcher, the snackbar and the grid reload to `PhotoGridScreen` (string resources and plurals only, accessible). Verify with a Robolectric Compose test using a fake `ActivityResultRegistry` via `LocalActivityResultRegistryOwner` (`PhotoGridPermissionTest` pattern): menu item present, dialog shows the count, confirm launches the request, cancel launches nothing, result snackbar shows the confirmed count, item disabled while running.

## 8. Documentation and project checks

- [x] 8.1 Document the download setting, the always-on verification and the removal action in `README.md`.
- [x] 8.2 Add an architecture section (verification strategies, `VerifiedUploadsStore`, removal flow, spike result) to `docs/architecture.md`. Verify the section names the classes and keys as implemented.
- [x] 8.3 Add the verification / removal conventions (strategy selection, fail-closed rules, Activity-only trash via `startIntentSenderForResult`, trash never hard delete, no "delete" naming, store backup exclusion) to `CLAUDE.md`. Verify the new rule mentions the guarding tests.
- [x] 8.4 Run `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport`. Verify all pass with zero lint issues and no new detekt findings (or the user approved them).
- [ ] 8.5 (Manual, by the user — not an agent task, does not block the archive) End-to-end check on a current device against a Nextcloud server (X-Hash path) and a plain WebDAV server (download path): upload, run the removal, confirm the photos land in the system trash and are restorable.

## 9. Review findings

- [x] 9.1 Location redaction: request `ACCESS_MEDIA_LOCATION` (upgrade users once), upload originals via `setRequireOriginal`, record only originals. Verify with `UploadWorkerVerificationTest` (granted / denied / original throws), `PhotoGridPermissionTest`, `VerifiedRecordOnlyFromOriginalRegressionTest`.
- [x] 9.2 Trashed / pending item rows: project `IS_TRASHED` / `IS_PENDING`, snapshot already trashed candidates, fake returns such rows. Verify with `RemovalCandidateCheckerTest`, `LocalMediaTrasherTest`, `UploadedRemovalViewModelTest`.
- [x] 9.3 Same-path overwrite: `applyUpload(UploadTarget, …)` around every `PUT`. Verify with `UploadWorkerVerificationTest`, `VerifiedUploadsStoreTest`.
- [x] 9.4 Stale confirm: re-read store / config, full re-check after 10 minutes. Verify with `UploadedRemovalViewModelTest`.
- [x] 9.5 Account binding / logout: clear on URL or username change, logout clears first and isolated, worker re-reads the config before recording. Verify with `SettingsViewModelVerifiedUploadsTest`, `UploadWorkerVerificationTest`.
- [x] 9.6 Main-thread CPU: `Set` matching on `computeDispatcher`, `flowOn` for `records`. Verify with `VerifiedUploadsStoreTest`.
- [x] 9.7 Crash / convention fixes (guarded recheck, per-uri trash check, bounded forget read, `mediaFileStateOf` failures, uri validation first, guarded setting write). Verify with `UploadedRemovalViewModelTest`, `LocalMediaTrasherTest`, `UploadWorkerVerificationTest`, `SettingsViewModelVerifiedUploadsTest`.
- [x] 9.8 Cancel / start race: generations. Verify with `UploadedRemovalViewModelTest`.
- [x] 9.9 Unreachable server: stop after 5 consecutive failures. Verify with `RemovalCandidateCheckerTest`, `UploadedRemovalViewModelTest`, `PhotoGridRemovalTest`.
- [x] 9.10 `NoHardDeleteLocalMediaRegressionTest` pins `createTrashRequest(…, true)`.
- [x] 9.11 Test gaps: cancel during the verification download, `remoteFileSize` cancellation, recreated screen launches the request once (`PhotoGridRemovalTest`).
- [x] 9.12 Bump AGP to 9.5.0 (user decision D-B).

## Workflow follow-up

- Run the independent code review and security review on the implementation, fix findings and update the artifacts if needed.
- Archive the change after review (`/opsx:archive`).
