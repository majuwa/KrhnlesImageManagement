# auto-date-folders Specification

## Purpose
Lets users who prefer chronological archives upload without typing an occasion name: the app puts each photo into a folder derived from its capture day, uploading the whole selection as one background upload. The preference lifecycle (logout, restore) is specified in `openspec/specs/credential-storage/spec.md`; retry of failed photos in `openspec/specs/failed-photo-retry/spec.md`.

## Requirements

### Requirement: Auto date folders can be switched on and off in Settings
The logged-in Settings content SHALL offer an "Auto date folders" switch with an explanatory hint naming the `YYYY/MM/DD` format. It SHALL be off by default and the chosen value SHALL persist across app restarts and screen recreation.

#### Scenario: Default
- **WHEN** the setting was never changed
- **THEN** auto date folders are off

#### Scenario: Toggle persists
- **WHEN** the user turns the switch on
- **THEN** the value is stored and the switch shows on after the screen is recreated

### Requirement: Enabled mode skips the occasion dialog
When auto date folders are on and a server is configured, tapping the upload action SHALL NOT show the occasion dialog; it SHALL show a preview listing one resolved path per distinct capture-day folder instead. Nothing is uploaded until the user confirms the preview; cancelling uploads nothing and keeps the selection. If no server is configured the "not configured" dialog is shown as before.

#### Scenario: Upload action with the mode on
- **WHEN** photos are selected and the user taps upload
- **THEN** the preview dialog lists the target folders and no occasion dialog is shown

#### Scenario: Cancel
- **WHEN** the user cancels the preview
- **THEN** no upload is enqueued and the selection is kept

#### Scenario: Not configured
- **WHEN** the mode is on, no server is configured and the user taps upload
- **THEN** only the "not configured" dialog is shown

### Requirement: The folder preview scrolls
The preview's path list SHALL scroll within a bounded height so that every path is reachable while the confirm and cancel buttons stay visible.

#### Scenario: Many days
- **WHEN** the selection spans 40 capture days
- **THEN** the preview lists 40 paths, the last path can be scrolled into view and the confirm button stays visible

### Requirement: Folder is derived from the capture day
Each photo SHALL be uploaded to `<baseFolder>/YYYY/MM/DD/`, where YYYY, MM and DD are the photo's capture year, month and day, zero-padded digits only (`2026/05/14`). The capture day SHALL be the photo's `DATE_TAKEN` converted to a date in the device's default time zone when the photo list is loaded, i.e. the same date as the photo's date header in the grid. No month names and no device-locale-dependent text or digits are used.

#### Scenario: Format
- **WHEN** a photo was taken on 2026-05-14
- **THEN** it is uploaded to `<baseFolder>/2026/05/14/`

#### Scenario: Device language does not change the folder
- **WHEN** the default locale is German or Arabic with Arabic-Indic digits and a photo was taken on 2026-03-09
- **THEN** the folder is `2026/03/09` with ASCII digits

#### Scenario: Device time zone decides the day
- **WHEN** the device zone is Europe/Berlin and a photo's `DATE_TAKEN` is 2026-05-13T22:30Z
- **THEN** its capture day, date header and folder are 2026-05-14 / `2026/05/14`

#### Scenario: Zone west of UTC
- **WHEN** the device zone is America/Los_Angeles and a photo's `DATE_TAKEN` is 2026-05-14T06:59Z
- **THEN** its folder is `2026/05/13`

### Requirement: A selection is uploaded as one background upload
When auto date folders are on, confirming the preview SHALL queue exactly one background upload for the whole selection, in which every photo is uploaded into its own capture-day folder. The upload SHALL have one progress indication covering all photos of the selection, one result notification and at most one retry action. Uploads in occasion mode and from the share target are unchanged (one folder per upload).

#### Scenario: Two days
- **WHEN** photos from 2026-04-30 and 2026-05-01 are selected and the preview is confirmed
- **THEN** one upload is queued, the 2026-04-30 photos go into `2026/04/30` and the 2026-05-01 photos into `2026/05/01`

#### Scenario: One result for the selection
- **WHEN** that upload finishes with one failed photo
- **THEN** one result notification with "Retry Failed (1)" is shown

### Requirement: Photos are ordered by capture day, then id
Photos of an auto date upload SHALL be processed in chronological order of their capture day and by id within a day (the capture day carries no time). Day, month and year boundaries are exact.

#### Scenario: Boundaries
- **WHEN** photos from 2026-02-01, 2025-12-31, 2026-01-31 and 2026-01-01 are selected in that order
- **THEN** they are processed in the order 2025-12-31, 2026-01-01, 2026-01-31, 2026-02-01, each into its own day folder

### Requirement: One upload-history entry per auto date upload
An auto date upload SHALL create one upload-history entry. Its label SHALL be the day folder if all photos share one day, otherwise `<first day folder> – <last day folder>` in chronological order.

