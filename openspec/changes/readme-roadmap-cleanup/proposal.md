# Proposal

## Why

The README "Future Roadmap" lists "Blurry/Bad Photo Detection" twice, although the blur review has been implemented for a long time (requirement A-005, plus the blur sensitivity setting from issues #20 / #39). Meanwhile the README "Features" list has no blur review entry at all. `docs/requirements.md` is stale as well: S-003 says credentials are encrypted with `androidx.security:security-crypto`, but they now use the Android Keystore (AES-256-GCM) with plain `SharedPreferences` (see CLAUDE.md and `docs/architecture.md`). P-003 and U-003 still say every upload needs an occasion name, although auto date folders exist. Many implemented features have no requirement row: videos beyond the grid, the share target, auto date folders, per-photo retry, upload verification, removal of uploaded photos, onboarding, upload history, Wi-Fi only, blur sensitivity, uploaded-photo markers, album rename, large album listings and TalkBack support.

This change is implemented **last**, after these sibling changes: `nextcloud-chunked-upload` (GitHub #69), `cancel-running-upload`, `pin-self-signed-certificate`, `occasion-folder-suggestions`, `download-album-photos`, `show-videos-in-albums`, `new-photos-reminder`, `german-translation` and `album-search-and-sort`. Each of them updates the README and requirements for its own feature. Running this change last means it can check the result once against the state of the code at that time. If it ran earlier, it would keep going out of date.

## What Changes

- **README roadmap:** remove both stale "Blurry/Bad Photo Detection" entries. Afterwards the roadmap lists only features that are still unimplemented at implementation time. A sibling change that has not been archived or merged by then goes on the roadmap, linked to its GitHub issue if one exists (for example #69 if chunked upload is still open). If nothing is left, the section says that and links to the GitHub issues page. It is not left as an empty heading.
- **README feature list:** add a "Blurry Photo Review" entry (per-album blur scan, bulk delete, sensitivity in Settings). Check that every capability in `openspec/specs/` (including the ones the siblings add) is described somewhere in the README: in Features, Upload Options, Usage, Sharing, Permissions or Tech Stack. Add only the entries that are missing. Fix README sentences that a sibling made untrue but left unchanged (for example "Very large videos are uploaded in one request" after chunked upload, or "Uploaded videos are not shown in the Albums tab" after show-videos-in-albums).
- **docs/requirements.md:** fix the stale rows S-003, P-003 and U-003, and extend G-002 with `ACCESS_MEDIA_LOCATION`. Add rows with an ID and status for every implemented feature that has no row yet, but skip features that a sibling has already given a row. Existing IDs are never renumbered or reused.
- No code, test, resource, Gradle or CLAUDE.md change. Only `README.md` and `docs/requirements.md` are edited.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
<!-- None. -->

This is a documentation-only change and no app behaviour changes, so the change sets `skip_specs: true` in `.openspec.yaml` and has no spec delta. Writing a documentation requirement only to pass validation would add a spec that describes no system behaviour.

## Impact

- **Docs:** `README.md` (Future Roadmap, Features and any sentence a sibling left stale) and `docs/requirements.md` (fixed and new rows).
- **Code / tests / resources:** none. Following CLAUDE.md, `./gradlew test spotlessCheck` is still run once as a sanity check, to confirm that the tree the docs describe builds and passes. Detekt, lint and coverage are unaffected because no Kotlin or resource file changes.
- **Ordering dependency:** this change must not start until the nine sibling changes listed above are implemented or explicitly dropped. Any sibling still open at that point is treated as unimplemented and listed on the roadmap.
- **Out of scope:** the unused `security-crypto` entry in `gradle/libs.versions.toml` (a build change, not a docs change; noted as a follow-up in design.md), and `docs/architecture.md`, which already describes the Keystore approach correctly.
