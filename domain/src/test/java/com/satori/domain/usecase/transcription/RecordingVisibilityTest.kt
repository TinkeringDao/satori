package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.session
import com.satori.domain.model.SessionListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingVisibilityTest {

    private fun item(
        id: String,
        durationMs: Long = 0L,
        hasTranscript: Boolean = false,
        hasNotes: Boolean = false
    ) = SessionListItem(
        session = session(id).copy(durationMs = durationMs),
        hasTranscript = hasTranscript,
        hasNotes = hasNotes
    )

    @Test
    fun `a recording with no line, no note and under two seconds holds nothing`() {
        assertTrue(RecordingVisibility.holdsNothing(item("a", durationMs = 0L)))
        assertTrue(RecordingVisibility.holdsNothing(item("a", durationMs = 1_999L)))
    }

    @Test
    fun `two seconds or a line or a note is something`() {
        assertFalse(RecordingVisibility.holdsNothing(item("a", durationMs = 2_000L)))
        assertFalse(RecordingVisibility.holdsNothing(item("a", hasTranscript = true)))
        assertFalse(RecordingVisibility.holdsNothing(item("a", hasNotes = true)))
    }

    @Test
    fun `the list hides what holds nothing and keeps the rest in order`() {
        val items = listOf(
            item("empty-new"),
            item("spoken", durationMs = 30_000L, hasTranscript = true),
            item("silent", durationMs = 12_000L),
            item("empty-old", durationMs = 900L)
        )

        val visible = RecordingVisibility.visible(items, recordingNowId = null)

        assertEquals(listOf("spoken", "silent"), visible.map { it.session.id })
    }

    @Test
    fun `the recording being made stays in the list though it holds nothing yet`() {
        val items = listOf(item("running"), item("empty-old"), item("spoken", hasTranscript = true))

        val visible = RecordingVisibility.visible(items, recordingNowId = "running")

        assertEquals(listOf("running", "spoken"), visible.map { it.session.id })
    }

    @Test
    fun `the recording being made is the one the recorder captures, not the newest one`() {
        // A quick-key press created "next" while the recorder still saves "saving".
        val items = listOf(item("next"), item("saving"), item("spoken", hasTranscript = true))

        val recordingNow = RecordingVisibility.recordingNowId(capturingSessionId = "saving")
        val visible = RecordingVisibility.visible(items, recordingNow)

        assertEquals("saving", recordingNow)
        assertEquals(listOf("saving", "spoken"), visible.map { it.session.id })
    }

    @Test
    fun `nothing is being made while no recorder captures`() {
        assertNull(RecordingVisibility.recordingNowId(capturingSessionId = null))
    }
}
