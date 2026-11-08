# Requirements – Kröhnle's Image Management

This document captures the functional and non-functional requirements derived from
implemented behaviour, user feedback, and explicit feature requests.

---

## 1. General

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| G-001 | The app must be privacy-first: no cloud analytics, no telemetry.                                | Implemented |
| G-002 | The app must target Android 15+ (`minSdk` 35) and handle granular media permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` and partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) are requested together; photos stay usable without video access, upgrade users are asked for video access at most once and partial-access users never automatically (issues #15 / #34). | Implemented |
| G-003 | All UI must be built with Jetpack Compose (no XML layouts).                                     | Implemented |
| G-004 | All async and background work must use Kotlin Coroutines / Flow.                                | Implemented |

---

## 2. Photo Selection (Local)

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| P-001 | Users can browse local device photos and videos in a grid view (videos with play icon and duration; uploaded like photos, issues #15 / #34). | Implemented |
| P-002 | Users can select one or more photos for upload.                                                 | Implemented |
| P-003 | Users must tag selected photos with an occasion name before uploading.                          | Implemented |
| P-004 | The grid must use `LazyVerticalGrid` for high-performance scrolling.                            | Implemented |
| P-005 | The occasion dialog suggests recent occasions and existing remote albums.                       | Implemented |

---

## 3. Upload

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| U-001 | Uploads must use `WorkManager` so they survive app closure or process death.                    | Implemented |
| U-002 | Photos are uploaded to a WebDAV server (Nextcloud-compatible).                                  | Implemented |
| U-003 | The upload destination folder is derived from the occasion name provided by the user.           | Implemented |
| U-004 | Untrusted or changed TLS certificates must be handled gracefully with a clear user-facing error message and, for the configured server, an explicit review (S-005). | Implemented |
| U-005 | A retry mechanism must be available when an upload fails.                                       | Implemented |
| U-006 | A running upload can be cancelled and resumed later (Cancel in the notification and in the app; uploaded items stay uploaded, the rest is resumed with "Retry Failed"). | Implemented |
| U-007 | Large files on Nextcloud (at least 50 MiB) are uploaded in resumable 10 MiB chunks: an interrupted file continues after its last confirmed chunk, server body-size limits do not apply, the progress shows the file percentage (issue #69). | Implemented |
| U-008 | Album photos saved to the device are downloaded by `WorkManager` in the background (survives closing the app), one batch at a time, with a progress notification and Cancel; an interrupted batch resumes without re-downloading finished photos; the result reports saved / failed counts with typed reasons (issue #73). | Implemented |
| U-009 | A downloaded photo is limited to 200 MiB, must be an allow-listed raster image (videos are reported as unsupported), is requested only below its album with origin-restricted credentials and never through a redirect, and becomes visible only when complete. | Implemented |

---

## 4. Albums (Remote)

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| A-001 | Users can browse remote WebDAV albums (directories) in a list.                                  | Implemented |
| A-002 | Tapping an album opens a detail view showing the photos and videos (MP4, M4V, MOV, 3GP, 3G2, WebM, MKV) inside it as thumbnails; a video tile shows its server preview or a placeholder with a play icon and is described "Video N of M" (issue #74). | Implemented |
| A-003 | Users can long-press to enter selection mode and delete selected photos and videos from the server. | Implemented |
| A-004 | A duplicate-finder scan is available per album (photos only; videos are ignored).               | Implemented |
| A-005 | A blur-detection scan is available per album (photos only; videos are ignored).                 | Implemented |
| A-006 | The album list can be searched by name (case- and accent-insensitive); the query survives rotation, tab switches and refresh. | Implemented |
| A-007 | The album list can be sorted by name (A–Z default, Z–A) or last modified (newest / oldest first, undated last); the choice is remembered and reset on logout. | Implemented |
| A-008 | Selected album photos can be saved to the device from the selection top bar: a background download to `Pictures/KroehnSync/<album>/` that needs no storage permission, never overwrites a file and is not marked as uploaded (issue #73). | Implemented |

---

## 5. Fullscreen Viewer

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| V-001 | Tapping a photo thumbnail in an album opens it in a fullscreen immersive viewer.                | Implemented |
| V-002 | **The viewer must support horizontal swiping to navigate between photos in the album.**         | Implemented |
| V-003 | The viewer must display a page counter (e.g. "3 / 12") when the album contains more than one photo. | Implemented |
| V-004 | Pinch-to-zoom must be supported within a single photo (up to 8×).                              | Implemented |
| V-005 | When a photo is zoomed in, swiping must pan the zoomed image instead of switching pages.        | Implemented |
| V-006 | When zoom is reset to 1×, horizontal swiping must switch pages again.                          | Implemented |
| V-007 | System bars are hidden in the viewer; a tap toggles overlay controls (back button, page counter). | Implemented |
| V-008 | Controls auto-hide after 3 seconds of inactivity (on a video page only while the video plays; a paused or failed video keeps its controls; photos unchanged; the delay honours the system accessibility timeout). | Implemented |
| V-009 | The viewer opens at the index of the photo the user tapped.                                     | Implemented |
| V-010 | A video page shows the server preview (or a placeholder) with a play button; the video file itself is not requested before the tap. | Implemented |
| V-011 | Videos play in the app, streamed from the configured server only with origin-restricted credentials, pinned trust, no redirects and no copy on the device; play / pause and a seek bar are available. | Implemented |
| V-012 | Playback stops on page change, when the album is closed or the account / server changes, and pauses when the viewer goes to the background (not on rotation). | Implemented |
| V-013 | The viewer offers "Save to device" for the current photo (not on a video page); a photo already saved from this viewer session cannot be saved again by accident (issue #73). | Implemented |

> **Note on V-002 / V-006:** The root cause of the regression was that `detectTransformGestures`
> unconditionally consumed all pointer events — including single-finger horizontal swipes — so
> the `HorizontalPager` never received them. The fix replaces it with a custom `awaitEachGesture`
> handler that only consumes events during multi-touch (pinch) or when `scale > 1f`.

---

## 6. Settings

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| S-001 | Users can configure the WebDAV server URL, username, and password.                              | Implemented |
| S-002 | Users can configure the base folder on the server.                                              | Implemented |
| S-003 | Credentials are stored encrypted using `androidx.security:security-crypto`.                    | Implemented |
| S-004 | Nextcloud Login Flow v2 is supported as an alternative to manual credential entry.              | Implemented |
| S-005 | Users can trust one self-signed server certificate for the configured server after reviewing and confirming its SHA-256 fingerprint; the trust is scoped to that host and port, can be withdrawn ("Stop trusting"), is removed on logout or server change and is never backed up. | Implemented |

---

## 7. Non-Functional

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| N-001 | All tests must pass (`./gradlew test`) before a change is considered done.                      | Policy      |
| N-002 | Code must pass `./gradlew spotlessCheck` (ktlint formatting) before merging.                   | Policy      |
| N-003 | No new detekt issues may be introduced without explicit approval.                               | Policy      |
| N-004 | The app must not suppress lint/detekt warnings without justification.                           | Policy      |
| N-005 | Saving album photos never requests a storage permission, never deletes anything but the app's own incomplete items and never creates an uploaded marker or a verified-upload record. | Policy      |
