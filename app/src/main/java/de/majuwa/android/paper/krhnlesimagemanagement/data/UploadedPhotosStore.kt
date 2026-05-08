package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uploadedPhotosDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "uploaded_photos",
)

class UploadedPhotosStore(
    private val context: Context,
) : UploadedPhotosRepositoryContract {
    private companion object {
        val KEY_UPLOADED_IDS = stringSetPreferencesKey("uploaded_ids")
    }

    override val uploadedPhotoIds: Flow<Set<Long>> =
        context.uploadedPhotosDataStore.data.map { prefs ->
            prefs[KEY_UPLOADED_IDS]
                ?.mapNotNull { it.toLongOrNull() }
                ?.toSet()
                ?: emptySet()
        }

    override suspend fun markAsUploaded(photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        context.uploadedPhotosDataStore.edit { prefs ->
            val existing = prefs[KEY_UPLOADED_IDS] ?: emptySet()
            prefs[KEY_UPLOADED_IDS] = existing + photoIds.map { it.toString() }
        }
    }

    override suspend fun clear() {
        context.uploadedPhotosDataStore.edit { prefs ->
            prefs.remove(KEY_UPLOADED_IDS)
        }
    }
}
