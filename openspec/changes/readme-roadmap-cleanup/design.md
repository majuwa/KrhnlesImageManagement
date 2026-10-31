# Design

## Context

See proposal.md, Why, for the motivation. The relevant current state:

- `README.md` has a "🚀 Features" list, a "🔮 Future Roadmap" section and further sections (Upload Options, Usage, Sharing photos from other apps, Permissions, Tech Stack) that also describe features. The roadmap holds only two copies of the same stale entry.
- `docs/requirements.md` has seven tables with stable IDs (G, P, U, A, V, S, N) and a Status column (`Implemented` / `Policy`). Rows cite GitHub issues inline, for example "(issues #15 / #34)". The last edit was the video change (#15 / #34).
- `openspec/specs/` holds 21 capabilities today. The sibling changes will add capabilities or change existing ones before this change runs, and each sibling is expected to update the README and requirements for its own feature.
- GitHub issues: at the time of planning only #69 (Nextcloud chunked upload v2) is open. Every older feature issue (#11, #13–#21, #30–#40) is closed and implemented. Most features have two numbers (an original and a duplicate, for example #15 / #34); both are cited, as the existing rows already do.
- Nothing in the build reads these files: Spotless covers only `src/**/*.kt`, and no test parses README or requirements.

## Goals / Non-Goals

**Goals:**
- The roadmap contains only work that is unimplemented on the day this change is applied.
- Every capability in `openspec/specs/` can be found in the README and has at least one row in `docs/requirements.md`.
- No duplicate rows next to the ones the siblings added.

**Non-Goals:**
- Rewriting or restructuring the README beyond the entries named in the tasks (no new sections, no change to its tone or emoji headings).
- Moving the detailed behaviour descriptions out of the README. They stay where they are, even where they are long.
- Adding a spec, a doc lint or a test that keeps the docs in sync (see Decision 5).
- Removing the unused `security-crypto` catalog entry (`gradle/libs.versions.toml`). That is a build change and is listed only as a follow-up.

## Decisions

### 1. "Implemented" is decided from the repository, not from the sibling list
A feature counts as implemented when its change is archived (`openspec/changes/archive/*-<name>`), its capability exists in `openspec/specs/` and its code is on the branch. A sibling that is still under `openspec/changes/` (not archived), or that was dropped, counts as unimplemented. It goes on the roadmap with one line and, if there is one, its issue link (`https://github.com/majuwa/KrhnlesImageManagement/issues/<n>`). Open GitHub issues labelled or titled `[Feature]` that are not implemented are also listed.
*Alternative:* assume that all nine siblings are done. Rejected, because the order is a plan and not a guarantee, and a wrong roadmap is exactly the bug this change fixes.

### 2. An empty roadmap is replaced by one sentence, not deleted
If nothing is left, the section keeps its heading and gets one sentence: no features are planned at the moment, and ideas and requests are tracked in GitHub issues (with a link to the issues page).
*Alternative:* remove the section. Rejected, because readers then cannot tell whether planned work exists, and the next planned feature would need the section to be added back.

### 3. The README is audited per capability against a checklist
For each spec in `openspec/specs/`, the implementer finds the README passage that describes it, using this list of today's capabilities and where they are covered:

| Capability | README location today |
|---|---|
| onboarding | Features, First-Run Introduction |
| video-support | Features, Local Image Grid and Videos; Permissions |
| photo-grid-accessibility | Features, TalkBack Support |
| auto-date-folders | Features, Custom or Automatic Foldering; Upload Options |
| upload-notifications, failed-photo-retry | Usage steps 6 and 8 |
| upload-history | Features, Upload History |
| upload-verification | Features, Verified Uploads; Upload Options |
| remove-uploaded-photos | Features, Remove Uploaded Photos from Device |
| uploaded-photo-markers | Features, Duplicate-Upload Prevention |
| credential-storage | Features, Secure Login; Tech Stack |
| connection-test, album-error-handling | Test Connection |
| album-photo-listing | Features, Large Albums |
| album-photo-deletion, album-navigation | Features, Delete Photos from Album |
| album-rename | Features, Manage Remote Albums |
| blur-sensitivity | Upload Options, Blur sensitivity; **no Features entry for the blur review itself** |
| share-target | Sharing photos from other apps |
| wifi-only-upload | Upload Options, Upload on Wi-Fi only |
| webdav-href-validation | Tech Stack, Server-response hardening |

The only gap known today is the blur review. It gets a "Blurry Photo Review" Features entry next to "Cloud Duplicate Detector", worded like it (scan an album, bulk-delete flagged photos, sensitivity in Settings, deletion as in the album). The capabilities the siblings add are checked the same way at implementation time. A missing one gets a short Features entry. A sentence that contradicts a sibling's spec is corrected to match the spec. If the spec and the code disagree, the code wins and the mismatch is reported instead of being documented.

### 4. Requirement rows: fix in place, append new, never renumber
- **Fixed in place:** S-003 (Android Keystore AES-256-GCM key `krhnles_master_key`, encrypted username and password in `SharedPreferences` `krhnles_secure`, classified decryption failures surfaced in Settings, issues #31 / #50). P-003 and U-003 (an occasion name, *or* automatic `YYYY/MM/DD` folders when enabled, issues #18 / #37). G-002 (add `ACCESS_MEDIA_LOCATION`, requested together with the media permissions and never an access permission). U-005 stays as it is and the per-photo retry gets its own row.
- **Candidate new rows** (each added only if no sibling row already covers it; IDs are the next free number in the section on implementation day, so the numbers below are illustrative):
  - Photo Selection: uploaded-photo markers and the "only new" filter (#13 / #32); TalkBack support (#30 / #49); user-triggered removal of verified uploads to the system trash.
  - Upload: auto date folders (#18 / #37); per-photo Retry Failed (#19 / #38); Wi-Fi only (#14 / #33); upload history (#11); share target with app-private staging (#16 / #35); upload verification (X-Hash / download); interrupted uploads resume and 413 / 507 are reported (video-support / upload-notifications).
  - Albums: album rename (#17 / #36); bounded, streamed listing of large albums (#27 / #46); blur sensitivity Low / Medium / High (#20 / #39); plain-language album error messages.
  - Settings: first-run introduction (#21 / #40); Test Connection bounded to 30 s, guarded against re-entry (leak fix #22 / #41); Verify by downloading again.
  - Non-Functional: server-response hardening (href validation, credentials only to the configured origin, bounded PROPFIND parsing without DOCTYPE / entities), with Status `Implemented`.
- **Status values:** use only the existing `Implemented` and `Policy`. A sibling still in progress gets no row from this change, because its own change adds the row.
*Alternative:* renumber the tables into a clean sequence. Rejected, because IDs are cited elsewhere (for example "A-005" in this change's own proposal and in commit messages) and must stay stable.

### 5. No spec delta and no enforcing test
The change sets `skip_specs: true`. The rule "the roadmap lists only unimplemented work" could be checked by a test, but only with brittle text matching on Markdown that a human edits, which costs more than it saves. Running this change after every batch of features handles the problem more cheaply.

## Risks / Trade-offs

- [A sibling lands after this change, or is reverted] → The roadmap and rows would be stale again. Mitigation: the task list starts with a gate that lists the nine siblings and their state; the change is not applied while any of them is still in progress unless the user decides to treat it as dropped.
- [Two siblings and this change edit the same README paragraph] → Merge conflicts or wording from two authors in one paragraph. Mitigation: this change edits only the entries named in Decisions 3 and 4 and does not reflow paragraphs.
- [A row added here duplicates a sibling's row with different wording] → Mitigation: search `docs/requirements.md` for the feature's issue numbers and key terms before adding a row (task 3.1).
- [The German translation sibling also translates the README] → If a German README exists, the same roadmap and Features edits are applied to it. If none exists, nothing is created.

## Migration Plan

Not applicable. This is a documentation-only edit with no runtime effect. To roll back, revert the commit.

## Open Questions

- Should the unused `security-crypto` entry in `gradle/libs.versions.toml` be removed in a separate small change? It does not affect this change.
