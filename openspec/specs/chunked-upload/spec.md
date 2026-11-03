# chunked-upload Specification

## Purpose

Uploads large photos and videos to Nextcloud in resumable chunks, so that files larger than a server's or proxy's single-request limit still upload and an interrupted upload continues where it stopped instead of starting over.

## Requirements

### Requirement: Large files use Nextcloud chunked upload
A file SHALL be uploaded in chunks when the server URL has the form `…/remote.php/dav/files/<user>/…`, the file size is known before the upload and the size is at least 50 MiB. Every other upload SHALL use one `PUT`: plain WebDAV servers, other Nextcloud URL forms, smaller files and files of unknown size.

#### Scenario: Large video on Nextcloud
- **WHEN** a 2 GiB video is uploaded to `https://cloud.example.com/remote.php/dav/files/alice/`
- **THEN** it is uploaded in chunks and arrives as one file in its target folder

#### Scenario: Small photo on Nextcloud
- **WHEN** a 5 MiB photo is uploaded to the same server
- **THEN** it is uploaded with one `PUT`

#### Scenario: Plain WebDAV
- **WHEN** a 2 GiB video is uploaded to a server whose URL does not contain `/remote.php/`
- **THEN** it is uploaded with one `PUT` and no request goes to an uploads collection

#### Scenario: Legacy Nextcloud URL
- **WHEN** the server URL is `https://cloud.example.com/remote.php/webdav/`
- **THEN** large files are uploaded with one `PUT`

#### Scenario: Unknown size
- **WHEN** the device cannot report the size of a large video
- **THEN** it is uploaded with one `PUT`

### Requirement: Chunked upload protocol
A chunked upload SHALL create the folder `…/remote.php/dav/uploads/<user>/<transfer-id>` with `MKCOL` and a `Destination` header naming the target file. It SHALL send the chunks in order as `PUT`s named `00001`, `00002`, … with `Destination` and `OC-Total-Length`. It SHALL finish with one `MOVE` of `<transfer-id>/.file` with `Destination` and `OC-Total-Length`.

#### Scenario: Three chunks
- **WHEN** a file of 25 MiB is uploaded with a chunk size of 10 MiB
- **THEN** the server receives `MKCOL`, `PUT 00001` (10 MiB), `PUT 00002` (10 MiB), `PUT 00003` (5 MiB) and `MOVE .file`, each with the same `Destination` and the total length 26214400

#### Scenario: Finalised file
- **WHEN** the `MOVE` is answered with 201 or 204
- **THEN** the file counts as uploaded

#### Scenario: Other MOVE answer
- **WHEN** the `MOVE` is answered with any other status
- **THEN** the file is not counted as uploaded

### Requirement: Chunk size and count are bounded
A chunk SHALL be 10 MiB, except the last one, which may be smaller. A file SHALL never need more than 10,000 chunks: for larger files the chunk size SHALL grow to the smallest whole number of MiB that keeps the count at 10,000 or below.

#### Scenario: Very large file
- **WHEN** a 200 GiB file is uploaded in chunks
- **THEN** it is sent in at most 10,000 chunks of equal size, except a smaller last chunk

### Requirement: Chunked requests follow the upload safety rules
Chunked requests SHALL only go to the configured origin, with credentials sent only there. They SHALL never follow a redirect: any 3xx fails the file. They SHALL be cancelled when the upload work is cancelled and SHALL have no overall time limit. Every URL SHALL be built from validated path segments. No server text SHALL be shown or logged.

#### Scenario: Redirect on a chunk
- **WHEN** a chunk `PUT` is answered with 302
- **THEN** no follow-up request is sent to the redirect target and the chunk counts as failed

#### Scenario: Unsafe user segment
- **WHEN** the user segment of the server URL is `..`
- **THEN** no chunked request is sent and the file is uploaded with one `PUT`

### Requirement: Size and storage errors keep their messages
A 413 or 507 answer to any chunked request SHALL fail the file with the same message as for a single `PUT`: "file too large for the server" or "server storage full".

#### Scenario: Server full during chunks
- **WHEN** a chunk `PUT` is answered with 507
- **THEN** the file fails with the message "server storage full" and is offered for retry

### Requirement: Chunked progress survives interruptions
After each confirmed chunk, the upload SHALL save in its queue file how far the transfer got. A later run of the same upload SHALL send only the chunks that are not confirmed yet and then finish the file.

