# Tasks

> Implement this change **last**, after the sibling changes (nextcloud-chunked-upload, cancel-running-upload, pin-self-signed-certificate, occasion-folder-suggestions, download-album-photos, show-videos-in-albums, new-photos-reminder, album-search-and-sort, readme-roadmap-cleanup) are merged. Never work from a fixed string list: translate **every string present in `app/src/main/res/values/` at implementation time**.

## 1. Preparation

- [ ] 1.1 Rebase onto the latest main containing all merged sibling changes and list every resource file under `app/src/main/res/values/` that holds `<string>`, `<plurals>` or `<string-array>`; verify the list (and the counts per file) is noted in the PR description as the translation scope.
- [ ] 1.2 Inspect `LoginFlowState.Failed` in `libs/login-flow-nextcloud` (initialise the submodule) and decide per design D5 whether failures carry a typed reason; verify the decision (typed mapping or one generic string) is recorded in docs/architecture.md in task 6.2.

## 2. Non-translatable strings and remaining hardcoded texts

- [ ] 2.1 Mark the non-translatable strings `translatable="false"` in `values/strings.xml` per design D3.6 (`app_name`, `placeholder_nextcloud_url`, `placeholder_webdav_url`, `placeholder_base_folder`, `notification_text_with_reason`, `preview_upload_path`, plus any sibling string of the same kind: brand / product names only, example URLs or paths, word-free format templates); verify `./gradlew lintDebug` still passes.
- [ ] 2.2 Replace `SettingsUiState.error: String?` with a typed error resolved in Compose (server URL required, fill in all fields, credential save failed, login failed / timed out per 1.2), add the English strings, never show `LoginFlowState.Failed.message`; update `SettingsViewModelTest` to assert the typed errors instead of English literals and verify `./gradlew test --tests '*SettingsViewModel*' --tests '*SettingsScreen*'` passes.
- [ ] 2.3 Replace `PhotoGridUiState.error: String?` (`throwable.message ?: "Failed to load photos"`, load and refresh) with a typed error / string resource, log the exception type only; add a `PhotoGridViewModelTest` case for a failing load asserting the typed error and verify it passes.
- [ ] 2.4 Use `stringResource(R.string.cd_back)` in `FullscreenViewerScreen` instead of the literal `"Back"`; verify with `FullscreenViewerGestureTest` / the viewer tests.
- [ ] 2.5 Add `NoHardcodedUiStringRegressionTest` (design D7.4: scans `ui/` sources via `krhnles.moduleDir` for `contentDescription = "`, `Text("`, `text = "`, `error = "`, `label = "`, `title = "` literals containing letters outside `${…}`, documented allow-list for Compose animation labels, matcher self-tests); verify it passes now and fails when a literal `contentDescription = "Back"` is temporarily reintroduced.

## 3. Parity and format tests (before translating)

- [ ] 3.1 Add `StringResourceParityTest` per design D7.1 (all XML files in `values/` and `values-de/`; key parity with separate missing / extra lists; no German copy of `translatable="false"` keys; plural quantities equal and ⊇ {one, other}; per-quantity placeholder multisets; escaping and `...` check; informal-address check; extractor self-tests on inline fixtures); verify it fails with a readable list of all missing keys while `values-de` does not exist yet or is empty.
- [ ] 3.2 Add `LocaleConfigTest` per design D7.2; verify it fails before task 5.1 and names the missing file / attribute.
- [ ] 3.3 Add `GermanStringsFormatTest` (Robolectric, `@Config(qualifiers = "de")`) per design D7.3, formatting every translatable string and every plural quantity (counts 1 and 2) with dummy arguments derived from its placeholders and asserting German differs from English for a fixed sample of keys; verify it fails before task 4 and that it uses no `Dispatchers.Default` / main-looper waits.

## 4. German translation

