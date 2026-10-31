# Design

## Context

See proposal.md (Why) for motivation and `specs/occasion-folder-suggestions/spec.md` for the behaviour contract.

Current state that shapes the approach:

- `OccasionDialog` (`ui/share/`) is a stateless-ish `AlertDialog` with a `rememberSaveable` `String` field, inline validation via `isValidOccasionName`, and is used by `PhotoGridScreen` (state `showOccasionDialog` in `PhotoGridViewModel`) and by `ShareReceiverScreen` (`ShareUiState.Ready`, host `ShareRoute` in `MainActivity`'s share mode).
- `UploadHistoryStore` exposes `entries: Flow<List<UploadHistoryEntry>>` (newest first, max 500, corruption → empty). `UploadHistoryEntry.occasionName` is the worker's `UploadQueue.historyLabel`: the occasion folder for `UploadBatch.Occasion` (capped at 1000 chars), the auto-date label for `UploadBatch.PerPhoto` (`2026/05/14` or `2026/04/30 – 2026/05/01`, capped at 200 chars), or `""`. The entry has **no** kind field.
- Every auto-date label is built from `dayFolderName()` and therefore always contains `/`, which `isValidOccasionName` rejects. Old `YYYY/MM-Mon` occasion-shaped folders also contain `/`.
- `AlbumsRepositoryProvider` (per owner, `Result`-returning `get()`, re-reads config on each call, rebuilds/closes on change, mutex with timeout) and `AlbumsRepository.listAlbums()` (bounded streamed PROPFIND Depth 1, `MAX_PROPFIND_RESPONSE_BYTES`, `ListingTooLarge`, `executeCancellable`, safe hrefs below the base folder) already implement every listing rule the feature needs.
- `RemoteAlbum.displayName` is `d:displayname`, falling back to the **still percent-encoded** last href segment. Uploads use the typed name as a path segment, so `displayName` is not a safe source for a folder name. `AlbumsRepository.albumRelativeSegments(album)` already validates "exactly one level below the base folder, no blank segment" and yields decoded segments.

## Goals / Non-Goals

**Goals:**
- Pure, unit-testable ranking (recent extraction, merge, filter) independent of Android.
- One owner of suggestion loading per host scope, following the CLAUDE.md ViewModel rules (default factory, no cross-ViewModel references, one-shot `first()` reads, guarded job with timeouts, `ensureActive()` before publishing).
- Zero impact on dialog responsiveness and on existing validation.

**Non-Goals:**
- No change to the upload-history format, the queue format, the worker or `RemoteAlbum`.
- No fuzzy matching (e.g. "Summer-2026" ≈ "Summer 2026"); only case-insensitive substring filtering. Showing both near-duplicates lets the user pick the existing one.
- No suggestions for sub-folders deeper than one level, no remote modification-time ranking (could follow `album-search-and-sort`).
- No suggestion management (deleting a suggestion); clearing the upload history removes recent suggestions.

## Decisions

### D1 — Exclude auto-date batches by validity, not by a new history field
Recent names are kept only if `isValidOccasionName(name.trim())` and `!exceedsAlbumNameLimit(name)`. Because every auto-date label contains `/`, this excludes all per-photo batches (current and already stored), blank labels (uploads into the base folder), legacy `YYYY/MM-Mon` folders and capped/truncated over-long labels — without touching the history data format.
- *Alternative:* add an optional `kind` to `UploadHistoryEntry` / the stored JSON. Rejected: modifies the `upload-history` capability and store, and still needs the validity fallback for existing entries.
- *Guard:* a unit test asserts that `resolveAutoDateUploadBatch(...).label` (single day and range) is never a valid occasion name, so a future label format change cannot leak into suggestions unnoticed.

### D2 — Pure functions in `ui/share/OccasionSuggestions.kt`
- `recentOccasionNames(entries: List<UploadHistoryEntry>): List<String>` — sort by `timestampMillis` desc, then `id` desc (does not rely on the store's order), trim, D1 filter, `distinctBy { it.lowercase(Locale.ROOT) }`.
- `mergeOccasionCandidates(recent: List<String>, remote: List<String>): List<String>` — remote names deduped case-insensitively and sorted `CASE_INSENSITIVE_ORDER` then natural (deterministic); each recent name is replaced in place by the remote spelling with the same key; remaining remote names are appended.
- `filterOccasionSuggestions(candidates: List<String>, query: String, limit: Int = MAX_OCCASION_SUGGESTIONS): List<String>` — blank query → `take(limit)`; else keep `contains` (case-insensitive, `Locale.ROOT`), drop `== query.trim()`, stable partition prefix-first, `take(limit)`. `MAX_OCCASION_SUGGESTIONS = 5`.
- `Locale.ROOT` lower-casing avoids Turkish-locale surprises and matches the existing `Locale.ROOT` convention for folder names.

### D3 — `AlbumsRepositoryContract.folderNameOf(album: RemoteAlbum): String?`
Non-suspending, side-effect free. `AlbumsRepository` implements it as `runCatching { albumRelativeSegments(album).last() }` + `isValidOccasionName` + `!exceedsAlbumNameLimit`, so the decoded segment, the depth check and `requireSafeHref` are reused verbatim. `FakeAlbumsRepository` gets a configurable implementation.
- *Alternative A:* use `displayName`. Rejected: server text that may differ from the path and is percent-encoded in the fallback (suggesting `Summer%202026` would create a new folder).
- *Alternative B:* decode the href in the ViewModel. Rejected: duplicates href/depth validation that already exists in the repository (CLAUDE.md: `resolveWebDavHref` rules live in the data layer).
- *Alternative C:* add a `folderName` to `RemoteAlbum`. Rejected: widens a model shared with album screens and the sibling `album-search-and-sort` change.

### D4 — `OccasionSuggestionsViewModel` (`ui/share/`)
`AndroidViewModel` with `@JvmOverloads constructor(application, credentialRepo = CredentialStore(application), history: UploadHistoryRepository = UploadHistoryStore(application), repoFactory = { AlbumsRepository(it) }, backgroundDispatcher = Dispatchers.Default, clock = SystemClock::elapsedRealtime, timeouts…)`; it owns `private val repos = AlbumsRepositoryProvider(credentialRepo, repoFactory = repoFactory)` and calls `repos.close()` in `onCleared()`. Added to `ViewModelFactoryRegressionTest`.

State: `candidates: StateFlow<List<String>>` (merged, query-independent). Internally it keeps `recent: List<String>`, `remote: List<String>` and `remoteSource: AlbumsRepositoryContract?` (the repository instance the remote list came from) plus `remoteLoadedAt`.

`load()` — guarded one-shot (`loadJob` ignored while active; never needs releasing beyond the timeouts below), increments a generation:
1. **Recent:** `withTimeoutOrNull(HISTORY_READ_TIMEOUT_MS = 3 s) { history.entries.first() }` inside `runCatching` rethrowing `CancellationException`; fallback empty. Compute `recentOccasionNames` on `backgroundDispatcher`, `ensureActive()` + generation check, publish.
2. **Remote**, entirely inside `withTimeoutOrNull(REMOTE_SUGGESTIONS_TIMEOUT_MS = 10 s)`:
   - `repos.get()`; failure (`NOT_CONFIGURED`, `INVALID_CONFIGURATION`, `TIMEOUT`, other) → clear remote, publish, stop. No request is sent.
   - If the returned instance `!==` `remoteSource` → clear remote immediately and publish (old server's names disappear before anything else happens). The provider rebuilds the instance on every config change (server, user, password version, base folder), so identity equals "same configuration".
   - If same instance and `clock() - remoteLoadedAt < REMOTE_SUGGESTIONS_MAX_AGE_MS (60 s)` → keep, stop (rotation / quick reopen send no second PROPFIND).
   - `repo.listAlbums()`; failure → keep what is published, log `javaClass.simpleName` only.
   - Success → `mapNotNull(repo::folderNameOf)`, then re-ask `repos.get()` and publish only if it returns the same instance (same pattern as `scanAlbumPhotos()`'s `ConfigurationChanged` check), `ensureActive()` + generation check.
   - Timeout → the `withTimeoutOrNull` cancels the cancellable listing (`executeCancellable`); published state unchanged.
3. Every non-cancellation failure becomes "no remote suggestions"; nothing reaches the UI as text.

The ViewModel holds no reference to `PhotoGridViewModel` / `ShareViewModel` and vice versa; hosts combine them in composition.
- *Alternative:* put suggestions into `PhotoGridViewModel` and `ShareViewModel`. Rejected: duplicated logic, both would need an `AlbumsRepositoryProvider`, and `ShareViewModel` is already large.
- *Alternative:* observe `history.entries` for the ViewModel's lifetime. Rejected: one-shot snapshot per dialog opening is enough and follows CLAUDE.md rule 6.

### D5 — Scoping and triggering
- **Grid:** `composable(ROUTE_PHOTOS)` passes `suggestionsViewModel: OccasionSuggestionsViewModel = viewModel()` (photos back-stack entry) to `PhotoGridScreen` as a defaulted parameter (same pattern as `removalViewModel`). Inside `if (uiState.showOccasionDialog)` a `LaunchedEffect(Unit) { suggestionsViewModel.load() }` starts loading; candidates are collected with `collectAsStateWithLifecycle()` and passed to `OccasionDialog`.
- **Share:** `ShareRoute` gains `suggestionsViewModel: OccasionSuggestionsViewModel = viewModel()` (activity scope, survives rotation). `LaunchedEffect(state is ShareUiState.Ready)` calls `load()` when the dialog appears; `ShareReceiverScreen` stays stateless and receives `occasionSuggestions: List<String> = emptyList()`.
- *Alternative:* prefetch during `Preparing`. Rejected for now: sends a request even when the user cancels while the share is read; loading at dialog open is fast enough because recent suggestions are local.

### D6 — Dialog UI and state
- `OccasionDialog(message, onDismiss, onConfirm, confirmEnabled = true, suggestions: List<String> = emptyList())` — the default keeps every existing call site and test valid.
- Field state becomes `rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }` so a tap can set `TextFieldValue(name, TextRange(name.length))` (cursor at end) and the text survives rotation / process recreation as today. Validation and `onConfirm(text.trim())` use `.text` unchanged.
- `val shown = remember(suggestions, field.text) { filterOccasionSuggestions(suggestions, field.text) }`.
- When `shown` is non-empty: a heading `Text(stringResource(R.string.occasion_suggestions_heading))` and a `FlowRow` of `SuggestionChip`s (label `Text(name, maxLines = 1, overflow = Ellipsis)`; semantics keep the full text). Each chip: `Modifier.semantics { role = Role.Button; onClick(label = useAsFolderLabel) { select(name); true } }` — the label wraps the real callback (CLAUDE.md rule 9). No icons (or `contentDescription = null`). The dialog `text` column becomes `verticalScroll` so field, error and chips stay reachable with the keyboard open on small screens.
- New strings: `occasion_suggestions_heading` ("Suggestions"), `occasion_suggestion_use_label` ("Use as folder name").
- Ordering stability: recent suggestions are published first; remote names are merged later. Because recent names keep their rank (D2) and remote ones are appended, the visible list only grows in place while the field is empty; while filtering, a prefix match may move ahead.

### D7 — Testing approach
- Pure tests run as plain JUnit; the ViewModel test runs under Robolectric (it is an `AndroidViewModel`) with `StandardTestDispatcher`/`runTest`, `FakeCredentialRepository`, a fake `UploadHistoryRepository` and `FakeAlbumsRepository` (with a suspending `onListAlbums` hook for timeout / config-change cases).
- Compose tests (Robolectric) pass candidates directly to `OccasionDialog` / `ShareReceiverScreen`; grid tests construct `OccasionSuggestionsViewModel` with fakes and `backgroundDispatcher = Dispatchers.Unconfined` (CLAUDE.md: `waitUntil {}` never drains the paused main looper).

## Risks / Trade-offs

- [Recent history may contain folders of a previously configured server (history is not cleared on logout and has no server field)] → Accepted: names are the user's own; tapping only fills the field. Documented in README.
- [A remote listing is sent from the share target before the user confirms] → Only to the already configured server with the existing same-origin auth rules; at most once per 60 s per configuration; not gated by Wi-Fi-only (same as the Albums tab; one small PROPFIND).
- [Large album counts (thousands)] → Listing is already bounded (32 MiB / `ListingTooLarge` → ignored); filtering is linear over ≤ 500 + N short strings per keystroke, memoized by `remember`.
- [Suggestion list changes under the user's finger when remote results arrive] → Recent ranks are stable and remote names append; results normally arrive within ~1 s of opening.
- [Inferring "auto-date" from `/` breaks if the label format changes] → D1 regression test pins it.
- [Changing the saved field state from `String` to `TextFieldValue`] → Saved state only exists while a dialog is open within one app version; a `StateRestorationTester` test covers the new state.
- [`SuggestionChip` already merges its own click semantics; an outer `semantics { onClick }` could add a second action] → The Compose test asserts exactly one click action with the localized label and role Button; if the chip cannot carry it, use a `TextButton`-style composable with `Modifier.clickable(onClickLabel = …, role = Role.Button)` instead (same spec).
- [Merge conflict with `album-search-and-sort` on `AlbumsRepositoryContract` / `FakeAlbumsRepository`] → This change only adds one method; no listing or model change.

## Migration Plan

No data migration. The feature is additive; rollback is removing the suggestion parameter and ViewModel. Existing `OccasionDialog` callers compile unchanged thanks to the defaulted parameter.

## Open Questions

- Should a later iteration rank remote albums by last modification (once `album-search-and-sort` exposes it) instead of alphabetically? Does not affect this change's specs or tasks.
- Should suggestions be prefetched while the share is being read (`Preparing`) if users report the remote part arriving late? Can be tuned without spec changes.
