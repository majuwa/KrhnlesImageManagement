# Tasks

## 1. Streaming PROPFIND parser

- [x] 1.1 Add `@RunWith(RobolectricTestRunner::class)` to `WebDavXmlParserTest` so parser tests use the production `android.util.Xml.newPullParser()` (no `kxml2` test dependency); verified by the existing parser tests passing on it.
- [x] 1.2 Add `PropfindEntryReader` in `data/WebDavXmlParser.kt`: `android.util.Xml.newPullParser()`, namespace-aware, `FEATURE_PROCESS_DOCDECL` false; prolog read with `nextToken()` up to the root `START_TAG`, `DOCDECL` / `ENTITY_REF` -> `MalformedMultistatusException`; root must be `{DAV:}multistatus`; then `next()` only; same field extraction and `oc:fileid` validation as today; responses without `d:href` skipped; parser errors wrapped as `MalformedMultistatusException`.
- [x] 1.3 Per-entry bounds (`MAX_ELEMENTS_PER_ENTRY = 256`, `MAX_ENTRY_DEPTH = 16`, `MAX_TEXT_LENGTH = 8 KiB` per captured value): exceeding one skips the rest of the entry and yields `PropfindItem.Skipped`; verified by tests for each bound and that the following entries are still read.
- [x] 1.4 Re-implement `parsePropfindXml(String)` on top of the reader and delete the regex/DOM code; replace the "benign DOCTYPE accepted" test by a rejection test.
- [x] 1.5 Regression tests (Robolectric parser): DOCTYPE with internal entity, billion-laughs payload, external `SYSTEM` entity and benign DOCTYPE are rejected and nothing is expanded; undeclared entity reference, truncated XML, mismatched tags and non-multistatus root fail with `MalformedMultistatusException`; a generated 10,000-entry stream parses within a reasonable time bound.

## 2. Bounded, cancellable repository listing

- [x] 2.1 Add `PhotoListing(photos, isTruncated)` and change `AlbumsRepositoryContract.listPhotos(albumHref, onProgress: (Int) -> Unit = {})` to return `Result<PhotoListing>`; implement in `AlbumsRepository` (request as today, `Depth: 1`, no `callTimeout`; `requireSafeHref` before any request; drop the collection entry; `isImage` + per-entry `resolveWebDavHref` filtering; duplicate hrefs dropped via `HashSet`; skipped/unsafe counts logged once, no hrefs); `onProgress` throttled (every 100 photos + final).
- [x] 2.2 `LimitedInputStream` over the decompressed `byteStream()` with `MAX_PROPFIND_RESPONSE_BYTES = 32 MiB` throwing `PropfindResponseTooLargeException` at the cap; `MAX_PHOTOS_PER_ALBUM = 20_000`; `listPhotos` returns truncated on either cap (Malformed if no complete entry); verified by `LimitedInputStream` unit tests and `MockWebServer` tests (entry cap via a lowered internal limit, byte cap via a lowered internal limit / oversized body).
- [x] 2.3 `listAlbums()` uses the streaming reader with the byte cap only; exceeding it fails with `PropfindResponseTooLargeException` mapped to `AlbumsError.ListingTooLarge` (+ string); verified by tests.
- [x] 2.4 Cancel the OkHttp `Call` while the read is blocked (sibling `launch { try { awaitCancellation() } finally { call.cancel() } }` inside the IO scope); a cancelled read rethrows `CancellationException`; verified by a `MockWebServer` test stalled mid-body with a hard time limit of a few seconds.
- [x] 2.5 Start `ensureMemoriesChecked()` concurrently with the PROPFIND in `listPhotos`; verified by a test that the listing returns while the Memories check is still pending (or that the describe request is issued alongside).
- [x] 2.6 Existing `AlbumsRepositoryTest` cases stay green (images only, extension fallback, unsafe hrefs skipped, album outside base folder refused without a request, HTTP errors -> `WebDavHttpException(code)`, same-origin `Authorization`); add duplicate-href and oversized-entry tests.
- [x] 2.7 Update every fake/stub implementing `AlbumsRepositoryContract` in `app/src/test`; verified by `./gradlew compileDebugUnitTestKotlin`.

## 3. ViewModel

