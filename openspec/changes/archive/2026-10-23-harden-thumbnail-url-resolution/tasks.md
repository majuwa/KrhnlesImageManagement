# Tasks

## 1. Production changes

- [x] 1.1 `memoriesCheckTimeoutMs` / `memoriesRetryBackoffMs` internal constructor parameters in `AlbumsRepository`
- [x] 1.2 Cache only definite Memories answers; inconclusive check retried with backoff (mutex + timeout kept)
- [x] 1.3 `downloadBitmapForHash` decodes with bounds + `inSampleSize` (`thumbnailSampleSize`, `decodeDownsampled`)

## 2. Tests

- [x] 2.1 `ThumbnailUrlResolutionTest`: sequential and concurrent calls -> one Memories request; non-Nextcloud -> zero; definite 404 cached
- [x] 2.2 Timeout and I/O failure not cached, retried after backoff, no storm within backoff
- [x] 2.3 Large image decoded within 512..1023 px, small image untouched, sample-size unit test
- [x] 2.4 Remove stale runTest comment in `AlbumsRepositoryTest`

## 2b. Review follow-ups

- [x] Retries fire-and-forget with exponential backoff (30 s .. 10 min); extract `MemoriesAvailability` (injectable clock / scope), separate lock and check timeouts, non-cancellation failures inconclusive
- [x] HTTP 429 / 5xx inconclusive; ASCII-only preview id; `buildUrl` file id guard; `require(target > 0)`; 100 MP decode guard
- [x] `MemoriesAvailabilityTest` (fake clock), cancellation / storm / concurrency / blur-threshold / Arabic-Indic tests; flaky timing tests replaced
- [x] Retry scope owned: `close()` on `MemoriesAvailability` / `AlbumsRepositoryContract` (default no-op), called by `AlbumsRepositoryProvider` on replace / invalidate / `close()` and from `onCleared()` of both album ViewModels; tests for provider closing and closed `MemoriesAvailability`

## 3. Documentation

- [x] 3.1 `docs/architecture.md` (Memories check semantics, downsampled decode, parallel scan deferred)
- [x] 3.2 `CLAUDE.md` (one sentence)
- [x] 3.3 `README.md` unchanged (blur score shift on the fallback path is internal; documented in proposal / architecture)

## 4. Checks

- [x] 4.1 `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport` exit 0
- [x] 4.2 `openspec validate harden-thumbnail-url-resolution --strict`