#### Scenario: Run ends after 40 of 100 chunks
- **WHEN** Android stops the upload work after 40 confirmed chunks of a video
- **THEN** the next run sends chunks 41 to 100 and the `MOVE`, and the video counts as uploaded once

#### Scenario: Process death between chunks
- **WHEN** the app process dies after chunk 12 was confirmed
- **THEN** the next run continues with chunk 13

#### Scenario: Older queue files
- **WHEN** a queue file written by an earlier version has no chunk progress
- **THEN** its files are uploaded from the start as before

### Requirement: Resume only continues the same transfer
A resumed transfer SHALL continue only when all of these hold: the server still has its upload folder, the total size is unchanged, the chunk size is the one the app chooses for that size, the device reads the confirmed part again with the same SHA-256, the file is opened the same way (original or not), and the server and destination are unchanged. Otherwise the transfer SHALL start again from the first chunk.

#### Scenario: Local file changed
- **WHEN** a video was edited on the device between two runs
- **THEN** the old transfer is discarded and the video is uploaded from the first chunk

#### Scenario: Server dropped the upload folder
- **WHEN** the server removed the unfinished upload folder (for example after its 24-hour cleanup)
- **THEN** the transfer starts again from the first chunk

#### Scenario: Location permission granted between runs
- **WHEN** the first chunks were read without the media location permission and it is granted before the next run
- **THEN** the transfer starts again from the first chunk with the original file

#### Scenario: Account changed between runs
- **WHEN** the user switched to another server or account before the next run
- **THEN** no request is sent for the old transfer and the file is uploaded from the start to the configured server

#### Scenario: Corrupt progress
- **WHEN** the saved chunk progress of an entry is malformed, including a chunk count above 10,000 that would wrap into range as a 32-bit number or a chunk larger than the file
- **THEN** the progress is ignored, the file is uploaded from the start and the rest of the queue is unaffected

#### Scenario: Progress of another account and the file cannot be opened
- **WHEN** the queue holds progress of another server or account and the file can no longer be opened
- **THEN** the progress is dropped and no request is sent for the old transfer

### Requirement: Transient chunk failures are retried in the run
A chunked file whose request fails transiently SHALL be tried again in the same run from its saved progress, with at most 3 attempts per file and run. Transient means a network error, a read timeout, 408, 423, 429 or 5xx except 501 and 507. When the last attempt also fails transiently, the progress SHALL be kept and the run SHALL be retried later like an interruption, counting towards the limit of five interruptions. Any other failure SHALL fail the file for this run.

#### Scenario: Network drop on chunk 30
- **WHEN** the connection drops while chunk 30 is sent and the next attempt succeeds
- **THEN** chunks 1 to 29 are not sent again and the file counts as uploaded

#### Scenario: Network outage longer than the retries
- **WHEN** the connection is lost after chunk 1 of 3 and all three attempts of the run fail
- **THEN** the confirmed chunk stays saved, no `DELETE` is sent, the run is retried later as an interruption and the next run sends only chunks 2 and 3 and the `MOVE`

#### Scenario: Repeated server errors
- **WHEN** three attempts of the same file are answered with 503
- **THEN** the progress is kept and the run is retried later, and the interruption is counted

#### Scenario: Repeated server errors at the interruption limit
- **WHEN** three attempts of the same file are answered with 503 in a run that already had four interruptions
- **THEN** the upload stops with a failure notification, its transfer is removed from the server and the file is offered for retry without chunk progress

### Requirement: A lost server upload restarts the file
A 404, 409 or 412 on a chunk `PUT` or the `MOVE` means the server lost the transfer: it SHALL be removed and the next attempt SHALL start again from the first chunk with a new transfer. After a `MOVE` answered that way this SHALL happen at most once per file and run; a second such `MOVE` answer SHALL fail the file for this run.

#### Scenario: Server lost the session during the transfer
- **WHEN** a chunk `PUT` is answered with 412
- **THEN** a `DELETE` is sent for the upload folder and the next attempt uploads the file from the first chunk with a new transfer

#### Scenario: Server loses the upload at the MOVE once
- **WHEN** the `MOVE` is answered with 412 and the next attempt's `MOVE` succeeds
- **THEN** the file is uploaded once more with a new transfer and counts as uploaded

#### Scenario: Server keeps losing the upload at the MOVE
- **WHEN** the `MOVE` is answered with 404, 409 or 412 in two attempts of the same run
- **THEN** the file is sent at most twice in this run, both transfers are removed, and the file fails and is offered for retry

