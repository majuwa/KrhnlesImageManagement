# Design

## Context

See proposal.md (Why) for motivation and specs/server-certificate-trust/spec.md for the required behavior.

Observed state (read-only inspection):

- Five independently built `OkHttpClient`s, all with the platform default TLS setup: `WebDavClient.client` (+ `noRedirectClient`, `uploadClient` via `newBuilder()`), `AlbumsRepository.client` (PROPFIND, DELETE, MOVE, thumbnails, Memories check; it also owns a `WebDavClient`), the Coil client in `KrhnlesApplication.newImageLoader()` (process singleton, reads the config from a `stateIn` cache per request), and the private client inside `NextcloudLoginFlow` (`libs/login-flow-nextcloud`, git submodule, built via `includeBuild`; **no injection point**, failures surface only as `LoginFlowState.Failed(message: String)`).
- `WebDavClient` is constructed by `SettingsViewModel` (connection test), `AlbumsRepository`, `UploadWorker` (`clientFactory: (WebDavConfig) -> WebDavClient`, also feeding `DownloadVerifier` / `NextcloudHashVerifier`) and `DefaultRemovalCandidateChecker` (`clientFactory`).
- TLS failures are classified only by `Throwable.toConnectionTestResult()` (any `SSLException` / `GeneralSecurityException` in the cause chain → `CertificateError`), reused by `toAlbumsError()` and `toFolderCreationError()`.
- The connection test runs against the **stored** configuration; the Nextcloud login runs against the typed URL before anything is stored.
- No Network Security Config exists; minSdk 35 / targetSdk 37, so user-installed CAs are not trusted and the platform default trust manager is the NSC-aware root trust manager.
- Backup exclusions are guarded by `BackupRulesTest` via `*_DATASTORE_NAME` constants; logout clears stores in individual `runCatching` blocks (`clearLogged`); `persistCredentials()` clears verified uploads before saving for another URL / username.

## Goals / Non-Goals

**Goals:**
- One place that builds every server-facing OkHttp client and its TLS policy; impossible (by test) to build one elsewhere.
- Pin = exact leaf certificate bound to one `https` origin, honoured only while that origin is configured.
- Capture an untrusted certificate for review without ever completing a handshake with it.
- No stale trust: removing / replacing a pin can never be bypassed by connection pooling or TLS session resumption.

**Non-Goals:**
- Pinning a CA or SPKI, multiple pinned origins, client certificates.
- Making the user's browser accept the self-signed login page (Login Flow v2 opens an external browser that has its own warning).
- Replacing the raw `LoginFlowState.Failed.message` display for non-certificate login failures (pre-existing; follow-up).
- Changing cleartext (`http://`) behavior.

## Decisions

### D1 Trust model: system trust OR exact pinned leaf (SHA-256 of the DER certificate)
The platform trust manager decides first; only if it rejects, the pin is consulted. The pin is the SHA-256 of the full DER-encoded leaf, which is what browsers and `openssl x509 -noout -fingerprint -sha256` display, so users can compare it with the server.
- *Alternatives:* OkHttp `CertificatePinner` — rejected, it runs **after** the platform chain validation, so it can never accept a self-signed certificate. SPKI pin — survives re-issuing with the same key, but users cannot compare it with common tools; deferred. A Network Security Config with `<certificates src="user"/>` — rejected: static XML cannot hold a runtime pin, it trusts every user-installed CA for every host, and requires device-settings fiddling. Automatic trust-on-first-use — rejected, no user confirmation.
- The TLS stack verifies the server's handshake signature with the leaf's public key independently of the trust manager, so presenting the pinned certificate requires its private key.

