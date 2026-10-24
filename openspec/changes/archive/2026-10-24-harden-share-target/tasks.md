## 1. Parsing and validation

- [x] 1.1 Refactor `ShareIntentParser` into pure `EXTRA_STREAM` extraction (hostile-extra safe incl. `BadParcelableException`), dedupe, cap 100 with rejected/truncated counts; set `id = -1` for shared entries (fixes `id = index`)
- [x] 1.2 Image content-type check (`image/[a-z0-9.+-]+`, else `intent.type`, else drop), `query()` on IO catching `SecurityException` / `IllegalArgumentException` / `IllegalStateException`, unique de-duplicated file names (timestamp + short uuid)
- [x] 1.3 Extend `ShareIntentParserTest` (Robolectric, `ShadowContentResolver.registerInputStream`): non-image, file://, own authority, duplicates, count cap, wrong extra types, malicious name, `id == -1`; a share never calls `markAsUploaded`

## 2. Staging and queue format

- [x] 2.1 Add `ShareStaging` (copy with 100 MiB per file / 1 GiB per share byte limits, SIZE pre-skip, free-space check, 2 GiB folder cap, stream closed on cancel, partial/batch cleanup, UUID name + canonical-parent validation, fail-closed 7-day sweep with injectable clock/dir) with unit tests incl. endless stream for size cap and retry-file-only orphan deletion
- [x] 2.2 Add optional `staged` to `UploadQueue` entries and `toRetryJson`; accept only UUID + canonical parent + `id = -1`; worker reads only the staged file (no `uri` fallback, never touches `contentResolver`); tests in `UploadWorkerRunTest` / retry tests incl. stopped-and-rerun worker uploads all photos from staging
- [x] 2.3 One cleanup helper (delete queue file's staged files except those in a new retry file) used at all `queueFile.delete()` sites (`UploadWorker.kt:95,109,125`) and the NotConfigured path; no per-success deletion

## 3. Share flow UI

- [x] 3.1 Add `ShareViewModel` (public `(Application)` constructor, `ViewModelFactoryRegressionTest`): states Preparing/NotConfigured/Ready/Copying(x of n)/Done/Error, Job latch, cancel + cleanup in `finally` and `onCleared()`, enqueue-failure cleanup, one-shot finish, 3 s config read defaulting to configured
- [x] 3.2 Update `MainActivity` and `ShareReceiverScreen`: "Preparing x of n" with Cancel, Back cancels the copy, NotConfigured Toast/dialog (`FLAG_ACTIVITY_NEW_TASK` if an open-app action exists), no photos -> Toast + `finish()`
- [x] 3.3 Strings (plurals / format) in `strings.xml`
- [x] 3.4 Robolectric tests: dialog count, confirm enqueues exactly once, `ActivityScenario.recreate()` during a gated copy with injected dispatcher, cancel enqueues nothing and leaves no files, not configured, skipped-item message, auto date on still uses occasion

## 4. Docs and checks

- [x] 4.1 Update `README.md` (7-day retry window), `CLAUDE.md` (rule 4: staged share files exception), `docs/architecture.md`
- [x] 4.2 Run `./gradlew test`, `spotlessCheck`, `detekt`, `lintDebug`, `jacocoTestReport`

## 5. Review fixes

- [x] 5.1 C1: type resolution — provider type null / `application/octet-stream` / wildcard → `DISPLAY_NAME` extension via `MimeTypeMap`, else a concrete intent type (never `image/*`); tests for "provider null + intent `image/*` + `.jpg`" and octet-stream cases (spec scenarios added)
- [x] 5.2 S3: allow-list raster types only (`SharedImageType`: jpeg, png, gif, webp, heic, heif, avif, bmp, dng; aliases normalized; SVG dropped); remote name ends with an extension of the validated type (appended otherwise)
- [x] 5.3 C2 + S5: sweep judges retry files by their own mtime and keeps retry files with unfinished / unknown unique work (`hasUnfinishedRetryWork`, bounded); `handleRetry` refreshes the retry file's mtime; tests "old staged + young retry file kept", running / unknown work kept, retry touch
- [x] 5.4 C3: `UploadEnqueuer` maps every non-cancellation exception to `onFailure` (no crash, staged files deleted, toast); test with a throwing WorkManager
- [x] 5.5 C4: all items skipped → `ShareError.NothingCopied` (`share_nothing_copied`); `share_copy_failed` only for space / write failures; skip reasons `SkipReason` → `share_copy_skipped` / `share_over_share_limit` plurals via `shareFinishMessage()`; tests
- [x] 5.6 C5: no orphan when a completed copy races a cancellation (`abandonableCall` `onAbandoned` deletes the file; the ViewModel records names without a suspension point); test
- [x] 5.7 C6: `OccasionDialog` name in `rememberSaveable`; `StateRestorationTester` test
- [x] 5.8 C7: `Preparing` shows an indeterminate progress with Cancel; test
- [x] 5.9 C8: double-confirm test asserts the dialog is gone after the first click (no `runCatching`)
- [x] 5.10 S1: `openAssetFileDescriptor(uri, "r", signal)` / `query(…, signal)` inside `abandonableCall` (per-item timeouts, signal cancelled on timeout / cancellation, stream closed by the signal's cancel listener); Cancel publishes `Cancelled` without joining; tests with a blocking provider (honouring and ignoring the signal)
- [x] 5.11 S4: `isValidRemoteFileName` rejects Unicode Cf (bidi controls, zero-width), line / paragraph separators and unpaired surrogates; PUT overwrite semantics unchanged and documented
- [x] 5.12 S2 / S6: confused-deputy and per-process staging budget documented in `docs/architecture.md` (accepted); S7: any `RuntimeException` while reading extras is ignored (type logged only); test
- [x] 5.13 README / CLAUDE.md / docs updated; `./gradlew test`, `spotlessCheck`, `detekt`, `lintDebug`, `jacocoTestReport`, `openspec validate harden-share-target --strict`
