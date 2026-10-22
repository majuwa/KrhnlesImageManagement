# Architecture

## Overview

Kröhnle's Image Management follows **MVVM + Clean Architecture** using Kotlin and Jetpack Compose.

## Package Structure

```
de.majuwa.android.paper.krhnlesimagemanagement/
├── MainActivity.kt          # Entry point, sets up Compose and WorkManager uploads; handles share intents
├── KrhnlesApp.kt            # Navigation host (NavHost with routes)
├── model/
│   ├── Photo.kt             # Photo data class (id, uri, date, size, mime)
│   ├── WebDavConfig.kt      # WebDAV connection configuration
│   ├── LoginFlowState.kt    # Sealed class for Nextcloud Login Flow v2 states
│   ├── CredentialState.kt   # Stored-credential health (Missing / Valid / Corrupted + CredentialCorruption reason)
│   └── UploadState.kt       # Sealed interface for upload progress states
├── data/
│   ├── MediaRepository.kt         # Queries device photos via ContentResolver
│   ├── CredentialStore.kt         # Encrypted credential storage (Android Keystore AES-256-GCM + SharedPreferences)
│   ├── CredentialCipher.kt        # AES-GCM encrypt/decrypt with classified DecryptResult; injectable CredentialKeyProvider
│   ├── UploadHistoryStore.kt      # Persists upload history entries in DataStore
│   ├── UploadedPhotosStore.kt     # DataStore-backed storage of uploaded photo IDs (prevents duplicate uploads)
│   ├── NextcloudAuthRepository.kt # Nextcloud Login Flow v2 implementation
│   ├── Repositories.kt            # Repository interfaces (CredentialRepository, UploadedPhotosRepositoryContract, …)
│   ├── AlbumsRepository.kt        # Remote albums/photos (PROPFIND, DELETE, MOVE, thumbnails); validates server hrefs
│   ├── PhotoDeletionResult.kt     # deletePhotosIndividually(): ViewModel-independent photo deletion loop
│   ├── WebDavHref.kt              # resolveWebDavHref() / hasSameOriginAs(): server-href and credential-origin checks
│   └── WebDavClient.kt            # WebDAV operations (PROPFIND, MKCOL, PUT, MOVE) via OkHttp
├── upload/
│   └── UploadBatch.kt         # Upload batching and auto-date folder resolution
├── ui/
│   ├── theme/                # Material3 theme (Color, Type, Theme)
│   ├── albums/
│   │   ├── AlbumsViewModel.kt      # Shared Albums-tab state: album list, detail, duplicate/blur review, deletions
│   │   ├── AlbumDetailScreen.kt    # Album grid, selection-mode deletion, shows every photo-deletion outcome
│   │   ├── DuplicateReviewScreen.kt / BlurReviewScreen.kt  # Review + delete; navigate back via state, Back blocked while deleting
│   ├── photogrid/
│   │   ├── PhotoGridScreen.kt      # Main screen: LazyVerticalGrid with date headers, cloud badges, filter toggle
│   │   └── PhotoGridViewModel.kt   # Manages photo loading, selection, grouping, uploaded-IDs tracking, filter
│   ├── uploadhistory/
│   │   ├── UploadHistoryScreen.kt    # Upload log list + clear actions
│   │   └── UploadHistoryViewModel.kt # Exposes persisted upload history
│   ├── navigation/
│   │   └── BackStackEntries.kt      # rememberParentEntry(): parent back-stack entry lookup keyed on the current entry
│   ├── settings/
│   │   ├── ConnectionTestResult.kt  # Typed connection-test outcomes + Throwable → result mapping
│   │   ├── SettingsScreen.kt        # Nextcloud login + manual WebDAV config
│   │   └── SettingsViewModel.kt     # Auth state + connection test; clears upload tracking on logout
│   └── share/
│       └── ShareReceiverScreen.kt   # Lightweight occasion dialog for the share-target flow
├── util/
│   └── ShareIntentParser.kt  # Extracts Photo objects from ACTION_SEND / ACTION_SEND_MULTIPLE intents
└── worker/
    └── UploadWorker.kt      # WorkManager CoroutineWorker for background uploads; records uploaded photo IDs
```

