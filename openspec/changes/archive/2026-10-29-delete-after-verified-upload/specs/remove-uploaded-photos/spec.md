# Spec Delta

## Purpose

Lets the user free space on the device by moving photos and videos to the system trash whose upload was verified and that are unchanged on the device and still present on the server, and never others.

## ADDED Requirements

### Requirement: Removal is a user-triggered menu action
The photos screen SHALL offer "Remove uploaded photos from device" in its top-bar menu. Local photos SHALL be removed only through this action, never automatically by an upload. While a removal is running the action SHALL be disabled, and a second activation SHALL be ignored.

#### Scenario: Menu action
- **WHEN** the user opens the photos screen menu
- **THEN** it contains "Remove uploaded photos from device"

#### Scenario: Uploads never remove
- **WHEN** a batch of photos is uploaded and verified
- **THEN** no local photo is removed until the user starts the action

### Requirement: Only verified, unchanged photos still on the server are removed
A photo SHALL be a removal candidate only if its upload was verified, it was uploaded to the currently configured server, its current size and modification time on the device equal the values recorded at upload, and the server still holds a file of the recorded size at the uploaded path. A photo that fails, or cannot complete, any of these checks SHALL be kept. The checks SHALL be cancellable by the user and SHALL show their progress.

#### Scenario: Verified and unchanged
- **WHEN** a photo was verified at upload, is unchanged and the server reports the same size
- **THEN** it is a candidate

#### Scenario: Unverified upload
- **WHEN** a photo was uploaded but not verified (or uploaded before this feature)
- **THEN** it is not a candidate

#### Scenario: Edited after upload
- **WHEN** a verified photo was edited on the device after the upload
- **THEN** it is kept

#### Scenario: Deleted on the server
- **WHEN** a verified photo's file was deleted on the server or the server answers with another size
- **THEN** it is kept

#### Scenario: Server unreachable
- **WHEN** the server check fails or times out
- **THEN** the photo is kept and no error text from the server or the exception is shown

#### Scenario: Other server
- **WHEN** a photo was verified on a server other than the configured one
- **THEN** it is kept

### Requirement: Trashed and pending items are never candidates
A photo that is in the system trash or still pending on the device SHALL count as missing and SHALL be kept. Photos already in the trash before the trash request SHALL NOT be requested, counted in the result or have their records removed.

#### Scenario: Already in the trash
- **WHEN** a recorded photo is already in the system trash
- **THEN** it is not requested and not counted as moved to the trash

#### Scenario: Declined with photos already in the trash
- **WHEN** the user declines the system dialog and nothing was newly moved to the trash
- **THEN** the app reports that nothing was moved, even if other candidates were already in the trash

#### Scenario: Pending item
- **WHEN** a recorded photo is pending on the device
- **THEN** it is kept

### Requirement: The confirmation is re-checked
When the user confirms, the app SHALL keep only candidates whose record is still stored unchanged and that belong to the currently configured server. When the confirmation is older than 10 minutes, the whole check including the server SHALL be repeated before anything is requested. A re-check that fails SHALL remove nothing.

#### Scenario: Record changed meanwhile
- **WHEN** a candidate's record was replaced or removed while the confirmation was open
- **THEN** that photo is not requested

#### Scenario: Server changed meanwhile
- **WHEN** the user logged out or configured another server while the confirmation was open
- **THEN** nothing is requested

#### Scenario: Old confirmation
- **WHEN** the confirmation was open for more than 10 minutes
- **THEN** the server check runs again and only photos that still pass are requested

### Requirement: An unreachable server stops the check
The check SHALL stop after 5 consecutive server failures or timeouts. Then every photo SHALL be kept and the app SHALL show a localized "server not reachable" message instead of a confirmation. A cancelled and immediately restarted check SHALL show only the state of the new check.

#### Scenario: Server down
- **WHEN** the server fails or times out for 5 photos in a row
- **THEN** the check stops, no photo is offered for removal and "Server not reachable" is shown

#### Scenario: Fewer failures
- **WHEN** the server fails for 4 photos and answers for the next one
- **THEN** the check continues

### Requirement: Removal is confirmed by the user
Before anything is removed the app SHALL ask "Move N photos to trash?" with the number of candidates, and the system's trash confirmation SHALL follow. If there is no candidate, the app SHALL explain which photos can be removed and SHALL NOT show the system confirmation. Cancelling either confirmation SHALL remove nothing further.

#### Scenario: Confirmation with count
- **WHEN** the check finds 12 candidates
- **THEN** the app asks to move 12 photos to the trash

#### Scenario: Nothing to remove
- **WHEN** the check finds no candidate
- **THEN** the app shows an explanation and no system dialog

#### Scenario: Cancelled
- **WHEN** the user cancels the in-app or the system confirmation
- **THEN** no further photo is moved to the trash

### Requirement: Removal moves photos to the system trash
Removal SHALL move photos and videos to the system trash, from which the user can restore them, and SHALL NOT delete them permanently. Only MediaStore image or video items whose uri matches the recorded media id SHALL be passed to a trash request. Each trash request SHALL contain at most 2000 items. The local checks SHALL be repeated right before the trash request.

#### Scenario: Restorable
- **WHEN** candidates are moved to the trash
- **THEN** they appear in the system trash and can be restored

#### Scenario: Inconsistent record
- **WHEN** a recorded uri is not a MediaStore image or video item, or its id does not match the uri
- **THEN** that photo is not trashed

### Requirement: Result is reported with the confirmed count
After the removal the app SHALL report the number of photos confirmed to be in the trash, never an assumed number, as localized text. Photos confirmed in the trash SHALL no longer be candidates, and the photo grid SHALL be refreshed.

#### Scenario: Removal reported
- **WHEN** 12 photos are confirmed in the trash
- **THEN** the app says that 12 photos were moved to the trash

#### Scenario: Partly confirmed
- **WHEN** 12 photos are requested and only 11 are in the trash afterwards
- **THEN** the app reports 11

### Requirement: Shared photos are never removed
Photos uploaded through the share target, and queue entries without a media id, SHALL never be candidates.

#### Scenario: Shared upload
- **WHEN** a shared photo was uploaded
- **THEN** the original in the sharing app is never touched by the action
