# Spec Delta

## ADDED Requirements

### Requirement: Photos saved from an album are not marked as uploaded
A photo saved to the device from a remote album SHALL NOT be added to the uploaded-photos set: it is a new local item that was never uploaded from this device, and the marker is not re-checked against the server. It SHALL therefore show no cloud badge, SHALL stay visible when "show only new photos" is on, and SHALL be uploadable like any other photo.

#### Scenario: Saved photo in the grid
- **WHEN** a photo was saved from an album and the user opens the photos screen
- **THEN** the saved photo is shown without the cloud badge and without the "already uploaded" state

#### Scenario: Filter on
- **WHEN** "show only new photos" is on and a photo was saved from an album
- **THEN** the saved photo is shown
