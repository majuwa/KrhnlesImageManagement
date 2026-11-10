# Design

## Context

See proposal.md, Why, for the motivation. State of the branch `claude/remaining-openspec-changes-80` when this plan was reviewed (2026-11-09):

- `README.md` (100 lines) has a "🚀 Features" list (lines 7 – 31), a "🔮 Future Roadmap" (lines 33 – 35, two copies of the same stale entry), "🛠️ Tech Stack" (37 – 44), "⚙️ Setup & Installation" (46 – 51), Permissions (53 – 58) and further sections (Test Connection, Upload Options, Usage, Sharing). No German README exists (`ls README*` lists only `README.md`).
- `docs/requirements.md` has seven tables with stable IDs. Highest IDs today: G-005, P-005, U-009, A-008, V-013, S-006, N-007. Status values are `Implemented` and `Policy`.
- `openspec/specs/` holds 29 capabilities. `cancel-running-upload` is implemented but not archived, so `upload-cancellation` exists only under `openspec/changes/cancel-running-upload/specs/`.
- GitHub: open issues are #69 – #78 and #80. #69 – #77 are the nine siblings, all implemented on this branch (they close when #80's PR merges), #78 is this change and #80 the umbrella. No other feature issue is open.
- Deferred follow-ups documented in `docs/architecture.md`: nested album folder navigation (line ~408, "Album search & sort"; README line 30 says "browsing into the month / day sub-folders is not yet supported"), an injectable client in the `login-flow-nextcloud` library so the Nextcloud login works with a self-signed server (D11, line ~348; README line 79 says "The Nextcloud login does not support self-signed servers"), and remembering system-trusted origins (D9, an internal UX detail, not a roadmap feature).
- Dependencies that the README does not mention: `androidx.media3:media3-exoplayer` + `media3-datasource-okhttp` (album video playback) and `com.google.mlkit:object-detection` 17.0.2 (`util/BlurAnalyzer.kt`, blur review). ML Kit's terms: images are processed on the device, but the SDK "may contact Google servers … to receive bug fixes, updated models and hardware accelerator compatibility information" and "send[s] metrics about the performance and utilization of the APIs … to Google" (https://developers.google.com/ml-kit/terms).
- Manifest permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`, `ACCESS_MEDIA_LOCATION`, `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` (the last two for the `dataSync` `SystemForegroundService` used by `UploadWorker` and `DownloadWorker`). Build: AGP 9.5.0, Gradle 9.8.2, `compileSdk` 37, `minSdk` 35, git submodule `libs/login-flow-nextcloud` included via `includeBuild`.
- README claims checked and found accurate (no edit): the saved album copy keeps EXIF GPS (album-photo-download spec: "byte for byte"); the occasion dialog and the share target contact the server for the album list also on mobile data (occasion-folder-suggestions spec has no network gate); the reminder sends nothing (N-006); per-app language on Android 13+ (`locales_config.xml` with `en`, `de`; the qualifier is redundant with `minSdk` 35 but not wrong); "after five interrupted attempts" (`MAX_UPLOAD_RUN_ATTEMPTS = 5`); "up to 100 at a time" (`MAX_SHARED_PHOTOS = 100`); the removal dialog title; no stale "uploaded in one request" / "not shown in the Albums tab" sentence remains.
- Nothing in the build reads these files: Spotless covers only `src/**/*.kt`, and no test parses README or requirements.

## Goals / Non-Goals

**Goals:**
- The roadmap contains only work that is unimplemented on the day this change is applied.
- Every capability in `openspec/specs/` (plus `upload-cancellation`) can be found in the README and has at least one row in `docs/requirements.md`.
- No README or requirement statement contradicts the code, in particular no privacy statement.
- No duplicate rows next to the ones the siblings added.

**Non-Goals:**
- Rewriting or restructuring the README beyond the edits listed in Decisions 3 and 6 (no new sections, no change to tone or emoji headings, no reflow).
- Moving detailed behaviour descriptions out of the README.
- Adding a spec, a doc lint or a test that keeps the docs in sync (Decision 5).
- Code or build changes: removing the unused `security-crypto` catalog entry, removing or configuring ML Kit, archiving `cancel-running-upload`. They are follow-ups (Decision 7).

## Decisions

### 1. "Implemented" is decided from the code, archiving is recorded but not decisive
A sibling counts as implemented when its feature commits are on the branch and its tasks are checked except manual device checks. Its archive state is recorded in the gate. For today: eight siblings archived; `cancel-running-upload` implemented, not archived (only 8.4 manual open). It is treated as implemented and the gate does **not** stop for it; its capability is mapped via `openspec/changes/cancel-running-upload/specs/upload-cancellation/spec.md`. A sibling without feature commits counts as unimplemented and goes on the roadmap.
Roadmap candidates are (a) unimplemented siblings, (b) open GitHub issues that are not implemented on the branch, (c) user-visible features explicitly deferred as follow-ups in `docs/architecture.md`. Internal follow-ups (D9 "remember system-trusted origins", `security-crypto` cleanup) and manual device checks are not roadmap items.
*Alternative:* "implemented = archived". Rejected: it would put the cancel feature, which is in the code and the README, on the roadmap.

### 2. Roadmap content
Exactly two lines replace README lines 34 – 35 (no issue exists for them, so no links):

```
- **Browse sub-folders of an album:** Open the month and day folders inside an album (for example `2026` → `05` → `14` created by auto date folders). Today the Albums tab lists only the folders directly inside the base folder.
- **Nextcloud login for servers with a self-signed certificate:** Today such a server is set up manually (server address, username and app password, then "Test Connection"); the browser login first needs a change in the `login-flow-nextcloud` library.
```

If the roadmap ever becomes empty, the heading stays with one sentence ("No features are planned at the moment; ideas and requests are tracked in [GitHub issues](https://github.com/majuwa/KrhnlesImageManagement/issues).") instead of being deleted. Not needed today.

### 3. README audit: capability → README location
| Capability | README location |
|---|---|
| onboarding | Features, First-Run Introduction |
| localization | Features, English & German |
| video-support | Features, Local Image Grid; Videos; Permissions |
| photo-grid-accessibility | Features, TalkBack Support |
| auto-date-folders | Features, Custom or Automatic Foldering; Upload Options |
| upload-notifications, failed-photo-retry | Features, Videos (413 / 507, interruptions); Usage steps 6 and 8 |
| chunked-upload | Features, Large Videos on Nextcloud |
| upload-cancellation (unarchived) | Features, Cancel a Running Upload |
| upload-history | Features, Upload History |
| upload-verification | Features, Verified Uploads; Upload Options |
| remove-uploaded-photos | Features, Remove Uploaded Photos from Device |
| uploaded-photo-markers | Features, Duplicate-Upload Prevention |
| new-photos-reminder | Features, New-Photos Reminder; Upload Options |
| occasion-folder-suggestions | Usage step 5; Sharing photos from other apps |
| credential-storage | Features, Secure Login; Tech Stack |
| connection-test, album-error-handling | Test Connection |
| server-certificate-trust | Servers with a self-signed certificate |
| album-list-browsing | Features, Search and Sort Albums |
| album-photo-listing | Features, Cloud Album Browser; Large Albums |
| album-photo-deletion, album-navigation | Features, Delete Photos from Album |
| album-rename | Features, Manage Remote Albums |
| album-video-playback | Features, Play Videos from the Album |
| album-photo-download | Features, Save Album Photos to the Device |
| blur-sensitivity | Upload Options, Blur sensitivity; **no Features entry for the blur review itself** |
| share-target | Sharing photos from other apps |
| wifi-only-upload | Upload Options, Upload on Wi-Fi only |
| webdav-href-validation | Tech Stack, Server-response hardening |

The only feature gap is the blur review. Insert after line 28 ("Cloud Duplicate Detector"):

```
- **Blurry Photo Review:** Scan an album for blurry photos with "Find blurry" (videos in the album are ignored; the review buttons only appear when the album has photos). The flagged photos are pre-selected: tap to change the selection, long-press to preview, then delete the selected ones from the server. How strictly photos are flagged is set by Blur sensitivity in Settings (see Upload Options). Photos that could not be deleted stay in the album, which reports how many deletions failed (see Delete Photos from Album). The analysis runs on the device (see Tech Stack).
```

The blur review has no confirmation dialog (`BlurReviewScreen.kt` deletes on its "Delete N photos" button); the entry must not claim one.

### 4. Requirement rows: fix in place, append, never renumber
Before editing, each row below is checked against the sibling rows (G-005, P-005, U-006 – U-009, A-006 – A-008, V-008, V-010 – V-013, S-005, S-006, N-005 – N-007); none of them covers these features. Auto date folders get **no** new row: the fixed P-003 / U-003 cover them (a separate row would duplicate them). "Verify by downloading again" gets no own row: U-014 covers it.

**Fixed in place (whole row replaced, ID kept):**

```
| G-001 | The app must be privacy-first: no analytics or telemetry of its own; photos, videos, file and folder names and credentials are sent only to the configured server. Exception: the blur review uses the bundled Google ML Kit object detection. Images are analysed on the device and never sent; the blur score is the Laplacian variance inside the regions ML Kit finds. The ML Kit SDK starts with the app (`MlKitInitProvider`), may download updates from Google and sends API usage and performance metrics with a per-installation identifier and the app's package name and version to Google (https://developers.google.com/ml-kit/terms, https://developers.google.com/ml-kit/android-data-disclosure). | Implemented |
| G-002 | The app must target Android 15+ (`minSdk` 35) and handle granular media permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) and `ACCESS_MEDIA_LOCATION` are requested together; any of the first three gives access, `ACCESS_MEDIA_LOCATION` never does (it lets uploads keep the photos' location); photos stay usable without video access, upgrade users are asked for video access and full-access users for the location permission at most once, and partial-access users never automatically (issues #15 / #34; the location permission: change `delete-after-verified-upload`). | Implemented |
| P-003 | Before uploading, users tag the selected photos with an occasion name, unless automatic `YYYY/MM/DD` day folders are enabled in Settings (issues #18 / #37). | Implemented |
| U-003 | The upload destination folder is the occasion name below the base folder or, with auto date folders on, each photo's capture day (`YYYY/MM/DD`, ASCII digits, one background upload per selection); shared photos always use the occasion (issues #18 / #37). | Implemented |
| S-003 | Username and password are encrypted with AES-256-GCM under an Android Keystore key (`krhnles_master_key`) and stored in `SharedPreferences` (`krhnles_secure`); of the connection settings only the server URL and base folder are stored unencrypted; decryption failures are classified and shown in Settings with a request to log in again (issues #31 / #50). | Implemented |
```

**Appended (in this order, at the end of their section's table):**

```
| P-006 | Photos and videos uploaded from this device are marked in the grid (cloud badge, announced by TalkBack) and a filter shows only new ones; marked items can be uploaded again; the markers are reset on disconnect and never backed up (issues #13 / #32). | Implemented |
| P-007 | Each grid item and day header is one screen-reader element with a localized description (position, photo / video, duration, capture date or "date unknown", uploaded state) and a select action; file names are never announced (issues #30 / #49). | Implemented |
| P-008 | Photos and videos are removed from the device only by the user-triggered "Remove uploaded photos from device" action: only verified uploads to the current server that are unchanged on the device and still on the server with the same size are moved to the system trash after Android's confirmation; nothing is deleted permanently or on the server. | Implemented |

| U-010 | After a partially failed upload the result notification offers "Retry Failed (N)", which uploads only the failed items into their original folders (shared photos from the app-private copy for 7 days) (issues #19 / #38). | Implemented |
| U-011 | An optional "Upload on Wi-Fi only" setting (off by default) runs uploads, including retries, only on an unmetered network; waiting does not count towards giving up; the setting is reset on logout and not backed up (issues #14 / #33). | Implemented |
| U-012 | Every upload run (including retries and cancelled runs) is recorded in a local upload history (folder, time, uploaded / failed counts, at most 500 entries) that can be cleared per entry or completely; it is not backed up and not cleared on logout (issue #11). | Implemented |
| U-013 | Up to 100 images can be shared into the app from other apps; only allow-listed raster image types are accepted, each is copied to app-private storage (at most 100 MiB per file, 1 GiB per share) before the user returns to the sharing app, and uploaded into the entered occasion folder (issues #16 / #35). | Implemented |
| U-014 | An upload of a photo or video from the device is verified when a check is available: on Nextcloud with the server's SHA-256 (`X-Hash`), on other servers only with "Verify by downloading again" (off by default); otherwise it is uploaded but not verified. A mismatch or failed check counts as failed and can be retried; photos not uploaded as the original (no `ACCESS_MEDIA_LOCATION`) and shared photos are never verified. | Implemented |
| U-015 | An interrupted upload (for example by Android's background time limit) is resumed later with the items not yet uploaded and stops with "Retry Failed" after 5 interrupted runs; "file too large" (HTTP 413) and "no storage left" (HTTP 507) are reported in the result notification (issues #15 / #34). | Implemented |

| A-009 | Users can rename a remote album with a validated name (trimmed, not blank or unchanged, no `/`, `\`, `.` or `..`, at most 255 UTF-8 bytes); an existing album is never overwritten, and a rename without an answer within 30 s is cancelled and the list reloaded (issues #17 / #36). | Implemented |
| A-010 | Album listings are streamed with bounded memory and show progress while loading; an album above 20,000 photos and videos or a 32 MiB listing shows its first items with a notice that the duplicate and blur reviews repeat; an album list above 32 MiB fails with an explanation (issues #27 / #46). | Implemented |
| A-011 | Failures of the album list, an album and the duplicate / blur reviews are shown as plain-language messages (never raw server or exception text) with a retry; a logout or server change closes an open album. | Implemented |
| A-012 | A duplicate or blur scan and its results, selection and a running deletion survive rotation (a recreated screen never restarts a scan); Back and the top-bar back / cancel buttons are blocked while a deletion runs so it always finishes. | Implemented |

| S-007 | Users can choose the blur sensitivity (Low / Medium / High, default Medium) in Settings; a changed level applies to the next blur scan, is reset on logout and is not backed up (issues #20 / #39). | Implemented |
| S-008 | A fresh install shows a two-page introduction once, skippable on both pages, whose "Connect now" opens Settings; users with an existing configuration (valid config, stored server URL or stored credentials) never see it (issues #21 / #40). | Implemented |
| S-009 | "Test Connection" checks the stored server and credentials within 30 s, ignores repeated taps while it runs and reports an untrusted certificate, unknown host, unreachable server, timeout, HTTP 401 / 403, 404 or another HTTP status in plain language; a logout or configuration change clears the result (issues #22 / #41). | Implemented |

| N-008 | Server responses are untrusted: server-supplied hrefs are used only on the configured origin and below the base folder (or album), credentials are sent only to the configured origin, and PROPFIND responses are stream-parsed with size, depth and attribute limits and without DOCTYPE or entities. | Implemented |
```

Each new row's facts were checked against CLAUDE.md and the matching spec when this plan was written; the implementer re-checks them (task 3.1) and corrects a row rather than the code if one disagrees, reporting the mismatch.
*Alternative:* renumber the tables. Rejected: IDs are cited elsewhere (this change, `docs/architecture.md` "N-007", commit messages).

### 5. No spec delta and no enforcing test
`skip_specs: true`. A test for "the roadmap lists only unimplemented work" would need brittle text matching on hand-edited Markdown. Running this change after each batch of features is cheaper.

### 6. Other README corrections (exact edits)
- **Tech Stack**, insert after "Image Loading: Coil":
  ```
  - **Video Playback:** AndroidX Media3 ExoPlayer with the OkHttp data source (album videos are streamed from the configured server only)
  - **Blur Detection:** Google ML Kit object detection, bundled and run on the device: photos never leave the device, but the ML Kit SDK may download updates from Google and sends usage and performance metrics of the API to Google ([ML Kit terms](https://developers.google.com/ml-kit/terms))
  ```
- **Setup**, replace steps 1 and 2:
  ```
  1. Clone the repository with its submodule (`git clone --recurse-submodules`, or `git submodule update --init` in an existing clone); the Nextcloud login library is built from `libs/login-flow-nextcloud`
  2. Open in an Android Studio version that supports Android Gradle Plugin 9.5 (see `gradle/libs.versions.toml`)
  ```
- **Permissions**, replace the `POST_NOTIFICATIONS` line and add one line after it:
  ```
  - **POST_NOTIFICATIONS** — for upload and download progress and result notifications and the optional new-photos reminder
  - **FOREGROUND_SERVICE** and **FOREGROUND_SERVICE_DATA_SYNC** — let uploads and album downloads keep running in the background as a data-sync foreground service with their progress notification
  ```
The README is privacy-sensitive: the ML Kit line is the only disclosure added; the decision whether to keep ML Kit is a follow-up for the user (Decision 7).

### 7. Follow-ups go to the PR description, not the docs
The PR description lists: (1) archive `cancel-running-upload` (8.4 manual open); (2) remove `securityCrypto` / `security-crypto` from `gradle/libs.versions.toml`; (3) decide whether to keep ML Kit or replace it with a pure on-device blur metric (then G-001 and the Tech Stack line change again); (4) the open manual device checks, each by change and task id: improve-photo-grid-talkback 5.9, stream-album-photo-listing 6.6, add-video-support 5.3, delete-after-verified-upload 8.5, nextcloud-chunked-upload 5.2, cancel-running-upload 8.4, pin-self-signed-certificate 8.2, occasion-folder-suggestions 6.5, album-search-and-sort 6.4, show-videos-in-albums 7.2, download-album-photos 9.4, new-photos-reminder 7.4, german-translation 4.5 / 5.2 / 7.5; (5) optional GitHub issues for the two roadmap items, to be linked from the roadmap later.

## Risks / Trade-offs

- [A later merge into the branch changes a documented feature] → The docs drift again. Mitigation: task 1.1 re-runs the gate immediately before editing; this change is re-run after the next feature batch.
- [The ML Kit disclosure is read as a regression] → It documents existing behaviour that G-001 hid; the code decision is deliberately left to the user (Decision 7).
- [A new row duplicates a sibling row with other wording] → Mitigation: task 3.1 searches by issue number and key term before adding.
- [Exact row text drifts from the code by implementation day] → Mitigation: task 3.1 re-checks each fact; a disagreement is corrected in the row and reported, never "fixed" in code by this change.

## Migration Plan

Not applicable. Documentation-only edit with no runtime effect. Roll back by reverting the commit.

## Open Questions

- Should ML Kit stay (with the disclosure) or be replaced in a separate code change? This change only discloses it.
- Should the two roadmap items get GitHub issues so the roadmap can link them?

## Review findings (applied after review of #78)

The independent review (commit d57c811) corrected: G-001 / Tech Stack (ML Kit starts with the app via `MlKitInitProvider`, may update from Google, sends API usage and performance metrics with a per-installation identifier and package name / version; blur score = Laplacian variance in ML Kit regions); the README duplicate / blur lines (both reviews delete immediately with "Delete N photos", only album selection mode asks for confirmation); U-014 (verified only when a check is available); S-003, S-008, G-002 wording; the README permissions (INTERNET also for ML Kit; WAKE_LOCK / ACCESS_NETWORK_STATE / RECEIVE_BOOT_COMPLETED from WorkManager, verified in the merged manifest); and added A-012 (review state survives rotation, Back blocked while deleting). The requirement ID count is now 70.

Capability mapping (done by the review): album-rename A-009; album-video-playback A-002 / V-rows; auto-date-folders P-003 / U-003; blur-sensitivity A-005; chunked-upload U-rows; connection-test / server-certificate-trust S-rows; credential-storage S-003; failed-photo-retry U-010; localization N-rows; new-photos-reminder N-rows; occasion-folder-suggestions P-rows; onboarding S-008; photo-grid-accessibility P-rows; remove-uploaded-photos / upload-verification U-014 and V-rows; share-target U-013; upload-history / upload-notifications / uploaded-photo-markers / wifi-only-upload U-rows; webdav-href-validation S-rows; upload-cancellation U-006.
