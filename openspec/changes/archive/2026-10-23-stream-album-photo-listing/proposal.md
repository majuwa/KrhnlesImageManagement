# Proposal

## Why

Opening an album issues one `Depth: 1` PROPFIND and `AlbumsRepository.propfind()` buffers the whole response with `body.string()`, then `parsePropfindXml()` builds a full DOM from a second copy of it, and the screen shows only a spinner until every `RemotePhoto` and thumbnail URL exists. For albums with thousands of photos this peaks at many times the response size (string + UTF-8 bytes + DOM) and can cause OOM or ANR, and the user gets no feedback. See GitHub issues #27 / #46.

WebDAV has no standard pagination for PROPFIND (`Depth: 1` returns the whole collection; `Range` does not apply to PROPFIND and `oc:chunk` is an upload mechanism), so the fix has to bound memory on the client rather than rely on server-side paging.

## What Changes

- Parse the PROPFIND multistatus as a stream (`android.util.Xml.newPullParser()` over the response body) instead of `body.string()` + DOM; memory is bounded by the kept entries, not the XML size. DOCTYPE (and any entity reference outside the built-ins) is rejected instead of regex-stripped (a stream cannot be pre-sanitized); the root must be `{DAV:}multistatus`.
- `AlbumsRepositoryContract.listPhotos(albumHref, onProgress)` returns `Result<PhotoListing(photos, isTruncated)>` and reports a throttled running count of photos found while reading. The result is still published to the UI once, at the end.
- The album screen shows "Loading photos… N" (plural string) while the listing is read. `isLoaded`, deletion and review semantics are unchanged (everything still works on a complete listing only).
- Hard, documented bounds: 32 MiB of (decompressed) response data via a counting stream, at most `MAX_PHOTOS_PER_ALBUM = 20_000` photos, per-entry element/depth limits and a per-text-node limit. Document bounds against quadratic parse cost / huge buffers (absolute depth 32, 64 attributes and 64 namespace declarations per element, 64 KiB per tag or text node on the byte stream) fail the whole response as malformed. Beyond the byte or photo cap, reading stops, the complete entries already parsed are kept and the album is flagged truncated with a notice on the album screen and on the duplicate / blur review (their results may be incomplete). A single oversized entry is skipped (counted, never logged) instead of failing the album. Duplicate hrefs inside one listing are dropped.
- `listAlbums()` uses the same streaming parser with the byte cap only; exceeding it fails with a typed `AlbumsError.ListingTooLarge` (never silently truncated).
- Cancellation (configuration change, new load, ViewModel cleared, leaving the screen) cancels the OkHttp `Call` while the read is blocked, so the read stops promptly — also before the first body byte (the parser is created lazily and wrapped stream failures are unwrapped); a failure of a cancelled or replaced load is never published. A stall maps to the timeout error.
- Thumbnail downloads for the reviews (`downloadBitmapForHash`) are capped (`MAX_THUMBNAIL_BYTES` = 32 MiB, typed `ThumbnailTooLargeException`) and cancellable (pre-existing gap fixed alongside).
- Thumbnail URL resolution of a loaded listing runs off the main thread.
- The Nextcloud Memories availability check starts concurrently with the PROPFIND so it no longer delays the photos.
- Nextcloud SEARCH (DASL) paging and progressive chunk publishing are evaluated and deliberately not implemented now (see design).

## Capabilities

### New Capabilities
- `album-photo-listing`: how an album's photos are listed for large albums: loading progress, bounded memory / size limits with truncation notice (album and reviews), unchanged deletion / review availability, cancellation, malformed and oversized response behavior.

### Modified Capabilities
<!-- None: existing album-error-handling / album-photo-deletion / webdav-href-validation requirements are unchanged; their guarantees are re-tested against the streaming path. -->

## Impact

- Code: `data/WebDavXmlParser.kt` (streaming reader, limits, `XmlTokenSizeLimitingInputStream`), `data/AlbumsRepository.kt` (`propfind`, `listPhotos`, `listAlbums`, cancellable call), `data/Repositories.kt` (contract + `PhotoListing`), `ui/albums/AlbumDetailViewModel.kt` (`startLoad`, `AlbumDetailState.loadedCount` / `isTruncated`, `reload` no-op while loading), `ui/albums/AlbumsError.kt` (`ListingTooLarge`), `ui/albums/AlbumDetailScreen.kt`, `DuplicateReviewScreen.kt`, `BlurReviewScreen.kt`, `strings.xml`, test fakes implementing `AlbumsRepositoryContract`.
- Security: XXE defense changes mechanism (stream parser, DOCDECL / ENTITY_REF rejected in the prolog, `FEATURE_PROCESS_DOCDECL` false, root check) and CLAUDE.md / `docs/architecture.md` must say so. A server that sends a benign DOCTYPE is now rejected (deliberate). Href validation, origin-restricted auth and the no-`callTimeout` rule are unchanged.
- Dependencies: none new. Parser tests run under `RobolectricTestRunner` so they exercise the same `android.util.Xml` parser as production (no `kxml2` test dependency).
- User-visible: large albums no longer crash and show a running count while loading; albums above the limits show a notice (also in the reviews); README updated.
