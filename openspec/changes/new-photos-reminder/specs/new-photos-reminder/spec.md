# Spec Delta

## Purpose

Reminds users of the deliberately manual app, through an opt-in periodic local notification, that photos and videos on the device have not been backed up yet, and takes them straight to those items in the photo grid.

## ADDED Requirements

### Requirement: Reminder setting in Settings
The logged-in Settings content SHALL offer a "Remind me about new photos" switch with a hint, exposed to accessibility services as one switch-role node. While the switch is on, an interval choice (Weekly, Every 2 weeks, Monthly) SHALL be shown as a single-choice group whose options announce their selected state. All texts SHALL come from string resources.

#### Scenario: Toggle is present
- **WHEN** a logged-in user opens Settings
- **THEN** the "Remind me about new photos" switch is shown and is off

#### Scenario: Interval only while on
- **WHEN** the user turns the switch on
- **THEN** the interval choice is shown with Weekly selected

#### Scenario: Toggle is one accessible control
- **WHEN** an accessibility service inspects the reminder row
- **THEN** it finds a single switch-role node named by the label whose activation flips the setting

### Requirement: Reminder setting default and lifecycle
The reminder SHALL be off by default; the interval SHALL default to Weekly and an unreadable stored interval SHALL read as Weekly. The setting SHALL persist across restarts. It SHALL be stored next to the other non-credential preferences without re-emitting or re-decrypting the credential flows, SHALL be reset to off by logout and SHALL NOT be restored on a new device.

#### Scenario: Persists across restart
- **WHEN** the user turns the reminder on with Monthly and the app process is restarted
- **THEN** the reminder is still on with Monthly

#### Scenario: Logout resets
- **WHEN** the reminder is on and the user logs out
- **THEN** the reminder is off afterwards

#### Scenario: Not restored on a new device
- **WHEN** the app data is restored from a cloud backup or device transfer
- **THEN** the reminder is off

#### Scenario: Unrelated to credentials
- **WHEN** the reminder setting is written
- **THEN** the credential state and WebDAV configuration observers do not re-emit

### Requirement: Notification permission is requested when enabling
Turning the reminder on SHALL request the notification permission if it is not granted. A denial SHALL NOT turn the reminder off. While the reminder is on and the app cannot post notifications (permission denied, app notifications off or the reminder channel blocked), Settings SHALL show a hint with an action that opens the app's notification settings; the state SHALL be re-checked when Settings resumes.

#### Scenario: Permission asked on enable
- **WHEN** the notification permission is not granted and the user turns the reminder on
- **THEN** the notification permission is requested from the system and the reminder is on

#### Scenario: Denied permission shows hint
- **WHEN** the user denies the notification permission
- **THEN** the reminder stays on and the notifications-off hint with its settings action is shown

#### Scenario: Hint disappears after enabling notifications
- **WHEN** the user enables notifications in the system settings and returns to Settings
- **THEN** the hint is no longer shown

#### Scenario: No request when granted
- **WHEN** the notification permission is granted and the user turns the reminder on
- **THEN** no permission request is shown

### Requirement: Periodic reminder check is scheduled with the setting
Exactly one periodic background check SHALL exist while the reminder is on, repeating at the chosen interval, with its first run one interval after it was turned on. Changing the interval SHALL replace the period of the existing check. Turning the reminder off or logging out SHALL cancel the check and remove a shown reminder notification. The check SHALL survive reboots and app updates.

#### Scenario: Enable schedules
- **WHEN** the user turns the reminder on with Every 2 weeks
- **THEN** one periodic check with a 14-day period is scheduled

#### Scenario: Interval change updates
- **WHEN** the reminder is on with Weekly and the user selects Monthly
- **THEN** there is still exactly one periodic check and its period is 30 days

#### Scenario: Disable cancels
- **WHEN** the user turns the reminder off
- **THEN** no periodic check is scheduled and no reminder notification is shown

#### Scenario: Logout cancels
- **WHEN** the reminder is on and the user logs out
- **THEN** no periodic check is scheduled

### Requirement: Schedule is reconciled with the stored setting
On app start the scheduled check SHALL be brought in line with the stored setting without resetting the timing of an existing check: missing while on → scheduled; a different period while on → the stored interval's period; present while off → cancelled. A check that runs while the stored setting is off SHALL cancel itself without posting anything. An unreadable setting SHALL change nothing. Quick successive changes of the setting SHALL leave the check consistent with the last stored value. Applying a change SHALL NOT depend on the Settings screen staying open, and the app-start reconcile and the logout reset SHALL be ordered with Settings changes (one lock).

