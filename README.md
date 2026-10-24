# Kröhnle's Image Management (KröhnSync)

A lightweight, privacy-focused Android application designed to selectively back up your local photos directly to your personal Nextcloud or WebDAV server. Instead of fully automated background dumps, this app puts you in control of what gets uploaded and how it's organized.

## 🚀 Features

- **First-Run Introduction:** On the first launch of a fresh install a two-page introduction explains what the app does and offers "Connect now", which opens Settings (Back from Settings returns to the photo grid). Skip is available on both pages; it is shown only once and never to users who already set up a server. Pressing Back on the first page closes the app and shows the introduction again next time. The completion flag is not part of backups, so a restored device without credentials shows the introduction again.
- **Local Image Grid:** Browse photos stored on your device in a clean, high-performance grid.
- **TalkBack Support:** Every photo in the grid is announced as one element ("Photo 2 of 5, taken on May 1, 2026", selected / not selected, already uploaded) with a "Select" / "Deselect" action, and each day header as one heading with its photo count and a select-all-of-the-day action. File names are never read aloud; photos without a capture date are announced as "date unknown" and grouped under "Unknown date". Album thumbnails and the fullscreen viewer announce "Photo N of M".
- **Smart Selection:** Select images individually or batch-select them grouped by date.
- **Custom or Automatic Foldering:** Either give each upload a custom occasion name (e.g., "Summer Vacation 2026") or enable automatic `YYYY/MM/DD` day folders for quick archive uploads.
- **Background Uploads:** Hit upload and move on with your day. Uploads are handled in the background.
- **Upload History:** Review past upload batches (folder, timestamp, succeeded/failed counts) and clear entries individually or all at once.
- **Duplicate-Upload Prevention:** Already-uploaded photos are marked with a cloud badge in the local grid. Use the filter toggle (funnel icon) to show only new (not yet uploaded) photos. The tracking is reset when you disconnect from the server.
- **WebDAV & Nextcloud Native:** Direct integration with your self-hosted storage.
- **Secure Login:** Nextcloud Login Flow v2 (browser-based) or manual WebDAV configuration. Credentials are encrypted at rest; if they can no longer be decrypted (e.g. after device security settings change), Settings explains why and asks you to log in again. If saving credentials fails, an error is shown and you can retry.
- **Cloud Album Browser:** Browse albums (folders) stored on your WebDAV/Nextcloud server.
- **Large Albums:** Opening an album shows how many photos have been found so far while the listing loads, using little memory even for albums with tens of thousands of photos. Very large albums (more than 20,000 photos or a 32 MiB folder listing) show only their first photos together with a notice (or, if none of the part that could be loaded contains photos, an explanation instead of "No photos"); the duplicate and blur review then warn that their results may be incomplete. A folder list that is too large to load shows an explanation instead of a partial list.
- **Cloud Duplicate Detector:** Scan an album for near-duplicate images and bulk-delete the extras.
- **Delete Photos from Album:** Long-press any photo in an album to enter selection mode. Select multiple photos, then tap the delete icon in the top bar. A confirmation dialog is shown before any server deletion. Photos that could not be deleted stay in the album, and a snackbar on the album screen reports how many deletions failed — also after deleting from the duplicate or blur review, which return to the album once the deletion has finished. Back (and the top-bar back / cancel-selection buttons) is disabled while any deletion runs, so it always finishes. Photos that were already deleted elsewhere (server answers "not found") are treated as deleted. Logging out or switching servers while a duplicate or blur review is open stops its scan and shows an error instead of its results. Rotating the screen during a duplicate or blur scan, on its results or while deleting keeps the scan, your selection and the deletion.
- **Manage Remote Albums:** Long-press an album in the Albums tab to rename it or delete it from the server, with confirmation and inline validation.

## 🔮 Future Roadmap
- **Blurry/Bad Photo Detection:** Smart suggestions to clean up low-quality photos and save cloud space.
- **Blurry/Bad Photo Detection:** Smart suggestions to clean up low-quality photos and save cloud space.

