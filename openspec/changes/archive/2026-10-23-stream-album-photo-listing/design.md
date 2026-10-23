# Design

## Context

Current flow (observed): `AlbumDetailViewModel.startLoad()` -> `AlbumsRepository.listPhotos(href)` -> private `propfind(url)` which does `response.body.string()` and `parsePropfindXml(String)` (regex DOCTYPE strip, `DocumentBuilderFactory` DOM, `getElementsByTagNameNS`) -> `drop(1)`, `isImage`, `withSafeHrefsBelow(albumUrl)` -> full `List<RemotePhoto>`; then one `thumbnailUrl()` per photo (the first call blocks on the Memories check); state is published twice (photos, then thumbnail URLs; `isLoaded` last). The screen shows a full-screen spinner while `isLoading`. `listAlbums()` uses the same `propfind`. Review scans (`scanAlbumPhotos`) and deletion operate on `detailState.photos` / the list passed to them. The OkHttp client has connect 30 s / read 60 s and deliberately no `callTimeout`.

What the servers support: plain `PROPFIND Depth: 1` has no pagination (sabre/dav, Nextcloud, ownCloud, generic WebDAV); `Range` is not defined for PROPFIND; `oc:chunk` is the upload-chunking namespace. Nextcloud additionally offers `SEARCH` (RFC 5323 / DASL `d:basicsearch`) with `d:limit`, but it is Nextcloud-specific, needs a stable `orderby`, a different request/response shape and scope handling, and is absent on plain WebDAV servers.

## Goals / Non-Goals

**Goals:**
- Peak memory proportional to the retained photos (not 5-10x the XML size); no OOM / ANR on 10k+ entry listings.
- Visible progress while loading; keep every existing security, deletion and cancellation guarantee.
- Works on every WebDAV server with no server-side feature detection.

**Non-Goals:**
- Progressive (chunked) publishing of photos to the grid (rejected after review, see Decision 6).
- Server-side paging (SEARCH), infinite scrolling, sorting or offline caching of listings.
- Changing thumbnail loading (Coil is already lazy via `LazyVerticalGrid`).

## Decisions

1. **Stream-parse the body, keep one PROPFIND.** Replace `body.string()` + DOM by `android.util.Xml.newPullParser()` (namespace-aware) reading the response `byteStream()`. A pull API `PropfindEntryReader(InputStream).next(): PropfindItem?` yields per `d:response` either `Entry(WebDavEntry)` (same fields as today: first `d:href`, `d:displayname` with href fallback, any `d:collection`, `d:getcontenttype`, `oc:fileid` validated with `[A-Za-z0-9]+`; responses without `d:href` are skipped as today) or `Skipped` (oversized entry). `parsePropfindXml(String)` stays as a thin wrapper (used by tests). Only `android.util.Xml` is used, so tests run on the same KXmlParser under `RobolectricTestRunner`; no `kxml2` test dependency.
   Alternatives: (a) SEARCH paging - rejected for now: Nextcloud-only, still needs the PROPFIND fallback, ordering/offset semantics vary by version, server still enumerates; documented follow-up. (b) Keep DOM and only cap size - rejected: still 5-10x amplification. (c) SAX - equivalent but push-based, awkward for cancellation.
2. **XXE defense for a stream.** A regex cannot sanitize a stream. The reader sets `FEATURE_PROCESS_DOCDECL` to false, then reads the prolog with `nextToken()` up to the root `START_TAG`, failing with `MalformedMultistatusException` on `DOCDECL` (DOCTYPE, also benign ones) or `ENTITY_REF`; the root must be `{DAV:}multistatus`. After that only `next()` is used: KXml rejects a DOCTYPE after the root start tag ("Unexpected token"), so no entity can be declared, and KXml rejects undeclared entity references (only the five built-ins and character references are allowed). Tests (internal entity, billion laughs, external `SYSTEM` entity, benign DOCTYPE, DOCTYPE after the root start tag and inside `d:response`, wrong root) run on that parser under Robolectric.
   **Failure classification (review C1 / S3).** KXml already reads in `setInput()` and wraps any exception there as `XmlPullParserException` (original in `detail`, `cause` null). The parser is therefore created lazily inside `next()`'s try, and an `XmlPullParserException` whose `detail` / cause is a stream `IOException` (socket timeout, `Call.cancel()`'s "Canceled", size / token cap) is rethrown unchanged; anything else — including `UnsupportedEncodingException` for a bogus declared encoding — becomes `MalformedMultistatusException`. Exception messages contain server content and are never logged (type only). CLAUDE.md's XXE bullet is rewritten.
