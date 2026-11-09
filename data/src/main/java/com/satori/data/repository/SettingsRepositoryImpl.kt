package com.satori.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.satori.domain.model.AppSettings
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.MicSource
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.ThemeMode
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// Internal so the tests can seed and read the file the repository works on.
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Implementation of SettingsRepository using DataStore for persistence.
 *
 * @property context Android context for DataStore access
 * @property resourceProvider Provider for accessing localized string resources
 */
class SettingsRepositoryImpl @Inject constructor(
    private val context: Context,
    private val resourceProvider: ResourceProvider
) : SettingsRepository {

    private object Keys {
        // Mode-specific intervals
        val SIMPLE_LISTENING_INTERVAL = intPreferencesKey("simple_listening_interval_seconds")
        val SHORT_MEETING_INTERVAL = intPreferencesKey("short_meeting_interval_seconds")
        val LONG_MEETING_INTERVAL = intPreferencesKey("long_meeting_interval_minutes")
        val TRANSLATION_INTERVAL = intPreferencesKey("translation_interval_seconds")

        // The system prompts earlier versions stored for each mode, the user's own or a copy of
        // the shipped one. Never read: a stored copy would keep the old prompt after an update.
        // updateSettings removes them.
        val OLD_PROMPTS = listOf(
            stringPreferencesKey("simple_listening_system_prompt"),
            stringPreferencesKey("short_meeting_system_prompt"),
            stringPreferencesKey("long_meeting_system_prompt"),
            stringPreferencesKey("translation_system_prompt")
        )

        // Translation settings
        val TRANSLATION_TARGET_LANGUAGE = stringPreferencesKey("translation_target_language")

        // Language of the AI notes: a NotesLanguage name
        val NOTES_LANGUAGE = stringPreferencesKey("notes_language")

        // What the one-tap Record on home records as: a category id or "translate", written only
        // by setHomeCategoryId
        val HOME_CATEGORY_ID = stringPreferencesKey("home_category_id")

        // And the language it records in: a RecordingLanguage code, absent for English or German,
        // written only by setHomeLanguage
        val HOME_LANGUAGE = stringPreferencesKey("home_language")

        // General settings
        val LLM_MODEL_PATH = stringPreferencesKey("llm_model_path")
        val AUTO_START_LLM = booleanPreferencesKey("auto_start_llm")
        val THEME_MODE = stringPreferencesKey("theme_mode")

        // Recording: the buzz on start and stop, and the screen kept on while the microphone is live
        val VIBRATE_ON_START_STOP = booleanPreferencesKey("vibrate_on_start_stop")
        val KEEP_SCREEN_ON_WHILE_RECORDING = booleanPreferencesKey("keep_screen_on_while_recording")
        // Which defaults the recording values were stored under: RECORDING_DEFAULTS_REVISION, or
        // absent for the earlier versions, whose screen default was off (see keepScreenOn)
        val RECORDING_DEFAULTS = intPreferencesKey("recording_defaults")
        // Whether recordings keep their audio
        val KEEP_AUDIO = booleanPreferencesKey("keep_audio")
        // After how many days older audio is compressed and deleted; 0 for never
        val AUDIO_COMPRESS_AFTER_DAYS = intPreferencesKey("audio_compress_after_days")
        val AUDIO_DELETE_AFTER_DAYS = intPreferencesKey("audio_delete_after_days")
        // Whether a recording stores where it starts
        val SAVE_PLACE = booleanPreferencesKey("save_place")

        // VAD parameters
        val VAD_MIN_SILENCE_DURATION = floatPreferencesKey("vad_min_silence_duration")
        val VAD_MAX_SPEECH_DURATION = floatPreferencesKey("vad_max_speech_duration")
        val VAD_THRESHOLD = floatPreferencesKey("vad_threshold")
        // Which defaults the VAD values were stored under: VAD_DEFAULTS_REVISION, or absent for
        // the earlier versions, whose defaults were the OLD_VAD_ ones (see vadValue)
        val VAD_DEFAULTS = intPreferencesKey("vad_defaults")

        // Speech: the microphone input (a MicSource name) and the model that transcribes again
        // after Stop (a SpeechModelId id; absent for none)
        val MIC_SOURCE = stringPreferencesKey("mic_source")
        val TRANSCRIBE_AFTER_STOP = stringPreferencesKey("transcribe_after_stop")

        // Per-mode default insight strategy
        val SIMPLE_LISTENING_DEFAULT_STRATEGY = stringPreferencesKey("simple_listening_default_strategy")
        val SHORT_MEETING_DEFAULT_STRATEGY = stringPreferencesKey("short_meeting_default_strategy")
        val LONG_MEETING_DEFAULT_STRATEGY = stringPreferencesKey("long_meeting_default_strategy")
        val TRANSLATION_DEFAULT_STRATEGY = stringPreferencesKey("translation_default_strategy")

        // LLM model variant selection (Q8_0 / IQ4_NL)
        val LLM_MODEL_VARIANT = stringPreferencesKey("llm_model_variant")


        // Adaptive conservative-threads state, written only by its dedicated setters
        val MEMORY_CONSTRAINED_DETECTED = booleanPreferencesKey("memory_constrained_detected")
        val CONSERVATIVE_THREADS_CHAR_THRESHOLD = intPreferencesKey("conservative_threads_char_threshold")
    }

    private companion object {
        /** What [Keys.VAD_DEFAULTS] holds once this version has stored the VAD values. */
        const val VAD_DEFAULTS_REVISION = 2

        // The VAD defaults of the earlier versions, which stored the values without Keys.VAD_DEFAULTS
        const val OLD_VAD_MIN_SILENCE_DURATION = 0.5F
        const val OLD_VAD_MAX_SPEECH_DURATION = 10.0F
        const val OLD_VAD_THRESHOLD = 0.5F

        const val TAG = "SatoriSettings"

        /** What [Keys.RECORDING_DEFAULTS] holds once this version has stored the recording values. */
        const val RECORDING_DEFAULTS_REVISION = 1

        // The screen default of the earlier versions, which stored it without Keys.RECORDING_DEFAULTS
        const val OLD_KEEP_SCREEN_ON = false

        /** What a retention key holds for never. */
        const val NEVER_DAYS = 0
    }

    override fun getSettings(): Flow<AppSettings> {
        return context.dataStore.data.map { preferences ->
            val defaults = AppSettings() // Use defaults from data class

            AppSettings(
                // Mode-specific intervals
                simpleListeningIntervalSeconds = preferences[Keys.SIMPLE_LISTENING_INTERVAL]
                    ?: defaults.simpleListeningIntervalSeconds,
                shortMeetingIntervalSeconds = preferences[Keys.SHORT_MEETING_INTERVAL]
                    ?: defaults.shortMeetingIntervalSeconds,
                longMeetingIntervalMinutes = (preferences[Keys.LONG_MEETING_INTERVAL]
                    ?: defaults.longMeetingIntervalMinutes).let { stored ->
                    // Valid set: 3, 5, 10 minutes (UI options).
                    // Migrate any out-of-range stored value to the default.
                    if (stored in listOf(3, 5, 10)) stored else defaults.longMeetingIntervalMinutes
                },
                translationIntervalSeconds = preferences[Keys.TRANSLATION_INTERVAL]
                    ?: defaults.translationIntervalSeconds,

                // Mode-specific system prompts: always the shipped ones, in the current app language
                simpleListeningSystemPrompt = resourceProvider.getSimpleListeningPrompt(),
                shortMeetingSystemPrompt = resourceProvider.getShortMeetingPrompt(),
                longMeetingSystemPrompt = resourceProvider.getLongMeetingPrompt(),
                translationSystemPrompt = resourceProvider.getTranslationPrompt(),

                // Translation settings
                translationTargetLanguage = preferences[Keys.TRANSLATION_TARGET_LANGUAGE]
                    ?: defaults.translationTargetLanguage,
                notesLanguage = NotesLanguage.fromName(preferences[Keys.NOTES_LANGUAGE]),
                homeCategoryId = preferences[Keys.HOME_CATEGORY_ID]?.takeIf { it.isNotBlank() }
                    ?: defaults.homeCategoryId,
                homeLanguage = RecordingLanguage.fromCode(preferences[Keys.HOME_LANGUAGE]),

                // General settings
                llmModelPath = preferences[Keys.LLM_MODEL_PATH] ?: defaults.llmModelPath,
                llmEnabled = preferences[Keys.AUTO_START_LLM] ?: defaults.llmEnabled,
                themeMode = try {
                    ThemeMode.valueOf(preferences[Keys.THEME_MODE] ?: "SYSTEM")
                } catch (e: IllegalArgumentException) {
                    defaults.themeMode
                },

                // Recording
                vibrateOnStartStop = preferences[Keys.VIBRATE_ON_START_STOP] ?: defaults.vibrateOnStartStop,
                keepScreenOnWhileRecording = keepScreenOn(preferences, defaults.keepScreenOnWhileRecording),
                keepAudio = preferences[Keys.KEEP_AUDIO] ?: defaults.keepAudio,
                audioCompressAfterDays = days(preferences[Keys.AUDIO_COMPRESS_AFTER_DAYS], AppSettings.AUDIO_COMPRESS_CHOICES),
                audioDeleteAfterDays = days(preferences[Keys.AUDIO_DELETE_AFTER_DAYS], AppSettings.AUDIO_DELETE_CHOICES),
                savePlace = preferences[Keys.SAVE_PLACE] ?: defaults.savePlace,

                // VAD parameters
                vadMinSilenceDuration = vadValue(
                    preferences, Keys.VAD_MIN_SILENCE_DURATION, OLD_VAD_MIN_SILENCE_DURATION, defaults.vadMinSilenceDuration
                ),
                vadMaxSpeechDuration = vadValue(
                    preferences, Keys.VAD_MAX_SPEECH_DURATION, OLD_VAD_MAX_SPEECH_DURATION, defaults.vadMaxSpeechDuration
                ),
                vadThreshold = vadValue(preferences, Keys.VAD_THRESHOLD, OLD_VAD_THRESHOLD, defaults.vadThreshold),

                // Speech; an input or a model this version doesn't know reads as the default or none.
                // A stored input stays as it was chosen, as long as Settings still offers it: the
                // speech recognition input of the earlier default reads as the usual one
                // (MicSource.offered), and the next write stores that. An unset one reads as
                // today's default.
                micSource = preferences[Keys.MIC_SOURCE]
                    ?.let { stored -> MicSource.entries.firstOrNull { it.name == stored } }
                    ?.let(::offeredMicSource)
                    ?: defaults.micSource,
                transcribeAfterStop = SpeechModelId.fromId(preferences[Keys.TRANSCRIBE_AFTER_STOP]),

                // LLM model variant
                llmModelVariant = try {
                    LlmModelVariant.valueOf(
                        preferences[Keys.LLM_MODEL_VARIANT] ?: LlmModelVariant.Q8_0.name
                    )
                } catch (e: IllegalArgumentException) {
                    LlmModelVariant.Q8_0
                },


                // Adaptive conservative-threads state
                memoryConstrainedDetected = preferences[Keys.MEMORY_CONSTRAINED_DETECTED]
                    ?: defaults.memoryConstrainedDetected,
                conservativeThreadsCharThreshold = preferences[Keys.CONSERVATIVE_THREADS_CHAR_THRESHOLD]
                    ?: defaults.conservativeThreadsCharThreshold,

                // Per-mode default insight strategy
                simpleListeningDefaultStrategy = parseStrategy(
                    preferences[Keys.SIMPLE_LISTENING_DEFAULT_STRATEGY],
                    InsightStrategy.REAL_TIME
                ),
                shortMeetingDefaultStrategy = parseStrategy(
                    preferences[Keys.SHORT_MEETING_DEFAULT_STRATEGY],
                    InsightStrategy.REAL_TIME
                ),
                longMeetingDefaultStrategy = parseStrategy(
                    preferences[Keys.LONG_MEETING_DEFAULT_STRATEGY],
                    InsightStrategy.REAL_TIME
                ),
                translationDefaultStrategy = parseStrategy(
                    preferences[Keys.TRANSLATION_DEFAULT_STRATEGY],
                    InsightStrategy.REAL_TIME
                )
            )
        }
    }

    private fun parseStrategy(stored: String?, default: InsightStrategy): InsightStrategy {
        return try {
            if (stored != null) InsightStrategy.valueOf(stored) else default
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    /**
     * The VAD value stored under [key], or [default] when none is. A value an earlier version
     * stored (no [Keys.VAD_DEFAULTS]) that equals its default then, [oldDefault], reads as
     * [default] too: those versions stored every setting at each change, and the owner never
     * changed voice detection. Once this version has stored the values they read as stored, so
     * the old defaults can be chosen again.
     */
    private fun vadValue(preferences: Preferences, key: Preferences.Key<Float>, oldDefault: Float, default: Float): Float {
        val stored = preferences[key] ?: return default
        val storedByAnEarlierVersion = preferences[Keys.VAD_DEFAULTS] == null
        return if (storedByAnEarlierVersion && stored == oldDefault) default else stored
    }

    /**
     * Whether the screen stays on while recording, as stored, or [default] when nothing is. Off
     * stored by an earlier version (no [Keys.RECORDING_DEFAULTS]) reads as [default] too: off was
     * its default, and it stored every setting at each change, so the owner never chose it. Once
     * this version has stored the value it reads as stored, so off can be chosen again.
     */
    private fun keepScreenOn(preferences: Preferences, default: Boolean): Boolean {
        val stored = preferences[Keys.KEEP_SCREEN_ON_WHILE_RECORDING] ?: return default
        val storedByAnEarlierVersion = preferences[Keys.RECORDING_DEFAULTS] == null
        return if (storedByAnEarlierVersion && stored == OLD_KEEP_SCREEN_ON) default else stored
    }

    // Whether the line on a stored input Settings no longer offers was written in this process.
    private val micSourceMigrationLogged = AtomicBoolean(false)

    /**
     * The input [stored] records with ([MicSource.offered]). A stored input Settings no longer
     * offers is said once in the log, where it is read as the usual one; the next write of the
     * settings stores that.
     */
    private fun offeredMicSource(stored: MicSource): MicSource {
        val offered = MicSource.offered(stored)
        if (offered != stored && micSourceMigrationLogged.compareAndSet(false, true)) {
            Log.i(TAG, "Microphone input ${stored.name} is no longer offered: recordings use ${offered.name}")
        }
        return offered
    }

    /** A number of days stored under a retention key: one of [choices], or null for never, nothing stored or another number. */
    private fun days(stored: Int?, choices: List<Int?>): Int? = stored?.takeIf { it != NEVER_DAYS && it in choices }

    override suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit { preferences ->
            // Mode-specific intervals
            preferences[Keys.SIMPLE_LISTENING_INTERVAL] = settings.simpleListeningIntervalSeconds
            preferences[Keys.SHORT_MEETING_INTERVAL] = settings.shortMeetingIntervalSeconds
            preferences[Keys.LONG_MEETING_INTERVAL] = settings.longMeetingIntervalMinutes
            preferences[Keys.TRANSLATION_INTERVAL] = settings.translationIntervalSeconds

            // The system prompts are not settings: what an earlier version stored goes.
            Keys.OLD_PROMPTS.forEach { preferences.remove(it) }

            // Translation settings
            preferences[Keys.TRANSLATION_TARGET_LANGUAGE] = settings.translationTargetLanguage
            preferences[Keys.NOTES_LANGUAGE] = settings.notesLanguage.name

            // General settings
            if (settings.llmModelPath.isNotBlank()) {
                preferences[Keys.LLM_MODEL_PATH] = settings.llmModelPath
            }
            preferences[Keys.AUTO_START_LLM] = settings.llmEnabled
            preferences[Keys.THEME_MODE] = settings.themeMode.name
            preferences[Keys.KEEP_AUDIO] = settings.keepAudio

            // Recording, marked as stored by this version
            preferences[Keys.VIBRATE_ON_START_STOP] = settings.vibrateOnStartStop
            preferences[Keys.KEEP_SCREEN_ON_WHILE_RECORDING] = settings.keepScreenOnWhileRecording
            preferences[Keys.RECORDING_DEFAULTS] = RECORDING_DEFAULTS_REVISION
            preferences[Keys.SAVE_PLACE] = settings.savePlace

            // Older audio
            preferences[Keys.AUDIO_COMPRESS_AFTER_DAYS] = settings.audioCompressAfterDays ?: NEVER_DAYS
            preferences[Keys.AUDIO_DELETE_AFTER_DAYS] = settings.audioDeleteAfterDays ?: NEVER_DAYS

            // VAD parameters, marked as stored by this version
            preferences[Keys.VAD_MIN_SILENCE_DURATION] = settings.vadMinSilenceDuration
            preferences[Keys.VAD_MAX_SPEECH_DURATION] = settings.vadMaxSpeechDuration
            preferences[Keys.VAD_THRESHOLD] = settings.vadThreshold
            preferences[Keys.VAD_DEFAULTS] = VAD_DEFAULTS_REVISION

            // Speech
            preferences[Keys.MIC_SOURCE] = settings.micSource.name
            val afterStop = settings.transcribeAfterStop
            if (afterStop != null) {
                preferences[Keys.TRANSCRIBE_AFTER_STOP] = afterStop.id
            } else {
                preferences.remove(Keys.TRANSCRIBE_AFTER_STOP)
            }

            // LLM model variant
            preferences[Keys.LLM_MODEL_VARIANT] = settings.llmModelVariant.name

            // Per-mode default insight strategy
            preferences[Keys.SIMPLE_LISTENING_DEFAULT_STRATEGY] = settings.simpleListeningDefaultStrategy.name
            preferences[Keys.SHORT_MEETING_DEFAULT_STRATEGY] = settings.shortMeetingDefaultStrategy.name
            preferences[Keys.LONG_MEETING_DEFAULT_STRATEGY] = settings.longMeetingDefaultStrategy.name
            preferences[Keys.TRANSLATION_DEFAULT_STRATEGY] = settings.translationDefaultStrategy.name
        }
    }

    override suspend fun setHomeCategoryId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.HOME_CATEGORY_ID] = id
        }
    }

    override suspend fun setHomeLanguage(language: RecordingLanguage) {
        context.dataStore.edit { preferences ->
            val code = language.code
            if (code == null) preferences.remove(Keys.HOME_LANGUAGE) else preferences[Keys.HOME_LANGUAGE] = code
        }
    }

    override suspend fun setMemoryConstrainedDetected(detected: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.MEMORY_CONSTRAINED_DETECTED] = detected
        }
    }

    override suspend fun lowerConservativeThreadsCharThreshold(chars: Int) {
        // Read and write in one edit, so two inferences finishing together can't raise it again.
        context.dataStore.edit { preferences ->
            val stored = preferences[Keys.CONSERVATIVE_THREADS_CHAR_THRESHOLD] ?: Int.MAX_VALUE
            if (chars < stored) preferences[Keys.CONSERVATIVE_THREADS_CHAR_THRESHOLD] = chars
        }
    }

    override suspend fun clearLlmModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(Keys.LLM_MODEL_PATH)
        }
    }
}
