# Proposal

## Why

The blur review decides "blurry" with the hardcoded `BlurDetector.DEFAULT_THRESHOLD` (3000 on the Laplacian-variance score). Softer cameras get falsely flagged, and other users want stricter or looser results. GitHub issues #20 and #39 (identical) ask for a user-adjustable sensitivity.

## What Changes

- New **Blur sensitivity** control in the logged-in Settings content: a three-position slider (Low / Medium / High, Medium = today's 3000), persisted by enum name next to the other preferences (`wifiOnly`, `autoDateFoldersEnabled`) in the existing preferences store behind `CredentialRepository`.
- The blur review passes the persisted level source to `BlurViewModel` per call (like `repositories`); the scan reads the level once after it started and filters with the mapped threshold instead of the constant.
- A changed setting applies to the **next** scan only; existing results are not re-filtered (issue acceptance criterion).
- Out of scope: custom numeric thresholds, per-album settings, re-filtering cached scores, changing the scoring algorithm, a "restore defaults" action, showing the applied level in the review, a new DataStore or backup rule changes.
- Note: the setting lives in the credentials DataStore, which is excluded from backup and device transfer, so it is not restored on a new device (defaults to Medium).

## Capabilities

### New Capabilities
- `blur-sensitivity`: configurable blur sensitivity levels, their persistence and use by the blur scan.

### Modified Capabilities

## Impact

- `BlurSensitivity` enum (`model/`), `CredentialRepository` / `CredentialStore` (`blurSensitivity` flow + `saveBlurSensitivity()`), `FakeCredentialRepository`.
- `BlurViewModel` stays a plain `ViewModel` (constructor unchanged); `findBlurryPhotos` / `retryFindBlurryPhotos` gain a sensitivity-source argument; `BlurReviewScreen` and the `ROUTE_BLUR` destination supply it.
- `SettingsViewModel`, `SettingsScreen`, `strings.xml`.
- No new DataStore, no `BackupRulesTest` / backup-rule change, no new dependencies.
- Docs: README.md, CLAUDE.md, docs/architecture.md.
