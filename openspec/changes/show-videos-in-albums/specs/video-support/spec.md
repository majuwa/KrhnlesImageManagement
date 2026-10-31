## ADDED Requirements

### Requirement: Duplicate and blur reviews ignore videos
The duplicate and blurry photo reviews SHALL analyse only the photos of an album: videos SHALL NOT be downloaded, analysed, counted in the review progress, shown as results or deleted by a review. The review actions SHALL be offered only when the loaded album contains at least one photo. Videos stay in the album unchanged by a review.

#### Scenario: Album with photos and a video
- **WHEN** the user starts the duplicate or blur review of an album with four photos and one video
- **THEN** the progress counts four photos, no request is sent for the video and the video is never a result

#### Scenario: Album with only videos
- **WHEN** the loaded album contains only videos
- **THEN** the duplicate and blur review actions are not offered

#### Scenario: Review deletion keeps videos
- **WHEN** the user deletes all results of a review in an album that also contains videos
- **THEN** the videos are still in the album and on the server

## REMOVED Requirements

### Requirement: Albums and reviews ignore videos
**Reason**: Album listings now show videos (with a play indicator, poster or placeholder and in-app playback); only the duplicate and blur reviews stay image-only.
**Migration**: Covered by "Album listings include videos" and "Video thumbnails never download the video" in `album-photo-listing`, "Duplicate and blur reviews ignore videos" in this capability and the new `album-video-playback` capability. No data migration is needed.
