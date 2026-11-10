## Context

Observed today (see proposal.md for motivation):

- `AlbumsRepository.listPhotos()` streams one PROPFIND (`displayname`, `resourcetype`, `getcontenttype`, `oc:fileid`) through `PropfindEntryReader` into `PhotoListingCollector`, which keeps an entry only if `AlbumsRepository.isImage(entry)` (content type `image/*`, else extension in `IMAGE_EXTENSIONS` regardless of the content type) and stops at `MAX_PHOTOS_PER_ALBUM` (20,000) or the 32 MiB byte cap. `RemotePhoto` has `displayName`, `href`, `fileId`, `contentType`; there is no size, date or duration.
- `thumbnailUrl()` / `fullImageUrl()` return the Memories preview (`<nextcloudBase>/index.php/apps/memories/api/image/preview/<id>`) when Memories is available and the file id is alphanumeric, otherwise the WebDAV URL of the file itself. For a video this fallback would make Coil download the whole video (and `LocalVideoFrameDecoderFactory` would then refuse to decode it).
- `AlbumDetailViewModel` resolves `thumbnailUrls: Map<href, String>` on `backgroundDispatcher` and publishes once; the album grid tile (`PhotoGridItem` in `AlbumDetailScreen.kt`) shows `AsyncImage(thumbUrl)` with `contentDescription = photoPositionDescription(...)` or a spinner when the URL is missing.
- `FullscreenViewerScreen` (route `albums/viewer`, uses the detail entry's `AlbumDetailViewModel`) renders every page as a `ZoomablePage(fullImageUrl)` in a `HorizontalPager`; it has a hardcoded `"Back"` description and page counter.
- The duplicate / blur reviews pass `detailState.photos` and `thumbnailUrls` to `findDuplicates()` / `findBlurryPhotos()` → `scanAlbumPhotos()` → `downloadBitmapForHash()` (URL allow-list: below base folder or Memories preview).
- Each album flow has one `AlbumsRepository(config, pin)` with one OkHttp client built by `serverHttpClientBuilder(ServerTrust(pin))` (certificate pinning, issue #71, is implemented) whose application interceptor adds `Authorization` only for `hasSameOriginAs(baseHttpUrl)`; the client follows OkHttp's default redirects and has no cache. `AlbumsRepositoryProvider` re-reads config **and pin** on every `get()` and closes the old repository when either changed, on `invalidate()` and when the detail ViewModel is cleared. `close()` today only stops the Memories retries.
- `NoTrustAllRegressionTest` (`TlsSourceScanner`) forbids `OkHttpClient(` / `OkHttpClient.Builder(` outside `data/tls/` and `DefaultHttpDataSource` everywhere; a `client.newBuilder()` derivation (as `WebDavClient.uploadClient` / `assemblyClient` do) keeps the TLS setup and is allowed. `ServerClientsPinningTest` checks every client kind against a self-signed `MockWebServer`.
- Coil's loader (`KrhnlesApplication`) uses a `PinnedClientHolder` with its own origin-restricted authenticated client and `serviceLoaderEnabled(false)`; remote video bytes are never decoded.
- The album grid tile (`PhotoGridItem`) is a `combinedClickable` `Box` (no selection semantics today; the `toggleable` rule 9 pattern applies to the local grid only). The viewer route has no bottom bar, so the viewer can only be left by Back (its entry is then popped).
- The Activity does not handle configuration changes itself: rotation recreates it and the viewer entry goes through `ON_STOP`.
- Album search / sort (`album-search-and-sort`, archived) orders and filters the **album list** only; photos inside an album keep server order. There is no interaction with this change.
- `gradle/libs.versions.toml` has no media playback library (Coil, `coil-video`, OkHttp 5.5.1, Compose BOM 2026.09.00). Current Media3 release: 1.11.1 (Aug 2026, verified via context7).

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

Checking the directory first is the only image-side difference: a collection that claims an `image/*` content type (today accepted by `isImage`, which tests the content type before `isDirectory`) is no longer listed.

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

`PhotoGridItem` (album) gets a `photo.isVideo` branch: poster `AsyncImage` (error / null → `Icons.Outlined.Movie` placeholder) plus a centred `PlayCircle` indicator, both `contentDescription = null`. The tile's description moves from the `AsyncImage` to the tile `Box` (`semantics(mergeDescendants = true) { contentDescription = … }`) so it is present with poster, placeholder or spinner. A new `mediaPositionDescription(photo, position, total)` in `ui/components/PhotoDescriptions.kt` returns `cd_photo_position` or the new `cd_video_position` ("Video %1$d of %2$d"); album tiles and viewer pages use it. Remote videos have no duration or date, so the description is position only, which matches the existing remote photo description. `NoFilenameContentDescriptionRegressionTest` stays green (no `displayName` / `href`). Selection semantics are not changed: album tiles (photo or video) expose click / long-click only, no selection state, today; a video tile gets exactly the photo tile's semantics. Adding a selection state to album tiles is a separate accessibility follow-up, not part of this change.

### 5. Reviews filter videos once, scan guards again

- `DuplicatesViewModel.findDuplicates` / `retryFindDuplicates` and `BlurViewModel.findBlurryPhotos` / `retryFindBlurryPhotos` call `photos.imagesOnly()` (`List<RemotePhoto>.filterNot { it.isVideo }`, next to `RemotePhoto`) before starting the scan, so progress totals and results only cover photos. The existing `retry…` guard `photos.isEmpty()` ("album not loaded, keep the error") stays on the **unfiltered** list; the filter is applied after it, so a loaded album without photos ends in the existing empty branch of `startScan` (`NoneFound`), never in a retry that silently does nothing.
- `scanAlbumPhotos()` skips a video without a request (defensive guard; documented in its KDoc) so a future caller cannot download a video for analysis.
- `AlbumDetailScreen` shows the review FABs only when `state.photos.any { !it.isVideo }`.
- Review deletions go through `launchPhotoDeletion()` with review results only, so they never contain videos. `AlbumPhotoDeletionTest` keeps all three `DeletePath`s and gains a video in the album: `SELECTION` deletes it; `DUPLICATES` / `BLUR` leave it in place.

Alternative: filter only inside `scanAlbumPhotos()` (rejected: progress "N of M" would count videos that are never analysed).

### 6. Playback: in-app Media3 ExoPlayer (option a) — chosen

Options evaluated:

| | (a) Media3 ExoPlayer, OkHttp data source | (b) No in-app playback: Save to device / open in Nextcloud | (c) Platform `MediaPlayer` / `VideoView` with a headers map |
|---|---|---|---|
| Privacy | Stream goes only to the user's server, in-process; Media3 has no telemetry; no copy left on the device (no `SimpleCache`) | Save: full copy in shared storage, visible to other apps; Nextcloud: needs the Nextcloud app or a browser session; a share link would publish the file | Credentials handed to the media server process |
| Security | Same OkHttp client lineage as listings: origin-restricted `Authorization`, pinned trust (`serverHttpClientBuilder`), redirects disabled, URL guard; container parsing in memory-safe Kotlin/Java with a restricted extractor set; codecs in the platform codec sandbox | No new parser in the app; but any external viewer would need the credentials or a public link | Platform HTTP stack follows redirects with the custom headers, cannot be origin-restricted and ignores app-level pinned trust → rejected |
| UX | Tap play, starts in seconds, seek via Range | Multi-GB download before watching; nothing for plain WebDAV servers | Similar to (a) |
| Weight | `media3-exoplayer` + `media3-datasource-okhttp` (+ `media3-ui-compose` only if `ContentFrame` is stable API); about 1–1.5 MB after R8 (measured in tasks); AndroidX, Apache-2.0, same release train as the rest of Jetpack | None | None |

Decision: (a). It is the only option that lets users watch their own videos without handing credentials or files to anything outside the app, and it is the only one that can enforce the app's existing network rules (origin-restricted credentials, no redirects, pinned trust). The dependency is the Jetpack standard for playback, modular, and limited to two or three artifacts (one version ref `media3 = "1.11.1"`). (b) remains complementary: the sibling `download-album-photos` adds "Save to device" but skips videos as "unsupported type" for now; extending it to stream videos to `MediaStore.Video` is a follow-up of that change, not of this one.

Implementation shape:

- **Repository side (no Media3 types in `data/`):** `AlbumsRepositoryContract.videoStreamUrl(photo): String?` = `resolveWebDavHref(baseFolderUrl, href)` for videos only (null for images or unsafe hrefs); `videoCallFactory(): okhttp3.Call.Factory` = a lazily built `videoClient` derived from the repository's pin-aware `client.newBuilder()` (inherits the trust of `serverHttpClientBuilder(ServerTrust(pin))`, the origin-restricted auth interceptor and the timeouts; `newBuilder()` is allowed by `NoTrustAllRegressionTest`, no new client is built outside `data/tls/`; a pin change already rebuilds and closes the repository in `AlbumsRepositoryProvider`, and with it the video client) with `followRedirects(false)`, `followSslRedirects(false)`, its own `Dispatcher()` (so `cancelAll()` never touches album calls), no cache, no `callTimeout`, and a guard application interceptor (added after the auth interceptor; it runs before any network I/O either way) that:
  1. fails with `RepositoryClosedException` once the repository is closed,
  2. fails with `UnsafeVideoUrlException` unless the method is `GET` without a body and the URL resolves strictly below the base folder on the configured origin (`resolveWebDavHref(baseFolderUrl, url.toString())`),
  3. sets `Accept-Encoding: identity` so byte ranges refer to the stored file (OkHttpDataSource already sends it unless `FLAG_ALLOW_GZIP`; kept as defence in depth),
  4. replaces the body of every non-2xx response with an empty body (original body closed): `OkHttpDataSource.open()` reads the **whole** error body into memory (`InvalidResponseCodeException.responseBody`), so a hostile or broken server could otherwise exhaust memory with an endless 4xx / 3xx body; the body is never shown anyway.

  Both guard exceptions **must extend `IOException`** (never `UnsafeHrefException`, an `IllegalArgumentException`): `OkHttpDataSource` runs calls with `Call.enqueue()`, and OkHttp rethrows a non-`IOException` thrown by an interceptor on its dispatcher thread, which crashes the app. `close()` sets the closed flag (`AtomicBoolean`) and calls `videoClient.dispatcher.cancelAll()` if the client was built.
- **Player side (`ui/viewer/`):** `VideoPlayerViewModel` (`AndroidViewModel(Application)`, default-factory creatable, internal test constructor taking a `VideoPlayerFactory`) scoped to the viewer entry with plain `viewModel()`. It never references `AlbumDetailViewModel`; the viewer passes the photo and `detailVm.repositories` to `play(photo, repositories)` like the reviews do. `play` is a guarded one-shot (job + generation): `repositories.get()` (provider timeout), `videoStreamUrl()`, then the factory builds the player. State: `VideoPlaybackState` = `Idle` / `Preparing(href)` / `Playing(href, isPlaying)` / `Failed(href, VideoPlaybackError)`. `stop()` releases the player; `pause()` pauses; resume re-asks `repositories.get()` and continues the existing player only if it returns the repository the player was built with (else the player is released and `play()` starts again — same identity check as `scanAlbumPhotos()`, so a resumed stream never outlives a pin / config change); `retry()` = `stop()` + `play()`; `onCleared()` releases.
- **Player factory (the only Media3 construction site):** `ExoPlayer.Builder(application, mediaSourceFactory)` with `mediaSourceFactory = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(videoCallFactory()), restrictedExtractors).setLoadErrorHandlingPolicy(noHttpStatusRetry)`, then `setMediaItem(MediaItem.fromUri(streamUrl))`.
  - Never the one-argument `ExoPlayer.Builder(context)`, `DefaultMediaSourceFactory` or `DefaultDataSource`: they route any `MediaItem` through `DefaultHttpDataSource` (system trust only, no credentials, platform redirect handling) and keep every extractor, so R8 cannot shrink them (Media3 shrinking guide). Guarded by extending `TlsSourceScanner` (task 5.3).
  - `restrictedExtractors` = `Mp4Extractor`, `FragmentedMp4Extractor` and `MatroskaExtractor` (covers mp4 / m4v / mov / 3gp / 3g2 / mkv / webm), each constructed with `DefaultSubtitleParserFactory()` as in the official shrinking guide (since Media3 1.4 subtitles are parsed during extraction; an extractor without a parser factory makes a file with an embedded subtitle track fail).
  - `noHttpStatusRetry` = `DefaultLoadErrorHandlingPolicy` that returns `C.TIME_UNSET` (no retry) for `InvalidResponseCodeException` and the guard exceptions: the default policy retries them, so one tap on a 401 would send several failed logins (Nextcloud brute-force throttling) and a redirect would be requested repeatedly. Transient network errors keep the default retries.
  - All `@UnstableApi` symbols (`ProgressiveMediaSource`, the extractors, the two-argument builder, the load error policy) are confined to this one function with `@OptIn(UnstableApi::class)` — **approved by the user** (an API-stability opt-in, not a lint suppression). `OkHttpDataSource.Factory(Call.Factory)`, `PlaybackException` and `HttpDataSource.InvalidResponseCodeException` are stable API, so the error mapping needs no opt-in.
- **Viewer:** a page renders `VideoPage` when `photo.isVideo`, else `ZoomablePage`. `VideoPage` shows the poster (`fullImageUrl`, error / null → placeholder) and a play button; while this page's href is playing it shows the video surface plus Compose controls (play / pause button, `Slider` seek bar with `stateDescription` "0:30 of 2:00" built from the local grid's `VideoDuration.kt` spoken-duration helpers, elapsed / total text; dragging the slider seeks and never swipes the pager). Surface: `ContentFrame` (media3-ui-compose ≥ 1.9, `PlayerSurface` + aspect ratio + shutter) if it is stable API in the chosen version; if lint reports it as `@UnstableApi`, the surface is an `AndroidView { SurfaceView }` attached with the stable `Player.setVideoSurfaceView()` / sized from `Player.videoSize` and `media3-ui-compose` is not added — a second `@OptIn` outside the factory is not approved. `LaunchedEffect(pagerState.currentPage)` stops a player whose href is not the current page; `LifecycleEventEffect(ON_STOP)` pauses **unless `LocalActivity.current?.isChangingConfigurations == true`** (the Activity is recreated on rotation, and a plain `ON_STOP` pause would contradict "rotation keeps playing"); `LaunchedEffect(detailState)` stops when the album reports `NotConfigured` / `ConfigurationChanged` or the playing href is no longer in `photos`. Controls auto-hide only while playing, using `LocalAccessibilityManager.calculateRecommendedTimeoutMillis` so TalkBack users are not raced. The hardcoded viewer "Back" description uses the existing `cd_back`; the page counter moves to a string resource.
- **Errors:** `PlaybackException.toVideoPlaybackError()` first walks the cause chain for the app's own exceptions (`UnsafeVideoUrlException` / `RepositoryClosedException` → generic), then branches on `HttpDataSource.InvalidResponseCodeException.responseCode` (3xx → `Redirected`; 401 / 403 / 404 / 410 / 5xx → the matching `AlbumsError`), then on Media3 error codes: decoder / parser / unsupported container → `UnsupportedFormat`; network / timeout / SSL → `Throwable.toAlbumsError()` applied to the innermost non-Media3 cause (so pinned-certificate failures still map to `CertificateChanged` / `CertificateError`). Strings: `albumsErrorMessage()` for `AlbumsError`, new `video_error_unsupported_format`, `video_error_redirected`. Only the exception type is logged.

### 7. Wording

String values change, names stay (fewer churn for tests and the German translation): `empty_album_photos` ("No photos or videos in this album."), `album_loading_photos`, `album_truncated_notice`, `album_truncated_empty_notice`, `dialog_delete_photos_title`, `dialog_delete_photos_message`, `snackbar_delete_failures`, `status_deleting_photos` (shared with the reviews; a neutral "Deleting…" fits both), `dialog_delete_album_message`. Review-only strings (`delete_n_photos`, review titles, empty results) stay photo-specific. New: `cd_video_position`, `cd_play_video`, `cd_pause_video`, `cd_video_seek`, `video_position_state`, `video_error_unsupported_format`, `video_error_redirected`.

## Risks / Trade-offs

- [Most servers have no video previews: Nextcloud needs the Movie preview provider + ffmpeg, plain WebDAV has none] → placeholder with play indicator is the designed default; README explains how to enable Nextcloud video previews.
- [Core preview with Basic auth / app password not accepted by some Nextcloud setups] → failure ends in the placeholder; verified manually on a real Nextcloud (task).
- [Device cannot decode HEVC 10-bit / Dolby Vision from newer iPhones] → typed "format not supported on this device" message; the file remains deletable and, via the sibling, saveable.
- [APK size grows] → at most three Media3 artifacts; the two-argument `ExoPlayer.Builder` plus the restricted extractor set let R8 drop `DefaultMediaSourceFactory`, `DefaultExtractorsFactory` and the unused extractors (the one-argument builder would keep them all); size measured and recorded in the tasks.
- [`media3-datasource-okhttp` is built against OkHttp 4] → Gradle resolves the app's OkHttp 5.5.1 (API-compatible for `Call.Factory`); checked with `./gradlew :app:dependencies` and covered by the real `OkHttpDataSource` MockWebServer test.
- [Server ignores `Range` and answers 200] → `OkHttpDataSource` skips to the position by reading, so a seek downloads the bytes before it; acceptable (Nextcloud / Apache / nginx support ranges), the spec's seeking scenario is stated for range-capable servers.
- [ExoPlayer is hard to run in Robolectric] → ViewModel and UI tests use a fake `VideoPlayerFactory`; the network contract is tested at the data-source level with the real `OkHttpDataSource` against `MockWebServer`; real playback is a manual device check.
- [Server ignores `Range`: no Wi-Fi-only gating] → the player downloads and discards the bytes before a seek position, bounded by the 32 MiB `LoadControl` buffer cap and by the user's own actions; playback is not gated by the upload-only Wi-Fi setting (accepted, documented in CLAUDE.md).
- [`Dispatcher.cancelAll()` does not reach a call whose headers arrived] → `VideoStreamClient` tracks live calls (guard adds, `EventListener` removes) and `close()` cancels them too.
- [Streaming on mobile data uses volume] → playback is always user-initiated and nothing is preloaded; the Wi-Fi-only setting stays upload-only (open question below).
- [Album above the cap now shows fewer photos when it also has videos] → documented; the truncation notice already explains partial albums.
- [`SurfaceView` inside a `HorizontalPager` during swipe animations] → minSdk 35 handles `SurfaceView` transforms; checked manually; `TextureView` surface type is the fallback.

## Migration Plan

No persisted data changes. The repository contract change is internal (fakes updated). Rollback = revert the change; the Media3 dependency is removed with it.

## Open Questions

- Should playback on a metered network ask for confirmation or honour the Wi-Fi-only upload setting? Default here: no prompt, since the user starts playback explicitly.
- Resolved: `@OptIn(UnstableApi::class)` on the single player factory function is approved by the user. Any further opt-in (e.g. for `media3-ui-compose`) is not approved and is avoided (see decision 6).
- Should the Nextcloud core preview also be used for image thumbnails when Memories is missing (today images then load the original)? Deliberately not changed here.
