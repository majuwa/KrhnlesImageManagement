# Tasks

## 1. Replace the placeholder Purposes (edited directly in the main specs)

A Purpose in a delta is read only when a capability is created, so these three are edited in `openspec/specs/` itself. Only the line below `## Purpose` changes; titles and requirements stay byte-identical.

- [x] 1.1 In `openspec/specs/album-error-handling/spec.md`, replace the placeholder with: "Keeps the album list, album details and the duplicate and blur reviews usable when loading fails: every failure is shown as a localized, plain-language message, a missing or unusable configuration never crashes these screens, and an open album stops using the previous server and credentials after a logout or account change." Verify with `openspec validate album-error-handling --type spec --strict` (valid, no warning) and `git diff --stat` showing only that file's Purpose line changed.
- [x] 1.2 In `openspec/specs/onboarding/spec.md`, replace the placeholder with: "Introduces first-time users to the app in two skippable pages and leads them to the connection settings, while existing users go straight to their photos; the completed flag is persisted, survives restarts and is never restored from backup." Verify with `openspec validate onboarding --type spec --strict`.
- [x] 1.3 In `openspec/specs/photo-grid-accessibility/spec.md`, replace the placeholder with: "Makes the local photo grid usable with screen readers: every photo, video and date header is one meaningful element with a localized description, state and labelled action, and photos are never announced by their file names." Verify with `openspec validate photo-grid-accessibility --type spec --strict`.

## 2. Split the long `failed-photo-retry` requirements (delta)

- [x] 2.1 Check the delta `specs/failed-photo-retry/spec.md` against the main spec: every sentence of the two original requirements appears in exactly one of the four resulting requirements with the same meaning, and every original scenario is kept unchanged on its original requirement (`Only failed photos are retried`, `Repeated activation`, `Missing retry file`, `Auto date folder is preserved`, `Folder stored in an older format is preserved`, `Two batches fail partially`, `Auto date selection over several days`). Verify by listing each original sentence and scenario next to its new place.
- [x] 2.2 Validate the change. Verify with `openspec validate fix-spec-validation-warnings --strict` (valid, no warning about requirement length).

## 3. Project checks

- [x] 3.1 Confirm that no code, test or resource file changed. Verify with `git diff --stat -- openspec/specs` showing only the three Purpose lines, and no file outside `openspec/` written by this change. The working tree also holds the uncommitted, already archived `delete-after-verified-upload` change, so plain `git status` does not show this change alone.

## Workflow follow-up

- Archive the change with the spec sync (`/opsx:archive`). This applies the `failed-photo-retry` delta to the main spec.
- After the archive, run `openspec validate --specs --strict`: all main specs pass (expected 21 of 21).
