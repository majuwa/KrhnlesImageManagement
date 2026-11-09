# Requirements – Kröhnle's Image Management

This document captures the functional and non-functional requirements derived from
implemented behaviour, user feedback, and explicit feature requests.

---

## 1. General

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| G-001 | The app must be privacy-first: no analytics or telemetry of its own; photos, videos, file and folder names and credentials are sent only to the configured server. Exception: the blur review uses the bundled Google ML Kit object detection on the device (images never leave the device); the ML Kit SDK may fetch updates from Google and sends API usage and performance metrics to Google (ML Kit terms). | Implemented |
| G-002 | The app must target Android 15+ (`minSdk` 35) and handle granular media permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) and `ACCESS_MEDIA_LOCATION` are requested together; any of the first three gives access, `ACCESS_MEDIA_LOCATION` never does (it lets uploads keep the photos' location); photos stay usable without video access, upgrade users are asked for video access and full-access users for the location permission at most once, and partial-access users never automatically (issues #15 / #34). | Implemented |
| G-003 | All UI must be built with Jetpack Compose (no XML layouts).                                     | Implemented |
| G-004 | All async and background work must use Kotlin Coroutines / Flow.                                | Implemented |
| G-005 | The UI must be available in English and German (informal "du", all regions); the app language can be chosen per app on Android 13+ and other languages fall back to English (issue #76). | Implemented |

---

## 2. Photo Selection (Local)

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| P-001 | Users can browse local device photos and videos in a grid view (videos with play icon and duration; uploaded like photos, issues #15 / #34). | Implemented |
| P-002 | Users can select one or more photos for upload.                                                 | Implemented |
| P-003 | Before uploading, users tag the selected photos with an occasion name, unless automatic `YYYY/MM/DD` day folders are enabled in Settings (issues #18 / #37). | Implemented |
| P-004 | The grid must use `LazyVerticalGrid` for high-performance scrolling.                            | Implemented |
| P-005 | The occasion dialog suggests recent occasions and existing remote albums.                       | Implemented |
| P-006 | Photos and videos uploaded from this device are marked in the grid (cloud badge, announced by TalkBack) and a filter shows only new ones; marked items can be uploaded again; the markers are reset on disconnect and never backed up (issues #13 / #32). | Implemented |
| P-007 | Each grid item and day header is one screen-reader element with a localized description (position, photo / video, duration, capture date or "date unknown", uploaded state) and a select action; file names are never announced (issues #30 / #49). | Implemented |
| P-008 | Photos and videos are removed from the device only by the user-triggered "Remove uploaded photos from device" action: only verified uploads to the current server that are unchanged on the device and still on the server with the same size are moved to the system trash after Android's confirmation; nothing is deleted permanently or on the server. | Implemented |

---

## 3. Upload

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| U-001 | Uploads must use `WorkManager` so they survive app closure or process death.                    | Implemented |
| U-002 | Photos are uploaded to a WebDAV server (Nextcloud-compatible).                                  | Implemented |
| U-003 | The upload destination folder is the occasion name below the base folder or, with auto date folders on, each photo's capture day (`YYYY/MM/DD`, ASCII digits, one background upload per selection); shared photos always use the occasion (issues #18 / #37). | Implemented |
| U-004 | Untrusted or changed TLS certificates must be handled gracefully with a clear user-facing error message and, for the configured server, an explicit review (S-005). | Implemented |
| U-005 | A retry mechanism must be available when an upload fails.                                       | Implemented |
| U-006 | A running upload can be cancelled and resumed later (Cancel in the notification and in the app; uploaded items stay uploaded, the rest is resumed with "Retry Failed"). | Implemented |
| U-007 | Large files on Nextcloud (at least 50 MiB) are uploaded in resumable 10 MiB chunks: an interrupted file continues after its last confirmed chunk, server body-size limits do not apply, the progress shows the file percentage (issue #69). | Implemented |
| U-008 | Album photos saved to the device are downloaded by `WorkManager` in the background (survives closing the app), one batch at a time, with a progress notification and Cancel; an interrupted batch resumes without re-downloading finished photos; the result reports saved / failed counts with typed reasons (issue #73). | Implemented |
| U-009 | A downloaded photo is limited to 200 MiB, must be an allow-listed raster image (videos are reported as unsupported), is requested only below its album with origin-restricted credentials and never through a redirect, and becomes visible only when complete. | Implemented |
| U-010 | After a partially failed upload the result notification offers "Retry Failed (N)", which uploads only the failed items into their original folders (shared photos from the app-private copy for 7 days) (issues #19 / #38). | Implemented |
| U-011 | An optional "Upload on Wi-Fi only" setting (off by default) runs uploads, including retries, only on an unmetered network; waiting does not count towards giving up; the setting is reset on logout and not backed up (issues #14 / #33). | Implemented |
| U-012 | Every upload run (including retries and cancelled runs) is recorded in a local upload history (folder, time, uploaded / failed counts, at most 500 entries) that can be cleared per entry or completely; it is not backed up and not cleared on logout (issue #11). | Implemented |
| U-013 | Up to 100 images can be shared into the app from other apps; only allow-listed raster image types are accepted, each is copied to app-private storage (at most 100 MiB per file, 1 GiB per share) before the user returns to the sharing app, and uploaded into the entered occasion folder (issues #16 / #35). | Implemented |
| U-014 | Every photo and video uploaded from the device as the original is verified: on Nextcloud with the server's SHA-256 (`X-Hash`), on other servers only with "Verify by downloading again" (off by default); a mismatch or failed check counts as failed and can be retried; uploads without the original (no `ACCESS_MEDIA_LOCATION`) and shared photos are uploaded but not verified. | Implemented |
| U-015 | An interrupted upload (for example by Android's background time limit) is resumed later with the items not yet uploaded and stops with "Retry Failed" after 5 interrupted runs; "file too large" (HTTP 413) and "no storage left" (HTTP 507) are reported in the result notification (issues #15 / #34). | Implemented |

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
| A-009 | Users can rename a remote album with a validated name (trimmed, not blank or unchanged, no `/`, `\`, `.` or `..`, at most 255 UTF-8 bytes); an existing album is never overwritten, and a rename without an answer within 30 s is cancelled and the list reloaded (issues #17 / #36). | Implemented |
| A-010 | Album listings are streamed with bounded memory and show progress while loading; an album above 20,000 photos and videos or a 32 MiB listing shows its first items with a notice that the duplicate and blur reviews repeat; an album list above 32 MiB fails with an explanation (issues #27 / #46). | Implemented |
| A-011 | Failures of the album list, an album and the duplicate / blur reviews are shown as plain-language messages (never raw server or exception text) with a retry; a logout or server change closes an open album. | Implemented |

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
| S-003 | Username and password are encrypted with AES-256-GCM under an Android Keystore key (`krhnles_master_key`) and stored in `SharedPreferences` (`krhnles_secure`); only the server URL and base folder are stored unencrypted; decryption failures are classified and shown in Settings with a request to log in again (issues #31 / #50). | Implemented |
| S-004 | Nextcloud Login Flow v2 is supported as an alternative to manual credential entry.              | Implemented |
| S-005 | Users can trust one self-signed server certificate for the configured server after reviewing and confirming its SHA-256 fingerprint; the trust is scoped to that host and port, can be withdrawn ("Stop trusting"), is removed on logout or server change and is never backed up. | Implemented |
| S-006 | Users can opt in (off by default) to a periodic reminder (Weekly / Every 2 weeks / Monthly) about photos and videos added since the last upload that are not marked uploaded; it asks for the notification permission when turned on, needs photo access, opens the filtered photo grid when tapped, is reset on logout and is never backed up. | Implemented |
| S-007 | Users can choose the blur sensitivity (Low / Medium / High, default Medium) in Settings; a changed level applies to the next blur scan, is reset on logout and is not backed up (issues #20 / #39). | Implemented |
| S-008 | A fresh install shows a two-page, skippable introduction once, whose "Connect now" opens Settings; users with an existing configuration never see it (issues #21 / #40). | Implemented |
| S-009 | "Test Connection" checks the stored server and credentials within 30 s, ignores repeated taps while it runs and reports an untrusted certificate, unknown host, unreachable server, timeout, HTTP 401 / 403, 404 or another HTTP status in plain language; a logout or configuration change clears the result (issues #22 / #41). | Implemented |

---

## 7. Non-Functional

| ID    | Requirement                                                                                     | Status      |
|-------|-------------------------------------------------------------------------------------------------|-------------|
| N-001 | All tests must pass (`./gradlew test`) before a change is considered done.                      | Policy      |
| N-002 | Code must pass `./gradlew spotlessCheck` (ktlint formatting) before merging.                   | Policy      |
| N-003 | No new detekt issues may be introduced without explicit approval.                               | Policy      |
| N-004 | The app must not suppress lint/detekt warnings without justification.                           | Policy      |
| N-005 | Saving album photos never requests a storage permission, never deletes anything but the app's own incomplete items and never creates an uploaded marker or a verified-upload record. | Policy      |
| N-006 | The reminder check never uses the network, never reads credentials, never starts or cancels uploads or downloads and only reads ids and add dates of the media index (bounded rows and time). | Policy      |
| N-007 | Every new or changed user-facing string, plural and string array has a German translation in `values-de` in the same change (identical placeholders, same plural quantities); non-translatable texts are marked `translatable="false"`. | Policy      |
| N-008 | Server responses are untrusted: server-supplied hrefs are used only on the configured origin and below the base folder (or album), credentials are sent only to the configured origin, and PROPFIND responses are stream-parsed with size, depth and attribute limits and without DOCTYPE or entities. | Implemented |
