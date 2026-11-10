# Spec Delta

## ADDED Requirements

### Requirement: Photo downloads stay inside their album
A photo saved to the device SHALL only be requested if its album href resolves strictly below the configured base folder and the photo href resolves strictly below that album, on the configured origin, under the same rules as other server-supplied hrefs. A refused href SHALL fail that photo without sending a request; the decision SHALL be repeated when the queued download runs, against the configuration current at that time.

#### Scenario: Traversal in a queued href
- **WHEN** a queued photo href contains `..`, `%2e%2e` or an encoded slash
- **THEN** no request is sent for it and it counts as failed

#### Scenario: Photo outside its album
- **WHEN** a queued photo href points to another album, the base folder itself or another user's files
- **THEN** no request is sent for it and it counts as failed

#### Scenario: Other origin
- **WHEN** a queued photo href is an absolute URL on another host or port
- **THEN** no request is sent and no credentials leave the device

#### Scenario: Base folder changed after queueing
- **WHEN** the base folder was changed in Settings after the download was queued and the album is no longer below it
- **THEN** no photo of that batch is requested
