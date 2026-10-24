## MODIFIED Requirements

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

### Requirement: Result notifications are separate per batch
The progress notification SHALL keep its own id; success, partial, failure and folder-creation-failure notifications SHALL use an id that differs from it and are told apart per batch by a notification tag. A batch is one queued background upload; an auto date upload of a selection is one batch even if it spans several day folders. Each batch SHALL have its own result notification and retry action, and the action SHALL re-queue only the failed photos of its batch and dismiss exactly that notification.

#### Scenario: Two batches fail partially
- **WHEN** two uploads (for example two selections started one after the other) each finish with failed photos
- **THEN** two notifications are shown and each retry action re-queues only its own batch's failed photos

#### Scenario: Auto date selection over several days
- **WHEN** an auto date upload spanning three day folders finishes with failed photos on two of the days
- **THEN** one result notification with one retry action is shown for the whole selection

## ADDED Requirements

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
