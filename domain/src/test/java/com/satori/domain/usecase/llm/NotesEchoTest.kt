package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordingMode
import com.satori.domain.usecase.sync.SyncSttLlmUseCase
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A note never keeps what its prompt's instructions said (brief 0.7.1, part A.1): the points the
 * 1B made of the note on a Malayalam text's language on 2026-10-16 ("Text is Malayalam.",
 * "Malayalam language used.") are left out of the last note, of each part and the merge of the
 * whole pass, of a live note and of a name, and the field log hears how many. An English
 * recording's notes, whose calls carry no instructions, are as they were. The sentences and
 * answers are made up.
 */
class NotesEchoTest {

    /** What the field log heard. */
    private class Echoes : PromptEchoLog {
        val heard = mutableListOf<Triple<String, Int, PromptEchoLog.What>>()
        override fun dropped(sessionId: String, count: Int, what: PromptEchoLog.What) {
            heard += Triple(sessionId, count, what)
        }
    }

    private val echoes = Echoes()

    // Tomorrow morning call the doctor, then send the meeting's report.
    private val malayalam = "നാളെ രാവിലെ ഡോക്ടറെ വിളിക്കണം, പിന്നെ മീറ്റിംഗിന്റെ റിപ്പോർട്ട് അയക്കണം."

    private val english = "Tomorrow morning I have to call the doctor, and then I send the report of the meeting to everyone " +
        "on the team before lunch."

    private val echoingAnswer = echoing("Call the doctor tomorrow morning.", "Send the report of the meeting.")

    /** An answer of the title and the two points that repeat the line of instructions, and [points]. */
    private fun echoing(vararg points: String) =
        """{"title": "Malayalam language", "points": ["Text is Malayalam.", "Malayalam language used.", """ +
            points.joinToString { "\"$it\"" } + "]}"

    private fun malayalam(count: Int) = List(count) { malayalam }.joinToString(" ")

    @Test
    fun `the last note of a Malayalam tail keeps none of its points or its title that repeat the line of instructions`() = runBlocking {
        val note = GenerateFinalInsightUseCase(FakeLlmRepository(answer = echoingAnswer), FakeSettingsRepository(), FakeResourceProvider(), echoes)(
            pendingText = malayalam(3), sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = "en"
        ).getOrThrow()!!

        assertNull(note.title)
        assertEquals("- Call the doctor tomorrow morning.\n- Send the report of the meeting.", note.content)
        assertEquals(listOf(Triple("s1", 3, PromptEchoLog.What.NOTE_LINES)), echoes.heard)
    }

    @Test
    fun `the last note of a Malayalam tail keeps no point or title that only names its translation, audio or speaker`() = runBlocking {
        // Review of 0.7.1: the order to read the Malayalam and write in English invites these.
        val answer = """{"title": "Malayalam Translation", "points": ["Translated from Malayalam to English.", """ +
            """"The speaker speaks Malayalam.", "Malayalam audio.", "Call the doctor tomorrow morning."]}"""

        val note = GenerateFinalInsightUseCase(FakeLlmRepository(answer = answer), FakeSettingsRepository(), FakeResourceProvider(), echoes)(
            pendingText = malayalam(3), sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = "en"
        ).getOrThrow()!!

        assertNull(note.title)
        assertEquals("- Call the doctor tomorrow morning.", note.content)
        assertEquals(listOf(Triple("s1", 4, PromptEchoLog.What.NOTE_LINES)), echoes.heard)
    }

    @Test
    fun `an English recording's last note, whose call has no instructions, keeps every point`() = runBlocking {
        val note = GenerateFinalInsightUseCase(FakeLlmRepository(answer = echoingAnswer), FakeSettingsRepository(), FakeResourceProvider(), echoes)(
            pendingText = english, sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = "en"
        ).getOrThrow()!!

        assertEquals("Malayalam language", note.title)
        assertEquals(4, note.content.lines().size)
        assertTrue(echoes.heard.isEmpty())
    }

    @Test
    fun `a note with nothing but echoes, asked again with the same answer, is none`() = runBlocking {
        val answer = """{"title": "Malayalam language", "points": ["Text is Malayalam.", "Written in English."]}"""
        val llm = FakeLlmRepository(answer = answer)

        val note = GenerateFinalInsightUseCase(llm, FakeSettingsRepository(), FakeResourceProvider(), echoes)(
            pendingText = malayalam(3), sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = "en"
        ).getOrThrow()

        assertNull(note)
        // Since 0.7.2 an answer with no point is asked once more; both answers' echoes are counted, once.
        assertEquals(2, llm.prompts.size)
        assertEquals(listOf(Triple("s1", 6, PromptEchoLog.What.NOTE_LINES)), echoes.heard)
    }

