# Spec Delta

## Purpose

Lets the user save photos from a remote album back to the device. Saving runs in the background, writes only complete files into the shared Pictures collection, and never trusts the server beyond the configured album.

## ADDED Requirements

### Requirement: Save to device is offered in the viewer and in selection mode
The fullscreen viewer SHALL show a "Save to device" button for the current photo in its overlay controls. The album detail selection top bar SHALL show a "Save to device" action that saves every selected photo and then clears the selection. Both SHALL have localized accessibility labels. The selection action SHALL be disabled while a photo deletion runs.

#### Scenario: Save from the viewer
- **WHEN** the user taps "Save to device" in the viewer
- **THEN** a background download of exactly the photo on the current page is started

#### Scenario: Save the selection
- **WHEN** three photos are selected and the user taps "Save to device"
- **THEN** one background download of those three photos is started and the selection is cleared

#### Scenario: Deletion running
- **WHEN** a selection-mode deletion is running
- **THEN** the "Save to device" action is disabled

#### Scenario: Repeated tap in the viewer
- **WHEN** the user taps "Save to device" again for the same photo while the viewer stays open
- **THEN** no second download of that photo is started from this viewer session

### Requirement: Starting a download is acknowledged
When a download is started, the app SHALL show a short localized message with the number of photos (plural form). If the download cannot be started (for example, not configured or the request cannot be stored), the app SHALL show a localized failure message and SHALL leave no queue file behind.

#### Scenario: Download started
- **WHEN** the user saves two photos
- **THEN** a message such as "Saving 2 photos in the background" is shown

#### Scenario: Not configured
- **WHEN** the configuration is missing or invalid when the user taps "Save to device"
- **THEN** a localized message explains that no server is configured and nothing is queued

### Requirement: Downloads run in the background and survive closing the app
Saving SHALL run as background work that continues when the app is closed or its process ends. While it runs, it SHALL show an ongoing progress notification with the saved / total count and a Cancel action. Downloads started one after another SHALL run one batch at a time, in the order they were started.

#### Scenario: App closed during a download
- **WHEN** the user closes the app while photos are being saved
- **THEN** the remaining photos are still saved

#### Scenario: Two batches
- **WHEN** the user starts a second download while the first runs
- **THEN** the second batch starts after the first has finished

### Requirement: Only photos of the open album are saved
A download SHALL contain only photos of the currently loaded album listing. Photos not in that listing, for example stale results after a server or account change, SHALL NOT be queued.

#### Scenario: Stale photo
- **WHEN** a save is requested for a photo that is no longer part of the loaded album
- **THEN** that photo is not queued

#### Scenario: Truncated album
- **WHEN** the album listing was truncated and the user saves a selected photo
- **THEN** that photo is saved like any other

### Requirement: The original file is saved
Each saved photo SHALL be the file stored on the server at the photo's WebDAV location, byte for byte. A preview or thumbnail rendition SHALL never be saved, and the request SHALL ask for an unencoded transfer.

#### Scenario: Nextcloud with Memories
- **WHEN** the viewer shows a Memories preview and the user saves the photo
- **THEN** the saved file has exactly the bytes of the WebDAV file, not those of the preview

### Requirement: Downloads never follow redirects
A download request that receives any 3xx response SHALL fail for that photo without following the redirect. Credentials SHALL be attached only to requests for the configured origin.

#### Scenario: Redirect
- **WHEN** the server answers a download with 302 to another location
- **THEN** no request is sent to that location and the photo counts as failed

### Requirement: Downloads are size-bounded
A photo larger than the per-file limit (200 MiB) SHALL NOT be saved. An announced `Content-Length` above the limit SHALL fail the photo before anything is written. A body that exceeds the limit while streaming SHALL fail the photo and discard what was written. The body SHALL be streamed to the destination, never held in memory as a whole.

#### Scenario: Announced too large
- **WHEN** the server announces a `Content-Length` above the limit
- **THEN** no file is created and the photo counts as failed with the "too large" reason

#### Scenario: Streamed too large
- **WHEN** a chunked body grows beyond the limit
- **THEN** the transfer stops, the partial file is removed and the photo counts as failed with the "too large" reason

