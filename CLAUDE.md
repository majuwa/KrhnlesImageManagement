# AI Agent Instructions - Kröhnle's Image Management

You are an expert Android developer assisting in building "Kröhnle's Image Management", a privacy-first photo backup tool for WebDAV/Nextcloud.

## 🎯 Project Vision
To provide a manual, user-curated image backup flow where users select local device photos, tag them with an occasion, and seamlessly upload them to a WebDAV directory in the background.

## 🛠️ Tech Stack & Architecture
- **Language:** Kotlin (Strictly avoid Java).
- **UI Framework:** Jetpack Compose (No XML layouts).
- **Architecture:** MVVM (Model-View-ViewModel) with Clean Architecture principles.
- **Async & Background:** Kotlin Coroutines, Flow, and Android WorkManager for uploads.
- **Cloud Communication:** WebDAV protocol via OkHttp.

## 📏 Coding Standards & Rules
1. **Compose Best Practices:** Ensure states are hoisted appropriately. Use `LazyVerticalGrid` for image loading to ensure high performance.
2. **Permissions:** Handle Android 13+ granular media permissions (`READ_MEDIA_IMAGES`) gracefully with appropriate user prompts.
3. **Navigation-scoped state:** Child destinations that share a parent's ViewModel resolve the parent entry with `rememberParentEntry(navController, backStack, PARENT_ROUTE)` (`ui/navigation/BackStackEntries.kt`), keyed on the composable's own `NavBackStackEntry`. Album sub-routes use `sharedAlbumsViewModel(navController, backStack)` in `KrhnlesApp.kt`. Never `remember` a `getBackStackEntry()` call with a constant key and never suppress `UnrememberedGetBackStackEntry` by any mechanism — annotation, `noinspection`, Gradle `lint {}` options, `lint.xml` or a baseline (guarded by `NoLintSuppressionRegressionTest`). Source-scanning unit tests get the module dir via the `krhnles.moduleDir` system property.
4. **Background Persistence:** Uploads *must* use `WorkManager` so they do not fail if the user closes the app.
5. **Error Handling:** WebDAV connections fail often due to self-signed certificates or bad URLs. Always provide clear, user-friendly error messages and retry mechanisms.
6. **One-shot Flow reads:** Read a single current value with `flow.first()` / `firstOrNull()`, never `collect { ...; return@collect }` — the early return does not cancel the upstream flow and leaks the collecting coroutine until its scope dies (issue #22 / #41, guarded by `NoCollectEarlyReturnRegressionTest`). Reserve `collect` for flows meant to be observed for the scope's lifetime. User-triggered one-shot actions (e.g. `SettingsViewModel.testConnection()`) keep a `Job` reference and ignore re-entry while it is active; such a guard must always be released — bound the work with a timeout, convert every failure into a result, cancel and null the `Job` when the session/config it belongs to changes, and call `ensureActive()` before publishing so a cancelled job never writes stale state.
7. **User-facing WebDAV errors:** Never show raw exception text. Map failures to a typed result (see `ConnectionTestResult` / `Throwable.toConnectionTestResult()`) and resolve it to a string resource. `WebDavClient` reports non-2xx statuses as `WebDavHttpException(code)` — branch on `code`, never parse messages. Only `testConnection()` has an overall call timeout (per call); never set `callTimeout` on the shared client, it would break large uploads.
8. **Photo deletion:** Every album photo-deletion entry point goes through `AlbumsRepositoryContract.deletePhotosIndividually()` (`data/PhotoDeletionResult.kt`, next to the repository contract) and, inside `AlbumsViewModel`, the private `launchPhotoDeletion()` — never loop over `deletePhoto()` yourself. Remove only `PhotoDeletionResult.deletedHrefs` from UI state (failed photos stay visible), reset busy state in `finally`, map non-cancellation failures to a result (`runCatching` + rethrow `CancellationException`), and ignore re-entry while `photoDeletionJob` is active. Report the result through the one-shot `photoDeletionOutcome` state consumed by `AlbumDetailScreen` — never through screen callbacks or a snackbar in a screen that navigates away. All entry points are covered by the parameterized `AlbumsViewModelPhotoDeletionTest` (`ParameterizedRobolectricTestRunner`); add new entry points to its `DeletePath` enum (issues #28 / #47).

## 🔒 Security Conventions
- **Credential storage:** Both **username and password** are encrypted via the Android Keystore (AES-256-GCM) and stored in standard `SharedPreferences` (`krhnles_secure`). Only the server URL and base folder (non-sensitive) live in the unencrypted DataStore.
- **Encryption key:** Generated and managed by the Android Keystore (`AndroidKeyStore` provider) under the alias `krhnles_master_key`. The old `EncryptedSharedPreferences`/`MasterKey` approach was replaced because those APIs are deprecated.
- **Decryption failures:** `CredentialCipher.decrypt()` must not silently collapse failures into `null`; it maps crypto/Keystore/IO failures to a classified `DecryptResult`. `CredentialStore` logs failures once per distinct failure (field + exception type only, never secrets) and exposes `credentialState` (`Missing` / `Valid` / `Corrupted(KEY_INVALIDATED|UNREADABLE)`), which Settings surfaces to the user. Encryption recovers once (delete + regenerate key) from any failure classified `KEY_INVALIDATED`, including during key retrieval. Tests use `fakes/SoftwareKeyProvider` instead of the Android Keystore.
- **Credential flows & saves:** All credential-derived flows come from one snapshot flow (`flowOn(ioDispatcher)`). Any change to the secure prefs must be accompanied by a `credentials_version` bump in a DataStore edit, written *after* the prefs, or observers will not re-emit. `save()` encrypts everything before writing anything and throws `CredentialSaveException` on failure; callers must catch it and show a string-resource error.
- **Backup exclusion:** The credentials DataStore (`datastore/credentials.preferences_pb`) is excluded from both cloud backup and device transfer via `backup_rules.xml` / `data_extraction_rules.xml`.
- **XML parsing (XXE):** `parsePropfindXml()` strips DOCTYPE declarations before parsing and sets `isExpandEntityReferences = false`. Android's Harmony/Expat parser doesn't support the standard SAX security features, so DOCTYPE stripping is the primary XXE defense.
- **Auth flow origin validation:** `NextcloudAuthRepository.validateSameOrigin()` ensures the `pollEndpoint` and `loginUrl` returned by the server share the same origin as the configured server URL, preventing token theft and phishing via a compromised server response.
- **Auth polling timeout:** `NextcloudAuthRepository.loginFlow()` enforces a maximum polling duration and fails with a user-facing timeout message instead of polling forever.
- **HTTP warning:** `SettingsUiState.httpWarning` is set to `true` when the user explicitly types an `http://` URL; the UI should surface a warning about plaintext credentials.
- **Minification:** Release builds have `isMinifyEnabled = true`.
- **WebDAV path hardening:** `WebDavClient` validates path segments (`.` / `..` forbidden) and uses `HttpUrl` path-segment encoding to prevent traversal and malformed URL injection.
- **Server href validation:** Never build a URL as `"$origin$href"` from a server-supplied href (PROPFIND `d:href`). Use `resolveWebDavHref(root, href)` (`data/WebDavHref.kt`): it rejects `.` / `..` (also percent-encoded), encoded `/` or `\`, backslash, query and fragment on the *raw* href (before `HttpUrl.resolve()` normalizes dots away), then requires the same scheme/host/port and a path strictly below `root` (`AlbumsRepository`: the configured base folder; listed photos: their album). Skip unsafe entries in listings; fail requests for them with `UnsafeHrefException` without sending anything; never log the href or credentials.
- **Auth interceptor origin restriction:** OkHttp interceptors that add `Authorization` (`AlbumsRepository`, the Coil image loader in `KrhnlesApplication`) must only do so when `request.url.hasSameOriginAs(configured URL)`; credentials are never sent to another host or port.

## After Every Code Change

After adding a feature or making any code change:

1. **Run all tests** — `./gradlew test` must pass before considering the change done.
2. **Update `README.md`** — if the change affects user-facing behaviour, features, gestures, or visual design.
3. **Update `CLAUDE.md`** — if the change affects architecture, key conventions, package structure, tech stack, or testing patterns.
4. **Run Spotless** -`./gradlew spotlessCheck` spotless muss pass before considering the change done
5. **Run Detekt** - `./gradlew detekt` — no new detekt issues or ask the user whether they are ok
6. **Run Android Lint** — `./gradlew lintDebug` must pass with zero issues (no baseline — all issues must be fixed)
7. **Generate coverage** — `./gradlew jacocoTestReport` produces an HTML report at `app/build/reports/jacoco/jacocoTestReport/html/`

## 🌐 String Resources
- All user-facing strings are in `app/src/main/res/values/strings.xml` — never hardcode strings in Kotlin/Compose.
- Use `pluralStringResource(R.plurals.xxx, count, count)` for count-dependent text (e.g. "1 photo" / "3 photos").
- In non-Composable contexts (workers, callbacks), use `resources.getQuantityString(R.plurals.xxx, count, count)`.
- In Compose callbacks, use `LocalResources.current` (not `LocalContext.current.resources`) to ensure Configuration-change awareness.

## Coding Guidelines
* do not suppress issues, unless asked to
* if suppress is best decision ask whether it should be done or an alternative should be found
* if a lock or something similar is required, use if possible a timeout so that it will be release
* for every bug request think what are meaningful test can be so that the bug won't happen in the future
* do not disable test unless you asked for permission

# Docuementation
* document ard in docs/architecture.md
