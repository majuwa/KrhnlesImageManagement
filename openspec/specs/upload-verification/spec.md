# upload-verification Specification

## Purpose

Confirms that a file uploaded to the server is byte-identical to the local photo or video, so that corrupt uploads are retried and local copies are only ever removed when an intact copy is known to exist on the server.

## Requirements

### Requirement: Every upload is verified when a strategy is available
Every upload of a photo or video from the device SHALL be verified with the strategies below. Verification SHALL NOT read the local file a second time: the SHA-256 of the local content and its byte count SHALL be computed from the same bytes that are sent to the server.

#### Scenario: Hash matches the sent bytes
- **WHEN** a 5 MB photo is uploaded
- **THEN** the recorded local hash and size describe exactly the 5 MB that were sent

### Requirement: Nextcloud servers verify with a server-side hash
For a server URL that contains `/remote.php/`, each upload SHALL request a server-side SHA-256 (`X-Hash: sha256`). If the response carries `X-Hash-SHA256`, the upload SHALL be verified when it equals the local hash (case-insensitive hex) and SHALL be a mismatch otherwise. No further request SHALL be sent. Servers whose URL does not contain `/remote.php/` SHALL NOT receive the header.

#### Scenario: Server hash matches
- **WHEN** a Nextcloud server answers the upload with an `X-Hash-SHA256` equal to the local hash
- **THEN** the photo is verified without another request

#### Scenario: Server hash differs
- **WHEN** a Nextcloud server answers with an `X-Hash-SHA256` that differs from the local hash
- **THEN** the photo is a verification mismatch

#### Scenario: Plain WebDAV
- **WHEN** a photo is uploaded to a server whose URL does not contain `/remote.php/`
- **THEN** the upload request carries no hash header

### Requirement: Download verification setting
Settings SHALL offer "Verify by downloading again" (default off, explained as using extra data). It SHALL persist across restarts and SHALL be excluded from backup like the other upload settings.

#### Scenario: Default
- **WHEN** the app is installed fresh
- **THEN** the setting is off

### Requirement: Download verification is opt-in
When the server provides no server-side hash (no `/remote.php/` in the URL, or no `X-Hash-SHA256` in the response), the upload SHALL be verified by downloading the uploaded file and comparing its SHA-256 and size with the local values, but only if "Verify by downloading again" is on. Otherwise the upload SHALL be unverified.

#### Scenario: Plain WebDAV with download verification on
- **WHEN** a plain WebDAV server stores the photo and the download setting is on
- **THEN** the photo is downloaded again and verified if hash and size match

#### Scenario: Nextcloud without hash support falls back
- **WHEN** a Nextcloud server answers without `X-Hash-SHA256` and the download setting is on
- **THEN** the photo is verified by downloading it again

#### Scenario: Download verification off
- **WHEN** the server provides no hash and the download setting is off
- **THEN** the photo is unverified and no download is made

### Requirement: Verification downloads are bounded and safe
A verification download SHALL only request the uploaded file on the configured origin, SHALL NOT follow redirects, SHALL stop with a mismatch as soon as more bytes than the local size arrive, SHALL be cancelled with the upload work and SHALL run under the same network constraints as the upload.

#### Scenario: Server returns a longer file
- **WHEN** the downloaded body is longer than the local size
- **THEN** the download is stopped and the photo is a verification mismatch

#### Scenario: Redirect
- **WHEN** the download answers with a 3xx status
- **THEN** the photo is not verified

#### Scenario: Upload work cancelled
- **WHEN** the upload work is cancelled during a verification download
- **THEN** the download is cancelled and nothing is reported as verified

### Requirement: Mismatch and inconclusive verification count as failed
A photo whose verification shows different content, or whose verification cannot finish (network error, read timeout, non-2xx download), SHALL count as a failed photo: it SHALL NOT be marked uploaded, SHALL be offered for retry, the notification SHALL name the verification as reason, and no error text from the server or the exception SHALL be shown.

#### Scenario: Mismatch is retried
- **WHEN** one of five photos has a verification mismatch
- **THEN** the notification reports 4 succeeded and 1 failed with a retry action, and the failed photo is not marked uploaded

#### Scenario: Download times out
- **WHEN** the verification download exceeds the upload read timeout
- **THEN** the photo counts as failed and is offered for retry

