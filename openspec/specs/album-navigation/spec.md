# album-navigation Specification

## Purpose

Defines how screens opened from the Albums tab (album detail, fullscreen viewer, duplicate review, blur review) share the Albums tab's state so they always operate on the live Albums session.

## Requirements

### Requirement: Back-stack entry lookups are not hidden from static analysis
The application module SHALL NOT disable or suppress the `UnrememberedGetBackStackEntry` lint check by any suppression mechanism (suppress annotations or `noinspection` comments in any source set, Gradle lint options, `lint.xml`, or a lint baseline); lookups of another destination's back-stack entry during composition MUST be remembered with a back-stack-entry key so that lint can verify them.

#### Scenario: Suppression annotation is reintroduced
- **WHEN** a Kotlin or Java source file in any source set of the app module suppresses `UnrememberedGetBackStackEntry` via an annotation (including file-level or fully qualified forms) or a `noinspection` comment
- **THEN** the unit test suite fails

#### Scenario: Check is disabled in lint configuration
- **WHEN** the app module's Gradle lint options, a `lint.xml`, or a lint baseline reference `UnrememberedGetBackStackEntry`
- **THEN** the unit test suite fails

### Requirement: Parent entry lookup is cached per current entry
Resolving the parent back-stack entry (the album detail entry) from a sub-screen SHALL be performed once per current back-stack entry and reused across recompositions, and SHALL be performed again when the current back-stack entry changes.

#### Scenario: Recomposition with the same entry
- **WHEN** a sub-screen recomposes while its own back-stack entry is unchanged
- **THEN** the parent entry is not looked up again

#### Scenario: Current entry changes
- **WHEN** the sub-screen's current back-stack entry changes
- **THEN** the parent entry is looked up again

#### Scenario: Parent route with arguments
- **WHEN** the parent destination's route declares arguments (such as the album href)
- **THEN** the sub-screen resolves the parent entry on the back stack by that route pattern

### Requirement: Album sub-screens share the live album state
Every screen opened from an album (fullscreen viewer, duplicate review, blur review) SHALL operate on the same album state instance as the album detail entry it was opened from that is currently on the navigation back stack, and SHALL NOT keep using state from an album detail entry that has since been removed or replaced.

#### Scenario: Sub-screen shares state with the album detail screen
- **WHEN** the user opens an album and then the duplicate review, the blur review or the fullscreen viewer from it
- **THEN** that screen uses the same album state instance as the album detail screen, so photos deleted from the review are no longer shown on the album detail screen

#### Scenario: Album detail entry is replaced
- **WHEN** the album detail entry on the back stack is removed and a new album detail entry is created, and the user then opens a sub-screen again
- **THEN** the sub-screen binds to the new album detail entry's state, not to the removed entry's state

### Requirement: Review scan state belongs to the review screen
The scan state of the duplicate and blur review SHALL exist only while the review screen is on the back stack: it is discarded, and a running scan is stopped, when the review is left. The album list SHALL be owned by the Albums tab entry and is not needed by album sub-screens.

#### Scenario: Leaving a review discards its scan
- **WHEN** the user leaves the duplicate or blur review and opens it again
- **THEN** the review starts a fresh scan of the album's current photos

### Requirement: Recreating a review screen keeps its state
A review SHALL start its scan automatically only while it has no scan state yet (idle). Recreating the review screen (e.g. after a rotation) SHALL NOT restart a running scan, SHALL NOT discard shown results or the user's selection, and SHALL NOT interrupt or leave a running deletion. After a failed scan, a new scan SHALL start only when the user taps Retry.

#### Scenario: Rotation while results are shown
- **WHEN** the review shows its results, the user changes the selection and rotates the device
- **THEN** the same results and the user's selection are still shown and no new scan runs

#### Scenario: Rotation while deleting
- **WHEN** the review is deleting photos and the device is rotated
- **THEN** the review keeps showing the deleting state, Back stays blocked and the review navigates back once the deletion has finished

#### Scenario: Recreating the album screen
- **WHEN** the album screen is recreated while the album is loaded or loading, or while photos are being deleted
- **THEN** the album is not reloaded and deleted photos do not reappear

#### Scenario: Retry after a failed scan
- **WHEN** the scan failed and the user taps Retry
- **THEN** a new scan starts; recreating the screen without tapping Retry does not start one
