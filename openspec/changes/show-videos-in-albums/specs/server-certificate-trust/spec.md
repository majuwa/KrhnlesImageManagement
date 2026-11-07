## MODIFIED Requirements

### Requirement: All server connections apply the same trust rules
The WebDAV operations (listing, folder creation, upload incl. chunked upload, chunk assembly and abort, rename, deletion, size checks, verification downloads), album thumbnails and previews, album video streaming, the image loader, the upload worker and the uploaded-photo removal check SHALL all apply the same trust rules. The Nextcloud login flow SHALL apply them only if its library accepts a client provided by the app; otherwise it SHALL rely on the system trust store only and SHALL never use a pin.

#### Scenario: Background upload to a pinned server
- **WHEN** an upload runs in the background against a configured server whose certificate is pinned
- **THEN** the upload connects and transfers the files

#### Scenario: Thumbnails after trusting
- **WHEN** the user trusts the server certificate and then opens an album
- **THEN** the album list and its thumbnails load from the pinned server

#### Scenario: Video from a pinned server
- **WHEN** the user plays a video of an album on a server whose certificate is pinned
- **THEN** the video streams from the pinned server, and the same video fails with a certificate problem once the pin no longer applies (removed, or for another port)
