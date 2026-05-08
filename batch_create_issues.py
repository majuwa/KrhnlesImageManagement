import json
import subprocess
import sys
import time

TOKEN = subprocess.run(['bash', '-c', 'echo $GITHUB_COPILOT_API_TOKEN'], capture_output=True, text=True).stdout.strip()

def call_mcp(params, req_id):
    payload = json.dumps({
        "jsonrpc": "2.0",
        "method": "tools/call",
        "params": params,
        "id": req_id
    })
    result = subprocess.run(
        ['curl', '-s', '-X', 'POST',
         '-H', f'Authorization: Bearer {TOKEN}',
         '-H', 'Content-Type: application/json',
         '-d', payload,
         'https://api.individual.githubcopilot.com/mcp'],
        capture_output=True, text=True
    )
    for line in result.stdout.split('\n'):
        if line.startswith('data: '):
            try:
                return json.loads(line[6:])
            except:
                pass
    return None

def create_issue(title, body, labels, req_id):
    r = call_mcp({
        "name": "issue_write",
        "arguments": {
            "method": "create",
            "owner": "majuwa",
            "repo": "KrhnlesImageManagement",
            "title": title,
            "body": body,
            "labels": labels
        }
    }, req_id)
    if r and 'result' in r:
        text = r['result']['content'][0]['text']
        try:
            data = json.loads(text)
            return data.get('url', text)
        except:
            return text
    elif r and 'error' in r:
        return f"ERROR: {r['error']}"
    return f"Unknown response: {r}"

