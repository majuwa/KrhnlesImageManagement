# Proposal

## Why

Feature request (issue text):

> `ui/share/OccasionDialog.kt` is a free-text field; users create near-duplicate folders ("Summer 2026" vs "Summer-2026"). Show suggestions when the field is empty or while typing: recent occasion folders from `UploadHistoryStore` (deduplicated case-insensitively, newest first, excluding auto-date / per-photo batches) and, best-effort, existing remote albums (bounded timeout, failures silently ignored, never blocks the dialog, never shows server text in errors, only for the configured server; reuse `AlbumsRepositoryProvider` / album listing rules — bounded PROPFIND etc.). Tapping a suggestion fills the field; occasion validation is unchanged. Applies to grid upload dialog and share target dialog. Filtering case-insensitive by typed text, limited count (e.g. 5).

Every upload into a mistyped or differently-cased folder name creates a new remote folder (WebDAV paths are case-sensitive), so the album list fragments and the duplicate / blur reviews see only part of an occasion. Offering the folders the user already uses is the cheapest way to prevent this without restricting free text.

## What Changes

- The occasion dialog (photo-grid upload and share target) shows up to 5 tappable folder suggestions below the name field, both when the field is empty and while typing.
- Suggestions come from two sources, merged into one ranked list:
  - **Recent occasions** from the local upload history: newest first, deduplicated case-insensitively, without auto-date (per-photo) batch labels, blank names or names that are not valid occasion names.
  - **Existing remote albums** (folders directly below the configured base folder), loaded best-effort from the configured server with the existing bounded album listing, under an overall timeout; any failure or timeout is silently ignored and never blocks, delays or errors the dialog. When a recent name and a remote album differ only in case, the remote folder's exact spelling is suggested.
- Typing filters suggestions case-insensitively (prefix matches before substring matches); a suggestion identical to the typed text is not shown.
- Tapping a suggestion fills the name field (cursor at the end). It does not confirm the upload; occasion validation, trimming and the Upload button behaviour are unchanged.
- Each suggestion is an accessible button whose text is the folder name, with a localized click label; all new strings live in `strings.xml`.
- A new default-factory-creatable `OccasionSuggestionsViewModel` owns the suggestion loading (its own `AlbumsRepositoryProvider`); the dialog itself stays stateless with respect to data and receives the candidates as a parameter. No other ViewModel references it.
- `AlbumsRepositoryContract` gains a side-effect-free `folderNameOf(album)` that returns the decoded folder segment of a listed album (exactly one level below the base folder) or `null`, so suggestions use the real path segment rather than the server-supplied display name.
- No change to the upload-history data format, the upload queue, the worker or album screens.

## Capabilities

### New Capabilities
- `occasion-folder-suggestions`: Ranked, filtered folder-name suggestions in the occasion dialog (grid upload and share target) from local upload history and the configured server's existing albums, including the best-effort/privacy rules for the remote part and accessibility of the suggestion buttons.

### Modified Capabilities
<!-- None: the share-target and upload-history requirements stay valid as written; suggestions are additive and covered by the new capability. -->

## Impact

- **UI:** `ui/share/OccasionDialog.kt` (new `suggestions` parameter, `TextFieldValue` state with saver, suggestion chips), `ui/photogrid/PhotoGridScreen.kt` and `ui/share/ShareReceiverScreen.kt` / `ShareRoute` (pass candidates, trigger loading when the dialog is shown).
- **New code:** `ui/share/OccasionSuggestions.kt` (pure ranking / dedupe / filtering), `ui/share/OccasionSuggestionsViewModel.kt`.
- **Data:** `data/Repositories.kt` (`AlbumsRepositoryContract.folderNameOf`), `data/AlbumsRepository.kt` (implementation reusing the existing album-segment validation), `fakes/FakeAlbumsRepository.kt`.
- **Resources:** new strings (heading, click label) in `values/strings.xml` (German translation is handled by the separate `german-translation` change, implemented last).
- **Tests:** new unit, ViewModel and Robolectric Compose tests; `ViewModelFactoryRegressionTest` gains the new ViewModel.
- **Docs:** `README.md`, `CLAUDE.md`, `docs/architecture.md`, `docs/requirements.md`.
- **Network:** at most one extra Depth-1 PROPFIND of the base folder per dialog opening (skipped when a fresh result for the same configuration exists); nothing is sent when no valid configuration is stored.
- **Sibling changes:** `album-search-and-sort` also touches album listing — this change only consumes `listAlbums()` and adds `folderNameOf`, it does not change listing, sorting or `RemoteAlbum`; whichever lands second rebases on the other. `pin-self-signed-certificate` changes the OkHttp client built for `AlbumsRepository`; suggestions inherit it automatically. `german-translation` must translate the new strings. `show-videos-in-albums`, `download-album-photos`, `nextcloud-chunked-upload`, `cancel-running-upload`, `new-photos-reminder` and `readme-roadmap-cleanup` have no functional overlap (README / CLAUDE.md edits may need a textual merge).
