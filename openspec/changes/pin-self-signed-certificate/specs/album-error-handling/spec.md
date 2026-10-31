# Spec Delta

## MODIFIED Requirements

### Requirement: Album errors are shown as user-friendly messages
When the album list, an album's photos or a duplicate / blur scan cannot be loaded, the app SHALL show a localized, plain-language message for the cause (not logged in, invalid server address, untrusted certificate, server certificate changed, server not found, server unreachable, timeout, access denied, not found, other HTTP status, unknown) and SHALL NOT show raw exception text. Where retrying can help, the screen SHALL offer a way to retry.

#### Scenario: Server rejects the credentials
- **WHEN** loading an album fails with HTTP 401 or 403
- **THEN** the album screen explains that access was denied and offers Retry

#### Scenario: Unexpected failure
- **WHEN** loading fails for a reason without a specific message
- **THEN** a generic localized message is shown, without exception text or server details

#### Scenario: Untrusted certificate
- **WHEN** loading fails because the server certificate is neither system-trusted nor pinned
- **THEN** the screen shows the localized untrusted-certificate message, which tells the user to review the certificate with the connection test in Settings, and offers Retry

#### Scenario: Server certificate changed
- **WHEN** loading fails because a certificate is pinned for the configured server and the server presented a different, untrusted certificate
- **THEN** the screen shows the localized "server certificate changed" message, distinct from the untrusted-certificate message, and offers Retry
