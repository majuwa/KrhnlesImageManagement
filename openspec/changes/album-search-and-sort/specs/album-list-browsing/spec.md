# Spec Delta

## Purpose

Lets users find an album in a long Albums tab quickly by searching its name and by ordering the list by name or by the server's last-modified date, without changing what the album list contains or how albums are opened, renamed or deleted.

## ADDED Requirements

### Requirement: Albums can be searched by name
The Albums tab SHALL offer a search field that filters the shown albums by display name while the user types. An album SHALL match when its folded name contains the folded, trimmed query; folding ignores letter case and diacritics. A blank query SHALL show all albums. The query SHALL accept at most 255 characters.

#### Scenario: Case-insensitive match
- **WHEN** the albums are "Summer 2025", "Winter" and "SUMMER camp" and the user types "summer"
- **THEN** only "Summer 2025" and "SUMMER camp" are shown

#### Scenario: Diacritics are ignored
- **WHEN** the albums are "Café Paris", "Straße" and "Zürich" and the user types "cafe", "strasse" or "zur" respectively
- **THEN** "Café Paris", "Straße" or "Zürich" respectively is shown

#### Scenario: Blank query
- **WHEN** the query is empty or consists only of spaces
- **THEN** all albums are shown

#### Scenario: Query is matched as one substring
- **WHEN** the user types "  mer 20 "
- **THEN** "Summer 2025" is shown and "Winter" is not

#### Scenario: Overlong input
- **WHEN** the user pastes 300 characters into the search field
- **THEN** the field keeps only the first 255 characters

### Requirement: A search without matches is explained
When albums are loaded but none matches a non-blank query, the Albums tab SHALL show a localized message that no album matches the query, instead of the empty-albums message, and SHALL keep the search field visible so the query can be changed or cleared.

#### Scenario: No match
- **WHEN** albums are loaded and the user types a name no album contains
- **THEN** a "no albums match" message naming the query is shown, the search field stays visible and the empty-albums message is not shown

#### Scenario: Server has no albums
- **WHEN** the album listing is empty
- **THEN** the empty-albums message is shown as before

### Requirement: The search query is kept while the Albums tab exists
The search query SHALL survive screen recreation (rotation), process death, switching tabs, opening and leaving an album, Refresh, rename and delete. It SHALL NOT be persisted beyond the Albums tab's saved state and SHALL be applied to every newly loaded list.

#### Scenario: Rotation
- **WHEN** the user typed "summer" and rotates the device
- **THEN** the field still contains "summer" and the list is still filtered

#### Scenario: Refresh keeps the filter
- **WHEN** the user typed "summer" and taps Refresh
- **THEN** the reloaded list is filtered by "summer"

#### Scenario: Back from an album
- **WHEN** the user filters, opens an album and navigates back
- **THEN** the same query and filtered list are shown

### Requirement: Albums can be sorted
The Albums tab SHALL offer a sort menu with the options Name A–Z, Name Z–A, Last modified (newest first) and Last modified (oldest first). Name A–Z SHALL be the default. The chosen option SHALL apply to the filtered list and to every newly loaded list.

#### Scenario: Default order
- **WHEN** the sort was never changed and the server lists "b", "C", "a"
- **THEN** the albums are shown as "a", "b", "C"

#### Scenario: Name Z–A
- **WHEN** the user selects Name Z–A
- **THEN** the albums are shown as "C", "b", "a"

#### Scenario: Sort and filter combine
- **WHEN** Name Z–A is selected and the query is "summer"
- **THEN** only matching albums are shown, in Z–A order

### Requirement: Name order is case- and diacritic-insensitive and deterministic
Name sorting SHALL compare folded names (case and diacritics ignored), independent of the device language. Albums with equal folded names SHALL be ordered by their exact name and then by their address, so the order never depends on the server's listing order.

#### Scenario: Diacritics sort with their base letter
- **WHEN** the albums are "Zoo", "Äpfel" and "Birnen" and Name A–Z is selected
- **THEN** they are shown as "Äpfel", "Birnen", "Zoo"

#### Scenario: Equal folded names
- **WHEN** two albums are named "Cafe" and "Café"
- **THEN** their relative order is the same for every server listing order

### Requirement: Last-modified order uses the server's folder date
Last-modified sorting SHALL use the last-modified date the server reports for each album folder in the album listing. Albums with equal dates SHALL be ordered by name A–Z. Albums without a usable date SHALL be shown after all dated albums in both directions, ordered by name A–Z.

#### Scenario: Newest first
- **WHEN** "A" was modified on 2026-05-01, "B" on 2026-06-01 and "C" has no date, and Last modified (newest first) is selected
- **THEN** the albums are shown as "B", "A", "C"

#### Scenario: Oldest first keeps undated albums last
- **WHEN** the same albums are sorted by Last modified (oldest first)
- **THEN** the albums are shown as "A", "B", "C"

### Requirement: Unusable folder dates are treated as unknown
The album listing SHALL request each folder's last-modified date and accept it only as an RFC 1123 date. A missing, empty, malformed or otherwise unparsable value SHALL make the date unknown without failing the listing or skipping the album; an entry whose value exceeds the existing per-entry size bounds SHALL be skipped like any other oversized entry. The photo listing SHALL NOT change.

#### Scenario: Valid date
- **WHEN** an album folder reports `Thu, 14 May 2026 10:00:00 GMT`
- **THEN** that instant is used for last-modified sorting

#### Scenario: Malformed date
- **WHEN** an album folder reports `yesterday` or no last-modified date
- **THEN** the album is listed and sorted as having an unknown date

#### Scenario: Oversized date value
- **WHEN** an album entry's last-modified value exceeds the per-entry text limit
- **THEN** that entry is skipped and the other albums are listed

### Requirement: The chosen sort is remembered
The chosen sort option SHALL take effect immediately and SHALL be stored so that it is used again after the app is restarted. If storing fails, the choice SHALL still apply for the rest of the session and no error text from the storage SHALL be shown.

#### Scenario: Restart
- **WHEN** the user selects Last modified (newest first) and restarts the app
- **THEN** the Albums tab uses Last modified (newest first)

#### Scenario: Storage failure
- **WHEN** storing the sort option fails
- **THEN** the list stays sorted by the selected option and the app does not crash

### Requirement: Album management is unchanged by search and sort
Search and sort SHALL only change which loaded albums are shown and in which order. Opening, renaming and deleting an album, the loading, error and busy states, and Refresh (still ignored while a rename runs) SHALL behave as before and SHALL act on the album the user picked.

#### Scenario: Delete from a filtered list
- **WHEN** the list is filtered and the user deletes a shown album
- **THEN** exactly that album is deleted and removed, and the query is kept

#### Scenario: Refresh while renaming
- **WHEN** a rename runs and the user taps Refresh with a query and sort active
- **THEN** no album listing is requested

#### Scenario: Busy states hide the list
- **WHEN** the album list is loading, an album is being renamed or deleted
- **THEN** the progress indicator is shown instead of the list and the search field, as before

### Requirement: Search field and sort menu are accessible
The search field SHALL have a visible localized label and, while it contains text, a clear button with a localized description. The sort button SHALL have a localized description and announce the current option. Each sort option SHALL be exposed as a radio button with its selected state. All texts SHALL come from string resources; decorative icons SHALL have no description.

#### Scenario: Screen reader on the sort menu
- **WHEN** a screen reader user opens the sort menu
- **THEN** each option is announced with its name, as a radio button, and the current option as selected

#### Scenario: Clearing the query
- **WHEN** the field contains text and the user activates "Clear search"
- **THEN** the query is empty and all albums are shown
