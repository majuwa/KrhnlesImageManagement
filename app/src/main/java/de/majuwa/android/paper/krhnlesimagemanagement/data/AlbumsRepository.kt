package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.util.exceedsAlbumNameLimit
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Size bounds of a PROPFIND listing (issues #27 / #46); internal so tests can lower them. */
internal data class PropfindListingLimits(
    /** Decompressed response bytes read per listing (album list and photo list). */
    val maxResponseBytes: Long = MAX_PROPFIND_RESPONSE_BYTES,
    /** Photos kept per album listing. */
    val maxPhotos: Int = MAX_PHOTOS_PER_ALBUM,
    val entryLimits: PropfindEntryLimits = PropfindEntryLimits(),
)

/** 32 MiB of (decompressed) PROPFIND XML; the primary memory bound of a listing. */
internal const val MAX_PROPFIND_RESPONSE_BYTES: Long = 32L * 1024 * 1024

/** Photos kept per album listing; beyond it the listing is truncated. */
internal const val MAX_PHOTOS_PER_ALBUM = 20_000

/**
 * Bytes of one image downloaded for hashing / blur analysis ([AlbumsRepository.downloadBitmapForHash]).
 * Usually a 512 px Memories preview; without Memories the original file, so the cap leaves room
 * for large camera JPEGs while keeping a hostile or broken server from exhausting memory.
 */
internal const val MAX_THUMBNAIL_BYTES: Long = 32L * 1024 * 1024

/** Per-read socket timeout of the album client (no overall call timeout, see CLAUDE.md). */
internal const val ALBUMS_READ_TIMEOUT_MS: Long = 60_000

class AlbumsRepository internal constructor(
    private val config: WebDavConfig,
    private val limits: PropfindListingLimits,
    private val maxThumbnailBytes: Long = MAX_THUMBNAIL_BYTES,
    readTimeoutMillis: Long = ALBUMS_READ_TIMEOUT_MS,
    private val memoriesCheckTimeoutMs: Long = MEMORIES_CHECK_TIMEOUT_MS,
    private val memoriesRetryBackoffMs: Long = MEMORIES_RETRY_BACKOFF_MS,
) : AlbumsRepositoryContract {
    constructor(config: WebDavConfig) : this(config, PropfindListingLimits())

    private val webDavClient = WebDavClient(config)
    private val baseHttpUrl: HttpUrl =
        requireNotNull(config.url.trimEnd('/').toHttpUrlOrNull()) { "Invalid WebDAV URL" }

    /**
     * Configured WebDAV URL plus base folder. Every server-supplied album / photo href must resolve
     * to a URL strictly below it (see [resolveWebDavHref]); anything else is never requested.
     */
    private val baseFolderUrl: HttpUrl =
        baseHttpUrl
            .newBuilder()
            .apply {
                config.normalizedBaseFolder
                    .split('/')
                    .filter { it.isNotBlank() }
                    .forEach(::addPathSegment)
            }.build()

    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder().header("User-Agent", USER_AGENT)
                // Credentials only go to the configured origin, never to another host (defence in depth).
                if (request.url.hasSameOriginAs(baseHttpUrl)) {
                    builder.header("Authorization", Credentials.basic(config.username, config.password))
                }
                chain.proceed(builder.build())
            }.build()

    /** scheme://host[:port] derived from config.url */
    private val origin: String = buildOrigin(config.url)

    /** https://cloud.example.com — null if URL doesn't contain /remote.php/ */
    private val nextcloudBase: String? = buildNextcloudBase(config.url)

    private val memories =
        MemoriesAvailability(
            isNextcloud = nextcloudBase != null,
            checkTimeoutMs = memoriesCheckTimeoutMs,
            retryBackoffMs = memoriesRetryBackoffMs,
            check = ::checkMemories,
        )
    private val memoriesAvailable: Boolean? get() = memories.available

    // ── Public API ────────────────────────────────────────────────────────────

    /** Cancels background Memories retries so a dropped repository never sends another request. */
    override fun close() = memories.close()

    /**
     * Album folders below the base folder, from one streamed PROPFIND with the byte cap only: a
     * listing above [PropfindListingLimits.maxResponseBytes] fails with
     * [PropfindResponseTooLargeException] instead of silently missing albums.
     */
    override suspend fun listAlbums(): Result<List<RemoteAlbum>> =
        resultOf {
            // Post-processing (href resolution of every entry) stays off the caller's (main) thread.
            withContext(Dispatchers.IO) {
                executePropfind(baseFolderUrl) { stream, job -> readEntries(stream, job, limits) }
                    .drop(1) // first entry is the directory itself
                    .filter { it.isDirectory }
                    .withSafeHrefsBelow(baseFolderUrl)
                    .map { RemoteAlbum(displayName = it.displayName, href = it.href) }
            }
        }

    /**
     * Photos of [albumHref] from one streamed PROPFIND (see [PhotoListing]). The Memories check
     * runs concurrently, so the thumbnail URLs are available right after the listing.
     */
    override suspend fun listPhotos(
        albumHref: String,
        onProgress: (photosFound: Int) -> Unit,
    ): Result<PhotoListing> =
        resultOf {
            val albumUrl = requireSafeHref(albumHref).withoutTrailingSlash()
            coroutineScope {
                launch { memories.ensureChecked() }
                executePropfind(albumUrl) { stream, job -> readPhotoListing(stream, albumUrl, job, limits, onProgress) }
            }
        }

    override suspend fun renameAlbum(
        album: RemoteAlbum,
        newName: String,
    ): Result<RemoteAlbum> =
        withContext(Dispatchers.IO) {
            resultOf {
                val trimmedName = newName.trim()
                if (!isValidOccasionName(trimmedName)) throw InvalidAlbumNameException()
                if (exceedsAlbumNameLimit(trimmedName)) throw AlbumNameTooLongException()
                val oldSegments = albumRelativeSegments(album)
                val newSegments = oldSegments.dropLast(1) + trimmedName
                webDavClient.renameDirectory(oldSegments, newSegments).getOrThrow()
                RemoteAlbum(
                    displayName = trimmedName,
                    href = buildAlbumHref(newSegments),
                )
            }
        }

    /**
     * URL suitable for a thumbnail (512 px). First call checks Memories availability.
     * Subsequent calls are instant.
     */
    override suspend fun thumbnailUrl(photo: RemotePhoto): String {
        memories.ensureChecked()
        return buildUrl(photo, size = THUMB_SIZE)
    }

    /** URL for full-screen display (up to 2048 px or direct WebDAV). */
    override fun fullImageUrl(photo: RemotePhoto): String = buildUrl(photo, size = FULL_SIZE)

    suspend fun isMemoriesAvailable(): Boolean {
        memories.ensureChecked()
        return memoriesAvailable == true
    }

    /**
     * Downloads [url] and decodes it into a [Bitmap] suitable for hash computation.
     * The returned bitmap must be recycled by the caller.
     *
     * Only URLs this repository hands out as thumbnails are requested: a file strictly below the
     * configured base folder, or the Nextcloud Memories preview of a file id on the configured
     * Nextcloud base. Anything else fails with [UnsafeHrefException] without sending a request.
     *
     * The body is read through [LimitedInputStream] ([MAX_THUMBNAIL_BYTES]; a larger body fails
     * with [ThumbnailTooLargeException], a larger announced `Content-Length` before reading) and the
     * call is cancelled with the coroutine ([executeCancellable]).
     */
    override suspend fun downloadBitmapForHash(url: String): Bitmap {
        val target =
            url.toHttpUrlOrNull()?.takeIf {
                resolveWebDavHref(baseFolderUrl, url) != null || it.isMemoriesPreviewUrl(nextcloudBase, baseHttpUrl)
            } ?: run {
                // The URL itself is not logged: it may be derived from server-controlled data.
                Log.w(TAG, "Refusing a thumbnail download outside the configured server / folder")
                throw UnsafeHrefException()
            }
        val request =
            Request
                .Builder()
                .url(target)
                .get()
                .build()
        return executeCancellable(client.newCall(request)) { response, _ ->
            if (!response.isSuccessful) {
                throw WebDavHttpException(response.code, "Thumbnail download failed: HTTP ${response.code}")
            }
            if (response.body.contentLength() > maxThumbnailBytes) throw ThumbnailTooLargeException()
            val bytes =
                LimitedInputStream(response.body.byteStream(), maxThumbnailBytes, ::ThumbnailTooLargeException)
                    .use { it.readBytes() }
            decodeDownsampled(bytes) ?: error("Could not decode thumbnail bitmap")
        }
    }

    /**
     * Sends a WebDAV DELETE for [photo]. `404 Not Found` / `410 Gone` count as success: the photo
     * is already gone (e.g. deleted from another device), which is what the user asked for. Other
     * non-2xx statuses fail with [WebDavHttpException]. An href outside the configured folder fails
     * with [UnsafeHrefException] without sending a request.
     */
    override suspend fun deletePhoto(photo: RemotePhoto): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { delete(requireSafeHref(photo.href), alreadyGoneIsSuccess = true) }
        }

    /** Sends a WebDAV DELETE for [album], removing the entire directory from the server. */
    override suspend fun deleteAlbum(album: RemoteAlbum): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { delete(requireSafeHref(album.href), alreadyGoneIsSuccess = false) }
        }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun delete(
        url: HttpUrl,
        alreadyGoneIsSuccess: Boolean,
    ) {
        val request =
            Request
                .Builder()
                .url(url)
                .delete()
                .build()
        client.newCall(request).execute().use { response ->
            val alreadyGone = alreadyGoneIsSuccess && response.code in ALREADY_GONE_CODES
            if (!response.isSuccessful && !alreadyGone) {
                throw WebDavHttpException(response.code, "DELETE failed: HTTP ${response.code}")
            }
        }
    }

    /** Resolves a server-supplied [href] strictly below [baseFolderUrl] or throws [UnsafeHrefException]. */
    private fun requireSafeHref(href: String): HttpUrl =
        resolveWebDavHref(baseFolderUrl, href) ?: run {
            // The href itself is not logged: it is server-controlled and may be crafted.
            Log.w(TAG, "Refusing request for an href outside the configured WebDAV folder")
            throw UnsafeHrefException()
        }

    /** Drops entries whose href is not strictly below [root] (see [resolveWebDavHref]). */
    private fun List<WebDavEntry>.withSafeHrefsBelow(root: HttpUrl): List<WebDavEntry> {
        val safe = filter { resolveWebDavHref(root, it.href) != null }
        if (safe.size != size) {
            Log.w(TAG, "Skipped ${size - safe.size} PROPFIND entries with an unsafe href")
        }
        return safe
    }

    private fun HttpUrl.withoutTrailingSlash(): HttpUrl =
        if (pathSegments.size > 1 && pathSegments.last().isEmpty()) {
            newBuilder().removePathSegment(pathSegments.size - 1).build()
        } else {
            this
        }

    private fun buildUrl(
        photo: RemotePhoto,
        size: Int,
    ): String {
        val base = nextcloudBase
        val id = photo.fileId?.takeIf { FILE_ID_REGEX.matches(it) }
        return if (memoriesAvailable == true && base != null && id != null) {
            "$base/index.php/apps/memories/api/image/preview/$id?x=$size&y=$size&a=1"
        } else {
            // Unsafe hrefs never reach the UI (listPhotos filters them); "" just fails to load.
            resolveWebDavHref(baseFolderUrl, photo.href)?.toString().orEmpty()
        }
    }

    private suspend fun checkMemories(): MemoriesCheck =
        withContext(Dispatchers.IO) {
            val base = nextcloudBase ?: return@withContext MemoriesCheck.UNAVAILABLE
            try {
                val req =
                    Request
                        .Builder()
                        .url("$base/index.php/apps/memories/api/describe")
                        .get()
                        .build()
                executeCancellable(client.newCall(req)) { response, _ ->
                    memoriesCheckForStatus(response.code)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Memories API check failed: ${e.javaClass.simpleName}")
                MemoriesCheck.INCONCLUSIVE
            }
        }

    /**
     * Sends a `Depth: 1` PROPFIND for [url] and passes the decompressed body, capped by
     * [LimitedInputStream], and the reading job to [read] (blocking, on the IO dispatcher; see
     * [executeCancellable] for cancellation of the blocked read).
     */
    private suspend fun <T> executePropfind(
        url: HttpUrl,
        read: (InputStream, Job) -> T,
    ): T {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request =
            Request
                .Builder()
                .url(url)
                .method("PROPFIND", body)
                .header("Depth", "1")
                .build()
        return executeCancellable(client.newCall(request)) { response, job ->
            if (!response.isSuccessful && response.code != 207) {
                throw WebDavHttpException(response.code, "PROPFIND failed: HTTP ${response.code}")
            }
            read(LimitedInputStream(response.body.byteStream(), limits.maxResponseBytes), job)
        }
    }

    /**
     * Segments of an album's collection relative to the configured WebDAV URL (as expected by
     * [WebDavClient]). The album must be exactly one level below the base folder; empty or blank
     * segments (`//`, `%20`) fail with [UnsafeHrefException] instead of being dropped, so the
     * request can never address a different folder than the one listed.
     */
    private fun albumRelativeSegments(album: RemoteAlbum): List<String> {
        val segments =
            requireSafeHref(album.href).pathSegments.let {
                if (it.lastOrNull() ==
                    ""
                ) {
                    it.dropLast(1)
                } else {
                    it
                }
            }
        val baseFolderDepth = baseFolderUrl.nonEmptyPathSegments().size
        if (segments.size != baseFolderDepth + 1 || segments.any { it.isBlank() }) throw UnsafeHrefException()
        return segments.drop(baseHttpUrl.nonEmptyPathSegments().size)
    }

    private fun buildAlbumHref(relativeSegments: List<String>): String {
        val url =
            baseHttpUrl
                .newBuilder()
                .apply { relativeSegments.forEach(::addPathSegment) }
                .build()
                .toString()
                .removePrefix(origin)
        return if (url.endsWith("/")) url else "$url/"
    }

    internal companion object {
        const val TAG = "AlbumsRepository"
        const val USER_AGENT = "Kroehnles-Image-Management/1.0"
        const val THUMB_SIZE = 512
        const val FULL_SIZE = 2048
        const val MEMORIES_CHECK_TIMEOUT_MS = 5_000L

        /** Minimum pause before an inconclusive Memories check is repeated. */
        const val MEMORIES_RETRY_BACKOFF_MS = 30_000L

        /** DELETE answers meaning "already deleted": 404 Not Found, 410 Gone. */
        val ALREADY_GONE_CODES = setOf(404, 410)

        val IMAGE_EXTENSIONS =
            setOf(
                "jpg",
                "jpeg",
                "png",
                "gif",
                "webp",
                "bmp",
                "heic",
                "heif",
                "tiff",
                "tif",
                "svg",
                "avif",
            )

        val PROPFIND_BODY =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:prop>
                <d:displayname/>
                <d:resourcetype/>
                <d:getcontenttype/>
                <oc:fileid/>
              </d:prop>
            </d:propfind>
            """.trimIndent()

        /** True when the entry is an image — by content type or file extension fallback. */
        fun isImage(entry: WebDavEntry): Boolean {
            if (entry.contentType.startsWith("image/")) return true
            if (entry.isDirectory) return false
            val ext =
                entry.href
                    .trimEnd('/')
                    .substringAfterLast('.')
                    .lowercase()
            return ext in IMAGE_EXTENSIONS
        }
    }
}

/** Every entry of a multistatus (oversized entries skipped, counted once in the log). */
private fun readEntries(
    stream: InputStream,
    job: Job,
    limits: PropfindListingLimits,
): List<WebDavEntry> {
    val reader = PropfindEntryReader(stream, limits.entryLimits)
    val entries = mutableListOf<WebDavEntry>()
    var skipped = 0
    while (true) {
        job.ensureActive()
        when (val item = reader.next() ?: break) {
            is PropfindItem.Entry -> entries += item.entry
            PropfindItem.Skipped -> skipped++
        }
    }
    logSkippedOversized(skipped)
    return entries
}

/**
 * Streams the photo entries of [albumUrl]: drops the collection itself, keeps images whose
 * href is strictly below the album, drops duplicate hrefs and stops (truncated) at
 * [PropfindListingLimits.maxPhotos] or the byte cap. Only counts are logged, never hrefs.
 */
private fun readPhotoListing(
    stream: InputStream,
    albumUrl: HttpUrl,
    job: Job,
    limits: PropfindListingLimits,
    onProgress: (Int) -> Unit,
): PhotoListing {
    val reader = PropfindEntryReader(stream, limits.entryLimits)
    val collector = PhotoListingCollector(albumUrl, limits.maxPhotos, onProgress)
    try {
        while (!collector.isTruncated) {
            job.ensureActive()
            collector.add(reader.next() ?: break)
        }
    } catch (e: PropfindResponseTooLargeException) {
        // The reader lost its position: the partial entry is dropped, complete ones are kept. Without
        // a complete entry besides the album collection itself there is nothing to show (an empty
        // "truncated" album would look like an empty album).
        if (!collector.hasEntryBesideCollection) {
            throw MalformedMultistatusException("No complete PROPFIND entry", e)
        }
        collector.isTruncated = true
    }
    if (collector.unsafeCount > 0) {
        Log.w(AlbumsRepository.TAG, "Skipped ${collector.unsafeCount} PROPFIND entries with an unsafe href")
    }
    logSkippedOversized(collector.oversizedCount)
    return collector.toListing()
}

private fun logSkippedOversized(count: Int) {
    if (count > 0) Log.w(AlbumsRepository.TAG, "Skipped $count oversized PROPFIND entries")
}

/**
 * Executes [call] blocking on the IO dispatcher and passes the response and the running job
 * to [handle]; closes the response afterwards.
 *
 * Coroutine cancellation does not interrupt a blocking socket read (and `invokeOnCompletion`
 * does not fire while the body is still blocked), so a sibling coroutine waits for
 * cancellation and then cancels the OkHttp [Call]: the read fails at once and the failure is
 * rethrown as the scope's [CancellationException], never reported as an error — whatever type the
 * failure has (a parser may wrap the `IOException`), so every non-cancellation exception of a
 * cancelled scope becomes its [CancellationException]. The sibling starts `UNDISPATCHED`, so its
 * `finally` is armed before the blocking read begins. No `callTimeout` (it would break large
 * uploads on the shared client); the read timeout applies.
 */
private suspend fun <T> executeCancellable(
    call: Call,
    handle: (Response, Job) -> T,
): T =
    withContext(Dispatchers.IO) {
        val canceller =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        try {
            call.execute().use { response -> handle(response, coroutineContext.job) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ensureActive() // cancelled: the failure comes from Call.cancel()
            throw e
        } finally {
            canceller.cancel()
        }
    }

/** Accumulates the photos of a streamed album listing (see [readPhotoListing]). */
private class PhotoListingCollector(
    private val albumUrl: HttpUrl,
    private val maxPhotos: Int,
    private val onProgress: (Int) -> Unit,
) {
    private val photos = ArrayList<RemotePhoto>()
    private val seenHrefs = HashSet<String>()

    /** Complete `d:response` items read, including the collection itself and skipped ones. */
    private var itemCount = 0

    /** At least one complete item after the album collection itself was read. */
    val hasEntryBesideCollection: Boolean get() = itemCount > 1

    var unsafeCount = 0
        private set
    var oversizedCount = 0
        private set

    /** `true` once a photo beyond [maxPhotos] was found or the byte cap was hit; reading stops. */
    var isTruncated = false

    fun add(item: PropfindItem) {
        itemCount++
        if (itemCount == 1) return // the first entry is the album collection itself
        when (item) {
            PropfindItem.Skipped -> oversizedCount++
            is PropfindItem.Entry -> addEntry(item.entry)
        }
    }

    private fun addEntry(entry: WebDavEntry) {
        if (!AlbumsRepository.isImage(entry)) return
        if (resolveWebDavHref(albumUrl, entry.href) == null) {
            unsafeCount++
            return
        }
        if (!seenHrefs.add(entry.href)) return
        if (photos.size >= maxPhotos) {
            isTruncated = true
            return
        }
        photos += RemotePhoto(entry.displayName, entry.href, entry.fileId, entry.contentType)
        if (photos.size % PROGRESS_STEP == 0) onProgress(photos.size)
    }

    fun toListing(): PhotoListing {
        onProgress(photos.size)
        return PhotoListing(photos = photos, isTruncated = isTruncated)
    }

    private companion object {
        /** `onProgress` is reported every this many photos (plus once at the end). */
        const val PROGRESS_STEP = 100
    }
}

/** Runs [block], turning every failure except cancellation into a failed [Result]. */
private suspend fun <T> resultOf(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/** Path of the Memories preview endpoint below the Nextcloud base (see `AlbumsRepository.buildUrl`). */
private val MEMORIES_PREVIEW_SEGMENTS = listOf("index.php", "apps", "memories", "api", "image", "preview")

/** `<nextcloudBase>/index.php/apps/memories/api/image/preview/<fileId>` on the origin of [configured]. */
private fun HttpUrl.isMemoriesPreviewUrl(
    nextcloudBase: String?,
    configured: HttpUrl,
): Boolean {
    val base = nextcloudBase?.toHttpUrlOrNull() ?: return false
    if (!hasSameOriginAs(configured) || encodedFragment != null) return false
    val prefix = base.nonEmptyPathSegments() + MEMORIES_PREVIEW_SEGMENTS
    return pathSegments.size == prefix.size + 1 &&
        pathSegments.take(prefix.size) == prefix &&
        pathSegments.last().let { id -> id.isNotEmpty() && id.all(::isAsciiAlphanumeric) }
}

/** The image downloaded by [AlbumsRepository.downloadBitmapForHash] exceeds [MAX_THUMBNAIL_BYTES]. */
class ThumbnailTooLargeException : IOException("Thumbnail exceeds the size limit")

/** A server-supplied href points outside the configured WebDAV folder / origin and was not requested. */
class UnsafeHrefException : IllegalArgumentException("Refusing href outside the configured WebDAV folder")

/** A new album name exceeds [MAX_ALBUM_NAME_BYTES] UTF-8 bytes; nothing was requested. */
class AlbumNameTooLongException : IllegalArgumentException("Album name too long")

/** A new album name is blank, `.` / `..` or contains `/`, `\` or NUL; nothing was requested. */
class InvalidAlbumNameException : IllegalArgumentException("Invalid album name")

/**
 * Largest power-of-two `inSampleSize` that keeps the longest side of a [width] x [height] image at
 * or above [target] px (the analysis size of dHash and blur detection); 1 for smaller images.
 */
internal fun thumbnailSampleSize(
    width: Int,
    height: Int,
    target: Int = AlbumsRepository.THUMB_SIZE,
): Int {
    require(target > 0) { "target must be positive" }
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / (sample * 2) >= target) sample *= 2
    return sample
}

/** Decodes [bytes] at roughly [AlbumsRepository.THUMB_SIZE] px instead of full size; `null` if undecodable. */
internal fun decodeDownsampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    checkPixelLimit(bounds.outWidth, bounds.outHeight)
    val options =
        BitmapFactory.Options().apply { inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** Largest decodable image (width x height) for scans; larger ones are rejected before any pixel is decoded. */
internal const val MAX_THUMBNAIL_PIXELS: Long = 100_000_000L

/** Rejects decompression bombs: a tiny file can announce a huge canvas that costs CPU and memory to decode. */
internal fun checkPixelLimit(
    width: Int,
    height: Int,
) {
    if (width.toLong() * height > MAX_THUMBNAIL_PIXELS) throw ThumbnailTooLargeException()
}

/** ASCII letters and digits only ([Char.isLetterOrDigit] also accepts e.g. Arabic-Indic digits). */
internal fun isAsciiAlphanumeric(c: Char): Boolean = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'
