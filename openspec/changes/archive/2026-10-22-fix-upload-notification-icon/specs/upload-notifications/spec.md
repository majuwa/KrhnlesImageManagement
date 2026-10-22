# Spec Delta

## Purpose

Defines how background-upload notifications (progress, completion, failure) present themselves so they are legible in the status bar and notification shade.

## ADDED Requirements

### Requirement: Upload notifications use a dedicated status-bar icon
Every upload notification (progress, completion, failure) SHALL use a dedicated monochrome, single-colour glyph as its small icon, and SHALL NOT use the launcher or adaptive-icon artwork.

#### Scenario: Progress notification icon
- **WHEN** an upload is running and the progress notification is shown
- **THEN** its small icon is the dedicated notification glyph, not the launcher icon

#### Scenario: Completion notification icon
- **WHEN** an upload finishes, with or without failed photos
- **THEN** the completion notification uses the same dedicated notification glyph

#### Scenario: Failure notification icon
- **WHEN** an upload fails entirely
- **THEN** the failure notification uses the same dedicated notification glyph
