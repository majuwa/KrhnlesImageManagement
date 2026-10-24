package de.majuwa.android.paper.krhnlesimagemanagement.util

import android.net.Uri

private const val CONTENT_SCHEME = "content"

/**
 * True if [uri] may be read as a photo: a `content://` URI whose authority does not belong to this
 * app ([ownPackage] or `<ownPackage>.…`). `file://` and other schemes are rejected so a share
 * intent (or a tampered queue file) cannot make the app read its own private files.
 */
fun isAllowedPhotoUri(
    uri: Uri,
    ownPackage: String,
): Boolean {
    if (!CONTENT_SCHEME.equals(uri.scheme, ignoreCase = true)) return false
    // Multi-user URIs look like content://<userId>@<authority>/...
    val authority = uri.authority?.substringAfterLast('@')?.lowercase() ?: return false
    if (authority.isBlank()) return false
    val own = ownPackage.lowercase()
    return authority != own && !authority.startsWith("$own.")
}

/**
 * True if [name] is usable as a single occasion folder name: not blank, not `.` / `..` and without
 * `/`, `\` or NUL (the auto date day folder `YYYY/MM/DD` is built internally, never typed).
 */
fun isValidOccasionName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() &&
        trimmed != "." &&
        trimmed != ".." &&
        trimmed.none { it == '/' || it == '\\' || it == '\u0000' }
}

/** True if every `/`-separated segment of [path] is a valid folder segment (no blank, `.`, `..`, `\`, NUL). */
fun isValidFolderPath(path: String): Boolean {
    val segments = path.trim().split("/")
    return path.isNotBlank() &&
        segments.all { segment ->
            val s = segment.trim()
            s.isNotEmpty() && s != "." && s != ".." && s.none { it == '\\' || it == '\u0000' }
        }
}
