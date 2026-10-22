# Tasks

## 1. Notification icon asset

- [x] 1.1 Add `app/src/main/res/drawable/ic_notification_upload.xml` (24dp vector, white fill, transparent background, using the Material Design `cloud_upload` path data); verify `./gradlew lintDebug` passes and the drawable previews as a flat glyph.

## 2. Use the icon in UploadWorker

- [x] 2.1 Add a single `NOTIFICATION_ICON` constant in `UploadWorker`'s companion object pointing at `R.drawable.ic_notification_upload`; verify it compiles.
- [x] 2.2 Replace the three `setSmallIcon(R.drawable.ic_launcher_foreground)` calls (progress, completion, failure) with the constant; verify `grep -rn ic_launcher_foreground app/src/main/java` returns nothing.
- [x] 2.3 Add tests in `UploadWorkerIntegrationTest` asserting progress, completion, and failure notifications use the new drawable and not `ic_launcher_foreground`; verify `./gradlew test` passes and the tests fail if the old drawable is restored.

## 3. Quality gates

- [x] 3.1 Run `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport`; verify all pass with no new issues.
- [x] 3.2 Check `README.md`, `CLAUDE.md`, and `docs/architecture.md` for mentions of the notification icon and update if present; verify by grep.
- [x] 3.3 Visually confirm on an emulator or device that the status-bar icon is a legible flat glyph.

## Workflow follow-up

- Close GitHub issue #45 when merged.
- Archive the change after review.
