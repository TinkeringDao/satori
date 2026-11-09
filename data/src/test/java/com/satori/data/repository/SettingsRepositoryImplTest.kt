package com.satori.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.satori.domain.model.AppSettings
import com.satori.domain.model.MicSource
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.provider.ResourceProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

/**
 * The AI's prompts are the app's own: the settings never read the prompts an earlier version
 * stored, and the next write removes them, so an update's new prompts apply at once.
 *
 * Voice detection an earlier version stored at its defaults then (0.5 / 0.5 / 10 s) reads as
 * today's (0.4 / 0.5 / 20 s), value by value, until this version stores the values; after that
 * they read as stored. The same goes for the screen kept on while recording, whose default was
 * off before. The microphone input, the transcript after Stop, the place setting and the ages of
 * older audio are stored under the keys and values later versions read. An unset microphone input
 * reads as the phone's usual one; a stored one stays as stored while Settings offers it, so the
 * earlier default, the speech recognition input, reads as the usual one.
 *
 * Runs against the app's real settings file, opened as the app opens it. That store is one per
 * process (preferencesDataStore), so the tests share it in one folder and each starts it empty.
 */
class SettingsRepositoryImplTest {

    companion object {
        @get:ClassRule
        @JvmStatic
        val folder = TemporaryFolder()

        // The keys an earlier version stored a prompt under, a copy of the shipped one or the user's own.
        private val oldPrompts = mapOf(
            "simple_listening_system_prompt" to "Summarise the voice note in one paragraph.",
            "short_meeting_system_prompt" to "Summarise the meeting and list the tasks.",
            "long_meeting_system_prompt" to "Summarise the long meeting.",
            "translation_system_prompt" to "Translate everything into {target_language}, politely."
        )
    }

    private val context = FilesContext(folder.root)
    private val repository = SettingsRepositoryImpl(context, shippedResources())

    @Before
    fun startEmpty() = runBlocking {
        context.dataStore.edit { it.clear() }
        Unit
    }

    private suspend fun storeOldPrompts() {
        context.dataStore.edit { preferences ->
            oldPrompts.forEach { (key, prompt) -> preferences[stringPreferencesKey(key)] = prompt }
        }
    }

    private suspend fun storedKeys(): Set<String> = context.dataStore.data.first().asMap().keys.map { it.name }.toSet()

    @Test
    fun `the prompts an earlier version stored are not read`() = runBlocking {
        storeOldPrompts()

        val settings = repository.getSettings().first()

        assertEquals("shipped voice note prompt", settings.simpleListeningSystemPrompt)
        assertEquals("shipped short meeting prompt", settings.shortMeetingSystemPrompt)
        assertEquals("shipped long meeting prompt", settings.longMeetingSystemPrompt)
        assertEquals("shipped translation prompt", settings.translationSystemPrompt)
    }

    @Test
    fun `the next write removes them and keeps every other setting`() = runBlocking {
        storeOldPrompts()
        context.dataStore.edit { it[intPreferencesKey("simple_listening_interval_seconds")] = 45 }

        repository.updateSettings(repository.getSettings().first().copy(shortMeetingIntervalSeconds = 90))

        assertTrue(storedKeys().intersect(oldPrompts.keys).isEmpty())
        val stored = context.dataStore.data.first()
        assertEquals(45, stored[intPreferencesKey("simple_listening_interval_seconds")])
        assertEquals(90, stored[intPreferencesKey("short_meeting_interval_seconds")])
    }

    @Test
    fun `a prompt in the settings written is not stored`() = runBlocking {
        repository.updateSettings(
            AppSettings(
                simpleListeningSystemPrompt = "Answer in rhymes.",
                translationSystemPrompt = "Translate loosely."
            )
        )

        assertTrue(storedKeys().intersect(oldPrompts.keys).isEmpty())
        val settings = repository.getSettings().first()
        assertEquals("shipped voice note prompt", settings.simpleListeningSystemPrompt)
        assertEquals("shipped translation prompt", settings.translationSystemPrompt)
    }

    // ========== Voice detection ==========

    /** Threshold, minimum silence and maximum speech, in that order. */
    private fun AppSettings.vad() = Triple(vadThreshold, vadMinSilenceDuration, vadMaxSpeechDuration)

