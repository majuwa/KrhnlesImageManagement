## Context

Observed: rename exists end to end (`AlbumsScreen` long-press menu -> `RenameAlbumDialog` -> `AlbumsViewModel.renameAlbum` -> `AlbumsRepository.renameAlbum` -> `WebDavClient.renameDirectory`, tests in `AlbumsScreenTest`, `AlbumsRepositoryTest`, `WebDavClientTest`, `AlbumsViewModelTest`). Source href is validated (`albumRelativeSegments` -> `requireSafeHref`), `Overwrite: F` is sent and the list entry is replaced in place. Gaps: validation, lost status (`error(...)`), blocking uncancellable `execute()` (`WebDavClient.kt:146`), possible redirect false success, no typed error / string, no guard / timeout (CLAUDE.md rules 6, 7). This change closes those gaps only.

## Goals / Non-Goals

**Goals:** meet the five acceptance criteria reliably; follow CLAUDE.md conventions.
**Non-Goals:** detail-screen rename, upload-history / queue rewriting, nested/moved albums, `webDavConfig` observer, deletion-path changes, new dependencies.

## Decisions

1. **One entry point: Albums list long-press (already present).** The detail route is keyed by the old href, so a detail rename would need re-navigation. Rejected for scope.
2. **Validation:** reuse `isValidOccasionName()` on the trimmed name; dialog maps to `error_album_name_blank`, `error_album_name_unchanged`, new `error_album_name_invalid`. `AlbumsRepository.renameAlbum` trims `newName` itself (or rejects untrimmed; `AlbumsRepository.kt:161`) and re-checks (-> `AlbumsError.InvalidName`); `WebDavClient.validatePathSegment` remains the last line. Unchanged check is exact (case-sensitive) and compares against both `displayName` and the decoded last path segment of the href (the displayName fallback may be percent-encoded, `WebDavXmlParser.kt:320`).
3. **Destination / source:** keep `buildPathUrl`; new segments are `oldSegments.dropLast(1) + newName`. Source from `requireSafeHref(album.href)`, sent with a trailing slash (collection URL) so servers do not answer 301; alternatively redirects are disabled for the MOVE request. `Overwrite: F` kept.
4. **Cancellable MOVE (blocker):** replace blocking `execute()` with the existing `Call.awaitStatus()` (`WebDavClient.kt:181`, cancels the call in `invokeOnCancellation`) or `executeCancellable`. No `call.timeout()` / `callTimeout` on the shared client (rule 7); the timeout lives in the ViewModel.
5. **Success statuses:** only 201 / 204 are success; any other status (including 3xx, 200) throws `WebDavHttpException(code)`. Branch on `code` only.
6. **Errors:** no new sealed type. Add `AlbumsError.AlreadyExists` (412) and `AlbumsError.InvalidName`; extend `toAlbumsError()` / `albumsErrorMessage()`. Mapping for MOVE: 412 -> AlreadyExists; 404 and 409 -> not found ("album no longer exists"; 409 means the destination parent is missing); 400 / 403 -> "server rejected the name or you lack permission" message; 423 and others -> generic HTTP path. Strings in `strings.xml`. The `404 -> not found` mapping used by album list/detail must keep its wording there; the rename-specific wording is chosen by the rename call site if the generic text does not fit.
7. **ViewModel one-shot:** `renameJob` (re-entry ignored while `isActive`); `withTimeoutOrNull(30 s)` around `repos.get()` + MOVE (never `withTimeout`: its `TimeoutCancellationException` would be rethrown by the CancellationException rule); `runCatching` inside, rethrowing `CancellationException`; null -> timeout result; `ensureActive()` before publishing; `finally` resets `isRenamingAlbum`. Timeout message says the rename may still have completed, and the ViewModel triggers `loadAlbums()` automatically after a timeout. Top-bar Refresh is ignored while a rename runs (avoids a reload racing the in-place replacement). Result is a one-shot `renameOutcome` + `consumeRenameOutcome()` replacing `onComplete`. No `webDavConfig` observer: `AlbumsRepositoryProvider` already rebuilds per call on config change.
8. **List update:** replace by old href at the same index, no PROPFIND. The grid is already replaced by a spinner while `isRenamingAlbum` (`AlbumsScreen.kt:134-142`) and the dialog closes on confirm, so no new busy UI is needed.
9. **CLAUDE.md rule 7:** reword "Only `testConnection()` has an overall call timeout" to also name album rename: a ViewModel-level `withTimeoutOrNull` plus a cancellable call; still no `callTimeout` on the shared client.
10. **Side effects:** Coil keys are URLs (new hrefs, new keys); `UploadedPhotosStore` keyed by MediaStore ids, unaffected; upload-history names not updated. Known limitation (design + README): pending uploads and retry queues store the old `folderName` and may re-create the old album after a rename.
11. **Accessibility:** unchanged from the existing dialog (labeled field, `supportingText` errors, snackbar outcome, no names as `contentDescription`).

## Review fixes

- Segment lists, not joined strings, go from repository to `WebDavClient.renameDirectory`; blank segments are rejected, never filtered; the source must be exactly one level below the base folder; `validatePathSegment` also rejects `\` and ISO control characters.
- Any 2xx other than 201 / 204 -> `AlbumsError.RenamePartial` ("may be incomplete") and the list is reloaded like after a timeout.
- New names are capped at 255 UTF-8 bytes (`AlbumsError.NameTooLong`, dialog and repository).
- `WebDavClient`'s interceptor adds `Authorization` only for the configured origin (`hasSameOriginAs`).
- The screen shows the snackbar inside the `LaunchedEffect` and consumes the outcome afterwards; the success branch drops a duplicate href; the repository uses `resultOf {}`.

## Risks / Trade-offs

- Case-only renames are accepted; a case-insensitive server answers 412 -> "already exists".
- A timeout cancels the client call but the server may still complete the MOVE; hence the automatic `loadAlbums()` and the "may have completed" wording.
- DELETE has the same 3xx -> GET hole; out of scope, noted for a follow-up.
- Callback -> outcome-state refactor touches existing tests; they are updated, not disabled.

## Decisions on former open questions

List long-press only; 30 s timeout; deletion path untouched; upload-history names not updated; case-only renames accepted, 412 -> already exists.
