## Why

Issues #17 / #36 ask for renaming a remote album (WebDAV MOVE). A basic rename already ships (long-press an album in the Albums tab -> "Rename" -> dialog pre-filled with the current name -> `AlbumsRepository.renameAlbum()` -> `WebDavClient.renameDirectory()` with `Destination` + `Overwrite: F` -> the list entry is replaced in `AlbumsState` without a reload). It does not yet meet the project conventions, so the acceptance criteria are only partly reliable:

- The dialog only rejects blank / unchanged names; names with `/`, `\`, NUL, `.` or `..` reach the server (the client throws a raw `IllegalArgumentException`, shown as a generic failure).
- A name that already exists answers `412 Precondition Failed` (because of `Overwrite: F`), but `renameDirectory()` throws `error("Rename failed: HTTP ...")`, so the status is lost and the user only sees "Failed to rename album".
- `renameDirectory()` uses a blocking `execute()` (`WebDavClient.kt:146`): a coroutine cancel / timeout cannot interrupt it, and a 3xx answer to the MOVE could be followed and reported as success.
- `AlbumsViewModel.renameAlbum()` has no re-entry guard, no timeout and no `Job` handling, and reports through a callback with an untyped failure.
- No typed error / string-resource mapping (CLAUDE.md rule 7).

## What Changes

- Entry point: keep the **long-press menu on the Albums list only**. The issue says "or"; the detail screen overflow would add a second entry point, a second dialog host and a need to rename the open `albums/detail?href=` route (its key is the old href), for no extra acceptance value.
- Validate the new name with the existing `isValidOccasionName()` (plus unchanged check) in the dialog and again in the ViewModel / repository (defence in depth), with friendly inline messages for invalid characters.
- `WebDavClient.renameDirectory()` throws `WebDavHttpException(code)` for non-2xx; `AlbumsRepository.renameAlbum()` keeps the source href validated by `requireSafeHref` (strictly below the base folder) and builds the destination via `HttpUrl` path-segment encoding on the configured origin; the renamed album stays within the same parent.
- `renameDirectory()` becomes cancellable (reuse `Call.awaitStatus()` / `executeCancellable`) and accepts only 201 / 204 as success; the MOVE source URL carries a trailing slash (or redirects are disabled for it) so a 301 is a failure, not a success.
- Errors reuse `AlbumsError` / `toAlbumsError()` / `albumsErrorMessage()`; new cases `AlbumsError.AlreadyExists` (412) and `AlbumsError.InvalidName`. 404 and 409 (destination parent missing) map to "album no longer exists"; 400 / 403 on MOVE map to "server rejected the name / no permission"; 423 uses the generic path. No new sealed error type.
- `AlbumsViewModel.renameAlbum()` becomes a guarded one-shot: one `renameJob`, re-entry ignored, `withTimeoutOrNull(30 s)` (null -> timeout result; never `withTimeout`), failures converted to a result, `ensureActive()` before publishing. After a timeout the ViewModel calls `loadAlbums()` automatically because the MOVE may still have completed. Top-bar Refresh is ignored while a rename runs. Result is reported through a one-shot state consumed by `AlbumsScreen` instead of a callback.
- The repository re-check trims the new name; the unchanged check compares (case-sensitive) against the `displayName` and the decoded last path segment of the href.
- Known limitation (documented): pending uploads / retry queues store the old `folderName` and may re-create the old album after a rename.
- Out of scope: renaming from the detail screen, rewriting upload history or queued uploads, nested albums, moving albums, renaming photos, observing `webDavConfig` in `AlbumsViewModel` (the repository provider already rebuilds on change), the deletion path (its DELETE has the same 3xx -> GET hole; not touched here).

## Capabilities

### New Capabilities
- `album-rename`: renaming a remote album from the Albums list - entry point, name validation, collision handling, typed errors, list update, concurrency and configuration-change behaviour.

### Modified Capabilities
<!-- none: existing spec requirements are unchanged; error mapping is added under the new capability -->

## Impact

- Code: `data/WebDavClient.kt`, `data/AlbumsRepository.kt`, `ui/albums/AlbumsViewModel.kt`, `ui/albums/AlbumsScreen.kt`, `ui/albums/AlbumsError.kt`, `res/values/strings.xml` (+ other locales if present).
- Tests: `WebDavClientTest`, `AlbumsRepositoryTest`, `AlbumsViewModelTest`, `AlbumsScreenTest`.
- Docs: `README.md` (rename rules), `docs/architecture.md`, `CLAUDE.md` (rename guard conventions).
- No new dependencies, no permission or backup changes.