    /** Stores the three values as an earlier version did: under their keys, with nothing else. */
    private suspend fun storeVadAsAnEarlierVersion(threshold: Float, minSilence: Float, maxSpeech: Float) {
        context.dataStore.edit { preferences ->
            preferences[floatPreferencesKey("vad_threshold")] = threshold
            preferences[floatPreferencesKey("vad_min_silence_duration")] = minSilence
            preferences[floatPreferencesKey("vad_max_speech_duration")] = maxSpeech
        }
    }

    @Test
    fun `nothing stored reads as today's voice detection`() = runBlocking {
        assertEquals(Triple(0.4F, 0.5F, 20.0F), repository.getSettings().first().vad())
    }

    @Test
    fun `an earlier version's defaults read as today's`() = runBlocking {
        storeVadAsAnEarlierVersion(threshold = 0.5F, minSilence = 0.5F, maxSpeech = 10.0F)

        assertEquals(Triple(0.4F, 0.5F, 20.0F), repository.getSettings().first().vad())
    }

    @Test
    fun `values changed in an earlier version are kept, each on its own`() = runBlocking {
        storeVadAsAnEarlierVersion(threshold = 0.6F, minSilence = 0.8F, maxSpeech = 15.0F)
        assertEquals(Triple(0.6F, 0.8F, 15.0F), repository.getSettings().first().vad())

        storeVadAsAnEarlierVersion(threshold = 0.5F, minSilence = 0.7F, maxSpeech = 10.0F)
        assertEquals(Triple(0.4F, 0.7F, 20.0F), repository.getSettings().first().vad())
    }

    @Test
    fun `the next write stores today's defaults in place of the old ones`() = runBlocking {
        storeVadAsAnEarlierVersion(threshold = 0.5F, minSilence = 0.5F, maxSpeech = 10.0F)

        repository.updateSettings(repository.getSettings().first().copy(keepAudio = false))

        val stored = context.dataStore.data.first()
        assertEquals(0.4F, stored[floatPreferencesKey("vad_threshold")])
        assertEquals(20.0F, stored[floatPreferencesKey("vad_max_speech_duration")])
        assertEquals(Triple(0.4F, 0.5F, 20.0F), repository.getSettings().first().vad())
    }

    @Test
    fun `once this version has stored them, the old defaults can be chosen again`() = runBlocking {
        storeVadAsAnEarlierVersion(threshold = 0.5F, minSilence = 0.5F, maxSpeech = 10.0F)

        repository.updateSettings(
            repository.getSettings().first().copy(vadThreshold = 0.5F, vadMaxSpeechDuration = 10.0F)
        )

        assertEquals(Triple(0.5F, 0.5F, 10.0F), repository.getSettings().first().vad())
    }

    // ========== Microphone, transcript after Stop, place ==========

    @Test
    fun `the microphone, the transcript after Stop and the place start at their defaults`() = runBlocking {
        val settings = repository.getSettings().first()

        // The phone's usual input: the speech recognition input records the razr's speech too quietly.
        assertEquals(MicSource.MIC, settings.micSource)
        assertNull(settings.transcribeAfterStop)
        assertFalse(settings.savePlace)
    }

    @Test
    fun `a speech recognition input stored earlier reads as the usual one, and the next write stores that`() = runBlocking {
        // Settings no longer offers it (MicSource.OFFERED): it recorded the razr too quietly.
        context.dataStore.edit { it[stringPreferencesKey("mic_source")] = "VOICE_RECOGNITION" }

        assertEquals(MicSource.MIC, repository.getSettings().first().micSource)

        repository.updateSettings(repository.getSettings().first().copy(keepAudio = false))

        assertEquals("MIC", context.dataStore.data.first()[stringPreferencesKey("mic_source")])
        assertEquals(MicSource.MIC, repository.getSettings().first().micSource)
    }

    @Test
    fun `the input for calls stays as stored`() = runBlocking {
        context.dataStore.edit { it[stringPreferencesKey("mic_source")] = "VOICE_COMMUNICATION" }

        assertEquals(MicSource.VOICE_COMMUNICATION, repository.getSettings().first().micSource)
    }

