package com.satori.service

import com.satori.domain.usecase.finish.FinishStep
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinishingNotificationTextTest {

    @Test
    fun `without a recording being finished the service keeps its generic text`() {
        assertNull(FinishingNotificationText.of(recordings = 0, step = FinishStep.Notes))
    }

    @Test
    fun `the title counts the recordings, as plurals`() {
        val one = FinishingNotificationText.of(recordings = 1, step = FinishStep.Notes)!!
        val three = FinishingNotificationText.of(recordings = 3, step = FinishStep.Notes)!!

        assertEquals(R.plurals.g_notif_finishing, one.title)
        assertEquals(1, one.count)
        assertEquals(R.plurals.g_notif_finishing, three.title)
        assertEquals(3, three.count)
    }

    @Test
    fun `the text is the words of the step under way, and nothing else`() {
        val words = mapOf(
            FinishStep.Transcript to R.string.g_speech_finishing_transcript,
            FinishStep.Lines to R.string.g_rec_finishing,
            // The reader's reading of the lines has no words of its own: it is finishing up, as the joining is.
            FinishStep.Reading to R.string.g_rec_finishing,
            FinishStep.Notes to R.string.g_rec_notes_writing,
            FinishStep.Name to R.string.g_rec_naming,
            FinishStep.Todos to R.string.g_rec_todos,
            FinishStep.Map to R.string.g_rec_mapping
        )

        for (step in FinishStep.entries) {
            assertEquals(step.name, FinishingNotificationText(2, words.getValue(step)), FinishingNotificationText.of(2, step))
        }
    }

    @Test
    fun `between steps there is no text`() {
        assertEquals(FinishingNotificationText(1, stepText = null), FinishingNotificationText.of(1, step = null))
    }
}
