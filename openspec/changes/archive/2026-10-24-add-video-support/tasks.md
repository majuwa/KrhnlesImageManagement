## 1. Model and data

- [x] 1.1 Add `Photo.mediaKind` (set by the query), `Photo.isVideo`, `Photo.durationMs` and one helper for the composite video id (`VIDEO_ID_FLAG = 1L shl 62`; raw `_ID >= 2^62` rejected / skipped); unit tests (no collision, positive, image ids unchanged, raw id >= 2^62 skipped)
- [x] 1.2 Extend `MediaRepository` with the `MediaStore.Video` query (incl. `DURATION`), mime fallback `video/*` for videos, merge and sort both lists by raw `DATE_TAKEN` desc; a `SecurityException` on the video query yields only images. Robolectric tests with a fake `ContentProvider` registered for the `media` authority via `Robolectric.buildContentProvider` (precedent `fakes/FakeShareProvider.kt`) and a separate `MatrixCursor` per Images / Video URI: merge order, id collision, video row with null mime type is a video, unknown date, duration, video query throwing `SecurityException`

## 2. Permissions

- [x] 2.1 Add `READ_MEDIA_VIDEO` to the manifest; request images + video + `READ_MEDIA_VISUAL_USER_SELECTED` together; access = any granted; "video missing" = images granted and video not granted, asked at most once (persisted flag or per session), never auto-requested for partial-access users; banner "Select more photos and videos" for partial users; banner action opens app settings after a denial with `shouldShowRequestPermissionRationale == false`; existing denied screen otherwise; strings
- [x] 2.2 Compose tests: upgrade case (images only: banner, asked once, no videos), partial access (banner, no automatic request), denied without rationale (settings action), all denied

## 3. Grid UI

- [x] 3.1 Add `coil-video` to the version catalog and `app/build.gradle.kts`; rely on Coil 3 service-loader registration only (no manual `VideoFrameDecoder.Factory()`); document in `docs/architecture.md`
- [x] 3.2 `PhotoItem`: play overlay and duration badge (decorative), video description strings (position, duration, date / unknown date, no duration when unknown or zero), no file name (`NoFilenameContentDescriptionRegressionTest` stays green); duration formatting helper with tests
- [x] 3.3 Day header: count covers all media, new `item_count` plural ("N items"), action labels "Select / Deselect all items of this day"; update existing header tests; Robolectric UI tests (video tile semantics via `LocalAsyncImagePreviewHandler` + `LocalInspectionMode`, mixed-day header count)
- [x] 3.4 Neutral wording: change upload toast, upload notification title / success / partial / failure plurals, uploading channel description and selected-count strings from "photo" to "item"/"media"; these stay photo-specific: share-target texts, album / review / viewer texts, onboarding copy

## 4. Upload

- [x] 4.1 `WebDavClient.uploadFile`: use `executeCancellable(call)` (shared, not blocking `execute()`); `InputStreamRequestBody.isOneShot() = true` and `contentLength()` from a known size (`-1` = chunked); per-upload `client.newBuilder().readTimeout(5 min)`; no `callTimeout`; map 413 / 507 to typed results with new string resources. Tests: cancelling the coroutine cancels the call, `isOneShot`, Content-Length present when known and absent (chunked) when unknown, readTimeout override applies to the upload only (shared client unchanged, no callTimeout), 413 and 507 messages
- [x] 4.2 `UploadWorker`: read the size via `openAssetFileDescriptor(uri, "r").length` (close it; `-1` falls back to chunked); catch `setForeground` failures (initial and progress, e.g. `ForegroundServiceStartNotAllowedException`) and return `Result.retry()` keeping the queue file; document that `markAsUploaded` happens only at the end of a run and a re-run re-uploads (PUT overwrites, Nextcloud may create versions). `UploadWorkerRunTest` where `setForeground` throws: result is retry, queue file kept, nothing marked uploaded
- [x] 4.3 Tests: mixed photo + video batch queued once, video queue entry keeps composite id and mime, `writeQueueFile` -> `parseUploadQueue` -> `toRetryJson` round trip with id `(1L shl 62) + 42`, `markAsUploaded` with colliding raw ids, retry keeps the video mime, worker streams a large fake stream without buffering, auto date folders for videos including a video with unknown date
- [x] 4.4 Test that `AlbumsRepository` listing skips a video entry (reviews keep ignoring videos)