    @Test
    fun `an unset input reads as the usual one, and the first write stores it`() = runBlocking {
        repository.updateSettings(repository.getSettings().first().copy(keepAudio = false))

        assertEquals("MIC", context.dataStore.data.first()[stringPreferencesKey("mic_source")])
        assertEquals(MicSource.MIC, repository.getSettings().first().micSource)
    }

    @Test
    fun `the microphone, the transcript after Stop and the place are stored`() = runBlocking {
        repository.updateSettings(
            AppSettings(micSource = MicSource.MIC, transcribeAfterStop = SpeechModelId.COHERE_TRANSCRIBE, savePlace = true)
        )

        val stored = context.dataStore.data.first()
        assertEquals("MIC", stored[stringPreferencesKey("mic_source")])
        assertEquals("cohere-transcribe-14-lang-int8", stored[stringPreferencesKey("transcribe_after_stop")])
        assertEquals(true, stored[booleanPreferencesKey("save_place")])
        val settings = repository.getSettings().first()
        assertEquals(MicSource.MIC, settings.micSource)
        assertEquals(SpeechModelId.COHERE_TRANSCRIBE, settings.transcribeAfterStop)
        assertTrue(settings.savePlace)
    }

    @Test
    fun `turning the transcript after Stop off removes it`() = runBlocking {
        repository.updateSettings(AppSettings(transcribeAfterStop = SpeechModelId.PARAKEET_V2_EN))

        repository.updateSettings(repository.getSettings().first().copy(transcribeAfterStop = null))

        assertFalse("transcribe_after_stop" in storedKeys())
        assertNull(repository.getSettings().first().transcribeAfterStop)
    }

