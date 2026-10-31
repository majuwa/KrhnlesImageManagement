# Design: German translation

## Context

See proposal.md – Why. Current state observed in the repository (2026-10-31):

- One resource file with texts: `app/src/main/res/values/strings.xml` (205 `<string>`, 25 `<plurals>`, all plurals use `one` / `other`). No `values-*` locale folder, no `translatable="false"`, one existing `tools:ignore="PluralsCandidate"` (`upload_history_counts`). The sibling changes listed in proposal.md add more strings before this change is implemented, so nothing below enumerates the string list.
- Lint runs with `warningsAsErrors = true`, `abortOnError = true`, no baseline. `MissingTranslation` is inactive today because no translation folder exists; it becomes active with `values-de`.
- minSdk 35 / targetSdk 37: the platform per-app language support (`android:localeConfig`, `LocaleManager`, API 33+) is always available; the app does not use AppCompat.
- Source-scanning tests read files through the `krhnles.moduleDir` system property (e.g. `BackupRulesTest`, which parses `res/xml` in a test with `DocumentBuilderFactory`; `NoDomPropfindRegressionTest` only scans the two production PROPFIND files, so DOM parsing inside tests is allowed). Robolectric tests run in the default `en-US` locale, so existing English text assertions stay valid.
- Hardcoded / raw user-facing texts found outside resources:
  - `SettingsViewModel`: `"Server URL is required"`, `"Please fill in all fields."` (asserted verbatim in `SettingsViewModelTest`), `LoginFlowState.Failed.message` from the login-flow library (submodule `libs/login-flow-nextcloud`, not checked out at proposal time), and `getString(R.string.error_credentials_save_failed)` resolved in the ViewModel (not configuration-change aware).
  - `PhotoGridViewModel`: `throwable.message ?: "Failed to load photos"` (twice; also violates CLAUDE.md rule 7).
  - `FullscreenViewerScreen`: `contentDescription = "Back"` (`R.string.cd_back` exists).
- Date texts are already locale-aware (`ofLocalizedDate(LONG).withLocale(locale)`); duration badges and auto date folders deliberately use `Locale.ROOT` and stay unchanged.
- `UploadWorker` calls `createNotificationChannel()` on every run, so an existing channel's name and description are updated to the current language on the next upload.

## Goals / Non-Goals

**Goals:**
- A complete, consistent German translation of every translatable text present at implementation time.
- Automated guards so a missing, extra or format-incompatible translation fails `./gradlew test` and `lintDebug`.
- Per-app language selection through the system settings.
- No English leftovers from hardcoded or raw texts.

**Non-Goals:**
- An in-app language picker (the system per-app language screen is enough; no `ACTION_APP_LOCALE_SETTINGS` shortcut in this change).
- Region variants (`values-de-rCH` with "ss" instead of "ß", `values-de-rAT`): `values-de` serves all German regions.
- Restricting library translations via `androidResources.localeFilters`: library texts (Material, system dialogs) already follow the device language; changing packaging is a separate decision.
- Translating server-side content (folder names, album names), auto date folder names (`YYYY/MM/DD`, `Locale.ROOT`) or README / docs.
- Translating the login-flow library's own UI (it opens the browser; the Nextcloud login page is localized by the server).
- A formal "Sie" variant.

## Decisions

### D1: Informal "du", lowercase, impersonal where possible

German UIs of the reference products disagree: Nextcloud ships its default German (`de`) informal ("du") and a separate formal variant (`de_DE`, "Sie"); the Android system UI has traditionally used the formal "Sie"; most consumer and messaging apps aimed at private users use "du". The app is a privacy-first tool for private / family photo backups to one's own Nextcloud, and Nextcloud's default German is "du", so the app's German will feel continuous with the server's web UI that the user sees during the browser login.

Rules:
- Buttons, menu items, titles and actions use the infinitive / noun style without any pronoun ("Hochladen", "Erneut versuchen", "Verbindung testen", "In den Papierkorb verschieben").
- Sentences that need direct address use "du", "dich", "dir", "dein" in lowercase (current Duden recommendation for UI text), imperative singular ("Prüfe die Serveradresse").
- Never "Sie" / "Ihnen" / "Ihr" / "Ihre" as address; enforced heuristically by a test (D7).

