# photo-grid-accessibility Specification

## Purpose
TBD - created by archiving change improve-photo-grid-talkback. Update Purpose after archive.

## Requirements

### Requirement: Photo grid items are single, meaningful accessibility elements
Every photo in the local photo grid SHALL be exposed to accessibility services as exactly one element whose description names its position within the day and the capture date, never the raw file name, and whose state reports whether the photo is selected and whether it was already uploaded.

#### Scenario: Unselected photo
- **WHEN** an accessibility service inspects the second of three photos of a day that is not selected and not uploaded
- **THEN** the element's description is "Photo 2 of 3, taken on <localized long date>", its state description is "Not selected", its toggleable state is Off and its role is checkbox

#### Scenario: Selected photo
- **WHEN** a photo is selected
- **THEN** its state description is "Selected", its toggleable state is On, with no separate child element announcing the selection

#### Scenario: Already uploaded photo
- **WHEN** a photo has already been uploaded
- **THEN** its state description is "Not selected, already uploaded" and no separate child element announces it

#### Scenario: Selected and already uploaded photo
- **WHEN** a photo is selected and already uploaded
- **THEN** its state description is "Selected, already uploaded" and its toggleable state is On

#### Scenario: Photos of the same day are distinguishable
- **WHEN** two photos of the same day are inspected
- **THEN** their descriptions differ by their position ("Photo 1 of 3" vs "Photo 2 of 3")

#### Scenario: Unknown capture date
- **WHEN** a photo has no capture date (DATE_TAKEN null or 0)
- **THEN** its description is "Photo 1 of 2, date unknown" and its day header reads "Unknown date" instead of a 1970 date

#### Scenario: Pre-1970 capture date is a real date
- **WHEN** a photo has a negative (pre-1970) DATE_TAKEN
- **THEN** it keeps its real date and is not announced as "date unknown"

#### Scenario: File name is not announced
- **WHEN** the unmerged semantics tree of a photo item is inspected
- **THEN** no node exposes the photo's display name in `ContentDescription` or `Text`

#### Scenario: Date follows the locale
- **WHEN** the device locale is German, or changes from English to German
- **THEN** the date in item descriptions and the header is rendered in German long format and re-rendered after the change

### Requirement: Photo grid items announce and perform their action
A photo grid item SHALL be a single checkbox-role control whose click action carries a label stating what it will do ("Select" while unselected, "Deselect" while selected) and whose labelled action invokes the real selection callback.

#### Scenario: Action label follows selection
- **WHEN** a photo toggles between unselected and selected
- **THEN** the click action label changes between "Select" and "Deselect" while the description is unchanged

#### Scenario: Semantic click performs the action once
- **WHEN** the OnClick semantics action of an item is performed
- **THEN** the selection callback is invoked exactly once

#### Scenario: One click target per item
- **WHEN** the merged semantics tree of an item is inspected
- **THEN** it contains exactly one node with a click action

### Requirement: Date headers are single toggleable elements
Each date header SHALL be one heading element, described by the merged date and photo-count texts (singular/plural aware via the existing `photo_count` plural), exposing a checkbox role, a state that is On only when all photos of that day are selected, and an action label "Select all photos of this day" or "Deselect all photos of this day" whose action invokes the real callback.

#### Scenario: Header with partially selected day
- **WHEN** some but not all photos of a day are selected
- **THEN** the header's toggleable state is Off, its state description is "Not selected" and its action label is "Select all photos of this day"

#### Scenario: Header with fully selected day
- **WHEN** all photos of a day are selected
- **THEN** the header's toggleable state is On, its state description is "Selected" and its action label is "Deselect all photos of this day"

#### Scenario: Singular and plural counts
- **WHEN** a day has one photo, or three photos
- **THEN** the header's merged texts contain "1 photo" or "3 photos" respectively

#### Scenario: Header has no separate checkbox node
- **WHEN** the merged tree of a header is inspected
- **THEN** it contains exactly one checkbox-like, clickable node (the header itself) and it is marked as a heading

#### Scenario: Activating the header toggles the day
- **WHEN** the header's OnClick action is performed on a grid with a partially selected day
- **THEN** the day callback is invoked exactly once, selecting all photos of that day, and performing it again on a fully selected day deselects them

### Requirement: Remote photo views never announce file names
Album detail, blur review and duplicate review thumbnails and the fullscreen viewer SHALL describe a photo by its position ("Photo N of M") and never by its raw display name; no Kotlin source under `ui/` may use a display name as a `contentDescription`.

#### Scenario: Remote thumbnail description
- **WHEN** an album-detail thumbnail for the third of five photos is inspected
- **THEN** its description is "Photo 3 of 5" and its display name is not exposed

#### Scenario: Source guard
- **WHEN** the regression test scans all `ui/**` Kotlin sources
- **THEN** a `displayName` or `href` anywhere on the right-hand side of a `contentDescription` / `stateDescription` assignment (including string templates and `stringResource` arguments) is reported as an offender
