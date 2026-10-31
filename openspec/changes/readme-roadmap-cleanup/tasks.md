# Tasks

## 1. Gate: state of the sibling changes

- [ ] 1.1 For each sibling (`nextcloud-chunked-upload`, `cancel-running-upload`, `pin-self-signed-certificate`, `occasion-folder-suggestions`, `download-album-photos`, `show-videos-in-albums`, `new-photos-reminder`, `german-translation`, `album-search-and-sort`), record whether it is archived under `openspec/changes/archive/`, still in progress under `openspec/changes/`, or dropped. If one is still in progress, stop and ask the user whether to wait or to treat it as unimplemented. Done when every sibling has a recorded state (design.md Decision 1).
- [ ] 1.2 List the open GitHub feature issues (`gh api 'repos/majuwa/KrhnlesImageManagement/issues?state=open'`, without pull requests) and note which of them are not implemented on the branch. Done when the list of roadmap candidates (unimplemented siblings plus open issues, each with its issue number if there is one) is written down.

## 2. README roadmap and feature list

- [ ] 2.1 In `README.md`, "🔮 Future Roadmap": remove both "Blurry/Bad Photo Detection" lines and list exactly the candidates from 1.2, one line each with an issue link (`https://github.com/majuwa/KrhnlesImageManagement/issues/<n>`) where one exists. If there are none, write the single fallback sentence linking to the issues page (Decision 2). Verify with `grep -c 'Blurry/Bad Photo Detection' README.md` = 0, and check that every roadmap line names a feature that is not in `openspec/specs/`.
- [ ] 2.2 Add a "Blurry Photo Review" entry to "🚀 Features" next to "Cloud Duplicate Detector": scan an album for blurry photos, bulk-delete the flagged ones as in the album, sensitivity under Settings, Blur sensitivity. Verify that the wording matches `openspec/specs/blur-sensitivity/spec.md` and `album-photo-deletion/spec.md`.
- [ ] 2.3 Go through every directory in `openspec/specs/` (today's 21 plus those the siblings added) with the checklist in design.md Decision 3, and add a short Features entry for any capability the README does not describe. Verify with a written mapping of capability → README section, with no capability left unmapped.
- [ ] 2.4 Fix README sentences that contradict a sibling's spec and that the sibling left unchanged (for example "Very large videos are uploaded in one request", or "Uploaded videos are not shown in the Albums tab", once the corresponding sibling is implemented). If the spec and the code disagree, report it instead of documenting it. Verify by reading each changed sentence against the spec requirement it now reflects.
- [ ] 2.5 If the `german-translation` sibling added a German README, apply the same changes as 2.1–2.4 to it. Otherwise skip this task. Verify that both READMEs have the same roadmap items and Features entries.

## 3. docs/requirements.md

- [ ] 3.1 Before any edit, search `docs/requirements.md` for each candidate from design.md Decision 4 and each sibling feature, by issue number and by key term, and drop the candidates that already have a row. Done when the list of rows to add or fix is written down.
- [ ] 3.2 Fix the stale rows in place, keeping their IDs: S-003 (Android Keystore AES-256-GCM, encrypted username and password in `SharedPreferences`, classified decryption failures surfaced in Settings, #31 / #50), P-003 and U-003 (an occasion name or automatic `YYYY/MM/DD` folders, #18 / #37), and G-002 (`ACCESS_MEDIA_LOCATION`). Verify with `grep -c 'security-crypto' docs/requirements.md` = 0, and check that every fixed row agrees with CLAUDE.md and the matching spec.
- [ ] 3.3 Append the remaining rows from 3.1 to their sections, using the next free ID per prefix, Status `Implemented`, and the issue references inline in the existing "(issues #a / #b)" style. Verify that no ID appears twice (`grep -o '^| [A-Z]*-[0-9]*' docs/requirements.md | sort | uniq -d` is empty), that no existing ID was renumbered (`git diff` shows only additions and the rows from 3.2), and that every capability in `openspec/specs/` is covered by at least one row.

## 4. Integration check

- [ ] 4.1 Run `./gradlew test spotlessCheck`, as CLAUDE.md requires, as a sanity check of the documented tree. Both must pass. No Kotlin, resource or Gradle file was changed (verify that `git status` lists only `README.md`, `docs/requirements.md` and, if 2.5 applied, the German README), so detekt, lintDebug and jacoco need no new run.
- [ ] 4.2 Confirm that `CLAUDE.md` and `docs/architecture.md` need no change, because no architecture, convention or test pattern changed. Done when this is noted in the PR description, together with the follow-up "remove the unused `security-crypto` entry from `gradle/libs.versions.toml`".

## Workflow follow-up

- Archive the change once the docs edit has been reviewed and merged. Because `skip_specs: true` is set, archiving changes no main spec.