Alternative considered: formal "Sie" (matches Android system settings, Nextcloud `de_DE`). Rejected: does not match the private-use target audience nor Nextcloud's default German, and mixing would be worse than either; a single consistent choice matters most.

### D2: Glossary (binding for this and every later change)

| English | German | Note |
|---|---|---|
| Upload (verb, button) | Hochladen / hochladen | `action_upload` → "Hochladen" |
| upload (noun), uploads | der Upload, die Uploads | "Upload abgeschlossen", "Upload-Verlauf" |
| Uploading … | Wird hochgeladen … / Lade … hoch | progress texts, e.g. "Lade %1$d / %2$d hoch" |
| Retry | Erneut versuchen | |
| Retry Failed (%d) | Fehlgeschlagene wiederholen (%d) | notification action, keep short |
| album / albums | das Album / die Alben | |
| occasion | der Anlass | "Anlass / Ordnername" |
| folder | der Ordner | base upload folder → "Basisordner für Uploads" |
| trash (system trash) | der Papierkorb | "In den Papierkorb verschieben" |
| remove (local photos) | entfernen | never "löschen" on the local removal path |
| delete (server) | löschen | server photos / albums only |
| Wi-Fi | WLAN | "Nur über WLAN hochladen" |
| metered / unmetered | getaktet / nicht getaktet | Android's German term |
| photo / video | das Foto / das Video | |
| item(s) | das Element / die Elemente | counts of photos and videos |
| Settings | Einstellungen | |
| server address / URL | Serveradresse / URL | |
| username | Benutzername | |
| app password / app token | App-Passwort / App-Token | |
| log in / log out | anmelden / abmelden | "Disconnect" → "Trennen" |
| connection | die Verbindung | "Test Connection" → "Verbindung testen" |
| certificate, self-signed | Zertifikat, selbstsigniert | |
| blurry | unscharf | "Unscharfe Fotos" |
| blur sensitivity | Unschärfe-Empfindlichkeit | Low / Medium / High → Niedrig / Mittel / Hoch |
| duplicates | Duplikate | |
| share (Android share) | teilen | |
| verify | prüfen | "Durch erneutes Herunterladen prüfen" |
| notification | die Benachrichtigung | |
| select / deselect | auswählen / Auswahl aufheben | |
| upload history | Upload-Verlauf | |
| auto date folders | Automatische Datumsordner | pattern `YYYY/MM/DD` described as "JJJJ/MM/TT", example digits unchanged |
| storage full | Speicher voll | |
| Nextcloud, WebDAV, HTTP, HTTPS, Memories | unchanged | product / protocol names |

Siblings may introduce new terms (chunked upload, cancel, pinned certificate, download, search / sort, reminder); the implementer extends this glossary in docs/architecture.md with the terms chosen, keeping the existing ones.

### D3: Translation rules

1. **Placeholders:** exactly the same multiset of placeholders as the English text (argument index + conversion, e.g. `%1$d`, `%2$s`, `%d`). Positional placeholders may be reordered for German word order; non-positional ones (`%d`, `%s`) must keep their order and, if a German sentence needs reordering, the English source is first changed to positional placeholders (same argument list, no code change).
2. **Plurals:** German uses CLDR `one` and `other`; every German plural has exactly the quantities of its English plural (today `one` + `other`). Each German quantity text has the same placeholders as the English text of the *same* quantity: where English `one` contains `%d` / `%1$d`, German keeps it (no hardcoded "1" or "ein"); where English `one` omits it (e.g. `dialog_auto_date_preview_message`), German omits it too. Plurals whose English `one` and `other` are identical (e.g. `selected_count`) still get both quantities.
3. **Escaping:** `\'` for apostrophes, `\"` for straight double quotes, `&amp;` / `&lt;`, `\\` for a literal backslash (e.g. `error_album_name_invalid`), `\n` only where the English text has one. Quoted UI terms use German typographic quotes „…“ (U+201E / U+201C), which need no escaping, e.g. `Album „%1$s“ löschen?`.
4. **Typography:** the ellipsis character `…` (never three dots), `–` / `·` / `•` as in the English text, non-breaking space not required. Sentence fragments inserted into other texts (`upload_error_*` after "Failed to create folder: %1$s") keep their lowercase start and must read correctly in the combined German sentence; combined sentences go through `notification_text_with_reason`, never concatenation.
5. **Length:** German is ~30 % longer; for buttons, tabs, notification actions and chips choose the shorter synonym and check the screens in the `de` locale (Compose previews or a device). No `maxLines` / font-size workarounds in this change.
6. **Non-translatable texts** are marked `translatable="false"` in `values/strings.xml` and not copied: `app_name` (brand), `placeholder_nextcloud_url`, `placeholder_webdav_url`, `placeholder_base_folder` (folder path example; "Photos" is also the default Nextcloud folder name), `notification_text_with_reason` (`%1$s %2$s`), `preview_upload_path` (`• %s`), plus any sibling string of the same kind. Lint `ExtraTranslation` and the parity test reject German copies. Alternative `donottranslate.xml` rejected: keeping the attribute next to the string makes the decision visible where the string is edited.
7. **No new suppressions:** no `tools:ignore` in `values-de`; if lint flags a German text (e.g. `PluralsCandidate`, `Typos`), the text is rephrased; if that is impossible, the user is asked (CLAUDE.md Coding Guidelines).

