Write the failing tests of each section first, then the production change.

## 1. Capture day and naming (`upload/UploadBatch.kt`, `data/MediaRepository.kt`)

- [x] 1.1 `MediaRepository.dateTakenOf(dateMillis: Long?, zone: ZoneId = ZoneId.systemDefault())` (behaviour unchanged, call sites keep the default)
- [x] 1.2 Replace `resolveAutoDateUploadBatches()` by `resolveAutoDateUploadBatch(photos): UploadBatch` (one batch): sort by `dateTaken` then id, folder per photo `YYYY/MM/DD` with manual zero padding (e.g. `"%04d/%02d/%02d"` with `Locale.ROOT`), history label = single day folder or `"<first> – <last>"`; remove `monthFolderFormatter` / `YearMonth`; no class-init formatter capturing the default locale. Make `UploadBatch` a sealed target: `Occasion(folderName)` or `PerPhoto(folders, label)` (folders same size/order as `photos`, non-blank, exactly one target enforced with `require`); occasion batches unchanged
- [x] 1.3 Update `hint_auto_date_folders` in `strings.xml` (`YYYY/MM/DD`, example `2026/05/14`, one upload per selection) and the KDoc of `isValidOccasionName` in `util/UploadPaths.kt`; check `values-*` for translations (none expected)
- [x] 1.4 Confirm no other code references the old format (`grep MM-MMM`, `MM-Mon`, `01-Jan`, `resolveAutoDateUploadBatches`)

## 2. Naming / grouping tests

- [x] 2.1 `UploadBatchTest`: update old-format assertions; fix the comment at :41 ("Known behaviour, unchanged by the accessibility work") to describe the `1970/01/01` bucket
- [x] 2.2 Format zero-padded (2026-03-09 -> `2026/03/09`, 2026-12-31)
- [x] 2.3 Locale: default locale `Locale.forLanguageTag("ar-EG")` (or `"ar-u-nu-arab"`) with the asserted precondition `String.format(locale, "%d", 1) != "1"`, and `Locale.GERMANY`; restore the default locale in `@After`
- [x] 2.4 Day, month and year boundaries (2026-01-31 / 2026-02-01, 2025-12-31 / 2026-01-01) give different folders, photos ordered chronologically regardless of input order
- [x] 2.5 Within a day photos ordered by id; multi-day selection gives exactly one batch; labels for one day and for several days
- [x] 2.6 Unknown date -> `1970/01/01`; pre-1970 date keeps its real date
- [x] 2.7 `previewUploadPath` with leading/trailing slashes and blank base; every resolved folder passes `isValidFolderPath`; `UploadPathsTest` :29 optionally adds `2026/05/14`
- [x] 2.8 `dateTakenOf` test (pure, explicit zone): Europe/Berlin 2026-05-13T22:30Z -> 2026-05-14; DST day 2026-03-29T00:30Z -> 2026-03-29 and 2026-03-29T22:30Z -> 2026-03-30; America/Los_Angeles 2026-05-14T06:59Z -> 2026-05-13; null / 0 -> `UNKNOWN_DATE`; and the day folder of such a photo equals the date the grid groups it under (same `dateTaken`)

## 3. Setting persistence and Settings UI (reuse existing tests)

- [x] 3.1 `CredentialStoreTest`: `autoDateFoldersEnabled` defaults to false, round-trips true/false, and `clear()` resets it to false
- [x] 3.2 `SettingsViewModelTest`: toggling persists; assert the value stored in `FakeCredentialRepository.saveAutoDateFolders`, not the optimistic UI state (not the no-op fake near :781)
- [x] 3.3 `SettingsScreenTest`: update the hard-coded `HINT_AUTO_DATE_FOLDERS` constant (:36-37) to the new hint; keep :276-287; add default-off and "recreated with stored true shows on" if missing

## 4. Enqueue, grid flow and preview

