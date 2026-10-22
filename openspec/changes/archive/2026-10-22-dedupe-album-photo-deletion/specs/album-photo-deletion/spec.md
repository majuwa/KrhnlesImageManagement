# Spec Delta

## Purpose

Defines how photos are deleted from a remote album — from the album selection mode, the duplicate review and the blur review — so that all three entry points behave identically with respect to failures, already-deleted photos, the album photo list, progress indication, outcome messaging and concurrent requests.

## ADDED Requirements

### Requirement: All photo deletion entry points share one deletion behaviour
Deleting photos from the album selection mode, the duplicate review and the blur review SHALL behave identically: each requested photo is deleted from the server individually, and the caller is told how many individual deletions failed.

#### Scenario: All deletions succeed
- **WHEN** the user deletes photos from any of the three entry points and the server accepts every deletion
- **THEN** every requested photo is deleted on the server and the reported failure count is 0

#### Scenario: Some deletions fail
- **WHEN** the user deletes N photos from any of the three entry points and the server rejects K of them
- **THEN** deletion is still attempted for all N photos and the reported failure count is K

#### Scenario: Nothing to delete
- **WHEN** a deletion is requested with an empty photo list from any entry point
- **THEN** no server request is made, the reported failure count is 0, and the entry point's progress indication is not left active

### Requirement: Photos already deleted on the server count as deleted
A photo deletion that the server answers with `404 Not Found` or `410 Gone` SHALL count as a successful deletion, because the photo no longer exists on the server. Other non-success statuses SHALL count as failures.

#### Scenario: Photo was deleted from another device
- **WHEN** the user deletes a photo and the server answers `404` or `410`
- **THEN** the deletion is not counted as a failure and the photo leaves the album list

#### Scenario: Server rejects the deletion
- **WHEN** the server answers the deletion with another non-success status (e.g. `403` or `500`)
- **THEN** the deletion counts as one failure and the photo stays in the album list

### Requirement: Only successfully deleted photos leave the album list
After a deletion finishes, the album photo list and its thumbnails SHALL no longer contain the photos that were deleted on the server, and SHALL still contain every photo whose deletion failed as well as every photo that was not part of the request.

#### Scenario: Partial failure keeps the failed photo visible
- **WHEN** the user deletes photos A and B and only the deletion of B fails
- **THEN** A is removed from the album list and its thumbnail, B and all other photos remain in the album list with their thumbnails

#### Scenario: All deletions fail
- **WHEN** every requested deletion fails
- **THEN** the album list is unchanged

### Requirement: Deletion progress indication is always cleared
While a deletion runs, the entry point that started it SHALL indicate that photos are being deleted (album selection mode: deleting flag; duplicate review and blur review: deleting state), and SHALL clear that indication when the deletion ends — also when it fails or is cancelled — before the result is published. The duplicate and blur review return to their idle state, unless a new scan was started meanwhile.

#### Scenario: Selection-mode deletion
- **WHEN** the user deletes selected photos from the album view
- **THEN** the deleting flag is set while the deletion runs and is cleared afterwards

#### Scenario: Duplicate or blur review deletion
- **WHEN** the user confirms a deletion in the duplicate or blur review
- **THEN** the review enters its deleting state while the deletion runs and returns to idle afterwards

#### Scenario: Result is published after progress is cleared
- **WHEN** the result of a deletion is published
- **THEN** the entry point's progress indication has already been cleared

### Requirement: The deletion outcome is shown on the album screen
When a deletion with at least one failure finishes, the app SHALL show a message with the number of photos that could not be deleted (localized, count-dependent) on the album detail screen — also when the deletion was started from the duplicate or blur review, which returns to the album detail screen after the deletion. Each outcome SHALL be shown at most once. No message is shown when nothing failed.

#### Scenario: Failure in the duplicate or blur review
- **WHEN** the user confirms a deletion in the duplicate or blur review and K > 0 deletions fail
- **THEN** the review returns to the album detail screen, which shows "K photo(s) could not be deleted."

#### Scenario: Failure in selection mode
- **WHEN** the user deletes selected photos on the album detail screen and K > 0 deletions fail
- **THEN** the album detail screen shows "K photo(s) could not be deleted." and the selection is cleared

#### Scenario: Outcome is shown once
- **WHEN** the outcome has been shown
- **THEN** it is consumed and not shown again, e.g. when the album screen is shown again later

### Requirement: Leaving a review while deleting is not possible
While the duplicate or blur review is deleting photos, Back and the top-bar back button SHALL NOT leave the review. When the deletion it started has finished, the review SHALL navigate back exactly once.

#### Scenario: Back during deletion
- **WHEN** the user presses Back while the review shows its deleting state
- **THEN** the review stays open and navigates back once the deletion has finished

### Requirement: Deletion failures never crash the app
Any failure during a deletion other than cancellation SHALL be reported as failed deletions instead of terminating the app. A failing photo is counted individually; if the deletion cannot start at all, every requested photo counts as failed.

#### Scenario: Invalid server configuration
- **WHEN** the user deletes N photos and the server connection cannot be prepared
- **THEN** the app does not crash, the reported failure count is N and the album list is unchanged

#### Scenario: Exception while deleting one photo
- **WHEN** deleting one of the requested photos throws an exception
- **THEN** that photo counts as one failure and the remaining photos are still deleted

### Requirement: Cancellation is not a failure
A cancelled deletion SHALL stop before the next photo, SHALL NOT count the cancellation as a failure, and SHALL NOT publish a result.

#### Scenario: Deletion is cancelled
- **WHEN** the deletion is cancelled while it runs
- **THEN** no further photo is deleted, no failure is counted and no result is published

### Requirement: Only one photo deletion runs at a time
While a photo deletion is running, further deletion requests from any entry point SHALL be ignored and reported to the caller as not started. Once the running deletion has finished, new deletions SHALL be accepted again.

#### Scenario: Double confirmation
- **WHEN** the user triggers a deletion twice in quick succession
- **THEN** each photo is deleted on the server only once

#### Scenario: Guard is released
- **WHEN** a deletion has finished
- **THEN** the next deletion request is started normally
