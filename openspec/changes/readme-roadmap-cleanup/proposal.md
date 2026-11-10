# Proposal

## Why

The README "Future Roadmap" lists "Blurry/Bad Photo Detection" twice, although the blur review has been implemented for a long time (requirement A-005, plus the blur sensitivity setting from issues #20 / #39). The README "Features" list has no blur review entry at all. `docs/requirements.md` is stale as well:

- S-003 says credentials are encrypted with `androidx.security:security-crypto`, but they use the Android Keystore (AES-256-GCM) with plain `SharedPreferences` (see CLAUDE.md and `docs/architecture.md`).
- P-003 and U-003 still say every upload needs an occasion name, although auto date folders exist (#18 / #37).
- G-002 does not mention `ACCESS_MEDIA_LOCATION`, which is requested together with the media permissions.
- G-001 claims "no cloud analytics, no telemetry", but the blur review uses the bundled Google ML Kit object detection SDK (`com.google.mlkit:object-detection`, `util/BlurAnalyzer.kt`). Per the ML Kit terms, images are processed on the device only, but the SDK may contact Google servers for updates and sends API usage and performance metrics to Google. Neither the requirement nor the README discloses this.
- Implemented features that have no requirement row: uploaded-photo markers, TalkBack support, removal of uploaded photos, per-photo retry, Wi-Fi only, upload history, the share target, upload verification, interrupted uploads and 413 / 507 reporting, album rename, large album listings, album error messages, blur sensitivity, onboarding and Test Connection, plus server-response hardening.

Further README statements are wrong or incomplete on the current branch: the Setup steps ("Android Studio (Ladybug or newer)" cannot build AGP 9.5, and the `libs/login-flow-nextcloud` git submodule is not mentioned), the Permissions list (missing `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`; `POST_NOTIFICATIONS` is also used for downloads and the reminder) and the Tech Stack (no Media3 ExoPlayer for album video playback, no ML Kit).

This change runs **last**, after `nextcloud-chunked-upload` (#69), `cancel-running-upload` (#70), `pin-self-signed-certificate` (#71), `occasion-folder-suggestions` (#72), `download-album-photos` (#73), `show-videos-in-albums` (#74), `new-photos-reminder` (#75), `german-translation` (#76) and `album-search-and-sort` (#77). All nine are implemented on the branch. Eight are archived; `cancel-running-upload` is implemented (commits `ad90c9e`, `3e706dc`, `0312cf5`, `81a0651`; 30/31 tasks, only the manual device check 8.4 open) but **not archived**, so its `upload-cancellation` capability is not yet in `openspec/specs/`. The siblings already updated the README and requirements for their own features (rows G-005, P-005, U-006 – U-009, A-006 – A-008, V-008, V-010 – V-013, S-005, S-006, N-005 – N-007), and the stale sentences that the original plan expected ("Very large videos are uploaded in one request", "Uploaded videos are not shown in the Albums tab") are already gone.

## What Changes

- **README roadmap:** replace both stale "Blurry/Bad Photo Detection" lines with the two features that are still genuinely open and documented as deferred follow-ups: browsing sub-folders of an album (nested auto date folders, `docs/architecture.md` "Album search & sort") and the Nextcloud login for servers with a self-signed certificate (`docs/architecture.md` D11, needs a `login-flow-nextcloud` library change). Open GitHub issues #69 – #77 are implemented on this branch (they close on merge of #80) and are not roadmap items. Manual device checks are verification debt, not features: they go into the PR description, not the README.
- **README Features:** add a "Blurry Photo Review" entry next to "Cloud Duplicate Detector".
- **README corrections:** Setup steps 1 – 2 (submodule, Android Studio version), Permissions (`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`, wider `POST_NOTIFICATIONS` purpose), Tech Stack (Media3 ExoPlayer, ML Kit with its metrics disclosure).
- **docs/requirements.md:** fix G-001, G-002, P-003, U-003, U-014 and S-003 in place; append 17 rows (P-006 – P-008, U-010 – U-015, A-009 – A-012, S-007 – S-009, N-008) with the exact text in design.md Decision 4. Existing IDs are never renumbered or reused.
- No code, test, resource, Gradle or CLAUDE.md change. Only `README.md` and `docs/requirements.md` are edited. No German README exists, so none is created or edited.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
<!-- None. -->

This is a documentation-only change and no app behaviour changes, so the change sets `skip_specs: true` in `.openspec.yaml` and has no spec delta. Writing a documentation requirement only to pass validation would add a spec that describes no system behaviour.

## Impact

- **Docs:** `README.md` (Future Roadmap, Features, Tech Stack, Setup, Permissions) and `docs/requirements.md` (6 fixed rows, 17 new rows).
- **Code / tests / resources:** none. Following CLAUDE.md, `./gradlew test spotlessCheck` is still run once as a sanity check of the documented tree. Detekt, lint and coverage are unaffected because no Kotlin or resource file changes.
- **Ordering dependency:** all nine siblings are implemented. Archiving `cancel-running-upload` is a separate workflow step outside this change's files; until it is archived, the capability check uses `openspec/changes/cancel-running-upload/specs/upload-cancellation/spec.md`.
- **Out of scope (follow-ups, listed in the PR description):** removing the unused `security-crypto` catalog entry (`gradle/libs.versions.toml`, `securityCrypto` + `security-crypto`); deciding whether to keep ML Kit (a code / dependency decision, this change only discloses it); archiving `cancel-running-upload`; the open manual device checks of the archived changes; `docs/architecture.md`, which already describes the Keystore approach and the two deferred follow-ups correctly.
