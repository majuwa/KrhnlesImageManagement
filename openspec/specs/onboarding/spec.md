# onboarding Specification

## Purpose
Introduces first-time users to the app in two skippable pages and leads them to the connection settings, while existing users go straight to their photos; the completed flag is persisted, survives restarts and is never restored from backup.

## Requirements

### Requirement: Onboarding is shown only to first-time users
The app SHALL show onboarding as the start destination if and only if the persisted `onboarding_completed` flag is unset AND the user is not an existing user. An existing user has a valid WebDAV configuration, OR any stored server URL, OR a credential state other than `Missing` (including `Corrupted`). Otherwise the app SHALL start on the photo grid.

#### Scenario: Fresh install
- **WHEN** the app starts with no stored configuration, credentials or flag
- **THEN** the Overview page is shown and the bottom bar is hidden

#### Scenario: Existing configured user upgrades
- **WHEN** the app starts with a valid configuration and no flag
- **THEN** the photo grid is shown and the flag is persisted silently

#### Scenario: Upgrade user with corrupted credentials
- **WHEN** the app starts with a stored server URL or `credentialState = Corrupted` and no flag
- **THEN** the photo grid is shown, onboarding is not shown and the flag is persisted silently

#### Scenario: Completed or skipped before
- **WHEN** the flag is set and the configuration was later removed
- **THEN** the photo grid is shown

#### Scenario: Unreadable preference store
- **WHEN** reading the flag throws an `IOException` or the file is corrupt
- **THEN** the flag is treated as unset, only a first-time user sees onboarding and the app does not crash

#### Scenario: Share-target launch
- **WHEN** the app is launched through a share intent on a first launch
- **THEN** the share receiver is shown as before, onboarding is not started and the flag is not changed

### Requirement: Start destination is resolved once without showing a wrong screen
The start destination SHALL be resolved once before the navigation graph is composed. Until it is known a neutral theme-colored background SHALL be shown. If resolution times out or fails, the photo grid SHALL be the destination.

#### Scenario: Resolution hangs
- **WHEN** reading the stores takes longer than the timeout
- **THEN** the photo grid is shown

#### Scenario: Restored destination
- **WHEN** the activity is recreated and a start destination was already resolved
- **THEN** the same destination is used immediately without waiting for a new resolution

### Requirement: Two skippable pages
Onboarding SHALL consist of two pages: Overview (what the app does) and Connect (short instructions plus a "Connect now" button). Both pages SHALL show a Skip action and the position ("Step N of 2"). The Overview page SHALL offer Next to reach the Connect page. Onboarding SHALL contain no permission request, login form or connection test.

#### Scenario: Next
- **WHEN** the user taps Next on the Overview page
- **THEN** the Connect page is shown

#### Scenario: Back on the Connect page
- **WHEN** the user presses Back on the Connect page
- **THEN** the Overview page is shown and nothing is persisted

#### Scenario: Back on the Overview page
- **WHEN** the user presses Back on the Overview page
- **THEN** the app is exited and the flag is not persisted, so onboarding is shown again on the next launch

### Requirement: Skip and Connect now persist then navigate
Skip and "Connect now" SHALL persist `onboarding_completed = true` and only then navigate, removing onboarding from the back stack. Repeated taps while finishing SHALL be ignored and the guard SHALL be released when finishing ends for any reason.

#### Scenario: Skip
- **WHEN** the user taps Skip
- **THEN** the flag is persisted, the photo grid is shown as the root of the back stack and onboarding is not shown on the next launch

#### Scenario: Connect now
- **WHEN** the user taps "Connect now"
- **THEN** the flag is persisted and the back stack is `photos` (root) with `settings` on top, so Back from Settings shows the photo grid and never returns to onboarding

#### Scenario: Double tap
- **WHEN** the user taps Skip or "Connect now" repeatedly while persisting
- **THEN** the flag is persisted and the navigation happens exactly once

#### Scenario: Persist failure
- **WHEN** persisting the flag fails with an exception other than cancellation
- **THEN** the user still reaches the chosen destination and the guard is released

### Requirement: Page survives configuration changes and process death
The current page SHALL survive rotation and process death.

#### Scenario: Process death on the Connect page
- **WHEN** the process is killed while the Connect page is shown and the user returns
- **THEN** the Connect page is shown again

### Requirement: Flag is excluded from backup
The DataStore file `datastore/app_preferences.preferences_pb` SHALL be excluded from cloud backup and device transfer in `backup_rules.xml` and in the `cloud-backup` and `device-transfer` sections of `data_extraction_rules.xml`, so a restored device without credentials shows onboarding again.

#### Scenario: Exclusion rules
- **WHEN** the backup XML resources are parsed
- **THEN** each of the three exclusion sections contains an exclude for `datastore/app_preferences.preferences_pb`

### Requirement: Accessibility and localisation
All onboarding text SHALL come from string resources. Each page title SHALL carry heading semantics and the page container SHALL set a pane title so that a page change is announced. Decorative icons SHALL have no content description.

#### Scenario: Heading and pane title
- **WHEN** either page is shown
- **THEN** its title node has heading semantics and the page container exposes the page title as pane title
