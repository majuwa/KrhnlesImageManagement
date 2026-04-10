# Requirements – Kröhnle's Image Management

This document captures the functional and non-functional requirements derived from
implemented behaviour, user feedback, and explicit feature requests.

---

## 1. General

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| G-001 | The app must be privacy-first: no cloud analytics, no telemetry.                                | Implemented |
| G-002 | The app must target Android 13+ and handle granular media permissions (`READ_MEDIA_IMAGES`).    | Implemented |
| G-003 | All UI must be built with Jetpack Compose (no XML layouts).                                     | Implemented |
| G-004 | All async and background work must use Kotlin Coroutines / Flow.                                | Implemented |

---

## 2. Photo Selection (Local)

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| P-001 | Users can browse local device photos in a grid view.                                            | Implemented |
| P-002 | Users can select one or more photos for upload.                                                 | Implemented |
| P-003 | Users must tag selected photos with an occasion name before uploading.                          | Implemented |
| P-004 | The grid must use `LazyVerticalGrid` for high-performance scrolling.                            | Implemented |

---

## 3. Upload

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| U-001 | Uploads must use `WorkManager` so they survive app closure or process death.                    | Implemented |
| U-002 | Photos are uploaded to a WebDAV server (Nextcloud-compatible).                                  | Implemented |
| U-003 | The upload destination folder is derived from the occasion name provided by the user.           | Implemented |
| U-004 | Self-signed TLS certificates must be handled gracefully with a clear user-facing error message. | Implemented |
| U-005 | A retry mechanism must be available when an upload fails.                                       | Implemented |

---

## 4. Albums (Remote)

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| A-001 | Users can browse remote WebDAV albums (directories) in a list.                                  | Implemented |
| A-002 | Tapping an album opens a detail view showing the photos inside it as thumbnails.                | Implemented |
| A-003 | Users can long-press to enter selection mode and delete selected photos from the server.        | Implemented |
| A-004 | A duplicate-finder scan is available per album.                                                 | Implemented |
| A-005 | A blur-detection scan is available per album.                                                   | Implemented |

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
| V-008 | Controls auto-hide after 3 seconds of inactivity.                                               | Implemented |
| V-009 | The viewer opens at the index of the photo the user tapped.                                     | Implemented |

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

---

## 7. Non-Functional

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| N-001 | All tests must pass (`./gradlew test`) before a change is considered done.                      | Policy      |
| N-002 | Code must pass `./gradlew spotlessCheck` (ktlint formatting) before merging.                   | Policy      |
| N-003 | No new detekt issues may be introduced without explicit approval.                               | Policy      |
| N-004 | The app must not suppress lint/detekt warnings without justification.                           | Policy      |
