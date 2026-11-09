package com.satori.data.finishing

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.repository.FinishingModelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A damaged file switches the finishing model back to its default instead of failing every Stop.
 * Internal so the tests open their DataStore with the same handler.
 */
internal val finishingModelCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { emptyPreferences() }

// Its own file (files/datastore/finishing_model.preferences_pb), apart from app_settings. Like every
// file of the app it is kept out of backups and device transfer (data_extraction_rules.xml).
private val Context.finishingModelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "finishing_model",
    corruptionHandler = finishingModelCorruptionHandler,
)

/**
 * [FinishingModelRepository] in a Preferences DataStore. The file of Gemma 4 E2B is the one its
 * [com.satori.data.config.ModelConfig] names, in the models folder.
 *
 * @property modelFile The path of the E2B file when it is on the phone (there and not empty), else
 *   null; it reads the disk and is called off the main thread.
 */
@Singleton
class DataStoreFinishingModelRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val modelFile: () -> String?,
) : FinishingModelRepository {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        modelDownloadManager: ModelDownloadManager,
    ) : this(
        context.finishingModelDataStore,
        { modelDownloadManager.getLlmModelPath(modelConfigForVariant(LlmModelVariant.GEMMA4_E2B_Q4_0).llmFilename) },
    )

    override fun observeEnabled(): Flow<Boolean> = dataStore.data
        .catch { e ->
            // A file that can't be read leaves the choice at its default.
            if (e !is IOException) throw e
            Log.w(TAG, "Finishing model choice unreadable, using the default", e)
            emit(emptyPreferences())
        }
        .map { prefs -> prefs[Keys.ENABLED] ?: DEFAULT_ENABLED }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }

    override suspend fun enabledModelPath(): String? {
        if (!observeEnabled().first()) return null
        return withContext(Dispatchers.IO) { modelFile() }
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("gemma4_e2b_after_stop")
    }

    internal companion object {
        /** On until switched off: once its file is on the phone, the better model does the work after Stop. */
        const val DEFAULT_ENABLED = true

        private const val TAG = "SatoriFinishingModel"
    }
}
