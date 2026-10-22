package de.majuwa.android.paper.krhnlesimagemanagement.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** True when this URL has the same scheme, host and port as [other] (same origin). */
internal fun HttpUrl.hasSameOriginAs(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

/** True when this URL has the same origin as the configured [serverUrl]; false if [serverUrl] is invalid. */
internal fun HttpUrl.hasSameOriginAs(serverUrl: String): Boolean =
    serverUrl.toHttpUrlOrNull()?.let { hasSameOriginAs(it) } == true

/**
 * Resolves a server-supplied WebDAV `href` (e.g. a PROPFIND `d:href`, normally an absolute,
 * percent-encoded path such as `/remote.php/dav/files/user/Album/a%20b.jpg`) against [root] and
 * returns the resulting URL only if it is safe to send credentials to and to act on:
 *
 * - no query, fragment, backslash or control characters,
 * - no `.` / `..` path segment, also not percent-encoded (`%2e%2e`), and no encoded `/` or `\`
 *   inside a segment,
 * - same scheme, host and port as [root] (an href such as `@attacker.example/x` or
 *   `https://attacker.example/x` is refused),
 * - path strictly below [root] (or equal to it when [allowRoot] is true).
 *
 * Returns `null` when the href is refused; callers must then not send any request for it.
 */
internal fun resolveWebDavHref(
    root: HttpUrl,
    href: String,
    allowRoot: Boolean = false,
): HttpUrl? {
    if (href.isBlank() || href.any { it in FORBIDDEN_HREF_CHARS || it.isISOControl() }) return null
    if (!href.hasOnlySafeSegments()) return null
    val resolved = root.resolve(href) ?: return null
    if (!resolved.hasSameOriginAs(root)) return null
    if (resolved.encodedQuery != null || resolved.encodedFragment != null) return null
    val rootSegments = root.nonEmptyPathSegments()
    val segments = resolved.nonEmptyPathSegments()
    val isBelowRoot = segments.size > rootSegments.size || (allowRoot && segments.size == rootSegments.size)
    return resolved.takeIf { isBelowRoot && segments.take(rootSegments.size) == rootSegments }
}

/** Decoded path segments without empty ones (trailing slash, doubled slashes). */
internal fun HttpUrl.nonEmptyPathSegments(): List<String> = pathSegments.filter { it.isNotEmpty() }

/**
 * Checks every `/`-separated piece of the raw href after percent-decoding. This runs *before*
 * [HttpUrl.resolve], because OkHttp silently normalizes dot segments away, which would hide a
 * traversal attempt.
 */
private fun String.hasOnlySafeSegments(): Boolean =
    split('/').all { piece ->
        val decoded = piece.percentDecodedOrNull() ?: return false
        decoded != "." && decoded != ".." && '/' !in decoded && '\\' !in decoded && decoded.none { it.isISOControl() }
    }

/** Percent-decodes [this] as UTF-8; `null` for malformed escapes. `+` is kept literally. */
private fun String.percentDecodedOrNull(): String? {
    if ('%' !in this) return this
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '%') {
            val hex = substring(i + 1, minOf(i + PERCENT_ESCAPE_LENGTH, length))
            if (hex.length != 2 || !hex.all { it.isHexDigit() }) return null
            bytes.write(hex.toInt(HEX_RADIX))
            i += PERCENT_ESCAPE_LENGTH
        } else {
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val HEX_RADIX = 16
private const val PERCENT_ESCAPE_LENGTH = 3
private val FORBIDDEN_HREF_CHARS = setOf('?', '#', '\\')
