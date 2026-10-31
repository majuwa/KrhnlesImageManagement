# Spec Delta

## Purpose

Helps users reuse the occasion folders they already have by suggesting recent occasion names and existing remote albums in the occasion dialog, so uploads do not end up in near-duplicate folders.

## ADDED Requirements

### Requirement: Suggestions are shown in both occasion dialogs
The occasion dialog of the photo-grid upload and of the share target SHALL show at most 5 folder suggestions below the name field whenever at least one suggestion matches, both while the field is empty and while the user types. When no suggestion matches, no suggestion area is shown.

#### Scenario: Empty field in the grid dialog
- **WHEN** the user opens the grid occasion dialog and 7 distinct recent occasions exist
- **THEN** the 5 newest recent occasions are shown as suggestions

#### Scenario: Share target dialog
- **WHEN** photos are shared into the app and the occasion dialog opens with recent occasion "Trip"
- **THEN** "Trip" is shown as a suggestion

#### Scenario: Nothing to suggest
- **WHEN** there is no upload history and no remote album
- **THEN** the dialog shows no suggestion area and works as before

#### Scenario: Auto date mode
- **WHEN** auto date folders are enabled and the user uploads from the grid
- **THEN** no occasion dialog and therefore no suggestions are shown

### Requirement: Recent occasions come from the upload history
Recent suggestions SHALL be the folder labels of upload-history entries, newest first, deduplicated case-insensitively (the newest spelling wins). Labels of auto-date (per-photo) uploads, blank labels, labels that are not valid occasion names and labels longer than the album name limit SHALL never be suggested.

#### Scenario: Newest first, case-insensitive dedupe
- **WHEN** the history contains "summer 2026" (older), "Trip" and "Summer 2026" (newest)
- **THEN** the recent suggestions are "Summer 2026" followed by "Trip"

#### Scenario: Auto date labels excluded
- **WHEN** the history contains the auto-date labels "2026/05/14" and "2026/04/30 – 2026/05/01" and the occasion "Trip"
- **THEN** only "Trip" is suggested

#### Scenario: Uploads into the base folder excluded
- **WHEN** a history entry has a blank folder label
- **THEN** it produces no suggestion

#### Scenario: Unreadable history
- **WHEN** reading the upload history fails or does not answer in time
- **THEN** no recent suggestions are shown and the dialog still works

### Requirement: Existing remote albums are suggested best-effort
When a valid server configuration is stored, the app SHALL load the folder names directly below the configured base folder with the existing bounded album listing and add them as suggestions after the recent ones. A failure, an oversized listing or exceeding the overall timeout SHALL be silently ignored; no error, progress or server text is shown.

#### Scenario: Remote albums added
- **WHEN** the server lists the albums "Birthday" and "Trip" and the history contains only "Trip"
- **THEN** the suggestions are "Trip" followed by "Birthday"

#### Scenario: Server unreachable
- **WHEN** the album listing fails with a connection, certificate or HTTP error
- **THEN** only recent suggestions are shown and no error message appears

#### Scenario: Server too slow
- **WHEN** the album listing does not finish within the overall suggestion timeout
- **THEN** the listing is cancelled, only recent suggestions are shown and the dialog was usable the whole time

#### Scenario: Not configured
- **WHEN** no valid server configuration is stored
- **THEN** no request is sent and only recent suggestions are shown

### Requirement: The dialog is never blocked by suggestions
Opening, typing in, confirming and cancelling the occasion dialog SHALL never wait for suggestions. Recent suggestions SHALL appear as soon as the history is read; remote suggestions SHALL be merged in when available.

#### Scenario: Confirm while loading
- **WHEN** the remote listing is still running and the user enters a valid name and taps Upload
- **THEN** the upload is queued immediately with the entered name

#### Scenario: Cancel while loading
- **WHEN** the remote listing is still running and the user cancels the dialog
- **THEN** the dialog closes immediately and nothing is uploaded

### Requirement: Remote suggestions belong to the configured server only
Remote suggestions SHALL only be shown for the server, account and base folder configured at the time they are shown. After a logout or a change of server, account or base folder, suggestions listed for the previous configuration SHALL NOT be shown, and a listing that finishes after such a change SHALL be discarded. Remote folder names SHALL be the decoded folder name used in upload paths, never a different server-supplied display name.

