package com.satori.ui.screens

import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.presentation.firstrun.MicrophoneState
import com.satori.presentation.main.LatestTranslation
import com.satori.presentation.main.LlmStatus
import com.satori.presentation.main.MainUiState
import com.satori.presentation.main.TranslationLanguages
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the recorder shows of a recording as it records: the tab the inner screen opens on (a
 * Translate recording's translation), what the cover shows in place of the transcript (a Translate
 * recording's newest translation, with the line being said under it), and the status line, which
 * says a recording in Malayalam is recorded so and a Translate recording's languages.
 */
class RecorderTextsTest {

    private fun line(id: String, text: String, at: Long, complete: Boolean = true) =
        TranscriptionSegment(id = id, sessionId = "s1", text = text, timestamp = at, isComplete = complete)

    private val german = line("g1", "Die Regale für die Werkstatt kommen am Dienstag.", at = 1)
    private val translation = LlmInsight(
        id = "t1", sessionId = "s1", title = null, content = "The shelves for the workshop arrive on Tuesday.",
        timestamp = 10, sourceSegmentIds = listOf("g1")
    )

    private val recording = MainUiState(isInitializing = false, isRecording = true, sessionMode = RecordingMode.SIMPLE_LISTENING)
    private val translating = recording.copy(
        sessionMode = RecordingMode.REAL_TIME_TRANSLATION,
        translationTarget = "en",
        completedSegments = listOf(german),
        insights = listOf(translation)
    )

    @Test
    fun `the inner screen opens a Translate recording on its translation, any other on its transcript`() {
        assertEquals(TAB_NOTES, openingTab(translating))
        assertEquals(TAB_TRANSCRIPT, openingTab(recording))
        assertEquals(TAB_TRANSCRIPT, openingTab(recording.copy(language = RecordingLanguage.MALAYALAM)))
    }

    @Test
    fun `the cover shows a Translate recording's newest translation once there is one, and any other's transcript`() {
        assertEquals(LatestTranslation(translation.content, german.text), coverTranslation(translating))
        // Before the first translation: the transcript.
        assertNull(coverTranslation(translating.copy(insights = emptyList())))
        // Notes are no translation.
        assertNull(coverTranslation(recording.copy(completedSegments = listOf(german), insights = listOf(translation))))
    }

    @Test
    fun `under the translation the line being said shows, and nothing between lines`() {
        assertEquals("Bitte schickt mir", livePartial(translating.copy(currentPartialSegment = line("p", " Bitte schickt mir ", at = 2, complete = false))))
        assertNull(livePartial(translating.copy(currentPartialSegment = line("p", "  ", at = 2, complete = false))))
        assertNull(livePartial(translating))
    }

    @Test
    fun `the status line says a recording in Malayalam is recorded in it, one in English or German that it records`() {
        assertEquals(StatusLineRes(R.string.g_rec_live_ml), recorderStatus(recording.copy(language = RecordingLanguage.MALAYALAM), null))
        assertEquals(StatusLineRes(R.string.g_rec_live), recorderStatus(recording.copy(language = RecordingLanguage.ENGLISH_OR_GERMAN), null))
        assertEquals(StatusLineRes(R.string.g_rec_live), recorderStatus(recording, null))
        // While the speech model still loads ("Getting ready" until it is in, the microphone recording), and before and after recording.
        assertEquals(StatusLineRes(R.string.g_rec_preparing), recorderStatus(recording.copy(listening = true, language = RecordingLanguage.MALAYALAM), null))
        assertEquals(StatusLineRes(R.string.g_rec_preparing), recorderStatus(recording.copy(listening = true), null))
        assertEquals(StatusLineRes(R.string.g_rec_preparing), recorderStatus(recording.copy(isRecording = false, isInitializing = true), null))
        assertNull(recorderStatus(recording.copy(isRecording = false), null))
    }

    @Test
    fun `a microphone not allowed is asked for in Getting ready's words, by Android's window or the settings page`() {
        assertNull(microphoneCopy(MicrophoneState.ALLOWED))

        val ask = microphoneCopy(MicrophoneState.NOT_ALLOWED)!!
        assertEquals(listOf(R.string.g_fr_mic_why, R.string.g_fr_mic_how), ask.lines)
        assertEquals(R.string.g_fr_mic_action, ask.action)
        assertEquals(RecorderAction.AllowMicrophone, ask.onAction)

        val again = microphoneCopy(MicrophoneState.REFUSED_ONCE)!!
        assertEquals(listOf(R.string.g_fr_mic_refused, R.string.g_fr_mic_how), again.lines)
        assertEquals(RecorderAction.AllowMicrophone, again.onAction)

        // Android no longer asks: the app's settings page, and the recorder stays.
        val settings = microphoneCopy(MicrophoneState.REFUSED_FOR_GOOD)!!
        assertEquals(listOf(R.string.g_fr_mic_refused, R.string.g_fr_mic_settings), settings.lines)
        assertEquals(R.string.g_rec_action_settings, settings.action)
        assertEquals(RecorderAction.OpenAppSettings, settings.onAction)
    }

    @Test
    fun `an AI model on its way says that it downloads, where a missing one offers to get it`() {
        assertEquals(R.string.g_rec_ai_downloading, AiComingCopy.caption)
        assertEquals(R.string.g_rec_notes_downloading, AiComingCopy.notes)
        assertEquals(R.string.g_rec_action_get_model, aiProblemCopy(LlmStatus.Missing).action)
        assertEquals(R.string.g_rec_notes_missing, aiProblemCopy(LlmStatus.Missing).notes)
    }

    @Test
    fun `a Translate recording's status line says the languages in and out, and warns when they are the same`() {
        assertEquals(
            StatusLineRes(R.string.g_rec_translate_from_into, listOf(R.string.g_rec_language_de, R.string.g_rec_language_en)),
            recorderStatus(translating, TranslationLanguages("de", "en"))
        )
        assertEquals(StatusLineRes(R.string.g_rec_translate_into, listOf(R.string.g_rec_language_en)), recorderStatus(translating, TranslationLanguages(null, "en")))
        assertEquals(StatusLineRes(R.string.g_rec_translate_same, listOf(R.string.g_rec_language_de)), recorderStatus(translating, TranslationLanguages("de", "de")))
    }
}
