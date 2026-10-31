# Design

## Context

See proposal.md – Why. Relevant current state (observed in the code):

- `AlbumsRepository.listAlbums()` sends one `Depth: 1` PROPFIND for the base folder with `PROPFIND_BODY` (`d:displayname`, `d:resourcetype`, `d:getcontenttype`, `oc:fileid`), drops the first entry, keeps collections with `withSafeHrefsBelow(baseFolderUrl)` and maps them to `RemoteAlbum(displayName, href)`. The same `PROPFIND_BODY` is used by `listPhotos()`.
- `PropfindEntryReader` / `ResponseCollector` (`data/WebDavXmlParser.kt`) capture only the `EntryField`s `HREF`, `DISPLAY_NAME`, `CONTENT_TYPE`, `FILE_ID`; `d:getlastmodified` is **not** read. Every captured value is bounded by `PropfindEntryLimits.maxTextLength` (8 KiB, oversized → entry `Skipped`), plus element / depth / token bounds.
- `AlbumsViewModel` (`AndroidViewModel`, `@JvmOverloads (Application, credentialRepo, repoFactory)`, listed in `ViewModelFactoryRegressionTest` as Application-only) holds `AlbumsState.albums` in server order; rename replaces the entry at the same index; delete filters by href; `loadAlbums()` is ignored while `renameJob` is active.
- `AlbumsScreen` shows a `LazyVerticalGrid` (2 columns, key = href) of `state.albums`, a Refresh action in the `TopAppBar`, and replaces the grid with a spinner while loading / renaming / deleting.
- Plain preferences (`wifiOnly`, `autoDateFoldersEnabled`, `blurSensitivity`, `verifyByDownload`) live in the credentials DataStore, are written with plain `edit`s, are reset by `CredentialStore.clear()` (`prefs.clear()`), excluded from backup with the file, and never re-emit the credential snapshot (it applies `distinctUntilChanged()` to its inputs before decrypting).
- The Albums tab is navigated with `saveState = true` / `restoreState = true`, so `rememberSaveable` state of the `albums` entry survives tab switches as well as rotation and process death.

## Goals / Non-Goals

**Goals:**
- Pure, JVM-testable search and sort functions; UI and ViewModel only wire them.
- No extra network request; only one more property in the album-folder PROPFIND.
- Keep every existing album-list guarantee (rename guards, Refresh-while-renaming, delete outcome, typed errors, href validation) untouched.

**Non-Goals:**
- Nested folder navigation (see Decision 8).
- Natural ("Album 2" before "Album 10") or locale-collated ordering; showing the last-modified date on album cards; searching photo names or album contents; server-side search (`SEARCH` / REPORT).
- Persisting the search query beyond the saved state of the Albums tab.

## Decisions

### 1. Folding: uppercase → lowercase (`Locale.ROOT`) → NFD → strip combining marks
`albumNameKey(name)` = `name.uppercase(Locale.ROOT).lowercase(Locale.ROOT)`, then `Normalizer.normalize(…, NFD)` and removal of `\p{Mn}`. The upper/lower round trip approximates full case folding (`ß` → `ss`, so "strasse" finds "Straße"); doing it before NFD lets the mark removal also clean up `İ` → `i̇` → `i`. `java.text.Normalizer` is in the JDK, so it is cheap and runs in plain JVM unit tests. Matching = `key(album).contains(key(query.trim()))`; blank query → no filter.
*Alternatives:* `lowercase()` only (misses diacritics / ß); `Collator` with PRIMARY strength (locale-dependent, no substring search, harder to test deterministically); ICU `CaseMap.fold` (Android-only API, not available in JVM tests).

### 2. Sorting: total, deterministic comparators in one pure function
`AlbumSort` (`model/AlbumSort.kt`): `NAME_ASC` (default), `NAME_DESC`, `MODIFIED_NEWEST`, `MODIFIED_OLDEST`, persisted by enum name with `fromStored(name)` like `BlurSensitivity`. `ui/albums/AlbumListOrdering.kt` holds `albumNameKey()`, `visibleAlbums(index, query, sort)` and an `AlbumListIndex` (albums paired with their precomputed keys, built once per loaded list).
- Name: compare keys, then exact `displayName`, then `href` (hrefs are unique in a listing), so the result never depends on server order. Z–A is the reversed name comparator.
- Modified: dated albums first (descending / ascending by `lastModified`), ties by the name A–Z comparator; undated albums always after, by name A–Z. Implemented as a partition, not as a reversed comparator, so "unknown last" holds in both directions.
*Alternative:* `sortedWith(compareBy(nullsLast()))` + `reversed()` — would move nulls first on reverse; rejected.

