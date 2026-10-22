# Tasks

## 1. Shared parent-entry helper

- [x] 1.1 Add `ui/navigation/BackStackEntries.kt` with `@Composable internal fun rememberParentEntry(navController, currentEntry, parentRoute)` keyed on `currentEntry`; verify it compiles (`./gradlew compileDebugKotlin`)
- [x] 1.2 Add `RememberParentEntryTest` (Robolectric Compose) covering re-resolution after the parent entry is replaced (fails if the key is made constant) and a ViewModel-sharing check between parent and child (sharing only; it does not detect a constant key); verify it passes with the helper

## 2. Fix KrhnlesApp routes

- [x] 2.1 Use `rememberParentEntry(navController, backStack, ROUTE_ALBUMS)` in the album detail, viewer, duplicates and blur routes; remove both `@SuppressLint("UnrememberedGetBackStackEntry")` annotations and the unused import; verify `./gradlew lintDebug` reports zero issues
- [x] 2.2 Add `NoLintSuppressionRegressionTest` that fails if `UnrememberedGetBackStackEntry` is suppressed anywhere under `app/src/main`; verify it passes via `./gradlew test`

## 3. Review fixes

- [x] 3.1 Extract the suppression matcher into `LintSuppressionScanner` and scan all of `app/src` (`.kt`/`.kts`/`.java`) for annotation (incl. `@file:`, qualified, multi-line) and `noinspection` forms, plus `app/build.gradle.kts`, `app/lint*.xml`, lint baselines and root `lint.xml`; add positive/negative matcher tests and a temp-dir scanner test; avoid self-flagging by never writing the issue id literally in the test sources
- [x] 3.2 Pass the module/root dirs to unit tests via `testOptions.unitTests.all { systemProperty(...) }`, fall back to the working dir and fail loudly if missing
- [x] 3.3 Rename/reword the ViewModel test as a sharing check only
- [x] 3.4 Add a caching test counting `getBackStackEntry` calls across forced recompositions (fails if `remember` is removed)
- [x] 3.5 Add `sharedAlbumsViewModel(navController, backStack)` and use it in all four album sub-routes

## 4. Documentation

- [x] 4.1 Document the parent-entry convention in `docs/architecture.md` (navigation) and `CLAUDE.md` (conventions); verify the sections reference `rememberParentEntry`

## 5. Verification

- [x] 5.1 Run `./gradlew test`, `./gradlew spotlessCheck` (after `spotlessApply`), `./gradlew detekt`, `./gradlew lintDebug`, `./gradlew jacocoTestReport`; verify all succeed with no new detekt issues

## Workflow follow-up

- Code review, security review, commit, closing #24/#43 and archive are handled separately.
