package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal const val UPLOADED_PHOTOS_DATASTORE_NAME = "uploaded_photos"

// An unreadable file means an empty set: markers are a convenience, losing them is safe.
// The handler is stateless, so the same instance serves the delegate and the tests.
internal val uploadedPhotosCorruptionHandler = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

private val Context.uploadedPhotosDataStore: DataStore<Preferences> by preferencesDataStore(
    name = UPLOADED_PHOTOS_DATASTORE_NAME,
    corruptionHandler = uploadedPhotosCorruptionHandler,
)

/**
 * Device-local MediaStore ids of photos that were uploaded. The file is excluded from backup and
 * device transfer because the ids are only meaningful on the device that produced them.
 */
class UploadedPhotosStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : UploadedPhotosRepositoryContract {
    /** Production constructor using the process-wide DataStore (the internal one is for tests). */
    constructor(context: Context) : this(context.applicationContext.uploadedPhotosDataStore)

    private companion object {
        val KEY_UPLOADED_IDS = stringSetPreferencesKey("uploaded_ids")
    }

    override val uploadedPhotoIds: Flow<Set<Long>> =
        dataStore.data.map { prefs ->
            // Invalid (non-Long) string entries are silently skipped to guard against
            // data corruption or future ID-type changes (e.g., Long → UUID migration)
            // without crashing the app.
            prefs[KEY_UPLOADED_IDS]
                ?.mapNotNull { it.toLongOrNull() }
                ?.toSet()
                ?: emptySet()
        }

    override suspend fun markAsUploaded(photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        dataStore.edit { prefs ->
            val existing = prefs[KEY_UPLOADED_IDS] ?: emptySet()
            prefs[KEY_UPLOADED_IDS] = existing + photoIds.map { it.toString() }
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_UPLOADED_IDS)
        }
    }
}
