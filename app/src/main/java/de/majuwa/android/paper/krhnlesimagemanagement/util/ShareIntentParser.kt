package de.majuwa.android.paper.krhnlesimagemanagement.util

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** At most this many photos are accepted from one share; the rest is reported as not included. */
const val MAX_SHARED_PHOTOS = 100

private const val TAG = "ShareIntentParser"
private const val SHORT_ID_LENGTH = 8
private const val MAX_FILE_NAME_BYTES = 255
private const val OCTET_STREAM = "application/octet-stream"

/** A provider that does not answer `getType` / `query` for one item within this time drops the item. */
internal const val PROVIDER_METADATA_TIMEOUT_MILLIS = 10_000L

/**
 * The URIs of a share intent before any provider access.
 *
 * @property uris accepted, de-duplicated URIs in share order, at most [MAX_SHARED_PHOTOS].
 * @property truncated accepted URIs beyond [MAX_SHARED_PHOTOS] that were not included.
 * @property rejected items dropped by [isAllowedPhotoUri] (`file://`, other schemes, this app's authorities).
 */
data class SharedItems(
    val uris: List<Uri>,
    val truncated: Int,
    val rejected: Int,
) {
    companion object {
        val EMPTY = SharedItems(emptyList(), truncated = 0, rejected = 0)
    }
}

/**
 * Extracts the shared [Uri]s from the `EXTRA_STREAM` of an [Intent.ACTION_SEND] (one URI) or
 * [Intent.ACTION_SEND_MULTIPLE] (a list) intent. Pure: no provider is queried.
 *
 * The intent is untrusted: extras of another type and unparcelable / unreadable extras (any
 * `RuntimeException` while reading them) are ignored (only the exception type is logged).
 * Order: [isAllowedPhotoUri] (only `content://` URIs of other apps, [ownPackage]),
 * de-duplication, cap at [MAX_SHARED_PHOTOS].
 */
fun parseSharedPhotos(
    intent: Intent,
    ownPackage: String,
): SharedItems {
    val raw = readStreamExtra(intent)
    val allowed = raw.filter { isAllowedPhotoUri(it, ownPackage) }
    val unique = allowed.distinct()
    return SharedItems(
        uris = unique.take(MAX_SHARED_PHOTOS),
        truncated = (unique.size - MAX_SHARED_PHOTOS).coerceAtLeast(0),
        rejected = raw.size - allowed.size,
    )
}

/** True if [intent] is a share intent this app handles (it is then never shown as the main app). */
fun isShareIntent(intent: Intent?): Boolean =
    intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE

private fun readStreamExtra(intent: Intent): List<Uri> =
    try {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Generic erasure: a hostile list may still carry other element types.
                val list: List<Any?> =
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                list.filterIsInstance<Uri>()
            }

            else -> {
                emptyList()
            }
        }
    } catch (e: RuntimeException) {
        // BadParcelableException, ClassCastException and whatever a hostile parcel can trigger.
        Log.w(TAG, "Ignoring unreadable share extra: ${e.javaClass.simpleName}")
        emptyList()
    }

/** The share's photos after the provider was asked; [rejected] items were not images, unreadable or timed out. */
data class ResolvedShare(
    val photos: List<Photo>,
    val rejected: Int,
)

/**
 * An allow-listed raster image type: [mimeType] is sent as the upload's content type,
 * [extensions] are the file extensions a remote name may end with (the first one is appended to a
 * name that ends with another one). SVG and every other type are not accepted.
 */
internal enum class SharedImageType(
    val mimeType: String,
    val extensions: List<String>,
) {
    JPEG("image/jpeg", listOf("jpg", "jpeg")),
    PNG("image/png", listOf("png")),
    GIF("image/gif", listOf("gif")),
    WEBP("image/webp", listOf("webp")),
    HEIC("image/heic", listOf("heic", "heif")),
    HEIF("image/heif", listOf("heif", "heic")),
    AVIF("image/avif", listOf("avif")),
    BMP("image/bmp", listOf("bmp")),
    DNG("image/x-adobe-dng", listOf("dng")),
    ;

    companion object {
        private val aliases =
            mapOf(
                "image/jpg" to JPEG,
                "image/pjpeg" to JPEG,
                "image/x-ms-bmp" to BMP,
                "image/dng" to DNG,
            )

        /** The allow-listed type for a MIME type (parameters and case ignored), or null. */
        fun of(mimeType: String?): SharedImageType? {
            val normalized = mimeType?.let(::baseMimeType) ?: return null
            return entries.firstOrNull { it.mimeType == normalized } ?: aliases[normalized]
        }
    }
}

/** `type/subtype` without parameters, trimmed and lower case. */
private fun baseMimeType(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)

/**
 * Resolves [uris] into [Photo]s. Every item's provider calls (`getType`, `query` with a
 * [CancellationSignal]) run on [dispatcher] under [itemTimeoutMillis] ([abandonableCall]); an item
 * whose provider does not answer in time is dropped and counted as rejected.
 *
 * The type is decided by [resolveImageType] and must be allow-listed ([SharedImageType]). Provider
 * failures (`SecurityException`, `IllegalArgumentException`, `IllegalStateException`,
 * `OperationCanceledException`) never crash: an unknown type falls back to the name / [intentType],
 * unknown metadata to a generated name and size 0.
 *
 * Shared photos have no MediaStore id (`id = -1`), so they are never marked as uploaded. The
 * remote file name is the provider's name if it is one valid name ([isValidRemoteFileName]) — with
 * the type's extension appended if it ends with another one — and not yet used in this share
 * (case-insensitive), else `shared_<timestamp>_<short id>.<ext>`.
 */
