# Tasks

## 1. Gate

- [x] 1.1 Record the state of the nine siblings (`nextcloud-chunked-upload`, `cancel-running-upload`, `pin-self-signed-certificate`, `occasion-folder-suggestions`, `download-album-photos`, `show-videos-in-albums`, `new-photos-reminder`, `german-translation`, `album-search-and-sort`) with `ls openspec/changes openspec/changes/archive` and `openspec list`, plus each one's feature commit (`git log --oneline --grep '#<issue>'`). Expected: eight archived, `cancel-running-upload` unarchived with 30/31 tasks (only 8.4 manual). Per design.md Decision 1 an unarchived sibling with feature commits is implemented and does not stop the change; stop and ask the user only if a sibling has no feature commit. Done when every sibling has a recorded state.
- [x] 1.2 List open issues (`gh issue list --state open`). Expected: #69 – #78, #80 only. Done when every open issue is classified as implemented on the branch (#69 – #77), this change (#78) or umbrella (#80); any other open `[Feature]` issue not implemented is added to the roadmap candidates of design.md Decision 2.

## 2. README.md

- [x] 2.1 Replace the two "Blurry/Bad Photo Detection" lines under "🔮 Future Roadmap" with exactly the two lines of design.md Decision 2 (plus any extra candidate from 1.2). Verify: `grep -c 'Blurry/Bad Photo Detection' README.md` = 0; `sed -n '/Future Roadmap/,/Tech Stack/p' README.md | grep -c '^- '` = 2 (or 2 + extra candidates); `grep -n 'not yet supported' README.md` (line ~30) and the "does not support self-signed servers" sentence (line ~79) still match the two roadmap items.
- [x] 2.2 Insert the "Blurry Photo Review" entry of design.md Decision 3 directly after the "Cloud Duplicate Detector" bullet. Verify: `grep -c 'Blurry Photo Review' README.md` = 1; the entry claims no confirmation dialog (`grep -n 'AlertDialog' app/src/main/java/de/majuwa/android/paper/krhnlesimagemanagement/ui/albums/BlurReviewScreen.kt` is still empty) and names "Find blurry" (`action_find_blurry` in `values/strings.xml`).
- [x] 2.3 Apply the Tech Stack, Setup and Permissions edits of design.md Decision 6. Verify: `grep -c 'Ladybug' README.md` = 0; `grep -c 'recurse-submodules' README.md` = 1; `grep -c 'FOREGROUND_SERVICE_DATA_SYNC' README.md` = 1; every `uses-permission` in `app/src/main/AndroidManifest.xml` appears in the Permissions list (`grep -o 'android.permission.[A-Z_]*' app/src/main/AndroidManifest.xml | sed 's/android.permission.//' | while read p; do grep -q "\*\*$p\*\*" README.md || echo "missing $p"; done` prints nothing); `grep -c 'Media3' README.md` ≥ 1 and `grep -c 'ML Kit' README.md` ≥ 1.
- [x] 2.4 Re-check the capability table of design.md Decision 3 against `ls openspec/specs` (29 today) plus `upload-cancellation` while unarchived. Verify: every directory name appears in the table; for any capability added after this plan, add one short Features entry or report it. Confirm `ls README*` lists only `README.md` (no German README to update).

## 3. docs/requirements.md

- [x] 3.1 Before editing, re-check each fact of the 6 fixed and 17 new rows in design.md Decision 4 against CLAUDE.md, the matching spec and the cited constants (`MAX_UPLOAD_RUN_ATTEMPTS`, `MAX_SHARED_PHOTOS`, `MAX_HISTORY_ENTRIES`, `MAX_PHOTOS_PER_ALBUM`), and search the file for each new row's issue numbers and key terms (`grep -n -i 'rename\|wi-fi\|history\|share\|verif\|onboard\|introduction\|blur\|marker\|talkback\|trash\|test connection\|href' docs/requirements.md`). Done when no new row duplicates an existing one and every fact holds. Note: the fact check and the capability mapping were done by the independent review of this change (see "Review findings" in design.md), not by the implementer's own written list.
- [x] 3.2 Replace rows G-001, G-002, P-003, U-003, U-014 and S-003 (and S-008, A-011 wording) with the exact text of design.md Decision 4. Verify: `grep -c 'security-crypto' docs/requirements.md` = 0; `grep -c 'ACCESS_MEDIA_LOCATION' docs/requirements.md` ≥ 1; `grep -c 'ML Kit' docs/requirements.md` = 1.
- [x] 3.3 Append the 17 rows of design.md Decision 4 at the end of their tables (P after P-005, U after U-009, A after A-008, S after S-006, N after N-007). Verify: `grep -oE '^\| [GPUASVN]-[0-9]{3}' docs/requirements.md | sort | uniq -d` is empty; `grep -oE '^\| [GPUASVN]-[0-9]{3}' docs/requirements.md | wc -l` = 70 (53 today + 17); `git diff docs/requirements.md` shows only the replaced rows and the 17 added rows; every capability of design.md Decision 3 maps to at least one row ID (mapping done by the independent review, listed in design.md "Review findings").

## 4. Integration check

- [x] 4.1 Run `./gradlew test spotlessCheck` as the CLAUDE.md sanity check; both pass. Verify `git status --porcelain` lists only `README.md` and `docs/requirements.md` (besides the pre-existing `.idea/vcs.xml`, which is never touched or staged), so detekt, lintDebug and jacoco need no new run.
- [x] 4.2 Confirm that `CLAUDE.md` and `docs/architecture.md` need no change (no architecture, convention or test pattern changed). Done when the PR description states this and lists the follow-ups of design.md Decision 7 (archive `cancel-running-upload`, `security-crypto` catalog cleanup, ML Kit decision, the open manual device checks by change and task id, optional issues for the roadmap items).

## Workflow follow-up

- Archive this change once the docs edit has been reviewed and merged. Because `skip_specs: true` is set, archiving changes no main spec.
