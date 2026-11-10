package de.majuwa.android.paper.krhnlesimagemanagement.data

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val MAX_WEBDAV_DATE_LENGTH = 64

/**
 * Parses a WebDAV `getlastmodified` value (RFC 4918 section 15.7: rfc1123-date) to epoch millis.
 * `null` for null, blank, overlong (> 64 chars) or unparsable input. The formatter's names are
 * fixed English, so the result does not depend on the default locale.
 */
internal fun parseWebDavDate(raw: String?): Long? {
    val value = raw?.trim()
    if (value.isNullOrEmpty() || value.length > MAX_WEBDAV_DATE_LENGTH) return null
    return runCatching {
        ZonedDateTime
            .parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}
