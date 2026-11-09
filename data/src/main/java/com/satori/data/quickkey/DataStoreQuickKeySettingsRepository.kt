package com.satori.data.quickkey

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.satori.domain.model.RecordingLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A damaged file resets the quick key to its defaults instead of failing every press. Internal
 * so the tests open their DataStore with the same handler.
 */
internal val quickKeyCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { emptyPreferences() }

// Its own file (files/datastore/quick_key.preferences_pb), apart from app_settings. Like every
// file of the app it is kept out of backups and device transfer (data_extraction_rules.xml).
private val Context.quickKeyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quick_key",
    corruptionHandler = quickKeyCorruptionHandler,
)

/** [QuickKeySettingsRepository] in a Preferences DataStore. */
@Singleton
class DataStoreQuickKeySettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) : QuickKeySettingsRepository {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.quickKeyDataStore)

    override fun observe(): Flow<QuickKeySettings> = dataStore.data
        .catch { e ->
            // A file that can't be read leaves the key working with the defaults.
            if (e !is IOException) throw e
            Log.w(TAG, "Quick key settings unreadable, using the defaults", e)
            emit(emptyPreferences())
        }
        .map { prefs ->
            QuickKeySettings(
                categoryId = prefs[Keys.CATEGORY_ID]?.takeIf { it.isNotBlank() }
                    ?: QuickKeySettings.DEFAULT_CATEGORY_ID,
                stopOnSecondPress = prefs[Keys.STOP_ON_SECOND_PRESS]
                    ?: QuickKeySettings.DEFAULT_STOP_ON_SECOND_PRESS,
                // A code this version doesn't know reads as English or German.
                language = RecordingLanguage.fromCode(prefs[Keys.LANGUAGE]),
                lastPressAtMs = prefs[Keys.LAST_PRESS_AT_MS],
                setUpByHand = prefs[Keys.SET_UP_BY_HAND] ?: false,
            )
        }

    override suspend fun setCategoryId(categoryId: String) {
        require(categoryId.isNotBlank()) { "categoryId must not be blank" }
        dataStore.edit { it[Keys.CATEGORY_ID] = categoryId }
    }

    override suspend fun setStopOnSecondPress(stop: Boolean) {
        dataStore.edit { it[Keys.STOP_ON_SECOND_PRESS] = stop }
    }

    override suspend fun setLanguage(language: RecordingLanguage) {
        dataStore.edit { prefs ->
            val code = language.code
            if (code == null) prefs.remove(Keys.LANGUAGE) else prefs[Keys.LANGUAGE] = code
        }
    }

    override suspend fun recordPress(atMs: Long) {
        dataStore.edit { it[Keys.LAST_PRESS_AT_MS] = atMs }
    }

    override suspend fun markSetUp() {
        dataStore.edit { it[Keys.SET_UP_BY_HAND] = true }
    }

    private object Keys {
        val CATEGORY_ID = stringPreferencesKey("category_id")
        val STOP_ON_SECOND_PRESS = booleanPreferencesKey("stop_on_second_press")
        // A RecordingLanguage code; absent for English or German.
        val LANGUAGE = stringPreferencesKey("language")
        // When the quick key activity last forwarded a press (wall clock ms), and whether the owner
        // said the key is set up: two of the signals that it reaches Satori.
        val LAST_PRESS_AT_MS = longPreferencesKey("last_press_at_ms")
        val SET_UP_BY_HAND = booleanPreferencesKey("set_up_by_hand")
    }

    private companion object {
        const val TAG = "SatoriQuickKey"
    }
}
