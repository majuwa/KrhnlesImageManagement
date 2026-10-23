# Design

## Context

See proposal.md (Why). Current state after commit fc571aa (#28 / #47):

- One `AlbumsViewModel` (`ui/albums/AlbumsViewModel.kt`) is scoped to the Albums tab back-stack entry; `albums/detail`, `albums/viewer`, `albums/duplicates` and `albums/blur` obtain it through `sharedAlbumsViewModel(navController, backStack)` in `KrhnlesApp.kt`, which resolves the Albums entry with `rememberParentEntry()`.
- All three photo-deletion entry points go through the private `launchPhotoDeletion(toDelete, onStart, onFinish)`: re-entry guard on `photoDeletionJob`, `deletePhotosIndividually()`, removal of only the deleted hrefs from `AlbumDetailState`, busy-state reset in `finally` (reviews via `compareAndSet(Deleting, Idle)`), one-shot `photoDeletionOutcome` consumed by `AlbumDetailScreen`.
- Review screens set `awaitingDeletion` when a deletion started, block Back (`BackHandler`) while their state is `Deleting`, and navigate back once it is not.
- `ViewModelFactoryRegressionTest` requires an `(Application)` constructor for every ViewModel created by `AndroidViewModelFactory` (the default `viewModel()` factory); ViewModels use `@JvmOverloads` with defaulted collaborators.

## Goals / Non-Goals

**Goals:**
- One responsibility per ViewModel; each screen depends only on the ViewModels it uses.
- Keep every guarantee of the `album-photo-deletion` spec unchanged (single orchestration, guard shared by all entry points, outcome on the detail screen, Back blocked while deleting, compare-and-set on the review state, failures never crash, cancellation not a failure).
- Identical user-visible behaviour and strings.

**Non-Goals:**
- No dependency-injection framework, no `SavedStateHandle`-based album argument, no process-death restoration improvements for the viewer (unchanged from today).
- No change to scan algorithms, thresholds or repository behaviour.

## Decisions

### D1 — Four ViewModels, one file each
`AlbumsViewModel` (list/rename/delete album, `AlbumsState`), `AlbumDetailViewModel` (`AlbumDetailState`, thumbnails, `fullImageUrl`, `isDeletingPhotos`, `photoDeletionOutcome`, deletion orchestration, `PhotoDeletionOutcome`), `DuplicatesViewModel` (`DuplicatesState`), `BlurViewModel` (`BlurState`), all in `ui/albums/`. The issue's minimal variant (duplicates/blur inside the detail ViewModel) would still leave a ~350-line class with three responsibilities; full extraction is what makes the review flows independently testable.
*Alternative:* plain helper classes composed inside one ViewModel — keeps one big public surface, rejected.

### D2 — Scoping per navigation route
- `albums` → `AlbumsViewModel` on its own entry (`viewModel()`).
- `albums/detail?href={href}` → `AlbumDetailViewModel` on its own entry. `albums/viewer`, `albums/duplicates`, `albums/blur` resolve the detail entry with `rememberParentEntry(navController, backStack, ROUTE_ALBUM_DETAIL_PATTERN)` via a private `sharedAlbumDetailViewModel(navController, backStack)` (replaces `sharedAlbumsViewModel`). `NavController.getBackStackEntry(route)` matches the route *pattern* (navigation 2.10), covered by a new `RememberParentEntryTest` case with an argument-bearing parent route.
- `albums/duplicates` / `albums/blur` → `DuplicatesViewModel` / `BlurViewModel` on their own entry (`viewModel()`), so the scan is cancelled (`viewModelScope`) and its state discarded when the review is popped. Previously a scan kept running in the shared ViewModel after leaving; on re-entry `LaunchedEffect(Unit)` started a scan anyway, so the visible behaviour is the same. `resetDuplicatesState()` / `resetBlurState()` stay (the top-bar back still calls them) and now also cancel a running scan.
- The album list no longer reaches sub-routes; nothing below `albums` used it.
- Found while implementing: the scans' per-photo `catch (e: Exception)` swallowed `CancellationException` (the loop kept going after cancellation) and the outer `catch (e: IllegalStateException)` turned it into an `Error` state (`CancellationException` is an `IllegalStateException`). Harmless while the shared ViewModel was never cleared, but route scoping makes cancellation real, so the scan loop rethrows `CancellationException` — a cancelled scan stops before the next photo and publishes no error (`DuplicatesViewModelTest` / `BlurViewModelTest`).

### D3 — Sharing the detail photo list with the reviews
Reviews receive the album's photos, thumbnail URLs and the detail ViewModel's repository source as **arguments**: `findDuplicates(photos, thumbnailUrls, repositories)` / `findBlurryPhotos(photos, thumbnailUrls, repositories)`, taken from the detail ViewModel by the screen once the album is loaded (see D8). The review ViewModels never hold a reference to another ViewModel and are tested with plain lists and a fake `AlbumsRepositorySource`. Previously the thumbnail URL was re-read from the live detail state per photo; a snapshot is equivalent because the fallback `thumbnailUrl(photo)` computes the same URL.

### D4 — Review deletions keep the single orchestration (and compare-and-set)
`AlbumDetailViewModel` implements a small `PhotoDeletionLauncher` interface (`launchPhotoDeletion(toDelete, onStart, onFinish): Boolean`) — the former private orchestrator, now the public boundary. `DuplicatesViewModel.deletePhotos(toDelete, launcher)` / `BlurViewModel.deleteBlurryPhotos(toDelete, launcher)` pass their own transitions: `onStart = { state = Deleting }`, `onFinish = { state.compareAndSet(Deleting, Idle) }`. Selection mode is `AlbumDetailViewModel.deleteSelectedPhotos(toDelete)` (`isDeletingPhotos`). Hence the guard (`photoDeletionJob`), the detail-list update, the `finally` reset before publishing the outcome and the one-shot outcome are one implementation for all three entry points. The deletion coroutine runs in the detail ViewModel's scope (which outlives the review); the review cannot be left while `Deleting` (BackHandler), and if its ViewModel were cleared anyway the `onFinish` compare-and-set only touches a discarded flow.
*Alternative:* derive the review busy state from a detail-side "active deletion source" flow — removes compare-and-set and the `Deleting` review state the spec names; rejected to keep the deletion contract literally unchanged.

### D5 — Repository provider
`data/AlbumsRepositoryProvider.kt`: `AlbumsRepositoryProvider(credentialRepo, lockTimeoutMs, repoFactory)` implements `AlbumsRepositorySource` with `suspend get(): Result<AlbumsRepositoryContract>`. It never throws (except cancellation): no complete credentials → `AlbumsRepositoryUnavailableException(NOT_CONFIGURED)`, a factory `IllegalArgumentException` → `INVALID_CONFIGURATION`, lock / config read not completed within the timeout (10 s) → `TIMEOUT`. Every `get()` re-reads the current config and rebuilds when it differs from the cached one, so no request is built from outdated credentials; calls are serialized by a `Mutex` acquired with `withTimeoutOrNull` (CLAUDE.md lock rule). `current` (cached instance or `null`) serves the synchronous `fullImageUrl`; `invalidate()` bumps a generation so a build overlapping it is returned but never cached. `AlbumsViewModel.loadAlbums()` no longer needs `invalidate()`. The detail ViewModel exposes its provider as `repositories: AlbumsRepositorySource`, which the reviews use (one OkHttp client per album flow).

### D6 — Constructor / factory compatibility
`AlbumsViewModel` and `AlbumDetailViewModel` stay `AndroidViewModel`s with `@JvmOverloads constructor(application, credentialRepo = CredentialStore(application), repoFactory = { AlbumsRepository(it) })`, so the default `viewModel()` factory keeps working. `DuplicatesViewModel` and `BlurViewModel` do not need the application and are plain `ViewModel`s with `@JvmOverloads` constructors of defaulted collaborators only (`analysisDispatcher`; `BlurViewModel` also `blurScorerFactory: () -> BlurScorer = { BlurAnalyzer() }`), i.e. a no-arg constructor. `BlurScorer` is a two-method interface implemented by `BlurAnalyzer`, which makes the blur scan unit-testable without ML Kit. `ViewModelFactoryRegressionTest` covers the `(Application)` constructors, `PlainViewModelFactoryRegressionTest` the no-arg ones.

### D8 — Review robustness (findings of code and security review)
- **Scans follow the configuration.** `scanAlbumPhotos()` asks `repositories.get()` before every photo and stops with `AlbumsError.ConfigurationChanged` (or the typed failure) when it no longer returns the repository the scan started with. Review ViewModels track `scanJob`; `cancelScan()` (album unavailable), `reset…State()` and a new scan cancel it, results are published after `ensureActive()`. The review screens use `rememberReviewAlbumReady()`: they call `detailViewModel.loadAlbum(albumHref)`, start the scan only when the detail state `isLoaded` (not loading, no error) and call `cancelScan()` on `NotConfigured` / `ConfigurationChanged` (not while `Deleting`; re-evaluated afterwards), showing the album error through `ReviewAlbumGate` (Retry → `reload()`). An unloaded / loading / failed album therefore never yields "none found", and a Retry on an empty list keeps the error (VM ignores `retry…` with no photos).
- **Deletion only touches the loaded album.** `launchPhotoDeletion` does not delete photos that are not in the current `detailState.photos` (review results surviving a config change) and counts them as failed. `onFinish` runs exactly once even when the job is cancelled before it was dispatched (try/finally plus `invokeOnCompletion`; `CoroutineStart.ATOMIC` is a delicate API and not used); the re-entry guard is `photoDeletionJob?.isCompleted == false`.
- **No reload on recreation.** `loadAlbum(href)` is a no-op when that album is already loaded or loading without error; `reload()` is the explicit Retry. `AlbumDetailState.isLoaded` marks a completed load.
- **Analysis off the main thread.** `PhotoAnalyzer` implementations run on an injectable `analysisDispatcher` (default `Dispatchers.Default`; tests inject the test dispatcher).
- **Hardening.** `parsePropfindXml()` keeps an `oc:fileid` only if it matches `[A-Za-z0-9]+` (it is placed in the Memories preview URL); `checkMemories()` logs the exception type only.

### D7 — Tests move with the code
- `AlbumsViewModelTest`: keeps list/rename/delete-album tests.
- `AlbumDetailViewModelTest` (new): `loadAlbum` tests moved from `AlbumsViewModelTest`, `isDeletingPhotos` test, thumbnails, `fullImageUrl`, re-load resets state.
- `AlbumsViewModelPhotoDeletionTest` → `AlbumPhotoDeletionTest`: same parameterized contract over `DUPLICATES`, `BLUR`, `SELECTION`; review paths call the review ViewModel with the detail ViewModel as launcher; the re-entry test covers all three entry points across ViewModels.
- `DuplicatesViewModelTest` / `BlurViewModelTest` (new): scan results, empty input, progress, re-entry while scanning, provided thumbnail URLs are used, error mapping, reset, and "a deletion finishing never overwrites a newer scan" (compare-and-set).
- `PhotoDeletionOutcomeUiTest`: adapted to the two ViewModels.
- `RememberParentEntryTest`: parent route with arguments.
- Review-robustness tests (D8): config change / logout mid-scan, `cancelScan`, reset during scan, Retry on empty list (`DuplicatesViewModelTest` / `BlurViewModelTest`); load no-op / `reload` / cancelled deletion releases busy state (`AlbumDetailViewModelTest`); deletion after config change and foreign photos (`AlbumPhotoDeletionTest`); `ReviewSelectionTest` (`StateRestorationTester`); blur review Back blocking and album error after logout (`PhotoDeletionOutcomeUiTest`); `oc:fileid` validation (`WebDavXmlParserTest`).

## Risks / Trade-offs

- [Each ViewModel creates its own `AlbumsRepository` (own OkHttp client)] → at most one per open screen; previously `loadAlbums()` recreated it anyway. Acceptable.
- [Review ViewModel's `onFinish` lambda is executed by the detail ViewModel] → only touches a `MutableStateFlow`; documented in KDoc, Back is blocked while deleting.
- [`getBackStackEntry` with a pattern route could change semantics in a future navigation release] → pinned by the new `RememberParentEntryTest` case.
- [Scan cancellation on leaving the review] → intended; no visible difference (see D2).

## Migration Plan

Pure refactor inside the app; no data migration. Rollback = revert the change.
