## Why

GitHub issues #30 / #49 (duplicates): TalkBack reads the raw filename (`IMG_20260501_123456.jpg`) as the thumbnail description in the local photo grid (`PhotoGridScreen.kt`), then the selection icon separately ("Selected" / "Not selected"), and the clickable item has no action label. Users relying on TalkBack cannot tell what a photo is, whether it is selected, or what a double tap does. The same fragmentation affects the per-day header row (title, count and checkbox announced as separate nodes). The remote album grids (album detail, blur review, duplicate review) and the fullscreen viewer announce the raw filename the same way.

## What Changes

- Each local grid photo item becomes ONE merged accessibility node built with `toggleable(role = Role.Checkbox)`: description "Photo N of M, taken on <date>" (or "..., date unknown" when the capture date is unknown), `stateDescription` ("Selected" / "Not selected", plus ", already uploaded" when applicable) and a click label ("Select" / "Deselect") attached to the real click action, so activating the node fires the real callback.
- Each date header uses the same pattern (`toggleable` + `stateDescription` + click label "Select all photos of this day" / "Deselect all ..."), is a `heading()`, and is described by its merged texts (date `Text` + existing `photo_count` plural); no new plural is added. The child `Checkbox` becomes decorative.
- The thumbnail image, selection icon and uploaded badge become decorative (null `contentDescription`); the raw filename is no longer announced anywhere in the local grid.
- The date is formatted once per date with a locale-aware `FormatStyle.LONG` formatter (`remember(locale)`); a locale change re-renders it.
- Remote album grids (`AlbumDetailScreen`, `BlurReviewScreen`, `DuplicateReviewScreen`) and `FullscreenViewerScreen` replace `contentDescription = photo.displayName` with a localized "Photo N of M" description (remote photos carry no capture date). No raw filename is announced.
- A source-scan regression test over all `ui/**` Kotlin files forbids `contentDescription = ...displayName`.
- Media with no capture date (`DATE_TAKEN` null/0 (negative = real pre-1970 date)) is mapped to a sentinel (`Photo.UNKNOWN_DATE`) and announced as "date unknown"; its header reads "Unknown date" instead of 1 January 1970.
- New string resources in `strings.xml` (no new plural); `cd_already_uploaded` is removed once unreferenced.
- Robolectric Compose UI tests assert the merged semantics, real action invocation, locale handling and the grid.
- No other visual change.

## Capabilities

### New Capabilities
- `photo-grid-accessibility`: TalkBack semantics (description, state, role, click labels, merging, no filenames) of the local photo grid items and date headers and of the photo descriptions in the remote album grids and viewer.

### Modified Capabilities
<!-- none: existing specs do not cover the photo grids -->

## Impact

- Code: `ui/photogrid/PhotoGridScreen.kt` (`PhotoItem`, `DateHeader`, `PhotoGrid` become `internal`), `ui/albums/AlbumDetailScreen.kt`, `BlurReviewScreen.kt`, `DuplicateReviewScreen.kt`, `ui/viewer/FullscreenViewerScreen.kt`, new shared description helper, `model/Photo.kt` (`UNKNOWN_DATE`), `data/MediaRepository.kt`, `strings.xml` (only `values` exists).
- Tests: new `ui/PhotoGridAccessibilityTest.kt`, `NoFilenameContentDescriptionRegressionTest`, remote description tests.
- Docs: `docs/architecture.md`, `README.md`, `CLAUDE.md`.
- Non-goals: changing the upload path of unknown-date photos (still `1970/01-Jan`, pinned by a test; pre-1970 photos keep their real year), changing the remote grids' selection semantics (they keep their visual-only selection markers; a checkbox/state rework is a follow-up), time-of-day in `Photo`, custom accessibility actions, haptics, extra announcements beyond default state-change events. `ShareIntentParser` assigns today's (known) date to shared photos and is unaffected.