### Requirement: Insufficient storage stops the batch
If the device lacks space for a photo (the announced size does not fit the free space minus a safety margin, or a write fails because the storage is full), that photo and all later photos of the batch SHALL fail with a "storage full" reason, and nothing partial SHALL remain.

#### Scenario: Storage full while writing
- **WHEN** the device runs out of space while a photo is written
- **THEN** that photo's partial file is removed, the remaining photos are not requested and the notification names the storage reason

### Requirement: Only allow-listed image types are saved
A photo SHALL be saved only if its file-name extension maps to an allow-listed raster image type (JPEG, PNG, GIF, WebP, HEIC, HEIF, AVIF, BMP, DNG). The saved item SHALL get that type's MIME type. Any other entry (video, SVG, TIFF, unknown extension) SHALL be skipped without a request and counted as failed with an "unsupported type" reason. A response whose `Content-Type` is present and is neither an image type nor `application/octet-stream` SHALL fail the photo.

#### Scenario: Video in the album
- **WHEN** the selection contains a video file
- **THEN** no request is sent for it and it counts as "unsupported type"

#### Scenario: SVG
- **WHEN** the user saves `drawing.svg`
- **THEN** it is not downloaded and counts as "unsupported type"

#### Scenario: HTML instead of an image
- **WHEN** the server answers a `.jpg` request with 200 and `Content-Type: text/html`
- **THEN** nothing is kept on the device and the photo counts as failed

### Requirement: Saved photos go to a per-album folder of the app
Saved photos SHALL be written to the shared Pictures collection under `Pictures/KroehnSync/<album>/`. `<album>` is the album folder name, sanitized to one safe path segment, and the app folder name SHALL NOT depend on the device language. Saving SHALL NOT request a storage or media permission.

#### Scenario: Saved location
- **WHEN** a photo from the album "Summer 2026" is saved
- **THEN** it appears in the device gallery under `Pictures/KroehnSync/Summer 2026/`

#### Scenario: Unsafe album name
- **WHEN** the album folder name contains characters such as `:` `*` `?` `"` `<` `>` `|`, control or bidi-format characters, or consists only of dots or spaces
- **THEN** those characters are replaced or a neutral fallback name is used, and the photo is still saved in a single folder below `Pictures/KroehnSync/`

### Requirement: Saved file names are safe and never overwrite
The saved display name SHALL come from the decoded last path segment of the photo's href, with path separators, control and format characters and characters invalid on common file systems replaced. It SHALL be at most 255 UTF-8 bytes with the allow-listed extension kept, and a generated name SHALL be used when nothing usable remains. Saving SHALL NOT overwrite an existing file: a name already in the folder SHALL get a unique variant.

#### Scenario: Same photo saved twice
- **WHEN** `IMG_1.jpg` is saved twice into the same album folder
- **THEN** both files exist; the second has a unique name and the first is unchanged

#### Scenario: Encoded name
- **WHEN** the href ends with `Caf%C3%A9%20%E2%80%AE gpj.jpg`
- **THEN** the saved name contains `Café` and no right-to-left override character

### Requirement: Incomplete files never appear
A photo SHALL become visible to other apps only after its complete body was written. If the transfer fails, is cancelled or the process ends while writing, the app SHALL remove the incomplete item. An incomplete item left by an earlier, interrupted run SHALL be removed when the batch resumes.

#### Scenario: Server fails mid-body
- **WHEN** the connection drops after half the body
- **THEN** no file of that photo remains on the device

#### Scenario: Process killed while writing
- **WHEN** the process is killed while a photo is written and the batch is resumed later
- **THEN** the incomplete item of that photo is removed and the photo is downloaded again

### Requirement: Downloads can be cancelled
The progress notification's Cancel action SHALL stop the running batch and the batches queued after it. The photo being transferred SHALL be discarded, photos already saved SHALL stay, and a result notification SHALL report how many were saved before the cancellation.

#### Scenario: Cancel during a transfer
- **WHEN** the user taps Cancel while the third of ten photos is transferred
- **THEN** the transfer is aborted at once, the third photo leaves no file, the first two stay saved and the notification reports 2 of 10 saved

