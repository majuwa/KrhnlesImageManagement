## ADDED Requirements

### Requirement: Video streams stay on the configured server
A video SHALL only be streamed from a URL that lies strictly below the configured base folder on the configured origin (resolved from the listing with the same href checks as photos). Any other URL SHALL fail without sending a request. Video requests SHALL carry the user's credentials only to the configured origin and SHALL NOT follow any redirect.

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
- **WHEN** the configured server uses a certificate the app trusts only through the user's explicit choice (see the self-signed certificate setting, when available)
- **THEN** video requests use exactly the same trust decision as album listing requests, never a broader one
