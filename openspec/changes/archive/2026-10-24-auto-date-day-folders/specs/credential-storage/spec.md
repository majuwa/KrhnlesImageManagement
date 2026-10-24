## ADDED Requirements

### Requirement: Auto date folders preference lifecycle
The auto date folders preference SHALL be stored as a plain preference in the credentials DataStore without a `credentials_version` bump. It SHALL be reset to off by `CredentialStore.clear()` (logout) and SHALL NOT be restored on a new device because the file is excluded from backup and device transfer (same as Wi-Fi only and blur sensitivity). Writing it SHALL NOT re-emit or re-decrypt the credential flows.

#### Scenario: Logout resets the setting
- **WHEN** auto date folders was on and the user logs out
- **THEN** the setting is off afterwards

#### Scenario: Not restored on a new device
- **WHEN** the app data is restored from a backup or device transfer
- **THEN** the setting is off because the credentials DataStore is excluded
