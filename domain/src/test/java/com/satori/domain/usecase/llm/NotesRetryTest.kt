package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.LARGE_MODEL
import com.satori.domain.fakes.TwoModelLlmRepository
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.monitor.ThermalMonitor
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.todo.AnswerLog
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording with enough words for notes ends with a note unless the model gave nothing usable
 * twice (0.7.2): an answer that leaves no point (blank, cut off before its first, every point left
 * out) is asked once more, on the model in memory and by the same call, with the order to write what
 * is said about the subject on the user message's line of instructions; the second answer is kept
 * when it has a point. The prompts are the app's own (res/values and values-de); the transcripts
 * and answers are made up, of the shapes the 1B gives, none of them the owner's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesRetryTest {

    private val shipped = ShippedPrompts()
    private val settings = FakeSettingsRepository()

    // Made up: "This app records meetings, then gives notes and minutes. Its AI works offline on the
    // phone itself, no internet needed. It is a big help for people who find writing hard; they only
    // need to speak."
    private val malayalam = "ഈ ആപ്പ് മീറ്റിംഗുകൾ റെക്കോർഡ് ചെയ്യും, പിന്നെ നോട്ട്സും മിനിറ്റ്സും തരും. " +
        "ഇതിന്റെ എഐ ഫോണിൽ തന്നെ ഓഫ്‌ലൈനായി പ്രവർത്തിക്കും, ഇന്റർനെറ്റ് വേണ്ട. " +
        "എഴുതാൻ ബുദ്ധിമുട്ടുള്ള ആളുകൾക്ക് ഇത് വലിയ സഹായമാണ്, അവർക്ക് സംസാരിച്ചാൽ മതി."
    private val english = "This app records meetings and gives notes and minutes. Its AI runs offline on the phone, " +
        "so it works on a plane too. It helps people who find writing hard, they only have to speak."
    private val german = "Die App nimmt Besprechungen auf und gibt Notizen und Protokolle. Die KI läuft offline auf dem Handy, " +
        "also auch im Flugzeug. Sie hilft Leuten, denen Schreiben schwerfällt, sie müssen nur sprechen."

    /** Every point an echo of the order to read the Malayalam, the title only the kind of text. */
    private val allEchoes = """{"title": "Malayalam recording", "points": ["Text is Malayalam.", "Recorded in Malayalam."]}"""
    private val good = """{"title": "Offline meeting app", "points": ["AI writes the notes.", "It works offline on the phone."]}"""
    private val titleOnly = """{"title": "Offline meeting app", "points": []}"""
    private val cutOff = """{"title": "Offline meeting app", "points": ["AI writes the no"""

    /** The field log's lines of a run, the echoes' and the notes' in one list, in order. */
    private class Lines : NotesLog, PromptEchoLog {
        val heard = mutableListOf<String>()
        override fun log(event: NotesLog.Event) {
            heard += NotesLog.line(event)
        }

        override fun dropped(sessionId: String, count: Int, what: PromptEchoLog.What) {
            heard += PromptEchoLog.line(sessionId, count, what)
        }
    }

    /** The raw answers kept, and the steps' answers cleared, in order. */
    private class Kept : AnswerLog {
        val events = mutableListOf<String>()
        override fun todoAnswer(sessionId: String, window: Int, answer: String) {
            events += "todo $sessionId $window"
        }

        override fun answer(sessionId: String, call: String, answer: String) {
            events += "$sessionId-$call: $answer"
        }

        override fun clear(sessionId: String, calls: String) {
            events += "clear $sessionId-$calls"
        }
    }

    private val lines = Lines()
    private val kept = Kept()

    private fun finalNote(llm: LlmRepository, text: String = malayalam, language: String = "en") = runBlocking {
        GenerateFinalInsightUseCase(llm, settings, shipped, lines, lines, kept)(
            pendingText = text, sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = language
        )
    }

    private val retryOrder = "write what is said about the subject, never about this recording, its text or its language."

    @Test
    fun `an answer whose points all go is asked once more, and the second answer is the note`() {
        val llm = ScriptedLlmRepository(listOf(allEchoes, good))

        val note = finalNote(llm).getOrThrow()!!

        assertEquals("Offline meeting app", note.title)
        assertEquals("- AI writes the notes.\n- It works offline on the phone.", note.content)
        assertEquals(
            listOf(
                "Notes of s1: no point left in the answer (every point left out), asking once more",
                "Notes of s1: the second answer kept, 2 lines",
                "Dropped 2 note lines that repeat their prompt in s1",
                "Dropped 1 note titles that name only the kind of text in s1"
            ),
            lines.heard
        )
    }

    @Test
    fun `the second call adds its order to the user message's line of instructions, the system prompt stays`() {
        val llm = ScriptedLlmRepository(listOf(allEchoes, good))

        finalNote(llm)

        val (first, second) = llm.prompts
        assertFalse(first.first, first.first.contains(retryOrder))
        val line = second.first.lines().first()
        assertTrue(line, line.startsWith("Instruction, never to be repeated in your answer: read the Malayalam below and write in English."))
        assertTrue(line, line.endsWith(" Write what is said about the subject, never about this recording, its text or its language."))
        assertEquals(first.second, second.second)
        assertEquals(shipped.getPromptForMode(RecordingMode.SIMPLE_LISTENING, "en"), second.second)
    }

    @Test
    fun `an answer with a point is the note, and the model is asked once`() {
        val llm = ScriptedLlmRepository(listOf(good))

        assertNotNull(finalNote(llm).getOrThrow())
        assertEquals(1, llm.prompts.size)
        assertTrue(lines.heard.isEmpty())
    }

    @Test
    fun `two answers without a point give no note, not even one of a title alone`() {
        val llm = ScriptedLlmRepository(listOf(titleOnly, titleOnly))

        assertNull(finalNote(llm).getOrThrow())
        assertEquals(2, llm.prompts.size)
        assertEquals(
            listOf(
                "Notes of s1: no point left in the answer (no point in full), asking once more",
                "Notes of s1: no note, no point in 2 answers"
            ),
            lines.heard
        )
    }

    @Test
    fun `a blank answer and one cut off before its first point are asked once more`() {
        assertNotNull(finalNote(ScriptedLlmRepository(listOf("", good))).getOrThrow())
        assertNotNull(finalNote(ScriptedLlmRepository(listOf(cutOff, good))).getOrThrow())

        assertEquals(
            listOf(
                "Notes of s1: no point left in the answer (it was blank), asking once more",
                "Notes of s1: the second answer kept, 2 lines",
                "Notes of s1: no point left in the answer (no point in full), asking once more",
                "Notes of s1: the second answer kept, 2 lines"
            ),
            lines.heard
        )
    }

    @Test
    fun `a model that fails is not asked again, and the failure stands`() {
        val llm = ScriptedLlmRepository(listOf(null))

        assertTrue(finalNote(llm).isFailure)
        assertEquals(1, llm.prompts.size)
        assertTrue(lines.heard.isEmpty())
    }

    @Test
    fun `each answer is kept as the model gave it, under its call's tag, once the step's old ones went`() {
        finalNote(ScriptedLlmRepository(listOf(allEchoes, good)))

        assertEquals(listOf("clear s1-notes-final", "s1-notes-final-0: $allEchoes", "s1-notes-final-1: $good"), kept.events)
    }

    @Test
    fun `the second call runs on the model in memory, Gemma 4 E2B too, without a load`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(allEchoes, good))
        llm.switchModel(LARGE_MODEL)

        assertNotNull(finalNote(llm).getOrThrow())
        assertEquals(listOf("switch $LARGE_MODEL", "prompt $LARGE_MODEL", "prompt $LARGE_MODEL"), llm.log)
    }

    @Test
    fun `an English recording's notes have no line until the second call, which has the order alone`() {
        val llm = ScriptedLlmRepository(listOf(titleOnly, good))

        assertNotNull(finalNote(llm, english, "en").getOrThrow())

        assertFalse(llm.prompts[0].first.contains("Instruction"))
        assertEquals(
            "Instruction, never to be repeated in your answer: $retryOrder",
            llm.prompts[1].first.lines().first()
        )
    }

    @Test
    fun `a German recording's second call has the order in German, in the neutral infinitive`() {
        val llm = ScriptedLlmRepository(listOf("""{"titel": "Offline-App", "punkte": []}""", """{"titel": "Offline-App", "punkte": ["Die KI schreibt die Notizen."]}"""))

        val note = finalNote(llm, german, "de").getOrThrow()!!

        assertEquals("- Die KI schreibt die Notizen.", note.content)
        assertEquals(
            "Anweisung, nie in der Antwort zu wiederholen: festhalten, was über das Thema gesagt wird, " +
                "nie etwas über diese Aufnahme, ihren Text oder ihre Sprache.",
            llm.prompts[1].first.lines().first()
        )
    }

    @Test
    fun `a point that repeats the retry's order goes, one about the subject stays`() {
        val retry = NotesTopic.prompt(malayalam, null, RecordingMode.SIMPLE_LISTENING, "en", shipped, retry = true).instructions

        assertTrue(PromptEcho.isEcho("Write what is said about the subject.", retry))
        assertTrue(PromptEcho.isEcho("Never about this recording, its text or its language.", retry))
        assertTrue(PromptEcho.isEcho("Recorded in Malayalam.", retry))
        assertFalse(PromptEcho.isEcho("The subject is an app that writes meeting notes.", retry))
        assertFalse(PromptEcho.isEcho("AI writes the notes.", retry))
    }

    // ─── The whole pass ──────────────────────────────────────────────────────────

    // Twelve lines of about 500 characters: two parts and a merge.
    private val longTalk = (1..12).map {
        segment("The workshop got a new bench, and the old shelves go to the basement next week. ".repeat(6), at = it.toLong())
    }
    private val noPoint = """{"title": "Workshop", "points": ["..."]}"""
    private val part = """{"title": "Workshop tidy-up", "points": ["Move the old shelves to the basement.", "Set up the new bench."]}"""

    // The second part's own note: since 0.7.3 a part that repeats the one before is none (NoteRepeats).
    private val secondPart = """{"title": "Basement shelves", "points": ["Paint the shelves in the basement.", "Label the paint tins."]}"""
    private val merged = """{"title": "Workshop tidy-up", "points": ["Shelves to the basement, a new bench."]}"""

    private fun pass(llm: LlmRepository, segments: List<com.satori.domain.model.TranscriptionSegment>, parts: MutableList<LlmInsight>) = runBlocking {
        GenerateBatchInsightUseCase(llm, settings, shipped, echoLog = lines, notesLog = lines, answerLog = kept)(
            segments, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en", onChunkInsight = { parts += it }
        )
    }

    @Test
    fun `a part and the merge whose answers leave no point are each asked once more`() {
        val llm = ScriptedLlmRepository(listOf(noPoint, part, secondPart, noPoint, merged))
        val parts = mutableListOf<LlmInsight>()

        val summary = pass(llm, longTalk, parts).getOrThrow()!!

        assertEquals(2, parts.size)
        assertEquals("- Shelves to the basement, a new bench.", summary.content)
        assertEquals(5, llm.prompts.size)
        assertTrue(llm.prompts[1].first.startsWith("Instruction, never to be repeated in your answer: $retryOrder"))
        assertTrue(llm.prompts[4].first.startsWith("Instruction, never to be repeated in your answer: $retryOrder"))
        assertEquals(
            listOf(
                "Notes of s1, part 1 of 2: no point left in the answer (every point left out), asking once more",
                "Notes of s1, part 1 of 2: the second answer kept, 2 lines",
                "Notes of s1, the merge: no point left in the answer (every point left out), asking once more",
                "Notes of s1, the merge: the second answer kept, 1 line",
                "Dropped 2 note lines with no words in s1"
            ),
            lines.heard
        )
        assertEquals(
            listOf("clear s1-notes", "s1-notes-part1-0", "s1-notes-part1-1", "s1-notes-part2-0", "s1-notes-merge-0", "s1-notes-merge-1"),
            kept.events.map { it.substringBefore(":") }
        )
    }

    @Test
    fun `a pass in one part on a Malayalam transcript, as after Stop's whole-file pass, is asked once more too`() {
        val lines = malayalam.split(". ").mapIndexed { i, text -> segment(text, at = i.toLong()) }
        val llm = ScriptedLlmRepository(listOf(allEchoes, good))

        val note = pass(llm, lines, mutableListOf()).getOrThrow()!!

        assertEquals("Offline meeting app", note.title)
        assertEquals(2, llm.prompts.size)
        assertEquals("Notes of s1: no point left in the answer (every point left out), asking once more", this.lines.heard.first())
    }

    @Test
    fun `a merge with no point in either answer gives no summary, and the parts stay the notes`() {
        val llm = ScriptedLlmRepository(listOf(part, secondPart, noPoint, noPoint))
        val parts = mutableListOf<LlmInsight>()

        assertNull(pass(llm, longTalk, parts).getOrThrow())
        assertEquals(2, parts.size)
        assertTrue(lines.heard.contains("Notes of s1, the merge: no note, no point in 2 answers"))
    }

    // ─── The review of 0.7.2 ────────────────────────────────────────────────────

    @Test
    fun `a second answer that only says this is a recording, or that nothing was found, is no note either`() {
        // A junk transcript (the Malayalam model on other speech): the first answer's points name the
        // language, the second's only say what the text is.
        val selfDescribed = """{"title": "Audio Recording", "points": ["This is an audio recording.", "Transcript of the recording."]}"""
        assertNull(finalNote(ScriptedLlmRepository(listOf(allEchoes, selfDescribed))).getOrThrow())
        assertEquals("Notes of s1: no note, no point in 2 answers", lines.heard[1])

        val placeholder = """{"title": "Chat", "points": ["..."]}"""
        val nothing = """{"title": "Unclear Topic", "points": ["The subject is unclear.", "Nothing about this recording.", "Subject not mentioned."]}"""
        assertNull(finalNote(ScriptedLlmRepository(listOf(placeholder, nothing)), english, "en").getOrThrow())
        val languageOnly = """{"title": "English Speech", "points": ["The recording is in English.", "Spoken English."]}"""
        assertNull(finalNote(ScriptedLlmRepository(listOf(placeholder, languageOnly)), english, "en").getOrThrow())

        val germanFirst = """{"titel": "Offline-App", "punkte": []}"""
        val germanNothing = """{"titel": "Thema", "punkte": ["Das Thema ist unklar.", "Die Aufnahme ist auf Deutsch.", "Nichts erwähnt."]}"""
        assertNull(finalNote(ScriptedLlmRepository(listOf(germanFirst, germanNothing)), german, "de").getOrThrow())
        assertEquals(4, lines.heard.count { it == "Notes of s1: no note, no point in 2 answers" })
    }

    // Forty lines of about 500 characters: four parts and a merge.
    private val longerTalk = (1..40).map {
        segment("The workshop got a new bench, and the old shelves go to the basement next week. ".repeat(6), at = it.toLong())
    }

    @Test
    fun `a pass asks no part again once two calls in a row gave no point in either answer`() {
        // A long junk transcript: every answer is empty of points.
        val llm = ScriptedLlmRepository(listOf(noPoint))

        assertNull(pass(llm, longerTalk, mutableListOf()).getOrThrow())

        // Parts 1 and 2 asked twice, parts 3 and 4 once: 6 calls, not 8.
        assertEquals(6, llm.prompts.size)
        assertEquals(
            listOf(
                "Notes of s1, part 1 of 4: no point left in the answer (every point left out), asking once more",
                "Notes of s1, part 1 of 4: no note, no point in 2 answers",
                "Notes of s1, part 2 of 4: no point left in the answer (every point left out), asking once more",
                "Notes of s1, part 2 of 4: no note, no point in 2 answers",
                "Notes of s1, part 3 of 4: no note, no point left in the answer (every point left out), " +
                    "and no second try after 2 calls in a row gave none in 2 answers",
                "Notes of s1, part 4 of 4: no note, no point left in the answer (every point left out), " +
                    "and no second try after 2 calls in a row gave none in 2 answers",
                "Dropped 6 note lines with no words in s1"
            ),
            lines.heard
        )
    }

    @Test
    fun `a call with a point starts the count again, so a later part is still asked twice`() {
        // Part 1 empty twice, part 2 fine, parts 3 and 4 empty twice each: the one part is the notes,
        // and since 0.7.3 no summary says it again.
        val llm = ScriptedLlmRepository(listOf(noPoint, noPoint, part, noPoint, noPoint, noPoint, noPoint))
        val parts = mutableListOf<LlmInsight>()

        assertNull(pass(llm, longerTalk, parts).getOrThrow())

        assertEquals("Workshop tidy-up", parts.single().title)
        assertEquals(7, llm.prompts.size)
        assertEquals(3, lines.heard.count { it.endsWith("no note, no point in 2 answers") })
        assertTrue(lines.heard.none { it.contains("no second try") })
    }

    @Test
    fun `a pass's second call waits the cooling gap of a hot phone first, as between two parts`() = runTest {
        val events = mutableListOf<String>()
        val scripted = ScriptedLlmRepository(listOf(noPoint, part))
        val llm = object : LlmRepository by scripted {
            override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> =
                scripted.generateInsight(text, systemPrompt, maxTokens, grammar).onStart { events += "prompt at $currentTime" }
        }
        val hot = object : ThermalMonitor {
            override fun isOverheating(): Boolean {
                events += "heat at $currentTime"
                return true
            }
        }
        val segments = malayalam.split(". ").mapIndexed { i, text -> segment(text, at = i.toLong()) }

        GenerateBatchInsightUseCase(llm, settings, shipped, hot, echoLog = lines, notesLog = lines)(
            segments, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en"
        ).getOrThrow()

        // The gap of 1 s before the second call, and one after the part, as after every part.
        assertEquals(listOf("prompt at 0", "heat at 0", "prompt at 1000", "heat at 1000"), events)
    }

    @Test
    fun `a pass's and a name's second calls run on Gemma 4 E2B in memory, without a load`() = runBlocking {
        val forPass = TwoModelLlmRepository(listOf(noPoint, part, secondPart, merged))
        forPass.switchModel(LARGE_MODEL)
        assertNotNull(pass(forPass, longTalk, mutableListOf()).getOrThrow())
        assertEquals(listOf("switch $LARGE_MODEL") + List(4) { "prompt $LARGE_MODEL" }, forPass.log)

        val forName = TwoModelLlmRepository(listOf("Summary", "Workshop Shelves Move"))
        forName.switchModel(LARGE_MODEL)
        val repo = FakeTranscriptionRepository(session(), longTalk)
        val named = AutoNameSessionUseCase(repo, GenerateSessionTitleUseCase(forName, shipped, lines, lines), lines)("s1", "en")
        assertEquals("Workshop Shelves Move", named.getOrThrow())
        assertEquals(listOf("switch $LARGE_MODEL", "prompt $LARGE_MODEL", "prompt $LARGE_MODEL"), forName.log)
    }
}
