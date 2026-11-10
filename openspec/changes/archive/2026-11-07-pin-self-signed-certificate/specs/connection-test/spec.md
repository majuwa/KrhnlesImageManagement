# Spec Delta

## MODIFIED Requirements

### Requirement: Connection test result is localized and typed
The system SHALL report the result as a typed outcome: success, not configured, untrusted certificate with reviewable certificate details, server certificate changed with the new certificate's details, other certificate/TLS problem, unknown host, server unreachable, timeout, authentication failure (HTTP 401/403), not found (HTTP 404), other HTTP error with its status code, invalid URL, or unknown error. The Settings screen SHALL show each outcome using a localized, user-friendly string resource, with success styled as positive and the other outcomes styled as errors. The screen SHALL NOT show raw exception messages.

#### Scenario: Successful connection
- **WHEN** the server check succeeds
- **THEN** the Settings screen shows the localized success message in the positive style

#### Scenario: Untrusted certificate
- **WHEN** the check fails with a TLS handshake, peer verification or certificate validation error and no certificate of the configured origin was captured
- **THEN** the Settings screen shows the localized certificate message and not the exception text

#### Scenario: Untrusted certificate offered for review
- **WHEN** the check fails because the configured origin presented a certificate that is neither system-trusted nor pinned, and no pin exists for that origin
- **THEN** the result is the untrusted-certificate outcome carrying that certificate's details, and the Settings screen shows the localized untrusted-certificate message with the certificate review

#### Scenario: Server certificate changed
- **WHEN** the check fails because a pin exists for the configured origin and the server presented a different certificate that is not system-trusted
- **THEN** the result is the certificate-changed outcome carrying the new certificate's details, and the Settings screen shows the localized certificate-changed message with the certificate review

#### Scenario: Credentials rejected
- **WHEN** the server answers HTTP 401 or 403
- **THEN** the Settings screen shows the localized authentication message and not the raw HTTP status text

#### Scenario: Other HTTP error
- **WHEN** the server answers another non-success status such as 503
- **THEN** the Settings screen shows the localized HTTP error message including the status code

#### Scenario: Failure without detail
- **WHEN** the check fails with an error that has no message or an unrecognised type
- **THEN** the Settings screen shows the localized generic failure message
