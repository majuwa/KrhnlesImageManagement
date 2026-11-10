# Spec Delta

## MODIFIED Requirements

### Requirement: Every upload is verified when a strategy is available
Every upload of a photo or video from the device SHALL be verified with the strategies below. The SHA-256 and byte count of the local content SHALL be computed from the bytes sent to the server, without a second read of the file. A resumed chunked upload SHALL include the bytes confirmed earlier by reading them once more on the device (not sending them again); they SHALL match their saved SHA-256, otherwise the transfer starts again.

#### Scenario: Hash matches the sent bytes
- **WHEN** a 5 MB photo is uploaded
- **THEN** the recorded local hash and size describe exactly the 5 MB that were sent

#### Scenario: Resumed chunked upload
- **WHEN** a 300 MiB video is resumed after 100 MiB were confirmed in an earlier run
- **THEN** the local hash and size describe all 300 MiB, of which the first 100 MiB were read on the device and matched their saved hash, and only the remaining 200 MiB were sent

#### Scenario: Confirmed part differs on resume
- **WHEN** the first 100 MiB read on resume do not match their saved hash
- **THEN** no further chunk of the old transfer is sent and the video is uploaded again from the first chunk

### Requirement: Nextcloud servers verify with a server-side hash
For a server URL that contains `/remote.php/`, the request that writes the file (the `PUT`, or the final `MOVE` of a chunked upload, never a chunk `PUT`) SHALL request `X-Hash: sha256`. An `X-Hash-SHA256` in the answer SHALL verify the upload when it equals the local hash (case-insensitive hex) and SHALL be a mismatch otherwise, without a further request. Servers whose URL does not contain `/remote.php/` SHALL NOT receive the header.

#### Scenario: Server hash matches
- **WHEN** a Nextcloud server answers the upload with an `X-Hash-SHA256` equal to the local hash
- **THEN** the photo is verified without another request

#### Scenario: Server hash differs
- **WHEN** a Nextcloud server answers with an `X-Hash-SHA256` that differs from the local hash
- **THEN** the photo is a verification mismatch

#### Scenario: Plain WebDAV
- **WHEN** a photo is uploaded to a server whose URL does not contain `/remote.php/`
- **THEN** the upload request carries no hash header

#### Scenario: Chunked upload with server hash
- **WHEN** a chunked video is finalised and the `MOVE` answer carries an `X-Hash-SHA256` equal to the hash of the whole local file
- **THEN** the video is verified without another request, and none of its chunk `PUT`s carried `X-Hash`

#### Scenario: Chunked upload without server hash
- **WHEN** the `MOVE` answer of a chunked video carries no `X-Hash-SHA256`
- **THEN** the video is verified by downloading it again if that setting is on, and is otherwise uploaded but not verified
