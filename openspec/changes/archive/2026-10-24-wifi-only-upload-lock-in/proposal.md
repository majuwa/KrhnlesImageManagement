## Why

GitHub issues #14 and #33 (duplicates) ask for a "Wi-Fi only" upload option. Verification against the code shows the feature already exists (toggle, persistence, `NetworkType.UNMETERED` at enqueue time and for retries, a run-time metered check in the worker). What is missing is a capability spec for the issue's four acceptance criteria and regression tests for the two criteria that are only covered indirectly (persistence in the store, default off at the store level), plus a toggle that TalkBack announces together with its label. This change locks the behaviour in so the issues can be closed.

## What Changes

- Add a `wifi-only-upload` capability spec covering: Settings toggle, persistence, default off, uploads wait for an unmetered network, and the existing safe fallbacks (cross-referencing `failed-photo-retry`).
- Add missing regression tests: `CredentialStore` default `false`, value survives a new store instance, `clear()` resets it; Settings toggle click persists the value; the switch is one labelled toggle node.
- Make the Settings Wi-Fi row a single `toggleable(role = Role.Switch)` node (label and hint merged, `Switch` itself non-interactive) so the switch has an accessible name.
- Fix the retry-budget interaction (user decision): `UploadWorker.interrupted()` gave up when `runAttemptCount + 1 >= MAX_UPLOAD_RUN_ATTEMPTS`, but `runAttemptCount` also grows with metered Wi-Fi-only back-offs and constraint stops, so a Wi-Fi-only upload could be abandoned without any real interruption. Only real interruptions (foreground start/progress refusals) are counted, in an optional top-level `interruptions` counter of the queue file (absent = 0, written atomically via `rewriteQueueFile`, reset for retry files). Metered wake-ups and constraint stops never consume the budget. Existing queue files stay valid.
- Document the behaviour in `docs/architecture.md` (incl. line ~195, "Wi-Fi-only back-offs count too" becomes false) and `README.md` (add a Settings description of the toggle; the usage notes already mention Wi-Fi only).
- Closes both #14 and #33.
- Out of scope (not in the issue's criteria): a "waiting for Wi-Fi" banner or notification, re-constraining already enqueued work when the setting is switched off (possible future fix: `WorkManager.updateWork()`), treating metered Wi-Fi hotspots differently, foreground-service changes.

## Capabilities

### New Capabilities
- `wifi-only-upload`: user-controlled restriction of background uploads to unmetered networks (toggle, persistence, default, enforcement).

### Modified Capabilities

- `video-support`: requirement "Videos are uploaded like photos" (scenario "Repeatedly interrupted" now counts foreground refusals, not WorkManager runs).

The existing `failed-photo-retry` requirement "Retry honours the Wi-Fi-only setting" stays as is and is referenced.

## Impact

- Code: `ui/settings/SettingsScreen.kt` (row semantics), `worker/UploadWorker.kt` and the queue model (`interruptions` counter), `worker/QueueFiles.kt`/retry-file writer if it copies queue fields.
- Tests: `CredentialStoreTest`, `SettingsScreenTest`, `UploadWorkerRunTest`, queue JSON round-trip test.
- Docs: `docs/architecture.md`, `README.md`, `UploadWorker` KDoc.
- No dependency, permission or API changes; the queue JSON gains one optional field.