#### Scenario: System stop
- **WHEN** the system stops the work (for example, the network is lost) rather than the user
- **THEN** the batch is resumed later without re-downloading the photos already saved

### Requirement: Interrupted batches resume without duplicates
After each saved photo, the batch SHALL persist that the photo is done, and a resumed batch SHALL download only the photos not yet saved. If the foreground service is refused, the batch SHALL be retried later. A batch interrupted 5 times SHALL stop, with the remaining photos counted as failed with an "interrupted" reason.

#### Scenario: Resume
- **WHEN** a batch of five photos was interrupted after the second
- **THEN** the resumed batch downloads only photos three to five and the result counts all five

#### Scenario: Interruption budget used up
- **WHEN** the foreground service was refused for the fifth time
- **THEN** the batch ends with a result notification naming the interruption reason

### Requirement: The result is reported with counts and typed reasons
When a batch ends, a result notification SHALL show the number of saved and failed photos in plural forms, followed by one localized sentence for each distinct failure reason. Reasons SHALL come from a fixed set of typed causes; server or exception text SHALL never be shown.

#### Scenario: Partial success
- **WHEN** 8 of 10 photos are saved and 2 were deleted on the server meanwhile
- **THEN** the notification reports 8 saved and 2 failed and names the "not found" reason once

#### Scenario: Reason set
- **WHEN** photos fail for different causes
- **THEN** each cause is reported as one of: unsupported type, too large, storage full, not found on the server, access denied, certificate problem, connection problem, server error, rejected by the app, could not write, server configuration changed, interrupted

#### Scenario: Server error text
- **WHEN** the server answers with an error page containing text
- **THEN** that text does not appear in any notification

### Requirement: Each batch has its own result notification
Each download batch SHALL post its own result notification, separate from other download batches and from upload notifications, so that none replaces another.

#### Scenario: Concurrent upload
- **WHEN** an upload and a download finish at the same time
- **THEN** both result notifications are shown and neither replaces the other

#### Scenario: Two download batches
- **WHEN** two download batches finish one after another
- **THEN** both result notifications remain visible

### Requirement: Download notifications use their own channel and glyph
Download progress and result notifications SHALL use a dedicated "Downloads" notification channel and a dedicated monochrome download glyph as their small icon, never the launcher icon.

#### Scenario: Progress notification
- **WHEN** a download runs
- **THEN** its notification is in the "Downloads" channel with the download glyph

### Requirement: The queued download holds no credentials
A queued download SHALL contain only the server URL, the album and photo hrefs, and per-photo progress. It SHALL never contain the username, password or token. It SHALL be stored only in the app's storage that is excluded from backup and device transfer, and a queued download SHALL be accepted only from that location under the app's own naming pattern.

#### Scenario: Work input
- **WHEN** a download is enqueued
- **THEN** the work input contains only the path of the queue file, and the queue file contains no credentials

#### Scenario: Foreign queue path
- **WHEN** the work is started with a path outside the app's queue directory or with an unexpected file name
- **THEN** nothing is downloaded

### Requirement: Configuration changes stop the download
Before each photo, the download SHALL check that the configured server URL still equals the one the batch was queued for and that the configuration is valid. Otherwise, it SHALL send no further requests, and the remaining photos SHALL fail with the "server configuration changed" reason.

#### Scenario: Logout during a download
- **WHEN** the user logs out while a batch is running
- **THEN** no further photo of that batch is requested

### Requirement: Batch-wide failures stop the batch
An authentication failure (401 / 403), a certificate failure, or 5 consecutive connection or server failures SHALL stop the batch. The remaining photos SHALL count as failed with that reason, and no further request SHALL be sent.

#### Scenario: Password changed on the server
- **WHEN** the first photo fails with 401
- **THEN** no further photo is requested and the notification names the access reason

### Requirement: Downloads are independent of the Wi-Fi-only upload setting
Saving SHALL require a network connection, but SHALL NOT wait for an unmetered network when "Wi-Fi only" is enabled for uploads, because the user starts each download explicitly.

#### Scenario: Wi-Fi only on mobile data
- **WHEN** "Wi-Fi only" is on, the device is on mobile data and the user saves a photo
- **THEN** the photo is downloaded without waiting for Wi-Fi
