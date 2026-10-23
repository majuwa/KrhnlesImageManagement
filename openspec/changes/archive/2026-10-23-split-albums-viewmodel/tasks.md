# Tasks

## 1. Shared building blocks

- [x] 1.1 Add `data/AlbumsRepositoryProvider.kt` (`get()`, `current`, `invalidate()`) and verify with `AlbumsRepositoryProviderTest` (caches, invalidate re-reads config, creation failure propagates)
- [x] 1.2 Add the `BlurScorer` interface, implemented by `BlurAnalyzer`, and verify the project compiles (`./gradlew compileDebugKotlin`)

## 2. Split the ViewModels

- [x] 2.1 Reduce `AlbumsViewModel` to list/rename/delete album and verify `AlbumsViewModelTest` passes
- [x] 2.2 Add `AlbumDetailViewModel` (detail state, thumbnails, `fullImageUrl`, selection deletion, public `PhotoDeletionLauncher` orchestration, one-shot outcome) and verify the new `AlbumDetailViewModelTest` passes (moved `loadAlbum` / `isDeletingPhotos` tests)
- [x] 2.3 Add `DuplicatesViewModel` (scan on supplied photos, `deletePhotos(toDelete, launcher)` with compare-and-set) and verify the new `DuplicatesViewModelTest`
- [x] 2.4 Add `BlurViewModel` (scan on supplied photos with injected scorer, `deleteBlurryPhotos(toDelete, launcher)` with compare-and-set) and verify the new `BlurViewModelTest`
- [x] 2.5 Move `AlbumsViewModelPhotoDeletionTest` to `AlbumPhotoDeletionTest` on the new ViewModels (all entry points, cross-ViewModel re-entry) and verify it passes
- [x] 2.6 Add the new ViewModels to `ViewModelFactoryRegressionTest` and verify it passes

## 3. Screens and navigation scoping

- [x] 3.1 Update `AlbumDetailScreen`, `DuplicateReviewScreen`, `BlurReviewScreen`, `FullscreenViewerScreen` to the new ViewModels and verify `PhotoDeletionOutcomeUiTest` / `FullscreenViewerScreenTest` / `AlbumsScreenTest` pass
- [x] 3.2 Scope the ViewModels per route in `KrhnlesApp.kt` (`sharedAlbumDetailViewModel` via `rememberParentEntry` on the detail route pattern) and verify with a new `RememberParentEntryTest` case for an argument-bearing parent route
- [x] 3.3 Update `docs/architecture.md` (package structure, navigation scoping, deletion path) and `CLAUDE.md` (navigation-scoped state, photo deletion conventions); README (errors, Back during deletion, rotation, logout during a review) — verify by reading the updated sections

## 4. Verification

- [x] 4.1 `./gradlew test`, `./gradlew spotlessCheck`, `./gradlew detekt`, `./gradlew lintDebug`, `./gradlew jacocoTestReport` all pass

## 5. Review findings (D8)

- [x] 5.1 Scans: `scanJob` + `cancelScan()` in both review ViewModels, per-photo repository check in `scanAlbumPhotos()`, `rememberReviewAlbumReady()` / `ReviewAlbumGate` in the review screens; verify with config-change-mid-scan, `cancelScan`, reset-during-scan and Retry-on-empty tests
- [x] 5.2 Deletion: foreign photos count as failed, `onFinish` always runs (`invokeOnCompletion`), guard on `isCompleted`; verify with the "deletion after config change" case in `AlbumPhotoDeletionTest` and the cancelled-before-dispatch test
- [x] 5.3 `loadAlbum` no-op for the loaded album, `reload()`, `isLoaded`; verify with `AlbumDetailViewModelTest`
- [x] 5.4 Plain `ViewModel` reviews, injectable analysis dispatcher, redundant `invalidate()` removed; verify with `PlainViewModelFactoryRegressionTest` and the ViewModel tests
- [x] 5.5 `oc:fileid` validation and type-only logging in `checkMemories()`; verify with `WebDavXmlParserTest`
- [x] 5.6 UI tests: `ReviewSelectionTest`, blur review Back blocking, review album error after logout
- [x] 5.7 `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport` pass

## Workflow follow-up

- Code and security review, archive, commit and closing #29 / #48 are handled separately.
