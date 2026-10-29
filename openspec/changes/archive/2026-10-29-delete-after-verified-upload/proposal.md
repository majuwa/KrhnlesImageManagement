# Proposal

## Why

Users back up photos to free space on the device, but today they must delete the originals by hand and trust that every upload arrived intact: a `2xx` on the `PUT` is the only evidence, nothing checks the bytes on the server. Verifying every upload, plus a user-triggered "Remove uploaded photos from device" action that covers only verified, unchanged photos still present on the server, closes that gap without risking the only good copy of a photo.

Automatic removal from the upload worker was the first plan; the device spike (design.md, *Spike result*) showed that a background worker cannot move other apps' photos to the trash (`createTrashRequest` needs `startIntentSenderForResult` from a visible Activity). The user chose a manual menu action instead.

## What Changes

- **Every upload is verified** through one `UploadVerifier` interface with two strategies:
  - **Nextcloud** (URL contains `/remote.php/`): the `PUT` sends `X-Hash: sha256`; the server returns `X-Hash-SHA256`, which is compared with a SHA-256 the app computes while streaming the file. No extra request.
  - **WebDAV download** (any server, also the Nextcloud fallback when `X-Hash-SHA256` is missing): only when the new setting **"Verify by downloading again"** (off by default, uses extra data) is on; the file is downloaded again and its SHA-256 and size compared.
  - Neither available → the upload is *unverified*: it counts as uploaded as before but its photo can never be removed by the app.
- An upload whose verification shows different content or cannot finish counts as a failed photo (not marked uploaded, offered for retry, new localized reason).
- Each verified photo is recorded in a new local store (`VerifiedUploadsStore`: media id, uri, size, modification time, server URL, remote folder and file name), excluded from backup, cleared on logout.
- **New menu action** on the photos screen: **"Remove uploaded photos from device"**. It checks every recorded photo — same server, unchanged on the device (size + modification time), still on the server with the same size (`HEAD`) — shows "Move N photos to trash?" and then the system's own trash confirmation (one dialog per request of up to 2000 items). Photos and videos go to the **system trash** (recoverable for about 30 days), never a hard delete. The result reports the number confirmed in the trash.
- Removal is decided **per photo**; no special access (`MANAGE_MEDIA`) is requested. Shared photos (staged copies without a MediaStore id) are never verified or removed.

## Capabilities

### New Capabilities
- `upload-verification`: verifying that an uploaded file on the server is byte-identical to the local file (local streaming hash, Nextcloud `X-Hash` strategy, download-and-compare strategy and its setting, outcomes and how they count, the verified-uploads record).
- `remove-uploaded-photos`: the user-triggered removal of verified local copies (menu action, candidate checks on device and server, confirmation, system trash, result reporting).

### Modified Capabilities
<!-- None: retries still re-queue exactly the failed photos (`failed-photo-retry`), and a failed verification is a failed upload, so `uploaded-photo-markers` ("failed uploads are not recorded") holds unchanged. -->

## Impact

- **Code:** `data/WebDavClient.kt` (`X-Hash` request header, response hash, bounded cancellable download, `HEAD` size check), new verifier strategies, new `data/VerifiedUploadsStore.kt`, `worker/UploadWorker.kt` (streaming digest, verification per entry, record), `data/CredentialStore.kt` (one plain preference), `ui/settings/` (one toggle, logout clears the store), `ui/photogrid/` (overflow menu, removal ViewModel, dialogs, `StartIntentSenderForResult` launcher), `res/xml/backup_rules.xml` + `data_extraction_rules.xml`, `res/values/strings.xml` + plurals.
- **Manifest:** no new permission.
- **Behaviour for everyone:** Nextcloud uploads now carry `X-Hash: sha256`; a server hash that differs from the local one fails the upload (retry offered). Without the download setting nothing else changes for plain WebDAV.
- **Risk:** removal is destructive; mitigated by verification, the device and server re-checks, the in-app plus system confirmation and trash instead of delete.
- **Docs:** `README.md`, `CLAUDE.md`, `docs/architecture.md`.
