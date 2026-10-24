## ADDED Requirements

### Requirement: Wi-Fi only toggle in Settings
The logged-in Settings content SHALL offer an "Upload on Wi-Fi only" switch with a hint explaining that background uploads are restricted to unmetered connections. The label, hint and switch SHALL be exposed to accessibility services as one toggle that announces its on/off state.

#### Scenario: Toggle is present
- **WHEN** a logged-in user opens Settings
- **THEN** the "Upload on Wi-Fi only" switch is shown

#### Scenario: Toggle is one accessible control
- **WHEN** an accessibility service inspects the Wi-Fi only row
- **THEN** it finds a single switch-role node named by the label, whose activation flips the setting

### Requirement: Wi-Fi only default and persistence
The setting SHALL be off by default and the chosen value SHALL persist across app restarts and screen recreation. Logging out SHALL reset it to off. It SHALL NOT be restored on a new device.

#### Scenario: Default
- **WHEN** the setting was never changed
- **THEN** uploads are not restricted to unmetered networks

#### Scenario: Persists across restart
- **WHEN** the user turns the setting on and the app process is restarted
- **THEN** the setting is still on

#### Scenario: Logout resets
- **WHEN** the setting is on and the user logs out
- **THEN** the setting is off afterwards

### Requirement: Uploads wait for an unmetered network
When the setting is on, a newly enqueued upload SHALL NOT start on a metered network and SHALL start once an unmetered network is available ("unmetered" is not the Wi-Fi transport). When the setting is off, no network-type constraint SHALL be set. If the unmetered constraint stops a running upload, WorkManager SHALL re-run it and finished entries SHALL be skipped.

#### Scenario: Enabled and only mobile data
- **WHEN** the setting is on and the device only has a metered connection
- **THEN** the upload stays pending and no data is sent

#### Scenario: Enabled and Wi-Fi becomes available
- **WHEN** the setting is on, an upload is pending and an unmetered network becomes available
- **THEN** the upload runs

#### Scenario: Disabled
- **WHEN** the setting is off
- **THEN** no network-type constraint is set on new uploads

#### Scenario: Unmetered cellular plan
- **WHEN** the setting is on and the only connection is a cellular network that is not flagged as metered
- **THEN** the upload runs

#### Scenario: Unmetered constraint stops a running upload
- **WHEN** the setting is on and the connection becomes metered while an upload runs
- **THEN** WorkManager re-runs the upload once an unmetered network is available and entries already uploaded are not sent again

#### Scenario: Switched off after enqueue
- **WHEN** an upload was enqueued with the setting on and the user then turns the setting off
- **THEN** the already queued work stays pending until an unmetered network is available or the user retries it

#### Scenario: Metered Wi-Fi hotspot
- **WHEN** the setting is on and the only connection is a Wi-Fi network flagged as metered
- **THEN** the upload stays pending

The run-time check for a setting switched on after enqueueing, retries and the unreadable-setting fallback are specified in `openspec/specs/failed-photo-retry/spec.md`.

### Requirement: Wi-Fi-only wake-ups do not use the interruption budget
Wake-ups caused by the Wi-Fi-only check or by constraint stops SHALL NOT count towards the interruption limit of an upload; only foreground-service refusals count (see the modified `video-support` requirement). The WorkManager back-off delay may still grow with such wake-ups (up to 5 hours); this is accepted.

#### Scenario: Metered wake-ups do not exhaust the retry budget
- **WHEN** the setting is on, the upload was woken up many times on a metered network and the foreground service is then refused once
- **THEN** the upload is retried, not given up
