# Design

## Context

`AlbumsViewModel` (`ui/albums/AlbumsViewModel.kt`) exposed three deletion entry points that differed only in the busy state they drive:

| Entry point | Caller | Busy state while running | Empty list |
|---|---|---|---|
| `deletePhotos()` | `DuplicateReviewScreen` | `duplicatesState = Deleting` → `Idle` | sets `Idle`, `onComplete(0)` |
| `deleteBlurryPhotos()` | `BlurReviewScreen` | `blurState = Deleting` → `Idle` | sets `Idle`, `onComplete(0)` |
| `deleteSelectedPhotos()` | `AlbumDetailScreen` | `isDeletingPhotos = true` → `false` | `onComplete(0)` only |

Each copy: `getRepo()`, sequential `repo.deletePhoto(photo)`, counts failures, removes **all** requested hrefs via `AlbumDetailState.withPhotosDeleted()`, resets its busy state, then calls `onComplete(failures)`; the review screens showed the failure snackbar from a `rememberCoroutineScope` and navigated back in that callback.

`AlbumsRepository` built every URL as `"$origin${href}"` from the PROPFIND `d:href`, and its OkHttp interceptor added `Authorization: Basic …` to every request regardless of host.

Issue #29 / #48 will later split `AlbumsViewModel` into per-responsibility ViewModels (album list, detail, duplicates, blur). The deletion logic must therefore not be bound to `AlbumsViewModel` internals.

## Goals / Non-Goals

**Goals:**
- One implementation of the deletion loop, testable without a ViewModel, in the `data` package.
- One implementation of the orchestration inside the ViewModel, with a re-entry guard and crash-free failure handling.
- Deletion outcome that reliably reaches the user after the review screens navigate back.
- Server-supplied hrefs can neither redirect credentials to another host nor act outside the configured base folder.

