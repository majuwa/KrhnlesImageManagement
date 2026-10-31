# Proposal: German translation

## Why

Feature request (issue text):

> All strings exist only in `values/strings.xml` (English); the primary audience is German-speaking. Add `app/src/main/res/values-de/strings.xml` with all strings and plurals translated. Decide and justify informal "du" vs formal "Sie", a small glossary (Upload → Hochladen, album → Album, occasion → Anlass, trash → Papierkorb, "Retry Failed" → …, Wi-Fi → WLAN, etc.) and rules: identical format arguments (`%1$s`, `%d`), plurals with quantity one/other, escaping of apostrophes/quotes, non-translatable strings marked `translatable="false"` in `values` instead of copied. Add a unit test asserting key parity between `values` and `values-de` for `<string>` and `<plurals>` (incl. plural quantities) and identical format placeholders, so new strings can't be forgotten; Android lint `MissingTranslation` / `StringFormatMatches` must pass. Also a locales config for per-app language on Android 13+.

Today every German-speaking user sees an English UI, and nothing stops a future string from shipping untranslated. A handful of user-facing texts are not even resources yet (hardcoded English in `SettingsViewModel`, `PhotoGridViewModel`, `FullscreenViewerScreen`, plus the raw Nextcloud login-flow library message), so they would stay English even after a translation.

## What Changes

- Add `app/src/main/res/values-de/strings.xml` with a German translation of **every translatable `<string>` and `<plurals>` present in `values/` at implementation time** (this change is implemented after the sibling changes that add strings: nextcloud-chunked-upload, cancel-running-upload, pin-self-signed-certificate, occasion-folder-suggestions, download-album-photos, show-videos-in-albums, new-photos-reminder, album-search-and-sort, readme-roadmap-cleanup).
- Use informal address ("du") consistently, a fixed glossary and translation rules (placeholders, plurals, escaping, typography) documented in design.md and in CLAUDE.md.
- Mark strings that must not be translated (brand name, example URLs / folder placeholder, pure format strings) `translatable="false"` in `values/strings.xml`; they are not copied to `values-de`.
- Add `res/xml/locales_config.xml` (`en`, `de`) and `android:localeConfig` in the manifest so the app appears in the Android per-app language settings.
- Move the remaining hardcoded / raw user-facing texts into string resources (typed errors resolved in Compose), so the German UI contains no English leftovers.
- Add source-scanning unit tests (via `krhnles.moduleDir`): resource key parity incl. plural quantities and identical format placeholders between `values` and `values-de`, no translations of non-translatable strings, informal-address check, locale config in sync with the `values-*` folders, no hardcoded UI literals; plus a Robolectric test that formats every German string / plural with dummy arguments under the `de` locale.
- Android lint (`MissingTranslation`, `ExtraTranslation`, `StringFormatMatches`, `StringFormatInvalid`, `MissingQuantity`, `Typos`) must pass with zero issues and no new suppressions.
- Update README.md, CLAUDE.md (new rule: every new string needs a German translation), docs/architecture.md and docs/requirements.md.

No breaking changes: English stays the default (`values/`) and the fallback for every other locale.

## Capabilities

### New Capabilities
- `localization`: Which languages the UI is offered in, how the language is chosen (system / per-app), completeness and consistency guarantees of translations (key and placeholder parity, informal address, terminology, non-translatable texts) and the rule that no user-facing text bypasses the string resources.

### Modified Capabilities
<!-- None: existing specs describe behaviour, not the language of the texts; their English wording stays valid for the default locale. -->

## Impact

- **Resources:** new `res/values-de/strings.xml`, new `res/xml/locales_config.xml`; `res/values/strings.xml` gains `translatable="false"` on a few entries and new keys for formerly hardcoded texts.
- **Manifest:** `android:localeConfig="@xml/locales_config"` on `<application>`.
- **Code (small):** `SettingsUiState.error` and `PhotoGridUiState.error` become typed / resource-based instead of `String`; login-flow failures are mapped to string resources instead of showing the library message; `FullscreenViewerScreen` uses `R.string.cd_back`.
- **Tests:** new `StringResourceParityTest`, `LocaleConfigTest`, `NoHardcodedUiStringRegressionTest`, `GermanStringsFormatTest` (Robolectric, `qualifiers = "de"`); `SettingsViewModelTest` / `PhotoGridViewModelTest` adjusted to typed errors. Existing Robolectric UI tests keep running in the default English locale.
- **Docs:** README.md, CLAUDE.md, docs/architecture.md, docs/requirements.md.
- **Process:** every future change that adds a string must add its German translation in the same change (enforced by lint `MissingTranslation` and the parity test).
- No new dependencies (per-app language uses the platform `LocaleManager` on API 33+, minSdk is 35; no AppCompat).
