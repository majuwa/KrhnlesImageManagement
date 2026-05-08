package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
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
    private companion object {
        val KEY_UPLOAD_HISTORY = stringPreferencesKey("upload_history_entries")
    }

    override val entries: Flow<List<UploadHistoryEntry>> =
        context.uploadHistoryDataStore.data.map { prefs ->
            decodeEntries(prefs[KEY_UPLOAD_HISTORY]).sortedByDescending { it.timestampMillis }
        }

    override suspend fun addEntry(entry: UploadHistoryEntry) {
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
                    add(
                        UploadHistoryEntry(
                            id = item.getLong("id"),
                            occasionName = item.getString("occasionName"),
                            timestampMillis = item.getLong("timestampMillis"),
                            photoCount = item.getInt("photoCount"),
                            failedCount = item.getInt("failedCount"),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
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
}
