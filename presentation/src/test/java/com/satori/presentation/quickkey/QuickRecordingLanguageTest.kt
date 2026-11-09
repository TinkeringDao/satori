package com.satori.presentation.quickkey

import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import com.satori.domain.usecase.transcription.CreateSessionUseCase
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.CategoriesOnPhone
import com.satori.presentation.main.HeldSettings
import com.satori.presentation.main.NoAudioKept
import com.satori.presentation.main.starterCategories
import com.satori.presentation.main.translatingCategory
import com.satori.presentation.sessions.CreatedRecordings
import com.satori.presentation.sessions.SpeechModelsHere
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The recording a quick-key press creates, in the key's language: Malayalam while its model is on
 * the phone, English or German while it isn't, and for a category that translates.
 */
class QuickRecordingLanguageTest {

    private val recordings = CreatedRecordings()
    private val models = SpeechModelsHere(malayalam = true)

    private val quickRecording = QuickRecordingViewModel(
        activeRecorder = ActiveRecorder(),
        categoryRepository = CategoriesOnPhone(*starterCategories.toTypedArray(), translatingCategory),
        settingsRepository = HeldSettings(),
        createSessionUseCase = CreateSessionUseCase(recordings),
        discardSilentRecording = DiscardSilentRecordingUseCase(recordings, NoAudioKept),
        speechModels = models
    )

    @Test
    fun `a key set to record a meeting in Malayalam does, with its model on the phone`() = runBlocking {
        val id = quickRecording.createRecording("meeting", RecordingLanguage.MALAYALAM)

        val created = recordings.created.single()
        assertEquals(created.id, id)
        assertEquals(RecordingLanguage.MALAYALAM, created.language)
        assertEquals("meeting", created.categoryId)
        assertEquals(RecordingMode.SHORT_MEETING, created.mode)
    }

    @Test
    fun `without the Malayalam model it records in English or German`() = runBlocking {
        models.malayalam = false

        quickRecording.createRecording("meeting", RecordingLanguage.MALAYALAM)

        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.created.single().language)
    }

    @Test
    fun `a category that translates records in English or German`() = runBlocking {
        quickRecording.createRecording("trip", RecordingLanguage.MALAYALAM)

        val created = recordings.created.single()
        assertEquals(RecordingMode.REAL_TIME_TRANSLATION, created.mode)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, created.language)
    }

    @Test
    fun `a key set to English or German records in it`() = runBlocking {
        quickRecording.createRecording("voice-note", RecordingLanguage.ENGLISH_OR_GERMAN)

        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.created.single().language)
    }
}
