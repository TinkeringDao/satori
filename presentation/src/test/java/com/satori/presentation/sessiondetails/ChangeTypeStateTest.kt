package com.satori.presentation.sessiondetails

import com.satori.domain.finish.FinishingStatus
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.starterCategories
import com.satori.presentation.main.translatingCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Change type" in the recording's page menu: offered for any recording but a Translate one, with
 * the categories but those that translate, and the recording's language; switched off, saying why,
 * while the recording is still being finished or the AI's work waits; hidden while other work of the
 * page runs. It writes the notes anew only when the AI can, and only for a category whose mode
 * writes another kind of notes.
 */
class ChangeTypeStateTest {

    private val talk = listOf(
        "We went over the plan for the garden this spring and who brings which tools on Saturday.",
        "The shed needs a new lock before anything goes in, and the hedge wants cutting back first.",
        "After that the beds get fresh soil, and the tomatoes go in once the nights stay warm enough."
    )

    private fun details(mode: RecordingMode = RecordingMode.SIMPLE_LISTENING, categoryId: String? = "voice-note", lines: List<String> = talk) =
        SessionWithDetails(
            session = TranscriptionSession(id = "s", name = null, createdAt = 0, lastModifiedAt = 0, mode = mode, categoryId = categoryId),
            segments = lines.mapIndexed { index, text ->
                TranscriptionSegment(id = "seg$index", sessionId = "s", text = text, timestamp = index * 1_000L, isComplete = true)
            },
            insights = emptyList()
        )

    private val ready = SessionDetailsUiState(
        sessionDetails = details(),
        notesAi = NotesAi.Ready,
        categories = starterCategories + translatingCategory
    )

    @Test
    fun `a voice note can be changed to every category but those that translate, and names its own`() {
        assertEquals(AiOffer.READY, ready.changeTypeOffer(transcribing = false))
        assertEquals(listOf("voice-note", "meeting", "idea", "todo"), ready.typeChoices.map { it.id })
        assertEquals("Voice note", ready.recordingAs?.name)
    }

    @Test
    fun `a Translate recording isn't offered it`() {
        val translation = ready.copy(sessionDetails = details(mode = RecordingMode.REAL_TIME_TRANSLATION, categoryId = null))
        val inTranslatingCategory = ready.copy(sessionDetails = details(mode = RecordingMode.REAL_TIME_TRANSLATION, categoryId = "trip"))

        for (state in listOf(translation, inTranslatingCategory)) {
            assertEquals(AiOffer.NONE, state.changeTypeOffer(transcribing = false))
            assertTrue(state.typeChoices.isEmpty())
            assertFalse(state.changeRewritesNotes)
        }
    }

    @Test
    fun `it waits while this recording is still being finished, for the finishing`() {
        for (phase in FinishingStatus.Phase.entries) {
            val finishing = ready.copy(finishing = FinishingStatus(sessionId = "s", phase = phase))

            assertEquals(phase.name, AiOffer.WAITS, finishing.changeTypeOffer(transcribing = false))
            assertTrue(phase.name, finishing.changeTypeWaitsForFinishing)
        }
    }

    @Test
    fun `it waits while the AI is in use elsewhere, for the AI or for the finishing of others`() {
        val mapping = ready.withAi(holder = AiWorkGate.MAP, finishing = false)
        val othersFinishing = ready.withAi(holder = null, finishing = true)

        assertEquals(AiOffer.WAITS, mapping.changeTypeOffer(transcribing = false))
        assertFalse(mapping.changeTypeWaitsForFinishing)
        assertEquals(AiOffer.WAITS, othersFinishing.changeTypeOffer(transcribing = false))
        assertTrue(othersFinishing.changeTypeWaitsForFinishing)
    }

    @Test
    fun `it is hidden while other work of the page runs`() {
        assertEquals(AiOffer.NONE, ready.copy(rewrite = RewriteProgress()).changeTypeOffer(transcribing = false))
        assertEquals(AiOffer.NONE, ready.copy(todoSearch = TodoSearch()).changeTypeOffer(transcribing = false))
        assertEquals(AiOffer.NONE, ready.changeTypeOffer(transcribing = true))
    }

    @Test
    fun `with no other category it is still offered for the language, which every recording but a translation can change`() {
        val alone = ready.copy(categories = starterCategories.take(1))

        assertTrue(alone.changesLanguage)
        assertEquals(AiOffer.READY, alone.changeTypeOffer(transcribing = false))
        assertEquals(AiOffer.READY, ready.copy(categories = emptyList()).changeTypeOffer(transcribing = false))
        // A translation has neither: no type to change to, and it stays in English or German.
        val translation = ready.copy(sessionDetails = details(mode = RecordingMode.REAL_TIME_TRANSLATION, categoryId = null))
        assertFalse(translation.changesLanguage)
        assertEquals(AiOffer.NONE, translation.copy(categories = starterCategories.take(1)).changeTypeOffer(transcribing = false))
    }

    @Test
    fun `it works with the AI off too, and then only the type changes`() {
        for (ai in listOf(NotesAi.TurnedOff, NotesAi.NoModel, null)) {
            val state = ready.copy(notesAi = ai)

            assertEquals("$ai", AiOffer.READY, state.changeTypeOffer(transcribing = false))
            assertFalse("$ai", state.changeRewritesNotes)
        }
        assertTrue(ready.changeRewritesNotes)
    }

    @Test
    fun `only a category in another mode writes the notes anew, and the sheet says so while one is offered`() {
        // A voice note: Meeting writes meeting notes; Idea and To-do record voice notes as it does.
        assertTrue(ready.rewritesNotesFor("meeting"))
        assertFalse(ready.rewritesNotesFor("idea"))
        assertFalse(ready.rewritesNotesFor("todo"))
        assertFalse(ready.rewritesNotesFor("voice-note"))
        assertFalse(ready.rewritesNotesFor("gone"))
        assertTrue(ready.someChangeRewritesNotes)

        // With only categories that record voice notes, none rewrites, and the sheet's line goes.
        val sameKind = ready.copy(categories = starterCategories.filter { it.mode == RecordingMode.SIMPLE_LISTENING })
        assertFalse(sameKind.someChangeRewritesNotes)
        assertEquals(AiOffer.READY, sameKind.changeTypeOffer(transcribing = false))

        // A meeting changed to a voice note gets voice notes.
        val meeting = ready.copy(sessionDetails = details(mode = RecordingMode.SHORT_MEETING, categoryId = "meeting"))
        assertTrue(meeting.rewritesNotesFor("voice-note"))
        // Without the AI none does.
        assertFalse(ready.copy(notesAi = NotesAi.TurnedOff).rewritesNotesFor("meeting"))
        assertFalse(ready.copy(notesAi = NotesAi.TurnedOff).someChangeRewritesNotes)
    }

    @Test
    fun `a recording with too little text keeps its notes, only the type changes`() {
        val short = ready.copy(sessionDetails = details(lines = listOf("Remind me about the shed.")))

        assertEquals(AiOffer.READY, short.changeTypeOffer(transcribing = false))
        assertFalse(short.changeRewritesNotes)
    }
}
