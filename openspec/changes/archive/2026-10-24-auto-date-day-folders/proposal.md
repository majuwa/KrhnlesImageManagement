## Why

Issues #18 / #37 ask for an "Auto date folders" mode. The issue text was ambiguous about the folder format (`MM` vs `MM-Month`); the shipped implementation used `YYYY/MM-Mon` (English month abbreviation) and queued one WorkManager upload per month. The user decided:

- **A. Day folders:** one folder per capture **day**, `<base>/YYYY/MM/DD/` (e.g. `2026/05/14`), digits only, so no month names or locale can influence remote folder names.
- **B. One upload per selection:** in auto date mode a selection is **one** background upload (one WorkManager work, one queue file) that puts each photo into its own day folder. The user therefore sees one progress banner / foreground notification for the whole selection, one result notification, one "Retry failed" action and one upload-history entry. This consciously deviates from the issue's wording ("separate WorkManager tasks per month"); it is a recorded user decision, not an oversight.

The remaining acceptance criteria (settings toggle, skipped occasion dialog, unchanged default flow, path preview) are already implemented, but several have no regression test and the behaviour is only described in prose. Review findings add: the preview dialog does not scroll for many days, and the capture-day time zone is implicit.

## What Changes

- **Grouping / naming** (`upload/UploadBatch.kt`): capture day = `LocalDate` (already zone-converted when the photo list loads); folder names `YYYY/MM/DD` with manual zero padding (no `DateTimeFormatter`, no month names, no default-locale dependency). Photos ordered by day, within a day by id. Unknown capture date bucket changes from `1970/01-Jan` to `1970/01/01` (`Photo.UNKNOWN_DATE`); real pre-1970 dates keep their real date.
- **One upload per selection:** auto mode resolves the selection into **one** `UploadBatch` carrying a folder per photo plus a history label; `UploadEnqueuer` writes one queue file / enqueues one work for it. Occasion (non-auto) mode and the share target are unchanged (one batch, one folder).
- **Queue format, backward compatible:** an optional per-entry `folderName` and an optional top-level `label`. Queue and retry files persisted before this change (single top-level `folderName`, including old `YYYY/MM-Mon`) upload and retry exactly as today. Every per-photo folder is validated; the worker MKCOLs each distinct folder level once (405 ok) before the first PUT; a retry keeps each failed photo's own folder. `UploadedPhotosRepository` / duplicate marking unchanged; queue file name / location validation (`worker/QueueFiles.kt`) unchanged.
- **Preview dialog** (`AutoDateFolderPreviewDialog`) becomes scrollable with a bounded height so Confirm stays reachable with many days.
- **Time zone made explicit:** `MediaRepository.dateTakenOf(millis, zone = ZoneId.systemDefault())` (testable); the day folder equals the grid's date header.
- User-visible text mentioning the old format is updated: `hint_auto_date_folders`, README (two places), `docs/architecture.md` (two places), the `isValidOccasionName` KDoc, the `UploadBatchTest` comment.
- Existing remote `YYYY/MM-Mon` folders are left untouched (no migration, no merging).
- Specs: new `auto-date-folders` capability; `failed-photo-retry` deltas (retry keeps per-photo folders; per-batch notification scenario no longer talks about months) as full-requirement MODIFIED copies; `credential-storage` delta (preference lifecycle, the only place it is specified). `upload-notifications` (status-bar icon only) is not affected.

Out of scope: auto date mode for the share target (always occasion dialog), configurable format / locale, migrating or merging old month folders, aggregating the in-app progress banner across several concurrently running uploads (kept as today, see design), changing the capture-day zone after load (travel limitation, see design).

## Capabilities

### New Capabilities
- `auto-date-folders`: optional mode that uploads a selection as one background upload in which every photo goes into its `YYYY/MM/DD` capture-day sub-folder of the base folder instead of asking for an occasion name.

### Modified Capabilities
- `failed-photo-retry`: retry keeps each photo's stored folder (per-photo day folders, old single-folder queues unchanged); the per-batch result notification scenario no longer relies on per-month batches.
- `credential-storage`: auto date folders preference lifecycle (reset on logout, not restored).

## Impact

- Production: `upload/UploadBatch.kt`, `upload/UploadEnqueuer.kt` (queue writer), `worker/UploadWorker.kt` (queue parser, per-photo folders, MKCOL of distinct folders, retry writer, history label), possibly a small helper in `data/WebDavClient.kt` for deduplicated MKCOL, `ui/photogrid/PhotoGridScreen.kt` (preview dialog scroll, auto mode passes one batch), `data/MediaRepository.kt` (`dateTakenOf` zone parameter), `res/values/strings.xml` (hint), KDoc in `util/UploadPaths.kt`. No new dependencies, no manifest/backup changes, no change to `UploadHistoryEntry` / `UploadedPhotosStore` / `RetryUploadReceiver` / `QueueFiles.kt`.
- Tests under `app/src/test/...`: `UploadBatchTest`, `UploadEnqueuerTest`, `UploadWorkerRunTest`, `UploadPathsTest`, `MediaRepository` date test, `CredentialStoreTest`, `SettingsViewModelTest`, `SettingsScreenTest`, `PhotoGridViewModelTest`, a `PhotoGridScreen` Compose test.
- Docs: `docs/architecture.md`, `README.md`.
