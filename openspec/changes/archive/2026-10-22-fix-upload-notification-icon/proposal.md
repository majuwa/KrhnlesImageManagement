# Proposal

## Why

`UploadWorker` uses `R.drawable.ic_launcher_foreground` as the notification small icon (GitHub issue #45). That drawable is a 108dp multi-colour adaptive-icon layer with transparent safe-zone padding. Android renders status-bar small icons as a monochrome alpha mask, so the crown/colour artwork collapses into a blob or a tiny, unrecognisable shape. Notification small icons must be a dedicated flat, single-colour glyph.

## What Changes

- Add a dedicated monochrome vector drawable `ic_notification_upload` (24dp, white fill, transparent background) for notification small icons, using the Material Design `cloud_upload` icon path (the project already depends on `material-icons-extended`).
- Replace all three `setSmallIcon(R.drawable.ic_launcher_foreground)` calls in `UploadWorker` (progress, completion, failure notifications) with the new drawable, referenced through a single shared constant/helper so the three sites cannot drift apart.
- Add a unit test guarding against regressing to the launcher drawable.

## Capabilities

### New Capabilities
- `upload-notifications`: how background-upload notifications (progress, completion, failure) present themselves, starting with the requirement that they use a dedicated monochrome status-bar icon.

### Modified Capabilities
<!-- None: no existing specs in openspec/specs/. -->

## Impact

- Code: `worker/UploadWorker.kt` (3 call sites), new `res/drawable/ic_notification_upload.xml`.
- Tests: new/extended unit test for `UploadWorker` notification construction.
- No API, dependency, permission, or data changes. Visual change only, in the status bar and notification shade.
- Docs: `docs/architecture.md` / `README.md` need no update unless they mention the notification icon (to be checked during apply).
