# Spec Delta

## ADDED Requirements

### Requirement: Photos saved from an album are never removal candidates
Saving a photo from a remote album SHALL NOT create or change a verified-upload record. A saved photo SHALL therefore never be offered or moved to the trash by "Remove uploaded photos from device", even though an identical file exists on the server, until the user uploads it and that upload is verified.

#### Scenario: Saved photo only
- **WHEN** the only photos on the device that exist on the server were saved from an album
- **THEN** the removal check finds nothing to remove

#### Scenario: Saved copy of a verified upload
- **WHEN** a verified photo is still on the device and the user saves the same photo from its album again
- **THEN** only the originally uploaded photo can be a candidate, the saved copy is not, and the original's record is unchanged