### D2 `PinAwareTrustManager : X509ExtendedTrustManager` (`data/tls/`)
- Delegate = `TrustManagerFactory.getInstance(getDefaultAlgorithm()).init(null as KeyStore?)` (once per process, lazily). On Android this is the NSC-aware root trust manager, so all platform policy (system CAs, per-domain config, CT where enabled) keeps applying to the system path. The delegate is injectable (`internal`) so JVM tests can simulate "system-trusted" with an `okhttp-tls` test CA.
- `checkServerTrusted(chain, authType, socket)` / `(…, engine)` call the **same overload** of the delegate. On `CertificateException`: read the peer host/port from `(socket as SSLSocket).handshakeSession` / `engine.handshakeSession` (`peerHost`, `peerPort`); if a pin exists, the peer host (normalized via `HttpUrl` rules: lowercase, IDN → ASCII, IPv6 brackets stripped) and port equal the pin's origin, `sha256(chain[0].encoded) == pin.sha256` (constant-time compare) and `chain[0].checkValidity()` passes → accept. If a pin exists for that host/port but the leaf differs → throw `CertificateChangedException` (a `CertificateException`, original as cause). Otherwise rethrow the original.
- The non-extended `checkServerTrusted(chain, authType)` delegates only (no host known → no pin, fail closed). `checkClientTrusted` delegates; `getAcceptedIssuers` returns the delegate's issuers. If the platform delegate is not an `X509ExtendedTrustManager`, the pin path is disabled (fail closed) and a type-only warning is logged.

### D3 Host name verification: pinned leaf tolerates SAN mismatch only on the pinned origin
`PinAwareHostnameVerifier` first calls OkHttp's `OkHostnameVerifier`; if that fails it accepts only if a pin exists, the requested host equals the pin's host, `session.peerPort` equals the pin's port (unknown port → reject) and the session's leaf SHA-256 equals the pin.
- *Why tolerate SAN mismatch:* self-signed certificates very often have no SAN (CN only — OkHttp has no CN fallback), name `localhost`, a LAN IP or the box's internal name. Requiring a SAN match would make most real self-signed setups unpinnable and push users to `http://`. Host name verification exists to bind a certificate to the host the client meant to reach; the pin is a strictly narrower binding (one exact certificate ↔ one host + port, confirmed by the user), so it carries the identity guarantee for this one case.
- *Why still meaningful:* (1) the system path keeps full SAN verification; (2) the pin path is checked in **both** the trust manager (peer host/port of the socket) and the verifier (requested host + peer port); (3) the pinned certificate on any other host or port is rejected even if its SAN names that host; (4) a pin for an origin other than the configured one is never handed to a client (D5).
- *Alternative:* require SAN match also for pins — rejected (usability, no security gain over an exact pin). Binding the trust manager to the configured host without reading the peer host — rejected: Coil and redirects can reach other hosts through the same client.

### D4 One factory for every server client: `data/tls/ServerHttpClients.kt`
`serverHttpClientBuilder(trust: ServerTrust): OkHttpClient.Builder` with `ServerTrust(pin: PinnedCertificate?, capture: CertificateCapture? = null)` builds a **new `SSLContext("TLS")` per call** with the (capturing) pin-aware trust manager and sets `sslSocketFactory(factory, trustManager)` + `hostnameVerifier`. Every client applies its timeouts / interceptors on top; `WebDavClient`'s derived clients keep using `newBuilder()` and therefore share the snapshot.
- Each trust snapshot has its own SSL session cache, so a session established under a removed pin can never be resumed by a newer client; OkHttp's `Address` equality includes `sslSocketFactory` and `hostnameVerifier`, so pooled connections are never shared across snapshots.
- `OkHttpClient.Builder()`, `OkHttpClient()`, `.sslSocketFactory(`, `.hostnameVerifier(` and `SSLContext.getInstance` appear only in this file (D10). Sibling changes (`download-album-photos`, `show-videos-in-albums` with Media3 `OkHttpDataSource.Factory(callFactory)`) must take their client / `Call.Factory` from here or from an existing pin-aware client.

### D5 Pin snapshots are passed explicitly, never defaulted
- `PinnedCertificate(origin: ServerOrigin, sha256: ByteString)`; `ServerOrigin(host, port)` is always `https` (constructed only from an `https` `HttpUrl`, effective port).
- `TrustedCertificateRepository.pinFor(config)` returns the stored pin only if its origin equals the configured URL's origin; readers use a bounded read (`withTimeoutOrNull(3 s)` + `runCatching` rethrowing `CancellationException`; timeout / failure → `null` = system trust only, fail closed).
- `WebDavClient(config, pin)` and `AlbumsRepository(config, pin)` take the pin **without a default**, so the compiler finds every construction site. Factories become `(WebDavConfig, PinnedCertificate?) -> …` (`UploadWorker`, `DefaultRemovalCandidateChecker`, `SettingsViewModel.connectionTester`). The worker and the removal check read the pin once per run / check together with the config.
- `AlbumsRepositoryProvider` re-reads config **and** pin on every `get()` (inside its existing Mutex-with-timeout) and rebuilds when either changes.
- Coil (`KrhnlesApplication`): `combine(webDavConfig, trustedCertificate)` → `stateIn(appScope, Eagerly)`; the fetcher gets a `Call.Factory` that delegates to a client held per effective pin and rebuilt (old pool `evictAll()`) when it changes.
- *Alternative:* put the pin into `WebDavConfig` / the credentials DataStore — rejected: it would couple to the decrypting snapshot flow and `credentials_version`, and the feature asks for its own backup-excluded file.

