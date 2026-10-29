# Proposal

## Why

`openspec validate --specs --strict` fails for 4 of 21 main specs. Three still carry the placeholder Purpose that `openspec archive` writes for a new capability, and `failed-photo-retry` has two requirements over 500 characters. Strict validation should pass so that real problems in future changes are not lost among old warnings.

## What Changes

- Replace the placeholder `## Purpose` ("TBD - created by archiving change …") in `album-error-handling`, `onboarding` and `photo-grid-accessibility` with a written Purpose. A Purpose in a delta is read only when a capability is created, so these three are edited directly in the main specs (task 1), not through a delta.
- Split the two long requirements of `failed-photo-retry`, without changing behaviour:
  - "Retry re-queues only failed photos into the original folder" keeps the retry action rules; the rule that stored folders are never re-derived becomes its own requirement, "Retry folders are never re-derived", with a new single-folder scenario. The two folder scenarios stay on the original requirement, because a MODIFIED block may not drop scenarios and they still check its "same remote folder" sentence.
  - "Result notifications are separate per batch" keeps the per-batch rules; the id rule becomes its own requirement, "Result notifications never reuse the progress notification id", with a new scenario.
- No code, test or resource change.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
- `failed-photo-retry`: two requirements reworded into shorter ones and two requirements added from the split text. Every SHALL statement keeps its meaning; nothing is added or dropped.

`album-error-handling`, `onboarding` and `photo-grid-accessibility` change only in their Purpose, not in their requirements, so they get no delta.

## Impact

- **Specs:** `openspec/specs/album-error-handling/spec.md`, `onboarding/spec.md` and `photo-grid-accessibility/spec.md` (Purpose only); `failed-photo-retry/spec.md` through the delta when the change is archived.
- **Code:** none. The tests that guard the retry and notification behaviour (`UploadWorker*Test`, `RetryUploadReceiver*Test`) stay as they are.