## Authentication

Two authentication methods are supported:

1. **Nextcloud Login Flow v2** (recommended): Opens the Nextcloud login page in the browser. The app polls for completion and receives an app-specific token. Follows the same pattern as the android-paper-reader reference project.
2. **Manual WebDAV config**: Direct URL, username, and password/app token entry for non-Nextcloud WebDAV servers.

Credentials are stored securely:
- URL and base folder in DataStore Preferences
- Username and password/app token encrypted via Android Keystore (AES-256-GCM) in standard SharedPreferences
- Crypto lives in `CredentialCipher` (format `Base64([ivLen][iv][ciphertext+tag])`), backed by an injectable `CredentialKeyProvider` (`AndroidKeystoreKeyProvider` in production, a software AES key in unit tests because the Keystore is unavailable under Robolectric)
- `CredentialCipher.decrypt()` maps missing values, format violations and every `GeneralSecurityException` / `ProviderException` / `IOException` to `DecryptResult.Missing`, `Success`, or `Failure(reason)` (other runtime exceptions are not caught) where the reason is `KEY_INVALIDATED` (`KeyPermanentlyInvalidatedException` / `UnrecoverableKeyException` / `UnrecoverableEntryException` anywhere in the cause chain) or `UNREADABLE` (malformed data, GCM tag mismatch, lost key, `UnexpectedKeyEntryException` when the alias holds a non-secret-key entry, other Keystore errors)
- Corrupted encrypted values fail closed (treated as not configured, never used for requests) and failures are logged via `Log.w` with field name and exception type only — never ciphertext or plaintext; a persisting failure is logged once per field until its outcome changes
- All credential-derived flows (`credentialState`, `isConfigured`, `webDavConfig`, `username`) derive from one private snapshot flow that decrypts both fields once per DataStore emission and runs `flowOn(ioDispatcher)` (internal constructor parameter, default `Dispatchers.IO`) to keep Keystore work off the main thread
- Because DataStore does not observe the secure SharedPreferences, `save()` and `clear()` bump a `credentials_version` key in the same DataStore edit, forcing an emission even when the server URL is unchanged
- `save()` is ordered for atomicity: encrypt both values → `commit()` them to the secure prefs → DataStore edit (`server_url` + version bump). A failed encryption leaves the previous credentials and URL untouched; failures surface as `CredentialSaveException`, which `SettingsViewModel` turns into `R.string.error_credentials_save_failed` instead of crashing
- `CredentialRepository.credentialState` exposes `CredentialState.Missing / Valid / Corrupted(reason)`; `SettingsViewModel` maps it to `SettingsUiState.credentialProblem` and the login screen shows a localized banner explaining why the user must log in again (issue #31 / #50)
- If obtaining the key or initialising the cipher during encryption fails with an error classified as `KEY_INVALIDATED` (same cause-chain predicate as decrypt), `CredentialCipher.encrypt()` deletes the key, regenerates it, and retries once, so re-login after a security-settings change succeeds; other errors propagate without deleting the key

## Navigation & Shared Albums State

`KrhnlesApp.kt` hosts a single `NavHost`. The Albums tab (`albums`) owns an `AlbumsViewModel`; its sub-routes (`albums/detail`, `albums/viewer`, `albums/duplicates`, `albums/blur`) share that ViewModel by scoping it to the Albums back-stack entry via the private helper `sharedAlbumsViewModel()`:

```kotlin
composable(route) { backStack ->
    val vm = sharedAlbumsViewModel(navController, backStack)
    // = viewModel(rememberParentEntry(navController, backStack, ROUTE_ALBUMS))
}
```

`rememberParentEntry()` (`ui/navigation/BackStackEntries.kt`) remembers `navController.getBackStackEntry(parentRoute)` keyed on the destination's own `NavBackStackEntry`: the lookup is cached across recompositions and re-resolved whenever the current entry changes, which is the form the navigation-compose `UnrememberedGetBackStackEntry` lint check accepts. Keying on a constant such as the route string is forbidden, and the check must never be silenced (issues #24 / #43).

`NoLintSuppressionRegressionTest` (backed by `LintSuppressionScanner` in the test source set) enforces this in `./gradlew test`: it scans every source set under `app/src` (`.kt`, `.kts`, `.java`) for `@Suppress`/`@SuppressLint`/`@SuppressWarnings` (any use-site target such as `@file:`, qualified or not, single- or multi-line) and `noinspection` comments naming the issue, and fails if `app/build.gradle.kts`, `app/lint*.xml`, any `app/*baseline*.xml` or the root `lint.xml` mention the issue id at all. The module and root directories are passed to unit tests as the system properties `krhnles.moduleDir` / `krhnles.projectDir` (`testOptions.unitTests.all` in `app/build.gradle.kts`); the test falls back to the working directory and fails loudly if `src/main` is not found. The test spells the issue id as a concatenation so the scan of its own source set does not flag its fixtures.

## Data Flow

1. **Photo Loading**: `MediaRepository` queries `MediaStore.Images` → photos grouped by date in `PhotoGridViewModel`
2. **Selection**: User taps photos or date headers → selection state in ViewModel
3. **Upload Trigger**: FAB → occasion dialog *or* auto-date preview dialog → one or more `WorkManager.enqueue(UploadWorker)` calls
4. **Upload Execution**: `UploadWorker` reads config from `CredentialStore`, creates remote folder via `WebDavClient.createDirectory()`, uploads each file via `WebDavClient.uploadFile()` (streamed request body, no full in-memory byte copy), then records the successfully uploaded photo IDs via `UploadedPhotosStore.markAsUploaded()`
5. **Progress**: Worker emits progress via `setProgress()` and shows system notifications
6. **History**: Upload outcomes are persisted via `UploadHistoryStore` and shown on the Upload History screen
7. **Album Management**: The Albums flow lists remote directories and can rename an album in place via `WebDavClient.renameDirectory()` using WebDAV `MOVE`, updating UI state without re-fetching the full album list

## Share Target Flow

When another app (Gallery, Screenshot, etc.) shares images via the Android share sheet:

1. `MainActivity` is launched with `ACTION_SEND` or `ACTION_SEND_MULTIPLE` intent
2. `parseSharedPhotos()` extracts URIs and wraps them as `Photo` objects (display name, MIME type, size resolved via `ContentResolver`; id is the URI hash code; date is today)
3. `ShareReceiverScreen` is displayed instead of the full app — shows only the occasion name dialog
4. On confirm → `enqueueUpload()` dispatches the upload via `WorkManager` → `finish()` returns the user to the source app
5. On dismiss → `finish()` returns without uploading
6. **History**: Upload outcomes are persisted via `UploadHistoryStore` and shown on the Upload History screen
7. **Uploaded Badge**: `PhotoGridViewModel` observes `UploadedPhotosStore.uploadedPhotoIds` and includes them in `PhotoGridUiState`; the grid shows a cloud badge on already-uploaded thumbnails (user can still select and re-upload them for a different album)
8. **Filter Toggle**: A funnel icon in the top bar (visible when any photos have been uploaded) lets the user hide already-uploaded photos; `PhotoGridViewModel.toggleShowOnlyNewPhotos()` recomputes `photosByDate` from the full unfiltered list

## Upload Path Construction

Given:
- WebDAV base URL: `https://nextcloud.example.com/remote.php/dav/files/user/`
- Base folder: `Photos/KrohnSync`
- Occasion: `Summer 2026`
- File: `photo.jpg`

Result: `https://nextcloud.example.com/remote.php/dav/files/user/Photos/KrohnSync/Summer 2026/photo.jpg`

When auto date folders are enabled, the user-selected photos are grouped by `YearMonth` and each batch resolves to `YYYY/MM-Month` (for example `2026/05-May`). A mixed-month selection therefore becomes multiple queued uploads under the configured base folder.

`WebDavClient.createDirectory()` walks each segment in order (MKCOL per segment, 405=already exists is OK), so nested paths are created safely regardless of what already exists on the server.
All path segments are validated (`.` / `..` rejected) and URL-encoded via `HttpUrl` segment builders to prevent path traversal and malformed path injection.

## Key Decisions

- **WorkManager** for uploads ensures they survive app closure
- **Share target** declared for `image/*` (`ACTION_SEND` + `ACTION_SEND_MULTIPLE`); `ShareReceiverScreen` shows the occasion dialog immediately so the user stays in context and the source app is returned to after confirm
- **Android Keystore** for secure credential encryption (replaced deprecated EncryptedSharedPreferences)
- **EncryptedSharedPreferences** — removed (deprecated in security-crypto 1.1.0)
- **Nextcloud Login Flow v2** for browser-based auth (no password handling in-app)
- **Nextcloud login polling timeout** so auth does not run forever if approval never happens
- **OkHttp** directly for WebDAV (no Retrofit needed for simple PUT/MKCOL/PROPFIND)
- **Coil 3** for efficient image loading in the grid
- **LazyVerticalGrid** with `GridItemSpan` for date headers spanning full width
- **AndroidViewModel** to access Application context for ContentResolver and DataStore
- **DataStore Preferences** (`uploaded_photos`) for persisting the set of already-uploaded photo IDs across restarts; stored as `Set<String>` (Long IDs converted to strings)
- **Settings connection test is one-shot** — `SettingsViewModel.testConnection()` reads the config once with `webDavConfig.first()` (never `collect` + `return@collect`, which leaks the collector; issue #22 / #41), drops calls while the previous test's `Job` is still active, and reports a typed `ConnectionTestResult` that `SettingsScreen` maps to localized `R.string.connection_test_*` messages (raw exception text is never shown). The server check is injected as `connectionTester` (default `WebDavClient.testConnection`) so tests run without a network. `NoCollectEarlyReturnRegressionTest` scans `app/src/main` for the early-return idiom (literal `return@collect` only — custom labels, `collectLatest` and multi-line forms are not detected)
- **Connection test guard is always released** — `WebDavClient.testConnection(timeoutMillis = 30 s)` sets a per-call OkHttp call timeout (the shared client used for uploads keeps no overall limit, so large uploads are not cut off) and runs via `enqueue` inside `suspendCancellableCoroutine`, so cancelling the coroutine cancels the HTTP call; cancellation is rethrown, never wrapped in the `Result`. Non-2xx statuses fail with the typed `WebDavHttpException(code)`. In the ViewModel every failure (including a DataStore `IOException` from `webDavConfig.first()` or a throwing tester) becomes a result, so `isTesting` is always cleared. `logout()` and any change of URL/username/password (observed in the `init` config collector) cancel the running test, null the `Job` and clear `testResult`; the job calls `ensureActive()` before publishing, so a cancelled test never writes a stale result into a new session
- **Connection-test error mapping** — `Throwable.toConnectionTestResult()`: `WebDavHttpException` 401/403 → `AuthFailed`, 404 → `NotFound`, other → `HttpError(code)`; any `SSLException` / `GeneralSecurityException` in the cause chain (`SSLHandshakeException`, `SSLPeerUnverifiedException`, `CertPathValidatorException`, `CertificateException`) → `CertificateError`; `UnknownHostException` → `UnknownHost`; `ConnectException` / `NoRouteToHostException` → `ServerUnreachable`; `InterruptedIOException` (socket or call timeout) → `Timeout`; `IllegalArgumentException` (bad URL) → `InvalidUrl`; everything else → `UnknownError`. Only the exception class name is logged
- **One photo-deletion path** — the three album deletion entry points (`deletePhotos()` for the duplicate review, `deleteBlurryPhotos()` for the blur review, `deleteSelectedPhotos()` for selection mode) only supply their busy-state transitions (`DuplicatesState.Deleting`/`Idle`, `BlurState.Deleting`/`Idle`, `isDeletingPhotos`) to the private `AlbumsViewModel.launchPhotoDeletion()`, which runs the ViewModel-independent `AlbumsRepositoryContract.deletePhotosIndividually()` (`data/PhotoDeletionResult.kt`; sequential, one `DELETE` per photo, a failed `Result` or a thrown exception counts as one failure and does not stop the rest; cancellation is rethrown and `ensureActive()` stops the loop before the next photo). Only hrefs that were actually deleted are removed from `AlbumDetailState` — photos whose deletion failed stay visible. Any other non-cancellation failure (e.g. `getRepo()` with an invalid URL) is mapped via `runCatching` + rethrow `CancellationException` to `PhotoDeletionResult.allFailed()` instead of crashing. The busy state is reset in `finally` (review paths via `compareAndSet(Deleting, Idle)`, so a scan started meanwhile is never overwritten). New deletion entry points (e.g. after the ViewModel split, #29 / #48) must reuse `deletePhotosIndividually()` / `launchPhotoDeletion()` (issues #28 / #47)
- **Photo-deletion outcome is state, not a callback** — the delete functions take no `onComplete`; they return `false` (and do nothing) while `photoDeletionJob` is still active (re-entry guard for all entry points; released when the job ends, every request bounded by the OkHttp connect/read timeouts). When a deletion finishes, `photoDeletionOutcome: StateFlow<PhotoDeletionOutcome?>` is set (after the busy state was cleared); `AlbumDetailScreen` collects it (`filterNotNull().collect` in `LaunchedEffect(viewModel)`, so consuming it does not cancel the snackbar), calls `consumePhotoDeletionOutcome()` first (shown once), clears the selection and shows `R.plurals.snackbar_delete_failures` via `photoDeletionFailureMessage()`. The duplicate / blur review remember (`rememberSaveable`) that they started a deletion and navigate back once their state leaves `Deleting`; `BackHandler` and the disabled top-bar back button keep the user on the review while deleting. This replaced snackbars launched in the review's `rememberCoroutineScope`, which were cancelled by the immediate navigation, and callbacks that could fire into a screen the user had already left
- **Already-deleted photos** — `AlbumsRepository.deletePhoto()` treats `404` / `410` as success (deleted elsewhere); other non-2xx statuses fail with `WebDavHttpException(code)`. `deleteAlbum()` still fails on `404`
- **Server hrefs are validated** — `resolveWebDavHref(root, href)` (`data/WebDavHref.kt`) checks the *raw* href first (percent-decoded pieces must not be `.` / `..` or contain `/`, `\` or control characters; no `?`, `#`, `\`), because `HttpUrl.resolve()` silently normalizes dot segments away; then resolves it with `root.resolve(href)` and requires the same scheme/host/port and a path strictly below `root`. `AlbumsRepository` uses `baseFolderUrl` (config URL + base folder) as root for album/photo hrefs (`deletePhoto`, `deleteAlbum`, `renameAlbum`, `listPhotos(albumHref)`, direct image URLs) and the album URL for the photos of a listing. Listings skip unsafe entries (only the count is logged); requests for an unsafe href fail with `UnsafeHrefException` without being sent; image URLs fall back to `""`. The href itself is never logged. Absolute, percent-encoded Nextcloud paths and absolute same-origin URLs are accepted
- **Credentials only for the configured origin** — the `AlbumsRepository` OkHttp interceptor and the Coil image-loader interceptor (`KrhnlesApplication`) only add `Authorization` when `request.url.hasSameOriginAs(config URL)` (scheme, host, port). OkHttp itself strips `Authorization` on cross-host redirects, and application interceptors are not re-run for redirects
- **Uploaded-photo tracking reset on logout** — `SettingsViewModel.logout()` calls `UploadedPhotosStore.clear()` so the tracking starts fresh when a user disconnects (or switches servers)
- **UploadWorker records IDs after success** — each successfully uploaded photo's MediaStore ID is written to `UploadedPhotosStore`; failed uploads are not marked; re-uploads are always allowed (badge is visual only)