### D6 Storage: `TrustedCertificateStore`, DataStore `trusted_certificates`
- `TRUSTED_CERTIFICATES_DATASTORE_NAME = "trusted_certificates"`, one string key `trusted_certificate` with a strict JSON record `{origin, sha256 (64 lowercase hex), subject, issuer, notBefore, notAfter, trustedAt}` read with `opt` + type checks; anything invalid (incl. non-`https` origin) → no pin. `trustedCertificatesCorruptionHandler` (`ReplaceFileCorruptionHandler` → empty), internal `DataStore<Preferences>` constructor for tests, process delegate private.
- At most one record (the app has one server); `trust()` replaces, `clear()` removes. Excluded in `backup_rules.xml` and both `data_extraction_rules.xml` sections; `BackupRulesTest` gets the constant.
- Not encrypted: a fingerprint is not secret, and the app sandbox gives the integrity; an attacker able to write app-private files could equally alter the code path.
- Lifecycle in `SettingsViewModel`: `logout()` → `clearLogged("trusted certificate")` after verified uploads, before credentials. `persistCredentials(url, user, pw, pinCandidate)`: if the new origin differs from the previous one (or the previous config is unreadable), clear the pin; then, if `pinCandidate` (login attempt) has the new origin, store it — both **before** `credentialStore.save()`; failures are logged by type and never block the save. A pin left for a non-configured origin is inert (D5) and replaced on the next trust.

### D7 Capture without trusting: `CertificateCapture` + capturing decorator
`CapturingTrustManager` wraps the pin-aware trust manager, calls it, and on `CertificateException` records (first only, `AtomicReference`) the leaf **only if** the peer host/port equals the capture's origin, plus whether it was a `CertificateChangedException`; it always rethrows. The handshake therefore fails before any HTTP bytes (credentials) are written.
- Used only for: the connection test (`WebDavClient.testConnection()` derives a per-call client from its own builder with `ServerTrust(pin, capture)`) and each Nextcloud login attempt.
- After a failed test with a capture, `testConnection()` fails with `CertificateReviewException(certificate: ServerCertificate, changed: Boolean, cause)`, which `toConnectionTestResult()` maps to `UntrustedCertificate(certificate)` / `CertificateChanged(certificate)` before the generic TLS branch.
- `ServerCertificate` (origin, sha256, subject / issuer via `X500Principal.getName(RFC2253)` with Unicode `Cc` / `Cf` / bidi controls removed and capped at 256 chars, `notBefore`, `notAfter`, `isValidAt(now)`). Subject / issuer are never logged.

### D8 Typed results and messages
- `ConnectionTestResult`: add `UntrustedCertificate(certificate: ServerCertificate)` and `CertificateChanged(certificate: ServerCertificate?)` (null outside the test, e.g. when reused for albums); `CertificateError` stays for TLS failures without a capture (protocol errors, redirect target untrusted, non-socket path).
- Classification order in `toConnectionTestResult()`: `WebDavHttpException` → `CertificateReviewException` → `CertificateChangedException` in the cause chain → generic `SSLException` / `GeneralSecurityException` → rest unchanged.
- `AlbumsError.CertificateChanged` (+ `albums_error_certificate_changed`); `AlbumsError.CertificateError` text now points to Settings → Test connection. `FolderCreationError.Connection` also carries `CertificateChanged`. `UploadFailure.CertificateChanged` (cause-chain check in `toUploadFailure()`, besides the 413 / 507 code branch) with `upload_failure_certificate_changed`, joined via `joinNotificationTexts()`.
- All new text in `strings.xml` (review card, warning, dialog, validity explanation, "Stop trusting", errors); dates via a locale-aware formatter once per value.

