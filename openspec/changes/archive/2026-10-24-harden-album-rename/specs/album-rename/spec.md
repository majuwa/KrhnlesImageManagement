## ADDED Requirements

### Requirement: Rename is offered from the album list
Long-pressing an album in the Albums tab SHALL offer a "Rename" action that opens a dialog pre-filled with the album's current name.

#### Scenario: Dialog pre-filled
- **WHEN** the user long-presses an album named "Summer 2025" and chooses Rename
- **THEN** a dialog opens whose text field contains "Summer 2025"

### Requirement: Invalid names are rejected before any request
The new name SHALL be trimmed and rejected with a localized inline message, without a server request, when it is blank, equal to the current name, `.` or `..`, contains `/`, `\` or NUL (the same rules as occasion names), or is longer than 255 UTF-8 bytes. The repository SHALL enforce the same rules independently of the dialog.

#### Scenario: Blank name
- **WHEN** the user confirms an empty or whitespace-only name
- **THEN** the dialog stays open with a "please enter a name" message and no request is sent

#### Scenario: Unchanged name
- **WHEN** the user confirms the current name (also with surrounding spaces)
- **THEN** the dialog stays open with a "choose a different name" message and no request is sent

#### Scenario: Name too long
- **WHEN** the user enters a name of more than 255 UTF-8 bytes
- **THEN** the dialog stays open with a "name too long" message and no request is sent

#### Scenario: Forbidden characters
- **WHEN** the user enters a name containing `/` or `\`, or `..`
- **THEN** the dialog stays open with an "invalid characters" message and no request is sent

### Requirement: Rename moves the folder and never overwrites
Renaming SHALL send one cancellable WebDAV `MOVE` from the album's validated collection URL (exactly one level below the configured base folder, with trailing slash) to a destination URL built on the configured origin with the new name as a single, encoded path segment in the same parent folder, with `Overwrite: F`. The album SHALL NOT be re-created (no MKCOL / copy / delete) and an unsafe source href SHALL fail without any request.

#### Scenario: Successful rename
- **WHEN** the user renames "Summer 2025" to "Autumn 2025" and the server answers 201
- **THEN** exactly one MOVE request with `Destination` ending in `/Autumn%202025` and `Overwrite: F` is sent

### Requirement: Only 201 and 204 count as MOVE success
Any other status, including redirects, SHALL be a failure; a 2xx other than 201 / 204 SHALL be reported as `AlbumsError.RenamePartial` and the list reloaded. The repository SHALL trim the new name itself.

#### Scenario: Redirect is not success
- **WHEN** the server answers the MOVE with 301
- **THEN** the rename fails and the list is unchanged

#### Scenario: Unexpected 2xx
- **WHEN** the server answers the MOVE with another 2xx such as 207
- **THEN** a "rename may be incomplete" message is shown and the album list is reloaded

#### Scenario: Blank source segment
- **WHEN** the album's href is `<base>/%20/A/` or its segment is blank
- **THEN** no request is sent and the rename fails (blank segments, `\` and control characters are rejected, never filtered)

#### Scenario: Unsafe source href
- **WHEN** the album's href is outside the base folder or contains `..`
- **THEN** no request is sent and the rename fails

### Requirement: Existing target name is reported, not overwritten
When the server answers `412 Precondition Failed`, the existing album SHALL stay untouched and the user SHALL see a message that an album with that name already exists (`AlbumsError.AlreadyExists`). 404 and 409 (destination parent missing) SHALL be reported as "album no longer exists", 400 / 403 as "server rejected the name or no permission", and other failures (401, 423, timeout, TLS, unreachable, other status) through the existing `AlbumsError` mapping. Exception text is never shown.

#### Scenario: Name already exists
- **WHEN** the server answers the MOVE with 412
- **THEN** the album list is unchanged and an "album already exists" message is shown

#### Scenario: Album was removed elsewhere
- **WHEN** the server answers 404 or 409
- **THEN** the list is unchanged and an "album no longer exists" message suggesting a refresh is shown

#### Scenario: Server rejects the name
- **WHEN** the server answers 400 or 403
- **THEN** a "server rejected the name or no permission" message is shown

### Requirement: The album list updates in place
On success the renamed album SHALL replace the old entry (same position, new name and href) without reloading the album list. While the rename runs the grid is replaced by a progress indicator. Photos inside the album are not reloaded or re-keyed.

#### Scenario: Entry replaced
- **WHEN** a rename succeeds
- **THEN** `AlbumsState.albums` contains the new name and href at the old position and no additional album listing was requested

### Requirement: Rename is a guarded one-shot
At most one rename SHALL run at a time (re-entry ignored while active, no second request). It SHALL be bounded by a 30 s `withTimeoutOrNull` around a cancellable call, convert every failure into a result, and always release its busy state. After a timeout the message SHALL say the rename may still have completed and the album list SHALL be reloaded automatically. Refresh SHALL be ignored while a rename runs. A cancelled rename SHALL NOT publish a result.

#### Scenario: Double confirm
- **WHEN** a rename is running and another rename is requested
- **THEN** the second request is ignored and only one MOVE is sent

#### Scenario: Server never answers
- **WHEN** the MOVE does not complete within the timeout
- **THEN** the in-flight call is cancelled, the busy state is cleared, a timeout message is shown and the album list is reloaded

#### Scenario: Refresh during rename
- **WHEN** the user taps Refresh while a rename runs
- **THEN** no album listing is requested

### Requirement: Credentials stay on the configured origin
`WebDavClient` SHALL add `Authorization` only to requests whose URL has the same origin as the configured server URL.

#### Scenario: Redirect to another host
- **WHEN** a request is redirected to another host or port
- **THEN** the redirected request carries no `Authorization` header

### Requirement: The rename outcome survives configuration changes
The Albums screen SHALL consume the rename outcome only after its snackbar was shown.

#### Scenario: Rotation before the snackbar
- **WHEN** the screen is cancelled before the snackbar was shown
- **THEN** the outcome stays available for the next composition

### Requirement: Rename dialog and results are accessible
The dialog text field SHALL have a visible label, announce validation errors as supporting text, and the result messages SHALL be delivered via a snackbar from the Albums screen. No file or album name SHALL be used as a `contentDescription`.

#### Scenario: Error announced
- **WHEN** validation fails
- **THEN** the field is in error state and the message is readable as its supporting text