## 5. Docs and checks

- [x] 5.1 Update `README.md` (videos supported; uploaded videos are not shown in Albums; Play Console Photo and Video Permissions declaration needed for `READ_MEDIA_VIDEO`), `CLAUDE.md` (rule 2 permissions, id scheme, `mediaKind`, Coil video decoder, upload rules), `docs/architecture.md`, `docs/requirements.md` (G-002)
- [x] 5.2 Run `./gradlew test`, `spotlessCheck`, `detekt`, `lintDebug`, `jacocoTestReport`
- [ ] 5.3 Manual device checks: upgrade path (images-only grant, then update), 14+ partial access, scroll performance of the grid with many videos, upload of a video larger than 1 GB against Nextcloud (Wi-Fi only, Content-Length sent)
- [ ] 5.4 Non-code note (orchestrator): after the change is archived, create the GitHub follow-up issue "Nextcloud chunked upload v2 for large videos"; close #15 and #34

## 6. Review fixes

- [x] 6.1 S1: `uploadClient` derives from `noRedirectClient` (no redirects, 5 min read timeout, origin-restricted auth interceptor); every 3xx fails the upload; KDoc and interceptor comment corrected (application interceptor not re-run for redirects; same-host https -> http keeps `Authorization` in OkHttp). Test: PUT answered 301 / 302 / 303 / 307 / 308 fails with no follow-up request
- [x] 6.2 S2 + C2: per-entry progress (`"uploaded": true`, atomic `rewriteQueueFile()` temp + rename, incremental `markAsUploaded`), re-runs skip finished entries, whole-batch result / history / notification, refused update after the last item = completion, attempt cap `MAX_UPLOAD_RUN_ATTEMPTS` (5) with failure notification + retry file for the rest, explicit exponential back-off via the shared `uploadWorkRequest()` (enqueuer and retry receiver); backward compatible (absent marker = not uploaded), staged cleanup unchanged (leftover temp file removed). Tests: `UploadQueueProgressTest`, `UploadWorkerForegroundTest`, `UploadWorkerStagedTest`, `UploadConstraintsTest`
- [x] 6.3 S3 + C4: upload length computed inside `use` with `runCatching` -> `-1`; staged entries never fall back to the URI. Test: unexpected failure of the size lookup uploads chunked
- [x] 6.4 S4: `serviceLoaderEnabled(false)` + `LocalVideoFrameDecoderFactory` (only `ContentMetadata` `content://` sources). Tests: single registration, remote / disk-cached `video/mp4` refused, local content video decoded
- [x] 6.5 C1: every permission result that keeps access reloads the grid. Tests: upgrade user grants video -> video tiles appear; partial -> full reloads
- [x] 6.6 C3: settings action only for `videoDenial() == Permanent` (rationale false after an earlier rationale, persisted `video_rationale_shown`). Tests: `VideoDenialTest`, dismissal vs denial in `PhotoGridPermissionTest`, `MediaPermissionStoreTest`
- [x] 6.7 C5: notification texts combined through `notification_text_with_reason` ("%1$s %2$s"); `channel_name_uploads` = "Uploads"
- [x] 6.8 C6: `toast_upload_started` takes the `item_count` plural ("1 item" / "N items"). Test: singular toast
- [x] 6.9 C7: grid test with uploaded id `42 or VIDEO_ID_FLAG`, image 42 and video raw 42: only the video is marked and hidden under "New only"
- [x] 6.10 JaCoCo report fixed (class dirs of AGP built-in Kotlin, `isIncludeNoLocationClasses` for Robolectric; it reported no classes before)
- [x] 6.11 Docs (README, CLAUDE.md, `docs/architecture.md`) and checks (`test`, `spotlessCheck`, `detekt`, `lintDebug`, `jacocoTestReport`, `openspec validate --strict`)
