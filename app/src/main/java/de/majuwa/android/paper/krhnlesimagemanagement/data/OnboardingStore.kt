package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

internal const val APP_PREFERENCES_NAME = "app_preferences"

private val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = APP_PREFERENCES_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Boolean `onboarding_completed` in its own DataStore; contains nothing sensitive. */
class OnboardingStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : OnboardingRepository {
    constructor(context: Context) : this(context.applicationContext.appPreferencesDataStore)

    private companion object {
        val KEY_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    override val completed: Flow<Boolean> =
        dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it[KEY_COMPLETED] ?: false }

    override suspend fun markCompleted() {
        dataStore.edit { it[KEY_COMPLETED] = true }
    }
}
