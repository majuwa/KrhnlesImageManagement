package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject

internal const val VERIFIED_UPLOADS_DATASTORE_NAME = "verified_uploads"

/** At most this many records are kept; the oldest ([VerifiedUpload.verifiedAt]) are dropped. */
internal const val MAX_VERIFIED_UPLOADS = 20_000

// An unreadable file means no records: nothing becomes removable, which is safe.
// The handler is stateless, so the same instance serves the delegate and the tests.
internal val verifiedUploadsCorruptionHandler = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

private val Context.verifiedUploadsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = VERIFIED_UPLOADS_DATASTORE_NAME,
    corruptionHandler = verifiedUploadsCorruptionHandler,
)

/**
 * One photo whose upload was verified byte-identical on the server. [size] and [dateModified] are
 * the MediaStore values read just before the upload; [serverUrl], [folder] and [fileName] say where
 * the copy lives (the exact remote folder string passed to the `PUT`). No credentials.
 */
data class VerifiedUpload(
    /** [de.majuwa.android.paper.krhnlesimagemanagement.model.Photo.id] (flagged for videos), `>= 0`. */
    val id: Long,
    val uri: String,
    val size: Long,
    val dateModified: Long,
    val serverUrl: String,
    val folder: String,
    val fileName: String,
    val verifiedAt: Long,
)

/**
 * Where a `PUT` writes: the configured server URL, the exact remote folder string and the file name.
 * Records are bound to it: a `PUT` to the same target replaces the file a record describes.
 */
data class UploadTarget(
    val serverUrl: String,
    val folder: String,
    val fileName: String,
)

/**
 * True if this record describes the file at [target]. Folder and file name are compared
 * case-insensitively (some servers are case-insensitive), so a doubtful match forgets more, never less.
 */
fun VerifiedUpload.isAt(target: UploadTarget): Boolean =
    serverUrl == target.serverUrl &&
        folder.equals(target.folder, ignoreCase = true) &&
        fileName.equals(target.fileName, ignoreCase = true)

/**
 * Device-local record of verified uploads, the only input of the removal action. The file is excluded
 * from backup and device transfer (device-specific MediaStore ids, uris and folder names).
 */
class VerifiedUploadsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    /** Parses the records off the main thread (up to 20,000 JSON objects); injectable for tests. */
    parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : VerifiedUploadsRepositoryContract {
    /** Production constructor using the process-wide DataStore (the internal one is for tests). */
    constructor(context: Context) : this(context.applicationContext.verifiedUploadsDataStore)

    private companion object {
        val KEY_RECORDS = stringSetPreferencesKey("records")
    }

    override val records: Flow<List<VerifiedUpload>> =
        dataStore.data.map { it[KEY_RECORDS].parseRecords() }.flowOn(parseDispatcher)

    override suspend fun recordVerified(record: VerifiedUpload) {
        if (record.id < 0L) return
        dataStore.edit { prefs ->
            val merged = prefs[KEY_RECORDS].parseRecords().filterNot { it.id == record.id } + record
            prefs[KEY_RECORDS] = merged.newestFirstCapped().map { it.toJson().toString() }.toSet()
        }
    }

    override suspend fun applyUpload(
        target: UploadTarget,
        record: VerifiedUpload?,
        forgetIds: Set<Long>,
    ) {
        val stored = record?.takeIf { it.id >= 0L }
        dataStore.edit { prefs ->
            val existing = prefs[KEY_RECORDS].parseRecords()
            val kept = existing.filterNot { it.isAt(target) || it.id in forgetIds || it.id == stored?.id }
            // Only write when something changed: most uploads touch no record.
            if (stored == null && kept.size == existing.size) return@edit
            prefs[KEY_RECORDS] =
                (kept + listOfNotNull(stored)).newestFirstCapped().map { it.toJson().toString() }.toSet()
        }
    }

    override suspend fun forget(ids: Set<Long>) {
        if (ids.isEmpty()) return
        dataStore.edit { prefs ->
            val existing = prefs[KEY_RECORDS]?.parseRecords() ?: return@edit
            val kept = existing.filterNot { it.id in ids }
            if (kept.size != existing.size) prefs[KEY_RECORDS] = kept.map { it.toJson().toString() }.toSet()
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_RECORDS) }
    }
}

/** Newest record per id (ties: the last parsed), newest first, at most [MAX_VERIFIED_UPLOADS]. */
private fun List<VerifiedUpload>.newestFirstCapped(): List<VerifiedUpload> =
    groupBy { it.id }
        .values
        .map { sameId -> sameId.maxBy { it.verifiedAt } }
        .sortedByDescending { it.verifiedAt }
        .take(MAX_VERIFIED_UPLOADS)

/** Strict parse; a malformed element is skipped (that photo is not removable), never repaired. */
private fun Set<String>?.parseRecords(): List<VerifiedUpload> =
    orEmpty().mapNotNull(::parseVerifiedUpload).newestFirstCapped()

internal fun parseVerifiedUpload(json: String): VerifiedUpload? =
    try {
        val obj = JSONObject(json)
        VerifiedUpload(
            id = obj.strictLong("id"),
            uri = obj.strictString("uri"),
            size = obj.strictLong("size"),
            dateModified = obj.strictLong("dateModified"),
            serverUrl = obj.strictString("serverUrl"),
            folder = obj.strictString("folder"),
            fileName = obj.strictString("fileName"),
            verifiedAt = obj.strictLong("verifiedAt"),
        ).takeIf { it.id >= 0L && it.size >= 0L }
    } catch (e: JSONException) {
        Log.w("VerifiedUploadsStore", "Skipping malformed record: ${e.javaClass.simpleName}")
        null
    }

/** `org.json` coerces strings and doubles; only a JSON integer counts here, anything else is malformed. */
private fun JSONObject.strictLong(key: String): Long =
    opt(key).let { value -> (value as? Long) ?: (value as? Int)?.toLong() }
        ?: throw JSONException("Not an integer: $key")

private fun JSONObject.strictString(key: String): String =
    opt(key) as? String ?: throw JSONException("Not a string: $key")

private fun VerifiedUpload.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("uri", uri)
        .put("size", size)
        .put("dateModified", dateModified)
        .put("serverUrl", serverUrl)
        .put("folder", folder)
        .put("fileName", fileName)
        .put("verifiedAt", verifiedAt)
