# Tasks

> Implement this change **last**. All sibling changes that add strings are merged (only the docs-only readme-roadmap-cleanup is open). Never work from a fixed string list: translate **every string present in `app/src/main/res/values/` at implementation time** (289 `<string>` / 34 `<plurals>` at review time).

## 1. Preparation

- [x] 1.1 Rebase onto the latest main and list every resource file under `app/src/main/res/values/` that holds `<string>`, `<plurals>` or `<string-array>` (at review time only `strings.xml`); verify the list and the counts per file are noted in the PR description as the translation scope.
- [x] 1.2 Re-check `libs/login-flow-nextcloud` `LoginFlowState.Failed`: at review time it carries only `message: String`, so design D5 maps every `Failed` to one generic string; verify it still has no typed reason (if it gained one, map each reason to its own string instead) and that the decision is recorded in docs/architecture.md in task 6.2.

## 2. Non-translatable strings and remaining hardcoded texts

- [x] 2.1 Mark the non-translatable strings `translatable="false"` in `values/strings.xml` per design D3.6 (`app_name`, `placeholder_nextcloud_url`, `placeholder_webdav_url`, `placeholder_base_folder`, `notification_text_with_reason`, `preview_upload_path`, `viewer_page_counter`, `video_elapsed_of_total`, `notification_uploading_file_progress`, plus any later string of the same kind: brand / product names only, example URLs or paths, word-free format templates); verify `./gradlew lintDebug` still passes.
- [x] 2.2 Replace `SettingsUiState.error: String?` with a typed error resolved in Compose (server URL required, fill in all fields, credential save failed, trusted certificate clear failed, login failed per 1.2), add the English strings, remove every `getApplication().getString(…)` from `SettingsViewModel`, never show or log the text of `LoginFlowState.Failed.message`; update `SettingsViewModelTest` and `SettingsViewModelCertificateTest` to assert the typed errors instead of English literals / resolved strings and verify `./gradlew test --tests '*SettingsViewModel*' --tests '*SettingsScreen*'` passes.
- [x] 2.3 Replace `PhotoGridUiState.error: String?` (`throwable.message ?: "Failed to load photos"`, load and refresh) with a typed error / string resource, log the exception type only; update the `PhotoGridViewModelTest` cases that assert the raw `"No permission"` / `"disk error"` messages to assert the typed error for both a failing load and a failing refresh, and verify they pass.
- [x] 2.4 Add `NoHardcodedUiStringRegressionTest` (design D7.4: scans all main Kotlin sources via `krhnles.moduleDir` for the listed UI / notification / toast literal patterns containing letters outside `${…}` and `%…` specifiers, documented allow-list for Compose animation labels, matcher self-tests incl. the negatives `"%.0f"` and `"$current / $total"`); verify it passes after 2.2 / 2.3 and fails when `contentDescription = "Back"` or `setContentText("Uploading")` is temporarily introduced.

## 3. Parity and format tests (before translating)

- [x] 3.1 Add `StringResourceParityTest` per design D7.1 (all XML files in `values/` and `values-de/`; key parity with separate missing / extra lists; no German copy of `translatable="false"` keys; plural quantities equal and ⊇ {one, other}; per-quantity placeholder multisets; escaping and `...` check; informal-address check incl. `\n` as sentence start and lowercase du-forms; no `lösch` in the removal-flow keys; extractor and checker self-tests on inline fixtures, e.g. "Bitte prüfen Sie die Serveradresse" fails, "Sie wurden hochgeladen." passes); verify it fails with a readable list of all missing keys while `values-de` does not exist yet or is empty.
- [x] 3.2 Add `LocaleConfigTest` per design D7.2; verify it fails before task 5.1 and names the missing file / attribute.
- [x] 3.3 Add `GermanStringsFormatTest` (Robolectric, `@Config(qualifiers = "de")`) per design D7.3, formatting every translatable string and every plural quantity (counts 1 and 2) with dummy arguments derived from its placeholders and asserting German differs from English for a fixed sample of keys, plus the `de-rAT` / `de-rCH` / `fr` sample cases and the German upload / download / reminder channel names; verify it fails before task 4 and that it uses no `Dispatchers.Default` / main-looper waits.

## 4. German translation

