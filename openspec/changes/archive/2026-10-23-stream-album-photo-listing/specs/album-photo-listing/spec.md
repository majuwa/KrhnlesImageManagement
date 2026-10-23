# Spec Delta

## Purpose
Defines how the photos of a remote album are listed and displayed so that very large albums neither exhaust memory nor leave the user without feedback.

## ADDED Requirements

### Requirement: Album loading shows progress
While an album's photo listing is being read, the album screen SHALL show a loading indicator that includes the number of photos found so far. The photos SHALL be shown together once the listing is complete; the album SHALL count as loaded only then.

#### Scenario: Running count while loading
- **WHEN** the server response for an album is still being received and photos have already been found
- **THEN** the album screen shows "Loading photos… N" with the number found so far, announced politely to accessibility services

#### Scenario: Listing completes
- **WHEN** the last photo has been read and the thumbnail addresses resolved
- **THEN** the loading indicator disappears, all photos are shown at once and the album counts as fully loaded

#### Scenario: Empty album
- **WHEN** the listing completes without any photo
- **THEN** the empty-album message is shown, and it is not shown while the listing is still loading

#### Scenario: Screen recreated while loading
- **WHEN** the screen is recreated (for example by rotation) while the album is still loading
- **THEN** the loading continues without being restarted

#### Scenario: Retry from a review during loading does not start a second listing
- **WHEN** a duplicate or blur review asks to reload the album while its listing is still running without error
- **THEN** the running listing continues and no second listing request is sent

### Requirement: Album listing memory is bounded
The app SHALL NOT hold the complete server response as text or as a document tree. It SHALL read at most 32 MiB of (decompressed) response data and at most 20,000 photos per album listing, SHALL bound the size of every single entry, and SHALL list every photo address at most once.

#### Scenario: Very large response
- **WHEN** an album response contains more than 20,000 photos
- **THEN** the app stops reading after 20,000 photos, shows those photos as a truncated album and does not terminate

#### Scenario: Oversized response
- **WHEN** the response body exceeds 32 MiB
- **THEN** the app stops reading immediately, drops the incomplete entry and shows the complete entries read so far as a truncated album, or a generic error if no complete entry besides the album itself was read

#### Scenario: Truncated listing without photos
- **WHEN** a listing is truncated and none of its complete entries is a photo
- **THEN** the album screen says that the album could not be loaded completely instead of showing the empty-album message

#### Scenario: Hostile XML structure
- **WHEN** the response nests elements more than 32 levels deep, has an element with more than 64 attributes or 64 namespace declarations, or contains more than 64 KiB in one tag or one text node
- **THEN** reading fails fast (within seconds, without exhausting memory) with the generic localized error and Retry

#### Scenario: One oversized entry
- **WHEN** a single entry of the response has too many elements, too deep nesting or an overlong text value
- **THEN** that entry is skipped, the other photos are shown and the skipped entry's address is not logged

#### Scenario: Duplicate entries
- **WHEN** the response lists the same photo address more than once
- **THEN** the photo is shown once

#### Scenario: Oversized album folder listing
- **WHEN** the response listing the albums exceeds 32 MiB
- **THEN** the album list shows a localized "listing too large" error instead of a partial list

### Requirement: Truncated albums are announced
When a photo listing was cut off by a limit, the album screen SHALL tell the user in a localized message that only part of the photos is shown, and the duplicate and blur review SHALL tell the user that their results may be incomplete. This SHALL NOT be presented as an error and SHALL NOT offer Retry.

#### Scenario: Album above the limit
- **WHEN** an album's listing is truncated
- **THEN** the shown photos are displayed together with a notice that not all photos are shown

#### Scenario: Review of a truncated album
- **WHEN** the duplicate or blur review is opened for a truncated album
- **THEN** it shows a notice that its results may be incomplete, and deletion applies only to the shown photos

### Requirement: Deletion and reviews use the complete listing only
Deletion, selection mode and the duplicate and blur review SHALL only operate on an album whose listing has completed (a truncated listing counts as complete). While the album is loading they SHALL NOT be available.

#### Scenario: Actions during loading
- **WHEN** the album is still loading
- **THEN** no photos, review buttons or selection mode are offered

### Requirement: A failing or malformed listing is reported as a typed error
If the response is not valid WebDAV XML, its root is not a WebDAV multistatus, it contains a document type declaration or an entity reference, it is interrupted, or the server answers with an error status, the app SHALL stop loading, discard the partially received photos, and show the same localized, plain-language error message and Retry as for other album load failures, without raw exception text.

#### Scenario: Malformed XML in the middle of a response
- **WHEN** the response becomes invalid XML after some photos were already read
- **THEN** loading stops, the album shows a generic localized error with Retry, and no partial photos are shown

#### Scenario: DOCTYPE in the response
- **WHEN** the response contains a document type declaration (with or without entity declarations, internal or external)
- **THEN** it is rejected as malformed and no entity is expanded or fetched

#### Scenario: Unsupported encoding
- **WHEN** the response declares an encoding the device does not support
- **THEN** it is rejected as malformed with the generic localized error

#### Scenario: Connection stalls
- **WHEN** no data arrives for the read timeout while the listing is being read, also before the first byte of the response body
- **THEN** the timeout error message is shown with Retry

### Requirement: Loading stops when it is no longer wanted
A running listing SHALL be stopped, including its blocked network read, when the user leaves the album, starts another load, or the server / account configuration changes or the user logs out. A stopped listing SHALL NOT publish photos.

#### Scenario: Logout during loading
- **WHEN** the user logs out while an album listing is being read
- **THEN** the request is cancelled promptly, no photo is published, and the album shows that the user is not logged in

#### Scenario: Cancellation while the read is blocked
- **WHEN** the listing is cancelled while the server has stopped sending data, before or in the middle of the response body
- **THEN** the network request is cancelled within a few seconds, well before the read timeout, and no error is shown

#### Scenario: Late failure of a stopped listing
- **WHEN** a stopped listing fails afterwards (for example its cancelled read surfaces as an error)
- **THEN** that failure is not shown and does not replace the "not logged in" / "server changed" message or the state of a newer load

#### Scenario: Review thumbnail downloads are bounded
- **WHEN** an image downloaded for the duplicate or blur review exceeds 32 MiB, or the review is left while a download is blocked
- **THEN** that download fails for this photo only (it is skipped) or is cancelled within a few seconds

#### Scenario: Unsafe entries in a streamed listing
- **WHEN** a streamed response contains entries whose address is outside the album or is unsafe
- **THEN** those entries are skipped exactly as before and never requested
