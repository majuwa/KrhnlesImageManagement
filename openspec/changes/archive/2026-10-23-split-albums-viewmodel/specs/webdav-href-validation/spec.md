# Spec Delta

## ADDED Requirements

### Requirement: Scan thumbnail downloads stay on the configured server
A thumbnail downloaded for the duplicate or blur scan SHALL only be requested if its URL lies strictly below the configured base folder, or is the Nextcloud Memories preview of a single file id below the configured Nextcloud base on the configured origin. Any other URL SHALL fail without sending a request.

#### Scenario: Thumbnail URL on another origin
- **WHEN** a scan is asked to download a thumbnail from another host or port
- **THEN** no request is sent and the photo is skipped

#### Scenario: Thumbnail URL outside the base folder
- **WHEN** a thumbnail URL points to another folder, another user's files, a traversal path or a Nextcloud endpoint other than the Memories image preview
- **THEN** no request is sent

#### Scenario: Unsafe file id
- **WHEN** a PROPFIND entry carries an `oc:fileid` that is not purely alphanumeric
- **THEN** the id is ignored and the photo uses its WebDAV URL, never a Memories preview URL built from that id

#### Scenario: Regular thumbnails
- **WHEN** the thumbnail is a photo below the base folder or its Memories preview
- **THEN** it is downloaded as before
