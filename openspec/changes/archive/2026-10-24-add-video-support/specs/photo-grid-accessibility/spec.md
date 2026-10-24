## ADDED Requirements

### Requirement: Video grid items describe themselves as videos
A video in the local photo grid SHALL be exposed as one element whose description names it as a video, its position within the day, its duration and the capture date (or that the date is unknown); the duration is omitted when it is unknown or zero, with the same selection and uploaded state descriptions and checkbox role as photos. The play indicator and the duration badge SHALL NOT be separate accessibility elements.

#### Scenario: Unselected video
- **WHEN** an accessibility service inspects the first of two media of a day, a 1:05 video that is not selected
- **THEN** the description is "Video 1 of 2, 1 minute 5 seconds, taken on <localized long date>", the state is "Not selected" and the role is checkbox

#### Scenario: Video without capture date
- **WHEN** a video has no capture date
- **THEN** the description says the date is unknown and contains no file name

#### Scenario: Video without duration
- **WHEN** a video has no duration or a duration of zero
- **THEN** the description is "Video 1 of 2, taken on <localized long date>" without a duration

## MODIFIED Requirements

### Requirement: Date headers are single toggleable elements
Each date header SHALL be one heading element, described by the merged date and item-count texts (singular/plural aware via the `item_count` plural; the count covers all media of the day, photos and videos, and the wording is neutral), exposing a checkbox role, a state that is On only when all media of that day are selected, and an action label "Select all items of this day" or "Deselect all items of this day" whose action invokes the real callback.

#### Scenario: Header with partially selected day
- **WHEN** some but not all items of a day are selected
- **THEN** the header's toggleable state is Off, its state description is "Not selected" and its action label is "Select all items of this day"

#### Scenario: Header with fully selected day
- **WHEN** all items of a day are selected
- **THEN** the header's toggleable state is On, its state description is "Selected" and its action label is "Deselect all items of this day"

#### Scenario: Singular and plural counts
- **WHEN** a day has one item, or three items
- **THEN** the header's merged texts contain "1 item" or "3 items" respectively

#### Scenario: Header has no separate checkbox node
- **WHEN** the merged tree of a header is inspected
- **THEN** it contains exactly one checkbox-like, clickable node (the header itself) and it is marked as a heading

#### Scenario: Activating the header toggles the day
- **WHEN** the header's OnClick action is performed on a grid with a partially selected day
- **THEN** the day callback is invoked exactly once, selecting all items of that day, and performing it again on a fully selected day deselects them

#### Scenario: Mixed day count
- **WHEN** a day has two photos and one video
- **THEN** the header's merged texts contain "3 items" and its state is On only when all three are selected