issues = [
    # Feature 2
    (
        "[Feature] Duplicate-upload prevention: mark already-uploaded photos in local grid",
        """## User Story
As a user, I want the app to remember which local photos I have already uploaded, so I do not accidentally back them up again and waste storage.

## Motivation
The local photo grid shows all photos with no indication of upload status. Users who back up regularly have no way to distinguish already-uploaded photos from new ones.

## Proposed Solution
- Maintain a local set of already-uploaded photo IDs (or content hashes) in DataStore/Room
- After a successful upload, record the IDs of the uploaded photos
- In the local photo grid, display a small "cloud" badge on already-uploaded thumbnails
- Optionally provide a filter to hide already-uploaded photos

## Acceptance Criteria
- [ ] After a successful upload, photos are marked in the local grid with a cloud badge
- [ ] Marking survives app restart
- [ ] Uploaded badge is shown for the correct photos only
- [ ] A "show only new photos" toggle hides already-uploaded photos
- [ ] Clearing the server config resets the uploaded-photo tracking""",
        ["enhancement"]
    ),
    # Feature 3
    (
        "[Feature] Wi-Fi only upload: option to restrict background uploads to Wi-Fi",
        """## User Story
As a privacy/data-conscious user, I want to ensure my photos are only uploaded over Wi-Fi and never consume my mobile data.

## Motivation
Photo uploads can be large. Users on metered connections need control over when uploads are allowed to run. WorkManager natively supports network-type constraints (`NetworkType.UNMETERED`).

## Proposed Solution
- Add a "Upload on Wi-Fi only" toggle in Settings
- Persist the setting in DataStore
- When enqueuing the `UploadWorker`, set `Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)` if the option is enabled
- If the device goes offline mid-upload, WorkManager will pause and resume automatically

## Acceptance Criteria
- [ ] "Wi-Fi only" toggle is present in Settings
- [ ] Uploads scheduled with the constraint wait until Wi-Fi is available
- [ ] The setting persists across restarts
- [ ] The toggle defaults to `false` (no restriction) to match current behaviour""",
        ["enhancement"]
    ),
    # Feature 4
    (
        "[Feature] Video support: include videos in local grid and upload",
        """## User Story
As a user, I want to be able to select and upload videos alongside photos, since my camera roll contains both.

## Motivation
The current app only handles images (`READ_MEDIA_IMAGES`). Users who want to back up their entire camera roll are blocked from uploading videos.

## Proposed Solution
- Add `READ_MEDIA_VIDEO` permission (Android 13+)
- Extend `MediaRepository` to also query `MediaStore.Video.Media`
- Show a video/photo filter toggle in the local grid top bar
- Video thumbnails can be loaded with Coil using a `VideoFrameDecoder`
- UploadWorker already handles arbitrary MIME types — no changes needed there

## Technical Notes
- `READ_MEDIA_VISUAL_USER_SELECTED` already covers both images and video on Android 14+
- Thumbnail loading for videos requires adding Coil's `coil-video` extension
- File size could be large; consider adding a per-file size warning

## Acceptance Criteria
- [ ] Videos appear in the local grid alongside photos
- [ ] Videos show a play-button overlay on their thumbnail
- [ ] Videos can be selected and uploaded like photos
- [ ] Permissions are requested correctly on Android 13 and 14+""",
        ["enhancement"]
    ),
    # Feature 5
    (
        "[Feature] Android share target: receive photos from other apps via share sheet",
        """## User Story
As a user, I want to share individual photos or screenshots from the Gallery or other apps directly into KrönSync, so I can upload them to my server without opening the app manually.

## Motivation
Many users take screenshots or save images in various apps. The Android share sheet is the natural entry point for these use cases. Without share-target support, users must switch to the app and manually navigate to find the photo.

## Proposed Solution
- Declare `MainActivity` (or a new `ShareReceiverActivity`) as a share target for `image/*` in `AndroidManifest.xml`
- On receipt, pre-select the shared image(s) and open the occasion dialog directly
- After upload is dispatched, close the share activity

## Acceptance Criteria
- [ ] App appears in the share sheet for image/* types
- [ ] Sharing one or more images from the system gallery opens the occasion dialog
- [ ] Upload starts normally via WorkManager after the user enters an occasion name
- [ ] The shared-from app is returned to after the occasion dialog is confirmed""",
        ["enhancement"]
    ),
    # Feature 6
    (
        "[Feature] Rename album: allow users to rename remote albums (WebDAV MOVE)",
        """## User Story
As a user, I want to rename a remote album after uploading, because I may have used the wrong occasion name or want to reorganize.

## Motivation
Typos and second thoughts happen. Currently, users would have to log into their Nextcloud web UI to rename a folder. A rename action in the app improves self-sufficiency.

## Proposed Solution
- Add a "Rename" option in the album long-press context menu (via the existing selection mode or an overflow menu in the album detail screen)
- Implement `WebDavClient.renameDirectory(oldPath, newPath)` using `WebDAV MOVE` with the `Destination` header
- Show an inline text field dialog for the new name
- On success, update the album name in `AlbumsState`

## Acceptance Criteria
- [ ] Long-pressing an album or using the detail screen overflow exposes a "Rename" option
- [ ] The rename dialog pre-fills with the current album name
- [ ] WebDAV MOVE is used; the album is not re-created
- [ ] On success, the album list updates without a full reload
- [ ] Blank or unchanged names are rejected with a friendly message""",
        ["enhancement"]
    ),
    # Feature 7
    (
        "[Feature] Auto date folder mode: organize uploads into YYYY/MM sub-folders automatically",
        """## User Story
As a user who prefers chronological organisation, I want the app to automatically create a folder structure like `Photos/2026/05` for uploads, so I do not have to type an occasion name every time.

## Motivation
The occasion-name dialog is intentional for curated uploads, but for "archive everything quickly" workflows, requiring an occasion name every time is friction.

## Proposed Solution
- Add an "Auto date folders" toggle in Settings (default: off)
- When enabled, the occasion dialog is skipped; the folder is derived from the photos' capture date as `YYYY/MM-Month` (e.g., `2026/05-May`)
- If photos span multiple months, split into separate WorkManager tasks per month
- Show the resolved folder path as a preview before upload starts

## Acceptance Criteria
- [ ] Toggle in Settings enables/disables auto date mode
- [ ] When enabled, tapping the upload FAB skips the occasion dialog
- [ ] Folders are created as `<baseFolder>/YYYY/MM-Month/`
- [ ] Photos from different months within a selection are split into separate uploads
- [ ] When disabled, existing behavior (occasion dialog) is unchanged""",
        ["enhancement"]
    ),
    # Feature 8
    (
        "[Feature] Per-photo retry: allow retrying only failed photos after a partial upload",
        """## User Story
As a user, when an upload partially fails (e.g., 3 out of 20 photos fail due to a temporary network error), I want to retry only the failed photos, not the entire batch.

## Motivation
Currently, if some photos fail, the completion notification reports "X succeeded, Y failed" but there is no way to retry the failed ones. The user would have to find and re-select those photos manually.

## Proposed Solution
- Persist a list of failed URIs in the upload queue file (or a new failure store)
- The completion notification for a partial upload includes a "Retry Failed" action button
- Tapping it schedules a new `UploadWorker` with only the failed files, to the same folder
- The album detail screen could also show a "retry" badge if opened after a partial upload

## Acceptance Criteria
- [ ] Partial failure notification includes a "Retry Failed (Y)" action button
- [ ] Tapping it re-queues only the failed files
- [ ] The folder name is reused from the original upload
- [ ] Full success notification has no retry button""",
        ["enhancement"]
    ),
    # Feature 9
    (
        "[Feature] User-configurable blur threshold for blur detection scan",
        """## User Story
As a user, I want to adjust the sensitivity of the blur detection scan, because the default threshold may flag too many or too few photos depending on my camera and shooting style.

## Motivation
`BlurDetector.DEFAULT_THRESHOLD` is a hardcoded constant. Different users have different standards for what counts as "blurry". A low-end camera produces softer images that might be falsely flagged at the default threshold.

## Proposed Solution
- Add a "Blur sensitivity" slider in Settings (Low / Medium / High, mapped to threshold values)
- Persist the chosen threshold in DataStore
- `AlbumsViewModel.findBlurryPhotos()` reads the threshold from the persisted setting
- The blur review screen shows the current threshold for context

## Acceptance Criteria
- [ ] Blur sensitivity is configurable in Settings with three preset levels
- [ ] Setting persists across restarts
- [ ] The blur scan uses the current setting, not the hardcoded constant
- [ ] Changing the setting takes effect on the next scan (not retroactively)""",
        ["enhancement"]
    ),
    # Feature 10
    (
        "[Feature] Onboarding screen: guided setup for first-time users",
        """## User Story
As a new user opening the app for the first time, I want a guided walkthrough explaining what the app does and how to connect my server, so I can get started without consulting external documentation.

## Motivation
Currently the app opens directly on the empty photo grid with an unexplained warning about no server being configured. New users have no contextual help and must figure out settings on their own.

## Proposed Solution
- Show a one-time onboarding screen on first launch (stored in DataStore)
- Step 1: App overview ("Manually back up your photos to your Nextcloud or WebDAV server")
- Step 2: Server setup instructions with a "Connect now" CTA that navigates to Settings
- Skip button on all steps
- After completing setup in Settings, mark onboarding as done

## Acceptance Criteria
- [ ] Onboarding is shown only on first launch
- [ ] Users can skip at any point
- [ ] Completing onboarding is persisted (not shown again)
- [ ] "Connect now" navigates to Settings
- [ ] Does not appear if the user is already configured""",
        ["enhancement"]
    ),
    # Bug 11: SettingsViewModel
    (
        "[Bug] SettingsViewModel.testConnection() uses collect instead of first(), causing a coroutine leak",
        """## Bug Description
In `SettingsViewModel.testConnection()`, the code calls `credentialStore.webDavConfig.collect { ... return@collect }`. The `collect` lambda uses `return@collect` to exit after the first emission, but this does **not** cancel the upstream Flow — the coroutine launched by `viewModelScope.launch` remains blocked until the ViewModel is cleared.

The correct approach is to call `.first()` to take a single emission and then complete.

## Reproduction
1. Open the Settings screen
2. Tap "Test Connection"
3. Navigate away — the viewModelScope launch holding the `collect` survives until ViewModel death

## Expected Behaviour
`testConnection()` reads the current config once (`.first()`), tests the connection, and returns.

## Fix
Replace:
```kotlin
credentialStore.webDavConfig.collect { config ->
    ...
    return@collect
}
```
With:
```kotlin
val config = credentialStore.webDavConfig.first()
...
```

## Impact
- Memory/coroutine leak per invocation of "Test Connection" until the ViewModel is destroyed
- Could cause concurrent test-connection calls if the user taps quickly""",
        ["bug"]
    ),
    # Bug 12: hardcoded nav labels
    (
        "[Bug] KrhnlesApp: bottom navigation labels \"Photos\" and \"Albums\" are hardcoded, violating i18n policy",
        """## Bug Description
In `KrhnlesApp.kt`, the bottom navigation bar labels are hardcoded English strings:
```kotlin
label = { Text("Photos") }
label = { Text("Albums") }
```
This violates the project convention that all user-facing strings must be in `strings.xml`.

## Fix
1. Add `<string name="nav_photos">Photos</string>` and `<string name="nav_albums">Albums</string>` to `strings.xml`
2. Replace the hardcoded literals with `stringResource(R.string.nav_photos)` / `stringResource(R.string.nav_albums)`

## Impact
- App cannot be localised for non-English speakers
- Violates `CLAUDE.md` string resource convention""",
        ["bug"]
    ),
    # Bug 13: SuppressLint
    (
        "[Bug] UnrememberedGetBackStackEntry suppressions in KrhnlesApp hide potential crashes",
        """## Bug Description
In `KrhnlesApp.kt`, two composable routes suppress the `UnrememberedGetBackStackEntry` lint warning:

```kotlin
@SuppressLint("UnrememberedGetBackStackEntry")
val albumsEntry = remember(ROUTE_ALBUMS) { navController.getBackStackEntry(ROUTE_ALBUMS) }
```

The lint warning exists because calling `getBackStackEntry()` inside a `remember` block with a constant key means the `NavBackStackEntry` is only retrieved once on first composition and is not properly re-keyed if the back stack changes. In the `remember(ROUTE_ALBUMS)` form the key is the constant string — which never changes — so this is effectively `remember { }` and could return a stale entry.

The correct pattern (as used in the `ROUTE_ALBUM_DETAIL` composable) is `remember(backStack)` where `backStack` is the current composable's `NavBackStackEntry`, so the remembered value is invalidated whenever the back stack changes.

## Fix
Replace:
```kotlin
@SuppressLint("UnrememberedGetBackStackEntry")
val albumsEntry = remember(ROUTE_ALBUMS) { navController.getBackStackEntry(ROUTE_ALBUMS) }
```
with:
```kotlin
val albumsEntry = remember(backStack) { navController.getBackStackEntry(ROUTE_ALBUMS) }
```
(where `backStack` is the composable's `NavBackStackEntry` parameter, same as in the ROUTE_ALBUM_DETAIL composable).

Then remove the `@SuppressLint` annotation.

## Impact
- Could return a stale `AlbumsViewModel` if the back stack is reconstructed
- Lint suppression hides the warning, which means reviewers have no visibility into this risk""",
        ["bug"]
    ),
    # Tech Debt 14: loadThumbnails O(N) sequential
    (
        "[Performance] AlbumsViewModel.loadThumbnails() fetches thumbnail URLs sequentially, O(N) round-trips",
        """## Problem
`loadThumbnails()` in `AlbumsViewModel` calls:
```kotlin
val urls = photos.associate { photo -> photo.href to r.thumbnailUrl(photo) }
```
`thumbnailUrl()` is a `suspend` function that checks Memories API availability once and then builds a URL. However it is called sequentially for every photo in a standard `associate` call inside a `launch` coroutine. For albums with many photos, this creates O(N) sequential suspensions.

While `thumbnailUrl()` itself mostly does string construction after the first call (once `ensureMemoriesChecked()` resolves), on non-Nextcloud servers each call hits the network. A parallel approach using `async/await` would be faster.

## Fix
Use `coroutineScope` + `async` + `awaitAll`:
```kotlin
private fun loadThumbnails(photos: List<RemotePhoto>) {
    viewModelScope.launch {
        val r = getRepo()
        coroutineScope {
            val urls = photos
                .map { photo -> async { photo.href to r.thumbnailUrl(photo) } }
                .awaitAll()
                .toMap()
            _detailState.update { it.copy(thumbnailUrls = urls) }
        }
    }
}
```

## Impact
- Noticeably slower album loading on large albums or non-Nextcloud servers""",
        ["bug"]
    ),
    # Bug 15: notification small icon
    (
        "[Bug] UploadWorker uses ic_launcher_foreground as notification small icon (visually incorrect)",
        """## Bug Description
`UploadWorker.buildProgressNotification()` and related methods use:
```kotlin
.setSmallIcon(R.drawable.ic_launcher_foreground)
```
Android notification guidelines require the small icon to be a monochrome, alpha-only drawable designed for status bar use. `ic_launcher_foreground` is a full-color adaptive icon foreground and will appear as a solid blob in the status bar on most devices.

## Fix
1. Create a dedicated notification icon `ic_notification_upload.xml` (monochrome, white/transparent)
2. Replace `R.drawable.ic_launcher_foreground` with `R.drawable.ic_notification_upload` in all three notification builder calls in `UploadWorker`

## Impact
- Poor visual appearance in the status bar and notification shade
- Violates Material Design notification icon guidelines""",
        ["bug"]
    ),
    # Tech Debt 16: PROPFIND no pagination
    (
        "[Performance] AlbumsRepository.propfind() loads all photos in a single PROPFIND — no pagination",
        """## Problem
`AlbumsRepository.propfind()` issues a `Depth: 1` PROPFIND request that returns every file in a directory in a single HTTP response. For albums with hundreds or thousands of photos, this means:
1. A very large HTTP response body is fully buffered in memory (`response.body.string()`)
2. XML parsing happens on the full string in one shot
3. All `RemotePhoto` objects are created and held in memory at once

This can cause Out-of-Memory errors or ANRs on large albums.

## Proposed Solution
- Short-term: Add a documented limit (e.g. 500 photos) and show a warning if exceeded
- Long-term: Implement cursor-based pagination using the Nextcloud DAV `{http://owncloud.org/ns}chunk` or `Range` header, or the Nextcloud Files API with `startIndex`/`count` parameters

## Acceptance Criteria
- [ ] Large albums (500+ photos) do not crash the app
- [ ] A progress indicator is shown while loading
- [ ] Or pagination is implemented with lazy loading""",
        ["bug"]
    ),
    # Tech Debt 17: DRY violation in deletion
    (
        "[Technical Debt] AlbumsViewModel: deletePhotos, deleteBlurryPhotos, deleteSelectedPhotos share identical deletion logic",
        """## Problem
`AlbumsViewModel` has three nearly-identical functions for deleting photos:
- `deletePhotos()` (via duplicates flow — sets `duplicatesState = Deleting`)
- `deleteBlurryPhotos()` (via blur flow — sets `blurState = Deleting`)
- `deleteSelectedPhotos()` (via manual selection — sets `isDeletingPhotos = true`)

All three:
1. Guard against empty list
2. Iterate over the list and call `r.deletePhoto(photo)`
3. Count failures
4. Update `detailState` via `withPhotosDeleted()`
5. Reset their respective loading flag
6. Call `onComplete(failures)`

This violates DRY and means any fix to deletion logic must be applied in three places.

## Fix
Extract a private `suspend fun performDeletion(toDelete: List<RemotePhoto>): Int` that handles steps 2-4 and returns the failure count. The three public functions manage only their own loading state and delegate to this helper.

## Impact
- Future bugs in deletion (e.g., error handling, retry) must be fixed three times
- Increases risk of inconsistency between the three flows""",
        ["bug"]
    ),
    # Tech Debt 18: AlbumsViewModel too many responsibilities
    (
        "[Technical Debt] AlbumsViewModel manages albums list, album detail, duplicates, blur, and deletion — violates Single Responsibility",
        """## Problem
`AlbumsViewModel` currently owns:
- `albumsState` — list of remote albums
- `detailState` — photos within one album
- `duplicatesState` — duplicate detection flow
- `blurState` — blur detection flow
- `isDeletingPhotos` — selection-mode deletion
- `isDeletingAlbum` — album deletion

This makes the ViewModel very large (~375 lines), hard to test in isolation, and means all screens that need just one piece of state must collect the entire ViewModel.

## Proposed Solution
Split into:
- `AlbumsViewModel` — manages only the albums list and album deletion
- `AlbumDetailViewModel` — manages the detail screen (photos, selection, deletion of individual photos)
- Keep `duplicatesState` and `blurState` in `AlbumDetailViewModel` or further extract to `DuplicatesViewModel`/`BlurViewModel`

Navigation would need to be updated to scope each ViewModel appropriately.

## Impact
- Large class is harder to understand and test
- Any change risks accidentally breaking an unrelated feature
- Test file `AlbumsViewModelTest.kt` is already quite large""",
        ["bug"]
    ),
    # Bug 19: accessibility
    (
        "[Accessibility] Local photo grid items lack meaningful content descriptions for screen readers",
        """## Problem
In `PhotoGridScreen.kt`, `PhotoItem` renders:
```kotlin
AsyncImage(
    ...
    contentDescription = photo.displayName,
    ...
)
```
`photo.displayName` is typically a raw filename like `IMG_20260501_123456.jpg`. This is not meaningful for TalkBack users.

Additionally, the selection state (checked/unchecked icon) has a content description but the overall item's clickable has none, so TalkBack only reads the icon description, not the photo context.

## Fix
- Provide a meaningful `contentDescription` such as `"Photo taken on ${photo.dateTaken.format(...)}, ${if selected 'selected' else 'not selected'}"`
- Ensure the `Modifier.clickable` on the item has a matching `onClickLabel`
- Use `Modifier.semantics` to merge child semantics into the parent for a single, coherent TalkBack announcement

## Impact
- App is not accessible to visually impaired users who rely on TalkBack""",
        ["bug"]
    ),
    # Bug 20: CredentialStore silent exception
    (
        "[Bug] CredentialStore.decryptValue() silently returns null on any exception, making credential corruption undiagnosable",
        """## Problem
```kotlin
private fun decryptValue(encoded: String?): String? {
    if (encoded == null) return null
    return runCatching {
        ...
    }.getOrNull()
}
```
Any decryption exception — including `KeyPermanentlyInvalidatedException` (key wiped after biometric enrollment change), malformed data, or AES tag mismatch — is silently swallowed. The function returns `null`, which causes `isConfigured` to return `false`, and the user is silently logged out with no explanation.

## Fix
1. Log a warning in the `runCatching` failure branch (using Android `Log.w`)
2. For `KeyPermanentlyInvalidatedException` specifically, surface a user-facing message: "Your stored credentials could not be decrypted because the device security settings changed. Please log in again."
3. Optionally expose a `CredentialStore.credentialState: Flow<CredentialState>` that distinguishes `Missing`, `Valid`, and `Corrupted` states

## Impact
- Users who change their biometric settings are silently logged out with no feedback
- Diagnosing credential storage bugs in production requires crash reporting that doesn't exist""",
        ["bug"]
    ),
]

for i, (title, body, labels) in enumerate(issues):
    print(f"Creating issue {i+1}/{len(issues)}: {title[:60]}...")
    result = create_issue(title, body, labels, i+200)
    print(f"  -> {result}")
    time.sleep(0.5)  # Small delay to avoid rate limiting

print("Done!")
