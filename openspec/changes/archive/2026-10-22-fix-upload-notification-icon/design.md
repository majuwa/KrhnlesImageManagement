# Design

## Context

`UploadWorker` builds three notifications (progress, completion, failure), each calling `setSmallIcon(R.drawable.ic_launcher_foreground)`. That drawable is a 108dp multi-colour adaptive layer; Android masks small icons to alpha only. minSdk is 35, so no legacy PNG fallbacks are needed. See proposal.md for motivation.

## Goals / Non-Goals

**Goals:**
- One dedicated monochrome vector used by all three notifications.
- A test that fails if a notification reverts to the launcher drawable.

**Non-Goals:**
- Redesigning the launcher icon or notification content/actions.
- Changing the retry action's icon (`addAction(0, ...)`).

## Decisions

- **New 24dp vector `ic_notification_upload.xml`, white fill, transparent background, using the Material Design `cloud_upload` icon path.** Notifications need an Android drawable resource, so Compose `Icons.Filled.CloudUpload` (an `ImageVector`) cannot be used directly; instead the Material icon's vector path data is copied into an XML drawable. This matches the app's Material look (`material-icons-extended` is already a dependency, so no new dependency) and stays consistent with in-app icons. Alternative: hand-drawn custom glyph; rejected in favour of the standard Material icon.
- **Single `private const val NOTIFICATION_ICON = R.drawable.ic_notification_upload` in `UploadWorker`'s companion**, used at all three call sites. Alternative: edit each call site inline; rejected because the original bug was three duplicated references.
- **Test via the existing `UploadWorkerIntegrationTest` setup**: capture the posted notifications and assert `smallIcon` resource id equals the new drawable (Robolectric). Alternative: a source-text grep test; rejected as brittle.

## Risks / Trade-offs

- [Glyph looks poor at 24dp] → Check on an emulator/device status bar and shade during apply.
- [Robolectric cannot expose the small icon id] → Fall back to asserting on `Notification.smallIcon.resId` via `NotificationCompat` builder output, or extract a pure `buildNotification` helper to test.
