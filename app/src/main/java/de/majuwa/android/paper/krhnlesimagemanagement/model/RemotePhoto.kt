package de.majuwa.android.paper.krhnlesimagemanagement.model

/** A photo file inside a remote album. */
data class RemotePhoto(
    val displayName: String,
    /** Full WebDAV href, e.g. /remote.php/dav/files/user/Photos/Summer25/img.jpg */
    val href: String,
    /** Nextcloud oc:fileid — null on non-Nextcloud WebDAV servers. */
    val fileId: String?,
    val contentType: String,
)