#### Scenario: Missing check is restored
- **WHEN** the reminder is stored as on, no check is scheduled and the app starts
- **THEN** the periodic check is scheduled

#### Scenario: Drifted period is repaired
- **WHEN** the reminder is stored as on with Monthly, the scheduled check has a 7-day period and the app starts
- **THEN** there is still exactly one periodic check, with the same identity and a 30-day period

#### Scenario: Quick toggling
- **WHEN** the user turns the reminder on, off and on again in quick succession
- **THEN** the reminder is on and exactly one periodic check is scheduled

#### Scenario: Leaving Settings does not lose a change
- **WHEN** the user turns the reminder on and leaves Settings before the check is scheduled
- **THEN** the check is still scheduled

#### Scenario: Reconcile races a change
- **WHEN** the app-start reconcile runs while a Settings change is being applied
- **THEN** the reconcile applies the value that change stored, not the previous one

#### Scenario: Logout during a change
- **WHEN** the user logs out while a change is being applied
- **THEN** the stored setting is off and the last scheduler action is a cancel

#### Scenario: Stale check cancels itself
- **WHEN** the check runs while the stored reminder is off
- **THEN** it posts nothing and the periodic check is cancelled

### Requirement: Reminder check is local only
The check SHALL NOT access the network, SHALL NOT start, enqueue or cancel uploads or downloads, SHALL NOT read or decrypt credentials and SHALL NOT modify media. It SHALL only read the media index, the uploaded-photos set, the upload history and the reminder setting, and SHALL only write the time of the last reminder. Scheduling and cancelling the check SHALL NOT affect any upload or download work.

#### Scenario: No upload and no network
- **WHEN** the check runs and finds new photos
- **THEN** no upload work is enqueued and no network request is made

#### Scenario: Turning the reminder off during an upload
- **WHEN** an upload is running and the user turns the reminder off
- **THEN** the upload continues and is not reported as cancelled

### Requirement: New items are counted since the last upload
An item SHALL be counted as new when it was added to the device after the baseline and is not in the uploaded-photos set. The baseline SHALL be the later of the time the reminder was turned on and the time of the newest upload run (including a cancelled one) that uploaded at least one file. Pending and trashed items SHALL NOT be counted. Images and videos with the same raw id SHALL be told apart by their media id.

#### Scenario: Pending and trashed items
- **WHEN** a pending item and a trashed item were added after the baseline
- **THEN** neither is counted

#### Scenario: Marked items are not counted
- **WHEN** 5 photos were added after the baseline and 2 of them are marked uploaded
- **THEN** the count is 3

#### Scenario: Items before the baseline are ignored
- **WHEN** a photo was added before the last successful upload and is not marked uploaded
- **THEN** it is not counted

#### Scenario: Video with the id of a marked image
- **WHEN** an image with raw id 42 is marked uploaded and a new video with raw id 42 was added after the baseline
- **THEN** the video is counted

#### Scenario: Failed-only run does not move the baseline
- **WHEN** the newest upload run uploaded no file
- **THEN** the baseline is the previous run that uploaded a file, or the time the reminder was turned on

### Requirement: Items created by the app are not counted
Media the app itself added to the device (photos saved from an album) SHALL NOT be counted as new, both while the app owns them and, after ownership was lost (reinstall), when they are in the app's save folder `Pictures/KroehnSync/`. Items without a recorded owner or folder SHALL still be counted. Saved photos SHALL stay visible under "show only new photos" (unchanged); the reminder's count MAY therefore be lower than what the filtered grid shows.

#### Scenario: Own download excluded
- **WHEN** the app saved a downloaded album photo to the device after the baseline
- **THEN** it is not counted

#### Scenario: Saved photo after a reinstall
- **WHEN** a photo in `Pictures/KroehnSync/` has no owner (the app was reinstalled) and was added after the baseline
- **THEN** it is not counted

#### Scenario: Item without owner or folder
- **WHEN** a photo of another app without a recorded owner and without a recorded folder was added after the baseline
- **THEN** it is counted

### Requirement: Count respects media access
The check SHALL count only the media kinds the app can currently read: images with image access, videos with video access, and the user-selected images and videos with selected-photos access. Without any media access the check SHALL post nothing. A media query that is refused SHALL count as zero for that kind.

#### Scenario: No media permission
- **WHEN** the check runs and no media permission is granted
- **THEN** no notification is posted

