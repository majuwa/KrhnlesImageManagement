# Design

## Context

`AlbumsRepository.thumbnailUrl()` = `ensureMemoriesChecked()` + `buildUrl()` (string work). The check is one `GET <nextcloudBase>/index.php/apps/memories/api/describe`, implemented by `data/MemoriesAvailability.kt`, guarded by a `Mutex` acquired with a timeout. Previously a timeout cached `false` forever, and `downloadBitmapForHash` used `BitmapFactory.decodeByteArray` at full size. `BlurDetector` scales to `MAX_ANALYSIS_SIZE` = 512 and dHash scales to a tiny grid, so no analyzer needs more than 512 px.

## Goals / Non-Goals

**Goals:** lock in "at most one request per repository instance"; do not cache inconclusive checks permanently; bound decoded bitmap size.

**Non-Goals:** parallel scan downloads (deferred: no measurement, unresolved memory model and invariants; may be reconsidered later with a measurement); changing OkHttp config (no `callTimeout`); changing analyzers.

## Decisions

1. **Tests.** `MemoriesAvailabilityTest` tests the state machine with a fake `check`, a fake clock (`nanoTime`) and `runTest` virtual time. `ThumbnailUrlResolutionTest` keeps a few MockWebServer integration tests with `runBlocking` (real time, generous margins; request arrival is observed with latches / bounded polling, not sleeps). `memoriesCheckTimeoutMs` / `memoriesRetryBackoffMs` are internal constructor parameters like `readTimeoutMillis`.
2. **Definite answers only.** `checkMemories()` returns `AVAILABLE` (200), `UNAVAILABLE` (other HTTP status except 429 / 5xx) or `INCONCLUSIVE` (I/O failure, HTTP 429 / 5xx, timeout, any non-cancellation exception of the check). Non-Nextcloud URLs are decided `false` without a request. Cancellation is never cached (the next call re-checks).
3. **Retries never block.** Only the first attempt per instance is awaited by callers (`listPhotos` runs it concurrently with the PROPFIND, so it costs at most the check timeout once). An inconclusive result sets `retryAtNanos = nanoTime + backoff`; backoff is exponential: 30 s, doubled per consecutive failure, capped at 10 min. While in backoff `ensureChecked()` returns immediately (WebDAV fallback); after it, the next call starts one retry fire-and-forget in the repository's own scope (`SupervisorJob` + IO, bounded by the check timeout) and returns at once, so a hanging endpoint never adds `checkTimeoutMs` to a listing or to `thumbnailUrl()` mid-scan. Chosen over "definite false after N timeouts" so a recovering server is still picked up. Lock acquisition has its own timeout, separate from the check timeout, so a late lock holder still gets a full check; timing out on the lock schedules no backoff. Retries are children of a `retryJob`; `MemoriesAvailability.close()` cancels it (and so the OkHttp call via `executeCancellable`) and blocks new retries. `AlbumsRepositoryContract.close()` (default no-op) forwards to it, and `AlbumsRepositoryProvider` closes every repository it replaces or invalidates (configuration change, logout, `invalidate()`) and on `close()` from the owning ViewModel's `onCleared()` (`AlbumsViewModel`, `AlbumDetailViewModel`), so no retry sends a request with outdated credentials. Caveat: a thumbnail list resolved during an outage keeps its WebDAV URLs until reloaded; acceptable.
4. **Downsampled decode.** Bounds pass, reject `outWidth * outHeight > 100 MP` (`ThumbnailTooLargeException`), then `inSampleSize` = largest power of two keeping the longest side >= 512 (result 512..1023 px). `BitmapFactory` rather than `ImageDecoder`: works on the downloaded byte array, is covered by Robolectric, and `ImageDecoder` would add a `Source` indirection for no gain. EXIF orientation behaviour is unchanged. Undecodable data still fails with the existing decode error.

## Risks / Trade-offs

- A permanent 4xx other than 429 (e.g. 404 when Memories is not installed) is cached as unavailable; 429 / 5xx are retried with backoff.
- Behaviour change on the WebDAV-fallback path: originals are subsampled before `BlurDetector`, so blur scores change versus full-size decoding. Intended (analyzers scale to 512 px anyway); covered by a test that a sharp textured image stays above the default threshold.
- Power-of-two sampling may leave up to 1023 px; analyzers downscale further.