- [x] 4.1 `UploadEnqueuer.writeQueueFile`: batch without per-photo folders writes today's JSON unchanged; batch with per-photo folders writes `label`, `folderName` on every entry and no top-level `folderName`
- [x] 4.2 `PhotoGridScreen`: auto mode confirm calls `onStartUpload(listOf(resolveAutoDateUploadBatch(selected)))`; preview lists distinct day folders; `AutoDateFolderPreviewDialog` path list scrolls (`verticalScroll` or `LazyColumn` with `heightIn(max = …)`), buttons outside the scrolling area
- [x] 4.3 `UploadEnqueuerTest`: existing `oneWorkPerBatch` stays green; new: one multi-day auto batch -> exactly one work and one queue file with per-entry folders + label and `!has("folderName")` at the top level; occasion batch JSON unchanged
- [x] 4.4 `PhotoGridViewModelTest` (existing :214-244): add not-configured + toggle on -> only the not-configured dialog
- [x] 4.5 Compose test harness for `PhotoGridScreen` (Robolectric, no real DataStore): `WorkManagerTestInitHelper.initializeTestWorkManager(...)` or an injected test `WorkManager` passed to `PhotoGridViewModel`, `FakeMediaRepository`, `FakeUploadedPhotosRepository`, `FakeCredentialRepository`; `shadowOf(application).grantPermissions(READ_MEDIA_IMAGES, POST_NOTIFICATIONS)`; `LocalAsyncImagePreviewHandler` + `LocalInspectionMode`; wait until `isConfigured` (Eagerly, initial `false`) is `true` and `uploadBaseFolder` (WhileSubscribed, initial `""`) is loaded before tapping upload; select photos via the grid's `toggleable` nodes
- [x] 4.6 Same harness, toggle on, two days: no occasion text field, preview lists both paths with the base folder; Confirm calls `onStartUpload` once with one batch whose photos map to `2026/04/30` / `2026/05/01` and clears the selection; Cancel calls nothing and keeps the selection
- [x] 4.7 Preview with 40 distinct days: the last path can be scrolled to (`performScrollTo()` / `assertIsDisplayed()` after scrolling) and the Upload button is displayed
- [x] 4.8 Same harness, toggle off -> occasion dialog, no preview, confirming gives one batch with the typed name (existing `OccasionDialogTest` stays green)
- [x] 4.9 Fallback only if 4.5 cannot compose in isolation: cover the flow via `PhotoGridViewModel` + a dialog-only Compose test and note the reason here (not needed: `PhotoGridScreenAutoDateTest` composes the real screen; only the 40-day test selects via `toggleDateSelection` because the lazy grid composes only visible items)

## 5. Worker (`worker/UploadWorker.kt`, optional helper in `data/WebDavClient.kt`)

