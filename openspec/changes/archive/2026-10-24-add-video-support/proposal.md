## Why

The app only lists and uploads images (`READ_MEDIA_IMAGES`, `MediaStore.Images`), so users cannot back up the videos in their camera roll. Issues #15 and #34 (duplicates) ask for videos in the local grid and in uploads. This change closes both.

## What Changes

- Request `READ_MEDIA_VIDEO` together with `READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED`; keep working with images only when video access is missing.
- `MediaRepository` also queries `MediaStore.Video.Media` (name, date taken, size, mime type, duration) and merges the result with the images by capture time. Whether an item is a video comes from the query that produced it (`mediaKind`), not from the mime type.
- Videos get a collision-free `Photo.id` (image and video `_ID` spaces overlap), so selection, the uploaded-marker store and the upload queue keep working unchanged.
- Video tiles show a thumbnail (Coil `coil-video`), a play overlay and the duration; TalkBack announces them as videos. Day header counts and user-facing upload wording become media-neutral.
- Videos are selected and uploaded like photos (same WorkManager flow, streaming, auto date folders, retry). Small upload hardening needed for large files: cancellable upload call, known `Content-Length` when available, a longer per-upload read timeout, distinct messages for HTTP 413 / 507, and a worker that survives a refused foreground-service start.
- Out of scope: Photos / Videos filter (deferred), size-warning dialog, Nextcloud chunked upload v2 / resumable uploads (follow-up issue "Nextcloud chunked upload v2 for large videos"), sharing videos into the app (share target stays image-only), videos in album screens and the duplicate / blur reviews, `DATE_MODIFIED` fallback for undated videos.

## Capabilities

### New Capabilities
- `video-support`: videos in the local grid, permissions, upload and uploaded markers.

### Modified Capabilities
- `photo-grid-accessibility`: video tiles expose a video description with duration; day header counts cover all media.

## Impact

- Code: `AndroidManifest.xml`, `data/MediaRepository.kt`, `model/Photo.kt`, `ui/photogrid/*`, `KrhnlesApplication.kt`, `data/WebDavClient.kt` (`uploadFile`), `worker/UploadWorker.kt` (foreground failure), `gradle/libs.versions.toml` + `app/build.gradle.kts` (`coil-video`), `strings.xml`.
- Unchanged by design: `UploadedPhotosStore`, album screens and reviews. The queue format only gains an optional per-entry `uploaded` marker (backward compatible).
- Docs: `README.md` (incl. Play policy declaration for `READ_MEDIA_VIDEO`, uploaded videos not shown in Albums), `CLAUDE.md` (rule 2 permissions), `docs/architecture.md`, `docs/requirements.md` (G-002).
