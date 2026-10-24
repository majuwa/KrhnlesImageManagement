## ADDED Requirements

### Requirement: The app is a share target for images
The app SHALL appear in the system share sheet for `image/*` content sent with `ACTION_SEND` or `ACTION_SEND_MULTIPLE`. Sharing one or more images SHALL open the occasion dialog showing the number of accepted photos, and cancelling it SHALL finish the activity without uploading or copying anything. Confirming SHALL queue one background upload into the entered occasion folder, also when auto date folders are enabled, and then finish the activity so the user returns to the sharing app.

#### Scenario: Single image shared
- **WHEN** the user shares one image from the gallery to the app
- **THEN** the occasion dialog opens for 1 photo

#### Scenario: Several images shared
- **WHEN** the user shares three images with `ACTION_SEND_MULTIPLE`
- **THEN** the occasion dialog opens for 3 photos and confirming queues one upload with 3 photos

#### Scenario: Auto date folders do not apply to shares
- **WHEN** auto date folders are enabled and the user confirms a share with an occasion name
- **THEN** all photos are uploaded into that occasion folder

#### Scenario: Cancel
- **WHEN** the user cancels the dialog
- **THEN** the activity finishes and nothing is copied or queued

### Requirement: Shared photos stay readable until uploaded
When the user confirms a share, the app SHALL copy every accepted photo into app-private, backup-excluded staging storage before finishing the activity, and the upload SHALL read the staged copies, not the original URIs. Shared photos SHALL carry no MediaStore id, so the upload never marks a MediaStore photo as uploaded.

#### Scenario: Upload runs after the share activity finished
- **WHEN** the user confirms a share, the activity finishes and the worker runs later without any grant for the original URI
- **THEN** all photos are uploaded from their staged copies and no MediaStore photo is marked as uploaded

#### Scenario: Worker re-run
- **WHEN** the worker is stopped after some uploads and run again
- **THEN** all photos are uploaded from their staged copies

### Requirement: Copying is visible and cancellable
While the share is being read the activity SHALL show a progress indicator with a Cancel action. While copying it SHALL show "Preparing x of n" with a Cancel action and ignore further confirms. Cancel SHALL take effect immediately, also while a provider call is blocked.

#### Scenario: Provider blocks
- **WHEN** a provider does not return from opening or querying an item, also ignoring cancellation
- **THEN** Cancel finishes the share at once, and an item that does not finish within the per-item timeout is skipped as unreadable

#### Scenario: Reading progress
- **WHEN** the shared items' metadata is still being read
- **THEN** a progress indicator with Cancel is shown

#### Scenario: Rotation while copying
- **WHEN** the device is rotated while photos are being copied
- **THEN** the copy continues once and exactly one upload is queued

### Requirement: Skipped photos are reported with their reason
A photo that cannot be copied SHALL be skipped and reported with its reason: unreadable or larger than the per-file cap, or not fitting into the per-share cap (reported separately). If every photo was skipped nothing SHALL be queued and a notice that none of the photos could be read SHALL be shown, distinct from the copy-failed (storage) message.

#### Scenario: Unreadable item
- **WHEN** one of three shared photos cannot be read while copying
- **THEN** the other two are queued and the user is told that one photo was skipped

#### Scenario: Nothing could be copied
- **WHEN** every shared photo is skipped while copying
- **THEN** nothing is queued and the user is told that none of the photos could be read, not that storage is missing

#### Scenario: Share budget exceeded
- **WHEN** a photo no longer fits into the per-share cap
- **THEN** it is left out and the user is told that it exceeded the share limit

### Requirement: Failed or cancelled copies leave nothing behind
Cancel, Back, the ViewModel being cleared, an enqueue failure (any failure except cancellation, without crashing) or a write/space failure SHALL stop the share, delete all partial and staged files of that share and queue nothing. A copy that completes after it was cancelled or timed out SHALL delete its file. Insufficient space or an I/O error SHALL produce one copy-failed message; an enqueue failure SHALL show a failure message.

