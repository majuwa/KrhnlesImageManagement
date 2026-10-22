# connection-test Specification

## Purpose

Defines how the user-triggered WebDAV connection test on the Settings screen reads configuration, runs, and reports its result.

## Requirements

### Requirement: Connection test reads configuration once
The connection test SHALL take a single snapshot of the current WebDAV configuration and SHALL NOT keep observing configuration changes after it starts. Once the test finishes, it SHALL hold no background work or subscriptions, whatever the outcome.

#### Scenario: Not configured
- **WHEN** the user starts a connection test while no valid WebDAV configuration is stored
- **THEN** the test finishes, reports "not configured", and leaves no active configuration subscription behind

#### Scenario: Configured server responds
- **WHEN** the user starts a connection test with a valid configuration and the check succeeds or fails
- **THEN** the test finishes, reports the outcome, and leaves no active configuration subscription behind

### Requirement: At most one connection test runs at a time
The system SHALL ignore a request to start a connection test while another connection test is still in progress. A new test SHALL be possible once the previous one has finished.

#### Scenario: Rapid repeated taps
- **WHEN** the user triggers the connection test several times before the first one completes
- **THEN** the server check runs only once

#### Scenario: Retry after completion
- **WHEN** a connection test has finished and the user triggers it again
- **THEN** a new check runs

### Requirement: Connection test result is localized and typed
The system SHALL report the result as a typed outcome: success, not configured, certificate/TLS problem, unknown host, server unreachable, timeout, authentication failure (HTTP 401/403), not found (HTTP 404), other HTTP error with its status code, invalid URL, or unknown error. The Settings screen SHALL show each outcome using a localized, user-friendly string resource, with success styled as positive and the other outcomes styled as errors. The screen SHALL NOT show raw exception messages.

#### Scenario: Successful connection
- **WHEN** the server check succeeds
- **THEN** the Settings screen shows the localized success message in the positive style

#### Scenario: Untrusted certificate
- **WHEN** the check fails with a TLS handshake, peer verification or certificate validation error
- **THEN** the Settings screen shows the localized certificate message and not the exception text

#### Scenario: Credentials rejected
- **WHEN** the server answers HTTP 401 or 403
- **THEN** the Settings screen shows the localized authentication message and not the raw HTTP status text

#### Scenario: Other HTTP error
- **WHEN** the server answers another non-success status such as 503
- **THEN** the Settings screen shows the localized HTTP error message including the status code

#### Scenario: Failure without detail
- **WHEN** the check fails with an error that has no message or an unrecognised type
- **THEN** the Settings screen shows the localized generic failure message

### Requirement: Connection test is bounded and cancellable
The connection check SHALL finish within an overall time limit and SHALL abort its network call when the test is cancelled. The limit SHALL apply only to the connection test, not to uploads. Failures to read the stored configuration or unexpected errors from the check SHALL be reported as a result and SHALL NOT crash the app; the test SHALL always end in a non-testing state unless it was cancelled.

#### Scenario: Server does not answer in time
- **WHEN** the server does not answer before the connection-test time limit
- **THEN** the test fails with the timeout outcome

#### Scenario: Test cancelled during the call
- **WHEN** the coroutine running the check is cancelled while the request is in flight
- **THEN** the network call is cancelled and the cancellation is not reported as a failure result

#### Scenario: Stored configuration cannot be read
- **WHEN** reading the stored configuration fails
- **THEN** the test reports the generic failure outcome, stops testing, and a new test can be started

### Requirement: Connection test results belong to one session
Logging out, or changing the stored server URL, username or password, SHALL cancel a running connection test and clear the previous result. A cancelled test SHALL NOT publish its result.

#### Scenario: Logout during a running test
- **WHEN** the user logs out while a test is running, logs in again and starts a new test
- **THEN** the new test runs, and the old test's result is never shown

#### Scenario: Credentials changed after a test
- **WHEN** a result is shown and the stored server URL, username or password changes
- **THEN** the result is cleared