- [x] 3.1 `AlbumDetailState` gains `loadedCount` and `isTruncated`; `startLoad` passes an `onProgress` that updates `loadedCount`, resolves thumbnail URLs, calls `ensureActive()` and publishes photos + URLs + `isTruncated` + `isLoaded` in one update; verified by `AlbumDetailViewModelTest` (progress visible before completion, single final publish, truncated flag).
- [x] 3.2 `reload()` is ignored while a load is running without error; verified by a test that a reload during loading issues no second listing.
- [x] 3.3 Cancellation: configuration change / logout during loading publishes no photos and shows `NotConfigured` / `ConfigurationChanged`; verified by a test with a suspended fake listing.
- [x] 3.4 Existing loading / deletion / recreation tests stay green (`loadAlbum` no-op, deletion semantics unchanged, `AlbumPhotoDeletionTest`).

## 4. UI

- [x] 4.1 Strings in `values/strings.xml` (only locale present): `album_loading_photos` plural, `album_truncated_notice`, `review_truncated_notice`, `albums_error_listing_too_large`; no hardcoded strings.
- [x] 4.2 `AlbumDetailScreen`: spinner with "Loading photos… N" (polite live region) while loading with `loadedCount > 0`; truncation banner above the grid when `isTruncated`; verified by Compose/Robolectric UI tests.
- [x] 4.3 Duplicate and blur review show `review_truncated_notice` when the album is truncated; verified by a UI test.

## 5. Documentation

- [x] 5.1 `docs/architecture.md`: streaming listing, limits, truncation, call cancellation, rejected SEARCH / chunk publishing alternatives, trickling-server accepted risk, new XXE mechanism.
- [x] 5.2 `CLAUDE.md`: replace the XXE bullet; add the listing rule (stream parser + caps, never DOM-parse a PROPFIND); add a source-scanning guard test (`krhnles.moduleDir`) asserting no `DocumentBuilderFactory` / DOM parse in the production PROPFIND path (`WebDavXmlParser.kt`, `AlbumsRepository.kt`).
- [x] 5.3 `README.md`: loading count for large albums and the truncation notice.

## 6. Final checks (CLAUDE.md "After Every Code Change")

- [x] 6.1 `./gradlew test` passes.
- [x] 6.2 `./gradlew spotlessCheck` passes.
- [x] 6.3 `./gradlew detekt` shows no new issues (no baseline change or suppression).
- [x] 6.4 `./gradlew lintDebug` passes with zero issues and no baseline or suppression.
- [x] 6.5 `./gradlew jacocoTestReport` generated at `app/build/reports/jacoco/jacocoTestReport/html/`.
- [ ] 6.6 Manual check on a device/emulator against a Nextcloud with a very large album: count shown while loading, no OOM, Back/rotation/logout during loading behave as specified.

## 7. Review findings (code + security review)

- [x] 7.1 C1 / S3: create the KXml parser lazily inside `next()`'s try; unwrap `XmlPullParserException` with an `IOException` `detail` / cause (rethrow it), else `MalformedMultistatusException` (also unsupported encoding); `executeCancellable` calls `ensureActive()` for every non-cancellation exception; `startLoad` calls `ensureActive()` and checks the load generation before publishing an error; verified by MockWebServer tests (stall before the first body byte + cancel → no result; stall before / mid body → `Timeout`), reader tests (stream failures rethrown unchanged, bogus encoding → malformed) and ViewModel tests (failure after logout keeps `NotConfigured`; failure of a replaced load is ignored).
- [x] 7.2 C2: byte cap without a complete entry besides the collection → `MalformedMultistatusException`; truncated empty listing shows `album_truncated_empty_notice`; `LimitedInputStream.available()` capped; verified by repository and UI tests.
- [x] 7.3 C3: thumbnail URLs resolved on an injectable `backgroundDispatcher`; `listAlbums()` post-processing on IO.
- [x] 7.4 C4: Memories test proves overlap (dispatcher holds both responses until both requests arrived).
- [x] 7.5 C5: logout test split; the late-progress test reloads after re-login, so only the generation guard rejects the stale callback.
- [x] 7.6 C6: blur review truncation notice UI tests (shown / not shown).
- [x] 7.7 S1 / S2: document bounds (depth 32, 64 attributes, 64 namespace declarations per start tag) and `XmlTokenSizeLimitingInputStream` (64 KiB per tag / text node, CDATA / comments / PIs do not reset); time-bounded regression tests for nesting, attributes, namespace declarations, huge text / CDATA / comment-split text; maximal multi-byte href still accepted.
- [x] 7.8 S4: CLAUDE.md / architecture note: never log parser exception messages.
- [x] 7.9 S5: `downloadBitmapForHash` uses `executeCancellable` and `LimitedInputStream` (`MAX_THUMBNAIL_BYTES`, `Content-Length` pre-check, `ThumbnailTooLargeException`); verified by size-cap (fixed length and chunked) and cancellation tests.
- [x] 7.10 S-tests: DOCTYPE after the root start tag and inside `d:response` rejected.
