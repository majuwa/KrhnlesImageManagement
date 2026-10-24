## 1. Data layer

- [x] 1.1 `WebDavClient.renameDirectory`: make the MOVE cancellable (`Call.awaitStatus()` or `executeCancellable`, no `call.timeout()`/`callTimeout`); accept only 201 / 204, otherwise throw `WebDavHttpException(code)`; keep HttpUrl-built `Destination` and `Overwrite: F`; send the source collection URL with trailing slash (or disable redirects for this request)
- [x] 1.2 `AlbumsRepository.renameAlbum` (~line 161): trim `newName` (or reject untrimmed), re-validate with `isValidOccasionName` (-> `AlbumsError.InvalidName`, no request), keep `requireSafeHref` source and same-parent destination
- [x] 1.3 Tests (`WebDavClientTest`, `AlbumsRepositoryTest`): 201 / 204 success; 301 / 302 / 200 -> failure; 412 -> `WebDavHttpException(412)`; 404; unsafe href sends nothing; `/` `\` `..` rejected without request; untrimmed name; `Overwrite: F`; exactly one request (no MKCOL/DELETE); a never-answering MockWebServer response is cancelled and releases the guard within the timeout

## 2. Error mapping

- [x] 2.1 Add `AlbumsError.AlreadyExists` and `AlbumsError.InvalidName`; extend `toAlbumsError()` / `albumsErrorMessage()`; MOVE mapping: 412 exists, 404 / 409 album no longer exists, 400 / 403 server rejected name / no permission, 423 generic
- [x] 2.2 Add strings (exists, invalid characters, no longer exists, rejected / no permission, timeout "may still have completed") incl. other locale files if present
- [x] 2.3 Unit tests for the mapping (parameterized by code)

## 3. ViewModel

- [x] 3.1 `AlbumsViewModel.renameAlbum`: `renameJob` re-entry guard, `withTimeoutOrNull(30 s)` (null -> timeout result), `runCatching` inside rethrowing `CancellationException`, `ensureActive()`, `finally` resets busy flag
- [x] 3.2 One-shot `renameOutcome` + `consumeRenameOutcome()` replacing the `onComplete` callback; in-place list replacement at same index; automatic `loadAlbums()` after a timeout
- [x] 3.3 Ignore top-bar Refresh (`loadAlbums`/reload from the UI) while a rename runs
- [x] 3.4 Tests: success updates list without a second `listAlbums` call (call counter in `FakeAlbumsRepository`, compare before / after); 412 keeps list; double call sends one MOVE; timeout releases guard, reports timeout and triggers reload; refresh ignored while renaming; blank name rejected without request

## 4. UI

- [x] 4.1 `RenameAlbumDialog`: use `isValidOccasionName`, add invalid-characters message; unchanged check against `displayName` and decoded last href segment, exact case
- [x] 4.2 `AlbumsScreen`: show `renameOutcome` via snackbar with typed message, consume it once
- [x] 4.3 Update existing callback-based tests (`AlbumsViewModelTest.kt:190-204`, `AlbumsScreenTest.kt:153-196`), never disable; cover pre-filled, blank, unchanged (incl. percent-encoded href segment), invalid chars, 412 message, supporting text, and one test asserting the spinner replaces the grid while renaming

## 5. Docs and verification

- [x] 5.1 Update `README.md` (rename rules + known limitation: pending uploads / retry queues keep the old folder name), `docs/architecture.md`, `CLAUDE.md` (rename guard conventions; reword rule 7 "Only `testConnection()` has an overall call timeout" to include the ViewModel-level rename timeout with a cancellable call, still no `callTimeout`)
- [x] 5.2 `./gradlew test spotlessCheck detekt lintDebug jacocoTestReport` all pass

## 6. Review fixes

- [x] 6.1 S1: `renameDirectory(oldSegments, newSegments)` takes segment lists, rejects blank segments; repository requires the source exactly one level below the base folder, no empty / blank segments; tests incl. `<base>/%20/A/`
- [x] 6.2 S2: `validatePathSegment` rejects `\` and ISO control characters; tests
- [x] 6.3 S3: other 2xx -> `AlbumsError.RenamePartial` + string + reload; tests (client, repository, mapping, ViewModel)
- [x] 6.4 S4: 255 UTF-8 byte cap in dialog and repository (`AlbumsError.NameTooLong`); tests
- [x] 6.5 S5: `WebDavClient` interceptor adds `Authorization` only for the configured origin; redirect test against a second server
- [x] 6.6 C1: snackbar shown inside the `LaunchedEffect`, outcome consumed afterwards; screen test
- [x] 6.7 C2: success branch drops another entry with the renamed href; ViewModel test
- [x] 6.8 C3: ViewModel test for a cancelled rename (no outcome, no reload)
- [x] 6.9 C4: redirect test uses a dispatcher answering `/elsewhere/` and asserts `requestCount`
- [x] 6.10 C5: `AlbumsRepository.renameAlbum` uses `resultOf {}`
- [x] 6.11 Docs updated (README, `docs/architecture.md`, `CLAUDE.md`); all checks pass