### Requirement: Unverified uploads count as uploaded
A photo that was uploaded successfully but cannot be verified because no strategy is available, or because its local size and modification time are unavailable or its local size differs from the uploaded byte count, SHALL count as uploaded and be marked uploaded as before, but SHALL NOT be verified.

#### Scenario: Plain WebDAV without download verification
- **WHEN** the server provides no hash and download verification is off
- **THEN** the photo counts as uploaded but not verified

#### Scenario: Local file changed during the upload
- **WHEN** the photo's local size differs from the number of bytes that were uploaded
- **THEN** the photo counts as uploaded but not verified, and is not offered for retry

### Requirement: Verified uploads are recorded on the device
Each verified photo SHALL be recorded locally with its media id, uri, size, modification time, server URL and remote path, without credentials. The record SHALL persist across restarts, SHALL be excluded from cloud backup and device transfer, SHALL be cleared on logout, SHALL fall back to empty when the file is corrupt, and SHALL keep at most 20,000 photos (the oldest dropped). A photo whose later upload fails verification SHALL lose its record.

#### Scenario: Recorded
- **WHEN** a photo is verified
- **THEN** a record for its media id exists

#### Scenario: Logout
- **WHEN** the user logs out
- **THEN** no record is left

#### Scenario: Corrupt file
- **WHEN** the record file cannot be read
- **THEN** the app treats it as empty and does not crash

### Requirement: The media location permission is requested
The app SHALL request the media location permission together with the media permissions; it SHALL NOT be required for media access. Users with media access but without it SHALL be asked once automatically, never before the stored flag is read and never with partial access. A denial SHALL NOT show a banner.

#### Scenario: Upgrade user asked once
- **WHEN** a user with full media access but without the media location permission opens the photos screen after the update
- **THEN** the app asks for it once and never again automatically

#### Scenario: Partial access
- **WHEN** the user granted only selected photos and videos
- **THEN** the app never asks for the media location on its own

### Requirement: Only originals are verified
When the media location permission is granted, a photo SHALL be read as the original (location not redacted). A photo that cannot be read as the original, because the permission is missing or the original cannot be opened, SHALL still be uploaded but SHALL NOT be verified or recorded.

#### Scenario: Original with location permission
- **WHEN** the media location permission is granted and a photo is uploaded and verified
- **THEN** the original bytes (with their location) are uploaded and the photo is recorded

#### Scenario: Without location permission
- **WHEN** the media location permission is not granted
- **THEN** the photo is uploaded and counts as uploaded, but is not verified and no record is written

#### Scenario: Original cannot be opened
- **WHEN** opening the original fails
- **THEN** the photo is uploaded from the normal stream, counts as uploaded and no record is written

### Requirement: Records are bound to the remote path
A record SHALL describe exactly the file at its server URL, folder and file name. Every upload attempt to a path, also of a shared photo and also a failed one, SHALL remove the records of other photos at that path; folder and file name SHALL be compared case-insensitively.

#### Scenario: Same name in the same folder
- **WHEN** photo A was verified at `Trip/a.jpg` and photo B is later uploaded as `Trip/a.jpg` (or `Trip/A.JPG`)
- **THEN** A's record is removed

#### Scenario: Failed upload to the same path
- **WHEN** an upload of another photo to A's path fails
- **THEN** A's record is removed

### Requirement: Records are bound to the account
No record SHALL be written when the configured server URL or username changed during the upload or the configuration is no longer valid. Saving credentials with another server URL or username SHALL remove every record. Logout SHALL remove them first, and a failure to clear one store SHALL NOT skip the others. A record SHALL NOT contain the username.

#### Scenario: Account changed during the upload
- **WHEN** the user logs out or changes the server or the account while a photo is being uploaded
- **THEN** no record is written for that photo

#### Scenario: Other account saved
- **WHEN** the user saves credentials with another server URL or username
- **THEN** no record is left

### Requirement: Shared photos are not verified
Photos uploaded from the share target (staged copies without a media id) SHALL NOT be verified or recorded.

#### Scenario: Shared photo
- **WHEN** a shared photo is uploaded
- **THEN** it counts as uploaded, is not verified, no hash header or verification download is sent for it and no record is written
