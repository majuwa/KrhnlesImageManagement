## Purpose

Lets users watch the videos of a remote album inside the fullscreen viewer, streamed from their own server only, without downloading the whole file first and without ever playing on its own.

## ADDED Requirements

### Requirement: A video page shows a poster and a play button
In the fullscreen viewer, a video SHALL be shown as its server preview image or, when there is none or it fails to load, a neutral video placeholder, with a play button in the centre. A video page SHALL NOT be zoomable, and swiping between pages SHALL work on it as on photo pages.

#### Scenario: Video with a server preview
- **WHEN** the user opens a video whose server offers a preview image
- **THEN** the preview is shown with a play button and nothing is played

#### Scenario: Video without a preview
- **WHEN** the user opens a video on a server without previews, or the preview request fails
- **THEN** a video placeholder with a play button is shown, and the video itself is not requested

#### Scenario: Swiping past a video
- **WHEN** the user swipes horizontally on a video page
- **THEN** the viewer moves to the neighbouring page

### Requirement: Videos play only on request and are streamed
A video SHALL start loading only when the user taps play, and SHALL be streamed from the server so that playback starts before the whole file is received and seeking requests only the needed part. Nothing SHALL be preloaded for pages that are not playing, and the stream SHALL NOT be stored on the device.

#### Scenario: Opening the viewer on a video
- **WHEN** the viewer opens on a video page or the user swipes to one
- **THEN** no request for the video file is sent

#### Scenario: Seeking
- **WHEN** the user seeks forward in a playing video
- **THEN** the app requests the file from the new position (byte range) instead of downloading everything before it

#### Scenario: No cached copy
- **WHEN** a video has been played and the viewer is closed
- **THEN** no copy of the video remains in the app's storage

### Requirement: Playback controls are accessible
While a video plays, the viewer SHALL offer play / pause, a seek bar with the current position and the duration, and the existing back control. Each control SHALL have a localized accessibility label; the seek bar SHALL expose its position and duration as its state and SHALL be adjustable by accessibility actions. The video page SHALL be described as "Video N of M", never by its file name.

#### Scenario: Play button announced
- **WHEN** an accessibility service inspects a video page before playback
- **THEN** the page is described as "Video 3 of 12" and the play button as "Play video"

#### Scenario: Pausing
- **WHEN** the user activates the pause control
- **THEN** playback pauses and the control is announced as "Play video"

#### Scenario: Seek bar state
- **WHEN** an accessibility service inspects the seek bar of a 2:00 video at 0:30
- **THEN** its state contains the position and the duration, and increase / decrease actions move the position

### Requirement: Playback stops when it is no longer visible
Playback SHALL stop and its network stream SHALL be closed when the user swipes to another page or leaves the viewer, and SHALL pause when the app goes to the background. Rotating the device SHALL keep the playing video and its position.

#### Scenario: Swiping to another page
- **WHEN** a video is playing and the user swipes to the next page
- **THEN** the video stops, its request is cancelled and the next page shows its own content

#### Scenario: App goes to the background
- **WHEN** a video is playing and the user switches to another app
- **THEN** playback pauses and resumes only when the user taps play again

#### Scenario: Rotation
- **WHEN** the device is rotated while a video plays
- **THEN** the same video continues from the same position without starting a new download from the beginning

#### Scenario: Leaving the viewer
- **WHEN** the user leaves the viewer while a video plays
- **THEN** the player is released and no further request for the video is sent

### Requirement: Playback stops when the configuration changes
When the user logs out or the server or account changes while a video is playing or loading, playback SHALL stop at once and no further request for the video SHALL be sent with the old server or credentials.

#### Scenario: Logout during playback
- **WHEN** the user logs out while a video streams
- **THEN** the stream is cancelled, the video is no longer shown and no further request to the old server is made

### Requirement: Playback failures are shown as plain-language messages
If a video cannot be played, the video page SHALL show a localized message chosen by the kind of failure (not reachable / timeout, access denied, not found, server redirected, format not supported on this device, generic) with Retry, and SHALL NOT show raw exception or server text. Retry SHALL start playback again from the beginning.

#### Scenario: Unsupported format
- **WHEN** the device cannot decode the video's format
- **THEN** the page says that this video format cannot be played on this device, with Retry

#### Scenario: File removed on the server
- **WHEN** the server answers the video request with 404
- **THEN** the page shows the "not found" message with Retry, not the server's response text

#### Scenario: Retry after a network failure
- **WHEN** playback failed because the server was unreachable and the user taps Retry
- **THEN** a new playback attempt starts
