# Spec Delta

## ADDED Requirements

### Requirement: Album sort preference lifecycle
The album sort preference SHALL be stored by option name as a plain preference in the credentials DataStore without a `credentials_version` bump; a missing, unknown or wrongly typed value SHALL read as Name A–Z. It SHALL be reset to Name A–Z by logout and SHALL NOT be restored on a new device. Writing it SHALL NOT re-emit or re-decrypt the credential flows.

#### Scenario: Default and unreadable value
- **WHEN** no album sort was stored, or the stored value is an unknown name or not a string
- **THEN** the album sort reads as Name A–Z

#### Scenario: Logout resets the sort
- **WHEN** Last modified (newest first) was selected and the user logs out
- **THEN** the album sort is Name A–Z afterwards

#### Scenario: Not restored on a new device
- **WHEN** the app data is restored from a backup or device transfer
- **THEN** the album sort is Name A–Z because the credentials DataStore is excluded

#### Scenario: No credential re-emission
- **WHEN** the album sort is written while the WebDAV configuration is observed
- **THEN** the configuration flow does not emit again and nothing is decrypted
