package com.satori.presentation.main

import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language row of the recorder's "Record as": while a recording runs it can be switched to the
 * other language, but a translation, when there is one to record in (Malayalam only with its
 * model); the chip opens the sheet for it even with no other category; a recording in Malayalam
 * writes no live notes.
 */
class RecordInStateTest {

    private val recording = MainUiState(
        isInitializing = false,
        llmStatus = LlmStatus.Ready,
        isRecording = true,
        sessionMode = RecordingMode.SIMPLE_LISTENING,
        sessionCategoryId = "voice-note",
        categories = starterCategories + translatingCategory,
        language = RecordingLanguage.ENGLISH_OR_GERMAN
    )

    @Test
    fun `while recording the language can be switched to Malayalam with its model, and back to English or German always`() {
        assertTrue(recording.copy(malayalamOnPhone = true).canChangeLanguage)
        // Without the model there is no other language: the sheet has no language row.
        assertFalse(recording.canChangeLanguage)
        assertTrue(recording.opensRecordAs)
        // A recording in Malayalam can go back to English or German, with the model or without.
        assertTrue(recording.copy(language = RecordingLanguage.MALAYALAM).canChangeLanguage)
        assertTrue(recording.copy(language = RecordingLanguage.MALAYALAM, malayalamOnPhone = true).canChangeLanguage)
    }

    @Test
    fun `a recording with no other category to switch to opens Record as for its language, with one to switch to`() {
        val alone = recording.copy(categories = starterCategories.take(1))

        assertFalse(alone.canChangeType)
        assertTrue(alone.copy(malayalamOnPhone = true).canChangeLanguage)
        assertTrue(alone.copy(malayalamOnPhone = true).opensRecordAs)
        // Nothing to switch to: the chip is a label.
        assertFalse(alone.opensRecordAs)
    }

    @Test
    fun `a translation keeps its language, and its label stays a label`() {
        val translation = recording.copy(sessionMode = RecordingMode.REAL_TIME_TRANSLATION, sessionCategoryId = null)

        assertFalse(translation.canChangeLanguage)
        assertFalse(translation.opensRecordAs)
    }

    @Test
    fun `before the recording starts, before its language is read and once Stop is pressed nothing is switched`() {
        assertFalse(recording.copy(isRecording = false).canChangeLanguage)
        assertFalse(recording.copy(language = null).canChangeLanguage)
        assertFalse(recording.copy(isRecording = false, saving = true).canChangeLanguage)
        assertFalse(recording.copy(isRecording = false, exit = RecorderExit.HOME).opensRecordAs)
    }

    @Test
    fun `a recording in Malayalam writes no live notes, one in English or German does`() {
        assertTrue(recording.copy(language = RecordingLanguage.MALAYALAM).liveNotesOff)
        assertFalse(recording.liveNotesOff)
        assertFalse(recording.copy(language = null).liveNotesOff)
    }

    @Test
    fun `the field log's line names the language and its live model, and where it came`() {
        assertEquals(
            "Recording s1 switched to MALAYALAM (indicconformer-ml-int8) at 41200 ms",
            MainViewModel.languageLine("s1", RecordingLanguage.MALAYALAM, atMs = 41_200L)
        )
        assertEquals(
            "Recording s1 switched to ENGLISH_OR_GERMAN (parakeet-tdt-0.6b-v3-int8) at 90000 ms",
            MainViewModel.languageLine("s1", RecordingLanguage.ENGLISH_OR_GERMAN, atMs = 90_000L)
        )
    }
}