### Requirement: Unexpected read errors fail only the file
An unexpected error while the device reads a chunked file (other than an input / output error) SHALL fail that file for this run, remove its transfer and SHALL NOT stop the rest of the run.

#### Scenario: Provider refuses access while the confirmed part is read again
- **WHEN** reading the file on the device fails with a security error during a resume
- **THEN** the file fails for this run, its transfer is removed and the rest of the run continues

### Requirement: Refusals between chunks interrupt the run
After each confirmed chunk except the last of a file, the upload SHALL check that its foreground service is still allowed. A refusal SHALL interrupt the run with the progress kept, exactly like a refusal between two files. It SHALL count towards the same limit of five interruptions.

#### Scenario: Budget ends mid-file
- **WHEN** the system refuses the foreground service after chunk 7 of 20
- **THEN** the work is retried later, the queue keeps chunks 1 to 7 as confirmed, and the interruption is counted

#### Scenario: Interrupted too often
- **WHEN** a chunked file is interrupted for the fifth time
- **THEN** the upload stops with a failure notification, its unfinished transfer is removed from the server and the file is offered for retry

### Requirement: Abandoned transfers are removed
When a transfer will not be resumed (a final failure, a give-up, a folder-creation failure, a user cancel, a discarded resume), the upload SHALL send one bounded `DELETE` for its upload folder, only for a transfer of the configured server, account and destination. Its failure SHALL NOT change the result, and retry files SHALL never carry chunk progress.

#### Scenario: Final MOVE rejected
- **WHEN** the `MOVE` is answered with 400
- **THEN** the file fails, a `DELETE` is sent for its upload folder and the retry file holds the file without chunk progress

#### Scenario: User cancels during chunks
- **WHEN** the user cancels the upload while chunk 5 is sent
- **THEN** the transfer is stopped, the "Upload cancelled" notification and retry file are produced as for any cancel (the retry file without chunk progress), and afterwards a `DELETE` is sent for its upload folder

#### Scenario: User cancels before the saved transfer is resumed
- **WHEN** a queue file holds chunk progress from an earlier run and the user cancels before the next request of that transfer is sent
- **THEN** a `DELETE` is sent for that saved upload folder

#### Scenario: User cancels while the settings are read
- **WHEN** a queue file holds chunk progress and the user cancels while the run is still reading its Wi-Fi-only setting or its server settings
- **THEN** the "Upload cancelled" notification and retry file are produced and a `DELETE` is sent for that saved upload folder

#### Scenario: Target folder cannot be created
- **WHEN** a queue file holds chunk progress and creating the target folder fails
- **THEN** a `DELETE` is sent for that saved upload folder before the failure notification is shown

#### Scenario: User cancels right after the final MOVE
- **WHEN** the `MOVE` was answered with 201 and the user cancels before the next file starts
- **THEN** the file counts as uploaded and no `DELETE` is sent

#### Scenario: Cleanup fails
- **WHEN** the `DELETE` times out or is answered with 500
- **THEN** the result notification is the same as without the cleanup and no server text is shown

#### Scenario: Run stopped by the system
- **WHEN** Android stops the upload work for its own reasons
- **THEN** no `DELETE` is sent and the next run resumes the transfer

#### Scenario: Stopped during the verification after the MOVE
- **WHEN** the `MOVE` succeeded and the run is stopped or cancelled while the upload is verified by downloading it again
- **THEN** the file is not counted as uploaded and is uploaded again by the next run or the retry, as for a single `PUT` (known limit)

### Requirement: Uploads endpoint unavailable falls back
When the server refuses the `MKCOL` that creates a new upload folder with a status that is neither transient (as defined above) nor 413 or 507, the file and the rest of the run SHALL use one `PUT` per file. A transient answer SHALL be retried like any transient chunk failure.

#### Scenario: Uploads collection missing
- **WHEN** the `MKCOL` of a new transfer is answered with 404 or 405
- **THEN** the file is uploaded with one `PUT` and the run sends no further `MKCOL` to the uploads collection

#### Scenario: Uploads collection briefly unavailable
- **WHEN** the `MKCOL` of a new transfer is answered with 503 and the next attempt succeeds
- **THEN** the file is uploaded in chunks with the same transfer id

### Requirement: Chunked progress is shown
While a file is uploaded in chunks, the progress notification SHALL show the share of that file already confirmed in percent, next to the file count of the batch. The text SHALL come from a string resource.

#### Scenario: Halfway through a large video
- **WHEN** 50 of 100 chunks of the second of three files are confirmed
- **THEN** the progress notification shows "1 / 3" and "50 %"