#### Scenario: Several days
- **WHEN** an upload covers 2026-04-30 and 2026-05-01
- **THEN** one history entry labelled `2026/04/30 – 2026/05/01` is recorded

#### Scenario: Same day
- **WHEN** all selected photos were taken on 2026-05-14
- **THEN** exactly one upload is queued and its history label is `2026/05/14`

#### Scenario: Unknown-date photos
- **WHEN** an upload covers a photo without capture date and photos from 2026-05-01
- **THEN** one history entry labelled `1970/01/01 – 2026/05/01` is recorded

#### Scenario: Label is bounded
- **WHEN** a queue carries a label longer than 200 characters or a label that is not a string
- **THEN** the label is recorded truncated to 200 characters, or as empty, respectively

### Requirement: Folders are created once per upload, level by level
Before the first photo is uploaded, every distinct target folder of the upload SHALL be created level by level (base folder levels, year, month, day) below the configured WebDAV URL; each folder level SHALL be requested at most once per upload and an already existing folder is not an error. An empty base folder means the paths start directly below the configured URL. Existing remote folders in other formats (e.g. `YYYY/MM-Mon`) are not touched or migrated.

#### Scenario: Nested folders are created
- **WHEN** base folder is `Photos/Krohn` and the upload targets `2026/04/30` and `2026/05/01`, none of which exist
- **THEN** MKCOL is issued once each, in order, for `Photos`, `Photos/Krohn`, `Photos/Krohn/2026`, `Photos/Krohn/2026/04`, `Photos/Krohn/2026/04/30`, `Photos/Krohn/2026/05`, `Photos/Krohn/2026/05/01` (relative to the configured server URL path) before the first file is uploaded

#### Scenario: Server URL with a path
- **WHEN** the configured URL is `https://host/remote.php/dav/files/u/`
- **THEN** the folders are created below `/remote.php/dav/files/u/`

### Requirement: A folder-creation failure fails the whole upload
If any target folder of an upload cannot be created, the upload SHALL fail as a whole before any photo is uploaded, with the existing folder-creation failure notification and no retry action.

#### Scenario: One folder cannot be created
- **WHEN** creating `2026/05/01` fails with HTTP 403
- **THEN** no photo is uploaded and the "folder not found or no permission" failure notification is shown

### Requirement: Upload queues stay backward compatible
A queue SHALL either name one folder for all its photos (the format of occasion uploads, the share target and every queue or retry file persisted before this change) or name a folder per photo; the format SHALL be decided from the file's content. Single-folder queues and retry files, including old `YYYY/MM-Mon` folders, SHALL upload and retry exactly as before. Which photos are marked as uploaded is unchanged.

#### Scenario: Old single-folder queue
- **WHEN** a queue persisted before this change has the folder `2026/05-May`
- **THEN** all its photos are uploaded into `2026/05-May`

### Requirement: Per-photo folders are validated
If a queue has a `label` or any photo with its own folder, it is a per-photo queue: every photo SHALL have a non-blank folder that is a JSON string, and the queue SHALL NOT also name a common folder; anything else (missing, non-string, mixed) makes the queue malformed: the upload fails, the file is deleted and nothing is uploaded. Values of other JSON types SHALL NOT be coerced to strings.

#### Scenario: Missing folder
- **WHEN** a queue entry has no folder and the queue names no common folder
- **THEN** the upload fails, the queue file is deleted and nothing is uploaded

#### Scenario: Non-string or mixed folders
- **WHEN** a photo's folder is null, a number, an array or an object, or only some photos have a folder, or a common folder is named next to per-photo folders
- **THEN** the upload fails, the queue file is deleted and nothing is uploaded

### Requirement: Per-photo folder paths are safe
Every per-photo folder SHALL be a valid folder path (no blank, `.` or `..` segment, no `\` or NUL); an invalid folder SHALL fail the upload with the "invalid folder name" explanation without any request, even if the invalid entry is the last one.

#### Scenario: Tampered per-photo folder
- **WHEN** one photo of a queue has the folder `2026/../x`
- **THEN** the upload fails with the "invalid folder name" explanation and no request is sent, even if that photo is the last one

### Requirement: Photos without a capture date
Photos without a known capture date SHALL be uploaded to `1970/01/01`; photos before 1970 keep their real date.

#### Scenario: Unknown date
- **WHEN** a photo has no capture date
- **THEN** it is uploaded to `1970/01/01`

### Requirement: Disabled mode keeps the occasion dialog
When auto date folders are off, tapping upload SHALL show the occasion dialog and confirming it SHALL enqueue exactly one upload into `<baseFolder>/<occasion name>/`, unchanged by this feature.

#### Scenario: Mode off
- **WHEN** the setting is off and the user taps upload
- **THEN** the occasion dialog is shown and no preview is shown
