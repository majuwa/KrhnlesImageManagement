# Proposal

## Why

Feature request: *"Self-signed certificates are common for self-hosted Nextcloud/WebDAV but today only produce a clear error. When a connection test / login fails with an untrusted certificate, Settings shows the leaf certificate's SHA-256 fingerprint, subject, issuer and validity and offers 'Trust this certificate' (explicit user confirmation, with a warning). The pinned fingerprint is stored per server origin (scheme+host+port), excluded from backup, cleared on logout and on server change. All OkHttp clients accept the system trust store OR exactly the pinned certificate for the configured origin — never trust-all, never for another host. Certificate change → clear 'certificate changed' error."*

Today every TLS failure maps to `ConnectionTestResult.CertificateError` / `AlbumsError.CertificateError` and the app is unusable with a self-signed server unless the user downgrades to plain `http://` (which is worse: credentials in cleartext) or installs a user CA, which the app does not trust anyway (minSdk 35, no Network Security Config opts into user CAs). A narrowly scoped, user-confirmed certificate pin closes this gap without weakening security for anyone who does not opt in.

## What Changes

- **Certificate review on untrusted servers**: when the Settings connection test or the Nextcloud login fails because the server's certificate is not trusted, the presented leaf certificate is captured *without trusting it* (the handshake still fails) and Settings shows its SHA-256 fingerprint, subject, issuer and validity period with a warning and a "Trust this certificate" action. Trusting needs a second, explicit confirmation in a warning dialog. A certificate outside its validity period is shown but cannot be trusted.
- **Per-origin pin, at most one**: the confirmed certificate is stored as the pin for exactly one origin (`https`, host, effective port). It is honoured only while that origin is the configured server origin. Only `https` origins can be pinned.
- **Pin lifecycle**: the pin is cleared on logout and whenever credentials are saved for a different origin; it survives a username, password or base-folder change on the same origin. Settings shows the trusted certificate's fingerprint with a "Stop trusting" action. The pin's DataStore file is excluded from cloud backup and device transfer.
- **Every server connection honours the pin, nothing else**: all OkHttp clients (WebDAV client incl. its no-redirect and upload variants, album repository incl. thumbnails / Memories / verification downloads, removal checks, the Coil image loader, the Nextcloud login flow) accept a server certificate if the system trust store (incl. the platform Network Security Config) accepts it, *or* if the connection goes to the pinned origin's host and port and the presented leaf is exactly the pinned certificate and currently valid. Never trust-all, never the pinned certificate on another host or port. All clients are built through one factory.
- **Certificate changed**: if a pin exists for the origin and the server presents a different, untrusted certificate, the connection fails with a distinct "server certificate changed" result in Settings (with the new certificate's details, so the user can review and re-trust it), in album / review screens and in upload failure notifications.
- **Login flow library**: `NextcloudLoginFlow` (submodule `libs/login-flow-nextcloud`) gets an optional injected `OkHttpClient` so the login flow uses the pin-aware, capturing client. **Requires a change and submodule bump in the user's `majuwa/login-flow-nextcloud` repository.**
- **Guards**: a source-scanning regression test fails on any trust-all `TrustManager`, permissive `HostnameVerifier`, insecure socket factory, user-CA Network Security Config, or an `OkHttpClient` built outside the trust factory in main sources.
- **Documentation**: README.md, CLAUDE.md, docs/architecture.md and docs/requirements.md are updated (U-004 changes from "error only" to "error or user-confirmed pin").

Out of scope: pinning a private CA (only the leaf is pinned), trusting certificates for hosts other than the configured server, mutual TLS / client certificates, changing the browser's handling of the login page (the user's browser shows its own warning for a self-signed login page).

## Capabilities

### New Capabilities
- `server-certificate-trust`: how the app decides whether a server certificate is trusted (system trust store or a user-confirmed per-origin pin), how an untrusted certificate is captured and presented for review, how the user confirms or revokes trust, the pin's storage, lifecycle and backup exclusion, host / port binding, and how a changed certificate is reported.

### Modified Capabilities
- `connection-test`: the typed result gains "untrusted certificate (with reviewable certificate details)" and "server certificate changed"; the plain certificate/TLS outcome remains for TLS failures without a reviewable certificate.
- `album-error-handling`: album list, album detail and review screens distinguish "server certificate changed" from the general certificate message, and the untrusted-certificate message points the user to Settings to review the certificate.

## Impact

- **New code** (`data/tls/`): trust factory for OkHttp clients, pin-aware `X509ExtendedTrustManager` + `HostnameVerifier`, capture decorator, `ServerOrigin`, certificate details model; `TrustedCertificateStore` (new DataStore `trusted_certificates`, corruption handler, internal test constructor).
- **Changed code**: `WebDavClient`, `AlbumsRepository`, `AlbumsRepositoryProvider` (cache key includes the pin), `UploadWorker` / `RemovalCandidateChecker` / `UploadedRemovalViewModel` client factories (pin argument), `KrhnlesApplication` (Coil client rebuilt on pin change), `SettingsViewModel` / `SettingsScreen` (review card, confirmation dialog, trusted-certificate row, login-flow capture, pin lifecycle in `persistCredentials` / `logout`), `ConnectionTestResult`, `AlbumsError`, `FolderCreationError`, `strings.xml`, `backup_rules.xml`, `data_extraction_rules.xml`.
- **External**: `libs/login-flow-nextcloud` (injectable `OkHttpClient`, new release + submodule bump).
- **Dependencies**: test-only `com.squareup.okhttp3:okhttp-tls` (same version as OkHttp) for `HeldCertificate` / `HandshakeCertificates` with `MockWebServer`.
- **Sibling changes**: `download-album-photos` and `show-videos-in-albums` must build their HTTP clients / Media3 data sources through the trust factory (enforced by the regression test); `nextcloud-chunked-upload` inherits pinning via `WebDavClient`'s clients; `german-translation` (implemented last) must translate the new strings; `readme-roadmap-cleanup` touches the same README.
