package de.majuwa.android.paper.krhnlesimagemanagement.model

/** A media file (photo or video) inside a remote album; the name is kept for history. */
data class RemotePhoto(
    val displayName: String,
    /** Full WebDAV href, e.g. /remote.php/dav/files/user/Photos/Summer25/img.jpg */
    val href: String,
    /** Nextcloud oc:fileid — null on non-Nextcloud WebDAV servers. */
    val fileId: String?,
    val contentType: String,
    /** Set by the listing (`AlbumsRepository.classifyRemoteMedia`), never derived from [contentType]. */
    val mediaKind: MediaKind = MediaKind.Image,
) {
    val isVideo: Boolean get() = mediaKind == MediaKind.Video
}

/** The photos of this list; videos are never analysed by the duplicate / blur reviews. */
fun List<RemotePhoto>.imagesOnly(): List<RemotePhoto> = filterNot { it.isVideo }
