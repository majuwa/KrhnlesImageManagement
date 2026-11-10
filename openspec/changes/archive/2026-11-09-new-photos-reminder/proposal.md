# Proposal

## Why

Feature request (verbatim from the request that started this change):

> The app is deliberately manual, so users can forget to back up. Add an opt-in setting (off by default) "Remind me about new photos" with an interval (e.g. weekly / every 2 weeks / monthly). A unique PeriodicWorkRequest (scheduled/cancelled when toggled; rescheduled correctly after app update / reboot — WorkManager persists) counts local photos and videos added since the last upload / last reminder that are not marked uploaded (UploadedPhotosStore), with a bounded MediaStore query (no network access in the job, no upload). If count > 0 it posts a notification on its own channel/id ("37 new photos and videos since your last upload", plurals) whose tap opens the photo grid with the "only new" filter enabled (deep link / intent extra handled by MainActivity → navigation; PendingIntent immutable, explicit). No notification when nothing is new, without media permission, or when notifications are disabled / POST_NOTIFICATIONS denied (consider asking for POST_NOTIFICATIONS when enabling the setting). The setting lives with the other non-credential preferences (plain edit, no credentials_version bump, reset on logout, not restored from backup).

Today nothing tells the user that new photos are waiting, so the manual flow quietly turns into "never backed up". A periodic, local-only nudge keeps the app manual (no automatic upload) while closing that gap.

## What Changes

- New Settings entry (logged-in content) "Remind me about new photos", off by default, with an interval choice: weekly, every 2 weeks, monthly. Turning it on asks for `POST_NOTIFICATIONS` if it is not granted; a hint with a shortcut to the app's notification settings is shown while the reminder is on but notifications cannot be shown.
- The setting (enabled flag, interval, the time it was turned on and the time of the last reminder) is stored as plain preferences in the credentials DataStore next to Wi-Fi only / blur sensitivity / auto date folders: no `credentials_version` bump, reset on logout, not restored from backup or device transfer.
- A unique periodic WorkManager job is scheduled when the setting is turned on, updated when the interval changes, and cancelled when it is turned off or on logout. It is reconciled with the stored setting on app start (`UPDATE`, which keeps its timing); WorkManager keeps it across reboots and app updates. Cancelling it needs one narrow, user-confirmed allowance in `NoUploadWorkCancelOutsideCancelPathRegressionTest` (`cancelUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME)` only in the reminder scheduler); upload work is never touched.
- The job (no network, no upload, no credential decryption) counts photos and videos that entered the device after the last upload (or after the reminder was turned on), are not marked uploaded, are accessible with the current media permission, and were not created by the app itself (photos saved from albums by `download-album-photos`: owned by the app, or below `Pictures/KroehnSync/` after a reinstall). The MediaStore query is bounded (id-only projection, row cap, abandonable timeout).
- If the count is greater than 0 and something was added since the previous reminder, it posts one notification on a new "Reminders" channel with its own id: "37 new photos and videos since your last upload" (plurals). Nothing is posted when nothing is new, without media access, or when notifications are not allowed.
- Tapping the notification opens the app on the photo grid with the "show only new photos" filter turned on (explicit, immutable `PendingIntent` to `MainActivity`, handled once through navigation; the Albums subtree is saved like a tab switch, so a running album deletion is not interrupted).

## Capabilities

### New Capabilities
- `new-photos-reminder`: the opt-in periodic reminder about photos and videos that are not backed up yet: setting and its lifecycle, scheduling, the local count, the notification and the tap that opens the filtered grid.

### Modified Capabilities
- `uploaded-photo-markers`: the "Show only new photos" filter can additionally be turned on by opening the app from the reminder notification.

## Impact

- **Code (planned):** `data/CredentialStore.kt` + `CredentialRepository` (new reminder preferences), new `model/ReminderInterval.kt`, new `reminder/` package (scheduler, worker, counting, notification), `ui/settings/SettingsScreen.kt` / `SettingsViewModel.kt` (toggle, interval, notification-permission request, cancel on logout), `MainActivity.kt` + `KrhnlesApp.kt` (launch request → photo grid), `ui/photogrid/PhotoGridViewModel.kt` (set the filter on instead of toggling), `res/values/strings.xml` (strings, plurals, channel name).
- **Android:** a third notification channel (after `upload_channel` and `download_channel`), id 1003 (1001 / 1002 upload, 1004 / 1005 download); no new permission (`POST_NOTIFICATIONS` and the media permissions are already declared); no new dependency (WorkManager 2.12 already used).
- **Privacy:** the job only reads MediaStore ids / `DATE_ADDED` and local stores; nothing leaves the device.
- **Tests:** new worker / counting / scheduler / launch-request tests, Settings and grid Compose tests, `PlainViewModelFactoryRegressionTest` for the new activity-scoped ViewModel, a SQLite-backed Bundle query in `FakeMediaStoreProvider`, and the new allowance cases in `NoUploadWorkCancelOutsideCancelPathRegressionTest`.
- **Small refactor:** `DOWNLOAD_RELATIVE_ROOT` extracted in `download/DownloadNames.kt` and shared by `DownloadWorker` and the reminder.
- **Docs:** `README.md`, `CLAUDE.md`, `docs/architecture.md`, `docs/requirements.md`.
- **Sibling changes:** `download-album-photos`, `cancel-running-upload`, `show-videos-in-albums`, `nextcloud-chunked-upload`, `pin-self-signed-certificate`, `occasion-folder-suggestions` and `album-search-and-sort` are implemented; this change builds on their current code (saved photos excluded, a cancelled batch counts as "last upload" only if it uploaded at least one file). Still open: `german-translation` (implemented last; must translate the new strings) and `readme-roadmap-cleanup` (README edits overlap). Possible merge conflicts only in `SettingsScreen.kt` / `strings.xml`.
