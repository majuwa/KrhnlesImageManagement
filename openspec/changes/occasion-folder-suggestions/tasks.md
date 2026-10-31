# Tasks

## 1. Pure ranking, dedupe and filtering

- [ ] 1.1 Add `ui/share/OccasionSuggestions.kt` with `MAX_OCCASION_SUGGESTIONS = 5`, `recentOccasionNames()`, `mergeOccasionCandidates()` and `filterOccasionSuggestions()` as described in design D1/D2 (`Locale.ROOT` keys, trim, `isValidOccasionName` + `exceedsAlbumNameLimit` filter); verify it compiles with `./gradlew compileDebugKotlin`.
- [ ] 1.2 Add `OccasionSuggestionsTest` (plain JUnit) covering: newest-first by timestamp regardless of input order, case-insensitive dedupe keeping the newest spelling, blank / `.` / `..` / `/` / `\` / over-255-byte labels excluded, single-day and range auto-date labels excluded, remote spelling replacing a case-variant recent name in place, remote names appended alphabetically and deduped, blank query → first 5, case-insensitive substring filter, prefix-before-substring order, exact match hidden but case-only variant shown, limit 5; verify `./gradlew test --tests '*OccasionSuggestionsTest'` passes.
- [ ] 1.3 Add the regression test that `resolveAutoDateUploadBatch(...).label` for one day and for a multi-day range is never a valid occasion name (pins design D1); verify it passes with the test from 1.2.

## 2. Album folder names from the repository

- [ ] 2.1 Add `fun folderNameOf(album: RemoteAlbum): String?` to `AlbumsRepositoryContract` (`data/Repositories.kt`) and implement it in `AlbumsRepository` via `albumRelativeSegments(album).last()` inside `runCatching`, returning `null` for unsafe / nested hrefs, invalid occasion names and names over the album name limit; add a configurable implementation to `fakes/FakeAlbumsRepository.kt`; verify `./gradlew compileDebugUnitTestKotlin` succeeds.
- [ ] 2.2 Extend `AlbumsRepositoryTest` with `folderNameOf` cases: `Summer%202026/` → `Summer 2026`, href without trailing slash, entry two levels deep → `null`, foreign origin / `..` / encoded `/` href → `null`, decoded name containing `\` → `null`; verify the test class passes.
- [ ] 2.3 Document in `CLAUDE.md` (rule 3 / album rules) that `AlbumsRepositoryContract.folderNameOf()` is the only source of a remote folder name for uploads and `RemoteAlbum.displayName` is never used as one; verify the referenced names exist in the code.

## 3. Suggestions ViewModel

- [ ] 3.1 Add `ui/share/OccasionSuggestionsViewModel.kt` per design D4: `@JvmOverloads` `(Application, …)` constructor, owned `AlbumsRepositoryProvider` closed in `onCleared()`, `candidates: StateFlow<List<String>>`, guarded `load()` with generation, `history.entries.first()` under a 3 s `withTimeoutOrNull` (`runCatching` rethrowing `CancellationException`), remote part under a 10 s overall `withTimeoutOrNull`, repository-identity check before and after listing, 60 s freshness via injectable clock, `ensureActive()` before every publish, exception-type-only logging; verify it compiles.
- [ ] 3.2 Add `OccasionSuggestionsViewModel` to `ViewModelFactoryRegressionTest`; verify that test passes.
- [ ] 3.3 Add `OccasionSuggestionsViewModelTest` (Robolectric, `runTest`) covering: recent published before a suspended remote listing completes; history read failure and history timeout → empty recent, no crash; not configured → no `listAlbums` call; listing failure / `ListingTooLarge` → recent only; listing exceeding the timeout is cancelled and the job ends; config change between two `load()` calls clears old remote names before the new listing; config change during the listing discards the result; re-entry while loading performs one listing; fresh result for the same configuration is reused within 60 s and re-listed after; `onCleared()` closes the repository; verify the test class passes.
- [ ] 3.4 Document the ViewModel in `CLAUDE.md` (new `AlbumsRepositoryProvider` owner; one-shot `first()` history read, timeouts, repository-identity check, silent failures, no references to other ViewModels, D1 auto-date exclusion guard) and in `docs/architecture.md` (package-structure entries for `OccasionSuggestions.kt` / `OccasionSuggestionsViewModel.kt` and a new "Occasion folder suggestions" section summarising design D1–D5); verify the referenced names exist in the code.

## 4. Dialog UI

- [ ] 4.1 Extend `OccasionDialog` per design D6: `suggestions: List<String> = emptyList()` parameter, `TextFieldValue` state with `TextFieldValue.Saver`, memoized `filterOccasionSuggestions`, heading + `FlowRow` of suggestion buttons (role Button, full-text semantics, `onClick(label)` wrapping the real fill callback, no content descriptions), scrollable dialog content; add `occasion_suggestions_heading` and `occasion_suggestion_use_label` to `values/strings.xml`; note the suggestion-button accessibility pattern in `CLAUDE.md` rule 9; verify `./gradlew compileDebugKotlin` and that existing `OccasionDialogTest` still passes unchanged.
- [ ] 4.2 Extend `OccasionDialogTest` (Robolectric Compose): empty field shows at most 5 suggestions and the heading; no suggestions → no heading; typing filters case-insensitively and prefix-first; tapping fills the field with the cursor at the end and does not call `onConfirm`; tapping then Upload confirms the suggestion; an invalid typed name still shows the error and disables Upload; each suggestion has role Button, its name as text and exactly one click action with the localized label; performing the accessibility click fills the field; `StateRestorationTester` keeps a tapped suggestion; verify the test class passes.

## 5. Wiring into grid and share target

- [ ] 5.1 Wire the grid: `PhotoGridScreen` gets `suggestionsViewModel: OccasionSuggestionsViewModel = viewModel()`, calls `load()` in a `LaunchedEffect` inside the `showOccasionDialog` branch and passes the collected candidates to `OccasionDialog`; add a Robolectric Compose test (new `PhotoGridOccasionSuggestionsTest` or in `PhotoGridScreenAutoDateTest`) with a ViewModel built from fakes and `backgroundDispatcher = Dispatchers.Unconfined` asserting suggestions appear in the grid dialog, a tap + Upload enqueues `UploadBatch.Occasion` with that name, and auto-date mode shows no suggestions; verify the tests pass.
- [ ] 5.2 Wire the share target: `ShareRoute` gets `suggestionsViewModel: OccasionSuggestionsViewModel = viewModel()`, calls `load()` when the state becomes `ShareUiState.Ready`, and `ShareReceiverScreen` gets `occasionSuggestions: List<String> = emptyList()` forwarded to `OccasionDialog`; extend `ShareReceiverScreenTest` (suggestions shown in `Ready`, tap + Upload calls `onConfirm` with the name, not shown in `Preparing` / `Copying` / `NotConfigured`); verify `ShareReceiverScreenTest` and `MainActivityShareTest` pass.
- [ ] 5.3 Add a non-blocking test: with a fake listing that never completes, the grid and the share dialog can be confirmed and cancelled immediately; verify it passes.
- [ ] 5.4 Update `README.md` (Usage step 5 and "Sharing photos from other apps": suggestions from recent occasions and existing albums, tap fills the name, server albums best-effort, history of a previous server may appear), `docs/requirements.md` (new row `P-005` "The occasion dialog suggests recent occasions and existing remote albums", Implemented) and `docs/architecture.md` (Data Flow step 3, Share Target Flow, D6 dialog behaviour in the suggestions section); verify the text matches the implemented behaviour and the Markdown tables render.

## 6. Verification

- [ ] 6.1 Run `./gradlew test` and verify all tests pass.
- [ ] 6.2 Run `./gradlew spotlessCheck` and `./gradlew detekt` and verify no new issues (ask the user before accepting any detekt finding).
- [ ] 6.3 Run `./gradlew lintDebug` and verify zero issues without suppressions or baseline.
- [ ] 6.4 Run `./gradlew jacocoTestReport` and verify the new files are covered in the HTML report.
- [ ] 6.5 Manually (or in an emulator) open the grid and share dialogs with a reachable and an unreachable server and verify suggestions appear without delaying the dialog and without any error message.

## Workflow follow-up

- Coordinate with `album-search-and-sort` (both touch `AlbumsRepositoryContract` / `FakeAlbumsRepository`) and rebase whichever lands second.
- Ensure `german-translation` (implemented last) translates `occasion_suggestions_heading` and `occasion_suggestion_use_label`.
- Archive the change after review and sync the new `occasion-folder-suggestions` spec.
