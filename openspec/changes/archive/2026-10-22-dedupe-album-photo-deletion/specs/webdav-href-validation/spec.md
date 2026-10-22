# Spec Delta

## Purpose

Defines how hrefs supplied by the WebDAV server (PROPFIND `d:href`) are validated before the app sends requests for them, and to which hosts the app sends the user's credentials.

## ADDED Requirements

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