- [x] 5.1 Parser: decide format from content (per-photo if `label` present or any entry has `folderName`: every entry needs a non-blank string `folderName`, top-level `folderName` must be absent, else malformed and file deleted; otherwise old top-level format). Read `folderName` / `label` via `opt(key)` + `is String` (never `getString`/`optString`; absent vs `JSONObject.NULL` distinct). Non-string label dropped, label capped at 200 chars before recording; history label = `label` ?: top-level `folderName` ?: `""`
- [x] 5.2 Validation before any request: top-level rule unchanged; entry folder non-blank + `isValidFolderPath`; any invalid -> `FolderCreationError.InvalidFolderName`, no request
- [x] 5.3 MKCOL every distinct effective folder level by level below `uploadPathPrefix`, each cumulative path at most once per run (405 ok); any failure -> whole work fails before any PUT (existing failure notification, history all failed, no retry file); then PUT each photo into its own folder, one progress over all photos
- [x] 5.4 Retry file: failed entries keep their entry `folderName`; top-level `folderName` and `label` copied if present (old queues -> old-format retry files)
- [x] 5.5 `UploadWorkerRunTest`: keep `retryRun_uploadsOnlyListedPhotosToStoredAutoDateFolderAndMarksNewIds` (:301-310, `2026/05-May`) unchanged as the old-format test
- [x] 5.6 New (MockWebServer `Dispatcher` answering per request path/method, with a helper that writes per-entry queue files; the 405 answer on shared levels is configured per path, MKCOL 403 only for the second day's path): per-photo queue `2026/04/30` + `2026/05/01` with base `Photos/Krohn` -> MKCOL order `Photos`, `Photos/Krohn`, `…/2026`, `…/2026/04`, `…/2026/04/30`, `…/2026/05`, `…/2026/05/01` (no duplicates), then PUTs into the right folders; 405 on existing levels does not fail; one result notification; one history entry with the label
- [x] 5.7 New: same with a non-root server URL `server.url("/remote.php/dav/files/u/")` -> all MKCOL/PUT paths below `/remote.php/dav/files/u/`
- [x] 5.8 New: retry of a per-photo queue with failed photos in `2026/05/13` and `2026/05/14` -> retry file entries keep `2026/05/13` / `2026/05/14` and label; running it PUTs each into its folder and marks only new successes
- [x] 5.9 New (use the dispatcher/helper from 5.6): 
  - invalid entry `2026/../x` placed *last* after valid days -> zero recorded requests, one history entry with all photos failed, queue file deleted
  - entry `folderName` = null (`JSONObject.NULL`), `5`, `[]`, `{}` -> malformed, file deleted, no request; non-string `label` is dropped (history label `""`), numeric label not coerced to `"5"`
  - entry without folder and no top-level folder -> malformed, file deleted
  - mixed file (one entry with `folderName`, another without; and top-level `folderName` next to entry folders; and `label` with entries lacking folders) -> malformed, file deleted, nothing uploaded
  - label longer than 200 chars -> recorded truncated to 200
  - MKCOL 403 on the second day only -> no PUT, failure notification
- [x] 5.11 Round trip: queue file written by `UploadEnqueuer.writeQueueFile` for a two-day auto batch (including an unknown-date photo) run by `UploadWorker` -> expected MKCOL/PUT paths and the history label (`1970/01/01 – 2026/05/01`)
- [x] 5.10 `WebDavClientTest` 405 stays unchanged; add a test for the dedup helper if one is added

## 6. Docs and checks

- [x] 6.1 `docs/architecture.md`: :167 (retry file format description: now `folderName` or per-photo `folderName` + `label`, content-based format detection, strict string reads), :152 (accessibility section, `1970/01-Jan` -> `1970/01/01`), :201 upload paths (per-day `YYYY/MM/DD`, digits only, one work per selection with per-photo folders, queue format and compatibility, distinct MKCOL, history label, banner unchanged for concurrent works, capture-day zone + travel limitation, no migration of old month folders, share target excluded); data-flow step 3 wording
- [x] 6.2 `README.md` lines 11 and 66: new format, one upload per selection; `CLAUDE.md` rule 4 only if the queue-format convention should be recorded there (one short clause)
- [x] 6.3 `./gradlew test`
- [x] 6.4 `./gradlew spotlessCheck`
- [x] 6.5 `./gradlew detekt` (no new issues)
- [x] 6.6 `./gradlew lintDebug` (zero issues, no baseline, no suppressions)
- [x] 6.7 `./gradlew jacocoTestReport`

## 7. Review fixes

- [x] 7.1 `UploadQueue.historyLabel` caps only `label` (200 chars), never the top-level `folderName`; tests for both
- [x] 7.2 Test: per-photo queue with one failed photo -> exactly one result notification, action `notification_retry_failed` with the failed count
- [x] 7.3 Test: `UploadEnqueuerTest` single-day auto batch -> one work, `label == "2026/05/14"`
- [x] 7.4 `rawQueueFile` dead `prefix` parameter removed
- [x] 7.5 `PhotoGridScreen` memoizes `resolveAutoDateUploadBatch` (keys `selectedPhotoIds`, `photosByDate`)
- [x] 7.6 design.md records the `" – "` separator as locale-free data
- [x] 7.7 `docs/architecture.md`: `createDirectory(ies)` return `Result` with `WebDavHttpException` failure
- [x] 7.8 Security: new queue / retry files go to `noBackupFilesDir`; `QueueFiles.kt` accepts it and the legacy `filesDir`, nothing else; tests (new location, legacy file runs / retries, other parent rejected); CLAUDE.md + architecture.md updated; spec delta in failed-photo-retry
- [x] 7.9 Security: `UploadHistoryStore` logs only the exception type on parse failure