    @Test
    fun `an input or a model this version doesn't know reads as the default and as none`() = runBlocking {
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey("mic_source")] = "UNPROCESSED"
            preferences[stringPreferencesKey("transcribe_after_stop")] = "a-model-of-a-later-version"
        }

        val settings = repository.getSettings().first()

        assertEquals(MicSource.MIC, settings.micSource)
        assertNull(settings.transcribeAfterStop)
    }

    // ========== Screen kept on while recording ==========

    /** Stores the screen setting as an earlier version did: under its key, without the marker. */
    private suspend fun storeKeepScreenOnAsAnEarlierVersion(on: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("keep_screen_on_while_recording")] = on }
    }

    @Test
    fun `the screen stays on by default`() = runBlocking {
        assertTrue(repository.getSettings().first().keepScreenOnWhileRecording)
    }

    @Test
    fun `off stored by an earlier version reads as on, and on as on`() = runBlocking {
        storeKeepScreenOnAsAnEarlierVersion(false)
        assertTrue(repository.getSettings().first().keepScreenOnWhileRecording)

        storeKeepScreenOnAsAnEarlierVersion(true)
        assertTrue(repository.getSettings().first().keepScreenOnWhileRecording)
    }

    @Test
    fun `once this version has stored it, off reads as off and on as on`() = runBlocking {
        storeKeepScreenOnAsAnEarlierVersion(false)

        repository.updateSettings(repository.getSettings().first().copy(keepScreenOnWhileRecording = false))

        assertEquals(1, context.dataStore.data.first()[intPreferencesKey("recording_defaults")])
        assertFalse(repository.getSettings().first().keepScreenOnWhileRecording)

        repository.updateSettings(repository.getSettings().first().copy(keepScreenOnWhileRecording = true))
        assertTrue(repository.getSettings().first().keepScreenOnWhileRecording)
    }

    @Test
    fun `any write stores the marker, so the screen reads as it was written`() = runBlocking {
        storeKeepScreenOnAsAnEarlierVersion(false)

        repository.updateSettings(repository.getSettings().first().copy(keepAudio = false))

        assertEquals(true, context.dataStore.data.first()[booleanPreferencesKey("keep_screen_on_while_recording")])
        assertTrue(repository.getSettings().first().keepScreenOnWhileRecording)
    }

    // ========== Older audio ==========

    @Test
    fun `older audio is never compressed or deleted by default`() = runBlocking {
        val settings = repository.getSettings().first()

        assertNull(settings.audioCompressAfterDays)
        assertNull(settings.audioDeleteAfterDays)
    }

    @Test
    fun `the ages for older audio are stored, never as 0`() = runBlocking {
        repository.updateSettings(AppSettings(audioCompressAfterDays = 14, audioDeleteAfterDays = 60))

        val stored = context.dataStore.data.first()
        assertEquals(14, stored[intPreferencesKey("audio_compress_after_days")])
        assertEquals(60, stored[intPreferencesKey("audio_delete_after_days")])
        assertEquals(14 to 60, repository.getSettings().first().let { it.audioCompressAfterDays to it.audioDeleteAfterDays })

        repository.updateSettings(repository.getSettings().first().copy(audioCompressAfterDays = null, audioDeleteAfterDays = null))

        assertEquals(0, context.dataStore.data.first()[intPreferencesKey("audio_compress_after_days")])
        assertEquals(null to null, repository.getSettings().first().let { it.audioCompressAfterDays to it.audioDeleteAfterDays })
    }

    @Test
    fun `an age that is not one of the choices reads as never`() = runBlocking {
        context.dataStore.edit { preferences ->
            // 15 is a choice for deleting only, 7 for compressing only
            preferences[intPreferencesKey("audio_compress_after_days")] = 15
            preferences[intPreferencesKey("audio_delete_after_days")] = 7
        }
        assertEquals(null to null, repository.getSettings().first().let { it.audioCompressAfterDays to it.audioDeleteAfterDays })

        context.dataStore.edit { preferences ->
            preferences[intPreferencesKey("audio_compress_after_days")] = 7
            preferences[intPreferencesKey("audio_delete_after_days")] = 15
        }
        assertEquals(7 to 15, repository.getSettings().first().let { it.audioCompressAfterDays to it.audioDeleteAfterDays })
    }

    @Test
    fun `home records in English or German until a language is stored, which its own setter alone writes`() = runBlocking {
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, repository.getSettings().first().homeLanguage)

        repository.setHomeLanguage(RecordingLanguage.MALAYALAM)
        assertEquals(RecordingLanguage.MALAYALAM, repository.getSettings().first().homeLanguage)
        assertEquals("ml", context.dataStore.data.first()[stringPreferencesKey("home_language")])
        // A screen's copy of the settings doesn't undo it.
        repository.updateSettings(AppSettings())
        assertEquals(RecordingLanguage.MALAYALAM, repository.getSettings().first().homeLanguage)

        // English or German is stored as none.
        repository.setHomeLanguage(RecordingLanguage.ENGLISH_OR_GERMAN)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, repository.getSettings().first().homeLanguage)
        assertFalse("home_language" in storedKeys())
    }

    @Test
    fun `every choice offered reads back as stored`() = runBlocking {
        for (days in AppSettings.AUDIO_COMPRESS_CHOICES) {
            repository.updateSettings(AppSettings(audioCompressAfterDays = days))
            assertEquals(days, repository.getSettings().first().audioCompressAfterDays)
        }
        for (days in AppSettings.AUDIO_DELETE_CHOICES) {
            repository.updateSettings(AppSettings(audioDeleteAfterDays = days))
            assertEquals(days, repository.getSettings().first().audioDeleteAfterDays)
        }
    }
}

/** Just enough of a Context for preferencesDataStore: the app context is itself, its files are in [dir]. */
private class FilesContext(private val dir: File) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = dir
}

/**
 * The prompts in the app language, which are all the repository reads. A proxy, so that strings
 * added to [ResourceProvider] for other features need nothing here.
 */
private fun shippedResources(): ResourceProvider {
    val prompts = mapOf(
        "getSimpleListeningPrompt" to "shipped voice note prompt",
        "getShortMeetingPrompt" to "shipped short meeting prompt",
        "getLongMeetingPrompt" to "shipped long meeting prompt",
        "getTranslationPrompt" to "shipped translation prompt"
    )
    return Proxy.newProxyInstance(ResourceProvider::class.java.classLoader, arrayOf(ResourceProvider::class.java)) { _, method, _ ->
        prompts[method.name] ?: error("The settings don't read ${method.name}")
    } as ResourceProvider
}
