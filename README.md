# Kröhnle's Image Management (KröhnSync)

A lightweight, privacy-focused Android application designed to selectively back up your local photos directly to your personal Nextcloud or WebDAV server. Instead of fully automated background dumps, this app puts you in control of what gets uploaded and how it's organized.

## 🚀 Features

- **Local Image Grid:** Browse photos stored on your device in a clean, high-performance grid.
- **Smart Selection:** Select images individually or batch-select them grouped by date.
- **Custom Occasions:** Give your upload batch a custom name or occasion (e.g., "Summer Vacation 2026") to automatically create and organize folders on your cloud.
- **Background Uploads:** Hit upload and move on with your day. Uploads are handled in the background.
- **WebDAV & Nextcloud Native:** Direct integration with your self-hosted storage.
- **Secure Login:** Nextcloud Login Flow v2 (browser-based) or manual WebDAV configuration. Credentials are encrypted at rest.
- **Cloud Album Browser:** Browse albums (folders) stored on your WebDAV/Nextcloud server.
- **Cloud Duplicate Detector:** Scan an album for near-duplicate images and bulk-delete the extras.
- **Delete Photos from Album:** Long-press any photo in an album to enter selection mode. Select multiple photos, then tap the delete icon in the top bar. A confirmation dialog is shown before any server deletion.
- **Delete Entire Album:** Long-press an album in the Albums tab to delete the whole album (and all its photos) from the server, with a confirmation dialog.

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

### Usage
1. Grant photo access when prompted
2. Browse your photos grouped by date
3. Tap individual photos or date headers to select
4. Tap the upload FAB (cloud icon) when photos are selected
5. Enter an occasion/folder name and confirm
6. Upload proceeds in the background with notification progress
