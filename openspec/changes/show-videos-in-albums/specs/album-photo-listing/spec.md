## ADDED Requirements

### Requirement: Album listings include videos
An album listing SHALL contain the album's photos and videos in server order. An entry SHALL count as a video when its content type is an allowed video type, or, only when its content type is missing or generic, when its file extension is an allowed video extension. Photo classification SHALL stay unchanged and take precedence. Other files and folders SHALL NOT be listed.

#### Scenario: Video with a video content type
- **WHEN** an album contains `clip.mp4` with content type `video/mp4`
- **THEN** it is listed as a video

#### Scenario: Video without a content type
- **WHEN** an album contains `clip.mov` whose content type is missing or `application/octet-stream`
- **THEN** it is listed as a video

#### Scenario: Video type not on the allow-list
- **WHEN** an album contains a file with content type `video/x-ms-wmv` or an `.avi` file without a content type
- **THEN** it is not listed

#### Scenario: Content type overrides the extension
- **WHEN** an album contains `notes.mp4` whose content type is `text/plain`
- **THEN** it is not listed

#### Scenario: Mixed album
- **WHEN** an album contains two photos, one video and one PDF file
- **THEN** the album shows three items, the video marked with a play indicator

#### Scenario: Album with only videos
- **WHEN** an album contains only videos
- **THEN** the videos are shown and the empty-album message is not shown

### Requirement: Video thumbnails never download the video
A video's thumbnail and viewer poster SHALL only be the server's preview image of that video (Nextcloud Memories preview when available, else the Nextcloud preview of its file id). Without such a preview (non-Nextcloud server, missing or unsafe file id, or a failing preview request) a placeholder icon SHALL be shown. The video file itself SHALL NOT be requested to show a thumbnail or poster, and preview resolution SHALL NOT add availability requests.

#### Scenario: Nextcloud server with previews
- **WHEN** an album on a Nextcloud server contains a video with a valid file id
- **THEN** its tile requests the preview image of that file id and shows it with a play indicator

#### Scenario: Plain WebDAV server
- **WHEN** an album on a server that is not Nextcloud contains a video
- **THEN** its tile shows a placeholder icon with a play indicator and no request is sent for it

#### Scenario: Preview fails
- **WHEN** the preview request of a video fails or returns something that is not an image
- **THEN** the tile shows the placeholder icon and the video file is not requested

#### Scenario: Unchanged availability checks
- **WHEN** the thumbnail addresses of an album with photos and videos are resolved on a Nextcloud server
- **THEN** at most one Memories availability request is sent, as for an album with photos only

## MODIFIED Requirements

### Requirement: Album loading shows progress
While an album's photo listing is being read, the album screen SHALL show a loading indicator that includes the number of photos and videos found so far. The photos and videos SHALL be shown together once the listing is complete; the album SHALL count as loaded only then.

#### Scenario: Running count while loading
- **WHEN** the server response for an album is still being received and photos or videos have already been found
- **THEN** the album screen shows the loading message with the number of items found so far, announced politely to accessibility services

#### Scenario: Listing completes
- **WHEN** the last photo or video has been read and the thumbnail addresses resolved
- **THEN** the loading indicator disappears, all photos and videos are shown at once and the album counts as fully loaded

#### Scenario: Empty album
- **WHEN** the listing completes without any photo or video
- **THEN** the empty-album message is shown, and it is not shown while the listing is still loading

#### Scenario: Screen recreated while loading
- **WHEN** the screen is recreated (for example by rotation) while the album is still loading
- **THEN** the loading continues without being restarted

#### Scenario: Retry from a review during loading does not start a second listing
- **WHEN** a duplicate or blur review asks to reload the album while its listing is still running without error
- **THEN** the running listing continues and no second listing request is sent

### Requirement: Album listing memory is bounded
The app SHALL NOT hold the complete server response as text or as a document tree. It SHALL read at most 32 MiB of (decompressed) response data and at most 20,000 media items (photos and videos together) per album listing, SHALL bound the size of every single entry, and SHALL list every photo or video address at most once.

#### Scenario: Very large response
- **WHEN** an album response contains more than 20,000 photos and videos together
- **THEN** the app stops reading after 20,000 items, shows those items as a truncated album and does not terminate

#### Scenario: Videos count towards the limit
- **WHEN** an album response contains 15,000 photos followed by 10,000 videos
- **THEN** the first 20,000 items in server order are shown and the album is marked as truncated

#### Scenario: Oversized response
- **WHEN** the response body exceeds 32 MiB
- **THEN** the app stops reading immediately, drops the incomplete entry and shows the complete entries read so far as a truncated album, or a generic error if no complete entry besides the album itself was read

#### Scenario: Truncated listing without photos
- **WHEN** a listing is truncated and none of its complete entries is a photo or video
- **THEN** the album screen says that the album could not be loaded completely instead of showing the empty-album message

#### Scenario: Hostile XML structure
- **WHEN** the response nests elements more than 32 levels deep, has an element with more than 64 attributes or 64 namespace declarations, or contains more than 64 KiB in one tag or one text node
- **THEN** reading fails fast (within seconds, without exhausting memory) with the generic localized error and Retry

#### Scenario: One oversized entry
- **WHEN** a single entry of the response has too many elements, too deep nesting or an overlong text value
- **THEN** that entry is skipped, the other photos and videos are shown and the skipped entry's address is not logged

#### Scenario: Duplicate entries
- **WHEN** the response lists the same photo or video address more than once
- **THEN** the item is shown once

#### Scenario: Oversized album folder listing
- **WHEN** the response listing the albums exceeds 32 MiB
- **THEN** the album list shows a localized "listing too large" error instead of a partial list
