# blur-sensitivity Specification

## Purpose
Lets the user choose how aggressively the blur review flags photos, by selecting one of three sensitivity levels that map to the blur score threshold used by the next scan.

## Requirements

### Requirement: Blur sensitivity is selectable in Settings
The logged-in Settings content SHALL offer a "Blur sensitivity" slider with exactly three positions: Low, Medium, High, with a hint that High flags more photos. Medium SHALL be the default. A photo is blurry when its score is strictly below the level's threshold.

#### Scenario: Default
- **WHEN** nothing was ever stored
- **THEN** Medium is selected and the threshold is 3000

#### Scenario: Change level
- **WHEN** the user moves the slider to High
- **THEN** High is persisted once and its label is announced

### Requirement: Levels map to thresholds
Low SHALL use threshold 1500, Medium 3000 (the previous `BlurDetector.DEFAULT_THRESHOLD`) and High 6000, so a higher sensitivity flags more photos. The values are heuristics, not validated against all image sources.

#### Scenario: Mapping
- **WHEN** a photo scores 2000
- **THEN** it is blurry at Medium and High but not at Low

#### Scenario: Boundary
- **WHEN** a photo scores exactly 3000
- **THEN** it is not blurry at Medium

### Requirement: The sensitivity control is accessible
The slider SHALL be the single accessibility node of the control: its content description is the setting title and its state description the current level label (the visible title and level texts are not announced separately); it SHALL be operable by TalkBack / keyboard step actions.

#### Scenario: TalkBack
- **WHEN** the slider is focused with Medium selected
- **THEN** it is announced as "Blur sensitivity, Medium" without a duplicate level announcement

### Requirement: The sensitivity persists and tolerates invalid values
The level SHALL be stored by name next to the other preferences in the existing preferences store and SHALL survive app restarts. A missing, unknown, blank or wrong-typed stored value SHALL yield Medium without crashing. The setting is not restored on a new device and is reset to Medium on logout (like Wi-Fi only and auto date folders). Writing the level SHALL NOT re-emit or re-decrypt the credential flows.

#### Scenario: Restart
- **WHEN** Low was saved and the app is restarted
- **THEN** Low is selected

#### Scenario: Logout
- **WHEN** High was saved and the user logs out
- **THEN** Medium is selected afterwards

#### Scenario: Slider drag does not disturb other edits
- **WHEN** the user has an unsaved base-folder edit and moves the slider
- **THEN** the typed text is kept

#### Scenario: Save fails
- **WHEN** persisting the level fails
- **THEN** the previously persisted level is shown again and an error string is displayed

#### Scenario: Unknown stored value
- **WHEN** the stored value is not a known level name
- **THEN** Medium is used

#### Scenario: Wrong-typed stored value
- **WHEN** the stored value under the key is not a string
- **THEN** Medium is used and nothing is thrown

### Requirement: The blur scan uses the current sensitivity
The blur review SHALL pass a sensitivity source to each scan call (start and Retry). The scan SHALL read the level once after it started (bounded by a timeout, falling back to Medium) and use the mapped threshold for the entire scan. A change made afterwards SHALL NOT alter a running scan or already shown results; it SHALL apply to the next scan (including Retry). Scans SHALL still start only from Idle.

#### Scenario: Next scan uses new level
- **WHEN** the level is changed to Low and a new blur scan runs
- **THEN** only photos with a score below 1500 are reported

#### Scenario: Not retroactive
- **WHEN** results are shown or a scan runs and the level is changed in Settings
- **THEN** the running scan and shown results stay unchanged

#### Scenario: Source unreadable
- **WHEN** reading the level times out or fails
- **THEN** the scan runs with Medium
