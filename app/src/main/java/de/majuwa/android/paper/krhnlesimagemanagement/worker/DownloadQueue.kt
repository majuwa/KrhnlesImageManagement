package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val DOWNLOAD_QUEUE_TAG = "DownloadQueue"
private val downloadQueueFileName = Regex("download_queue_[0-9]+_[0-9a-f-]+\\.json")

private const val KEY_SERVER_URL = "serverUrl"
private const val KEY_ALBUM_HREF = "albumHref"
private const val KEY_ENTRIES = "entries"
private const val KEY_HREF = "href"
private const val KEY_DOWNLOADED = "downloaded"
private const val KEY_PENDING_ITEM = "pendingItem"
private const val KEY_INTERRUPTIONS = "interruptions"

/**
 * Foreground-service refusals of one download queue after which it gives up (persisted as
 * `interruptions`; constraint stops never count). Separate from the upload constants on purpose.
 */
internal const val MAX_DOWNLOAD_RUN_ATTEMPTS = 5

/**
 * Hard backstop for a counter that cannot be persisted: a refused foreground service gives up once
 * WorkManager's `runAttemptCount` reaches this many starts.
 */
internal const val MAX_DOWNLOAD_RUN_ATTEMPTS_BACKSTOP = 10 * MAX_DOWNLOAD_RUN_ATTEMPTS

/** Directory of download queue files: `noBackupFilesDir`, excluded from backup and device transfer. */
internal fun downloadQueueFilesDir(context: Context): File = context.noBackupFilesDir

/** True if [file] is a download queue file written by the app, directly inside [downloadQueueFilesDir]. */
internal fun isDownloadQueueFile(
    context: Context,
    file: File,
): Boolean =
    try {
        downloadQueueFileName.matches(file.name) &&
            file.canonicalFile.parentFile == downloadQueueFilesDir(context).canonicalFile
    } catch (e: IOException) {
        Log.w(DOWNLOAD_QUEUE_TAG, "Cannot canonicalize queue file path: ${e.javaClass.simpleName}")
        false
    }

/** True if [name] is the name of a download queue file. */
internal fun isDownloadQueueFileName(name: String): Boolean = downloadQueueFileName.matches(name)

/** One photo of a download queue: its server [href] and whether an earlier run already saved it. */
internal data class DownloadQueueEntry(
    val href: String,
    val downloaded: Boolean = false,
)

/**
 * A parsed download queue file. It holds no credentials and no user name: only the [serverUrl] the
 * batch was queued for, the [albumHref], the photo hrefs with their progress, the uri of the
 * [pendingItem] being written (so a killed run can discard it) and the [interruptions] counter.
 */
internal data class DownloadQueue(
    val serverUrl: String,
    val albumHref: String,
    val entries: List<DownloadQueueEntry>,
    val pendingItem: String? = null,
    val interruptions: Int = 0,
) {
    /** Indices of the entries not saved yet (by this or an earlier run). */
    val pendingIndices: List<Int>
        get() = entries.indices.filter { !entries[it].downloaded }

    fun withDownloaded(index: Int): DownloadQueue =
        copy(entries = entries.toMutableList().also { it[index] = it[index].copy(downloaded = true) })

    fun withPendingItem(uri: String?): DownloadQueue = copy(pendingItem = uri)

    fun withInterruption(): DownloadQueue = copy(interruptions = interruptions + 1)

    fun toJson(): JSONObject =
        JSONObject().apply {
            put(KEY_SERVER_URL, serverUrl)
            put(KEY_ALBUM_HREF, albumHref)
            if (interruptions > 0) put(KEY_INTERRUPTIONS, interruptions)
            pendingItem?.let { put(KEY_PENDING_ITEM, it) }
            put(
                KEY_ENTRIES,
                JSONArray().also { array ->
                    entries.forEach { entry ->
                        array.put(
                            JSONObject().apply {
                                put(KEY_HREF, entry.href)
                                if (entry.downloaded) put(KEY_DOWNLOADED, true)
                            },
                        )
                    }
                },
            )
        }
}

/**
 * Parses a download queue file's JSON; null if it is malformed. `serverUrl`, `albumHref` and every
 * entry `href` must be strings (`opt` + `is String`: `org.json` would coerce numbers and `null`),
 * a present `pendingItem` must be a string, `downloaded` counts only as a JSON `true`, and
 * `interruptions` is clamped to `0..MAX_DOWNLOAD_RUN_ATTEMPTS`.
 */
internal fun parseDownloadQueue(json: String): DownloadQueue? =
    try {
        val root = JSONObject(json)
        val serverUrl = root.opt(KEY_SERVER_URL) as? String
        val albumHref = root.opt(KEY_ALBUM_HREF) as? String
        val array = root.opt(KEY_ENTRIES) as? JSONArray
        val pending = root.opt(KEY_PENDING_ITEM)
        val entries =
            array?.let { a ->
                List(a.length()) { i ->
                    val obj = a.optJSONObject(i)
                    val href = obj?.opt(KEY_HREF) as? String
                    href?.let { DownloadQueueEntry(it, obj.opt(KEY_DOWNLOADED) == true) }
                }
            }
        val malformed =
            serverUrl == null || albumHref == null || entries == null || entries.any { it == null } ||
                (root.has(KEY_PENDING_ITEM) && pending !is String)
        if (malformed) {
            null
        } else {
            DownloadQueue(
                serverUrl = serverUrl.orEmpty(),
                albumHref = albumHref.orEmpty(),
                entries = entries.orEmpty().filterNotNull(),
                pendingItem = pending as? String,
                interruptions =
                    (root.opt(KEY_INTERRUPTIONS) as? Number)
                        ?.toLong()
                        ?.coerceIn(0L, MAX_DOWNLOAD_RUN_ATTEMPTS.toLong())
                        ?.toInt() ?: 0,
            )
        }
    } catch (e: JSONException) {
        Log.w(DOWNLOAD_QUEUE_TAG, "Malformed download queue: ${e.javaClass.simpleName}")
        null
    }
