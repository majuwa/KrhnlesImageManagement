# Spec Delta

## MODIFIED Requirements

### Requirement: Every upload is verified when a strategy is available
Every upload of a photo or video from the device SHALL be verified with the strategies below. Verification SHALL NOT read the local file a second time: the SHA-256 of the local content and its byte count SHALL be computed from the same bytes that are sent to the server. For a resumed chunked upload, the bytes confirmed by an earlier attempt SHALL be read once more on the device, not sent again, and included in the hash. They SHALL match the SHA-256 saved when they were sent, otherwise the transfer starts again.

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
For a server URL that contains `/remote.php/`, the request that writes the file SHALL request a server-side SHA-256 (`X-Hash: sha256`). That request is the `PUT`, or the final `MOVE` for a chunked upload. Chunk `PUT`s SHALL NOT carry the header. If the answer carries `X-Hash-SHA256`, the upload SHALL be verified when it equals the local hash (case-insensitive hex) and SHALL be a mismatch otherwise. No further request SHALL be sent. Servers whose URL does not contain `/remote.php/` SHALL NOT receive the header.

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
