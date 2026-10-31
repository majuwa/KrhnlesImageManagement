## ADDED Requirements

### Requirement: Album video tiles describe themselves as videos
A video tile in the album detail grid SHALL be described as "Video N of M" (its position among all photos and videos of the album), whether its poster is loaded, failed or replaced by the placeholder. The play indicator and the placeholder icon SHALL NOT be separate accessibility elements. Selection SHALL be announced as for photo tiles.

#### Scenario: Video tile with poster
- **WHEN** an accessibility service inspects the album tile of the second of five items, a video with a server preview
- **THEN** its description is "Video 2 of 5" and the play indicator is not announced separately

#### Scenario: Video tile with placeholder
- **WHEN** the video has no server preview and shows the placeholder icon
- **THEN** its description is still "Video 2 of 5"

#### Scenario: Selected video tile
- **WHEN** a video tile is selected in selection mode
- **THEN** it is announced as selected like a selected photo tile

## MODIFIED Requirements

### Requirement: Remote photo views never announce file names
Album detail, blur review and duplicate review thumbnails and the fullscreen viewer SHALL describe a photo by its position ("Photo N of M") and a video by its position ("Video N of M"), and never by its raw display name; no Kotlin source under `ui/` may use a display name as a `contentDescription`.

#### Scenario: Remote thumbnail description
- **WHEN** an album-detail thumbnail for the third of five photos is inspected
- **THEN** its description is "Photo 3 of 5" and its display name is not exposed

#### Scenario: Remote video description
- **WHEN** an album-detail tile or the viewer page of the fourth of five items, a video, is inspected
- **THEN** its description is "Video 4 of 5" and its display name is not exposed

#### Scenario: Source guard
- **WHEN** the regression test scans all `ui/**` Kotlin sources
- **THEN** a `displayName` or `href` anywhere on the right-hand side of a `contentDescription` / `stateDescription` assignment (including string templates and `stringResource` arguments) is reported as an offender
