# Tasks

## 1. Data layer

- [x] 1.1 Add `BlurSensitivity` enum (LOW 1500 / MEDIUM = `BlurDetector.DEFAULT_THRESHOLD` / HIGH 6000, `fromStored(String?)` -> MEDIUM for null/blank/unknown)
- [x] 1.2 `CredentialRepository`: add `blurSensitivity: Flow<BlurSensitivity>` and `saveBlurSensitivity()`; `CredentialStore` stores the enum name under string key `blur_sensitivity` (same pattern as `wifiOnly`, no `credentials_version` bump), reads type-safely (`prefs.asMap()[KEY] as? String`, wrong type -> MEDIUM)
- [x] 1.3 Update `FakeCredentialRepository`; update `BlurDetector` / `BlurScorer` KDoc that mention the fixed threshold

## 2. Scan behavior

- [x] 2.1 `BlurViewModel` stays a plain `ViewModel` (no-arg constructor unchanged). `findBlurryPhotos(photos, thumbnailUrls, repositories, sensitivity)` and `retryFindBlurryPhotos(...)` take the level source (`Flow<BlurSensitivity>`); inside the scan job, after `Scanning` is published, read it with `first()` bounded by `withTimeoutOrNull(3s)` + `runCatching` (rethrow `CancellationException`), fallback MEDIUM; filter with `< threshold`
- [x] 2.2 Keep "scans start only from Idle" and Retry-from-Error semantics; do not re-filter or rescan on setting change
- [x] 2.3 `BlurReviewScreen` takes the source as parameter; `ROUTE_BLUR` destination provides it (never a collected State value); update all call sites and tests

## 3. Settings UI

- [x] 3.1 `SettingsViewModel`: collect `blurSensitivity` into `SettingsUiState`, add `setBlurSensitivity()` (persist, failure must not crash)
- [x] 3.2 `SettingsScreen` (logged-in content, next to the other toggles): `BlurSensitivitySetting` card, Slider 0..2 with steps = 1, drag position derived from the hoisted level, persist in `onValueChange` whenever the rounded level differs from the current one; the slider is the single semantics node (content description = title, `stateDescription` = level label; visible title / level texts hidden from semantics)
- [x] 3.3 Add strings to `strings.xml` (title, hint that High flags more photos, low/medium/high labels reused for state description and visible text; no extra content-description string)

## 4. Tests

- [x] 4.1 `CredentialStoreTest`: default MEDIUM, round-trip each level, unknown / blank / wrong-typed stored value -> MEDIUM
- [x] 4.2 `BlurSensitivity` unit test: MEDIUM equals `DEFAULT_THRESHOLD`, strict `<` boundary, `fromStored`
- [x] 4.3 `BlurViewModelTest`: same scores give different blurry sets per level; level changed mid-scan does not affect the running scan; next scan and Retry use the new level; source timeout/failure -> MEDIUM; scan still starts only from Idle
- [x] 4.4 `SettingsViewModelTest`: exposes persisted level, `setBlurSensitivity` persists
- [x] 4.5 Compose UI (Robolectric, `analysisDispatcher = Dispatchers.Unconfined`, injected fakes, no real DataStore): Settings slider shows default Medium, stateDescription, `SemanticsActions.SetProgress` persists the chosen level; single semantics node, no duplicate texts

## 4b. Review follow-ups

- [x] 4b.1 `snapshot`: `distinctUntilChanged()` on (serverUrl, baseFolder, credentialsVersion) before decrypting; tests with a real `CredentialStore` (no re-emit on blur/wifi/auto-date writes; typed base folder kept)
- [x] 4b.2 `setBlurSensitivity`: pending-save guard for the collector, restore + `blurSensitivitySaveFailed` error string on failure; tests (failure path, no flicker)
- [x] 4b.3 Logout resets the level to Medium: documented (design/spec/README) and pinned by `CredentialStoreTest`
- [x] 4b.4 `FakeCredentialRepository.blurSaveCount`: one save per new level, none when re-setting the current one
- [x] 4b.5 `readSensitivity`: `ensureActive()` in `onFailure`; test with a source throwing `CancellationException`
- [x] 4b.6 `credentialDataStore` `@VisibleForTesting(otherwise = PRIVATE)` + `NoCredentialDataStoreAccessRegressionTest`

## 5. Docs and checks

- [x] 5.1 Update `README.md` (blur sensitivity setting), `CLAUDE.md` (storage decision incl. not restored on a new device, per-call sensitivity source) and `docs/architecture.md`
- [x] 5.2 Run `./gradlew test`, `./gradlew spotlessCheck`, `./gradlew detekt`, `./gradlew lintDebug` (zero issues, no suppressions) and `./gradlew jacocoTestReport`
