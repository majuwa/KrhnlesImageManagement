# Proposal

## Why

`AlbumsViewModel` owns the album list, album renaming/deletion, the album detail photos, the duplicate scan, the blur scan and every photo-deletion entry point (~450 lines). Any change to one flow risks breaking another, the class is hard to test in isolation, and every album screen depends on the whole thing (GitHub issue #29, identical duplicate #48).

## What Changes

- Split `AlbumsViewModel` into four ViewModels with one responsibility each:
  - `AlbumsViewModel` — remote album list, rename album, delete album.
  - `AlbumDetailViewModel` — photos and thumbnails of one album, full-image URLs, selection-mode photo deletion, the shared photo-deletion orchestration (re-entry guard, busy-state reset, one-shot deletion outcome).
  - `DuplicatesViewModel` — duplicate scan and its review state.
  - `BlurViewModel` — blur scan and its review state.
- Scope each ViewModel to its own navigation route: the album list to the Albums tab entry, the album detail state to the album detail entry (shared by the fullscreen viewer, duplicate review and blur review opened from it), each review's scan state to the review entry (discarded and cancelled when the review is left).
- Review deletions keep going through the album detail's single deletion orchestration, so the photo list, the outcome snackbar on the album detail screen, the re-entry guard and the review busy state (`Deleting` → `Idle` via compare-and-set) behave exactly as before.
- Extract the cached repository creation into one reusable, configuration-aware helper (`AlbumsRepositoryProvider`, Result-returning, lock with timeout) and the blur scoring behind a small interface so the blur scan can be unit tested. The reviews use the album detail's repository (one OkHttp client per album flow).
- Extract the shared scan loop (download → analyze → always recycle → progress → error mapping) of both reviews into one helper.
- Split and move the tests accordingly; extend the ViewModel factory regression test to the new ViewModels.
- Fixes from code and security review of the split:
  - Selection-mode deletion can no longer be cut short by leaving the album (Back blocked, back / clear-selection buttons disabled while deleting).
  - A missing or invalid configuration (e.g. a restored album after logout) no longer crashes the album list, album, or reviews; it is shown as an error.
  - A recreated review (rotation) no longer restarts its scan, discards results / selection or interrupts a deletion; Retry is explicit.
  - Album, review and album-list errors are typed and shown as localized messages instead of raw exception text (new `albums_error_*` strings, Retry on the album screen).
  - An open album stops using the old server and credentials after logout or a server / account change.
  - Duplicate / blur scans stop and drop their results when the server or account changes (per-photo repository check, `scanJob` cancellation), only start once the album is loaded and never report "none found" for an unloaded or failed album; deletions never touch photos outside the loaded album; a recreated album screen no longer reloads (`loadAlbum` no-op, explicit `reload()`); `oc:fileid` is validated before it enters a Memories URL.
  - Scan thumbnail downloads are restricted to the configured base folder or the Memories preview endpoint.

## Capabilities

### New Capabilities

- `album-error-handling`: user-friendly album / review errors, no crash on missing or invalid configuration, an open album stops using outdated credentials.

### Modified Capabilities

- `album-navigation`: album sub-screens share the state of the album they were opened from (album detail entry) instead of the whole Albums tab state; review scan state lives only as long as the review screen and survives recreation.
- `album-photo-deletion`: leaving is blocked during selection-mode deletions too (requirement renamed to "Leaving a screen while deleting is not possible").
- `webdav-href-validation`: scan thumbnail downloads stay on the configured server and folder.

## Impact

- Code: `ui/albums/*` (ViewModels, screens, `AlbumsError`, `PhotoScan`, `ReviewSelection`), `ui/viewer/FullscreenViewerScreen.kt`, `KrhnlesApp.kt` (ViewModel scoping), `data/` (repository provider, thumbnail URL validation, typed PROPFIND failures), `util/BlurAnalyzer.kt` (scorer interface), `res/values/strings.xml` (`albums_error_*`).
- Tests: `AlbumsViewModelTest`, `AlbumsViewModelPhotoDeletionTest` (moved/renamed), `PhotoDeletionOutcomeUiTest`, `ViewModelFactoryRegressionTest`, `AlbumsScreenTest`, `AlbumsRepositoryTest`, `BlurStateTest`, new tests for the detail, duplicate and blur ViewModels, the repository provider, `AlbumsError` and the album-detail parent-entry lookup.
- Docs: `docs/architecture.md` (package structure, navigation scoping, scans, repositories, errors, deletion path, href validation), `CLAUDE.md` (navigation-scoped state, error mapping, security conventions), `README.md` (error messages, Back during deletion, rotation).
- No dependency, API, permission or persistence changes.
