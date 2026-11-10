# webdav-href-validation Specification

## Purpose

Defines how hrefs supplied by the WebDAV server (PROPFIND `d:href`) are validated before the app sends requests for them, and to which hosts the app sends the user's credentials.

## Requirements

### Requirement: Server-supplied hrefs stay on the configured server
An album or photo href from the server SHALL only be used if, after resolving it against the configured server URL, it has the same scheme, host and port as the configured server URL. Otherwise no request SHALL be sent for it.

#### Scenario: Userinfo-like href
- **WHEN** the server returns an href such as `@attacker.example/x.jpg`
- **THEN** the app never sends a request to `attacker.example` and never sends the credentials there

#### Scenario: Cross-origin href
- **WHEN** the server returns an absolute or scheme-relative href pointing to another host or port
- **THEN** the entry is skipped in listings and any deletion of it fails without a request

#### Scenario: Real Nextcloud href
- **WHEN** the server returns an absolute, percent-encoded path such as `/remote.php/dav/files/user/Photos/Summer%202025/IMG%20%C3%A4.jpg`, or an absolute URL on the configured origin
- **THEN** the album or photo is listed, displayed and can be deleted as before

### Requirement: Server-supplied hrefs stay inside the configured base folder
An album or photo href SHALL only be used if its path lies strictly below the configured base folder; a photo returned by an album listing SHALL lie below that album. Hrefs containing `.` or `..` segments (also percent-encoded), an encoded `/` or `\` inside a segment, a backslash, a query or a fragment SHALL be refused. Refused hrefs SHALL be skipped in listings and SHALL make deletion, renaming and listing of that href fail without sending a request.

#### Scenario: Path traversal
- **WHEN** an href contains `/../`, `%2e%2e` or an encoded slash
- **THEN** it is refused, even if the normalized path would lie inside the base folder

#### Scenario: Path outside the base folder
- **WHEN** an href points to another folder of the user, another user's files or the base folder itself
- **THEN** it is refused and no request is sent

### Requirement: Credentials are only sent to the configured origin
The app SHALL attach the user's credentials (`Authorization` header) only to requests whose scheme, host and port equal the configured server URL — for WebDAV album requests and for image loading.

#### Scenario: Request to another origin
- **WHEN** the app sends a request to a URL on another host or port
- **THEN** the request carries no `Authorization` header

#### Scenario: Request to the configured server
- **WHEN** the app sends a request to the configured server
- **THEN** the request carries the user's credentials

### Requirement: Scan thumbnail downloads stay on the configured server
A thumbnail downloaded for the duplicate or blur scan SHALL only be requested if its URL lies strictly below the configured base folder, or is the Nextcloud Memories preview of a single file id below the configured Nextcloud base on the configured origin. Any other URL SHALL fail without sending a request.

#### Scenario: Thumbnail URL on another origin
- **WHEN** a scan is asked to download a thumbnail from another host or port
- **THEN** no request is sent and the photo is skipped

#### Scenario: Thumbnail URL outside the base folder
- **WHEN** a thumbnail URL points to another folder, another user's files, a traversal path or a Nextcloud endpoint other than the Memories image preview
- **THEN** no request is sent

#### Scenario: Unsafe file id
- **WHEN** a PROPFIND entry carries an `oc:fileid` that is not purely alphanumeric
- **THEN** the id is ignored and the photo uses its WebDAV URL, never a Memories preview URL built from that id

#### Scenario: Regular thumbnails
- **WHEN** the thumbnail is a photo below the base folder or its Memories preview
- **THEN** it is downloaded as before

### Requirement: Video streams stay on the configured server
A video SHALL only be streamed from a URL that lies strictly below the configured base folder on the configured origin (resolved from the listing with the same href checks as photos). Any other URL, and any request other than a plain read of the video, SHALL fail without sending a request. Video requests SHALL carry the user's credentials only to the configured origin, SHALL NOT follow any redirect, and the body of an error response SHALL NOT be read into memory.

#### Scenario: Video below the base folder
- **WHEN** the user plays a listed video of an album
- **THEN** the request goes to that video's address on the configured server with the user's credentials and may ask for a byte range

#### Scenario: Unsafe video address
- **WHEN** a video's address would resolve outside the configured base folder, to another host or port, or contains traversal or encoded separators
- **THEN** playback fails with the generic message and no request is sent

#### Scenario: Server redirects the video request
- **WHEN** the server answers a video request with any redirect (301, 302, 303, 307 or 308), to the same or another origin
- **THEN** the redirect is not followed, no request is sent to the redirect target and the page shows the redirect failure message

#### Scenario: Stream after configuration change
- **WHEN** the server or account changes, or the user logs out, while a video stream of the old configuration is still open or about to reconnect
- **THEN** no further request is sent with the old configuration's credentials

#### Scenario: Server certificate trust
- **WHEN** the configured server uses a certificate the app trusts only through the user's explicit choice (the pinned self-signed certificate)
- **THEN** video requests use exactly the same trust decision as album listing requests, never a broader one

#### Scenario: Error response with a huge body
- **WHEN** the server answers a video request with an error status and an arbitrarily large body
- **THEN** the body is discarded without being read, memory use stays bounded and the page shows the message for that status

### Requirement: Photo downloads stay inside their album
A photo saved to the device SHALL only be requested if its album href resolves strictly below the configured base folder and the photo href resolves strictly below that album, on the configured origin, under the same rules as other server-supplied hrefs. A refused href SHALL fail that photo without sending a request; the decision SHALL be repeated when the queued download runs, against the configuration current at that time.

#### Scenario: Traversal in a queued href
- **WHEN** a queued photo href contains `..`, `%2e%2e` or an encoded slash
- **THEN** no request is sent for it and it counts as failed

#### Scenario: Photo outside its album
- **WHEN** a queued photo href points to another album, the base folder itself or another user's files
- **THEN** no request is sent for it and it counts as failed

#### Scenario: Other origin
- **WHEN** a queued photo href is an absolute URL on another host or port
- **THEN** no request is sent and no credentials leave the device

#### Scenario: Base folder changed after queueing
- **WHEN** the base folder was changed in Settings after the download was queued and the album is no longer below it
- **THEN** no photo of that batch is requested
