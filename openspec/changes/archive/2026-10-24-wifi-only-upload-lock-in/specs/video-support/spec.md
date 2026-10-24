## MODIFIED Requirements

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
- **WHEN** the system has refused to start or keep the upload foreground service five times for the same upload (counted in the queue file; absent = 0)
- **THEN** it stops with a failure notification and offers to retry the items not uploaded yet

#### Scenario: Wake-ups without interruption do not use the budget
- **WHEN** an upload was started any number of times without a foreground refusal (Wi-Fi-only wake-ups on a metered network, constraint stops)
- **THEN** none of these starts counts towards the limit and a later refusal still retries the upload
