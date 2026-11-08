package de.majuwa.android.paper.krhnlesimagemanagement.download

import de.majuwa.android.paper.krhnlesimagemanagement.data.baseFolderUrlOf
import de.majuwa.android.paper.krhnlesimagemanagement.data.resolveWebDavHref
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import okhttp3.HttpUrl

/**
 * The URL a queued photo may be downloaded from, or `null` when it must not be requested.
 *
 * [albumHref] must resolve strictly below the configured base folder and [photoHref] strictly below
 * that album, on the configured origin ([resolveWebDavHref]: no `.` / `..`, also encoded, no encoded
 * slash, no query / fragment). The photo must not be a collection (no trailing `/`). The decision
 * is taken against the configuration current at call time; a `null` result means no request at all.
 * An invalid configured URL also yields `null`. No href is logged.
 */
internal fun resolveDownloadTarget(
    config: WebDavConfig,
    albumHref: String,
    photoHref: String,
): HttpUrl? {
    val base = runCatching { baseFolderUrlOf(config) }.getOrNull() ?: return null
    val album = resolveWebDavHref(base, albumHref) ?: return null
    val albumCollection = album.newBuilder().addPathSegment("").build()
    val photo = resolveWebDavHref(albumCollection, photoHref) ?: return null
    return photo.takeUnless { it.encodedPath.endsWith("/") }
}
