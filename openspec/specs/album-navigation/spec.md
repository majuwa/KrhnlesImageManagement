# album-navigation Specification

## Purpose

Defines how screens opened from the Albums tab (album detail, fullscreen viewer, duplicate review, blur review) share the Albums tab's state so they always operate on the live Albums session.

## Requirements

### Requirement: Album sub-screens share the live Albums tab state
Every screen opened from the Albums tab SHALL operate on the same Albums state instance as the Albums tab entry that is currently on the navigation back stack, and SHALL NOT keep using state from an Albums entry that has since been removed or replaced.

#### Scenario: Sub-screen shares state with the Albums tab
- **WHEN** the user opens an album and then the duplicate or blur review from it
- **THEN** the review screen uses the same Albums state instance as the Albums tab and the album detail screen

#### Scenario: Albums entry is replaced
- **WHEN** the Albums entry on the back stack is removed and a new Albums entry is created, and the user then opens a sub-screen again
- **THEN** the sub-screen binds to the new Albums entry's state, not to the removed entry's state

### Requirement: Back-stack entry lookups are not hidden from static analysis
The application module SHALL NOT disable or suppress the `UnrememberedGetBackStackEntry` lint check by any suppression mechanism (suppress annotations or `noinspection` comments in any source set, Gradle lint options, `lint.xml`, or a lint baseline); lookups of another destination's back-stack entry during composition MUST be remembered with a back-stack-entry key so that lint can verify them.

#### Scenario: Suppression annotation is reintroduced
- **WHEN** a Kotlin or Java source file in any source set of the app module suppresses `UnrememberedGetBackStackEntry` via an annotation (including file-level or fully qualified forms) or a `noinspection` comment
- **THEN** the unit test suite fails

#### Scenario: Check is disabled in lint configuration
- **WHEN** the app module's Gradle lint options, a `lint.xml`, or a lint baseline reference `UnrememberedGetBackStackEntry`
- **THEN** the unit test suite fails

### Requirement: Parent entry lookup is cached per current entry
Resolving the Albums back-stack entry from a sub-screen SHALL be performed once per current back-stack entry and reused across recompositions, and SHALL be performed again when the current back-stack entry changes.

#### Scenario: Recomposition with the same entry
- **WHEN** a sub-screen recomposes while its own back-stack entry is unchanged
- **THEN** the Albums entry is not looked up again

#### Scenario: Current entry changes
- **WHEN** the sub-screen's current back-stack entry changes
- **THEN** the Albums entry is looked up again
