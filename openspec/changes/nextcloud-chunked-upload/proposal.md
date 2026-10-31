# Proposal

## Why

Large videos are uploaded with a single `PUT` (issue #69, follow-up of #15 / #34). Default Nextcloud setups reject bodies above about 512 MiB (nginx `client_max_body_size`) or 1 GiB (Apache `LimitRequestBody`) with HTTP 413. The app reports this clearly but cannot upload such files. When Android ends a run (the `dataSync` foreground budget is used up, a constraint stop, process death), the interrupted file restarts from byte 0. For a multi-gigabyte video on a mobile connection this can mean the upload never finishes.

## What Changes

- On Nextcloud servers whose URL has the form `…/remote.php/dav/files/<user>/…`, every file whose size is known and at least the chunking threshold (50 MiB) is uploaded with Nextcloud chunked upload v2:
  - `MKCOL …/remote.php/dav/uploads/<user>/<transfer-id>` with a `Destination` header,
  - numbered chunk `PUT`s with `Destination` and `OC-Total-Length` (10 MiB per chunk, more for files that would need over 10,000 chunks),
  - one final `MOVE …/<transfer-id>/.file` to the destination with `Destination` and `OC-Total-Length`.
- Plain WebDAV servers, other Nextcloud URL shapes (for example `/remote.php/webdav/`), files below the threshold and files of unknown size still use the single `PUT`. A Nextcloud server whose uploads endpoint refuses the first `MKCOL` also falls back to the single `PUT` for the rest of the run.
- Transfer progress (transfer id, chunk size, total length, number of confirmed chunks, SHA-256 of the confirmed prefix, original or plain source, binding to server and destination) is saved in the queue file entry after each chunk, through the existing atomic `rewriteQueueFile()`. A later run, or a new attempt in the same run, resumes after the last confirmed chunk. It reads the confirmed prefix again on the device without sending it, and restarts the transfer if the local file changed, the source kind changed, the server dropped the upload folder, or the server or destination changed.
- A refused foreground service between two chunks interrupts the run like a refusal between two files, and counts towards the same interruption limit.
- Transient chunk failures (I/O, timeouts, 408, 423, 429, 5xx) are tried again in the same run, at most 3 attempts per file. Each attempt resumes from the saved progress.
- When a transfer is abandoned, the worker sends a best-effort `DELETE` for its upload folder. This covers a file that finally failed in the run, giving up after too many interruptions, a user cancel, and a local file that changed. Retry files never carry transfer progress.
- Verification keeps its rules. The final `MOVE` asks for `X-Hash: sha256`, because Nextcloud computes it while assembling on regular storage. Chunk `PUT`s never ask for it. If the `MOVE` answer has no `X-Hash-SHA256` (object-storage assembly, older servers), the existing fallback applies: download verification when enabled, otherwise unverified. The local digest still covers the whole file and comes from one read per attempt. Only an original source can produce a record.
- The progress notification shows the percentage of a file being uploaded in chunks (new string resource).
- 413 / 507 on any chunked request still map to "file too large for the server" / "server storage full". Every chunked request uses the no-redirect upload client without a call timeout, is cancellable, carries credentials only to the configured origin, and builds its URLs from validated path segments.

## Capabilities

### New Capabilities
- `chunked-upload`: resumable Nextcloud chunked upload v2 for large files: when it is used, the protocol, how progress is persisted and resumed, fallbacks, retries within a run, cleanup of abandoned transfers, and progress display.

### Modified Capabilities
- `upload-verification`: "Every upload is verified when a strategy is available" now covers a resumed chunked transfer (the prefix sent earlier is read again and must match its saved hash). "Nextcloud servers verify with a server-side hash" now names the final `MOVE` of a chunked upload as the request that carries `X-Hash: sha256` and excludes chunk `PUT`s.

## Impact

- **Code**: `data/WebDavClient.kt` (chunk `MKCOL` / `PUT` / `MOVE` / `DELETE` methods, assembly client with a longer read timeout), new `data/NextcloudChunking.kt` (uploads-collection URL derivation, transfer id and chunk-name rules), new `worker/ChunkedTransfer.kt` (persisted state and policy) and `worker/ChunkedUploader.kt` (attempt loop), `worker/UploadQueue.kt` (optional `chunked` entry object, stripped from retry JSON), `worker/UploadWorker.kt` (dispatch, per-chunk persistence, foreground check, abort on give-up / cancel), `res/values/strings.xml` (progress text).
- **Unchanged**: `uploadWorkRequest()`, `UploadEnqueuer`, retry receiver, `UploadVerifier` strategies, `VerifiedUploadsStore`, the queue format for existing files (the new key is optional).
- **Tests**: new MockWebServer worker and client tests (resume, MOVE failure, cancel cleanup, fallback, verification on `MOVE`), queue-parsing tests, plus updates to the regression guards that scan upload sources.
- **Docs**: README.md, CLAUDE.md, docs/architecture.md, docs/requirements.md.
- **Sibling changes**:
  - `cancel-running-upload` adds the notification Cancel action. This change only adds the `DELETE` of an open transfer when the worker is stopped with `STOP_REASON_CANCELLED_BY_APP`, and keeps that action in the new progress text.
  - `pin-self-signed-certificate` changes how the `WebDavClient` clients are built. The new assembly client must be derived from `uploadClient` so it inherits the pin.
  - `german-translation` translates the new string.
  - `show-videos-in-albums` and `download-album-photos` are unaffected because the final files are ordinary files.
