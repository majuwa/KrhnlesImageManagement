## ADDED Requirements

### Requirement: Videos are deleted like photos
Videos in an album SHALL be selectable in selection mode and deleted from the server with exactly the same behaviour as photos: individual deletion, already-deleted files counting as deleted, only successfully deleted items leaving the album, one deletion at a time and the outcome on the album screen. The confirmation and progress texts SHALL use wording that covers photos and videos.

#### Scenario: Deleting a mixed selection
- **WHEN** the user selects two photos and one video and confirms the deletion
- **THEN** all three are deleted on the server individually, leave the album and the reported failure count is 0

#### Scenario: Video deletion fails
- **WHEN** the server rejects the deletion of the selected video with 403
- **THEN** the video stays in the album and the outcome reports one failure

#### Scenario: Video already gone
- **WHEN** the server answers the deletion of a video with 404
- **THEN** it counts as deleted and leaves the album

#### Scenario: Deleted video is gone from the viewer
- **WHEN** a video was deleted in selection mode and the user then opens the viewer
- **THEN** the viewer neither shows nor requests the deleted video
