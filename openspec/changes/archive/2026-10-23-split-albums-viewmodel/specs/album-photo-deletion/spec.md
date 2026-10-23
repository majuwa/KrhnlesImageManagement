# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: Leaving a review while deleting is not possible`
- TO: `### Requirement: Leaving a screen while deleting is not possible`

## MODIFIED Requirements

### Requirement: Leaving a screen while deleting is not possible
While the duplicate review, the blur review or the album screen (selection mode) is deleting photos, Back and the screen's top-bar navigation buttons (back, clear selection) SHALL NOT leave the screen or abandon the deletion. When the deletion started by a review has finished, the review SHALL navigate back exactly once; the album screen stays open and shows the outcome.

#### Scenario: Back during deletion
- **WHEN** the user presses Back while the review shows its deleting state
- **THEN** the review stays open and navigates back once the deletion has finished

#### Scenario: Back during a selection-mode deletion
- **WHEN** the user confirms deleting the selected photos on the album screen and presses Back while the deletion runs
- **THEN** the album screen stays open, the back and clear-selection buttons are disabled, and every selected photo is still deleted
