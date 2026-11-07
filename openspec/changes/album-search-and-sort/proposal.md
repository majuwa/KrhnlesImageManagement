# Proposal

## Why

Feature request: "With many albums (especially auto date folders YYYY/MM/DD) the flat album list is hard to navigate." Today the Albums tab shows every folder below the base folder in the server's PROPFIND order, with no way to find an album by name or to see the most recently changed albums first; users with dozens or hundreds of occasion folders have to scroll and read every card.

## What Changes

- Add a search field to the Albums tab that filters the album list by name while typing: case-insensitive and diacritic-tolerant ("cafe" finds "Café", "strasse" finds "Straße" and "STRAẞE"). A blank query shows all albums; a query without matches shows a dedicated "no albums match" message instead of the empty-albums message. The query survives rotation, process death and switching tabs, and is kept across Refresh, rename and delete. Changing the query or the sort shows the list from its first album.
- Add a sort menu to the Albums top bar with four options: Name A–Z (default), Name Z–A, Last modified (newest first), Last modified (oldest first). Name order is case- and diacritic-insensitive with a deterministic tie-break; albums whose last-modified date is unknown always sort last.
- Read the WebDAV `d:getlastmodified` property of every album folder (it is not requested or parsed today) in the album-folder listing only, parsed as an RFC 1123 date; an absent, malformed or oversized value means "unknown". The photo listing request is unchanged.
- Remember the chosen sort as a plain, non-credential preference: persisted across restarts, reset to Name A–Z on logout (also in an Albums tab that stayed open), not restored from backup or device transfer (same lifecycle as Wi-Fi only, auto date folders and blur sensitivity).
- A renamed album keeps its last-modified value and is shown at the position the current sort and filter give it (until now it stayed at its old position in the unsorted server order).
- Filtering and sorting are pure functions with unit tests; the search field and sort menu are accessible (labels, clear action, radio-button semantics with selected state); all texts are string resources.
- Album delete, rename (incl. its guards), Refresh (still ignored while a rename runs), album opening and all album sub-screens are otherwise unchanged.

**Out of scope — nested folder navigation.** Auto date uploads create `<base>/YYYY/MM/DD/`; `listAlbums()` lists only the direct children of the base folder (`Depth: 1`), so these users see only year folders (`2026`), and opening one shows "No photos" because `listPhotos()` keeps image entries only and drops sub-collections. Browsing into sub-folders does not fit cleanly into this change: it needs a new listing contract for album detail (sub-folders next to photos, their own limits and truncation rules), a decision on rename / delete for folders more than one level below the base folder (the rename spec and `albumRelativeSegments()` require exactly one level), multiple `albums/detail` entries on the back stack sharing the route pattern that `sharedAlbumDetailViewModel()` resolves, review semantics for folders, and it overlaps the photo-listing work of the sibling changes `show-videos-in-albums` and `download-album-photos`. The href hardening itself would not block it (sub-folder hrefs are validated with `resolveWebDavHref` below the base folder exactly like albums). It is recommended as a separate follow-up change; see design.md.

## Capabilities

### New Capabilities
- `album-list-browsing`: Finding albums in the Albums tab — name search, sort options, ordering rules for names and last-modified dates, empty-result message and accessibility of these controls.

### Modified Capabilities
- `album-rename`: "The album list updates in place" — the renamed entry still replaces the old one in the loaded list without a reload, but it is displayed at the position given by the current sort and filter and keeps its last-modified value.
- `credential-storage`: adds the lifecycle of the album sort preference (plain preference in the credentials DataStore, no `credentials_version` bump, reset on logout, not restored on a new device, never re-emits or re-decrypts the credential flows).

## Impact

- **Code:** `model/RemoteAlbum.kt` (+ `lastModified`), new `model/AlbumSort.kt`; `data/WebDavXmlParser.kt` (`WebDavEntry.lastModified`, captured `d:getlastmodified` under the existing `PropfindEntryLimits`), `data/AlbumsRepository.kt` (album-listing PROPFIND body with `d:getlastmodified`, date parsing, rename keeps `lastModified`); `data/Repositories.kt` / `data/CredentialStore.kt` (`albumSort` flow + `saveAlbumSort`); `ui/albums/AlbumsViewModel.kt` (sort state), new `ui/albums/AlbumListOrdering.kt` (pure search / sort functions), `ui/albums/AlbumsScreen.kt` (search field, sort menu, no-match state); `res/values/strings.xml`.
- **Tests:** new pure-function tests, parser / repository / credential-store / view-model / Robolectric screen tests; `fakes/FakeCredentialRepository.kt` and the full `CredentialRepository` implementation `TrackingCredentialRepository` in `SettingsViewModelTest` gain the new members; `FakeAlbumsRepository` needs no change (its rename already copies the album). The existing rename tests in `ui/AlbumsScreenTest` find the dialog field with a bare `hasSetTextAction()` and must be narrowed to the dialog once the screen has a search field.
- **Docs:** `README.md`, `CLAUDE.md`, `docs/architecture.md`, `docs/requirements.md`.
- **No** new dependency, permission, network request, backup rule or DataStore file; the album-folder PROPFIND asks for one more property (a few dozen bytes per album, still under the 32 MiB cap).
- **Sibling changes:** `occasion-folder-suggestions`, `pin-self-signed-certificate` and `nextcloud-chunked-upload` are implemented and archived; this change is rebased on them (`AlbumsRepository(config, pin)`, `AlbumsViewModel(Application, credentialRepo, trustedCertificates, repoFactory)`, `AlbumsRepositoryContract.folderNameOf()` unchanged). `OccasionSuggestionsViewModel` consumes `listAlbums()` and keeps its own case-only `suggestionKey()` — it dedupes real folder names, where "Cafe" and "Café" are different folders, so it is not switched to the diacritic-folding key; `show-videos-in-albums` / `download-album-photos` change the photo listing, which this change leaves untouched (only `WebDavXmlParser.kt` may textually conflict); `german-translation` (implemented last) must translate the new strings; `readme-roadmap-cleanup` edits the same README section; `new-photos-reminder` may add another plain preference to the same DataStore with the same lifecycle. No interaction with `cancel-running-upload`.
