package com.satori.presentation.sessiondetails

import com.satori.domain.finish.FinishingStatus
import com.satori.domain.finish.FinishingStatus.Phase
import com.satori.domain.finish.TranscriptProgress
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.usecase.finish.FinishStep
import com.satori.presentation.ai.AiWorkGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailsUiStateTest {

    private val talk = listOf(
        "We went over the plan for the garden this spring and who brings which tools on Saturday.",
        "The shed needs a new lock before anything goes in, and the hedge wants cutting back first.",
        "After that the beds get fresh soil, and the tomatoes go in once the nights stay warm enough."
    )

    private fun segment(index: Int, text: String) =
        TranscriptionSegment(id = "seg$index", sessionId = "s", text = text, timestamp = index * 1_000L, isComplete = true)

    private fun details(
        mode: RecordingMode = RecordingMode.SIMPLE_LISTENING,
        lines: List<String> = talk,
        notes: Int = 0
    ) = SessionWithDetails(
        session = TranscriptionSession(id = "s", name = null, createdAt = 0, lastModifiedAt = 0, mode = mode),
        segments = lines.mapIndexed(::segment),
        insights = List(notes) { LlmInsight(id = "note$it", sessionId = "s", content = "A note.", timestamp = 0, sourceSegmentIds = emptyList()) }
    )

    private val ready = SessionDetailsUiState(sessionDetails = details(), notesAi = NotesAi.Ready)

    private fun running(step: FinishStep?, skipping: Boolean = false) = FinishingStatus(
        sessionId = "s",
        phase = Phase.RUNNING,
        step = step,
        transcript = if (step == FinishStep.Transcript) TranscriptProgress(SpeechModelId.LIVE, fraction = 0.4f, skipping = skipping) else null
    )

    private val waiting = FinishingStatus(sessionId = "s", phase = Phase.WAITING, position = 1)
    private val deferred = FinishingStatus(sessionId = "s", phase = Phase.WAITS_FOR_RECORDING)

    private fun todo(id: String, status: TodoStatus) =
        Todo(id = id, sessionId = "s", text = "Bring the ladder", status = status, createdAt = 0, updatedAt = 0)

    /** The recording with its lines still being recognized: none of them complete. */
    private fun partial(): SessionWithDetails = details().let { it.copy(segments = it.segments.map { line -> line.copy(isComplete = false) }) }

    // ─── The AI's work while the AI is in use ────────────────────────────────

    @Test
    fun `the AI's work waits while the AI is in use elsewhere`() {
        val busy = ready.copy(aiBusy = true)

        assertTrue(busy.aiWaits(transcribing = false))
        assertFalse(ready.aiWaits(transcribing = false))
    }

    @Test
    fun `the page's own work doesn't make its other actions wait, it hides them`() {
        val rewriting = ready.copy(aiBusy = true, rewrite = RewriteProgress())
        val searching = ready.copy(aiBusy = true, todoSearch = TodoSearch())
        val transcribing = ready.copy(aiBusy = true)

        assertFalse(rewriting.aiWaits(transcribing = false))
        assertFalse(searching.aiWaits(transcribing = false))
        assertFalse(transcribing.aiWaits(transcribing = true))
        assertFalse(rewriting.offersWriteNotes(transcribing = false))
        assertFalse(rewriting.offersFindAgain(transcribing = false))
        assertFalse(searching.offersWriteNotes(transcribing = false))
        assertFalse(searching.offersFindAgain(transcribing = false))
        assertFalse(ready.offersWriteNotes(transcribing = true))
        assertFalse(ready.offersFindAgain(transcribing = true))
    }

    @Test
    fun `the AI's work waits for the finishing while a recording waits or is being finished, else for the AI`() {
        assertFalse(ready.withAi(holder = null, finishing = false).aiBusy)
        // Handed over, before the finisher takes the gate; finishing; freeing the model once the queue is empty.
        listOf(null to true, AiWorkGate.FINISHER to true, AiWorkGate.FINISHER to false).forEach { (holder, finishing) ->
            val state = ready.withAi(holder, finishing)
            assertTrue("$holder, $finishing", state.aiBusy && state.aiFinishing)
        }
        // The map's work, a live note, another recording's page: nothing is being finished.
        listOf(AiWorkGate.MAP, AiWorkGate.LIVE_NOTE, AiWorkGate.REWRITE, AiWorkGate.TODOS, AiWorkGate.TRANSCRIBE).forEach { holder ->
            val state = ready.withAi(holder, finishing = false)
            assertTrue(holder, state.aiBusy)
            assertFalse(holder, state.aiFinishing)
        }
        // The map's work holding the AI while a recording waits: what the page waits for is the finishing.
        assertTrue(ready.withAi(AiWorkGate.MAP, finishing = true).aiFinishing)
    }

    @Test
    fun `the empty notes tab offers writing the notes as the menu does`() {
        assertEquals(AiOffer.READY, ready.writeNotesOffer(transcribing = false))
        // Switched off while the AI is in use elsewhere, rather than refused on the tap.
        assertEquals(AiOffer.WAITS, ready.copy(aiBusy = true).writeNotesOffer(transcribing = false))
        // Not at all while the page's own search runs, which would take the tap without a word, nor
        // while it transcribes again.
        assertEquals(AiOffer.NONE, ready.copy(aiBusy = true, todoSearch = TodoSearch()).writeNotesOffer(transcribing = false))
        assertEquals(AiOffer.NONE, ready.copy(todoSearch = TodoSearch()).writeNotesOffer(transcribing = false))
        assertEquals(AiOffer.NONE, ready.copy(aiBusy = true).writeNotesOffer(transcribing = true))
        assertEquals(AiOffer.NONE, ready.copy(notesAi = NotesAi.TurnedOff).writeNotesOffer(transcribing = false))
    }

    @Test
    fun `writing the notes is offered while the AI is busy, so the menu can say it waits`() {
        val busy = ready.copy(aiBusy = true, finishing = running(FinishStep.Name))

        assertTrue(busy.offersWriteNotes(transcribing = false))
        assertTrue(busy.offersFindAgain(transcribing = false))
        assertTrue(busy.aiWaits(transcribing = false))
    }

    @Test
    fun `writing the notes needs the AI, enough text and no translation`() {
        assertTrue(ready.offersWriteNotes(transcribing = false))
        assertFalse(ready.copy(notesAi = NotesAi.TurnedOff).offersWriteNotes(transcribing = false))
        assertFalse(ready.copy(notesAi = NotesAi.NoModel).offersWriteNotes(transcribing = false))
        assertFalse(ready.copy(notesAi = null).offersWriteNotes(transcribing = false))
        assertFalse(ready.copy(sessionDetails = details(lines = listOf("Testing, one two."))).offersWriteNotes(transcribing = false))
        assertFalse(ready.copy(sessionDetails = details(mode = RecordingMode.REAL_TIME_TRANSLATION)).offersWriteNotes(transcribing = false))
        assertFalse(SessionDetailsUiState(notesAi = NotesAi.Ready).offersWriteNotes(transcribing = false))
    }

    @Test
    fun `a new search for to-dos and ideas needs the AI and something said, also in a translation`() {
        assertTrue(ready.offersFindAgain(transcribing = false))
        assertTrue(ready.copy(sessionDetails = details(lines = listOf("Call the plumber."))).offersFindAgain(transcribing = false))
        assertTrue(ready.copy(sessionDetails = details(mode = RecordingMode.REAL_TIME_TRANSLATION)).offersFindAgain(transcribing = false))
        assertFalse(ready.copy(sessionDetails = details(lines = listOf(" "))).offersFindAgain(transcribing = false))
        assertFalse(ready.copy(notesAi = NotesAi.TurnedOff).offersFindAgain(transcribing = false))
        // Lines still being recognized are not yet said: the search reads complete ones.
        assertFalse(ready.copy(sessionDetails = partial()).offersFindAgain(transcribing = false))
    }

    // ─── Editing while the recording is finished ─────────────────────────────

    @Test
    fun `the lines can't be edited while the finishing may still replace them`() {
        assertFalse(ready.copy(finishing = waiting).canEditTranscript)
        assertFalse(ready.copy(finishing = deferred).canEditTranscript)
        assertFalse(ready.copy(finishing = running(null)).canEditTranscript)
        assertFalse(ready.copy(finishing = running(FinishStep.Transcript)).canEditTranscript)
        assertFalse(ready.copy(finishing = running(FinishStep.Lines)).canEditTranscript)
    }

    @Test
    fun `the lines can be edited once the finishing is past them, and without one`() {
        assertTrue(ready.canEditTranscript)
        listOf(FinishStep.Notes, FinishStep.Name, FinishStep.Todos, FinishStep.Map).forEach { step ->
            assertTrue("$step", ready.copy(finishing = running(step)).canEditTranscript)
        }
    }

    @Test
    fun `the notes can't be edited while the finishing may still write them anew`() {
        listOf(waiting, deferred, running(null), running(FinishStep.Transcript), running(FinishStep.Lines), running(FinishStep.Notes))
            .forEach { status -> assertFalse("$status", ready.copy(finishing = status, sessionDetails = details(notes = 2)).canEditNotes) }
    }

    @Test
    fun `the notes can be edited once the Notes step is over, and without a finishing`() {
        assertTrue(ready.copy(sessionDetails = details(notes = 2)).canEditNotes)
        listOf(FinishStep.Name, FinishStep.Todos, FinishStep.Map).forEach { step ->
            assertTrue("$step", ready.copy(finishing = running(step), sessionDetails = details(notes = 2)).canEditNotes)
        }
    }

    @Test
    fun `while the finishing waits for the AI model the lines can be edited, the notes it will write can't`() {
        // The first recording of a fresh install: no model is on the phone yet, the first run downloads it.
        val noModel = ready.copy(notesAi = NotesAi.NoModel)
        val waitsAtNotes = FinishingStatus(sessionId = "s", phase = Phase.WAITS_FOR_MODEL)
        val tooShort = waitsAtNotes.copy(noNotes = true)

        val atNotes = noModel.copy(finishing = waitsAtNotes)
        assertTrue(atNotes.waitsForModel)
        assertTrue(atNotes.canEditTranscript)
        assertFalse(atNotes.copy(sessionDetails = details(notes = 2)).canEditNotes)
        // It waits for the model only past its notes, for its name and to-dos.
        assertTrue(noModel.copy(finishing = tooShort, sessionDetails = details(notes = 2)).canEditNotes)
        assertTrue(noModel.copy(finishing = tooShort).canEditTranscript)
        // Nothing is written while the model isn't in: the tab doesn't say it is.
        assertFalse(atNotes.showsWritingNotes)
        assertFalse(ready.copy(finishing = running(FinishStep.Notes)).waitsForModel)
    }

    @Test
    fun `Skip shows while the better transcript is made, until it is pressed`() {
        assertTrue(ready.copy(finishing = running(FinishStep.Transcript)).canSkipTranscript)
        assertFalse(ready.copy(finishing = running(FinishStep.Transcript, skipping = true)).canSkipTranscript)
        assertFalse(ready.copy(finishing = running(FinishStep.Lines)).canSkipTranscript)
        assertFalse(ready.copy(finishing = waiting).canSkipTranscript)
        assertFalse(ready.canSkipTranscript)
    }

    // ─── Writing notes ───────────────────────────────────────────────────────

    @Test
    fun `the notes tab says the notes are being written while they are to come and there are none`() {
        listOf(waiting, deferred, running(null), running(FinishStep.Transcript), running(FinishStep.Lines), running(FinishStep.Notes))
            .forEach { status -> assertTrue("$status", ready.copy(finishing = status).showsWritingNotes) }
    }

    @Test
    fun `once the notes step is over the tab says what it says without the finishing`() {
        listOf(FinishStep.Name, FinishStep.Todos, FinishStep.Map).forEach { step ->
            assertFalse("$step", ready.copy(finishing = running(step)).showsWritingNotes)
        }
        assertFalse(ready.showsWritingNotes)
    }

    @Test
    fun `notes that are there show, and a rewrite shows its own progress`() {
        val finishing = ready.copy(finishing = running(FinishStep.Notes))

        assertFalse(finishing.copy(sessionDetails = details(notes = 1)).showsWritingNotes)
        assertFalse(finishing.copy(rewrite = RewriteProgress()).showsWritingNotes)
    }

    @Test
    fun `a recording too short for notes doesn't say they are being written`() {
        val finishing = ready.copy(finishing = running(FinishStep.Transcript))
        // A quick note of eleven words: the Notes step writes from twenty.
        val short = details(lines = listOf("Remember to take the bins out before the truck comes tomorrow."))

        assertFalse(finishing.copy(sessionDetails = short).showsWritingNotes)
        assertFalse(finishing.copy(sessionDetails = short).notesToCome)
        // Words still being recognized don't count: the step reads complete lines.
        assertFalse(finishing.copy(sessionDetails = partial()).showsWritingNotes)
        // Twenty-one, over two lines, do.
        val twenty = details(
            lines = listOf(
                "Remember to take the bins out before the truck comes tomorrow.",
                "The green one goes out on Thursday after the holiday."
            )
        )
        assertTrue(finishing.copy(sessionDetails = twenty).showsWritingNotes)
    }

    @Test
    fun `once the finishing has found too little text for notes, the tab says so and not that they are written`() {
        // What the finishing says counts, also where the page would count the words otherwise.
        val found = running(FinishStep.Lines).copy(noNotes = true)

        assertFalse(ready.copy(finishing = found).notesToCome)
        assertFalse(ready.copy(finishing = found).showsWritingNotes)
        assertTrue(ready.copy(finishing = running(FinishStep.Lines)).showsWritingNotes)
    }

    @Test
    fun `no notes are to come while the AI is off or not yet known`() {
        val finishing = ready.copy(finishing = running(FinishStep.Transcript))

        assertFalse(finishing.copy(notesAi = NotesAi.TurnedOff).showsWritingNotes)
        assertFalse(finishing.copy(notesAi = NotesAi.NoModel).showsWritingNotes)
        assertFalse(finishing.copy(notesAi = null).showsWritingNotes)
    }

    // ─── The recording's to-dos and ideas ────────────────────────────────────

    @Test
    fun `the to-dos are split by status, in transcript order, without done and dismissed ones`() {
        val todos = listOf(
            todo("a", TodoStatus.OPEN),
            todo("b", TodoStatus.IDEA),
            todo("c", TodoStatus.SUGGESTED),
            todo("d", TodoStatus.DONE),
            todo("e", TodoStatus.OPEN),
            todo("f", TodoStatus.DISMISSED),
            todo("g", TodoStatus.IDEA)
        )

        val split = RecordingTodos.of(todos)

        assertEquals(listOf("a", "e"), split.open.map { it.id })
        assertEquals(listOf("c"), split.suggested.map { it.id })
        assertEquals(listOf("b", "g"), split.ideas.map { it.id })
        assertFalse(split.isEmpty)
        assertTrue(RecordingTodos.of(listOf(todo("d", TodoStatus.DONE), todo("f", TodoStatus.DISMISSED))).isEmpty)
    }

    @Test
    fun `the delete sheet counts the open to-dos that stay on the list`() {
        val state = ready.copy(todos = RecordingTodos.of(listOf(todo("a", TodoStatus.OPEN), todo("b", TodoStatus.IDEA), todo("c", TodoStatus.OPEN))))

        assertEquals(2, state.keptTodos)
    }

    @Test
    fun `a new search counts what it added, to-dos and suggestions as to-dos`() {
        val before = listOf(todo("a", TodoStatus.OPEN), todo("b", TodoStatus.IDEA))
        val after = before + listOf(
            todo("c", TodoStatus.OPEN),
            todo("d", TodoStatus.SUGGESTED),
            todo("e", TodoStatus.IDEA)
        )

        assertEquals(TodosFound(todos = 2, ideas = 1), TodosFound.between(before, after))
        assertEquals(TodosFound(todos = 0, ideas = 0), TodosFound.between(before, before))
    }

    @Test
    fun `a new row already done or dismissed is not counted as found`() {
        val before = listOf(todo("a", TodoStatus.OPEN))
        val after = before + listOf(todo("b", TodoStatus.DONE), todo("c", TodoStatus.DISMISSED), todo("d", TodoStatus.OPEN))

        assertEquals(TodosFound(todos = 1, ideas = 0), TodosFound.between(before, after))
    }

    @Test
    fun `a to-do changed meanwhile is not counted as found`() {
        val before = listOf(todo("a", TodoStatus.SUGGESTED))

        assertEquals(TodosFound(todos = 0, ideas = 0), TodosFound.between(before, listOf(todo("a", TodoStatus.OPEN))))
    }
}
