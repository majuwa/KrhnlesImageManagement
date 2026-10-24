## 1. Accessibility

- [x] 1.1 In `SettingsScreen.kt` give the Wi-Fi only `Row` `Modifier.toggleable(value = uiState.wifiOnly, role = Role.Switch, onValueChange = viewModel::setWifiOnly)`, `testTag("wifiOnlyRow")` and a 48 dp minimum height (`minimumInteractiveComponentSize`/padding); set the inner `Switch` to `onCheckedChange = null`

## 1b. Retry budget

- [x] 1b.1 Add optional top-level `interruptions` (absent = 0) to the queue model and its JSON read/write; retry files and old files start at 0
- [x] 1b.2 `UploadWorker.interrupted()`: cap on `interruptions` instead of `runAttemptCount`; persist `interruptions + 1` via `rewriteQueueFile` before `Result.retry()`; update the `MAX_UPLOAD_RUN_ATTEMPTS` and class KDoc (remove "Wi-Fi-only back-offs count too")

## 2. Tests

- [x] 2.1 `CredentialStoreTest`: `wifiOnly` defaults to false; `saveWifiOnly(true)` is read back by a new `CredentialStore` instance (test name "survives a new store instance"; the DataStore delegate is process-wide, never create a second DataStore for the same file); `clear()` resets it to false. Use the combined style of `CredentialStoreTest.kt:323-335` (default, round trip, clear)
- [x] 2.2 `SettingsScreenTest`: `onNodeWithTag("wifiOnlyRow").performScrollTo()`, click, `assertIsOn()`, `fake.wifiOnlyValue()` is true; `onAllNodes(isToggleable() and hasText("Upload on Wi-Fi only")).assertCountEquals(1)`
- [x] 2.2b `UploadWorkerRunTest`: `runAttemptCount = MAX-1` with a metered network and Wi-Fi only on -> `Result.retry()`, no notification, queue file intact; N metered wake-ups then one foreground refusal -> still `Result.retry()`; `MAX_UPLOAD_RUN_ATTEMPTS` refusals -> failure notification + retry file; queue file without `interruptions` parses as 0 and the counter round-trips through `rewriteQueueFile`
- [x] 2.3 Verify existing coverage still matches the spec (`UploadConstraintsTest`, `UploadEnqueuerTest`, `RetryUploadReceiverTest`, `UploadWorkerRunTest` metered/unmetered); add a case only if a scenario in `specs/wifi-only-upload` is uncovered

## 3. Docs and checks

- [x] 3.1 `README.md`: describe "Upload on Wi-Fi only" as a new Settings description section (do not duplicate the usage notes at ~lines 79/82): off by default, unmetered semantics incl. metered hotspots and unmetered cellular plans, applies to retries, already queued uploads wait for an unmetered network also after switching off, the retry back-off may grow to 5 h after repeated metered wake-ups
- [x] 3.2 `docs/architecture.md`: note the single toggleable row and that the setting is reset on logout and not restored from backup; fix line ~195 (`interruptions` counter in the queue file instead of `runAttemptCount`, "Wi-Fi-only back-offs count too" removed)
- [x] 3.2b Close #14 and #33 when merged
- [x] 3.3 `./gradlew test`, `spotlessCheck`, `detekt`, `lintDebug`, `jacocoTestReport` pass

## 4. Review fixes

- [x] 4.1 `interrupted()`: hard backstop on `runAttemptCount` (`MAX_UPLOAD_RUN_ATTEMPTS_BACKSTOP` = 10x cap; and failed `rewriteQueueFile` with `runAttemptCount >= MAX_UPLOAD_RUN_ATTEMPTS`); tests in `UploadWorkerForegroundTest`
- [x] 4.2 `parseUploadQueue`: `interruptions` parsed as a clamped Number (Int.MAX_VALUE, Long, 2.0, negative); tests
- [x] 4.3 `CLAUDE.md` and `docs/architecture.md` describe the `interruptions` cap rule and the backstop; design.md updated
- [x] 4.4 `SettingsScreenTest`: no toggleable descendants of `wifiOnlyRow` (unmerged tree) and the row has `Role.Switch`