    @Test
    fun `the whole pass leaves the echoes out of each part, merges the parts without them, and the log hears them once`() = runBlocking {
        // Twelve lines of Malayalam are three parts (MalayalamNotesTest), each answered with three
        // echoes and points of its own (since 0.7.3 a part that repeats one before it is none), then
        // the merge, which reads the parts' English points: its call has nothing to repeat.
        val llm = ScriptedLlmRepository(
            listOf(
                echoingAnswer,
                echoing("Book the taxi for the airport.", "Pack the camera bag."),
                echoing("Water the plants on Sunday.", "Pay the electricity bill."),
                """{"title": "Doctor and report", "points": ["Call the doctor, then send the report."]}"""
            )
        )
        val lines = (1..12).map { segment(malayalam(7), at = it.toLong()) }
        val parts = mutableListOf<com.satori.domain.model.LlmInsight>()

        val merged = GenerateBatchInsightUseCase(llm, FakeSettingsRepository(), FakeResourceProvider(), echoLog = echoes)(
            lines, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en", onChunkInsight = { parts += it }
        ).getOrThrow()!!

        assertEquals(4, llm.prompts.size)
        assertEquals(3, parts.size)
        assertEquals(
            listOf(
                "- Call the doctor tomorrow morning.\n- Send the report of the meeting.",
                "- Book the taxi for the airport.\n- Pack the camera bag.",
                "- Water the plants on Sunday.\n- Pay the electricity bill."
            ),
            parts.map { it.content }
        )
        assertTrue(parts.all { it.title == null })
        assertEquals("- Call the doctor, then send the report.", merged.content)
        assertEquals("Doctor and report", merged.title)
        // The merge read the parts without their echoes.
        val mergeCall = llm.prompts.last().first
        assertTrue(mergeCall, !mergeCall.contains("Text is Malayalam") && !mergeCall.contains("language used"))
        assertEquals(listOf(Triple("s1", 3 * 3, PromptEchoLog.What.NOTE_LINES)), echoes.heard)
    }

    @Test
    fun `a live note of German written in English leaves out a point that says only what it was written from`() = runBlocking {
        val llm = FakeLlmRepository(answer = """{"title": "Room booked", "points": ["German text.", "The room for Friday is booked."]}""")
        val said = "Wir haben den Raum für das Treffen am Freitag gebucht und die Liste ist jetzt fertig"

        val notes = SyncSttLlmUseCase(llm, FakeSettingsRepository(AppSettings(simpleListeningIntervalSeconds = 0)), FakeResourceProvider(), echoes)(
            transcriptionFlow = flowOf(segment(said, at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            notesLanguage = { "en" }
        ).toList()

        assertEquals("- The room for Friday is booked.", notes.single().content)
        assertTrue(llm.prompts.single().first.startsWith("Instruction, never to be repeated in your answer: read the German below"))
        assertEquals(listOf(Triple("s1", 1, PromptEchoLog.What.NOTE_LINES)), echoes.heard)
    }

    @Test
    fun `a Malayalam recording without readings is named after its notes' title, and asked of its Malayalam a name that repeats its line of instructions is none`() = runBlocking {
        val lines = (1..6).map { segment(malayalam, at = it.toLong()) }
        val notes = listOf(insight("Doctor and report", "- Call the doctor tomorrow morning.", at = 9L))
        val recording = FakeTranscriptionRepository(session("s1", name = null), lines, notes)
        val llm = ScriptedLlmRepository(listOf("Topic in English"))

        val named = AutoNameSessionUseCase(recording, GenerateSessionTitleUseCase(llm, FakeResourceProvider(), echoes))("s1", "en")

        // Since 0.8.0 its name is asked of its readings alone (design R6): with none, the model isn't asked (D7).
        assertEquals("Doctor and report", named.getOrThrow())
        assertTrue(llm.prompts.isEmpty())

        // Asked of the Malayalam itself, as the debug rerun's reader=off asks, an answer that repeats the line is none.
        val attempt = GenerateSessionTitleUseCase(llm, FakeResourceProvider(), echoes).attempt(lines, emptyList(), "en")
        assertEquals(GenerateSessionTitleUseCase.Attempt.Rejected(GenerateSessionTitleUseCase.Rejection.ECHO), attempt)
        assertEquals(listOf(Triple("s1", 1, PromptEchoLog.What.NAMES)), echoes.heard)
    }

    @Test
    fun `an English recording's name is never taken for an echo`() = runBlocking {
        val lines = listOf(segment(english, at = 1L))
        val llm = ScriptedLlmRepository(listOf("Topic in English"))

        val title = GenerateSessionTitleUseCase(llm, FakeResourceProvider(), echoes)(lines, emptyList(), "en").getOrThrow()

        assertEquals("Topic in English", title)
        assertTrue(echoes.heard.isEmpty())
    }
}
