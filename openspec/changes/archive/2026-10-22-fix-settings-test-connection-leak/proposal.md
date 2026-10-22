# Proposal

## Why

GitHub issue #22 (duplicate #41): `SettingsViewModel.testConnection()` used `webDavConfig.collect { ...; return@collect }`. `return@collect` only leaves the lambda for one emission; it does not cancel the upstream DataStore flow, so every tap on "Test Connection" left a coroutine suspended in `viewModelScope` until the ViewModel was cleared. Quick repeated taps also started overlapping connection tests. The `.first()` replacement has since landed on `main` (in the #61 merge), but only the "not configured" path is covered by a test. Nothing stops overlapping runs, nothing stops the `collect`/`return@collect` pattern from coming back somewhere else, and the result messages are hard-coded English strings (the UI even checks one with `startsWith`).

## What Changes

- `testConnection()` reads the current WebDAV config exactly once and finishes. No collector is left behind on the "not configured", success or failure path.
- Tapping again while a test is already running is ignored, so connection tests never overlap.
- The connection check can be injected into `SettingsViewModel` (it defaults to `WebDavClient.testConnection`), so the success and failure paths can be tested without a network.
- Test results become a typed UI-state value (`ConnectionTestResult`: Success, NotConfigured, CertificateError, UnknownHost, ServerUnreachable, Timeout, AuthFailed, NotFound, HttpError(code), InvalidUrl, UnknownError). The screen maps each case to a user-friendly string resource and colours it by type rather than by matching on the text; raw exception text is never shown. **BREAKING** (internal only): `SettingsUiState.testResult` changes from `String?` to `ConnectionTestResult?`.
- `WebDavClient` reports non-2xx statuses as a typed `WebDavHttpException(code)` (message unchanged, so other callers keep working). `testConnection()` gets an overall per-call timeout (30 s) and is cancelled together with its coroutine; the shared client used for uploads is unchanged.
- Logout or a change of server URL, username or password cancels a running test and clears the previous result; a cancelled test never publishes its result. Failures to read the stored config (DataStore `IOException`) or a throwing check become a result instead of crashing.
- A source-scanning regression test fails the build if `return@collect` (taking one value from a flow with `collect` instead of `first()`) shows up anywhere in the `app/src/main` sources.

## Capabilities

### New Capabilities
- `connection-test`: the user-triggered WebDAV connection test on the Settings screen: it reads the config once, runs at most one test at a time, and shows a localized result.

### Modified Capabilities
<!-- none -->

## Impact

- `ui/settings/SettingsViewModel.kt`, new `ui/settings/ConnectionTestResult.kt`, `ui/settings/SettingsScreen.kt`, `data/WebDavClient.kt`, `res/values/strings.xml`
- Tests: `SettingsViewModelTest.kt`, `SettingsScreenTest.kt`, `WebDavClientTest.kt`, new `NoCollectEarlyReturnRegressionTest.kt` and `ConnectionTestResultMappingTest.kt`
- Docs: `docs/architecture.md` (Settings section and flow conventions), `CLAUDE.md` (one-shot flow reads, guard release, typed WebDAV errors), `README.md` (Test Connection messages)
- No new dependencies, and no changes to persisted data.
