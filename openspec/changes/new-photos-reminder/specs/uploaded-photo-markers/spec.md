# Spec Delta

## MODIFIED Requirements

### Requirement: Show only new photos filter
When at least one item is marked uploaded and no selection is active, the top bar SHALL offer a filter toggle. When on, items in the uploaded-photos set SHALL be hidden and date groups that become empty SHALL be removed; when off, all items SHALL be shown. The filter SHALL have no effect while nothing is marked. Opening the app from the new-photos reminder SHALL turn the filter on (never off), also when it was already on.

#### Scenario: Filter on
- **WHEN** the user turns the filter on
- **THEN** uploaded items and empty date groups are hidden

#### Scenario: Filter off
- **WHEN** the user turns the filter off
- **THEN** all items are shown again

#### Scenario: Turned on by the reminder
- **WHEN** the filter is off and the app is opened from the new-photos reminder
- **THEN** the filter is on and uploaded items are hidden

#### Scenario: Reminder keeps an active filter on
- **WHEN** the filter is already on and the app is opened from the new-photos reminder
- **THEN** the filter is still on