### 3. Query state: `rememberSaveable` in `AlbumsScreen`, filtering via `remember`
The query is UI state of the Albums entry: a `rememberSaveable` `String` (truncated to 255 chars on change — longer queries can never match an album name of at most 255 UTF-8 bytes). The screen computes `remember(index, query, sort) { visibleAlbums(index, query, sort) }` with `index = remember(state.albums) { AlbumListIndex(state.albums) }`, so folding runs once per loaded list and each keystroke is only substring checks.
*Alternatives:* `SavedStateHandle` in `AlbumsViewModel` — would require switching the default-factory constructor to `(Application, SavedStateHandle)` and moving `AlbumsViewModel` in `ViewModelFactoryRegressionTest`, and a `TextField` driven by an asynchronously collected `StateFlow` is prone to lost keystrokes / cursor jumps. Rejected; the pure function is tested directly instead of through the ViewModel.

### 4. Sort state: ViewModel-owned, persisted plain preference, optimistic
`CredentialRepository` gains `val albumSort: Flow<AlbumSort>` (read `asMap()[KEY_ALBUM_SORT] as? String` → `AlbumSort.fromStored`) and `suspend fun saveAlbumSort(sort)` (plain `edit`, no `credentials_version` bump, inside `CredentialStore.kt` only — `NoCredentialDataStoreAccessRegressionTest`). `AlbumsViewModel` exposes `albumSort: StateFlow<AlbumSort>` = stored value overridden by a session-local selection: `setAlbumSort(sort)` sets the override immediately and persists in `viewModelScope` with `runCatching` (rethrow `CancellationException`, log the exception type only). Logout clears it through `prefs.clear()`; no new backup rule is needed (same file).
*Decision on logout:* reset, consistent with every other plain preference in this DataStore and the existing spec wording; a sort is cheap to choose again and the next account may be a different server. *Alternatives:* `app_preferences` DataStore (survives logout but is a second home for user prefs and would need its own backup exclusion check) — rejected; `SharedPreferences` — rejected (project uses DataStore).