- [x] 4.1 Create `app/src/main/res/values-de/strings.xml` (and a `values-de/<file>.xml` for every other `values/` file holding strings) translating **every translatable `<string>`, `<plurals>` (all quantities) and `<string-array>` present at implementation time**, following design D1 (informal "du", lowercase; infinitive style for actions), D2 (glossary) and D3 (placeholders, plurals, escaping, „…“ quotes, `…`, fragments, length); verify `StringResourceParityTest` and `GermanStringsFormatTest` pass.
- [x] 4.2 Check terminology against the glossary in design D2 (incl. the sibling terms: Abbrechen / abgebrochen, auf dem Gerät speichern, sichern, Erinnerung, Fingerabdruck, vertrauen) and the cross-reference rule of D3.4 (texts naming "Test Connection", "Stop trusting", "Settings" use the exact German label); verify by grep of `values-de` that no alternative wording is used (e.g. no "Uploaden", "Wi-Fi", "Wiederholen" as retry action, "Sie" address, "löschen" for local removal, "fehlgeschlagen" in the cancel notification texts).
- [x] 4.3 Keep the local-removal wording distinct: verify `StringResourceParityTest`'s removal-wording check passes (no "lösch" in `action_remove_uploaded`, `action_move_to_trash`, `removal_*`) and by grep that server photo / album deletion strings (`dialog_delete_*`, `snackbar_delete_*`, `delete_n_photos`, `action_delete`) use "löschen".
- [x] 4.4 Adjust `PhotoGridAccessibilityTest` `grid renders the date in the device locale` (runs under `qualifiers = "de"`) to the German item count and content description; verify `./gradlew test --tests '*PhotoGridAccessibilityTest*'` passes and the other tests of the class still assert English.
- [ ] 4.5 Review every screen, dialog, banner and notification in the `de` locale (Compose previews or a device with per-app language German: photo grid incl. permission banners and removal flow, upload dialog incl. occasion suggestions / auto date preview, upload history incl. cancelled entries, albums incl. search / sort / rename / delete / detail / save to device / blur / duplicates review, viewer incl. video controls, settings logged-in / login / manual / certificate review / reminder, onboarding, share target, upload progress (incl. chunk percentage and Cancel) / result / cancelled / retry notifications, download progress / result notifications, reminder notification, all three channels); verify no English text, no clipped button or notification action, and record any follow-up layout issue as a GitHub issue instead of a workaround.

## 5. Per-app language

- [x] 5.1 Add `app/src/main/res/xml/locales_config.xml` with `en` and `de` and set `android:localeConfig="@xml/locales_config"` on `<application>` in `AndroidManifest.xml` (design D4); verify `LocaleConfigTest` passes.
- [ ] 5.2 On an Android 13+ device / emulator with English system language, select German for the app in Settings › System › Languages › App languages; verify the app is listed with English and German, switches to German, keeps the current photo selection, shows an error that was visible before the switch (e.g. the login error) in German, and that the next upload notification and channel name are German.

## 6. Documentation

- [x] 6.1 Update README.md: German UI (follows the device language), per-app language selection on Android 13+, English fallback; verify the feature list and setup section mention it.
- [x] 6.2 Update docs/architecture.md: new "Localization" section (resource layout, `translatable="false"` rule, `locales_config.xml`, typed UI errors, login-failure mapping from 1.2, the four guard tests) and the glossary + translation rules from design D1–D3; add a "Key Decisions" bullet for informal "du" and the hand-written locale config; verify the section exists and matches the implementation.
- [x] 6.3 Update docs/requirements.md: a General requirement (e.g. G-005) "UI available in English and German (informal du), per-app language on Android 13+" with status Implemented, and a Non-Functional policy "every new user-facing string has a German translation in the same change"; verify both rows exist.
- [x] 6.4 Update CLAUDE.md "String Resources": every new or changed `<string>` / `<plurals>` / `<string-array>` needs a German translation in `values-de` in the same change (informal "du", glossary in docs/architecture.md, identical placeholders, same plural quantities); non-translatable texts get `translatable="false"` instead of a copy; no user-facing text from exception / library messages or literals; guarded by `StringResourceParityTest`, `GermanStringsFormatTest`, `LocaleConfigTest`, `NoHardcodedUiStringRegressionTest`; new locales also go into `locales_config.xml`; verify the rule is present.

## 7. Integration checks

- [x] 7.1 Run `./gradlew test`; verify all tests pass (existing English UI tests still run in the default locale).
- [x] 7.2 Run `./gradlew lintDebug`; verify zero issues, in particular no `MissingTranslation`, `ExtraTranslation`, `Untranslatable`, `StringFormatMatches`, `StringFormatCount`, `StringFormatInvalid`, `MissingQuantity`, `UnusedQuantity`, `ImpliedQuantity`, `Typos` or `TypographyEllipsis`, and no new `tools:ignore` / baseline (ask the user before any suppression).
- [x] 7.3 Run `./gradlew spotlessCheck` and `./gradlew detekt`; verify both pass with no new detekt issues.
- [x] 7.4 Run `./gradlew jacocoTestReport`; verify the report is generated and the new tests and typed-error code are covered.
- [ ] 7.5 Immediately before merging, re-sync with main and re-run `./gradlew test lintDebug`; verify no string added by a later merge is missing in `values-de`.

## Workflow follow-up

- Archive the change after review (`openspec archive german-translation`) so `localization` becomes a main spec.
