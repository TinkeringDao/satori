package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.session
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.transcription.ChangeRecordingLanguageUseCase.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The language a recording is made in, switched while it records and on its page: stored as
 * chosen, with the new language's live model as the transcript's while it records and none
 * changed on the page; Malayalam only while its model is on the phone, and while it records each
 * language only with its model, but on the page English or German always; never for a Translate
 * recording; what can't be stored changes nothing and says why.
 */
class ChangeRecordingLanguageUseCaseTest {

    private val voiceNote = session().copy(
        mode = RecordingMode.SIMPLE_LISTENING, categoryId = "voice-note", transcriptModel = SpeechModelId.PARAKEET_V3.id
    )
    private val bothModels = SpeechModelsOnPhone(SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML)

    private fun change(recordings: LanguagesStored, models: SpeechModelsOnPhone = bothModels) =
        ChangeRecordingLanguageUseCase(recordings, models)

    @Test
    fun `a recording switched to Malayalam while it records stores it, with the Malayalam model as its transcript's`() = runBlocking {
        val recordings = LanguagesStored(FakeTranscriptionRepository(voiceNote))

        val outcome = change(recordings).whileRecording("s1", RecordingLanguage.MALAYALAM)

        assertEquals(Outcome.Changed(RecordingLanguage.MALAYALAM), outcome)
        assertEquals(listOf("s1" to RecordingLanguage.MALAYALAM), recordings.languageWrites)
        assertEquals(RecordingLanguage.MALAYALAM, recordings.recordings.session?.language)
        assertEquals(SpeechModelId.INDICCONFORMER_ML.id, recordings.recordings.session?.transcriptModel)
        // Its category and mode stay.
        assertEquals("voice-note", recordings.recordings.session?.categoryId)
        assertEquals(RecordingMode.SIMPLE_LISTENING, recordings.recordings.session?.mode)
    }

    @Test
    fun `switched back to English or German, Parakeet v3 makes the transcript from then on`() = runBlocking {
        val malayalam = voiceNote.copy(language = RecordingLanguage.MALAYALAM, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id)
        val recordings = LanguagesStored(FakeTranscriptionRepository(malayalam))

        val outcome = change(recordings).whileRecording("s1", RecordingLanguage.ENGLISH_OR_GERMAN)

        assertEquals(Outcome.Changed(RecordingLanguage.ENGLISH_OR_GERMAN), outcome)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.recordings.session?.language)
        assertEquals(SpeechModelId.PARAKEET_V3.id, recordings.recordings.session?.transcriptModel)
    }

    @Test
    fun `on its page only the language changes, the transcript stays the one its model made`() = runBlocking {
        val recordings = LanguagesStored(FakeTranscriptionRepository(voiceNote))

        val outcome = change(recordings).onPage("s1", RecordingLanguage.MALAYALAM)

        assertEquals(Outcome.Changed(RecordingLanguage.MALAYALAM), outcome)
        assertEquals(RecordingLanguage.MALAYALAM, recordings.recordings.session?.language)
        assertEquals(SpeechModelId.PARAKEET_V3.id, recordings.recordings.session?.transcriptModel)
    }

    @Test
    fun `Malayalam isn't a choice while its model isn't on the phone`() = runBlocking {
        val recordings = LanguagesStored(FakeTranscriptionRepository(voiceNote))
        val onlyParakeet = SpeechModelsOnPhone(SpeechModelId.PARAKEET_V3)

        assertEquals(Outcome.Refused, change(recordings, onlyParakeet).whileRecording("s1", RecordingLanguage.MALAYALAM))
        assertEquals(Outcome.Refused, change(recordings, onlyParakeet).onPage("s1", RecordingLanguage.MALAYALAM))

        assertTrue(recordings.languageWrites.isEmpty())
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.recordings.session?.language)
    }

    @Test
    fun `on its page a Malayalam recording goes back to English or German whatever is on the phone`() = runBlocking {
        val malayalam = voiceNote.copy(language = RecordingLanguage.MALAYALAM, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id)
        val recordings = LanguagesStored(FakeTranscriptionRepository(malayalam))
        val noSpeechModel = SpeechModelsOnPhone()

        val outcome = change(recordings, noSpeechModel).onPage("s1", RecordingLanguage.ENGLISH_OR_GERMAN)

        assertEquals(Outcome.Changed(RecordingLanguage.ENGLISH_OR_GERMAN), outcome)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.recordings.session?.language)
        assertEquals(SpeechModelId.INDICCONFORMER_ML.id, recordings.recordings.session?.transcriptModel)
    }

    @Test
    fun `while it records, a switch to English or German needs Parakeet v3 on the phone`() = runBlocking {
        val malayalam = voiceNote.copy(language = RecordingLanguage.MALAYALAM, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id)
        val recordings = LanguagesStored(FakeTranscriptionRepository(malayalam))
        val onlyMalayalam = SpeechModelsOnPhone(SpeechModelId.INDICCONFORMER_ML)

        assertEquals(Outcome.Refused, change(recordings, onlyMalayalam).whileRecording("s1", RecordingLanguage.ENGLISH_OR_GERMAN))

        assertTrue(recordings.languageWrites.isEmpty())
        assertEquals(RecordingLanguage.MALAYALAM, recordings.recordings.session?.language)
    }

    @Test
    fun `the language the recording is in already changes nothing`() = runBlocking {
        val recordings = LanguagesStored(FakeTranscriptionRepository(voiceNote))

        assertEquals(Outcome.Unchanged, change(recordings).whileRecording("s1", RecordingLanguage.ENGLISH_OR_GERMAN))

        assertTrue(recordings.languageWrites.isEmpty())
    }

    @Test
    fun `a Translate recording keeps English or German, and the offer says so`() = runBlocking {
        val translation = voiceNote.copy(mode = RecordingMode.REAL_TIME_TRANSLATION, categoryId = null)
        val recordings = LanguagesStored(FakeTranscriptionRepository(translation))

        assertEquals(Outcome.Refused, change(recordings).whileRecording("s1", RecordingLanguage.MALAYALAM))
        assertTrue(recordings.languageWrites.isEmpty())
        assertFalse(ChangeRecordingLanguageUseCase.offered(RecordingMode.REAL_TIME_TRANSLATION))
        assertFalse("not while its mode isn't known", ChangeRecordingLanguageUseCase.offered(null))
        listOf(RecordingMode.SIMPLE_LISTENING, RecordingMode.SHORT_MEETING, RecordingMode.LONG_MEETING).forEach { mode ->
            assertTrue("$mode", ChangeRecordingLanguageUseCase.offered(mode))
        }
    }

    @Test
    fun `a recording that is gone is refused`() = runBlocking {
        val recordings = LanguagesStored(FakeTranscriptionRepository(null))

        assertEquals(Outcome.Refused, change(recordings).whileRecording("s1", RecordingLanguage.MALAYALAM))
    }

    @Test
    fun `a store that fails changes nothing and says so`() = runBlocking {
        val failure = IOException("disk full")
        val recordings = LanguagesStored(FakeTranscriptionRepository(voiceNote)).apply { languageFailure = failure }

        val outcome = change(recordings).whileRecording("s1", RecordingLanguage.MALAYALAM)

        assertTrue(outcome is Outcome.Failed)
        assertSame(failure, (outcome as Outcome.Failed).cause)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.recordings.session?.language)
        assertEquals(SpeechModelId.PARAKEET_V3.id, recordings.recordings.session?.transcriptModel)
    }
}
