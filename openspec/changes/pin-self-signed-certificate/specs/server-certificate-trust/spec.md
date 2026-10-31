# Spec Delta

## Purpose

Lets users of self-hosted servers with a self-signed certificate connect securely by explicitly trusting exactly one reviewed certificate for their configured server, while every other connection keeps relying on the system trust store.

## ADDED Requirements

### Requirement: Server certificates are trusted by the system store or the pin only
Every connection the app makes to a server SHALL accept a server certificate only if the system trust store (including the platform network security configuration) accepts it, or if it is the pinned certificate for the connection's origin as defined in the other requirements of this capability. The app SHALL never accept every certificate and SHALL never skip host name verification.

#### Scenario: Publicly trusted server without a pin
- **WHEN** the configured server presents a certificate that the system trust store accepts and that matches the host name
- **THEN** the connection succeeds

#### Scenario: Self-signed server without a pin
- **WHEN** the configured server presents a self-signed certificate and no certificate is pinned
- **THEN** the connection fails and no request data is sent

#### Scenario: System-trusted certificate for the wrong host name
- **WHEN** a server presents a certificate that the system trust store accepts but whose names do not cover the requested host, and it is not the pinned certificate
- **THEN** the connection fails

### Requirement: The pinned certificate is accepted only on its own origin
A pinned certificate SHALL be accepted only when the connection uses `https` to the pinned origin's host and effective port, that origin is the configured server origin, the presented leaf certificate is byte-for-byte the pinned one (equal SHA-256 fingerprint), and the current time is within its validity period. Host names SHALL be compared case-insensitively in normalized form.

#### Scenario: Pinned certificate on the configured server
- **WHEN** a pin exists for the configured origin and the server at that host and port presents exactly the pinned, currently valid certificate
- **THEN** the connection succeeds

#### Scenario: Pinned certificate whose names do not match the host
- **WHEN** the pinned certificate does not list the configured host name (for example it names only a LAN address) and the configured server presents it
- **THEN** the connection succeeds because the user explicitly bound this exact certificate to this origin

#### Scenario: Pinned certificate presented by another host
- **WHEN** a connection to any other host presents the pinned certificate, even one whose names cover that host
- **THEN** the connection fails

#### Scenario: Pinned certificate on another port of the same host
- **WHEN** a connection to the configured host on a different port presents the pinned certificate
- **THEN** the connection fails

#### Scenario: Pin stored for an origin that is not configured
- **WHEN** the stored pin belongs to an origin other than the configured server origin
- **THEN** the pin is ignored and only the system trust store decides

#### Scenario: Pinned certificate has expired
- **WHEN** the configured server presents the pinned certificate after its validity period ended or before it began
- **THEN** the connection fails

### Requirement: All server connections apply the same trust rules
The WebDAV operations (listing, folder creation, upload, rename, deletion, size checks, verification downloads), album thumbnails and previews, the image loader, the upload worker, the uploaded-photo removal check and the Nextcloud login flow SHALL all apply the same trust rules. A connection SHALL never reuse a TLS session or pooled connection established under a pin that has since been removed or replaced.

#### Scenario: Background upload to a pinned server
- **WHEN** an upload runs in the background against a configured server whose certificate is pinned
- **THEN** the upload connects and transfers the files

#### Scenario: Thumbnails after trusting
- **WHEN** the user trusts the server certificate and then opens an album
- **THEN** the album list and its thumbnails load from the pinned server

#### Scenario: Pin removed while connections are open
- **WHEN** the pin is removed (stop trusting, logout or server change) after connections to the server were made
- **THEN** the next request performs a full certificate check and fails for the self-signed server

### Requirement: Untrusted certificates are captured without trusting them
When the connection test or the Nextcloud login fails because the server certificate is not trusted, the app SHALL record the leaf certificate presented by the tested origin and still fail the connection. Certificates presented by any other host (for example after a redirect) SHALL NOT be offered for review. Capturing SHALL NOT send credentials or request data over the untrusted connection.

#### Scenario: Connection test against a self-signed server
- **WHEN** the user runs the connection test against a configured `https` server with a self-signed certificate
- **THEN** the test fails, and the result carries that server's leaf certificate for review

#### Scenario: Redirect to a host with an untrusted certificate
- **WHEN** the configured server redirects the test to another host whose certificate is not trusted
- **THEN** the test reports a certificate problem without offering a certificate for review

### Requirement: Certificate details are shown for review
For a captured certificate, Settings SHALL show its SHA-256 fingerprint (full, as grouped hexadecimal), subject, issuer, start and end of validity (formatted locale-aware) and the origin it would be trusted for, together with a warning that trusting lets anyone holding this certificate's key read the connection. Subject and issuer SHALL be shown without control characters and length-limited, and SHALL NOT be logged.

#### Scenario: Review card after a failed test
- **WHEN** the connection test fails with an untrusted certificate
- **THEN** Settings shows the fingerprint, subject, issuer, validity, origin, the warning and a "Trust this certificate" action

#### Scenario: Certificate outside its validity period
- **WHEN** the captured certificate has expired or is not yet valid
- **THEN** Settings shows its details with an explanation and does not offer "Trust this certificate"

