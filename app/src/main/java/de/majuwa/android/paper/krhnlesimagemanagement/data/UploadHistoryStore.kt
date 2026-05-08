package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.uploadHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "upload_history",
)

class UploadHistoryStore(
    private val context: Context,
) : UploadHistoryRepository {
    private val tag = "UploadHistoryStore"

    private companion object {
        val KEY_UPLOAD_HISTORY = stringPreferencesKey("upload_history_entries")
    }

    override val entries: Flow<List<UploadHistoryEntry>> =
        context.uploadHistoryDataStore.data.map { prefs ->
            decodeEntries(prefs[KEY_UPLOAD_HISTORY]).sortedByDescending { it.timestampMillis }
        }

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        require(isValidEntry(entry)) { "failedCount must be between 0 and total photo count, and photo count must be non-negative" }
        context.uploadHistoryDataStore.edit { prefs ->
            val updated = decodeEntries(prefs[KEY_UPLOAD_HISTORY]).toMutableList().apply { add(entry) }
            prefs[KEY_UPLOAD_HISTORY] = encodeEntries(updated)
        }
    }

    override suspend fun removeEntry(entryId: Long) {
        context.uploadHistoryDataStore.edit { prefs ->
            val updated = decodeEntries(prefs[KEY_UPLOAD_HISTORY]).filterNot { it.id == entryId }
            prefs[KEY_UPLOAD_HISTORY] = encodeEntries(updated)
        }
    }

    override suspend fun clearAll() {
        context.uploadHistoryDataStore.edit { prefs ->
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
        }.getOrElse { throwable ->
            Log.e(tag, "Failed to parse upload history entries", throwable)
            emptyList()
        }
    }

    private fun encodeEntries(entries: List<UploadHistoryEntry>): String =
        JSONArray().apply {
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
