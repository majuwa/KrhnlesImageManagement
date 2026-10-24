package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

internal const val UPLOAD_HISTORY_DATASTORE_NAME = "upload_history"

// An unreadable file means an empty history: the log is a convenience, losing it is safe.
internal val uploadHistoryCorruptionHandler = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

private val Context.uploadHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = UPLOAD_HISTORY_DATASTORE_NAME,
    corruptionHandler = uploadHistoryCorruptionHandler,
)

/**
 * Local log of finished upload runs. The file is excluded from backup and device transfer because
 * folder names are user-written. At most [MAX_HISTORY_ENTRIES] newest entries are kept.
 */
class UploadHistoryStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : UploadHistoryRepository {
    /** Production constructor using the process-wide DataStore (the internal one is for tests). */
    constructor(context: Context) : this(context.applicationContext.uploadHistoryDataStore)

    private val tag = "UploadHistoryStore"

    private companion object {
        val KEY_UPLOAD_HISTORY = stringPreferencesKey("upload_history_entries")
        const val MAX_HISTORY_ENTRIES = 500
    }

    override val entries: Flow<List<UploadHistoryEntry>> =
        dataStore.data.map { prefs ->
            decodeEntries(prefs[KEY_UPLOAD_HISTORY]).sortedByDescending { it.timestampMillis }
        }

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        require(isValidEntry(entry)) {
            "failedCount must be between 0 and total photo count, and photo count must be non-negative"
        }
        dataStore.edit { prefs ->
            val existing = decodeEntries(prefs[KEY_UPLOAD_HISTORY])
            val stored =
                if (existing.any { it.id == entry.id }) {
                    // maxOf(...) + 1 would only overflow at Long.MAX_VALUE, which ids (epoch millis) never reach.
                    entry.copy(id = maxOf(entry.id, existing.maxOf { it.id } + 1))
                } else {
                    entry
                }
            val updated =
                (existing + stored)
                    .sortedByDescending { it.timestampMillis }
                    .take(MAX_HISTORY_ENTRIES)
            prefs[KEY_UPLOAD_HISTORY] = encodeEntries(updated)
        }
    }

    override suspend fun removeEntry(entryId: Long) {
        dataStore.edit { prefs ->
            val updated = decodeEntries(prefs[KEY_UPLOAD_HISTORY]).filterNot { it.id == entryId }
            prefs[KEY_UPLOAD_HISTORY] = encodeEntries(updated)
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_UPLOAD_HISTORY)
        }
    }

    private fun decodeEntries(raw: String?): List<UploadHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val entry =
                        UploadHistoryEntry(
                            id = item.getLong("id"),
                            occasionName = item.getString("occasionName"),
                            timestampMillis = item.getLong("timestampMillis"),
                            photoCount = item.getInt("photoCount"),
                            failedCount = item.getInt("failedCount"),
                        )
                    if (isValidEntry(entry)) {
                        add(entry)
                    } else {
                        Log.w(tag, "Skipping invalid upload history entry at index=$i")
                    }
                }
            }
        }.map { decoded ->
            // Newest entry per id wins (stable sort); the dropped duplicate disappears on the next write.
            val unique = decoded.sortedByDescending { it.timestampMillis }.distinctBy { it.id }
            if (unique.size < decoded.size) {
                Log.w(tag, "Dropped ${decoded.size - unique.size} upload history entries with duplicate ids")
            }
            unique
        }.getOrElse { throwable ->
            Log.e(tag, "Failed to parse upload history entries: ${throwable.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun encodeEntries(entries: List<UploadHistoryEntry>): String =
        JSONArray()
            .apply {
                entries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("id", entry.id)
                            put("occasionName", entry.occasionName)
                            put("timestampMillis", entry.timestampMillis)
                            put("photoCount", entry.photoCount)
                            put("failedCount", entry.failedCount)
                        },
                    )
                }
            }.toString()

    private fun isValidEntry(entry: UploadHistoryEntry): Boolean =
        entry.photoCount >= 0 && entry.failedCount in 0..entry.photoCount
}
