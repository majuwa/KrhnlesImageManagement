# Proposal

## Why

`AlbumsViewModel` contains three copies of the same photo-deletion loop — `deletePhotos()` (duplicate review), `deleteBlurryPhotos()` (blur review) and `deleteSelectedPhotos()` (album selection mode) — so every fix to deletion has to be made three times and the flows can drift apart (GitHub issues #28 and its duplicate #47). The planned split of `AlbumsViewModel` by responsibility (#29 / #48) needs the deletion logic in one reusable place first.

While characterizing the three paths, and in the code and security review of the first draft, several shared defects surfaced:

- All three paths removed *every* requested photo from the album detail list, including photos whose server `DELETE` failed.
- A `DELETE` answered with `404` / `410` (photo already deleted elsewhere) counted as a failure, so the photo stayed stuck in the list.
- The duplicate and blur review showed their failure snackbar in a `rememberCoroutineScope` and navigated back immediately — the scope was cancelled and the message never appeared.
- An exception during deletion (e.g. an invalid server URL in `getRepo()`) escaped `viewModelScope` and crashed the app.
- `onComplete` callbacks captured screen lambdas: pressing Back while deleting and then reopening the review could pop the wrong screen or overwrite a new scan with `Idle`; nothing prevented a second deletion from starting while one was running.
- **Security (pre-existing, fixed as defence in depth):** `AlbumsRepository` built request URLs as `"$origin${href}"` from unvalidated PROPFIND hrefs, and its Basic-auth interceptor attached credentials to *any* host. An href such as `@attacker.example/x` yields `https://host@attacker.example/x` (credentials sent to another host); `/remote.php/dav/files/user/Other/x.jpg` deletes outside the configured base folder.

## What Changes

- Extract the deletion loop (delete each photo via the repository, count failures, collect the hrefs that were actually deleted) into one reusable, ViewModel-independent helper `AlbumsRepositoryContract.deletePhotosIndividually()` in `data/PhotoDeletionResult.kt`, next to the repository contract.
- One private ViewModel helper `launchPhotoDeletion()` owns the orchestration (re-entry guard, busy-state start/finish, detail-state update, result publication); the three public functions only supply their busy-state transitions.
- **Bug fix:** photos whose deletion failed stay in the album detail list (with their thumbnail); only successfully deleted photos are removed.
- **Bug fix:** `DELETE` of a photo answered with `404 Not Found` or `410 Gone` counts as deleted. Other non-2xx statuses fail with the typed `WebDavHttpException(code)`.
- **Bug fix / API change:** the deletion outcome is no longer delivered through an `onComplete` callback. The ViewModel publishes a one-shot `photoDeletionOutcome` (`PhotoDeletionOutcome(failureCount)`) which the album detail screen consumes and shows as a snackbar (`R.plurals.snackbar_delete_failures`). The review screens navigate back once their own deletion has finished and block Back while deleting.
- **Bug fix:** any non-cancellation failure during deletion becomes a result (every photo counted as failed) instead of crashing; cancellation stops the loop and is not counted as a failure.
- **Re-entry guard:** while a photo deletion runs, further deletion requests from any entry point are ignored (the delete functions return `false`).
- **Security hardening:** server-supplied hrefs are resolved with `HttpUrl.resolve()` and only accepted if they have the same scheme/host/port as the configured server, lie strictly below the configured base folder (photos: below their album when listed), contain no `.` / `..` segments (also percent-encoded), no encoded `/` or `\`, no query/fragment. Unsafe hrefs are skipped in listings and refused for DELETE/MOVE/thumbnail URLs without sending a request. The `Authorization` header of the albums client and of the Coil image loader is only attached to requests for the configured origin.

## Capabilities

### New Capabilities
- `album-photo-deletion`: deleting photos from a remote album through the album selection mode, the duplicate review and the blur review — failure counting, already-deleted photos, which photos leave the album list, busy state, outcome messaging, crash-free failure and the re-entry guard.
- `webdav-href-validation`: validation of server-supplied WebDAV hrefs (origin, base folder, traversal) and restriction of credentials to the configured origin.

### Modified Capabilities
<!-- none -->

## Impact

- Code: `ui/albums/AlbumsViewModel.kt` (refactor, outcome state, guard), new `data/PhotoDeletionResult.kt`, new `data/WebDavHref.kt`, `data/AlbumsRepository.kt` (href validation, typed DELETE status, 404/410, origin-restricted auth), `KrhnlesApplication.kt` (origin-restricted Coil auth), `ui/albums/AlbumDetailScreen.kt`, `DuplicateReviewScreen.kt`, `BlurReviewScreen.kt` (outcome / navigation via state).
- Public ViewModel API: `deletePhotos`, `deleteBlurryPhotos`, `deleteSelectedPhotos` lose the `onComplete` parameter and return `Boolean` (started); new `photoDeletionOutcome` / `consumePhotoDeletionOutcome()`.
- Tests: parameterized `AlbumsViewModelPhotoDeletionTest` (all three entry points), `PhotoDeletionTest`, new href/DELETE/auth tests in `AlbumsRepositoryTest`, new `ui/PhotoDeletionOutcomeUiTest`; redundant `deletePhotos*` tests removed from `AlbumsViewModelTest`; `FakeAlbumsRepository` gains per-photo results, a suspending `onDeletePhoto` hook and a bitmap factory.
- Docs: `docs/architecture.md`, `CLAUDE.md` (rule 8, security conventions), `README.md`.
- No dependency or data-format changes.
