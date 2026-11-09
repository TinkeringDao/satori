package com.satori.presentation.main

import com.satori.domain.model.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's label at the top: while a recording runs it is the Record as chip, which offers
 * every category but those that translate, the one it is recorded as marked; before the start,
 * once Stop is pressed and for a translation it is only a label.
 */
class RecordAsStateTest {

    private val recording = MainUiState(
        isInitializing = false,
        llmStatus = LlmStatus.Ready,
        isRecording = true,
        sessionMode = RecordingMode.SIMPLE_LISTENING,
        sessionCategoryId = "voice-note",
        categories = starterCategories + translatingCategory
    )

    @Test
    fun `while recording the label switches the recording, to every category but those that translate`() {
        assertTrue(recording.canChangeType)
        assertEquals(listOf("voice-note", "meeting", "idea", "todo"), recording.typeChoices.map { it.id })
    }

    @Test
    fun `the label names the category it is recorded as, and the next one once switched`() {
        assertEquals("Voice note", recording.recordingAs?.name)

        val switched = recording.copy(sessionCategoryId = "meeting", sessionMode = RecordingMode.SHORT_MEETING)

        assertEquals("Meeting", switched.recordingAs?.name)
        assertTrue(switched.canChangeType)
    }

    @Test
    fun `a translation is only a label`() {
        val translation = recording.copy(sessionMode = RecordingMode.REAL_TIME_TRANSLATION, sessionCategoryId = null)
        val inTranslatingCategory = recording.copy(sessionMode = RecordingMode.REAL_TIME_TRANSLATION, sessionCategoryId = "trip")

        for (state in listOf(translation, inTranslatingCategory)) {
            assertFalse(state.canChangeType)
            assertTrue(state.typeChoices.isEmpty())
        }
    }

    @Test
    fun `before the recording starts, while it gets ready and once Stop is pressed it is only a label`() {
        assertFalse(recording.copy(isRecording = false).canChangeType)
        assertFalse(recording.copy(isRecording = false, isStartingRecording = true).canChangeType)
        assertFalse(recording.copy(isRecording = false, saving = true).canChangeType)
        assertFalse(recording.copy(isRecording = false, exit = RecorderExit.HOME).canChangeType)
    }

    @Test
    fun `with no other category to switch to it stays a label`() {
        assertFalse(recording.copy(categories = starterCategories.take(1)).canChangeType)
        assertFalse(recording.copy(categories = emptyList()).canChangeType)
        // Before the recording's mode is read, nothing is offered.
        assertFalse(recording.copy(sessionMode = null).canChangeType)
    }

    @Test
    fun `a recording whose category was deleted can be switched to any, none of them marked`() {
        val orphan = recording.copy(sessionCategoryId = "deleted")

        assertNull(orphan.recordingAs)
        assertTrue(orphan.canChangeType)
        assertEquals(4, orphan.typeChoices.size)
    }

    @Test
    fun `the field log's line holds ids, the mode and the position, never the category's name`() {
        val owners = starterCategories[1].copy(name = "Weekly sync with the landlord")

        assertEquals(
            "Recording s1 switched to meeting (SHORT_MEETING) at 63127 ms",
            MainViewModel.switchLine("s1", owners, atMs = 63_127L)
        )
    }
}