## 🛠️ Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Image Loading:** Coil
- **Background Processing:** Android WorkManager
- **Networking:** OkHttp (WebDAV via PROPFIND/MKCOL/PUT)
- **Security:** Android Keystore (AES-256-GCM) encrypted credential storage
- **Server-response hardening:** Album and photo paths returned by the server are only used if they stay on the configured server and inside the configured base folder; credentials are only ever sent to the configured server

## ⚙️ Setup & Installation

1. Clone the repository
2. Open in Android Studio (Ladybug or newer)
3. Sync Gradle and build
4. Run on a device with Android 15+ (API 35+)

### Permissions
- **READ_MEDIA_IMAGES** — required to browse device photos
- **INTERNET** — required for WebDAV uploads
- **POST_NOTIFICATIONS** — for upload progress notifications

### Configuration (Nextcloud)
1. Open the app and tap the Settings icon in the top bar
2. Enter your Nextcloud server URL (e.g., `https://nextcloud.example.com`)
3. Tap "Connect via Browser" — you'll be redirected to log in securely
4. After approving in the browser, the app receives an app-specific token automatically

### Configuration (Manual WebDAV)
1. In Settings, expand "Manual WebDAV config"
2. Enter the full WebDAV URL, username, and password/app token
3. Tap "Save & Connect"

### Test Connection
If the album list, an album or a duplicate / blur scan cannot be loaded, the same plain-language explanations are shown (not logged in, invalid server address, certificate, server not found / unreachable, timeout, access denied, not found, HTTP error); retry with the Retry button (album, scans) or the reload icon (album list). Logging out or switching servers while an album is open closes that album's photos instead of continuing to use the old server.

In Settings, "Test Connection" checks the stored server and credentials (it gives up after 30 seconds). Failures are explained in plain language: an untrusted (e.g. self-signed or expired) certificate, a server name that cannot be found, an unreachable server, a timeout, rejected credentials (HTTP 401/403), a missing WebDAV folder (HTTP 404), or another HTTP error code. Tap the button again to retry. Logging out or changing the server or credentials clears the previous result.

### Upload Options
- **Base upload folder:** Choose the remote root folder that all uploads go into.
- **Auto date folders:** Optional toggle in Settings that skips the occasion dialog and uploads each photo into the folder of its capture day, `YYYY/MM/DD` such as `Photos/2026/05/14/` (digits only, independent of the device language). A selection that spans several days is still one background upload with one progress notification, one result notification and one upload-history entry (for example `2026/04/30 – 2026/05/01`). Existing `YYYY/MM-Month` folders from earlier versions are left untouched.
- **Blur sensitivity:** Slider in Settings (Low / Medium / High, default Medium) that decides how aggressively the blur review flags photos: High flags more photos, Low only clearly blurry ones. A changed level applies to the next blur scan only; results already shown or being computed are not updated. The setting is stored with the other preferences and, like them, is not restored on a new device and is reset to Medium when you log out. The thresholds are heuristics and may need tuning for your camera.

### Usage
1. Grant photo access when prompted
2. Browse your photos grouped by date
3. Tap individual photos or date headers to select
4. Tap the upload FAB (cloud icon) when photos are selected
5. Either enter an occasion/folder name, or confirm the auto-date folder preview if that mode is enabled
6. Upload proceeds in the background with notification progress
7. Open the history icon in the Photos top bar to review previous upload results
8. If some photos fail (for example a network error or a photo that can no longer be read), the result notification offers "Retry Failed (N)": it re-uploads only those photos into the same folder, respects the Wi-Fi-only setting and dismisses that notification. Retrying works best for photos picked in the app; photos shared from other apps may no longer be readable. Each retry appears as its own entry in the history. If the remote folder cannot be created, the notification explains why in plain language. Occasion names cannot be `.` or `..` or contain `/` or `\`. With Wi-Fi-only on, an upload that starts on mobile data waits and retries later. Only photos from other apps' content providers can be shared in; `file://` shares are ignored.