- [ ] 4.1 Create `app/src/main/res/values-de/strings.xml` (and a `values-de/<file>.xml` for every other `values/` file holding strings) translating **every translatable `<string>`, `<plurals>` (all quantities) and `<string-array>` present at implementation time**, following design D1 (informal "du", lowercase; infinitive style for actions), D2 (glossary) and D3 (placeholders, plurals, escaping, „…“ quotes, `…`, fragments, length); verify `StringResourceParityTest` and `GermanStringsFormatTest` pass.
- [ ] 4.2 Extend the glossary in docs/architecture.md (task 6.2) with every new term introduced by sibling strings (e.g. chunked upload, cancel, certificate pinning, download, search / sort, reminder) and verify each term is used identically in all German strings (grep `values-de` for the English-derived alternatives).
- [ ] 4.3 Keep the local-removal wording distinct: verify by grep that the strings of the "Remove uploaded photos from device" flow use "entfernen" / "Papierkorb" and contain no "lösch", while server photo / album deletion strings use "löschen".
- [ ] 4.4 Review every screen, dialog, banner and notification in the `de` locale (Compose previews or a device with per-app language German: photo grid incl. permission banners and removal flow, upload dialog / auto date preview, upload history, albums incl. rename / delete / detail / blur / duplicates review, viewer, settings logged-in / login / manual, onboarding, share target, upload progress / result / retry notifications and channel); verify no English text, no clipped button or notification action, and record any follow-up layout issue as a GitHub issue instead of a workaround.

## 5. Per-app language

- [ ] 5.1 Add `app/src/main/res/xml/locales_config.xml` with `en` and `de` and set `android:localeConfig="@xml/locales_config"` on `<application>` in `AndroidManifest.xml` (design D4); verify `LocaleConfigTest` passes.
- [ ] 5.2 On an Android 13+ device / emulator with English system language, select German for the app in Settings › System › Languages › App languages; verify the app is listed with English and German, switches to German, keeps the current photo selection and that the next upload notification and channel name are German.

## 6. Documentation

- [ ] 6.1 Update README.md: German UI (follows the device language), per-app language selection on Android 13+, English fallback; verify the feature list and setup section mention it.
- [ ] 6.2 Update docs/architecture.md: new "Localization" section (resource layout, `translatable="false"` rule, `locales_config.xml`, typed UI errors, login-failure mapping from 1.2, the four guard tests) and the glossary + translation rules from design D1–D3; add a "Key Decisions" bullet for informal "du" and the hand-written locale config; verify the section exists and matches the implementation.
- [ ] 6.3 Update docs/requirements.md: a General requirement (e.g. G-005) "UI available in English and German (informal du), per-app language on Android 13+" with status Implemented, and a Non-Functional policy "every new user-facing string has a German translation in the same change"; verify both rows exist.
- [ ] 6.4 Update CLAUDE.md "String Resources": every new or changed `<string>` / `<plurals>` / `<string-array>` needs a German translation in `values-de` in the same change (informal "du", glossary in docs/architecture.md, identical placeholders, same plural quantities); non-translatable texts get `translatable="false"` instead of a copy; no user-facing text from exception / library messages or literals; guarded by `StringResourceParityTest`, `GermanStringsFormatTest`, `LocaleConfigTest`, `NoHardcodedUiStringRegressionTest`; new locales also go into `locales_config.xml`; verify the rule is present.

## 7. Integration checks

- [ ] 7.1 Run `./gradlew test`; verify all tests pass (existing English UI tests still run in the default locale).
- [ ] 7.2 Run `./gradlew lintDebug`; verify zero issues, in particular no `MissingTranslation`, `ExtraTranslation`, `StringFormatMatches`, `StringFormatInvalid`, `MissingQuantity` or `Typos`, and no new `tools:ignore` / baseline (ask the user before any suppression).
- [ ] 7.3 Run `./gradlew spotlessCheck` and `./gradlew detekt`; verify both pass with no new detekt issues.
- [ ] 7.4 Run `./gradlew jacocoTestReport`; verify the report is generated and the new tests and typed-error code are covered.
- [ ] 7.5 Immediately before merging, re-sync with main and re-run `./gradlew test lintDebug`; verify no string added by a late sibling merge is missing in `values-de`.

## Workflow follow-up

- Archive the change after review (`openspec archive german-translation`) so `localization` becomes a main spec.
