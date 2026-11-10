## MODIFIED Requirements

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
