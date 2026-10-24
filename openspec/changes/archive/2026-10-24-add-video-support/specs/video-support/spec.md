## ADDED Requirements

### Requirement: Videos appear in the local grid
The local grid SHALL list the device's videos together with its photos, grouped by capture day and ordered by capture time, newest first. A video SHALL be recognised as a video because it comes from the device's video collection, even when its media type is missing. A video without a capture date SHALL be grouped with the other undated media under "Unknown date".

#### Scenario: Photos and videos on one day
- **WHEN** the device has a photo taken at 10:00 and a video taken at 11:00 on the same day
- **THEN** the grid shows the video before the photo under that day

#### Scenario: Image and video share a raw media id
- **WHEN** a photo and a video both have the media store id 42
- **THEN** both are listed as separate items and selecting one does not select the other

#### Scenario: Video without media type
- **WHEN** a video row has no media type
- **THEN** it is listed as a video and uploaded with the generic media type `video/*`

#### Scenario: Video without capture date
- **WHEN** a video has no capture date
- **THEN** it is listed under "Unknown date" like an undated photo

### Requirement: Video tiles are recognizable and accessible
Video frames SHALL be decoded only for local device media, never for content loaded from the network. A video tile SHALL show a thumbnail frame, a play indicator and, when the duration is known and greater than zero, the video duration. It SHALL remain one accessible element whose description says it is a video, its position, its duration (when known) and its capture date, never the file name.

#### Scenario: Video tile
- **WHEN** a 1 minute 5 second video is shown
- **THEN** the tile shows a play indicator and "1:05", and its description starts with "Video"

#### Scenario: Unknown duration
- **WHEN** a video has no duration or a duration of zero
- **THEN** no duration badge is shown and the description omits the duration

#### Scenario: Remote video content
- **WHEN** a server answers an image request with video content
- **THEN** it is not decoded as a video

### Requirement: Video access is requested with photo access
The app SHALL request video access together with photo access, including the partial-access permission, and photos SHALL stay usable without video access. An upgrade from a photos-only version SHALL ask for video access at most once and then offer to grant it or open the app settings. Users with partial access SHALL NOT be asked automatically. Without any media access the existing permission screen SHALL be shown.

#### Scenario: Upgrade, images granted, videos denied
- **WHEN** photo access was granted earlier and the user denies the one-time video request
- **THEN** photos are listed, no video is listed, a message with a grant action is shown and the app does not ask again on its own

#### Scenario: Banner offers to grant
- **WHEN** video access is missing
- **THEN** the banner explains that videos are not shown and offers to grant access

#### Scenario: Partial access
- **WHEN** the user selected specific photos and videos
- **THEN** exactly the selected items are listed, no permission is requested automatically and the banner "Select more photos and videos" is shown

#### Scenario: Denied without further dialog
- **WHEN** the video request was denied after the system had offered a rationale before, and the system will not show the dialog again
- **THEN** the banner action opens the app settings

#### Scenario: Dialog dismissed
- **WHEN** the first video request is dismissed without a rationale ever having been offered
- **THEN** the banner action asks for video access again instead of opening the app settings

#### Scenario: Access widened
- **WHEN** a user with photo-only or partial access grants video or full access from a request
- **THEN** the grid reloads at once and shows the newly accessible items

#### Scenario: Everything denied
- **WHEN** no media access is granted
- **THEN** the permission rationale with a grant button is shown

### Requirement: Videos are uploaded like photos
Selected videos SHALL be uploaded with the same flow as photos (occasion or auto date folders, background upload, retry) with their own media type. A video SHALL be streamed, never loaded into memory completely, and the file size SHALL be announced when the device reports it (chunked transfer otherwise). An uploaded video SHALL be marked as uploaded without marking a photo with the same raw media id, and vice versa.

#### Scenario: Mixed selection
- **WHEN** the user uploads two photos and one video into an occasion
- **THEN** one background upload with three items is queued and all three land in that folder

#### Scenario: Large video
- **WHEN** a multi-gigabyte video is uploaded
- **THEN** there is no client-side overall time limit and the file is streamed, not buffered in memory; the size is sent when known

#### Scenario: Server rejects the size
- **WHEN** the server answers a video upload with HTTP 413
- **THEN** the item fails with the message "file too large for the server"

#### Scenario: Server storage full
- **WHEN** the server answers a video upload with HTTP 507
- **THEN** the item fails with the message "server storage full"

#### Scenario: Same raw id after upload
- **WHEN** a video with media id 42 was uploaded and a photo with media id 42 exists
- **THEN** only the video shows the uploaded marker

#### Scenario: Failed video retried
- **WHEN** a video upload fails
- **THEN** the retry action re-uploads that video with its media type

#### Scenario: Server redirects the upload
- **WHEN** the server answers an upload with any redirect (301, 302, 303, 307 or 308)
- **THEN** the item fails and no follow-up request is sent

#### Scenario: Foreground service not allowed
- **WHEN** the system refuses to start or keep the upload foreground service (for example the time budget is used up) before the last item
- **THEN** the work is retried later with back-off, the queue and the items uploaded so far (with their uploaded markers) are kept, and the retried run uploads only the remaining items while its result counts the whole batch

#### Scenario: Refused after the last item
- **WHEN** the foreground service is refused only after the last item was uploaded
- **THEN** the batch completes normally

#### Scenario: Repeatedly interrupted
- **WHEN** an upload was interrupted on its fifth run
- **THEN** it stops with a failure notification and offers to retry the items not uploaded yet

### Requirement: Albums and reviews ignore videos
Album listings and the duplicate and blurry photo reviews SHALL NOT include video files. Uploaded videos therefore do not appear in the Albums screens.

#### Scenario: Album contains a video file
- **WHEN** an album folder on the server contains an mp4 file
- **THEN** it is not listed and not scanned
