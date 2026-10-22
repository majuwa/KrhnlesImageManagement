# Tasks

## 1. ViewModel fix and guard

- [x] 1.1 Add `ConnectionTestResult` sealed type and change `SettingsUiState.testResult` to it; verify the project compiles (`./gradlew compileDebugKotlin`)
- [x] 1.2 Add injectable `connectionTester` constructor parameter (default `WebDavClient(it).testConnection()`) keeping `@JvmOverloads`; verify `ViewModelFactoryRegressionTest` passes
- [x] 1.3 Keep the single `first()` config read and add a `Job`-based re-entrancy guard in `testConnection()`; verify with the tests in 1.4
- [x] 1.4 Extend `SettingsViewModelTest`: no lingering collector for the not-configured, success and failure paths; concurrent calls invoke the tester once; a retry after completion runs again; verify `./gradlew test --tests '*SettingsViewModelTest'` passes

## 2. Localized UI

- [x] 2.1 Add `connection_test_*` strings to `strings.xml` and map `ConnectionTestResult` in `SettingsScreen` (colour by type, not by text); verify `./gradlew lintDebug` reports zero issues
- [x] 2.2 Add a `SettingsScreenTest` case that shows the localized success/failure text; verify it passes

## 3. Pattern regression guard

- [x] 3.1 Add `NoCollectEarlyReturnRegressionTest` that scans `app/src/main` Kotlin sources for `return@collect`, with matcher self-tests; verify it passes on the current tree

## 4. Docs and quality gates

- [x] 4.1 Update `docs/architecture.md` (connection test flow, one-shot flow reads) and `CLAUDE.md` (convention: use `first()` for one-shot reads); verify the text is present
- [x] 4.2 Run `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport` and verify all pass with no new detekt issues

## 5. Code review fixes

- [x] 5.1 Cancel and null `testConnectionJob` in `logout()` and on URL/username/password change; `ensureActive()` before publishing; test: suspended tester, logout, re-login, new test calls the tester again and the stale result is never written
- [x] 5.2 `WebDavClient.testConnection(timeoutMillis)`: per-call OkHttp call timeout (shared upload client unchanged) and `enqueue` + `suspendCancellableCoroutine` so cancellation cancels the call; tests for timeout and cancellation; correct the "always released" comment, design decision 2 and `docs/architecture.md`
- [x] 5.3 Convert config-read `IOException` and throwing testers into results (rethrow `CancellationException`, no detekt suppression); test with a repository whose `webDavConfig` throws on the test read and with a throwing tester
- [x] 5.4 Typed `WebDavHttpException(code)` in `WebDavClient`; map failures to typed `ConnectionTestResult` cases with localized strings (certificate, unknown host, unreachable, timeout, auth, not found, HTTP code, invalid URL, generic); remove raw-message strings; mapping unit tests and screen tests asserting no raw text
- [x] 5.5 Clear `testResult` on credential/URL change; tests for clear, cancel and base-folder change keeping the result
- [x] 5.6 Document the source-scan heuristic gaps in design.md
- [x] 5.7 Update README (Test Connection messages), CLAUDE.md and docs/architecture.md; rerun `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport`

## Workflow follow-up

- Code and security review, archive, commit and closing #22/#41 are handled by separate agents.
