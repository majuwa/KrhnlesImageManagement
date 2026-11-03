package de.majuwa.android.paper.krhnlesimagemanagement.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Highest chunk number Nextcloud's chunked upload v2 accepts. */
internal const val MAX_CHUNK_NUMBER = 10_000

private const val CHUNK_NAME_DIGITS = 5
private const val TRANSFER_ID_PREFIX = "krhnles-"
private const val TRANSFER_ID_HEX_DIGITS = 32
private val transferIdPattern = Regex("$TRANSFER_ID_PREFIX[0-9a-f]{$TRANSFER_ID_HEX_DIGITS}")

private const val FILES_URL_SEGMENTS = 4 // remote.php / dav / files / <user>

/**
 * Validates one path segment of a WebDAV URL before it is added with `HttpUrl` segment encoding:
 * blank, `.` / `..`, `/`, `\` and control characters are rejected (traversal, malformed URLs).
 * Shared by [WebDavClient] and the Nextcloud uploads URL derivation.
 */
internal fun validatePathSegment(segment: String) {
    require(segment.isNotBlank()) { "Invalid path segment: blank" }
    require('/' !in segment) { "Invalid path segment: '$segment'" }
    require(segment != "." && segment != "..") { "Invalid path segment: '$segment'" }
    require('\\' !in segment) { "Invalid path segment: backslash" }
    require(segment.none { it.isISOControl() }) { "Invalid path segment contains a control character" }
}

/**
 * The Nextcloud chunked-upload collection (`<prefix>/remote.php/dav/uploads/<user>`) for a server
 * URL of the form `<prefix>/remote.php/dav/files/<user>/…`, with the scheme, host and port of
 * [configUrl] (a sub-path install keeps its prefix). Any other shape (`/remote.php/webdav/`, plain
 * WebDAV, an unparsable URL) and an unsafe user segment (blank, `..`, containing `/`, `\` or
 * control characters) give `null`: such uploads use a single `PUT`. The user segment is the one
 * already in the files URL, which is the id the server accepts.
 */
internal fun nextcloudUploadsCollection(configUrl: String): HttpUrl? {
    val url = configUrl.trim().toHttpUrlOrNull() ?: return null
    val segments = url.pathSegments
    val start = segments.indexOf("remote.php")
    val isFilesUrl =
        start >= 0 &&
            segments.size > start + FILES_URL_SEGMENTS - 1 &&
            segments[start + 1] == "dav" &&
            segments[start + 2] == "files"
    val user = if (isFilesUrl) segments[start + FILES_URL_SEGMENTS - 1] else null
    val safeUser = user != null && runCatching { validatePathSegment(user) }.isSuccess
    return if (safeUser) {
        HttpUrl
            .Builder()
            .scheme(url.scheme)
            .host(url.host)
            .port(url.port)
            .apply {
                segments.subList(0, start).forEach(::addPathSegment)
                listOf("remote.php", "dav", "uploads", checkNotNull(user)).forEach(::addPathSegment)
            }.build()
    } else {
        null
    }
}

/** True for a transfer id made by [newTransferId]: `krhnles-` and 32 lower-case hex digits. */
internal fun isValidTransferId(id: String): Boolean = transferIdPattern.matches(id)

/** A new random transfer id (a random UUID without dashes). */
internal fun newTransferId(): String = TRANSFER_ID_PREFIX + UUID.randomUUID().toString().replace("-", "")

/** Name of chunk [number] (1..[MAX_CHUNK_NUMBER]): five digits, zero padded (`00001`). */
internal fun chunkName(number: Int): String {
    require(number in 1..MAX_CHUNK_NUMBER) { "Invalid chunk number" }
    return number.toString().padStart(CHUNK_NAME_DIGITS, '0')
}
