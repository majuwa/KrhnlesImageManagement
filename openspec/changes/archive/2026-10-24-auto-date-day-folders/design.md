## Context

The feature exists: `autoDateFoldersEnabled` (`CredentialStore`, plain DataStore edit), `AutoDateFoldersSetting` (Settings), `PhotoGridViewModel.onUploadRequested()` (preview vs occasion dialog), `resolveAutoDateUploadBatches()` / `previewUploadPath()` (`upload/UploadBatch.kt`), `UploadEnqueuer` (one queue file + one `UploadWorker` per batch), `WebDavClient.createDirectory()` (MKCOL per segment, 405 ok), `UploadWorker` (one top-level `folderName` per queue, `WebDavConfig.uploadPathPrefix + folderName`, `isValidFolderPath` allows `/`, retry file copies `folderName`, history entry `occasionName = folderName`). Today auto mode groups by `YearMonth`, names `YYYY/MM-Mon` and queues one work per month.

User decisions: **A** per-day folders `YYYY/MM/DD`; **B** one upload per selection with a folder per photo (deviates on purpose from the issue's "separate WorkManager tasks per month").

## Goals / Non-Goals

**Goals:** per-day digit-only folders; one work per selection in auto mode with a backward-compatible queue format; scrollable preview; explicit capture-day zone; pin each acceptance criterion with a test; write the contract.
**Non-Goals:** share-target auto mode, month names / locale-specific names, configurable formats, migration of existing remote month folders, downgrade compatibility of queue files (old app versions fail and delete per-photo queues, nothing is uploaded), aggregating the in-app banner over concurrent works, UI changes beyond the hint text and the dialog scroll.

## Decisions

### Capture day and naming
- **Capture day** is `Photo.dateTaken`, the `LocalDate` that `MediaRepository.dateTakenOf()` computes once when the photo list loads by converting `DATE_TAKEN` to the device default zone. It is the same date as the grid's date header (the header groups by the same field). Refactor to `dateTakenOf(dateMillis: Long?, zone: ZoneId = ZoneId.systemDefault())` so the conversion is testable; behaviour unchanged.
- **Travel limitation (accepted):** the day is the device's zone *at load time*, not the zone where the photo was taken. A photo taken at 23:30 in Tokyo and loaded on a device set to Europe/Berlin lands in the previous day; reloading after a zone change can move a not-yet-uploaded photo to another day. No EXIF offset is read.
- **Folder name:** `"%04d/%02d/%02d"` from `year`, `monthValue`, `dayOfMonth` with manual padding or `String.format(Locale.ROOT, ...)`. No `DateTimeFormatter`/month text and no class-init formatter capturing the default locale. The locale test (default locale `ar-EG` / `ar-u-nu-arab` with the precondition that `String.format(locale, "%d", 1) != "1"`, plus `Locale.GERMANY`, restored in `@After`) guards against reintroducing locale-dependent formatting.
- **Order:** photos sorted by capture day ascending, within a day by id (the date carries no time). Distinct folders in the preview and the queue are chronological.
- **Unknown date** (`Photo.UNKNOWN_DATE`, 1970-01-01) falls naturally into `1970/01/01`; real pre-1970 dates (negative epoch) keep their date.

### One upload per selection (decision B)
- `resolveAutoDateUploadBatch(photos): UploadBatch` (replaces the per-month list) returns **one** batch: photos in the order above, a folder per photo (e.g. an optional `photoFolderNames: List<String>?` on `UploadBatch`, same size/order as `photos`, `require`d) and a history label. Occasion mode keeps `UploadBatch(folderName, photos)` without per-photo folders. `UploadBatch` is pinned as a sealed target with exactly one of two shapes: `Occasion(folderName)` (today's behaviour) or `PerPhoto(folders: List<String>, label: String)` (`folders` same size and order as `photos`, every entry non-blank; enforced with `require`). A batch can never carry both a top-level folder and per-photo folders. Further naming is an implementation detail; the queue JSON below is the contract.
- **History label** (stored in `UploadHistoryEntry.occasionName`, no model change): the single day folder if the selection covers one day (`2026/05/14`), otherwise `"<first> – <last>"` of the chronologically first and last day folder (`2026/04/30 – 2026/05/01`; includes `1970/01/01` when unknown-dated photos are part of it). Locale-free data, computed at resolve time; never used as a path. The `" – "` separator is deliberately literal data and an intentional exception to the string-resource rule: it is persisted and displayed verbatim in the history, not UI chrome, and must not change with the UI locale. The label is capped to 200 characters in `historyLabel`; a single-folder queue's top-level `folderName` is never capped.
- `UploadEnqueuer` is unchanged in structure ("one work per non-empty batch"); auto mode simply passes one batch, so the toast reads "Queued 1 upload for N photos". One work → one foreground/progress notification, one result notification (tag = queue file name), at most one retry action, one history entry.
- **Banner:** `PhotoGridViewModel.uploadProgress` keeps `firstOrNull { RUNNING }`. With one work per selection it covers the whole selection. If several works run concurrently (two selections started back to back, share target + grid), the banner shows one of them, as today — not aggregated (out of scope; summing progress of running works ignores enqueued ones and would need its own spec).

### Queue format (v1 compatible, no version field)
```json
// occasion / old format — written and read exactly as today
{"folderName": "Trip", "photos": [{"id": 1, "uri": "...", "mimeType": "...", "displayName": "a.jpg"}]}
// auto date format
{"label": "2026/04/30 – 2026/05/01",
 "photos": [{"id": 1, "uri": "...", "mimeType": "...", "displayName": "a.jpg", "folderName": "2026/04/30"},
            {"id": 2, "uri": "...", "mimeType": "...", "displayName": "b.jpg", "folderName": "2026/05/01"}]}
```
- **Writer:** a batch without per-photo folders writes today's format byte-for-byte; a batch with per-photo folders writes `label`, a `folderName` on every entry and **no** top-level `folderName` (so a missing entry folder can never silently fall back to the base folder).
- **Reader decides the format from the content, never from a version field:**
  - **Per-photo format** if `label` is present (any value, even a non-string) or any entry has a `folderName` key: then *every* entry MUST have a non-blank JSON string `folderName` and the top-level `folderName` MUST be absent. Anything else (an entry without folder, a top-level `folderName` next to per-photo data, a mixed file) → malformed queue (work fails, file deleted, nothing uploaded — same as an unparseable file).
  - **Old format** otherwise: top-level `folderName` as today (blank = base folder; absent → malformed as today).
  - **Strict string reads:** Android/Robolectric `org.json` `getString`/`optString` coerce non-strings (`5` → `"5"`, `JSONObject.NULL` → `"null"`). The reader reads `folderName` and `label` with `opt(key)` and an `is String` check; absent (`opt` returns null) and `JSONObject.NULL` are distinct, and `JSONObject.NULL`, numbers, arrays and objects are all "present but not a string". A non-string entry or top-level `folderName` → malformed. A non-string `label` is not a path and is **dropped** (history label falls back to `""`) once the per-photo rules above are satisfied.
  - **History label** = `label` (strings only, truncated to 200 chars before it is recorded) ?: top-level `folderName` ?: `""`.
- **Validation before any request:** top-level `folderName` keeps today's rule (blank = base folder itself, otherwise `isValidFolderPath`). An entry `folderName` must be non-blank and pass `isValidFolderPath`. Any invalid effective folder fails the whole work with `FolderCreationError.InvalidFolderName` without a request (as the existing `..` test). `label` is never part of a path, so it is not path-validated. Queue file name / location validation (`isWorkerQueueFile`, `isRetryQueueFile`) is unchanged and applies to both formats; `RetryUploadReceiver` does not parse the content and is unchanged.
- **Persisted old files:** queue and retry files written before the update (single top-level `folderName`, e.g. `2026/05-May` or an occasion) take the "else" branch and behave exactly as today, including their retry files.

### Folder creation and upload
- Before the first PUT the worker collects the distinct effective folders in first-occurrence (chronological) order and creates them level by level below `config.uploadPathPrefix`; every cumulative path is MKCOLed **at most once per run** (shared levels such as the base folder, year and month are not repeated); 405 = exists, ok. E.g. base `Photos/Krohn`, folders `2026/04/30`, `2026/05/01` → `Photos`, `Photos/Krohn`, `…/2026`, `…/2026/04`, `…/2026/04/30`, `…/2026/05`, `…/2026/05/01`. Implement via a small deduplicating helper (e.g. `WebDavClient.createDirectories(paths)` or a shared `created` set); `createDirectory()` keeps its current contract for single-folder callers.
- **Failure:** if any folder cannot be created, the whole work fails before any PUT, exactly like today's single-folder failure (failure notification with the `FolderCreationError` text, history entry with all photos failed, no retry file). Rationale: same semantics as today, no partially created selection to explain.
- Each photo is PUT into its own effective folder; progress counts all photos of the work (`KEY_PROGRESS` / `KEY_TOTAL` unchanged).
- **Round trip:** a queue produced by `UploadEnqueuer.writeQueueFile` for an auto batch must be consumable by `UploadWorker` unchanged (writer and reader are tested together, see task 5.11).
- **Retry file:** contains only failed entries; each keeps its own entry `folderName` if it had one, the top-level `folderName` and `label` are copied if present. Old-format queues therefore produce old-format retry files; auto queues produce per-entry retry files with the original label. Retry history entries use the same label rule.
- `UploadedPhotosRepository.markAsUploaded(successIds)` and the `-1` id sentinel are unchanged.

### Other
- **Preview dialog** (`AutoDateFolderPreviewDialog`): the path list scrolls (`verticalScroll` on the text content, or a `LazyColumn` with `heightIn(max = …)`), message stays on top, Confirm/Cancel stay visible. It lists one path per distinct day folder; the plural message ("split into %d folders") stays generic.
- **Setting lifecycle** is specified only in the `credential-storage` delta.

## Criterion -> test mapping

| Criterion | Test |
|---|---|
| Default off; round trip; reset by logout | `CredentialStoreTest` (new: default, round trip, `clear()` resets to off) |
| Settings toggle, hint, persists | `SettingsScreenTest` (existing :276-287; update the hard-coded `HINT_AUTO_DATE_FOLDERS` at :36-37; add default off); `SettingsViewModelTest` (assert `FakeCredentialRepository.saveAutoDateFolders`, not optimistic state; not the no-op fake near :781) |
| Toggle on skips occasion dialog; not configured | `PhotoGridViewModelTest` (existing :214-244; new not-configured case) |
| Preview / cancel / confirm one batch with per-photo folders; toggle off | New `PhotoGridScreen` Compose test (harness in tasks 4.2) + existing `OccasionDialogTest` |
| Preview scrolls (40 days) | Same Compose harness or dialog-level Compose test |
| Format, boundaries, order by id, locale, unknown date, pre-1970, label | `UploadBatchTest` |
| Capture day zone = header date | `MediaRepository` `dateTakenOf` test (Berlin, DST, Los Angeles) |
| One work per selection, queue format | `UploadEnqueuerTest` (existing `oneWorkPerBatch` stays; new auto batch → one work, JSON per-entry folders + label, no top-level `folderName`; occasion JSON unchanged) |
| Distinct MKCOL, per-photo PUT, non-root URL, failure semantics, validation | `UploadWorkerRunTest` (new) |
| Old queues and retry files unchanged | `UploadWorkerRunTest` existing :301-310 kept as old-format test |
| Retry keeps per-photo folders | `UploadWorkerRunTest` (new retry with `2026/05/14` entries) |
| Strict string / format detection, label cap | `UploadWorkerRunTest` (malformed matrix, mixed file, non-string and long label) |
| Writer -> worker round trip | `UploadWorkerRunTest` (queue from `UploadEnqueuer.writeQueueFile`) |

## Risks / Trade-offs

- Deviation from the issue wording (one work per selection instead of per month) — user decision B. Large multi-day selections are one long work; a folder error on any day fails the whole selection (same as today for one folder).
- A transient MKCOL failure on any day fails the whole multi-day selection (no partial upload); the user must re-select and upload again. Accepted, same semantics as today for one folder, larger blast radius.
- If WorkManager restarts or retries a work (process death, constraint change), already uploaded photos of that work are PUT again (existing behaviour, `markAsUploaded` happens at the end); with one work per selection the repeated amount is larger.
- Downgrade: an older app version reading a new-format (per-photo) queue file finds no top-level `folderName`, treats it as malformed, fails and deletes it. This is safe (nothing is uploaded to the base folder) but the photos are silently not uploaded.
- Banner shows only one of several concurrently running works (unchanged).
- Albums tab lists the base folder with `Depth: 1`, so year folders (`2026`) appear as albums and contain only month folders, no photos directly; browsing day folders as albums is not supported (accepted, unchanged).
- A real photo dated local 1970-01-01 lands in the unknown bucket `1970/01/01` (already the case for the grid header).
- Travel / zone limitation (see Decisions).
- Mixed remote layout: old `YYYY/MM-Mon` folders and new `YYYY/MM/DD` folders coexist under the same year; no migration (accepted).
- Out of scope, noted: `setAutoDateFoldersEnabled` save has no failure handling; the DataStore read uses `it[KEY]` rather than a type-safe `asMap()[KEY] as? Boolean`; the worker re-reads the base folder at run time, so the real target can differ from the preview if the base folder changed meanwhile.
