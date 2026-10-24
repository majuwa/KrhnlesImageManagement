# failed-photo-retry Specification

## Purpose
Lets the user retry only the photos that failed in a partially successful upload, directly from the upload completion notification, into the same remote folder.

## Requirements

### Requirement: Partial failure offers a retry of the failed photos
When an upload finishes with at least one failed photo, its completion notification SHALL contain a "Retry Failed (N)" action, where N is the number of failed photos. A fully successful upload SHALL NOT show a retry action.

#### Scenario: Partial failure shows retry action
- **WHEN** an upload of 20 photos finishes with 3 failures
- **THEN** the completion notification has an action labelled "Retry Failed (3)"

#### Scenario: Full success shows no retry action
- **WHEN** all photos of an upload succeed
- **THEN** the completion notification has no retry action

### Requirement: Retry re-queues only failed photos into the original folder
Activating the retry action SHALL dismiss the notification and start one new background upload (at most one retry runs per retry file at a time; activating the action again before it finished does nothing) containing exactly the photos that failed, each targeting the same remote folder it targeted in the original upload. Photos that already succeeded SHALL NOT be uploaded again. Only photos that succeed in the retry are newly marked as uploaded. The remote folders are the ones stored with the queue and are never re-derived: an upload with one folder for all photos retries into that folder, an auto date upload with a folder per photo retries each failed photo into its own day folder, and queues persisted with an older folder format keep their folder.

#### Scenario: Only failed photos are retried
- **WHEN** the user activates "Retry Failed (3)" after a 20-photo upload
- **THEN** a new upload with exactly those 3 photos is queued into the original folder

#### Scenario: Auto date folder is preserved
- **WHEN** an auto date upload had failed photos in `2026/05/13` and `2026/05/14`
- **THEN** one retry upload re-queues each failed photo into its own folder, `2026/05/13` or `2026/05/14`, not a folder derived from today's date

#### Scenario: Folder stored in an older format is preserved
- **WHEN** a queue persisted before the per-day format change has the folder `2026/05-May`
- **THEN** its retry targets `2026/05-May`

#### Scenario: Repeated activation
- **WHEN** the user activates the retry action twice before the retry has finished
- **THEN** only one retry upload exists for that retry file

#### Scenario: Missing retry file
- **WHEN** the retry action is activated and its queue file no longer exists
- **THEN** the notification is dismissed and nothing is enqueued

### Requirement: Retry honours the Wi-Fi-only setting
Both the original upload and a retry SHALL require an unmetered network if and only if the Wi-Fi-only setting is enabled when they are started. If the setting cannot be read within a short time limit, an unmetered network SHALL be required. A queued upload that starts on a metered network after Wi-Fi-only was enabled SHALL NOT upload but be retried later. Enqueueing SHALL NOT depend on the activity staying alive.

#### Scenario: Wi-Fi-only enabled
- **WHEN** Wi-Fi-only is enabled and the user starts a retry
- **THEN** the retry waits for an unmetered network

#### Scenario: Wi-Fi-only disabled
- **WHEN** Wi-Fi-only is disabled and the user starts a retry
- **THEN** the retry runs on any connected network

#### Scenario: Enabled after enqueue
- **WHEN** Wi-Fi-only is switched on after an upload was queued and the upload starts on a metered network
- **THEN** nothing is uploaded and the work is retried later with the queue file kept

#### Scenario: Activity destroyed while enqueueing
- **WHEN** the activity is destroyed (for example by rotation) while the setting is being read
- **THEN** the upload is still queued exactly once

#### Scenario: Setting unreadable
- **WHEN** reading the setting times out or fails
- **THEN** an unmetered network is required

### Requirement: Unreadable photos count as failed
A photo whose content cannot be opened (for example a revoked share permission or a deleted file) SHALL be counted as a failed photo and recorded for retry; it SHALL NOT stop the upload of the remaining photos or crash the upload, and no exception text SHALL be shown to the user. Progress SHALL advance for failed photos too.

#### Scenario: One unreadable photo
- **WHEN** one of five photos cannot be opened
- **THEN** the other four are uploaded and the completion notification reports 4 succeeded and 1 failed with a retry action

### Requirement: Result notifications are separate per batch
The progress notification SHALL keep its own id; success, partial, failure and folder-creation-failure notifications SHALL use an id that differs from it and are told apart per batch by a notification tag. A batch is one queued background upload; an auto date upload of a selection is one batch even if it spans several day folders. Each batch SHALL have its own result notification and retry action, and the action SHALL re-queue only the failed photos of its batch and dismiss exactly that notification.

#### Scenario: Two batches fail partially
- **WHEN** two uploads (for example two selections started one after the other) each finish with failed photos
- **THEN** two notifications are shown and each retry action re-queues only its own batch's failed photos

#### Scenario: Auto date selection over several days
- **WHEN** an auto date upload spanning three day folders finishes with failed photos on two of the days
- **THEN** one result notification with one retry action is shown for the whole selection

### Requirement: Retry queue files are validated
A retry queue file SHALL only be used if its name matches `retry_queue_<hex and dashes>.json` and it lies directly in the app files directory. A missing or malformed queue file SHALL end the upload with a failure result without crashing; a malformed file SHALL be deleted.

#### Scenario: Path outside or below the files directory
- **WHEN** the retry action carries a path outside the files directory, in a subdirectory or with a different name pattern
- **THEN** nothing is enqueued

#### Scenario: Queue file malformed
- **WHEN** the worker runs with an unparseable queue file
- **THEN** the work fails, the file is deleted and nothing is uploaded

### Requirement: Folder-creation failure is explained in plain language
If the remote folder cannot be created, the failure notification SHALL show a localized, upload-specific message (invalid folder name, folder not found or no permission, server not configured, certificate, server not found, unreachable, timeout, other) and never raw exception text or album wording. The occasion dialogs SHALL reject names that are `.`, `..` or contain `/` or `\` with an inline error.

#### Scenario: Server rejects folder creation
- **WHEN** creating the folder fails with HTTP 403
- **THEN** the notification shows the "folder not found or no permission" explanation

#### Scenario: Invalid occasion name
- **WHEN** the occasion name is `..`
- **THEN** the dialog shows an inline error and cannot be confirmed, and a queued folder `..` fails with the "invalid folder name" explanation without a request

### Requirement: Only foreign content URIs are uploaded
The share target and the upload worker SHALL only accept `content://` URIs whose authority is not the app's own. Other URIs (for example `file://`) SHALL be ignored when sharing and counted as failed photos in the worker.

#### Scenario: file URI shared
- **WHEN** a share intent carries `file:///data/data/<package>/files/x`
- **THEN** the photo is not offered for upload

### Requirement: Queue and retry files are excluded from backup
The system SHALL write new upload queue and retry queue files to `noBackupFilesDir`, because they contain per-photo capture days. Queue-file validation SHALL accept files directly in `noBackupFilesDir` and, for files and notifications created by earlier versions, directly in `filesDir`, and SHALL reject every other parent directory.

#### Scenario: New files are written outside backup
- **WHEN** an upload is enqueued or a partial upload writes a retry file
- **THEN** the file is created in `noBackupFilesDir`

#### Scenario: Legacy file still runs
- **WHEN** the worker or retry receiver gets a valid queue file name located in `filesDir`
- **THEN** it is processed as before

#### Scenario: Other parent rejected
- **WHEN** the queue file lies in any other directory
- **THEN** it is ignored
