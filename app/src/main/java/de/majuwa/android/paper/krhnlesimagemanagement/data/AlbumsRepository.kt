package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AlbumsRepository(
    private val config: WebDavConfig,
) : AlbumsRepositoryContract {
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
            .readTimeout(60, TimeUnit.SECONDS)
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

    @Volatile private var memoriesAvailable: Boolean? = null
    private val memoriesMutex = Mutex()

    // ── Public API ────────────────────────────────────────────────────────────

    override suspend fun listAlbums(): Result<List<RemoteAlbum>> =
        withContext(Dispatchers.IO) {
            runCatching {
                propfind(baseFolderUrl)
                    .drop(1) // first entry is the directory itself
                    .filter { it.isDirectory }
                    .withSafeHrefsBelow(baseFolderUrl)
                    .map { RemoteAlbum(displayName = it.displayName, href = it.href) }
            }
        }

    override suspend fun listPhotos(albumHref: String): Result<List<RemotePhoto>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val albumUrl = requireSafeHref(albumHref).withoutTrailingSlash()
                propfind(albumUrl)
                    .drop(1)
                    .filter { isImage(it) }
                    .withSafeHrefsBelow(albumUrl)
                    .map { RemotePhoto(it.displayName, it.href, it.fileId, it.contentType) }
            }
        }

    override suspend fun renameAlbum(
        album: RemoteAlbum,
        newName: String,
    ): Result<RemoteAlbum> =
        withContext(Dispatchers.IO) {
            runCatching {
                val oldSegments = albumRelativeSegments(album)
                require(oldSegments.isNotEmpty()) { "Invalid album path" }
                val newSegments = oldSegments.dropLast(1) + newName
                webDavClient
                    .renameDirectory(
                        oldPath = oldSegments.joinToString("/"),
                        newPath = newSegments.joinToString("/"),
                    ).getOrThrow()
                RemoteAlbum(
                    displayName = newName,
                    href = buildAlbumHref(newSegments),
                )
            }
        }

    /**
     * URL suitable for a thumbnail (512 px). First call checks Memories availability.
     * Subsequent calls are instant.
     */
    override suspend fun thumbnailUrl(photo: RemotePhoto): String {
        ensureMemoriesChecked()
        return buildUrl(photo, size = THUMB_SIZE)
    }

    /** URL for full-screen display (up to 2048 px or direct WebDAV). */
    override fun fullImageUrl(photo: RemotePhoto): String = buildUrl(photo, size = FULL_SIZE)

    suspend fun isMemoriesAvailable(): Boolean {
        ensureMemoriesChecked()
        return memoriesAvailable == true
    }

    /**
     * Downloads [url] and decodes it into a [Bitmap] suitable for hash computation.
     * The returned bitmap must be recycled by the caller.
     *
     * Only URLs this repository hands out as thumbnails are requested: a file strictly below the
     * configured base folder, or the Nextcloud Memories preview of a file id on the configured
     * Nextcloud base. Anything else fails with [UnsafeHrefException] without sending a request.
     */
    override suspend fun downloadBitmapForHash(url: String): Bitmap =
        withContext(Dispatchers.IO) {
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WebDavHttpException(response.code, "Thumbnail download failed: HTTP ${response.code}")
                }
                val bytes = response.body.bytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Could not decode thumbnail bitmap")
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
        val id = photo.fileId
        return if (memoriesAvailable == true && base != null && id != null) {
            "$base/index.php/apps/memories/api/image/preview/$id?x=$size&y=$size&a=1"
        } else {
            // Unsafe hrefs never reach the UI (listPhotos filters them); "" just fails to load.
            resolveWebDavHref(baseFolderUrl, photo.href)?.toString().orEmpty()
        }
    }

    private suspend fun ensureMemoriesChecked() {
        if (memoriesAvailable != null) return
        // withTimeoutOrNull so the mutex is never held indefinitely (CLAUDE.md: locks need timeouts)
        withTimeoutOrNull(MEMORIES_CHECK_TIMEOUT_MS) {
            memoriesMutex.withLock {
                if (memoriesAvailable == null) {
                    memoriesAvailable = checkMemories()
                }
            }
        } ?: run { memoriesAvailable = false }
    }

    private suspend fun checkMemories(): Boolean =
        withContext(Dispatchers.IO) {
            val base = nextcloudBase ?: return@withContext false
            try {
                val req =
                    Request
                        .Builder()
                        .url("$base/index.php/apps/memories/api/describe")
                        .get()
                        .build()
                client.newCall(req).execute().use { it.code == 200 }
            } catch (e: Exception) {
                Log.w(TAG, "Memories API check failed: ${e.javaClass.simpleName}")
                false
            }
        }

    private fun propfind(url: HttpUrl): List<WebDavEntry> {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request =
            Request
                .Builder()
                .url(url)
                .method("PROPFIND", body)
                .header("Depth", "1")
                .build()
        val xml =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 207) {
                    throw WebDavHttpException(response.code, "PROPFIND failed: HTTP ${response.code}")
                }
                response.body.string()
            }
        return parsePropfindXml(xml)
    }

    /** Album path relative to the configured WebDAV URL (as expected by [WebDavClient]). */
    private fun albumRelativeSegments(album: RemoteAlbum): List<String> =
        requireSafeHref(album.href)
            .nonEmptyPathSegments()
            .drop(baseHttpUrl.nonEmptyPathSegments().size)

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

    private companion object {
        const val TAG = "AlbumsRepository"
        const val USER_AGENT = "Kroehnles-Image-Management/1.0"
        const val THUMB_SIZE = 512
        const val FULL_SIZE = 2048
        const val MEMORIES_CHECK_TIMEOUT_MS = 5_000L

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
        pathSegments.last().let { id -> id.isNotEmpty() && id.all(Char::isLetterOrDigit) }
}

/** A server-supplied href points outside the configured WebDAV folder / origin and was not requested. */
class UnsafeHrefException : IllegalArgumentException("Refusing href outside the configured WebDAV folder")
