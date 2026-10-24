package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Booleans in the app preferences (excluded from backup like the onboarding flag):
 * `video_access_requested` is set once the app asked an images-only user for video access, so the
 * upgrade request happens at most once; `video_rationale_shown` is set once
 * `shouldShowRequestPermissionRationale(READ_MEDIA_VIDEO)` was true after a denial, so a later
 * denial without rationale counts as permanent (open the app settings).
 */
class MediaPermissionStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : MediaPermissionRepository {
    constructor(context: Context) : this(context.applicationContext.appPreferencesDataStore)

    private companion object {
        val KEY_VIDEO_ACCESS_REQUESTED = booleanPreferencesKey("video_access_requested")
        val KEY_VIDEO_RATIONALE_SHOWN = booleanPreferencesKey("video_rationale_shown")
    }

    private fun flag(key: Preferences.Key<Boolean>): Flow<Boolean> =
        dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it[key] ?: false }

    override val videoAccessRequested: Flow<Boolean> = flag(KEY_VIDEO_ACCESS_REQUESTED)

    override suspend fun markVideoAccessRequested() {
        dataStore.edit { it[KEY_VIDEO_ACCESS_REQUESTED] = true }
    }

    override val videoRationaleShown: Flow<Boolean> = flag(KEY_VIDEO_RATIONALE_SHOWN)

    override suspend fun markVideoRationaleShown() {
        dataStore.edit { it[KEY_VIDEO_RATIONALE_SHOWN] = true }
    }
}