### D9 Settings UX and state
- `SettingsUiState` gains `trustedCertificate: TrustedCertificateInfo?` (observed from the store, filtered to the configured origin), `certificateReview: CertificateReview?` (source `ConnectionTest` or `Login`, the certificate, `changed`) and `trustInProgress`.
- "Trust this certificate" → `AlertDialog` (fingerprint, origin, warning, how to compare the fingerprint on the server) → `confirmTrust()`: guarded one-shot (`trustJob`, re-entry ignored); for `ConnectionTest` it re-reads the config (3 s) and stores only if the configured origin still equals the certificate's origin and the review is still current, writes under `withTimeoutOrNull(5 s)`, `ensureActive()` before publishing, then calls `testConnection()`. A config change / logout cancels and nulls `trustJob` and clears the review (inside `cancelConnectionTest()`).
- Login: `LoginFlowState.Failed` with a capture → `certificateReview(Login)` instead of the raw message; confirming keeps the certificate as in-memory `pendingLoginPin` and restarts the login with `ServerTrust(pendingLoginPin, newCapture)`. `pendingLoginPin` is discarded on URL change, failure or cancel, and handed to `persistCredentials()` on `Authenticated` (stored only if the returned server's origin matches).
- "Stop trusting" row (fingerprint, valid until) → guarded one-shot `clear()`.

### D10 Guards
- `NoTrustAllRegressionTest` (source scan of `src/main`, module dir via `krhnles.moduleDir`): `X509TrustManager` / `X509ExtendedTrustManager` / `HostnameVerifier` implementations and `SSLContext.getInstance`, `OkHttpClient.Builder()`, `OkHttpClient()`, `.sslSocketFactory(`, `.hostnameVerifier(` only in `data/tls/`; nowhere `HostnameVerifier { _, _ -> true }`-style lambdas, `ALLOW_ALL`, `NoopHostnameVerifier`, `getInsecure`, `TrustAll`, `HttpsURLConnection.setDefault`, `DefaultHttpDataSource`; manifest / `res/xml` without a Network Security Config that contains `src="user"` or `overridePins`.
- Behavioural tests (D2/D3) are the real proof that the one allowed trust manager is not permissive; Android Lint's `TrustAllX509TrustManager` / `BadHostnameVerifier` checks stay enabled with zero issues.

### D11 Login library: injectable client
`NextcloudLoginFlow(…, httpClient: OkHttpClient? = null)`: when given, the library uses `httpClient.newBuilder()` plus its User-Agent interceptor and timeouts; otherwise behaves as today. Released in `majuwa/login-flow-nextcloud`, then the submodule is bumped. Until then the login flow cannot use a pin; manual configuration + connection test works independently.

## Risks / Trade-offs

- [User trusts a MITM certificate on first contact] → explicit two-step confirmation, warning text explaining how to compare the fingerprint on the server, never automatic; scope limited to one origin.
- [SAN mismatch tolerated for pins] → only for the exact pinned leaf on the exact configured host + port, double-checked (D3).
- [Platform calls the non-extended trust-manager overload] → pin path fails closed; manual device check task on an API 35 emulator.
- [Stale trust via pooled connections / TLS resumption] → per-snapshot `SSLContext`, explicit rebuild + `evictAll()` in the Coil holder, provider rebuild on pin change.
- [Pin read timeout makes a healthy pinned server fail] → fail closed by design; the error is retryable and rare (local DataStore read).
- [Logout clear fails, then re-login to the same origin resurrects the pin] → logged; same accepted risk as other logout clears; the user still confirmed this exact certificate for this origin before.
- [Server certificates that rotate often (internal ACME)] → each rotation shows "certificate changed" and needs re-trust; CA/SPKI pinning is a possible follow-up.
- [Library change lives in another repository] → separate task group; app-side work does not depend on it except the login path.
- [Server-controlled subject / issuer text in the UI] → sanitized, length-capped, shown as data under labelled fields, never logged.

## Migration Plan

No data migration: the new DataStore starts empty and existing users see no change until a certificate is trusted. Rollback: removing the feature leaves an inert, backup-excluded file; clients fall back to platform defaults.

## Open Questions

- Release mechanics of `login-flow-nextcloud` (tag vs. plain submodule commit) — does not change the design.
- Whether the review card should also offer copying the fingerprint to the clipboard — UI nicety, can be added without spec changes.