suspend fun resolveSharedPhotos(
    uris: List<Uri>,
    intentType: String?,
    contentResolver: ContentResolver,
    today: LocalDate = LocalDate.now(),
    nowMillis: Long = System.currentTimeMillis(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    itemTimeoutMillis: Long = PROVIDER_METADATA_TIMEOUT_MILLIS,
): ResolvedShare {
    val usedNames = mutableSetOf<String>()
    val photos =
        uris.mapNotNull { uri ->
            val info =
                abandonableCall(dispatcher, itemTimeoutMillis) { signal ->
                    queryProviderInfo(contentResolver, uri, signal)
                } ?: return@mapNotNull null
            val type = resolveImageType(info.type, info.displayName, intentType) ?: return@mapNotNull null
            val name =
                info.displayName
                    ?.let { withTypeExtension(it, type) }
                    ?.takeIf { isValidRemoteFileName(it) && it.lowercase(Locale.ROOT) !in usedNames }
                    ?: generatedName(type, nowMillis, usedNames)
            usedNames += name.lowercase(Locale.ROOT)
            Photo(
                id = -1L,
                uri = uri,
                displayName = name,
                dateTaken = today,
                size = info.size,
                mimeType = type.mimeType,
            )
        }
    return ResolvedShare(photos = photos, rejected = uris.size - photos.size)
}

/**
 * The allow-listed type of a shared item. A concrete provider type decides alone (a non-image or
 * a non-allow-listed image type such as SVG drops the item). If the provider reports none,
 * `application/octet-stream` or a wildcard, the [displayName]'s extension decides via
 * [MimeTypeMap] (a known non-image extension drops the item); without a known extension a concrete
 * allow-listed [intentType] is used (never a wildcard such as image/any).
 */
internal fun resolveImageType(
    providerType: String?,
    displayName: String?,
    intentType: String?,
): SharedImageType? {
    val provider = providerType?.let(::baseMimeType)
    if (provider != null && !isUnknownType(provider)) return SharedImageType.of(provider)
    val extensionType =
        displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    return if (extensionType != null) SharedImageType.of(extensionType) else SharedImageType.of(intentType)
}

/** True if a provider-reported (normalized) type says nothing about the content. */
private fun isUnknownType(type: String): Boolean = type.isEmpty() || type == OCTET_STREAM || type.endsWith("/*")

/** [name] if it already ends with one of [type]'s extensions (any case), else `name.<ext>`. */
internal fun withTypeExtension(
    name: String,
    type: SharedImageType,
): String {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    return if (extension in type.extensions) name else "$name.${type.extensions.first()}"
}

/**
 * True if [name] is usable as one remote file name: a valid path segment of at most 255 UTF-8
 * bytes without invisible formatting characters (Unicode category Cf, e.g. the bidi controls
 * U+202A–U+202E / U+2066–U+2069 and U+200E / U+200F that can disguise an extension), line /
 * paragraph separators or unpaired surrogates.
 */
fun isValidRemoteFileName(name: String): Boolean =
    isValidPathSegment(name) &&
        name.toByteArray(Charsets.UTF_8).size <= MAX_FILE_NAME_BYTES &&
        name.codePoints().noneMatch { Character.getType(it).toByte() in disallowedNameCharTypes }

private val disallowedNameCharTypes =
    setOf(
        Character.FORMAT,
        Character.SURROGATE,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
    )

/** The `WebDavClient` path-segment rule: not blank, not `.` / `..`, no `/`, `\` or control characters. */
fun isValidPathSegment(segment: String): Boolean =
    segment.isNotBlank() &&
        segment != "." &&
        segment != ".." &&
        segment.none { it == '/' || it == '\\' || it.isISOControl() }

private fun generatedName(
    type: SharedImageType,
    nowMillis: Long,
    usedNames: Set<String>,
): String {
    val extension = type.extensions.first()
    while (true) {
        val shortId = UUID.randomUUID().toString().take(SHORT_ID_LENGTH)
        val name = "shared_${nowMillis}_$shortId.$extension"
        if (name !in usedNames) return name
    }
}

private data class ProviderInfo(
    val type: String?,
    val displayName: String?,
    val size: Long,
)

/** Blocking: the provider's type and `OpenableColumns` of [uri]; failures become unknown values. */
private fun queryProviderInfo(
    contentResolver: ContentResolver,
    uri: Uri,
    signal: CancellationSignal,
): ProviderInfo {
    val type = providerCall { contentResolver.getType(uri) }
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    val meta =
        providerCall {
            contentResolver.query(uri, projection, null, null, null, signal)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                ProviderInfo(type = null, displayName = name, size = size.coerceAtLeast(0L))
            }
        }
    return ProviderInfo(type = type, displayName = meta?.displayName, size = meta?.size ?: 0L)
}

/** Runs a provider call; the provider failures a hostile or revoked share can cause become null. */
private inline fun <T> providerCall(block: () -> T?): T? =
    try {
        block()
    } catch (e: SecurityException) {
        logProviderFailure(e)
    } catch (e: IllegalArgumentException) {
        logProviderFailure(e)
    } catch (e: IllegalStateException) {
        logProviderFailure(e)
    } catch (e: OperationCanceledException) {
        logProviderFailure(e)
    }

private fun logProviderFailure(e: Exception): Nothing? {
    Log.w(TAG, "Provider call failed: ${e.javaClass.simpleName}")
    return null
}