### Requirement: Trusting a certificate requires explicit confirmation
"Trust this certificate" SHALL open a confirmation dialog repeating the fingerprint and the warning; only confirming it SHALL store the pin. The pin SHALL be stored only if the reviewed certificate's origin is still the configured server origin (or the origin of the running login attempt) and the review still belongs to the current settings. After a successful trust the connection test SHALL run again.

#### Scenario: User confirms
- **WHEN** the user taps "Trust this certificate" and confirms the dialog
- **THEN** the certificate is pinned for the reviewed origin and the connection test runs again and succeeds

#### Scenario: User cancels
- **WHEN** the user dismisses the confirmation dialog
- **THEN** nothing is stored and the review stays visible

#### Scenario: Configuration changed before confirming
- **WHEN** the server URL is changed or the user logs out while the review or the dialog is shown
- **THEN** the review is cleared and confirming stores nothing

#### Scenario: Saving the pin fails
- **WHEN** storing the pin fails or does not finish in time
- **THEN** Settings shows a localized error, nothing is trusted, and the user can try again

### Requirement: At most one pin is stored, for one origin
The app SHALL store at most one pinned certificate, together with its origin (`https`, host, effective port), SHA-256 fingerprint and the details shown at review. Only `https` origins SHALL be pinnable. Trusting a certificate SHALL replace any previous pin. An unreadable or corrupt pin store SHALL be treated as having no pin.

#### Scenario: Trusting replaces the old pin
- **WHEN** a pin exists and the user trusts a new certificate after a "certificate changed" error
- **THEN** only the new certificate is pinned

#### Scenario: Corrupt pin store
- **WHEN** the stored pin cannot be parsed
- **THEN** no certificate is pinned and self-signed connections fail until the user trusts again

### Requirement: The pin is cleared on logout and on server change
Logging out SHALL remove the pin. Saving credentials for a different origin SHALL remove a pin for another origin before the new credentials are written. Changing only the username, password or base folder on the same origin SHALL keep the pin. A failure to remove the pin SHALL be logged and SHALL NOT prevent the logout or the save.

#### Scenario: Logout
- **WHEN** the user logs out while a certificate is pinned
- **THEN** the pin is removed, and logging in again to the same self-signed server requires trusting the certificate again

#### Scenario: Switching servers
- **WHEN** the user saves credentials for a server with a different host, port or scheme
- **THEN** the pin of the previous server is removed

#### Scenario: New password on the same server
- **WHEN** the user saves new credentials for the same origin
- **THEN** the pin is kept and the connection still succeeds

### Requirement: The user can stop trusting a pinned certificate
While a pin exists for the configured origin, Settings SHALL show the trusted certificate's fingerprint and its validity end with a "Stop trusting" action that removes the pin.

#### Scenario: Stop trusting
- **WHEN** the user taps "Stop trusting"
- **THEN** the pin is removed and the next connection to the self-signed server fails with the untrusted-certificate result

### Requirement: The pin is excluded from backup and device transfer
The pin store SHALL be excluded from cloud backup and from device-to-device transfer, so a restored or new device never trusts a certificate the user did not confirm on it.

#### Scenario: Backup rules
- **WHEN** the backup and data extraction rules are inspected
- **THEN** the pin store file is excluded in the full-backup rules and in both the cloud-backup and device-transfer sections

### Requirement: A changed server certificate is reported as such
When a pin exists for the connection's origin and the server presents a different certificate that the system trust store does not accept, the connection SHALL fail and be reported as "server certificate changed" in Settings, album and review screens and upload failure notifications, never as success and never by silently re-pinning.

#### Scenario: Connection test after the server certificate was replaced
- **WHEN** the configured server presents a new self-signed certificate while the old one is pinned
- **THEN** the test reports "certificate changed" with the new certificate's details for review, and the old pin stays in place until the user trusts the new one

#### Scenario: Album list after the certificate changed
- **WHEN** the album list is loaded while the server presents a certificate other than the pinned one
- **THEN** the screen shows the localized "server certificate changed" message

#### Scenario: Upload after the certificate changed
- **WHEN** a background upload runs while the server presents a certificate other than the pinned one
- **THEN** the upload fails and its notification gives the localized "server certificate changed" reason

#### Scenario: Server switched to a publicly trusted certificate
- **WHEN** a pin exists and the server now presents a certificate the system trust store accepts for its host name
- **THEN** the connection succeeds

### Requirement: The Nextcloud login can trust a self-signed server
When the Nextcloud login fails because the entered server's certificate is untrusted, Settings SHALL offer the same review and confirmation. A confirmed certificate SHALL be used for the rest of that login attempt and SHALL be persisted only if the credentials are saved for that same origin.

#### Scenario: Login against a self-signed server
- **WHEN** the user starts the Nextcloud login for a self-signed server, trusts the certificate after review and completes the login in the browser
- **THEN** the login finishes, the credentials are saved and the certificate is pinned for that origin

#### Scenario: Server reports a different address
- **WHEN** the login completes but the server reports an address with a different origin than the trusted one
- **THEN** the credentials are saved without a pin, and the next connection test offers that origin's certificate for review

#### Scenario: Login abandoned after trusting
- **WHEN** the user trusts the certificate during a login attempt and then cancels or the login fails
- **THEN** no pin is stored
