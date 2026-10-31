# Spec Delta

## MODIFIED Requirements

### Requirement: The album list updates in place
On success the renamed album SHALL replace the old entry in the loaded album list (same position in the loaded list, new name and href, unchanged last-modified date) without reloading the album list. The Albums tab SHALL display it at the position the current sort option and search query give it, and SHALL hide it if its new name does not match the query. While the rename runs the grid is replaced by a progress indicator. Photos inside the album are not reloaded or re-keyed.

#### Scenario: Entry replaced
- **WHEN** a rename succeeds
- **THEN** `AlbumsState.albums` contains the new name and href at the old position and no additional album listing was requested

#### Scenario: Renamed album moves to its sorted position
- **WHEN** Name A–Z is selected, the albums are "Apples", "Berries", "Cherries" and "Apples" is renamed to "Dates"
- **THEN** the albums are shown as "Berries", "Cherries", "Dates" without an additional album listing

#### Scenario: Last-modified date is kept
- **WHEN** an album with a known last-modified date is renamed while Last modified (newest first) is selected
- **THEN** the renamed album keeps that date and its place among the dated albums until the next Refresh

#### Scenario: Renamed album no longer matches the query
- **WHEN** the query is "summer" and the album "Summer" is renamed to "Holiday"
- **THEN** the renamed album is not shown until the query is changed or cleared, and it is shown under its new name then
