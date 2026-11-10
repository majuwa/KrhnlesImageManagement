# upload-history Specification

## Purpose
Gives users a local, persistent log of past upload runs (folder label, time, success and failure counts) so they can confirm what was backed up and whether it succeeded, without leaking folder names into cloud backups.

## Requirements

### Requirement: Upload runs are recorded
Each finished upload run, including a retry run and a run cancelled by the user, SHALL add exactly one history entry with the folder label, the completion time, the photo count and the failed count. A cancelled run SHALL be marked as cancelled and its failed count SHALL be the number of items not uploaded. A system stop that is continued later SHALL NOT add an entry. A failure to persist an entry SHALL NOT fail the upload.

#### Scenario: Partial failure
- **WHEN** a run uploads 5 photos and 1 fails
- **THEN** one entry with 5 photos and 1 failed is stored

#### Scenario: Retry run
- **WHEN** a retry of the failed photos finishes
- **THEN** a second entry covering only the retried photos is stored

#### Scenario: Persist failure
- **WHEN** storing the entry throws
- **THEN** the upload result and notification are unchanged

#### Scenario: Cancelled run
- **WHEN** the user cancels a run of 10 photos after 4 were uploaded
- **THEN** exactly one entry with 10 photos, 6 not uploaded and the cancelled mark is stored

#### Scenario: System stop
- **WHEN** the system stops a run that is continued later
- **THEN** no entry is stored for the stop

#### Scenario: Entries of older versions
- **WHEN** stored entries have no cancelled mark
- **THEN** they are listed as not cancelled

### Requirement: History persists and is newest first
History SHALL survive app restarts and SHALL be listed newest first. Unreadable or inconsistent stored entries SHALL be ignored, not crash the app.

#### Scenario: Survives restart
- **WHEN** entries were stored and the app is restarted
- **THEN** the same entries are listed

#### Scenario: Order
- **WHEN** entries from three different times are stored
- **THEN** the list shows the latest first

#### Scenario: Corrupt data
- **WHEN** the stored value is not valid JSON
- **THEN** the list is empty and the app does not crash

#### Scenario: Corrupt file
- **WHEN** the history file itself is corrupt
- **THEN** the list is empty and the app does not crash

#### Scenario: Persisted duplicate ids
- **WHEN** stored data contains two entries with the same id
- **THEN** only the newest of them is listed

### Requirement: Entry display and accessibility
Each entry SHALL show the folder label, a locale-formatted date and time, and the succeeded and failed counts. A cancelled entry SHALL instead show that it was cancelled with the uploaded and not uploaded counts and SHALL NOT call the not uploaded items failed. An empty history SHALL show an empty-state message. No file name is used as a content description.

#### Scenario: Entry content
- **WHEN** an entry for "Trip" with 5 photos, 1 failed is shown
- **THEN** it shows "Trip", the formatted time and "4 succeeded · 1 failed"

#### Scenario: Cancelled entry content
- **WHEN** a cancelled entry for "Trip" with 10 photos, 6 not uploaded is shown
- **THEN** it shows "Trip", the formatted time and "Cancelled · 4 uploaded · 6 not uploaded"

#### Scenario: Empty state
- **WHEN** there are no entries
- **THEN** the empty-state text is shown and the clear-all action is hidden

### Requirement: Entries can be removed
The user SHALL be able to delete a single entry and to clear all entries after a confirmation. Deleting one entry SHALL NOT remove another, even if they were recorded in the same millisecond.

#### Scenario: Delete one
- **WHEN** the user deletes one of two entries
- **THEN** only the other remains

#### Scenario: Clear all
- **WHEN** the user confirms clear all
- **THEN** the list is empty

#### Scenario: Same-millisecond entries
- **WHEN** two entries are recorded with the same timestamp
- **THEN** both are listed and have distinct ids assigned by the store

### Requirement: History size is bounded
The store SHALL keep at most the 500 newest entries; adding beyond that SHALL drop the oldest.

#### Scenario: Cap
- **WHEN** a 501st entry is added
- **THEN** 500 entries remain and the oldest is gone

#### Scenario: Existing oversized history
- **WHEN** more than 500 entries are stored and another is added
- **THEN** 500 entries remain

### Requirement: History is excluded from backup
The history data SHALL be excluded from cloud backup and device transfer, because folder names are user-written and may be sensitive.

#### Scenario: Backup rules
- **WHEN** the backup and data-extraction rules are inspected
- **THEN** the history file is excluded in full backup, cloud backup and device transfer
