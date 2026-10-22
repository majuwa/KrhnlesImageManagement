# Design

## Context

`KrhnlesApp.kt` hosts a single `NavHost`. The Albums tab route (`albums`) owns an `AlbumsViewModel`; its sub-routes (`albums/detail`, `albums/viewer`, `albums/duplicates`, `albums/blur`) obtain the same ViewModel via `viewModel(navController.getBackStackEntry(ROUTE_ALBUMS))`. Detail and viewer already use `remember(backStack) { ... }`; duplicates and blur use `remember(ROUTE_ALBUMS)` plus `@SuppressLint`. The navigation-compose lint detector (`UnrememberedGetBackStackEntryDetector`) accepts a `getBackStackEntry` call only when it is inside `remember` with a key of type `NavBackStackEntry`. Lint runs with `warningsAsErrors = true`.

## Goals / Non-Goals

**Goals:**
- One shared, lint-clean, unit-testable way to resolve the Albums parent entry from every album sub-route.
- Regression tests at behaviour level (re-keying) and at policy level (no suppression).

**Non-Goals:**
- Migrating to type-safe navigation or nested navigation graphs.
- Changing any screen behaviour, routes or arguments.

## Decisions

1. **Shared helper `rememberParentEntry(navController, currentEntry, parentRoute)`** in a new `ui/navigation/BackStackEntries.kt` (`internal`, `@Composable`), implemented as `remember(currentEntry) { navController.getBackStackEntry(parentRoute) }`. All four album sub-routes call it with their composable lambda's `NavBackStackEntry`.
   - *Why:* removes the duplicated pattern (four copies, two of them wrong), makes the keying testable in isolation, and the `NavBackStackEntry`-typed key satisfies the lint detector without suppression.
   - *Alternative:* the minimal inline fix from the issue (`remember(backStack)` in the two routes). Correct, but leaves four copies and no unit-testable seam; rejected in favour of the helper (the inline form is exactly what the helper contains).
   - *Alternative:* nested `navigation("albums_graph")` with the graph entry as owner. Larger refactor touching routes and bottom-nav `popUpTo`; out of scope.

   - A private `sharedAlbumsViewModel(navController, backStack)` composable in `KrhnlesApp.kt` wraps `viewModel(rememberParentEntry(..., ROUTE_ALBUMS))` so the four routes do not repeat it (kept next to `ROUTE_ALBUMS`, so `ui/navigation` stays independent of `ui/albums`).

2. **Behavioural regression tests (Robolectric + Compose):**
   - *Re-resolve:* a test `NavHost` with `parent`/`child` routes; the helper is composed outside the NavHost with a key held in `mutableStateOf`. After the parent entry is replaced (`navigate(parent) { popUpTo(parent) { inclusive = true } }`) and a new child entry is used as the key, the helper must return the new parent entry. A constant key keeps returning the old entry, so this test fails for the buggy form.
   - *Caching:* with a mocked `NavController`, forced recompositions with the same key must call `getBackStackEntry` exactly once; changing the key calls it again. Fails if `remember` is dropped from the helper.
   - *ViewModel sharing:* the child's ViewModel obtained via the resolved entry is the parent destination's ViewModel, also after the parent entry is recreated. This is a sharing check only — inside a real `NavHost` each entry has its own composition, so it does not detect a constant key.

3. **Policy regression test:** a plain JUnit test (`NoLintSuppressionRegressionTest`, matcher in `LintSuppressionScanner`) covers every suppression mechanism: in all source sets under `app/src` (`.kt`, `.kts`, `.java`) it flags `@Suppress`/`@SuppressLint`/`@SuppressWarnings` with any use-site target (`@file:` etc.), fully qualified or not, single- or multi-line, plus any line naming the issue together with `suppress`/`noinspection`; it also flags any mention of the issue id in `app/build.gradle.kts` (`disable`, `checkOnly`, ...), `app/lint*.xml`, `app/*baseline*.xml` and the root `lint.xml`. The matcher is a separate function with positive and negative fixture tests so it cannot be weakened silently. Lint already fails on an unremembered call, but only while the check is active — this test closes that loophole and runs in `./gradlew test`.
   - *Self-exclusion:* rather than excluding the test source set (which would let a suppression in test code slip through), the test spells the issue id as a compile-time concatenation and the scanner takes it as a parameter, so no scanned file contains the literal id next to a suppression keyword.
   - *Directory:* the module and root dirs come from the `krhnles.moduleDir` / `krhnles.projectDir` system properties set in `testOptions.unitTests.all`; fallback is the working directory, and the test asserts `src/main` and `build.gradle.kts` exist so a wrong path fails loudly instead of passing vacuously.
   - *Alternative:* a lint `lint.xml` forbidding suppressions — lint has no built-in "forbid suppress for ID X" option; rejected.

## Risks / Trade-offs

- [Source-scan test depends on the module path] → passed explicitly from Gradle; fails loudly if missing.
- [Line-based keyword match can false-positive on prose like "suppress X" in comments] → acceptable: it fails loudly and is fixed by rewording; a false negative would be worse.
- [Helper re-resolves on every new current entry] → `getBackStackEntry` is a cheap back-stack lookup; negligible.
- [`getBackStackEntry` throws if the parent route is absent] → unchanged from today; album sub-routes are only reachable from the Albums tab.
