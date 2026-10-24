## Context

Verified against the code (all met unless noted):
- Toggle: `SettingsScreen` shows "Upload on Wi-Fi only" with hint when logged in (`SettingsScreenTest` checks it is displayed).
- Persistence: `CredentialStore.saveWifiOnly` writes `wifi_only` to the credentials DataStore; no direct store-level test exists (gap: test only).
- Default: `?: false` in `CredentialStore.wifiOnly`, `SettingsUiState.wifiOnly = false`; `SettingsViewModelTest` covers the view model default, the store default is untested.
- Constraint: `uploadConstraints(true)` sets `NetworkType.UNMETERED`; used by `UploadEnqueuer` (original upload) and `handleRetry`; tested in `UploadConstraintsTest`, `UploadEnqueuerTest`, `RetryUploadReceiverTest`.
- Setting switched on after enqueue: `UploadWorker.doWork` returns `Result.retry()` on a metered active network (`UploadWorkerRunTest`).
- Unreadable setting: bounded read, falls back to unmetered.
- Gaps found: no a11y label association for the switch (the `Switch` is a separate focus target without name); no click-persists UI test; README has no Settings description of the toggle (usage notes at ~lines 79/82 already mention Wi-Fi only, so add a Settings section without duplicating them).
- Not covered and not required: toggling off after enqueue leaves already enqueued UNMETERED work waiting until an unmetered network (or user retry); no "waiting for Wi-Fi" UI.
- Retry budget bug: `interrupted()` uses `runAttemptCount + 1 >= MAX_UPLOAD_RUN_ATTEMPTS`; `runAttemptCount` increments on every start incl. the metered `Result.retry()` (UploadWorker.kt ~128) and constraint stops, so Wi-Fi-only uploads can exhaust the budget without any refusal.

WorkManager facts (context7 / Android docs): `runAttemptCount` increases on every retry or stop and the exponential back-off delay is capped by WorkManager (5 h). `NetworkType.UNMETERED` is satisfied by networks with `NET_CAPABILITY_NOT_METERED`, i.e. it means "unmetered", not "Wi-Fi": a Wi-Fi hotspot flagged metered does not satisfy it, and an unmetered cellular plan does. When a constraint stops being met while work runs, WorkManager stops the worker and reschedules it; it resumes when the constraint holds again. Constraints are fixed in the `WorkRequest` at enqueue time, which is why the worker re-checks the setting at run time.

## Goals / Non-Goals

**Goals:** a spec and regression tests for the four acceptance criteria; one accessibility fix on the toggle row; stop metered wake-ups and constraint stops from consuming the interruption budget; docs.
**Non-Goals:** new UI for waiting state; re-enqueueing, cancelling or updating existing work on toggle (`WorkManager.updateWork()` could change the constraints of pending work in the future); other worker or enqueuer behaviour changes; the same separate-`Switch` accessibility gap in `AutoDateFoldersSetting`, which is left as is.

## Decisions

- Keep `UNMETERED` (as the issue proposes) rather than `NetworkType.CONNECTED` plus a Wi-Fi transport check: it honours the user's metered-hotspot flag, which is the privacy-friendly reading. The hint text already says "unmetered (Wi-Fi)". Alternative (transport check) rejected: WorkManager cannot express it.
- Do not re-constrain enqueued work when the toggle is switched off. The cost is that such work waits for an unmetered network; the user can retry. Doing better would need cancelling and re-enqueueing queue files, which exceeds the issue.
- Accessibility: the `Row` gets `Modifier.toggleable(value = uiState.wifiOnly, role = Role.Switch, onValueChange = viewModel::setWifiOnly)` (merges descendants, so label and hint are the node text), the inner `Switch` has `onCheckedChange = null`, `testTag("wifiOnlyRow")`, and the row keeps a 48 dp minimum height (`minimumInteractiveComponentSize` or padding).
- Retry budget: a new optional top-level `interruptions` Int in the queue JSON (absent = 0, so old files and retry files written by older versions work). `interrupted()` is called only for a real foreground refusal; it reads `queue.interruptions`, and if `interruptions + 1 < MAX_UPLOAD_RUN_ATTEMPTS` it rewrites the queue file atomically with `interruptions + 1` (existing `rewriteQueueFile`, same tmp + rename) and returns `Result.retry()`; otherwise it gives up as today (failure notification + retry file). Retry files start at 0 (a fresh budget: the user explicitly retries). `runAttemptCount` is no longer used for the cap. The metered `Result.retry()` and WorkManager constraint stops do not touch the counter. The back-off delay still grows with those wake-ups (WorkManager-controlled, up to 5 h); accepted and documented in README/architecture. If the counter rewrite fails, the run still returns `Result.retry()` while `runAttemptCount < MAX_UPLOAD_RUN_ATTEMPTS` (rather than risking a lost upload); beyond that, or at `runAttemptCount >= 10 * MAX_UPLOAD_RUN_ATTEMPTS` (`MAX_UPLOAD_RUN_ATTEMPTS_BACKSTOP`) regardless, it gives up like the normal cap, so an unwritable queue file cannot retry forever. Metered wake-ups never reach `interrupted()`. `interruptions` is parsed as any JSON number and clamped to `0..MAX_UPLOAD_RUN_ATTEMPTS` (no overflow).
- New capability spec rather than extending `failed-photo-retry`, whose requirement stays authoritative for retry and run-time check.

## Risks / Trade-offs

- Making the whole row clickable enlarges the touch target; acceptable and standard.
- A metered Wi-Fi hotspot never uploads while the option is on; documented in the hint/README.
