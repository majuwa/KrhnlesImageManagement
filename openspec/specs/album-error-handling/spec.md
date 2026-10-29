# album-error-handling Specification

## Purpose
Keeps the album list, album details and the duplicate and blur reviews usable when loading fails: every failure is shown as a localized, plain-language message, a missing or unusable configuration never crashes these screens, and an open album stops using the previous server and credentials after a logout or account change.

## Requirements

### Requirement: Album errors are shown as user-friendly messages
When the album list, an album's photos or a duplicate / blur scan cannot be loaded, the app SHALL show a localized, plain-language message for the cause (not logged in, invalid server address, untrusted certificate, server not found, server unreachable, timeout, access denied, not found, other HTTP status, unknown) and SHALL NOT show raw exception text. Where retrying can help, the screen SHALL offer a way to retry.

#### Scenario: Server rejects the credentials
- **WHEN** loading an album fails with HTTP 401 or 403
- **THEN** the album screen explains that access was denied and offers Retry

#### Scenario: Unexpected failure
- **WHEN** loading fails for a reason without a specific message
- **THEN** a generic localized message is shown, without exception text or server details

### Requirement: Missing or invalid configuration never crashes album screens
Opening the album list, an album, the duplicate review or the blur review with no stored credentials or an unusable server address SHALL NOT terminate the app; the screen SHALL show the corresponding message instead.

#### Scenario: Album restored after logout
- **WHEN** an album screen is restored or opened while no credentials are stored
- **THEN** it shows that the user is not logged in, and no request is sent

#### Scenario: Invalid server address
- **WHEN** the stored server address cannot be parsed and the user opens the album list, an album or a review
- **THEN** the screen shows that the server address is invalid instead of crashing

### Requirement: An open album stops using outdated credentials
When the user logs out or changes the server or account while an album (or a screen opened from it) is open, the app SHALL stop using the previous server and credentials for that album: a running load or deletion is stopped, the album's photos are no longer shown and no further request for them is sent with the old credentials.

#### Scenario: Logout while an album is open
- **WHEN** the user logs out while an album is open
- **THEN** the album shows that the user is not logged in, its photos are removed, and nothing can be listed or deleted on the old server

#### Scenario: Server change while deleting
- **WHEN** the server or account changes while photos of the open album are being deleted
- **THEN** no further photo is deleted on the old server, the busy state ends and the album shows that the server settings changed

#### Scenario: Logout during a scan or review
- **WHEN** the user logs out or changes the server while the duplicate or blur review scans or shows results
- **THEN** the scan stops without further requests to the old server, the results are discarded and the review shows that the user is not logged in / the server settings changed

#### Scenario: Deleting review results after a server change
- **WHEN** photos found for the previous server are submitted for deletion after the server or account changed
- **THEN** none of them is deleted, and they are reported as failed

#### Scenario: Unloaded album
- **WHEN** a review is opened while its album is not loaded (e.g. restored after process death) or failed to load
- **THEN** the review loads the album / shows the album error and does not report that nothing was found

#### Scenario: Saving the same settings again
- **WHEN** the same server and credentials are saved again
- **THEN** the open album stays as it is
