package de.majuwa.android.paper.krhnlesimagemanagement.data

import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class AlbumsRepository(
    private val config: WebDavConfig,
) {
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .header("Authorization", Credentials.basic(config.username, config.password))
                        .header("User-Agent", USER_AGENT)
                        .build(),
                )
            }.build()

    /** scheme://host[:port] derived from config.url */
    private val origin: String = buildOrigin(config.url)

    /** https://cloud.example.com — null if URL doesn't contain /remote.php/ */
    private val nextcloudBase: String? = buildNextcloudBase(config.url)

    @Volatile private var memoriesAvailable: Boolean? = null
    private val memoriesMutex = Mutex()

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun listAlbums(): Result<List<RemoteAlbum>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val basePath =
                    if (config.normalizedBaseFolder.isBlank()) {
                        config.url.trimEnd('/')
                    } else {
                        "${config.url.trimEnd('/')}/${config.normalizedBaseFolder}"
                    }
                propfind(basePath)
                    .drop(1) // first entry is the directory itself
                    .filter { it.isDirectory }
                    .map { RemoteAlbum(displayName = it.displayName, href = it.href) }
            }
        }

    suspend fun listPhotos(albumHref: String): Result<List<RemotePhoto>> =
        withContext(Dispatchers.IO) {
            runCatching {
                propfind("$origin${albumHref.trimEnd('/')}")
                    .drop(1)
                    .filter { it.contentType.startsWith("image/") }
                    .map { RemotePhoto(it.displayName, it.href, it.fileId, it.contentType) }
            }
        }

    /**
     * URL suitable for a thumbnail (512 px). First call checks Memories availability.
     * Subsequent calls are instant.
     */
    suspend fun thumbnailUrl(photo: RemotePhoto): String {
        ensureMemoriesChecked()
        return buildUrl(photo, size = THUMB_SIZE)
    }

    /** URL for full-screen display (up to 2048 px or direct WebDAV). */
    fun fullImageUrl(photo: RemotePhoto): String = buildUrl(photo, size = FULL_SIZE)

    suspend fun isMemoriesAvailable(): Boolean {
        ensureMemoriesChecked()
        return memoriesAvailable == true
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun buildUrl(
        photo: RemotePhoto,
        size: Int,
    ): String {
        val base = nextcloudBase
        val id = photo.fileId
        return if (memoriesAvailable == true && base != null && id != null) {
            "$base/index.php/apps/memories/api/image/preview/$id?x=$size&y=$size&a=1"
        } else {
            "$origin${photo.href}"
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
            } catch (_: Exception) {
                false
            }
        }

    private fun propfind(url: String): List<WebDavEntry> {
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
                    error("PROPFIND failed: HTTP ${response.code}")
                }
                response.body?.string() ?: error("Empty PROPFIND response")
            }
        return parsePropfindXml(xml)
    }

    private fun parsePropfindXml(xml: String): List<WebDavEntry> {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val responses = doc.getElementsByTagNameNS(DAV_NS, "response")
        val entries = mutableListOf<WebDavEntry>()

        for (i in 0 until responses.length) {
            val resp = responses.item(i) as? Element ?: continue
            val href = resp.firstTextNS(DAV_NS, "href") ?: continue
            val displayName =
                resp.firstTextNS(DAV_NS, "displayname")
                    ?: href.trimEnd('/').substringAfterLast('/')
            val isDirectory =
                resp
                    .getElementsByTagNameNS(DAV_NS, "collection")
                    .length > 0
            val contentType = resp.firstTextNS(DAV_NS, "getcontenttype") ?: ""
            val fileId = resp.firstTextNS(OC_NS, "fileid")
            entries +=
                WebDavEntry(
                    href = href,
                    displayName = displayName,
                    isDirectory = isDirectory,
                    contentType = contentType,
                    fileId = fileId,
                )
        }
        return entries
    }

    private fun Element.firstTextNS(
        ns: String,
        local: String,
    ): String? =
        (getElementsByTagNameNS(ns, local) as NodeList)
            .let { nl ->
                if (nl.length > 0) {
                    nl
                        .item(0)
                        .textContent
                        .trim()
                        .ifBlank { null }
                } else {
                    null
                }
            }

    private data class WebDavEntry(
        val href: String,
        val displayName: String,
        val isDirectory: Boolean,
        val contentType: String,
        val fileId: String?,
    )

    private companion object {
        const val DAV_NS = "DAV:"
        const val OC_NS = "http://owncloud.org/ns"
        const val USER_AGENT = "Kroehnles-Image-Management/1.0"
        const val THUMB_SIZE = 512
        const val FULL_SIZE = 2048
        const val MEMORIES_CHECK_TIMEOUT_MS = 5_000L

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

        fun buildOrigin(url: String): String =
            try {
                val u = URL(url)
                buildString {
                    append(u.protocol).append("://").append(u.host)
                    if (u.port != -1) append(":").append(u.port)
                }
            } catch (_: Exception) {
                ""
            }

        fun buildNextcloudBase(url: String): String? {
            val idx = url.indexOf("/remote.php/")
            return if (idx > 0) url.substring(0, idx) else null
        }
    }
}
