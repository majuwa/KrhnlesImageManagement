# Proposal

## Why

Issues #25 / #44 report that thumbnail URLs are resolved one photo after the other (O(N) round-trips). Checked against the current code: the premise no longer holds. `AlbumDetailViewModel.startLoad()` maps the listing with `repository.thumbnailUrl(photo)`, which calls `ensureMemoriesChecked()` (at most one `GET .../memories/api/describe` per repository instance, mutex + timeout, none on non-Nextcloud servers) and then pure string construction. Nothing locks this in with tests, and the review identified two real performance weaknesses nearby:

- A Memories check that merely timed out (or failed with an I/O error) is cached as a permanent `false`, so one slow moment degrades every thumbnail of that repository instance to full-size originals for good.
- `downloadBitmapForHash` decodes the downloaded bytes at full size; on the original-file fallback a 24 MP photo becomes a ~96 MB bitmap although the analyzers (dHash, blur) work on at most 512 px.

## What Changes

- Regression tests locking in URL resolution: N `thumbnailUrl()` calls (sequential and concurrent) send at most one Memories request per repository instance; a non-Nextcloud URL sends none.
- Memories check caches only a definite answer (HTTP 200 -> available, other non-transient statuses -> unavailable). A timeout, I/O failure, HTTP 429 or 5xx is inconclusive: that call uses the WebDAV fallback and a later call retries with exponential backoff (30 s, doubling, capped at 10 min). Only the first attempt per instance is awaited by callers; retries run fire-and-forget in the repository's own scope (bounded by the check timeout), so a hanging endpoint never delays a listing or URL resolution again. Cancellation is never cached. Extracted into `data/MemoriesAvailability.kt` (injectable clock and scope).
- `downloadBitmapForHash` decodes with `inJustDecodeBounds` + a power-of-two `inSampleSize` so the longest side stays at or above 512 px (the analysis size); images announcing more than 100 MP are rejected after the bounds pass with `ThumbnailTooLargeException` (decompression-bomb guard; the scan skips the photo). Byte cap, URL restrictions, cancellability and typed errors unchanged.
- Hardening: the Memories preview id check accepts ASCII alphanumerics only, and `buildUrl` falls back to the WebDAV URL for a file id that is not `[A-Za-z0-9]+` (defense in depth next to the PROPFIND parse).
- Internal constructor parameters `memoriesCheckTimeoutMs` and `memoriesRetryBackoffMs` (tests use real time via `runBlocking`); the stale "tests skipped under runTest" comment in `AlbumsRepositoryTest` is removed.
- Dropped from the original proposal: parallel / sliding-window scan downloads (unmeasured, memory model and invariants unresolved). Can be reconsidered later with a measurement.

## Capabilities

### Modified Capabilities
- `album-photo-listing`: thumbnail address resolution costs at most one Memories check per repository instance; an inconclusive check is retried with backoff; scan thumbnails are decoded downsampled.

## Impact

- Code: `data/AlbumsRepository.kt`, new `data/MemoriesAvailability.kt`, `data/WebDavXmlParser.kt` (`FILE_ID_REGEX` internal).
- Tests: new `MemoriesAvailabilityTest` (fake check + fake clock), `ThumbnailUrlResolutionTest` (MockWebServer integration); comment cleanup in `AlbumsRepositoryTest`.
- Docs: `docs/architecture.md`, `CLAUDE.md`.
- Behaviour change (intended): on the WebDAV-fallback path (no Memories) originals are now subsampled to 512..1023 px before `BlurDetector`, so Laplacian-variance scores differ from those of full-size decoding (sharp images stay above the default threshold, covered by a test). Besides that, lower memory use and better thumbnails after a transient timeout.
- Closes #25 and #44 (premise already resolved, now locked in).