3. **Memory bounds.**
   - Byte cap: `MAX_PROPFIND_RESPONSE_BYTES = 32 MiB`, enforced by `LimitedInputStream` around the decompressed `response.body.byteStream()`; the first read beyond the cap throws `PropfindResponseTooLargeException` (an `IOException`) immediately. The reader then has lost its position, so the partial entry is dropped: `listPhotos` returns the complete entries read so far with `isTruncated = true`, or fails with `MalformedMultistatusException` if no complete entry besides the album collection was read (review C2; a truncated listing whose complete entries contain no photo shows a dedicated notice instead of "No photos"); `LimitedInputStream.available()` never reports bytes beyond the cap, so the decoder's read-ahead does not swallow complete entries; `listAlbums` fails with `PropfindResponseTooLargeException` -> `AlbumsError.ListingTooLarge` (never silently truncated, a missing album would look deleted).
   - Entry cap: `MAX_PHOTOS_PER_ALBUM = 20_000` (single named constant; the byte cap is the primary bound). On reaching it, reading stops at an entry boundary and the result is truncated.
   - Per-entry bounds: `MAX_ELEMENTS_PER_ENTRY = 256` elements and `MAX_ENTRY_DEPTH = 16` nesting levels inside one `d:response`, `MAX_TEXT_LENGTH = 8 KiB` per captured text value. Exceeding any skips the rest of that entry (consumed without retaining text) and yields `Skipped`; the repository counts skipped entries and logs only the count, never the href (#16).
   - Document bounds (security review S1 / S2): KXml grows `elementStack`, `attributes` and `nspStack` by a constant step, so within 32 MiB deep nesting or one tag with 200k attributes / namespace declarations costs minutes to hours, and a single 32 MiB text node costs ~260 MB of heap (an `OutOfMemoryError`, not caught). Skipping does not help (the parser still processes it), so these fail the whole response with `MalformedMultistatusException`: on every `START_TAG` (prolog, responses, skipped elements) an absolute depth > 32, > 64 attributes or > 64 namespace declarations; and `XmlTokenSizeLimitingInputStream` in front of the parser fails once more than 64 KiB arrive between two markup starts (`<` outside CDATA, comments and PIs, because `next()` coalesces text across them). That caps one tag (attributes / namespace declarations) and one text node before KXml buffers it; a maximal captured value (8 KiB characters of multi-byte UTF-8) still fits. Time-bounded regression tests cover each case.
   - Duplicate hrefs within one listing are dropped (first wins) with a `HashSet` (#10).
   - Server order is kept, so "first N" means "first as the server lists them".
4. **Contract.** `AlbumsRepositoryContract.listPhotos(albumHref: String, onProgress: (Int) -> Unit = {}): Result<PhotoListing>` with `data class PhotoListing(val photos: List<RemotePhoto>, val isTruncated: Boolean)`. `onProgress` receives the number of photos accepted so far, throttled to at most one call per `PROGRESS_STEP = 100` photos (plus one final call); it is called on the IO dispatcher, so callers must only write thread-safe state (a `StateFlow`). Href validation is unchanged: `requireSafeHref(albumHref)` before any request, per-entry `resolveWebDavHref` filtering, the first entry (the collection itself) dropped, unsafe count logged once.
5. **Cancellation of blocking IO.** Coroutine cancellation does not interrupt a blocking socket read and `Job.invokeOnCompletion` does not fire while the coroutine body is still blocked. The PROPFIND read therefore runs as:
   ```kotlin
   withContext(Dispatchers.IO) {          // multi-threaded: the sibling runs while the read blocks
       val call = client.newCall(request)
       val canceller = launch { try { awaitCancellation() } finally { call.cancel() } }
       try { call.execute().use { response -> readBody(response) } } finally { canceller.cancel() }
   }
   ```
   (`executeCancellable(call) { response -> ... }` in `AlbumsRepository`). The sibling `canceller` runs on another IO thread and calls `call.cancel()` as soon as the scope is cancelled; the read then throws (an `IOException`, or a parser exception wrapping it), and *every* non-cancellation exception of a cancelled scope is rethrown as the scope's `CancellationException` (checked with `ensureActive()`), never reported as an error. `downloadBitmapForHash` uses the same path and reads its body through `LimitedInputStream` (`MAX_THUMBNAIL_BYTES` = 32 MiB, also checked against `Content-Length`, → `ThumbnailTooLargeException`; review S5). The response is closed in `use {}`. The shared client stays without `callTimeout`; the 60 s per-read timeout still maps to `AlbumsError.Timeout`. Tested with `MockWebServer` stalling before the first body byte (`setBodyDelay`) and mid-body (`throttleBody`) with a hard limit of a few seconds (well below the read timeout), and with a lowered read timeout (internal constructor parameter) mapping both stalls to `AlbumsError.Timeout`.
6. **ViewModel: publish once, show a count.** `startLoad` calls `listPhotos(href) { n -> _detailState.update { it.copy(loadedCount = n) } }`, resolves thumbnail URLs on an injectable `backgroundDispatcher` (`Dispatchers.Default`; 20,000 resolutions must not run on Main — review C3; `listAlbums()` post-processes on IO), calls `ensureActive()`, checks the load generation and then publishes photos, URLs, `isTruncated` and `isLoaded = true` in one atomic update. The error path applies the same two checks, so the failure of a cancelled read never overwrites `NotConfigured` / `ConfigurationChanged` or a newer load (review C1). `isLoading` stays true until then. Progressive chunk publishing was rejected (review #6/#7/#9/#12/#13/#18): it needed a deletion gate, `isLoadingMore`, a Flow contract, chunk-size tuning and growing-list accessibility handling, and changed the meaning of `isLoaded` for reviews, for little benefit once memory is fixed. Deletion and review semantics are therefore unchanged (they only ever see a complete or truncated-complete list). `reload()` is ignored while a load is running without error, so Retry from a review whose album is (re)loading never starts a second listing. `loadAlbum`'s no-op rule and `onConfigurationChanged` (cancels the load, which now cancels the call) are unchanged.
7. **Memories check off the critical path.** `listPhotos` starts `ensureMemoriesChecked()` in a sibling coroutine concurrently with the PROPFIND, so the first `thumbnailUrl()` call afterwards is instant in the usual case.
8. **UI.** While `isLoading`, the full-screen spinner shows `pluralStringResource(R.plurals.album_loading_photos, n, n)` ("Loading photos… N") once `loadedCount > 0`, as a polite live region. When `isTruncated`, a non-error banner `album_truncated_notice` is shown above the grid, and the duplicate / blur review show `review_truncated_notice` above their results (results may be incomplete). Only `values/strings.xml` exists (no translations).
9. **Trickling server (#14) - accepted risk.** A server that sends one byte just under every read timeout can keep a listing open indefinitely. No `callTimeout` or overall timeout is added (it would break large uploads on the shared client and penalize slow links); the byte cap bounds the data, and leaving the screen cancels the call.

## Risks / Trade-offs

- [Server still computes and sends the whole listing] -> accepted; SEARCH paging documented as a follow-up.
- [Benign DOCTYPE now rejected] -> no known WebDAV server (sabre/dav, Nextcloud, ownCloud, Apache mod_dav) sends one; rejecting is the simplest safe rule for a stream.
- [Truncation hides photos beyond the limits from deletion/review] -> visible notices on the album and both reviews; limits are named constants.
- [Hostile XML structure (nesting, attributes, namespaces, huge text)] -> document bounds fail the response fast; a legitimate server stays far below them.
- [Trickling server] -> accepted, see Decision 9.

## Migration Plan

No data migration. No flag; `parsePropfindXml(String)` remains as a wrapper. Rollback is a plain revert.

## Open Questions

- Whether to tune `MAX_PHOTOS_PER_ALBUM` after measuring a real large album on a low-memory device.