### 5. `d:getlastmodified` only in the album-folder listing
Add `EntryField.LAST_MODIFIED(DAV_NS, "getlastmodified")` and `WebDavEntry.lastModified: String?` (raw, trimmed, blank → null). It is captured under the existing per-entry bounds (`maxTextLength`, `maxElementsPerEntry`, `maxEntryDepth`, `maxTokenBytes`) — no new limit and no change to the document bounds. `listAlbums()` uses a new `ALBUMS_PROPFIND_BODY` (current props + `d:getlastmodified`); `listPhotos()` keeps `PROPFIND_BODY` byte-for-byte, so the photo listing's byte budget (32 MiB / 20,000 photos) is unaffected. `executePropfind` takes the body as a parameter.
Parsing: `parseWebDavDate(raw): Long?` (epoch millis) with `DateTimeFormatter.RFC_1123_DATE_TIME` (RFC 4918 §15.7 mandates rfc1123-date; the formatter's month/day names are fixed English, so it is locale-independent), wrapped in `runCatching` → `null`; values longer than 64 characters are not parsed (`null`). `RemoteAlbum` gains `lastModified: Long? = null` (default keeps existing call sites and tests compiling).
*Alternative:* request the property in the shared body — rejected, it would add ~60 bytes per photo to the bounded photo listing for no use.

### 6. Rename keeps `lastModified`; list stays in server order
`AlbumsRepository.renameAlbum` returns `album.copy(displayName = trimmed, href = buildAlbumHref(newSegments))`, so the date is carried over (a MOVE may or may not touch the folder mtime depending on the server; the next Refresh shows the server value). `AlbumsState.albums` keeps server order and the existing in-place replacement / overwrite de-duplication; only the screen's derived list is sorted, so the existing rename/delete logic and tests stay valid.

### 7. UI
- **Search field:** an `OutlinedTextField` pinned above the grid (not scrolling with it), `singleLine`, label `album_search_label`, leading search icon (`contentDescription = null`), trailing `IconButton` with `cd_clear_album_search` only when non-empty, `ImeAction.Search` hides the keyboard. Shown whenever the album list or the no-match message is shown; hidden in loading / busy / error / no-albums states (as the grid is today).
- **Sort menu:** `IconButton` (`Icons.AutoMirrored.Filled.Sort`) in the `TopAppBar` actions next to Refresh, `contentDescription = cd_sort_albums`, `stateDescription` = label of the current option. `DropdownMenu` with four `DropdownMenuItem`s, each with `Modifier.semantics { role = Role.RadioButton; selected = … }` and a check icon (`contentDescription = null`) for the current option.
- **No match:** `albums_no_match` ("No albums match “%1$s”") in place of the grid; `empty_albums` keeps meaning "server has no albums".
- Grid keys stay `href`; `AlbumCard` callbacks capture the shown `album`, so actions on a filtered/sorted list act on the picked album.

### 8. Nested folder navigation is out of scope
Observed: `listAlbums()` (Depth 1 below the base folder) shows auto-date users only `2026`, `2025`, …; `listPhotos()` (Depth 1, images only via `isImage`) shows such a year folder as an empty album. Supporting `2026 → 05 → 14` would need: (a) a detail listing that returns sub-collections next to photos with its own limits/truncation semantics (modifies `album-photo-listing`, which `show-videos-in-albums` and `download-album-photos` are also changing); (b) a policy for rename/delete deeper than one level (`album-rename` and `albumRelativeSegments()` require exactly one level); (c) several `albums/detail?href=` entries on the back stack, with `sharedAlbumDetailViewModel()` resolving the route *pattern* to the topmost one — workable but every sub-screen / deletion-outcome path would need re-verification; (d) what reviews and search mean for folders. Href hardening is **not** a blocker: a sub-folder href would be validated with `resolveWebDavHref(baseFolderUrl, href)` exactly like an album. Recommended follow-up change (e.g. `nested-album-folders`): make the album detail list sub-folders as navigable cards that push another `albums/detail?href=` entry (route-scoped `AlbumDetailViewModel` per level), keep rename/delete for top-level albums only, and keep reviews on the current level's photos.

## Risks / Trade-offs

- [Folder `getlastmodified` semantics differ by server: Nextcloud propagates child changes up to parents, plain Apache `mod_dav` only updates a directory when its direct entries change] → the spec says "the date the server reports"; README documents it; no client-side recursion.
- [First frame before the DataStore emits uses Name A–Z, the list could re-sort once] → the album listing (network) is far slower than the local DataStore read, so the stored sort is normally known first; no blocking wait added.
- [Very large album lists (theoretically ~100k entries under 32 MiB) make filtering on the main thread costly] → keys precomputed once per list, per keystroke only `contains`; if profiling shows jank, move `visibleAlbums` to `Dispatchers.Default` via `produceState` without spec change.
- [A renamed album "disappears" when its new name does not match the query] → specified explicitly; the query field stays visible with the clear button.
- [Name sort changes the long-standing visible order (server order)] → intentional default, documented in README; users can pick another option.
- [Parallel sibling changes touch `WebDavXmlParser.kt`, `AlbumsRepository.kt`, README and strings] → only additive edits; the photo-listing body stays unchanged; `german-translation` lists the new keys.

## Migration Plan

No data migration: an absent `album_sort` key reads as Name A–Z, `RemoteAlbum.lastModified` defaults to `null`. Rollback = revert; an orphaned `album_sort` key is harmless and cleared at the next logout.

## Open Questions

- Should the last-modified date be shown on album cards (at least while a date sort is active)? Deferrable UI polish; not needed for the requested sorting.
- Should the nested-folder follow-up be prioritized, given that auto-date users currently see empty year albums? Separate change; does not affect this one.
