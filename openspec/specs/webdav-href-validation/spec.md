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
