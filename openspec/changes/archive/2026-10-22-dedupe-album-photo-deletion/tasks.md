# Tasks

## 1. Characterize existing behaviour

- [x] 1.1 Add per-photo delete results (`deletePhotoResults` keyed by href) to `FakeAlbumsRepository` and verify existing tests still pass (`./gradlew test`)
- [x] 1.2 Add characterization tests for `deletePhotos`, `deleteBlurryPhotos` and `deleteSelectedPhotos` in the parameterized `AlbumsViewModelPhotoDeletionTest` (success removes photos + thumbnails, failure count, attempts every photo, empty list makes no server call and resets/keeps busy state idle, busy state transitions, busy state cleared before the result) and verify they pass against the unchanged ViewModel

## 2. Extract shared deletion logic

- [x] 2.1 Create `PhotoDeletionResult` and `AlbumsRepositoryContract.deletePhotosIndividually()` and verify with new `PhotoDeletionTest` (all succeed, partial failure, all fail, empty, every photo attempted in order)
- [x] 2.2 Add private `launchPhotoDeletion()` to `AlbumsViewModel` (empty guard, busy start, delete, detail update, busy reset in `finally`) and make the three public delete functions delegate to it; verify all characterization tests from 1.2 stay green
- [x] 2.3 Fix the bug: remove only successfully deleted hrefs from the detail state; update the characterization expectation and add a partial-failure regression test for each of the three entry points (failed photo and its thumbnail remain); verify `./gradlew test`
- [x] 2.4 Document the deletion helper in `docs/architecture.md` (package structure + key decision) and verify the text matches the code

## 3. Review findings

- [x] 3.1 Move `PhotoDeletionResult` / `deletePhotosIndividually()` to `data/PhotoDeletionResult.kt` next to `AlbumsRepositoryContract`; update imports, `PhotoDeletionTest` and CLAUDE.md rule 8
- [x] 3.2 Treat `404` / `410` on photo `DELETE` as deleted, fail other statuses with `WebDavHttpException(code)`; regression tests in `AlbumsRepositoryTest` (404, 410, 403/500 typed, album 404 still fails)
- [x] 3.3 Count exceptions thrown by `deletePhoto()` per photo, rethrow cancellation and `ensureActive()` before every photo; tests in `PhotoDeletionTest` (thrown exception, returned cancellation, cancelled caller, `allFailed`)
- [x] 3.4 Map non-cancellation failures in `launchPhotoDeletion()` (incl. `getRepo()`) to `PhotoDeletionResult.allFailed()` via `runCatching` + rethrow `CancellationException`; replace the test that expected the crash with "throwing repository" / "throwing repository factory" / "cancellation stops the loop and is not reported" tests for all three paths; update design D3
- [x] 3.5 Replace the `onComplete` callbacks with the one-shot `photoDeletionOutcome` state + `consumePhotoDeletionOutcome()`; `AlbumDetailScreen` collects it and shows `R.plurals.snackbar_delete_failures`; review screens navigate back via state, block Back while deleting and use `compareAndSet(Deleting, Idle)`; tests in `AlbumsViewModelPhotoDeletionTest` and `ui/PhotoDeletionOutcomeUiTest` (plural message, snackbar after navigating back, selection-mode snackbar, Back blocked + single navigation)
- [x] 3.6 Add the re-entry guard (`photoDeletionJob`, delete functions return `false` while active); test that a double invocation deletes each photo once and the guard is released afterwards
- [x] 3.7 Make the "busy state is set while deleting" test assert the set half (busy immediately after the call, cleared when the outcome is published)
- [x] 3.8 Rename the shadowing `.also { result -> }` (now `.let { deletion -> }`)
- [x] 3.9 Remove the redundant `deletePhotos*` tests from `AlbumsViewModelTest` (covered by the parameterized test)

## 4. Security hardening (defence in depth)

- [x] 4.1 Add `resolveWebDavHref()` / `hasSameOriginAs()` (`data/WebDavHref.kt`): raw-segment dot/encoded-slash check before `HttpUrl.resolve()`, same scheme/host/port, strictly below the root, no query/fragment/backslash
- [x] 4.2 Use it in `AlbumsRepository` for `listAlbums` (skip unsafe entries), `listPhotos` (album href below the base folder, photos below the album), `deletePhoto`, `deleteAlbum`, `renameAlbum` and direct image URLs; unsafe hrefs fail with `UnsafeHrefException` without a request and are logged without the href
- [x] 4.3 Restrict the `Authorization` header of the `AlbumsRepository` client and of the Coil image loader to the configured origin
- [x] 4.4 Tests in `AlbumsRepositoryTest`: `@host`, scheme-relative and absolute cross-origin hrefs, another port, `/../`, traversal staying inside, `%2e%2e`, encoded slash, backslash, query, outside base folder, other user, base folder itself — all refused with no request; encoded Nextcloud hrefs and absolute same-origin hrefs still work; listings skip unsafe entries; credentials only sent to the configured origin

## 5. Documentation & verification

- [x] 5.1 Update `docs/architecture.md`, `CLAUDE.md` (rule 8, security conventions: href validation + auth origin restriction) and `README.md`
- [x] 5.2 Run `./gradlew spotlessApply spotlessCheck` and verify it passes
- [x] 5.3 Run `./gradlew detekt` and verify no new issues (no suppressions)
- [x] 5.4 Run `./gradlew lintDebug` and verify zero issues
- [x] 5.5 Run `./gradlew test jacocoTestReport` and verify all tests pass and the report is generated
- [x] 5.6 `openspec validate dedupe-album-photo-deletion --strict` passes
