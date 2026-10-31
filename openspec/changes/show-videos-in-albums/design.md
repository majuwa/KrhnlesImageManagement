## Context

Observed today (see proposal.md for motivation):

- `AlbumsRepository.listPhotos()` streams one PROPFIND (`displayname`, `resourcetype`, `getcontenttype`, `oc:fileid`) through `PropfindEntryReader` into `PhotoListingCollector`, which keeps an entry only if `AlbumsRepository.isImage(entry)` (content type `image/*`, else extension in `IMAGE_EXTENSIONS` regardless of the content type) and stops at `MAX_PHOTOS_PER_ALBUM` (20,000) or the 32 MiB byte cap. `RemotePhoto` has `displayName`, `href`, `fileId`, `contentType`; there is no size, date or duration.
- `thumbnailUrl()` / `fullImageUrl()` return the Memories preview (`<nextcloudBase>/index.php/apps/memories/api/image/preview/<id>`) when Memories is available and the file id is alphanumeric, otherwise the WebDAV URL of the file itself. For a video this fallback would make Coil download the whole video (and `LocalVideoFrameDecoderFactory` would then refuse to decode it).
- `AlbumDetailViewModel` resolves `thumbnailUrls: Map<href, String>` on `backgroundDispatcher` and publishes once; the album grid tile (`PhotoGridItem` in `AlbumDetailScreen.kt`) shows `AsyncImage(thumbUrl)` with `contentDescription = photoPositionDescription(...)` or a spinner when the URL is missing.
- `FullscreenViewerScreen` (route `albums/viewer`, uses the detail entry's `AlbumDetailViewModel`) renders every page as a `ZoomablePage(fullImageUrl)` in a `HorizontalPager`; it has a hardcoded `"Back"` description and page counter.
- The duplicate / blur reviews pass `detailState.photos` and `thumbnailUrls` to `findDuplicates()` / `findBlurryPhotos()` → `scanAlbumPhotos()` → `downloadBitmapForHash()` (URL allow-list: below base folder or Memories preview).
- Each album flow has one `AlbumsRepository` with one OkHttp client whose application interceptor adds `Authorization` only for `hasSameOriginAs(baseHttpUrl)`. `AlbumsRepositoryProvider` closes a repository when the configuration changes or the detail ViewModel is cleared.
- Coil's loader (`KrhnlesApplication`) has its own origin-restricted authenticated client and `serviceLoaderEnabled(false)`; remote video bytes are never decoded.
- `gradle/libs.versions.toml` has no media playback library (Coil, `coil-video`, OkHttp 5.5.1, Compose BOM 2026.09.00).

## Goals / Non-Goals

**Goals:**
- One classification function for remote media; videos flow through the existing listing, selection and deletion code unchanged.
- No code path ever requests a video's WebDAV URL except the player, and the player only after a tap.
- Streaming uses the same trust, credential-origin and redirect rules as the rest of the album flow, owned by the album flow's repository.
- Reviews stay image-only with one filtering point plus a defensive guard.

**Non-Goals:**
- Remote durations, sizes or dates in the grid (would need more PROPFIND properties; not requested).
- Background / PiP playback, playlists, subtitles, audio-track selection, adaptive streaming.
- Changing image classification (the extension fallback of `isImage` stays as it is).

## Decisions

### 1. Classification: `classifyRemoteMedia(entry): MediaKind?`

A pure function in `AlbumsRepository`'s companion replaces the `isImage` call in `PhotoListingCollector`:

1. directory → `null`;
2. `isImage(entry)` (unchanged) → `MediaKind.Image`;
3. normalized content type (lower-case, parameters after `;` stripped) in `VIDEO_CONTENT_TYPES` → `MediaKind.Video`;
4. content type blank or `application/octet-stream`, and extension in `VIDEO_EXTENSIONS` → `MediaKind.Video`;
5. otherwise `null` (not listed).

`VIDEO_CONTENT_TYPES` = `video/mp4`, `video/x-m4v`, `video/quicktime`, `video/3gpp`, `video/3gpp2`, `video/webm`, `video/x-matroska`; `VIDEO_EXTENSIONS` = `mp4`, `m4v`, `mov`, `3gp`, `3g2`, `webm`, `mkv`. These are what phone cameras (Android, iOS) produce and what the restricted extractor set (decision 6) can parse; AVI, WMV, FLV, MPEG-TS are left out so the grid does not list files that would then fail to play. A specific non-video content type wins over a video extension (`notes.mp4` + `text/plain` is not listed), unlike the image fallback, which is kept for compatibility.

`model.MediaKind` (already used by `Photo`) is reused; `RemotePhoto` gains `mediaKind: MediaKind = MediaKind.Image` and `isVideo`. The class keeps its name (renaming `RemotePhoto` everywhere is churn with no behavioural value). Alternatives: a new `RemoteMediaKind` enum (rejected: duplicate of `MediaKind`); keeping `isImage` and adding `isVideo` side by side (rejected: two predicates could both be true for `photo.jpg` with `video/mp4`; one function fixes precedence).

### 2. The 20,000 cap counts photos and videos

`PhotoListingCollector` counts every listed item. The cap exists to bound memory, UI list size and thumbnail resolution work, which depend on the item count, not on the kind; counting only images would let a video-heavy album grow without bound. `MAX_PHOTOS_PER_ALBUM` keeps its name (referenced in CLAUDE.md, tests and docs); its KDoc and the docs say "media items (photos and videos)". Consequence: in an album above the cap the shown part may now contain fewer than 20,000 photos; truncation is announced as before.

### 3. Preview URLs never point at the video file

Contract changes in `AlbumsRepositoryContract`:

- `suspend fun thumbnailUrl(photo): String?` and `fun fullImageUrl(photo): String?`. Images: unchanged and never `null` for a listed image. Videos: `videoPreviewUrl(photo, size)` = the Memories preview when `memoriesAvailable == true` and the file id is safe; else, on a Nextcloud server (`nextcloudBase != null`) with a safe file id, the core preview `<nextcloudBase>/index.php/core/preview?fileId=<id>&x=<size>&y=<size>&a=1&mimeFallback=false` (`mimeFallback=false` so a missing preview is a 404, not a redirect to a mime icon); else `null`.
- No new availability request: the core preview is used blind; a 404 / error / non-image answer simply ends in the placeholder (Coil error state; `LocalVideoFrameDecoderFactory` keeps refusing network video bytes).
- `AlbumDetailViewModel.thumbnailUrls` keeps `Map<String, String>`; videos without a preview are absent. The tile shows the spinner only for an image without URL (unchanged) and the placeholder for a video without URL. `AlbumDetailViewModel.fullImageUrl()` returns `String?` (`null` before load or for a preview-less video).
- `downloadBitmapForHash()` and its allow-list stay unchanged: reviews never see videos (decision 5), so the core preview endpoint is not added to that allow-list.

Alternative: extract a frame by requesting the first bytes of the video with a Range request and `MediaMetadataRetriever` (rejected by the request: no download to get a frame; it would also feed untrusted remote bytes to the platform retriever, which the app deliberately avoids).

### 4. Grid tile and accessibility

`PhotoGridItem` (album) gets a `photo.isVideo` branch: poster `AsyncImage` (error / null → `Icons.Outlined.Movie` placeholder) plus a centred `PlayCircle` indicator, both `contentDescription = null`. The tile's description moves from the `AsyncImage` to the tile `Box` (`semantics(mergeDescendants = true) { contentDescription = … }`) so it is present with poster, placeholder or spinner. A new `mediaPositionDescription(photo, position, total)` in `ui/components/PhotoDescriptions.kt` returns `cd_photo_position` or the new `cd_video_position` ("Video %1$d of %2$d"); album tiles and viewer pages use it. Remote videos have no duration or date, so the description is position only, which matches the existing remote photo description. `NoFilenameContentDescriptionRegressionTest` stays green (no `displayName` / `href`).

### 5. Reviews filter videos once, scan guards again

- `DuplicatesViewModel.findDuplicates` / `retryFindDuplicates` and `BlurViewModel.findBlurryPhotos` / `retryFindBlurryPhotos` call `photos.imagesOnly()` (`List<RemotePhoto>.filterNot { it.isVideo }`, next to `RemotePhoto`) before anything else, so progress totals and results only cover photos.
- `scanAlbumPhotos()` skips a video without a request (defensive guard; documented in its KDoc) so a future caller cannot download a video for analysis.
- `AlbumDetailScreen` shows the review FABs only when `state.photos.any { !it.isVideo }`.
- Review deletions go through `launchPhotoDeletion()` with review results only, so they never contain videos. `AlbumPhotoDeletionTest` keeps all three `DeletePath`s and gains a video in the album: `SELECTION` deletes it; `DUPLICATES` / `BLUR` leave it in place.

Alternative: filter only inside `scanAlbumPhotos()` (rejected: progress "N of M" would count videos that are never analysed).

### 6. Playback: in-app Media3 ExoPlayer (option a) — chosen

Options evaluated:

| | (a) Media3 ExoPlayer, OkHttp data source | (b) No in-app playback: Save to device / open in Nextcloud | (c) Platform `MediaPlayer` / `VideoView` with a headers map |
|---|---|---|---|
| Privacy | Stream goes only to the user's server, in-process; Media3 has no telemetry; no copy left on the device (no `SimpleCache`) | Save: full copy in shared storage, visible to other apps; Nextcloud: needs the Nextcloud app or a browser session; a share link would publish the file | Credentials handed to the media server process |
| Security | Same OkHttp client lineage as listings: origin-restricted `Authorization`, pinned trust (sibling), redirects disabled, URL guard; container parsing in memory-safe Kotlin/Java with a restricted extractor set; codecs in the platform codec sandbox | No new parser in the app; but any external viewer would need the credentials or a public link | Platform HTTP stack follows redirects with the custom headers, cannot be origin-restricted and ignores app-level pinned trust → rejected |
| UX | Tap play, starts in seconds, seek via Range | Multi-GB download before watching; nothing for plain WebDAV servers | Similar to (a) |
| Weight | `media3-exoplayer` + `media3-datasource-okhttp` + `media3-ui-compose`; about 1–1.5 MB after R8 (measured in tasks); AndroidX, Apache-2.0, same release train as the rest of Jetpack | None | None |

Decision: (a). It is the only option that lets users watch their own videos without handing credentials or files to anything outside the app, and it is the only one that can enforce the app's existing network rules (origin-restricted credentials, no redirects, pinned trust). The dependency is the Jetpack standard for playback, modular, and limited to three artifacts. (b) remains complementary: the sibling `download-album-photos` adds "Save to device" but skips videos as "unsupported type" for now; extending it to stream videos to `MediaStore.Video` is a follow-up of that change, not of this one.

Implementation shape:

- **Repository side (no Media3 types in `data/`):** `AlbumsRepositoryContract.videoStreamUrl(photo): String?` = `resolveWebDavHref(baseFolderUrl, href)` for videos only (null for images or unsafe hrefs); `videoCallFactory(): okhttp3.Call.Factory` = a `videoClient` derived from the repository's `client.newBuilder()` (inherits the origin-restricted auth interceptor, timeouts and — once `pin-self-signed-certificate` lands — the trust configuration of its trust factory; if that change's source guard rejects `newBuilder()` derivations, the video client is built through the trust factory with the same settings instead. Its provider cache key includes the pin, so a pin change closes the repository and the video client with it) with `followRedirects(false)`, `followSslRedirects(false)`, its own `Dispatcher`, no `callTimeout`, and a first application interceptor that (1) fails with `RepositoryClosedException` (an `IOException`) without proceeding once the repository is closed, (2) fails with `UnsafeHrefException` unless the URL resolves strictly below the base folder on the configured origin, (3) sets `Accept-Encoding: identity` so byte ranges refer to the stored file. `close()` sets the closed flag and `videoClient.dispatcher.cancelAll()`.
- **Player side (`ui/viewer/`):** `VideoPlayerViewModel` (`AndroidViewModel(Application)`, default-factory creatable, internal test constructor taking a `VideoPlayerFactory`) scoped to the viewer entry with plain `viewModel()`. It never references `AlbumDetailViewModel`; the viewer passes the photo and `detailVm.repositories` to `play(photo, repositories)` like the reviews do. `play` is a guarded one-shot (job + generation): `repositories.get()` (provider timeout), `videoStreamUrl()`, then the factory builds an `ExoPlayer` with `ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(videoCallFactory()), restrictedExtractors)` where `restrictedExtractors` = MP4, fragmented MP4 and Matroska/WebM only; `MediaItem` with the stream URL. State: `VideoPlaybackState` = `Idle` / `Preparing(href)` / `Playing(href, isPlaying)` / `Failed(href, VideoPlaybackError)`. `stop()` releases the player; `pause()` pauses; `retry()` = `stop()` + `play()`; `onCleared()` releases. Media3 APIs marked `@UnstableApi` are opted into with one narrowly scoped `@OptIn(UnstableApi::class)` on the factory function (an API-stability opt-in, not a lint suppression).
- **Viewer:** a page renders `VideoPage` when `photo.isVideo`, else `ZoomablePage`. `VideoPage` shows the poster (`fullImageUrl`, error / null → placeholder) and a play button; while this page's href is playing it shows `PlayerSurface` (media3-ui-compose) plus Compose controls (play / pause button, `Slider` seek bar with `stateDescription` "0:30 of 2:00" built from the local grid's `VideoDuration.kt` spoken-duration helpers, elapsed / total text). `LaunchedEffect(pagerState.currentPage)` stops a player whose href is not the current page; `LifecycleEventEffect(ON_STOP)` pauses; `LaunchedEffect(detailState)` stops when the album reports `NotConfigured` / `ConfigurationChanged` or the playing href is no longer in `photos`. Controls auto-hide only while playing, using `LocalAccessibilityManager.calculateRecommendedTimeoutMillis` so TalkBack users are not raced. Existing hardcoded viewer strings ("Back", counter) move to resources if `german-translation` has not done so yet.
- **Errors:** `PlaybackException.toVideoPlaybackError()` branches on Media3 error codes and on `HttpDataSource.InvalidResponseCodeException.responseCode` (3xx → `Redirected`; 401 / 403 / 404 / 410 / 5xx → the matching `AlbumsError`), network / timeout / SSL causes → existing `AlbumsError` variants via `Throwable.toAlbumsError()`, decoder / parser / unsupported container → `UnsupportedFormat`, `UnsafeHrefException` / `RepositoryClosedException` → generic. Strings: `albumsErrorMessage()` for `AlbumsError`, new `video_error_unsupported_format`, `video_error_redirected`. Only the exception type is logged.

