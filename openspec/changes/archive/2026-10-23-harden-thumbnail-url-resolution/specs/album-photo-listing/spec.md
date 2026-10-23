## ADDED Requirements

### Requirement: Thumbnail address resolution costs at most one request per repository instance
Resolving thumbnail addresses SHALL send at most one network request (the Nextcloud Memories availability check) per repository instance once a definite answer is known, regardless of the number of photos or callers, and none when the configured server is not a Nextcloud server. Inconclusive checks are governed by the next requirement (at most one per backoff period). Building an address for a single photo SHALL NOT send a request.

#### Scenario: Many photos, one availability check
- **WHEN** the thumbnail addresses of 500 photos are resolved sequentially on a Nextcloud server
- **THEN** exactly one request to the Memories API is sent

#### Scenario: Concurrent resolution shares the check
- **WHEN** several coroutines resolve thumbnail addresses before the check completed
- **THEN** only one Memories request is sent

#### Scenario: Non-Nextcloud server
- **WHEN** the configured server URL is not a Nextcloud `remote.php` URL
- **THEN** addresses are resolved without any request

### Requirement: Only definite Memories answers are cached
The availability check SHALL cache HTTP 200 as available and any other HTTP status except 429 and 5xx as unavailable. A timeout, I/O failure, HTTP 429 / 5xx or cancellation SHALL NOT be cached: that call uses the direct WebDAV address, and a later call SHALL repeat the check, at most once per backoff period (exponential, capped). Only the first check per repository instance MAY be awaited by a caller; retries SHALL NOT delay listings or address resolution.

#### Scenario: Timeout is retried later
- **WHEN** the check times out and a later call happens after the backoff
- **THEN** the check is sent again and, if answered 200, Memories preview addresses are used from then on

#### Scenario: Retry does not block
- **WHEN** the Memories endpoint never answers and a listing is loaded again after the backoff
- **THEN** the listing does not wait for the check timeout

#### Scenario: Transient server error
- **WHEN** the Memories check answers 503 or 429
- **THEN** it is treated as inconclusive and retried after the backoff

#### Scenario: No request storm during backoff
- **WHEN** many addresses are resolved within the backoff after an inconclusive check
- **THEN** no further Memories request is sent and the WebDAV addresses are used

### Requirement: Scan thumbnails are decoded downsampled
Bitmaps downloaded for duplicate / blur analysis SHALL be decoded with a sample size that keeps the longest side at or above 512 px without exceeding twice that, and images smaller than 512 px SHALL NOT be scaled. Images whose announced size exceeds 100 megapixels SHALL be rejected after reading only their bounds. Download limits, URL restrictions, cancellation and typed errors SHALL be unchanged.

#### Scenario: Large original
- **WHEN** a 3000 x 2000 image is downloaded for analysis
- **THEN** the returned bitmap's longest side is between 512 and 1023 px

#### Scenario: Decompression bomb
- **WHEN** an image announces 60000 x 60000 px
- **THEN** it is rejected with a typed error before any pixel is decoded and the scan skips the photo

#### Scenario: Small image
- **WHEN** a 300 x 200 image is downloaded
- **THEN** it is returned at 300 x 200
