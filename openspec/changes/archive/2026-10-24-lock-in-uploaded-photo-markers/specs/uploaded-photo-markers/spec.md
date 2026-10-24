## ADDED Requirements

### Requirement: Successful uploads are recorded
After a photo or video is uploaded successfully, its media id SHALL be stored in the local uploaded-photos set. Failed uploads and shared photos (id -1) SHALL NOT be recorded. Video ids carry the video flag so that a video and an image with the same raw MediaStore id are distinct.

#### Scenario: Success is recorded
- **WHEN** an entry is uploaded successfully
- **THEN** its id is in the uploaded-photos set

#### Scenario: Failure is not recorded
- **WHEN** an entry fails to upload
- **THEN** its id is not in the uploaded-photos set

#### Scenario: Video and image with the same raw id
- **WHEN** a video with raw id 42 was uploaded and an image with raw id 42 was not
- **THEN** only the video is marked

### Requirement: Uploaded marker in the local grid
The local grid SHALL show a cloud badge on exactly the items whose id is in the uploaded-photos set. The marker SHALL be part of the item's single accessibility node state ("Selected / Not selected, already uploaded"); the badge icon itself SHALL be decorative. Uploaded items SHALL stay selectable so they can be uploaded again, for example into another album.

#### Scenario: Badge on uploaded items only
- **WHEN** the grid shows an uploaded and a not uploaded item
- **THEN** only the uploaded item has the badge and the "already uploaded" state description

#### Scenario: Re-upload allowed
- **WHEN** the user selects an uploaded item and starts an upload
- **THEN** the upload is started without a block

### Requirement: Uploaded markers persist
The uploaded-photos set SHALL persist across app restarts. Marks SHALL accumulate across uploads. Unreadable (non-numeric) stored entries SHALL be ignored, not crash the app. The data SHALL be excluded from cloud backup and device transfer, because media ids are device specific.

#### Scenario: Survives restart
- **WHEN** ids were marked and the app process is restarted
- **THEN** the same ids are marked

#### Scenario: Corrupt entry
- **WHEN** the stored set contains a non-numeric entry
- **THEN** it is skipped and the valid ids are returned

#### Scenario: Unreadable store file
- **WHEN** the stored file is corrupt
- **THEN** the set is empty and new marks can be stored afterwards

#### Scenario: Not restored on a new device
- **WHEN** the app data is restored by cloud backup or device transfer
- **THEN** the uploaded-photos data is not part of the restored data

### Requirement: Show only new photos filter
When at least one item is marked uploaded and no selection is active, the top bar SHALL offer a filter toggle. When on, items in the uploaded-photos set SHALL be hidden and date groups that become empty SHALL be removed; when off, all items SHALL be shown. The filter SHALL have no effect while nothing is marked.

#### Scenario: Filter on
- **WHEN** the user turns the filter on
- **THEN** uploaded items and empty date groups are hidden

#### Scenario: Filter off
- **WHEN** the user turns the filter off
- **THEN** all items are shown again

### Requirement: Logout resets the tracking
Logging out (clearing the server configuration) SHALL clear the uploaded-photos set.

#### Scenario: Logout
- **WHEN** photos are marked and the user logs out
- **THEN** no photo is marked afterwards
