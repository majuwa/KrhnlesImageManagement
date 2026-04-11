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
3. **Background Persistence:** Uploads *must* use `WorkManager` so they do not fail if the user closes the app.
4. **Error Handling:** WebDAV connections fail often due to self-signed certificates or bad URLs. Always provide clear, user-friendly error messages and retry mechanisms.

## 🔒 Security Conventions
- **Credential storage:** Both **username and password** are stored in `EncryptedSharedPreferences` (`krhnles_secure`). Only the server URL and base folder (non-sensitive) live in the unencrypted DataStore.
- **Encryption key:** Use `MasterKey.Builder` with `KeyScheme.AES256_GCM` (never the deprecated `MasterKeys.getOrCreate()`).
- **Backup exclusion:** The credentials DataStore (`datastore/credentials.preferences_pb`) is excluded from both cloud backup and device transfer via `backup_rules.xml` / `data_extraction_rules.xml`.
- **XML parsing (XXE):** `parsePropfindXml()` disables DOCTYPE declarations and external entity resolution on the `DocumentBuilderFactory` to prevent XXE injection.
- **Auth flow origin validation:** `NextcloudAuthRepository.validateSameOrigin()` ensures the `pollEndpoint` and `loginUrl` returned by the server share the same origin as the configured server URL, preventing token theft and phishing via a compromised server response.
- **HTTP warning:** `SettingsUiState.httpWarning` is set to `true` when the user explicitly types an `http://` URL; the UI should surface a warning about plaintext credentials.
- **Minification:** Release builds have `isMinifyEnabled = true`.

## After Every Code Change

After adding a feature or making any code change:

1. **Run all tests** — `./gradlew test` must pass before considering the change done.
2. **Update `README.md`** — if the change affects user-facing behaviour, features, gestures, or visual design.
3. **Update `CLAUDE.md`** — if the change affects architecture, key conventions, package structure, tech stack, or testing patterns.
4. **Run Spotless** -`./gradlew spotlessCheck` spotless muss pass before considering the change done
5. **Run Detect** - no new detekt issues or ask the user whether they are ok

## Coding Guidelines
* do not suppress issues, unless asked to
* if suppress is best decision ask whether it should be done or an alternative should be found
* if a lock or something similar is required, use if possible a timeout so that it will be release
* for every bug request think what are meaningful test can be so that the bug won't happen in the future
* do not disable test unless you asked for permission

# Docuementation
* document ard in docs/architecture.md