#### Scenario: Images only
- **WHEN** image access is granted, video access is not, and new photos and videos exist
- **THEN** only the new photos are counted

#### Scenario: Partial access
- **WHEN** only selected-photos access is granted and none of the new items was selected
- **THEN** the count is 0 and no notification is posted

### Requirement: Counting is bounded
The media query SHALL read only what is needed to decide newness (no thumbnails, no file contents), SHALL scan at most a fixed number of rows per media kind and SHALL be abandoned after a time limit. When the row limit is reached, the count SHALL be shown as a lower bound. A check that times out SHALL post nothing and SHALL NOT update the last-reminder time.

#### Scenario: Row cap reached
- **WHEN** more new items exist than the row limit
- **THEN** the notification states "At least" the counted number

#### Scenario: Query times out
- **WHEN** the media query does not finish within the time limit
- **THEN** no notification is posted and the last-reminder time is unchanged

### Requirement: Reminder notification
When the count is greater than 0 and at least one counted item was added after the previous reminder, the check SHALL post one notification on its own "Reminders" channel with its own id (distinct from every upload and download notification id), replacing a previous reminder. Its text SHALL use plurals: "N new photos and videos since your last upload", or a variant without "since your last upload" when no upload happened since the reminder was turned on. It SHALL then store the reminder time, taken before the media query started, and only while the reminder is still on and unchanged (a check finishing after logout SHALL write nothing). The text SHALL read "At least N" when the count is a lower bound. The notification SHALL be private on the lock screen: its public version SHALL show the title only, never the count.

#### Scenario: New items notify
- **WHEN** the check counts 37 new items and nothing was reminded before
- **THEN** a notification "37 new photos and videos since your last upload" is shown on the Reminders channel

#### Scenario: Lock screen
- **WHEN** the device is locked and the reminder is shown
- **THEN** only the title is visible, not the count

#### Scenario: Singular
- **WHEN** the check counts 1 new item
- **THEN** the singular plural form is used

#### Scenario: Nothing new
- **WHEN** the count is 0
- **THEN** no notification is posted

#### Scenario: Nothing added since the last reminder
- **WHEN** all counted items were already counted by the previous reminder
- **THEN** no notification is posted

#### Scenario: Separate from upload notifications
- **WHEN** an upload or download result notification and a reminder are both shown
- **THEN** neither replaces the other

### Requirement: No reminder when notifications are not allowed
The check SHALL NOT post and SHALL NOT update the last-reminder time when the notification permission is denied, app notifications are off or the reminder channel is blocked.

#### Scenario: Permission denied
- **WHEN** new items exist and the notification permission is denied
- **THEN** no notification is posted and the last-reminder time is unchanged

#### Scenario: Channel blocked
- **WHEN** the user turned off the Reminders channel
- **THEN** no notification is posted

### Requirement: Tapping the reminder opens the filtered photo grid
Tapping the reminder SHALL open the app on the photo grid with the "show only new photos" filter on, whether the app was closed, in the background or showing another screen, and SHALL dismiss the notification. The request SHALL be applied once: recreating the activity SHALL NOT apply it again, and an intent re-delivered from the recents screen SHALL NOT be a request. The tap SHALL use an explicit, immutable pending intent to the app's main activity.

#### Scenario: Cold start
- **WHEN** the app is not running and the user taps the reminder
- **THEN** the photo grid is shown with the filter on

#### Scenario: App on another screen
- **WHEN** the app shows Settings and the user taps the reminder
- **THEN** the photo grid is shown with the filter on and Back does not return to Settings

#### Scenario: Applied once
- **WHEN** the grid was opened from the reminder, the user turns the filter off and rotates the device
- **THEN** the filter stays off

#### Scenario: Album photo deletion running
- **WHEN** an album shows a running photo deletion and the user taps the reminder
- **THEN** the photo grid is shown with the filter on, the deletion is not interrupted and its result is shown when the user returns to the album through the Albums tab

#### Scenario: Share screen in the app's task
- **WHEN** the share screen of the app is the screen the tap reaches
- **THEN** the share screen stays and the request is dropped

#### Scenario: First-run onboarding pending
- **WHEN** the start destination is the onboarding
- **THEN** the onboarding is shown and the request is dropped

#### Scenario: Unknown launch data ignored
- **WHEN** the app is opened with the reminder action and additional extras or data
- **THEN** only the filtered grid is opened and the extras and data are ignored
