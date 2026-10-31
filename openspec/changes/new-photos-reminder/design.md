# Design

## Context

See proposal.md (Why) for motivation and `specs/new-photos-reminder/spec.md` for the behavior contract. Relevant current state:

- **Non-credential preferences** (`wifi_only`, `auto_date_folders`, `blur_sensitivity`, `verify_by_download`) are plain keys in the credentials DataStore, written only through `CredentialStore` (`NoCredentialDataStoreAccessRegressionTest`). The credential snapshot de-duplicates on `(serverUrl, baseFolder, credentials_version)` before decrypting, so extra keys never touch the Keystore. `CredentialStore.clear()` calls `prefs.clear()` (resets every such key) and the file is excluded from backup / device transfer.
- **Logout** is `SettingsViewModel.logout()`: `clearLogged(...)` steps (verified uploads → credentials → uploaded markers), each in its own `runCatching` that rethrows `CancellationException`.
- **Uploaded markers**: `UploadedPhotosStore.uploadedPhotoIds: Flow<Set<Long>>` (media ids, video ids carry `Photo.VIDEO_ID_FLAG`, built only via `mediaItemId()`).
- **Upload history**: `UploadHistoryStore.entries` (`timestampMillis`, `photoCount` = batch size, `failedCount`), written by `UploadWorker.recordHistory()` at the end of each run, not cleared on logout, user-clearable.
- **Media**: `MediaRepository` queries `MediaStore.Images` / `MediaStore.Video` (full rows, no limit) for the grid. Access rules live in `ui/photogrid/MediaAccess.kt` (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`).
- **Notifications**: `UploadWorker` owns channel `upload_channel` (LOW), ids 1001 (foreground) / 1002 (results, tagged by queue file) and checks `POST_NOTIFICATIONS` before `notify`. The photo grid already requests `POST_NOTIFICATIONS` on launch when missing. `minSdk` is 35, so the runtime permission, `QUERY_ARG_*` bundles and `OWNER_PACKAGE_NAME` are always available.
- **Entry**: `MainActivity` (standard launch mode, exported for the launcher / share filters) shows either `ShareRoute` or `KrhnlesApp`. `KrhnlesApp` resolves the start route once (`StartupViewModel`, may be onboarding) and hosts the `NavHost`; the grid's `PhotoGridViewModel` is scoped to `ROUTE_PHOTOS` and only has `toggleShowOnlyNewPhotos()`.

## Goals / Non-Goals

**Goals:**
- One unique periodic job per install that is always consistent with the stored setting (toggle, interval change, logout, app start, self-cancel).
- A counting step that is a pure function over rows + markers + baseline, so the logic is unit-testable without MediaStore.
- A launch request that is applied exactly once and goes through navigation, never through a second activity or a global singleton.

**Non-Goals:**
- No automatic upload, no network check of what is on the server, no server-side comparison.
- No dismissal of a shown reminder when the user starts an upload (it is auto-cancelled on tap and replaced by the next reminder).
- No per-album / per-folder reminders, no custom time of day, no exact scheduling (WorkManager timing is inexact by design).
- No change to the existing launch-time `POST_NOTIFICATIONS` request of the photo grid.

## Decisions

### D1. Storage: four plain keys in the credentials DataStore via `CredentialStore`
Keys: `new_photos_reminder_enabled` (Boolean), `new_photos_reminder_interval` (enum name, read type-safely via `asMap()[KEY] as? String`, fallback `Weekly`, like `blur_sensitivity`), `new_photos_reminder_since` (epoch ms, written together with `enabled = true` in one edit when the reminder is turned on), `new_photos_reminder_last_shown` (epoch ms, removed in that same edit). Exposed on `CredentialRepository` as `newPhotosReminder: Flow<NewPhotosReminderSettings>` (data class: enabled, interval, sinceMillis, lastShownMillis), `saveNewPhotosReminder(enabled, interval)` and `markNewPhotosReminderShown(atMillis)`. Plain `edit`s, no `credentials_version` bump.
- *Why:* the request asks to follow Wi-Fi only / blur sensitivity; this gives logout reset (`prefs.clear()`) and backup exclusion for free and keeps all DataStore access inside `CredentialStore`.
- *Alternative:* `app_preferences` (onboarding / media-permission flags) — rejected: it is not cleared on logout, so a separate reset path would be needed.
- *Alternative:* a separate reminder DataStore — rejected: another backup-rules entry and another logout step for four values.

### D2. `ReminderInterval` enum in `model/`
`Weekly(7)`, `EveryTwoWeeks(14)`, `Monthly(30)` days, `fromStored(String?)` with fallback `Weekly`. "Monthly" is a fixed 30-day period (PeriodicWorkRequest has no calendar months).

### D3. Scheduling through a `NewPhotosReminderScheduling` interface
`reminder/NewPhotosReminderScheduler.kt` wraps `WorkManager`:
- `schedule(interval, policy)` → `enqueueUniquePeriodicWork(NEW_PHOTOS_REMINDER_WORK_NAME, policy, request)`; request = `PeriodicWorkRequestBuilder<NewPhotosReminderWorker>(interval.days, DAYS)`, `setInitialDelay(interval.days, DAYS)`, constraint `setRequiresBatteryNotLow(true)`, no network constraint, tag `new_photos_reminder`.
- From Settings: turning on → `UPDATE` (after a cancel there is nothing to update, so it enqueues), interval change → `UPDATE` (keeps the existing work id, replaces the period). Turning off / logout → `cancelUniqueWork` + cancel the reminder notification.
- `reconcile(settings)` on app start → enabled: `KEEP` (never resets the timing of a running schedule); disabled: `cancelUniqueWork` (no-op if absent).
- Every call is wrapped (`runCatching`, rethrow `CancellationException`, `IllegalStateException` from an uninitialised WorkManager logged by type); `await()` the `Operation` with a timeout (CLAUDE.md: bounded waits).
- *Why an interface:* `SettingsViewModel` gets it as a default constructor parameter (`@JvmOverloads`, `ViewModelFactoryRegressionTest`), tests pass a recording fake; Robolectric tests of the real scheduler use `WorkManagerTestInitHelper`.
- *Alternative:* schedule from the worker side only (self-rescheduling one-time work) — rejected: periodic work is what WorkManager persists across reboot / update without extra receivers.

### D4. Ordering of a Settings change
`SettingsViewModel.setNewPhotosReminder(enabled, interval)`: persist first (`saveNewPhotosReminder`), then schedule / cancel; a failed write is logged by type and changes nothing else (the switch shows the stored value, like `setVerifyByDownload`). A failed schedule leaves the stored value; app-start `reconcile()` and the worker's self-cancel (D6) repair any drift. Logout adds a `clearLogged("reminder") { reminderScheduler.cancel() }` step before the credential clear; the credential clear resets the keys.

### D5. Reconcile on app start
`MainActivity.onCreate` (non-share path, `savedInstanceState == null`) calls `KrhnlesApplication.newPhotosReminderScheduler.reconcileInBackground()`, which runs in `appScope`: reads `newPhotosReminder.first()` under `withTimeoutOrNull(3 s)` (`runCatching`, rethrow cancellation); null / failure → do nothing.
- *Why not `Application.onCreate`:* Robolectric tests instantiate `KrhnlesApplication` for every test; touching WorkManager there would break unrelated tests. The share path is skipped because it runs in another app's task and must stay minimal.

### D6. `NewPhotosReminderWorker` (CoroutineWorker, `reminder/`)
Internal constructor with injectable `CredentialRepository`-like settings source, `UploadedPhotosRepositoryContract`, `UploadHistoryRepository`, `NewMediaQuery`, `ReminderNotifier`, clock and permission checker; public `(Context, WorkerParameters)` constructor for WorkManager. Steps (each store read `withTimeoutOrNull(3 s)` + `runCatching`, rethrow cancellation):
1. Settings unreadable → `Result.success()` (nothing posted, nothing cancelled). Disabled → cancel own unique work, `Result.success()`.
2. `ReminderNotifier.canPost()` false (permission, `areNotificationsEnabled()`, channel importance `NONE`) → success, no query.
3. `queryableKinds(granted)` (pure): image access → Image; video access → Video; neither but `READ_MEDIA_VISUAL_USER_SELECTED` → both (MediaStore returns only selected items); none → success, no query.
4. Baseline = max(`sinceMillis`, newest history `timestampMillis` with `photoCount > failedCount`). History unreadable → `sinceMillis` only. `lastShownMillis` read too.
5. Markers unreadable / timeout → success without posting (an empty set would over-count).
6. Query (D7), then pure `countNewMedia(rows, uploadedIds, baselineMillis, lastShownMillis)` → `NewMediaCount(total, hasNewSinceLastReminder, isLowerBound, sinceLastUpload)`.
7. `total > 0 && hasNewSinceLastReminder` → re-read the setting (still enabled, same `sinceMillis`) → post (D8) → `markNewPhotosReminderShown(now)`.
Always `Result.success()` (a missed reminder needs no retry; periodic work continues anyway). No network, no `WebDavConfig`, no Keystore.

### D7. Bounded media query (`reminder/NewMediaQuery.kt`)
Per kind: `contentResolver.query(collection, arrayOf(_ID, DATE_ADDED), Bundle, CancellationSignal)` with `QUERY_ARG_SQL_SELECTION` = `date_added > ? AND (owner_package_name IS NULL OR owner_package_name != ?)` plus, for every path in `APP_OWNED_RELATIVE_PATHS`, `AND relative_path NOT LIKE ?` (prefix match); `QUERY_ARG_LIMIT` = `MAX_REMINDER_SCAN_ROWS` (50,000) + 1; sort `date_added DESC`. `DATE_ADDED` is in seconds; the baseline is converted with floor division and `>` keeps second-granularity semantics. Pending / trashed rows are excluded by MediaStore's default collection query (no `QUERY_ARG_MATCH_*`). Ids go through `mediaItemId(rawId, kind)` (raw ids `>= 2^62` skipped). The whole query runs on `Dispatchers.IO` under `withTimeoutOrNull(REMINDER_QUERY_TIMEOUT_MS = 30 s)` with the `CancellationSignal` cancelled on timeout; a timeout aborts the run (no post, no `last_shown` write). A `SecurityException` for one kind → zero rows for that kind.
- *Why not reuse `MediaRepository.loadPhotos()`:* it reads the full library with names, sizes and durations and has no limit; the reminder needs ids and `DATE_ADDED` of recent rows only.
- *Why `DATE_ADDED`:* "new on the device" is when it entered MediaStore; `DATE_TAKEN` would miss photos received from others and is null for many files.
- *Why the owner filter:* downloads saved by the app (sibling `download-album-photos`) are owned by our package while it is installed; `APP_OWNED_RELATIVE_PATHS` covers the case where ownership is lost (reinstall). It starts as an empty list in this change; `download-album-photos` adds its destination folder constant to it (whichever lands second wires them together — see Open Questions).

### D8. Notification (`reminder/ReminderNotifier.kt`)
Channel `new_photos_reminder_channel` ("Reminders", `IMPORTANCE_DEFAULT`, created before posting), id `NEW_PHOTOS_REMINDER_NOTIFICATION_ID = 1003` (never 1001 / 1002), no tag, so a new reminder replaces the old one. Small icon = the existing notification glyph. Title `R.string.reminder_notification_title`; text `R.plurals.reminder_new_media_since_upload` / `R.plurals.reminder_new_media` (no upload since enabling) / `R.plurals.reminder_new_media_more_than` (lower bound), via `resources.getQuantityString`. `setAutoCancel(true)`. Content intent: `PendingIntent.getActivity(context, REQUEST_CODE_SHOW_NEW_MEDIA, Intent(context, MainActivity::class.java).setAction(ACTION_SHOW_NEW_MEDIA).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP), FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)`. `notify` only after the `POST_NOTIFICATIONS` check (lint `MissingPermission` satisfied without suppression).

### D9. Launch request → navigation
- Pure `launchRequestOf(intent): AppLaunchRequest?` (`ui/navigation/LaunchRequest.kt`): `ShowNewMedia` iff `intent.action == ACTION_SHOW_NEW_MEDIA`; extras and data are never read. `isShareIntent` is checked first, unchanged.
- `LaunchRequestViewModel` (plain `ViewModel`, no-arg constructor, activity-scoped via `by viewModels()`): `pending: StateFlow<AppLaunchRequest?>`, `offer(request)`, `consume()`. `MainActivity` offers in `onCreate` only when `savedInstanceState == null` (process-death / rotation recreation never re-applies the original intent) and in an `addOnNewIntentListener` (CLEAR_TOP + SINGLE_TOP delivers taps to the running instance).
- `KrhnlesApp(onStartUpload, launchRequests, onLaunchRequestConsumed)`; inside `KrhnlesNavigation`, a `LaunchedEffect(pending)`: if the start route is onboarding → `consume()` (dropped); else `navigate(ROUTE_PHOTOS) { popUpTo(ROUTE_PHOTOS) { inclusive = false }; launchSingleTop = true }` (never the graph start, per CLAUDE.md 3a). The `ROUTE_PHOTOS` composable observes the same pending request and, when it is `ShowNewMedia`, calls `PhotoGridViewModel.showOnlyNewPhotos()` (sets `true`, recomputes from `allPhotosByDate`; idempotent) and then `consume()`. Because consumption happens only in the grid destination, the filter is never lost when navigation and composition race.
- *Alternative:* a nav argument on `ROUTE_PHOTOS` / a `navDeepLink` — rejected: changes the route pattern used by `popUpTo(ROUTE_PHOTOS)` and the bottom bar, and a deep link URI would be re-applied by the NavController on recreation.
- *Alternative:* an intent extra boolean — rejected: the action alone is sufficient and an exported activity should not interpret extras.
- *Security note:* `MainActivity` is exported, so any app can send `ACTION_SHOW_NEW_MEDIA`; the only effect is opening the grid with a filter on — no data is read from the intent.

### D10. Settings UI
In the logged-in section next to Wi-Fi only: one `toggleable(role = Role.Switch)` row; when on, three `selectable(role = Role.RadioButton)` rows inside `selectableGroup()`. Turning on with `POST_NOTIFICATIONS` missing launches `RequestPermission` through `rememberLauncherForActivityResult` (testable with a fake `ActivityResultRegistry`, like `PhotoGridPermissionTest`). `canPostReminders` is computed in the screen (`NotificationManagerCompat.areNotificationsEnabled()` + permission + channel importance) on `ON_RESUME` and after the permission result; when the reminder is on and it is false, a hint with "Open notification settings" (`Settings.ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE`) is shown. Strings via resources; callbacks use `LocalResources.current`.

## Risks / Trade-offs

- [WorkManager periodic timing is inexact (Doze, battery-not-low)] → acceptable for a weekly nudge; documented in README.
- [A clock change backwards puts the baseline in the future, so nothing is counted until time catches up] → accepted; turning the reminder off and on resets it.
- [Restored / copied old photos get a new `DATE_ADDED` and count as new] → intended: they are not backed up from this device.
- [Clearing the upload history moves the baseline back to the enable time, so more items count] → accepted; markers still exclude uploaded items.
- [Partial access counts only selected items, typically 0] → spec'd; README explains that reminders need full photo access.
- [Worker writes `last_shown` while logout clears the DataStore] → the worker re-reads `enabled` + `sinceMillis` right before posting; a stray `last_shown` key with `enabled` unset is harmless and removed by the next enable.
- [Large libraries: 50,000-row cap per kind] → only ids and dates are read; above the cap the text says "more than".
- [Reminder channel `IMPORTANCE_DEFAULT` may make a sound] → user can change it per channel; the upload channel stays LOW.

## Migration Plan

No data migration: absent keys mean "off / Weekly". Existing installs see the new switch off. Rollback: removing the feature leaves unused keys in the credentials DataStore (cleared on next logout) and a periodic work entry whose worker class no longer exists; a rollback must therefore keep a `cancelUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME)` call on app start.

## Open Questions

- Which constant `download-album-photos` uses for its MediaStore destination folder: whichever change lands second adds it to `APP_OWNED_RELATIVE_PATHS` (the owner-package filter already covers downloads while the app stays installed). This does not change the specs or the task breakdown.
