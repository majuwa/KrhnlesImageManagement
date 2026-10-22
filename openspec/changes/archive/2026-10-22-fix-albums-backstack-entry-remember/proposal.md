# Proposal

## Why

GitHub issues #24 and #43 (identical duplicates): in `KrhnlesApp.kt` the duplicate-review and blur-review routes resolve the shared Albums back-stack entry with `remember(ROUTE_ALBUMS) { navController.getBackStackEntry(ROUTE_ALBUMS) }` and silence the resulting `UnrememberedGetBackStackEntry` lint warning with `@SuppressLint`. Because the key is a constant string, the remembered entry would never be re-resolved within one composition. In practice this stale-entry/stale-`AlbumsViewModel` risk is mostly theoretical: `NavHost` composes each back-stack entry in its own composition, so a new child entry gets a fresh `remember` slot anyway. The main defect is the suppression itself: it hides the unsafe pattern from lint and reviewers and contradicts the project rule "do not suppress issues".

## What Changes

- Album sub-screens (album detail, fullscreen viewer, duplicate review, blur review) resolve the Albums back-stack entry through one shared helper keyed on the current destination's `NavBackStackEntry`, so the entry is re-resolved whenever the current entry changes.
- Both `@SuppressLint("UnrememberedGetBackStackEntry")` annotations (and the then-unused `SuppressLint` import) are removed; lint (`warningsAsErrors = true`) again guards this pattern.
- The four album sub-routes obtain the shared `AlbumsViewModel` through one private `sharedAlbumsViewModel(navController, backStack)` composable instead of repeating the lookup.
- Regression tests: Robolectric Compose tests proving the helper re-resolves when the current entry changes, caches the lookup across recompositions, and shares the parent-scoped ViewModel; plus a scan test that fails if `UnrememberedGetBackStackEntry` is silenced by any mechanism (suppress annotations or `noinspection` comments in any source set, Gradle `lint {}` options, `lint.xml`, lint baselines).

## Capabilities

### New Capabilities
- `album-navigation`: How screens reached from the Albums tab share the Albums tab's state (album list, selected album, review results) via the Albums back-stack entry.

### Modified Capabilities
<!-- none -->

## Impact

- Code: `app/src/main/java/.../KrhnlesApp.kt`, new `app/src/main/java/.../ui/navigation/BackStackEntries.kt`.
- Tests: new `app/src/test/java/.../ui/navigation/RememberParentEntryTest.kt`, new `app/src/test/java/.../NoLintSuppressionRegressionTest.kt` and `LintSuppressionScanner.kt`.
- Build: `app/build.gradle.kts` passes `krhnles.moduleDir` / `krhnles.projectDir` system properties to unit tests.
- Docs: `docs/architecture.md` (navigation section), `CLAUDE.md` (navigation convention). No user-visible behaviour change, no new dependencies.