#### Scenario: Cancel while copying
- **WHEN** the user cancels during the copy
- **THEN** the copy stops, no staged or partial file remains and nothing is queued

#### Scenario: Copy completes after cancellation
- **WHEN** a photo's copy completes just as the share is cancelled
- **THEN** its staged file is deleted

#### Scenario: Out of space
- **WHEN** there is not enough free space or a write fails
- **THEN** the share stops with a single copy-failed message and its files are removed

### Requirement: Staged photos are cleaned up
Staged files SHALL be deleted only when their queue file is deleted, except files referenced by a newly written retry file; one helper SHALL be used at every queue-file deletion.

#### Scenario: Staged copy kept until the queue is final
- **WHEN** a staged photo uploads successfully
- **THEN** its staged file is not deleted until the queue file is deleted

### Requirement: Old staged photos are swept
Staged files older than 7 days and not referenced by an `upload_queue_*` file SHALL be swept off the main thread. One referenced by retry files SHALL be deleted with them only if every such retry file is itself older than 7 days by its own modification time and has no unfinished retry work (an unknown work state keeps it). The sweep SHALL delete nothing if a queue file cannot be read during the scan.

#### Scenario: Orphaned retry file
- **WHEN** a staged file is older than 7 days and referenced only by a retry file that is itself older than 7 days and has no unfinished retry work
- **THEN** the staged file and the retry file are deleted

#### Scenario: Old staged file of a young retry file
- **WHEN** a staged file is older than 7 days but the retry file referencing it is younger than 7 days
- **THEN** neither is deleted

#### Scenario: Retry still running
- **WHEN** the retry work of an old retry file is still enqueued or running
- **THEN** its staged files and the retry file are kept

#### Scenario: Unreadable queue file during sweep
- **WHEN** a queue file cannot be read while sweeping
- **THEN** no staged file is deleted

### Requirement: Staged entries are validated and retryable
A staged name SHALL match the UUID pattern, resolve below the staging folder and be accepted only with `id = -1`. A missing staged file SHALL fail that photo without reading the original URI. A retry of a failed staged photo SHALL read its staged copy; tapping "Retry Failed" SHALL refresh the retry file's modification time, so the 7-day retry window restarts.

#### Scenario: Failed staged photo can be retried
- **WHEN** a staged photo fails to upload and the user activates "Retry Failed"
- **THEN** the retry uploads it from its staged copy and the file survives queue deletion

#### Scenario: Retry tap restarts the retry window
- **WHEN** the user activates "Retry Failed" for a retry file
- **THEN** the retry file's modification time is set to now

#### Scenario: Invalid staged entry
- **WHEN** a queue entry has a `staged` name that is not a UUID, escapes the staging folder or has an id other than -1
- **THEN** the entry is rejected

### Requirement: Shared input is untrusted
URIs SHALL be read from `EXTRA_STREAM` (one URI or a list). Only `content://` URIs of other apps are accepted (`isAllowedPhotoUri`); `file://`, other schemes and URIs of this app SHALL be dropped. Extras of unexpected types, any runtime failure while reading the extras and provider failures (`SecurityException`, `IllegalArgumentException`, `IllegalStateException`, `OperationCanceledException`) SHALL be ignored without crashing.

#### Scenario: file URI ignored
- **WHEN** a share carries `file:///sdcard/a.jpg`
- **THEN** it is dropped and no dialog opens if nothing else remains

#### Scenario: Own app URI ignored
- **WHEN** a share carries a URI whose authority belongs to this app
- **THEN** it is dropped

#### Scenario: Unreadable extra
- **WHEN** reading `EXTRA_STREAM` throws a runtime exception
- **THEN** the share is treated as containing no photo and the app does not crash

