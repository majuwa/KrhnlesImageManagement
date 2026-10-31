## Purpose

Lets the user stop a running background upload at any time without losing what was already uploaded, and resume the remaining photos and videos later with "Retry Failed".

## ADDED Requirements

### Requirement: Running uploads offer a Cancel action
While an upload is running, its progress notification SHALL offer a "Cancel" action and the in-app upload progress banner SHALL offer a "Cancel upload" button. Activating either SHALL cancel exactly that running upload, without a confirmation dialog.

#### Scenario: Cancel from the notification
- **WHEN** an upload of 50 photos is running and the user taps "Cancel" in its progress notification
- **THEN** that upload stops and the progress notification disappears

#### Scenario: Cancel from the app
- **WHEN** the Photos tab shows the upload progress banner and the user taps "Cancel upload"
- **THEN** that upload stops and the banner disappears

#### Scenario: Two uploads
- **WHEN** two uploads exist and the user cancels the running one
- **THEN** the other upload is not cancelled

### Requirement: The Cancel action only affects running upload work
The Cancel action SHALL be delivered only to the app itself, SHALL NOT be modifiable by other apps, and SHALL cancel only background work that is an upload and is running at that moment. An invalid, unknown, finished or not-running target SHALL be ignored without crashing.

#### Scenario: Upload no longer running
- **WHEN** the Cancel action is activated after the upload has already finished or is waiting to be retried
- **THEN** nothing is cancelled and no file is changed

#### Scenario: Malformed action
- **WHEN** the cancel broadcast carries a missing or malformed work id, or the id of work that is not an upload
- **THEN** it is ignored without crashing

#### Scenario: Action cannot be altered
- **WHEN** the progress notification is built
- **THEN** its Cancel action is an immutable, explicit broadcast to a receiver that is not exported

### Requirement: Cancel stops the transfer immediately
Cancelling SHALL abort a request that is being sent at that moment and SHALL NOT start any further request for that upload. The item whose transfer was aborted SHALL count as not uploaded.

#### Scenario: Cancel during a large video
- **WHEN** the user cancels while a 2 GB video is being sent
- **THEN** sending stops and the video is one of the items not uploaded

#### Scenario: Cancel before the first item
- **WHEN** the user cancels while the upload folder is still being created
- **THEN** no photo is sent and every item is not uploaded

### Requirement: Uploaded items stay uploaded after a cancel
Items uploaded before the cancel SHALL keep their uploaded marker in the grid, their verified-upload record (if verified) and their uploaded state in the queue, and SHALL NOT be uploaded again when the remaining items are resumed. An item whose transfer finished just before the cancel SHALL be recorded as uploaded.

#### Scenario: Cancel mid-batch
- **WHEN** the user cancels an upload of 10 photos after 4 were uploaded
- **THEN** the 4 photos show the uploaded marker and the remaining 6 do not

#### Scenario: Transfer finished as the cancel arrives
- **WHEN** the server confirmed an item and the cancel arrives before its marker was saved
- **THEN** the item is still marked uploaded and is not part of the remaining items

### Requirement: Remaining items can be resumed with Retry Failed
After a cancel with at least one item not uploaded, the app SHALL keep exactly the not uploaded items (each with its own folder) for a retry and offer the existing "Retry Failed (N)" action. Resuming SHALL upload only those items, and a cancelled retry SHALL itself be resumable.

#### Scenario: Resume after cancel
- **WHEN** an upload of 10 photos was cancelled after 4 and the user taps "Retry Failed (6)"
- **THEN** exactly the 6 remaining photos are uploaded into their original folders and none of the 4 is sent again

#### Scenario: Auto date folders are kept
- **WHEN** an auto date upload over two day folders is cancelled
- **THEN** each remaining item is resumed into its own day folder

#### Scenario: Cancelled retry
- **WHEN** the user cancels a running retry after some of its items were uploaded
- **THEN** the items of that retry that are still not uploaded can be resumed again

### Requirement: Cancelled uploads are reported as cancelled
A cancelled upload SHALL post one result notification per batch, separate from the progress notification and titled "Upload cancelled", that states how many items were uploaded and how many were not, and SHALL NOT describe the not uploaded items as failed. A cancel after the last item has been processed SHALL report the batch as a finished upload.

#### Scenario: Cancelled result notification
- **WHEN** an upload of 10 photos is cancelled after 4 were uploaded
- **THEN** a notification "Upload cancelled" states that 4 were uploaded and 6 were not, with "Retry Failed (6)"

#### Scenario: Nothing left
- **WHEN** the cancel arrives after every item has been processed
- **THEN** the usual completion notification is shown and no retry is offered for cancelled items

### Requirement: A user cancel does not use the interruption budget
A user cancel SHALL NOT count as an interruption, and the remaining items SHALL start a resumed upload with an interruption count of zero.

#### Scenario: Cancel after earlier interruptions
- **WHEN** an upload that the system already interrupted four times is cancelled by the user and then resumed
- **THEN** the resumed upload starts with an interruption count of zero

### Requirement: System stops are not treated as cancels
An upload stopped by the system (network or other constraints, foreground-service time limit, preemption, quota or an unknown reason) SHALL NOT be reported as cancelled: its progress SHALL be kept and it SHALL continue automatically with the items not uploaded yet.

#### Scenario: Wi-Fi lost with Wi-Fi only
- **WHEN** Wi-Fi only is on and the connection becomes metered during an upload
- **THEN** no cancelled notification or history entry appears and the upload continues later without re-sending finished items

#### Scenario: Foreground-service time limit
- **WHEN** the system stops the upload because its foreground-service time limit is reached
- **THEN** it is not reported as cancelled and is continued later

### Requirement: Cancelled uploads are recovered
If a cancelled upload could not be finalized (the cancel reached the upload between two runs, or the app was closed before it finished), the next app start SHALL finalize it like a cancelled upload, exactly once. A queue whose upload state is unknown SHALL be left untouched.

#### Scenario: App killed right after cancel
- **WHEN** the app process dies after the user cancelled an upload but before the cancelled notification was posted
- **THEN** on the next app start the remaining items become resumable and the cancelled notification is posted once

#### Scenario: Upload state unknown
- **WHEN** the state of the upload of a queue cannot be determined at app start
- **THEN** the queue is neither changed nor deleted