### D4: Hand-written `locales_config.xml` plus a sync test

`res/xml/locales_config.xml` lists `<locale android:name="en"/>` and `<locale android:name="de"/>`; `AndroidManifest.xml` sets `android:localeConfig="@xml/locales_config"`. A test (D7) checks that the listed locales equal `en` (the default `values/`) plus the language qualifier of every `values-<lang>` folder that contains string resources, and that the manifest references the file.

Alternative: AGP `androidResources { generateLocaleConfig = true }` with `res/resources.properties` (`unqualifiedResLocale=en`). Rejected for now: it adds a build-time generated manifest attribute that is harder to review and test with the existing source-scanning approach, and whether AGP also counts locales of dependency resources would need to be verified; the hand-written file is two lines and the test keeps it in sync.

Per-app language changes recreate the activity (no `configChanges` declared); ViewModels and `rememberSaveable` state survive. No AppCompat / `AppCompatDelegate.setApplicationLocales` is needed on API 33+.

### D5: Typed errors instead of hardcoded / raw texts

- `SettingsUiState.error: String?` becomes a typed value resolved in Compose (`@StringRes` id or a small sealed `SettingsError`), covering "server URL required", "fill in all fields", credential save failure and login failures. This also fixes the ViewModel-resolved `getString(...)` that would not follow a language change.
- Login-flow failures: the library's `message` is never shown. At implementation time the implementer inspects `LoginFlowState.Failed` in `libs/login-flow-nextcloud`; if it exposes a typed reason (e.g. timeout, origin mismatch, network), each maps to its own string; otherwise a single generic "login failed, try again" string is used. The polling-timeout case keeps a dedicated message if the library distinguishes it.
- `PhotoGridUiState.error: String?` becomes a typed value / string resource ("photos could not be loaded"); the exception text is dropped (log type only).
- `FullscreenViewerScreen` uses `stringResource(R.string.cd_back)`.
- New English keys get German translations like every other string.

### D6: Source-level guarantee, not runtime checks

Parity and placeholder correctness are checked at test time from the XML sources (fast, no Robolectric), plus one Robolectric test that actually formats each German text. No runtime fallback logic is added; Android already falls back to `values/` for any key missing in `values-de`, but the tests ensure that never happens.

### D7: Tests

All source-scanning tests locate files via `System.getProperty("krhnles.moduleDir")` and fail with a clear message if the directory is missing (pattern of `NoLintSuppressionRegressionTest`). XML is parsed with the JDK DOM parser in test code (like `BackupRulesTest`).