### Requirement: Only allow-listed image types are accepted
An item's type SHALL be the provider type if it is concrete; if the provider reports none, `application/octet-stream` or a wildcard, the type derived from the display name's extension; without a known extension a concrete intent type (never a wildcard such as `image/*`). An item SHALL be accepted only if that type is an allow-listed raster image type (JPEG, PNG, GIF, WebP, HEIC, HEIF, AVIF, BMP, DNG); SVG and other types SHALL be dropped.

#### Scenario: Non-image item ignored
- **WHEN** a share carries a URI whose content type is `application/pdf`
- **THEN** it is dropped

#### Scenario: Unknown provider type with an image name
- **WHEN** the provider reports no content type, the intent type is `image/*` and the display name is `a.jpg`
- **THEN** the item is accepted as `image/jpeg`

#### Scenario: Octet-stream provider type
- **WHEN** the provider reports `application/octet-stream` and the display name is `x.png`
- **THEN** the item is accepted as `image/png`, also if the intent type is another image type

#### Scenario: SVG ignored
- **WHEN** a share carries a URI whose content type is `image/svg+xml`
- **THEN** it is dropped

### Requirement: Shared input is count-limited
Duplicate URIs SHALL be dropped and at most 100 photos per share accepted.

#### Scenario: Count limit
- **WHEN** a share carries 150 images
- **THEN** the first 100 are accepted and the user is told that 50 were not included

### Requirement: Shared items are size-bounded
Each staged copy SHALL be at most 100 MiB, one share at most 1 GiB in total and the whole staging folder at most 2 GiB, enforced on the bytes actually copied; items whose provider-reported size exceeds the per-file cap MAY be skipped up front.

#### Scenario: Oversized item
- **WHEN** a provider streams more than 100 MiB for one item
- **THEN** copying stops, the partial file is deleted and the item is skipped

### Requirement: Shared IO is off the main thread and not logged
Metadata queries and copying SHALL NOT run on the main thread, provider calls SHALL receive a cancellation signal and a per-item timeout, and URIs and file names SHALL NOT be logged.

#### Scenario: Metadata read
- **WHEN** a share is received
- **THEN** the provider is queried off the main thread and only exception types are logged

### Requirement: Shared file names are safe
A file name from the provider SHALL be used as the remote file name only if it is a single valid path segment without invisible formatting characters (Unicode category Cf such as bidi controls), line or paragraph separators or unpaired surrogates, otherwise a generated name (timestamp and short random id) with an extension matching the content type SHALL be used. The remote name SHALL end with an extension of the validated type (appended if missing or different).

#### Scenario: Malicious file name
- **WHEN** a provider reports the display name `../x.jpg`
- **THEN** the upload uses a generated safe name

#### Scenario: Bidi-disguised file name
- **WHEN** a provider reports a display name containing U+202E (right-to-left override)
- **THEN** the upload uses a generated safe name

#### Scenario: Extension does not match the type
- **WHEN** an item validated as `image/png` has the display name `evil.svg`
- **THEN** it is uploaded as `evil.svg.png`

### Requirement: Shared file names are unique within a share
Remote file names SHALL be unique within one share (case-insensitive). A same-named file already in the remote folder is overwritten by the upload (known behaviour, unchanged).

#### Scenario: Duplicate names in one share
- **WHEN** two shared photos report the display names `IMG.jpg` and `img.JPG`
- **THEN** they are uploaded under two different names

### Requirement: Sharing before the server is configured
If no valid WebDAV configuration exists (a configuration read that times out after 3 s counts as configured), a share SHALL NOT show the occasion dialog or copy anything. It SHALL show a localized message that a server must be set up first; any action that opens the app SHALL start it in a new task. A share that contains no accepted photo SHALL show a localized notice that no supported photo was received and finish without launching the app.

#### Scenario: Not configured
- **WHEN** the user shares an image and no server is configured
- **THEN** a message asks to set up the server and nothing is copied

#### Scenario: Nothing usable
- **WHEN** a share contains only unsupported items
- **THEN** a no-supported-photo notice is shown and the activity finishes