#### Scenario: Server changed between dialogs
- **WHEN** remote suggestions were loaded for server A, the user switches to server B and opens the dialog again
- **THEN** no album of server A is suggested

#### Scenario: Configuration changed during the listing
- **WHEN** the configuration changes while the remote listing is running
- **THEN** the listing result is discarded

#### Scenario: Encoded folder name
- **WHEN** the server lists an album whose href ends in "Summer%202026/"
- **THEN** the suggestion reads "Summer 2026"

#### Scenario: Unsafe or nested entries
- **WHEN** the listing contains an entry that is not exactly one level below the base folder or whose name is not a valid occasion name
- **THEN** that entry is not suggested

### Requirement: Remote spelling wins for case variants
When a recent occasion and a remote album differ only in letter case, the app SHALL suggest one entry at the recent occasion's rank with the remote album's exact spelling. Suggestions SHALL be deduplicated case-insensitively across both sources.

#### Scenario: Case variant
- **WHEN** the recent occasion is "summer 2026" and the server has the album "Summer 2026"
- **THEN** exactly one suggestion "Summer 2026" is shown at the recent occasion's position

### Requirement: Typing filters suggestions
While the field contains text, the app SHALL show only suggestions containing the trimmed text case-insensitively, names starting with it before other matches, otherwise in the ranked order. A suggestion exactly equal to the trimmed text SHALL NOT be shown; at most 5 suggestions are shown.

#### Scenario: Case-insensitive filter
- **WHEN** the suggestions are "Trip", "Summer 2026" and "Birthday" and the user types "SUM"
- **THEN** only "Summer 2026" is shown

#### Scenario: Prefix before substring
- **WHEN** the ranked suggestions are "Late summer" and "Summer 2026" and the user types "summer"
- **THEN** "Summer 2026" is shown before "Late summer"

#### Scenario: Case-only difference is still suggested
- **WHEN** the user typed "summer 2026" and "Summer 2026" exists
- **THEN** "Summer 2026" is shown as a suggestion

#### Scenario: Exact match hidden
- **WHEN** the user typed "Trip" and "Trip" is a suggestion
- **THEN** "Trip" is not shown

### Requirement: Tapping a suggestion fills the field
Tapping a suggestion SHALL replace the field's text with the suggestion and place the cursor at its end. It SHALL NOT confirm the upload. Occasion validation, trimming and the enabled state of the Upload button SHALL behave exactly as for typed text.

#### Scenario: Fill and confirm
- **WHEN** the user taps the suggestion "Trip" and then Upload
- **THEN** the upload is queued into the folder "Trip"

#### Scenario: No implicit upload
- **WHEN** the user taps a suggestion
- **THEN** no upload is queued until Upload is tapped

#### Scenario: Text survives rotation
- **WHEN** the user taps a suggestion and rotates the device
- **THEN** the field still contains the suggestion

### Requirement: Suggestions are accessible
Each suggestion SHALL be exposed to accessibility services as a button whose text is the folder name and whose click action has a localized label. The suggestion area SHALL have a localized heading. All suggestion texts other than folder names SHALL come from string resources, and no icon inside a suggestion carries a content description.

#### Scenario: TalkBack semantics
- **WHEN** a suggestion "Trip" is inspected with accessibility semantics
- **THEN** it has role Button, text "Trip" and a click action whose label is the localized "use as folder name" text

#### Scenario: Activating with TalkBack
- **WHEN** the click action of a suggestion is performed through accessibility
- **THEN** the field is filled exactly as for a tap

### Requirement: Suggestion data stays local and private
Suggestion loading SHALL NOT write any new persistent data, SHALL NOT log folder names, hrefs or server responses (only exception types), and SHALL send requests only to the configured server with its existing authentication rules.

#### Scenario: Logging on failure
- **WHEN** the remote listing fails
- **THEN** at most the exception type is logged

#### Scenario: Nothing persisted
- **WHEN** suggestions were loaded and the app is restarted without an upload
- **THEN** no suggestion-specific data was stored and the history is unchanged
