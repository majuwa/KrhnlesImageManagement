# Spec Delta

## MODIFIED Requirements

### Requirement: Retry re-queues only failed photos into the original folder
Activating the retry action SHALL dismiss the notification and start one new background upload containing exactly the photos that failed, each targeting the same remote folder it targeted in the original upload. At most one retry SHALL run per retry file at a time; activating the action again before it has finished SHALL do nothing. Photos that already succeeded SHALL NOT be uploaded again. Only photos that succeed in the retry are newly marked as uploaded.

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
Each batch SHALL have its own result notification and retry action, told apart by a notification tag. The action SHALL re-queue only the failed photos of its batch and dismiss exactly that notification. A batch is one queued background upload; an auto date upload of a selection is one batch even if it spans several day folders.

#### Scenario: Two batches fail partially
- **WHEN** two uploads (for example two selections started one after the other) each finish with failed photos
- **THEN** two notifications are shown and each retry action re-queues only its own batch's failed photos

#### Scenario: Auto date selection over several days
- **WHEN** an auto date upload spanning three day folders finishes with failed photos on two of the days
- **THEN** one result notification with one retry action is shown for the whole selection

## ADDED Requirements

### Requirement: Retry folders are never re-derived
The remote folders of a retry SHALL be the ones stored with the queue and SHALL never be re-derived: an upload with one folder for all photos retries into that folder, an auto date upload with a folder per photo retries each failed photo into its own day folder, and queues persisted with an older folder format keep their folder.

#### Scenario: One folder for the whole upload
- **WHEN** an upload with the single folder `Summer 2026` for all photos had failed photos, and auto date folders were switched on before the retry
- **THEN** the retry still targets `Summer 2026`

### Requirement: Result notifications never reuse the progress notification id
The progress notification SHALL keep its own id. Success, partial, failure and folder-creation-failure notifications SHALL use an id that differs from it.

#### Scenario: Result after progress
- **WHEN** an upload finishes and posts its result notification
- **THEN** the result notification uses a different id than the progress notification, so the result never replaces or is replaced by a progress notification