1. **`StringResourceParityTest`** (plain JUnit):
   - Collects `<string>`, `<plurals>` (with quantities) and `<string-array>` (with item count) from *all* XML files in `src/main/res/values/` and `src/main/res/values-de/`, so strings added by siblings in other files are covered.
   - Translatable default keys == German keys (reports missing and extra separately, sorted).
   - Keys with `translatable="false"` do not appear in `values-de`.
   - Plural quantities equal per plural; each German plural has at least `one` and `other`.
   - Placeholders: extract `%[index$][flags][width][.precision]conversion` (ignoring `%%`), number non-positional ones sequentially, compare as a multiset of (index, conversion) per string and per plural quantity (German `one` against English `one`, `other` against `other`); English positional and non-positional must not be mixed within one text.
   - Escaping sanity: German texts contain no unescaped straight apostrophe / quote that aapt would reject or strip (the raw XML text has no `'` or `"` not preceded by `\`), and no three-dot `...`.
   - Informal address: in German texts, `Sie`, `Ihnen`, `Ihr`, `Ihre`, `Ihren`, `Ihrem`, `Ihrer`, `Ihres` must not appear capitalized except at the start of a text or after `.`, `!`, `?`, `:` (sentence start, where "Sie"/"Ihre" can mean "they"/"their"); `Ihnen` is rejected everywhere.
   - Self-tests of the extractors on inline fixtures (positional / non-positional / `%%` / `%1$s` vs `%1$d`), like the matcher self-test in `NoLintSuppressionRegressionTest`.
2. **`LocaleConfigTest`** (plain JUnit): locales in `res/xml/locales_config.xml` == `{en}` ∪ languages of `values-<lang>` folders containing strings; manifest `<application>` has `android:localeConfig="@xml/locales_config"`.
3. **`GermanStringsFormatTest`** (Robolectric, `@Config(qualifiers = "de")`): for every translatable string and every plural quantity (counts 1 and 2), resolve the id by name and format with dummy arguments derived from the placeholders (`d` → 2, `s` → "x"); assert no exception and that the German text differs from English for a fixed sample of keys (proves the `de` folder is picked up). Uses `Resources.getIdentifier` in test code only.
4. **`NoHardcodedUiStringRegressionTest`** (plain JUnit): scans `src/main/java/**/ui/**/*.kt` for `contentDescription = "`, `Text("`, `text = "`, `error = "`, `label = "` / `title = "` literals that contain a letter outside `${…}` templates; an explicit allow-list for non-UI `label` uses (Compose `AnimatedContent(label = "…")` animation labels) is kept in the test with a reason per entry. Self-tests for the matcher.
5. Adjusted: `SettingsViewModelTest` (typed errors instead of English literals), `PhotoGridViewModelTest` (typed load error), and any Compose test asserting the changed texts.

## Risks / Trade-offs

- [Sibling strings added after this change lands are forgotten] → `MissingTranslation` (error under `warningsAsErrors`) and `StringResourceParityTest` fail; CLAUDE.md gets the rule; tasks re-check parity after rebasing onto the latest main.
- [Translation quality / wrong context for short strings] → glossary + rules; the implementer checks each screen in the `de` locale; strings with `%` arguments carry the existing XML comments; context-poor keys get a comment in `values/strings.xml` where needed.
- [German texts too long for buttons, notification actions, bottom bar] → shortest glossary synonym; screen check in `de`; no layout workarounds in this change (follow-up issue if one is needed).
- [Lint `Typos` / `PluralsCandidate` flags German texts] → rephrase; never suppress without asking.
- [Heuristic informal-address check gives a false positive] → it only flags capitalized forms mid-sentence, which are formal address in German by definition; a false positive is fixed by rephrasing, not by an allow-list.
- [Login-flow library failure types unknown at proposal time] → D5 defines both outcomes (typed mapping or one generic string); the spec only requires a localized message.
- [Notification channel keeps its old name until the next upload] → acceptable; the channel is re-created on each worker run.
- [Robolectric `getIdentifier` lint `DiscouragedApi`] → only in test sources, which `lintDebug` does not check by default; if it does, use reflection over `R.string` / `R.plurals` fields instead.

## Migration Plan

Pure addition; no data migration. Rollback = delete `values-de/`, `locales_config.xml` and the manifest attribute (the typed-error refactoring can stay). Users on German devices see German on the first launch after the update; others are unaffected.

## Open Questions

- Should the login-flow library itself get German resources (separate repository / submodule)? Out of scope here; only relevant if the library shows UI of its own.
- Should a later change add an in-app shortcut to the system app-language screen in Settings? Deferred; does not affect this change.
