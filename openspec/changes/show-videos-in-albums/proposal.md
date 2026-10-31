## Why

Feature request: "Videos can be uploaded since #15 / #34, but album listings are image-only, so uploaded videos never appear. Include video files in the album listing (classify by `getcontenttype` with extension fallback; allow-list of video types), mark them in the grid with a play icon (thumbnail: Nextcloud preview / Memories preview if available, else a placeholder icon — no full download to get a frame), with TalkBack descriptions consistent with the local grid. In the viewer, a video page shows a poster and a play button. Duplicate and blur reviews stay image-only; deletion and selection work for videos."

Today `AlbumsRepository.isImage` drops every non-image PROPFIND entry (CLAUDE.md: "Album listings and reviews stay image-only"), so a user who backs up a video can never see, play or clean it up in the Albums tab. The upload side is complete (streaming, verification, retry), which makes the read side the visible gap.

## What Changes

- **Listing:** album listings also keep video entries. An entry is a video when its `getcontenttype` is on a video allow-list, or — only when the content type is missing or generic (`application/octet-stream`) — when its extension is on a video extension allow-list. Image classification is unchanged. `RemotePhoto` gains a `mediaKind` (reusing `model.MediaKind`). The existing bounds stay; `MAX_PHOTOS_PER_ALBUM` (20,000) now counts every listed media item, photos and videos.
- **Thumbnails without downloading videos:** a video's grid thumbnail and viewer poster are the Nextcloud Memories preview (when available) or the Nextcloud core preview of its file id; otherwise (non-Nextcloud server, no safe file id, preview missing / failing) a placeholder icon is shown. The WebDAV address of a video is never requested as an image.
- **Album grid:** video tiles show a play indicator over the poster or placeholder; TalkBack announces "Video N of M" (photos stay "Photo N of M"), never the file name. Videos are selectable and deletable in selection mode exactly like photos (same `deletePhotosIndividually()` / `launchPhotoDeletion()` path).
- **Viewer and playback:** a video page shows its poster (or placeholder) and a play button instead of the zoomable image. Tapping play streams the video in-app with **AndroidX Media3 ExoPlayer** over an OkHttp data source that is derived from the album flow's OkHttp client (origin-restricted credentials, pinned trust once available, no redirects at all, Range requests for seeking, no disk cache, only URLs strictly below the configured base folder). Playback never starts on its own, stops when the page changes, the viewer is left, the app goes to the background (pause) or the server / account changes. Failures map to typed, localized messages with Retry. Option (b) "no in-app playback, Save to device / open in Nextcloud" was evaluated and rejected (see design.md).
- **Reviews:** the duplicate and blur reviews filter videos out before `scanAlbumPhotos()` (and `scanAlbumPhotos()` skips any video defensively); their buttons are offered only when the loaded album contains at least one photo. Review deletions therefore never include videos.
- **Wording:** album strings that now cover mixed media (empty album, loading count, truncation notices, delete dialog / progress, delete-album confirmation) become neutral ("photos and videos" / "items").
- **New dependency:** `androidx.media3:media3-exoplayer`, `media3-datasource-okhttp`, `media3-ui-compose` (one version ref `media3` in `gradle/libs.versions.toml`). No HLS / DASH / SmoothStreaming / IMA / cast modules.
- **BREAKING (internal API only):** `AlbumsRepositoryContract.thumbnailUrl()` / `fullImageUrl()` return `String?` (null only for a video without a server preview); the contract gains `videoStreamUrl()` and `videoCallFactory()`.
- Out of scope: server-side video transcoding, HLS, thumbnails extracted from the remote video, showing the duration of remote videos (PROPFIND has none), Picture-in-Picture / background playback, casting, a Photos / Videos filter in albums (see sibling `album-search-and-sort`), saving videos to the device (sibling `download-album-photos`), sharing videos into the app.

## Capabilities

### New Capabilities
- `album-video-playback`: video pages in the fullscreen viewer — poster, play button, in-app streaming, player lifecycle, typed playback errors, accessible controls.

### Modified Capabilities
- `album-photo-listing`: listings include allow-listed videos; the 20,000 cap counts photos and videos; video thumbnails never download the video.
- `video-support`: the requirement "Albums and reviews ignore videos" is replaced by "Duplicate and blur reviews ignore videos".
- `photo-grid-accessibility`: remote views describe videos as "Video N of M"; album video tiles are accessible.
- `album-photo-deletion`: videos are deleted through the same deletion behaviour as photos.
- `webdav-href-validation`: video streams stay on the configured server and below the base folder; credentials only go to the configured origin; no redirects.

## Impact

- Code: `model/RemotePhoto.kt`, `data/AlbumsRepository.kt` (classification, URL builders, video call factory, `close()`), `data/Repositories.kt` (contract), `ui/albums/AlbumDetailViewModel.kt`, `AlbumDetailScreen.kt`, `DuplicatesViewModel.kt`, `BlurViewModel.kt`, `PhotoScan.kt`, review screens, `ui/viewer/FullscreenViewerScreen.kt`, new `ui/viewer/VideoPlayerViewModel.kt` + `VideoPage.kt` + `VideoPlaybackError.kt`, `KrhnlesApp.kt` (viewer route), `strings.xml`, `fakes/FakeAlbumsRepository.kt`.
- Dependencies: Media3 (ExoPlayer, OkHttp data source, ui-compose); release APK size increase to be measured (expected low single-digit MB before R8, about 1–1.5 MB after).
- Unchanged: upload path, `downloadBitmapForHash()` allow-list (reviews never request video URLs), `LocalVideoFrameDecoderFactory` (remote video content is still never decoded by Coil), Memories availability (no new request type).
- Docs: `README.md`, `CLAUDE.md` (rule 2 "Album listings and reviews stay image-only", rule 3, rule 9, security conventions), `docs/architecture.md`, `docs/requirements.md` (A-002, A-003, new V-0xx entries).
- Sibling changes: `pin-self-signed-certificate` (the video client is derived from the album client built by its trust factory, so it carries the pin; its source guard against clients built outside the factory must accept this derivation; a pin change rebuilds the repository and thereby closes the video client), `download-album-photos` (skips videos as "unsupported type" for now; a follow-up extends it to stream videos to `MediaStore.Video`; its viewer action shares the overlay with the play button), `album-search-and-sort` (sorting applies to mixed media; a media-kind filter would build on `RemotePhoto.mediaKind`), `german-translation` (implemented last; picks up the new strings), `readme-roadmap-cleanup` (README edits overlap). No interaction with `nextcloud-chunked-upload`, `cancel-running-upload`, `occasion-folder-suggestions`, `new-photos-reminder` beyond the README.
