## MODIFIED Requirements

### Requirement: Staged photos are cleaned up
Staged files SHALL be deleted only when their queue file is deleted, except files referenced by a newly written retry file; one helper SHALL be used at every queue-file deletion. When a cancelled upload's queue becomes its retry file, the staged files of its items that were uploaded SHALL be deleted at once and those of the items not uploaded SHALL be kept with the retry file.

#### Scenario: Staged copy kept until the queue is final
- **WHEN** a staged photo uploads successfully
- **THEN** its staged file is not deleted until the queue file is deleted

#### Scenario: Cancelled share upload
- **WHEN** an upload of 5 shared photos is cancelled after 2 of them were uploaded
- **THEN** the staged files of those 2 are deleted and the other 3 stay and are uploaded from their staged copies when the user taps "Retry Failed (3)"

#### Scenario: Retry window after a cancel
- **WHEN** an upload with shared photos is cancelled
- **THEN** the 7-day retry window of its remaining staged copies starts at the cancel