### 7. Wording

String values change, names stay (fewer churn for tests and the German translation): `empty_album_photos` ("No photos or videos in this album."), `album_loading_photos`, `album_truncated_notice`, `album_truncated_empty_notice`, `dialog_delete_photos_title`, `dialog_delete_photos_message`, `snackbar_delete_failures`, `status_deleting_photos` (shared with the reviews; a neutral "Deleting…" fits both), `dialog_delete_album_message`. Review-only strings (`delete_n_photos`, review titles, empty results) stay photo-specific. New: `cd_video_position`, `cd_play_video`, `cd_pause_video`, `cd_video_seek`, `video_position_state`, `video_error_unsupported_format`, `video_error_redirected`.

## Risks / Trade-offs

- [Most servers have no video previews: Nextcloud needs the Movie preview provider + ffmpeg, plain WebDAV has none] → placeholder with play indicator is the designed default; README explains how to enable Nextcloud video previews.
- [Core preview with Basic auth / app password not accepted by some Nextcloud setups] → failure ends in the placeholder; verified manually on a real Nextcloud (task).
- [Device cannot decode HEVC 10-bit / Dolby Vision from newer iPhones] → typed "format not supported on this device" message; the file remains deletable and, via the sibling, saveable.
- [APK size grows] → only three Media3 artifacts, restricted extractor set lets R8 drop the others; size measured and recorded in the tasks.
- [ExoPlayer is hard to run in Robolectric] → ViewModel and UI tests use a fake `VideoPlayerFactory`; the network contract is tested at the data-source level with the real `OkHttpDataSource` against `MockWebServer`; real playback is a manual device check.
- [Streaming on mobile data uses volume] → playback is always user-initiated and nothing is preloaded; the Wi-Fi-only setting stays upload-only (open question below).
- [Album above the cap now shows fewer photos when it also has videos] → documented; the truncation notice already explains partial albums.
- [`SurfaceView` inside a `HorizontalPager` during swipe animations] → minSdk 35 handles `SurfaceView` transforms; checked manually; `TextureView` surface type is the fallback.

## Migration Plan

No persisted data changes. The repository contract change is internal (fakes updated). Rollback = revert the change; the Media3 dependency is removed with it.

## Open Questions

- Should playback on a metered network ask for confirmation or honour the Wi-Fi-only upload setting? Default here: no prompt, since the user starts playback explicitly.
- Is a `@OptIn(UnstableApi::class)` on the single player factory acceptable under the "do not suppress" guideline (it is an opt-in, not a suppression)? If not, the implementation must stay on the stable Media3 API surface only, which may require `DefaultMediaSourceFactory` instead of the restricted extractor set.
- Should the Nextcloud core preview also be used for image thumbnails when Memories is missing (today images then load the original)? Deliberately not changed here.
