package com.satori.domain.repository

import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordingLanguage
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for application settings persistence.
 */
interface SettingsRepository {
    /**
     * Get settings as a Flow that emits whenever settings change.
     */
    fun getSettings(): Flow<AppSettings>

    /**
     * Update application settings.
     *
     * The system prompts are not stored: [getSettings] always gives the app's own, in the app
     * language, so a new version's prompts apply at once. [AppSettings.memoryConstrainedDetected],
     * [AppSettings.conservativeThreadsCharThreshold], [AppSettings.homeCategoryId] and
     * [AppSettings.homeLanguage] are not written here; use their setters.
     *
     * @param settings New settings to persist
     */
    suspend fun updateSettings(settings: AppSettings)

    /**
     * Stores what the one-tap Record on the home screen records as next: a category id, or
     * [com.satori.domain.model.RecordTargets.TRANSLATE_ID].
     */
    suspend fun setHomeCategoryId(id: String)

    /**
     * Stores the language the one-tap Record on the home screen records in next
     * ([AppSettings.homeLanguage]). The default stores nothing, for a repository that keeps no
     * choice of the home screen.
     */
    suspend fun setHomeLanguage(language: RecordingLanguage) {}

    /**
     * Persists the result of the post-load memory check, so the next launch starts with the
     * thread count it calls for.
     */
    suspend fun setMemoryConstrainedDetected(detected: Boolean)

    /**
     * Lowers the persisted input size from which conservative threads are used. A value at or
     * above the stored one is ignored, so the threshold only ever converges downward.
     */
    suspend fun lowerConservativeThreadsCharThreshold(chars: Int)

    /**
     * Forgets the stored AI model path, for when the last AI model was deleted. [updateSettings]
     * cannot do it: it keeps the stored path when it is given a blank one. Readers that only
     * check the path for blank then report that no model is installed.
     */
    suspend fun clearLlmModelPath()
}
