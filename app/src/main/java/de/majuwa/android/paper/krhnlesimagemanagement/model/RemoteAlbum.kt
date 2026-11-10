package de.majuwa.android.paper.krhnlesimagemanagement.model

/** An album corresponds to a sub-folder inside the configured WebDAV base folder. */
data class RemoteAlbum(
    /** Human-readable folder name (from d:displayname). */
    val displayName: String,
    /** Full WebDAV href as returned by PROPFIND, e.g. /remote.php/dav/files/user/Photos/Summer25/ */
    val href: String,
    /** Folder `d:getlastmodified` as epoch millis; `null` when the server did not report a valid date. */
    val lastModified: Long? = null,
)
