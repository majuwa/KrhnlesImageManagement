# Design: German translation

## Context

See proposal.md – Why. Current state observed in the repository (re-checked 2026-11-09, after the sibling changes were merged):

- One resource file with texts: `app/src/main/res/values/strings.xml` (289 `<string>`, 34 `<plurals>`, no `<string-array>`; every plural has exactly `one` + `other`). `colors.xml` / `themes.xml` and `values-night/` hold no strings. No `values-*` locale folder, no `translatable="false"`, one existing `tools:ignore="PluralsCandidate"` (`upload_history_counts`, English source only). No English text has more than one non-positional placeholder or mixes positional and non-positional ones. One plural `one` has no placeholder (`dialog_auto_date_preview_message`); several plurals have identical `one` / `other` (`upload_history_counts_cancelled`, `notification_upload_partial`, `notification_upload_cancelled_counts`, …).
- Lint runs with `warningsAsErrors = true`, `abortOnError = true`, no baseline. `MissingTranslation` is inactive today because no translation folder exists; it becomes active with `values-de`. Release builds use `isMinifyEnabled` + `isShrinkResources`; strings are referenced through `R`, so shrinking keeps or drops a key in all locales alike — no `keep.xml` is needed.
- minSdk 35 / targetSdk 37: the platform per-app language support (`android:localeConfig`, `LocaleManager`, API 33+) is always available; the app does not use AppCompat. Per-app locales apply to the whole process (also the application context used by workers, receivers, toasts and notifications).
- Source-scanning tests read files through the `krhnles.moduleDir` system property (e.g. `BackupRulesTest`, which parses `res/xml` in a test with `DocumentBuilderFactory`; `NoDomPropfindRegressionTest` only scans the two production PROPFIND files, so DOM parsing inside tests is allowed). `robolectric.properties` sets only `sdk=35`, so Robolectric tests run in its default `en-rUS` (independent of the developer machine's JVM locale); the ~200 English `onNodeWithText("…")` assertions stay valid. **Exception:** `PhotoGridAccessibilityTest` `grid renders the date in the device locale` already runs with `@Config(qualifiers = "de")` and asserts the English "1 item" and "…taken on 1. Mai 2026"; it breaks as soon as `values-de` exists and is adjusted in this change.
- Hardcoded / raw / ViewModel-resolved user-facing texts:
  - `SettingsViewModel`: `"Server URL is required"`, `"Please fill in all fields."` (asserted verbatim in `SettingsViewModelTest`), `LoginFlowState.Failed.message` from the login-flow library, and `getApplication().getString(…)` for `error_credentials_save_failed` and `error_trusted_certificate_clear_failed` (asserted via `getString` in `SettingsViewModelTest` / `SettingsViewModelCertificateTest`). A resolved `String` in ViewModel state keeps the old language after a per-app language switch, because the ViewModel survives the activity recreation.
  - `PhotoGridViewModel`: `throwable.message ?: "Failed to load photos"` (load and refresh; also violates CLAUDE.md rule 7; `PhotoGridViewModelTest` asserts the raw messages `"No permission"` / `"disk error"`).
  - `FullscreenViewerScreen` already uses `R.string.cd_back` (fixed by a sibling); no `contentDescription = "…"` literal remains.
  - Language-neutral literals that stay: `"$current / $total"` progress text in `UploadNotifications` / `DownloadNotifications`, `"%.0f".format(score)` in `BlurReviewScreen`, Compose animation labels (`label = "blur-content"`, `"duplicates-content"`), the auto-date history label `"$first – $last"`.
- Login-flow library (`libs/login-flow-nextcloud`, checked out): `LoginFlowState.Failed(val message: String)` only; its messages are English literals ("Server responded N", "Login timed out. Please try again.", origin-check text, `e.message ?: "Unexpected error"`). There is no typed reason, and branching on message text is forbidden (CLAUDE.md rule 7).
- Locale-dependent formatting is already right and stays unchanged: grid headers (`ofLocalizedDate(LONG)` keyed on the composition locale), upload history (`ofLocalizedDateTime` with `LocalLocale`), certificate dates (`DateFormat.getDateInstance`, default locale, which follows the per-app locale because the activity is recreated). Deliberately locale-independent and unchanged: duration badges and auto date folders (`Locale.ROOT`), album search / sort folding (`Locale.ROOT` lowercase/uppercase/lowercase, no `Collator`), occasion suggestion keys, WebDAV date parsing. The German UI therefore does not change album sort order or folder names.
- Notification channels are (re)created right before use — upload (`createUploadNotificationChannel()` on every worker run and on cancel), download (every `DownloadWorker` run), reminder (`ReminderNotifier.post()`), so an existing channel's name and description switch to the current language the next time the channel is used.

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
| Retry Failed (%d) | Erneut versuchen (%d) | `notification_retry_failed`; the same action is shown on the "Upload cancelled" notification, which must never say "failed" (CLAUDE.md rule 4), so the German stays neutral and short |
| album / albums | das Album / die Alben | |
| occasion | der Anlass | "Anlass / Ordnername" |
| folder | der Ordner | base upload folder → "Basisordner für Uploads" |
| trash (system trash) | der Papierkorb | "In den Papierkorb verschieben" |
| remove (local photos) | entfernen | never "lösch…" anywhere in the removal flow (`action_remove_uploaded`, `action_move_to_trash`, `removal_*`), not even negated: `removal_confirm_message`'s "Nothing is deleted on the server." → "Auf dem Server bleibt alles erhalten." (checked by D7.1) |
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
| Cancel / cancelled | Abbrechen / abgebrochen | "Upload abgebrochen", "Speichern abgebrochen"; never "fehlgeschlagen" for a cancel |
| not uploaded (cancel counts) | nicht hochgeladen | |
| save to device (album download) | auf dem Gerät speichern | "Saving photos to this device" → "Fotos werden auf dem Gerät gespeichert"; channel "Downloads" stays "Downloads" |
| back up / backed up | sichern / gesichert | reminder: "Fotos warten auf die Sicherung" |
| reminder | die Erinnerung | channel "Reminders" → "Erinnerungen" |
| suggestions | Vorschläge | occasion suggestions |
| search / sort | Suchen / Sortieren | "Name A–Z", "Zuletzt geändert (neueste zuerst)" |
| (server) certificate, fingerprint | Zertifikat, Fingerabdruck | "SHA-256-Fingerabdruck" |
| trust (a certificate) | vertrauen | "Diesem Zertifikat vertrauen", "Stop trusting" → "Nicht mehr vertrauen" |
| subject / issuer | Inhaber / Aussteller | certificate review |
| Nextcloud, WebDAV, HTTP, HTTPS, Memories, SHA-256, RSA, EC, `openssl …` commands | unchanged | product / protocol names and command lines verbatim |

All sibling changes that add strings are merged, so the table covers their terms; a later change that introduces a new term adds it to the glossary in docs/architecture.md.

### D3: Translation rules

1. **Placeholders:** exactly the same multiset of placeholders as the English text (argument index + conversion, e.g. `%1$d`, `%2$s`, `%d`). Positional placeholders may be reordered for German word order; non-positional ones (`%d`, `%s`) must keep their order and, if a German sentence needs reordering, the English source is first changed to positional placeholders (same argument list, no code change).
2. **Plurals:** German uses CLDR `one` and `other`; every German plural has exactly the quantities of its English plural (today `one` + `other`). Each German quantity text has the same placeholders as the English text of the *same* quantity: where English `one` contains `%d` / `%1$d`, German keeps it (no hardcoded "1" or "ein"); where English `one` omits it (e.g. `dialog_auto_date_preview_message`), German omits it too. Plurals whose English `one` and `other` are identical (e.g. `selected_count`) still get both quantities.
3. **Escaping:** `\'` for apostrophes, `\"` for straight double quotes, `&amp;` / `&lt;`, `\\` for a literal backslash (e.g. `error_album_name_invalid`), `\n` only where the English text has one. Quoted UI terms use German typographic quotes „…“ (U+201E / U+201C), which need no escaping, e.g. `Album „%1$s“ löschen?`.
4. **Typography:** the ellipsis character `…` (never three dots), `–` / `·` / `•` as in the English text, non-breaking space not required. Sentence fragments inserted into other texts (`upload_error_*` after "Failed to create folder: %1$s") keep their lowercase start and must read correctly in the combined German sentence; combined sentences go through `notification_text_with_reason`, never concatenation.
   **Cross-references:** a text that names another UI label (e.g. "Test Connection in Settings", "use Stop trusting", "Open Settings") uses exactly the German translation of that label (`action_test_connection`, `action_stop_trusting`, `title_settings`) in „…“, so the user finds the button the text refers to.
5. **Length:** German is ~30 % longer; for buttons, tabs, notification actions and chips choose the shorter synonym and check the screens in the `de` locale (Compose previews or a device). No `maxLines` / font-size workarounds in this change.
6. **Non-translatable texts** are marked `translatable="false"` in `values/strings.xml` and not copied. The complete list at review time: `app_name` (brand), `placeholder_nextcloud_url`, `placeholder_webdav_url`, `placeholder_base_folder` (`Photos/KrohnSync`, a path example matching the default Nextcloud folder), and the word-free format templates `notification_text_with_reason` (`%1$s %2$s`), `preview_upload_path` (`• %s`), `viewer_page_counter` (`%1$d / %2$d`), `video_elapsed_of_total` (`%1$s / %2$s`), `notification_uploading_file_progress` (`%1$d / %2$d · %3$d %%`; German also writes "50 %" with a space). Texts with words stay translatable even if short (`video_position_state` "%1$s of %2$s" → "%1$s von %2$s", `nav_photos` → "Fotos"). Texts containing a command line (`certificate_trust_dialog_*`: `openssl x509 -noout -fingerprint -sha256`) are translated but keep the command verbatim. Lint `Untranslatable` and the parity test reject German copies of non-translatable keys. Alternative `donottranslate.xml` rejected: keeping the attribute next to the string makes the decision visible where the string is edited.
7. **No new suppressions:** no `tools:ignore` in `values-de`; if lint flags a German text (e.g. `PluralsCandidate`, `Typos`), the text is rephrased; if that is impossible, the user is asked (CLAUDE.md Coding Guidelines).

### D4: Hand-written `locales_config.xml` plus a sync test

`res/xml/locales_config.xml` lists `<locale android:name="en"/>` and `<locale android:name="de"/>`; `AndroidManifest.xml` sets `android:localeConfig="@xml/locales_config"`. A test (D7) checks that the listed locales equal `en` (the default `values/`) plus the language qualifier of every `values-<lang>` folder that contains string resources (non-language folders such as `values-night` hold no strings and are ignored), and that the manifest references the file.

`androidResources.localeFilters` stays unset (non-goal, see above): it only affects which *library* translations are packaged, not the app's own texts or the per-app language list, which comes from the hand-written file.

Alternative: AGP `androidResources { generateLocaleConfig = true }` with `res/resources.properties` (`unqualifiedResLocale=en`). Rejected for now: it adds a build-time generated manifest attribute that is harder to review and test with the existing source-scanning approach, and whether AGP also counts locales of dependency resources would need to be verified; the hand-written file is two lines and the test keeps it in sync.

Per-app language changes recreate the activity (no `configChanges` declared); ViewModels and `rememberSaveable` state survive. No AppCompat / `AppCompatDelegate.setApplicationLocales` is needed on API 33+.

### D5: Typed errors instead of hardcoded / raw texts

- `SettingsUiState.error: String?` becomes a typed value resolved in Compose (`@StringRes` id or a small sealed `SettingsError`), covering "server URL required", "fill in all fields", credential save failure, trusted-certificate clear failure (`error_trusted_certificate_clear_failed`, set on logout) and login failure. No `getApplication().getString(…)` remains in `SettingsViewModel`, so a per-app language switch (activity recreated, ViewModel kept) re-renders the error in the new language.
- Login-flow failures: the library's `message` is never shown. Decided now (library inspected): `LoginFlowState.Failed` carries only `message: String`, so **every** `Failed` maps to one generic string (e.g. `error_login_failed`: "Login failed. Check the server address and try again."), including the polling timeout; the message is never parsed (CLAUDE.md rule 7). The message is logged by type only (`Failed`), never its text. Distinct reasons would need a typed state in the library first (out of scope).
- `PhotoGridUiState.error: String?` becomes a typed value / string resource ("photos could not be loaded"); the exception text is dropped (log type only).
- `model/UploadState.Failure(message: String)` is never constructed today and is left alone (no behaviour change); it is not a user-facing path.
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
   - Escaping sanity: German texts contain no unescaped straight apostrophe / quote that aapt would reject or strip (the parsed text — where `\'` / `\"` survive as two characters and `&apos;` / `&quot;` become bare `'` / `"` — has no `'` or `"` not preceded by `\`), and no three-dot `...`.
   - Informal address: in German texts, `Sie`, `Ihr`, `Ihre`, `Ihren`, `Ihrem`, `Ihrer`, `Ihres` must not appear capitalized except at a sentence start (start of the text, after `.`, `!`, `?`, `:` or a `\n`, where "Sie"/"Ihre" can mean "they"/"their"); `Ihnen` is rejected everywhere. Conversely `Du`, `Dich`, `Dir`, `Dein…` must not appear capitalized mid-sentence (D1: lowercase).
   - Removal wording: the German texts of `action_remove_uploaded`, `action_move_to_trash` and every `removal_*` key contain no `lösch` (case-insensitive), so the local trash flow is never called deleting (D2).
   - Self-tests of the extractors on inline fixtures (positional / non-positional / `%%` / `%1$s` vs `%1$d`), like the matcher self-test in `NoLintSuppressionRegressionTest`.
2. **`LocaleConfigTest`** (plain JUnit): locales in `res/xml/locales_config.xml` == `{en}` ∪ languages of `values-<lang>` folders containing strings; manifest `<application>` has `android:localeConfig="@xml/locales_config"`.
3. **`GermanStringsFormatTest`** (Robolectric, `@Config(qualifiers = "de")`): for every translatable string and every plural quantity (counts 1 and 2), resolve the id by name and format with dummy arguments derived from the placeholders (`d` → 2, `s` → "x"); assert no exception and that the German text differs from English for a fixed sample of keys (proves the `de` folder is picked up). Plus three small cases: a sample key resolves to the same German text under `qualifiers = "de-rAT"` and `"de-rCH"` and to the English text under `"fr"` (spec: regional variants / fallback); and the upload, download and reminder notification channels created under `de` (`createUploadNotificationChannel()`, `createDownloadNotificationChannel()`, `ReminderNotifier.post` path or its channel helper) carry the German `channel_name_*` / `reminder_channel_name` (spec: localized background texts). Uses `Resources.getIdentifier` in test code only.
4. **`NoHardcodedUiStringRegressionTest`** (plain JUnit): scans all of `src/main/java/**/*.kt` (not only `ui/`: notifications and toasts are built in `worker/`, `download/`, `reminder/`, `upload/`) for `contentDescription = "`, `Text("`, `text = "`, `error = "`, `label = "`, `title = "`, `message = "`, `setContentTitle("`, `setContentText("`, `Toast.makeText(<ctx>, "` and `?: "` fallbacks next to `.message` literals that contain a letter outside `${…}` templates and outside `%…` format specifiers (so `"%.0f".format(score)` and `"$current / $total"` pass); an explicit allow-list for non-UI `label` uses (Compose `AnimatedContent(label = "…")` animation labels) is kept in the test with a reason per entry. Self-tests for the matcher (positive: `contentDescription = "Back"`, `error = "Server URL is required"`, `throwable.message ?: "Failed to load photos"`; negative: `"%.0f"`, `"$current / $total"`).
5. Adjusted: `SettingsViewModelTest` and `SettingsViewModelCertificateTest` (typed errors instead of English literals / ViewModel-resolved strings), `PhotoGridViewModelTest` (typed load error instead of the raw `"No permission"` / `"disk error"` messages), `PhotoGridAccessibilityTest` `grid renders the date in the device locale` (runs under `qualifiers = "de"`: expects the German item count and content description, resolved via resources, not hardcoded English), and any Compose test asserting the changed texts.

## Risks / Trade-offs

- [Sibling strings added after this change lands are forgotten] → `MissingTranslation` (error under `warningsAsErrors`) and `StringResourceParityTest` fail; CLAUDE.md gets the rule; tasks re-check parity after rebasing onto the latest main.
- [Translation quality / wrong context for short strings] → glossary + rules; the implementer checks each screen in the `de` locale; strings with `%` arguments carry the existing XML comments; context-poor keys get a comment in `values/strings.xml` where needed.
- [German texts too long for buttons, notification actions, bottom bar] → shortest glossary synonym; screen check in `de`; no layout workarounds in this change (follow-up issue if one is needed).
- [Lint `Typos` / `PluralsCandidate` flags German texts] → rephrase; never suppress without asking.
- [Heuristic informal-address check gives a false positive] → it only flags capitalized forms mid-sentence, which are formal address in German by definition; a false positive is fixed by rephrasing, not by an allow-list.
- [One generic login error hides the reason (timeout vs. bad server)] → accepted; the library exposes no typed reason and messages are never parsed; the connection test in Settings gives typed diagnostics.
- [A notification channel keeps its old name until it is used again; notifications already shown stay in the old language] → acceptable; each channel is re-created right before its next use.
- [Tests depending on the locale] → Robolectric defaults to `en-rUS` whatever the machine locale; the only existing `qualifiers = "de"` test is adjusted (D7.5); new German tests set the qualifier explicitly.
- [Robolectric `getIdentifier` lint `DiscouragedApi`] → only in test sources, which `lintDebug` does not check by default; if it does, use reflection over `R.string` / `R.plurals` fields instead.

## Migration Plan

Pure addition; no data migration. Rollback = delete `values-de/`, `locales_config.xml` and the manifest attribute (the typed-error refactoring can stay). Users on German devices see German on the first launch after the update; others are unaffected.

## Open Questions

- Should the login-flow library itself get German resources (separate repository / submodule)? Out of scope here; only relevant if the library shows UI of its own.
- Should a later change add an in-app shortcut to the system app-language screen in Settings? Deferred; does not affect this change.