**Non-Goals:**
- Splitting `AlbumsViewModel` (#29 / #48).
- Parallel deletion or automatic retries.
- Treating `404` on album deletion as success (album deletion is a separate, rarer flow; unchanged).

## Decisions

### D1: Two layers — a ViewModel-independent deletion function plus a private ViewModel orchestrator
- `data/PhotoDeletionResult.kt`: `internal data class PhotoDeletionResult(deletedHrefs, failureCount)` (+ `allFailed(photos)`) and `internal suspend fun AlbumsRepositoryContract.deletePhotosIndividually(photos)`. It lives next to `AlbumsRepositoryContract` and only talks to it, so the future split ViewModels (#29) can call it directly.
- `AlbumsViewModel.launchPhotoDeletion(toDelete, onStart, onFinish): Boolean` (private) owns guard, launch, detail update, busy reset and outcome publication. The three public functions are one-line delegations that pass their busy-state lambdas.

*Alternatives considered:* a private `suspend fun performDeletion(): Int` in the ViewModel (would have to move again in #29); a `DeletePhotosUseCase` class (no DI/domain layer yet; a top-level extension is equally testable).

### D2: Remove only successfully deleted photos from the detail state
`PhotoDeletionResult.deletedHrefs` contains only successful deletions; only those are removed from `AlbumDetailState` (photos and thumbnails).

### D3: Failures become results; cancellation is not a failure; busy state reset in `finally`
- `deletePhotosIndividually()` wraps every `deletePhoto()` in `runCatching { … .getOrThrow() }`: a failed `Result` *or* a thrown exception counts as one failure and the loop continues. A `CancellationException` (thrown or returned) is rethrown; `ensureActive()` before every photo stops the loop promptly after cancellation.
- `launchPhotoDeletion()` wraps the whole deletion (including `getRepo()`, which can throw `IllegalArgumentException` for an invalid server URL) in `runCatching`, rethrows `CancellationException` and maps every other failure to `PhotoDeletionResult.allFailed(toDelete)` (logging only the exception class). This follows the established `SettingsViewModel.runConnectionTest()` pattern and avoids catching generic `Exception` (detekt).
- `onFinish()` runs in `finally`, so the busy state is always cleared, also on cancellation. A cancelled deletion publishes no outcome.

### D4: Outcome as one-shot ViewModel state, consumed by the album detail screen
`photoDeletionOutcome: StateFlow<PhotoDeletionOutcome?>` is set after `onFinish()`; `consumePhotoDeletionOutcome()` clears it. `AlbumDetailScreen` collects it in a `LaunchedEffect(viewModel)` (`filterNotNull().collect`), consumes it first (shown at most once), clears the selection and shows `R.plurals.snackbar_delete_failures` via `photoDeletionFailureMessage(resources, outcome)` when `failureCount > 0`. Collecting instead of keying the effect on the value matters: consuming the value must not cancel the running `showSnackbar`.

The detail screen is the screen visible after the duplicate/blur review navigates back, and it outlives the review, so the message is no longer lost with the review's composition scope. Because the outcome is state (not a callback holding screen lambdas), nothing fires into a screen that has already left.

*Alternative considered:* a `Channel`/`SharedFlow` event — rejected: an event emitted while the detail screen is not composed (review on top) would be dropped or need replay configuration; a nullable `StateFlow` with explicit consumption keeps it until shown.

### D5: Review navigation driven by state; Back blocked while deleting
`DuplicateReviewScreen` / `BlurReviewScreen` remember (`rememberSaveable`) that *they* started a deletion and navigate back once their state is no longer `Deleting`. `BackHandler(enabled = isDeleting)` swallows Back and the top-bar back button is disabled while deleting, so the user cannot leave mid-deletion (the deletion is short and bounded by the OkHttp timeouts). `onFinish` for the review paths uses `compareAndSet(Deleting, Idle)`, so a finished deletion never overwrites a scan that was started meanwhile.

### D6: Re-entry guard
`launchPhotoDeletion()` keeps `photoDeletionJob` and returns `false` without doing anything while it is active (for any entry point — all of them mutate the same album). The guard is released when the job completes, which is guaranteed: every request is bounded by the OkHttp connect (30 s) and read (60 s) timeouts, every failure becomes a result and `onFinish` runs in `finally`. No overall timeout is applied because the duration legitimately scales with the number of photos. The review screens only start waiting for navigation if the call returned `true`.

### D7: Empty list
`onStart()` and `onFinish()` run synchronously and the outcome `PhotoDeletionOutcome(0)` is published without launching a coroutine or making a request.

### D8: `404` / `410` on photo `DELETE` mean "already deleted"
`AlbumsRepository.deletePhoto()` treats `404 Not Found` and `410 Gone` as success (the photo was deleted elsewhere — exactly what the user wants), so it leaves the list instead of being stuck. Other non-2xx statuses fail with `WebDavHttpException(code)` (typed, branch on `code`). `deleteAlbum()` keeps failing on `404`.

### D9: Href validation (`data/WebDavHref.kt`)
`resolveWebDavHref(root, href, allowRoot = false): HttpUrl?`:
1. Refuse blank hrefs, `?`, `#`, `\` and control characters.
2. Percent-decode every `/`-separated piece of the **raw** href and refuse `.`, `..`, or pieces containing `/`, `\` or control characters. This must run before `HttpUrl.resolve()`, because OkHttp silently normalizes dot segments (also `%2e%2e`) away, which would hide the attempt.
3. `root.resolve(href)`; refuse unless scheme, host and port equal `root` (`@attacker.example/x` resolves *relative* to the root and then fails the base-folder check; `//attacker.example/…` and absolute cross-origin URLs fail the origin check).
4. Refuse unless the decoded path segments start with the root's and are strictly longer (the root itself is refused unless `allowRoot`).

`AlbumsRepository` uses `baseFolderUrl` (config URL + base folder) as root for album and photo hrefs (`deletePhoto`, `deleteAlbum`, `renameAlbum`, `listPhotos(albumHref)`, direct image URLs) and the album URL as root for the photos of a listing. Listings skip unsafe entries (logging only the count); DELETE / MOVE / PROPFIND for an unsafe href fail with `UnsafeHrefException` without any request; image URLs fall back to `""`. The href itself is never logged. Real Nextcloud hrefs (absolute, percent-encoded paths such as `/remote.php/dav/files/user/Photos/Summer%202025/IMG%20%C3%A4.jpg`) and absolute same-origin URLs keep working.

### D10: Credentials only for the configured origin
The `AlbumsRepository` interceptor and the Coil image-loader interceptor (`KrhnlesApplication`) only add `Authorization` when `request.url.hasSameOriginAs(configured URL)`. Application interceptors run once per call, and OkHttp drops `Authorization` on cross-host redirects itself, so redirects do not re-add credentials.

### D11: Test strategy
- `AlbumsViewModelPhotoDeletionTest` (`ParameterizedRobolectricTestRunner` over `DeletePath`) runs the same contract for all three entry points: success, partial / all failure, failed photos stay, empty list, busy state set synchronously and cleared before the outcome is published (unconfined collector), busy while suspended, throwing repository, throwing repository factory (no crash), cancellation (loop stops, no outcome, not a failure), re-entry ignored (one `DELETE` per photo, guard released afterwards), outcome consumption.
- `PhotoDeletionTest` covers the extracted function (incl. thrown exception, returned cancellation, cancellation of the caller, `allFailed`).
- `AlbumsRepositoryTest` covers `404`/`410` as success, typed status on `403`/`500`, `404` on album delete still failing, encoded Nextcloud hrefs, absolute same-origin hrefs, and refusal without any request of `@host`, scheme-relative and absolute cross-origin hrefs, another port, `/../`, traversal that stays inside, `%2e%2e`, encoded slash, backslash, query, paths outside the base folder / of another user, the base folder itself; listings skip unsafe entries; `Authorization` sent to the configured origin and not to another origin.
- `ui/PhotoDeletionOutcomeUiTest` (Compose + Robolectric) covers the plural message, the snackbar on the detail screen for an outcome produced while it was not visible and for selection mode, and the duplicate review blocking Back while deleting and navigating back exactly once afterwards.
- Redundant `deletePhotos*` tests in `AlbumsViewModelTest` were removed (fully covered by the parameterized test).

## Risks / Trade-offs

- [Behaviour change D2 / D8] → consistent with server state; documented in spec and architecture docs. A `404` caused by a misconfiguration would be reported as deleted, but hrefs come from a listing just made, so `404` means "gone".
- [Back is blocked during deletion] → deletion is short, bounded by timeouts and shows a progress indicator; leaving would otherwise orphan the review's navigation.
- [Stricter hrefs could hide entries from unusual servers] → only hrefs outside the configured folder/origin or with traversal are dropped; same-origin absolute URLs and encoded paths are accepted.
- [`renameAlbum` now requires the album below the base folder (was: below the WebDAV root)] → albums are only listed from the base folder, so this matches real usage.
