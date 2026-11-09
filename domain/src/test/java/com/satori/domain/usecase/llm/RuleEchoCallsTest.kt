package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.LlmRepository
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
 * Satori's own rules are an echo in every notes and name call (0.7.3): the last note after Stop, a
 * part of the whole pass, a live note and a recording's name each leave out what gives back the
 * rules of their system prompt, say so on SatoriNotes, and a note left with no point is asked once
 * more as in 0.7.2. The prompts are the app's own (res/values and values-de); the transcripts and
 * answers are made up, of the shapes the 1B gave on 2026-10-18 and 19, none of them the owner's.
 */
class RuleEchoCallsTest {

    private val shipped = ShippedPrompts()
    private val settings = FakeSettingsRepository(AppSettings(simpleListeningIntervalSeconds = 0))

    // Made up: "Tomorrow at nine the plumber comes and fixes the kitchen tap, then the boiler is serviced. Before
    // that the kitchen must be cleaned and the things moved. He said the work will be done before noon."
    private val malayalam = "നാളെ രാവിലെ ഒൻപത് മണിക്ക് പ്ലംബർ വന്ന് അടുക്കളയിലെ ടാപ്പ് ശരിയാക്കും, " +
        "പിന്നെ ബോയിലറും സർവീസ് ചെയ്യും. അതിന് മുൻപ് അടുക്കള വൃത്തിയാക്കണം, സാധനങ്ങൾ മാറ്റിവെക്കണം. " +
        "ഉച്ചയ്ക്ക് മുൻപ് പണി തീരും എന്ന് പറഞ്ഞു."
    private val english = "Tomorrow the plumber comes at nine to fix the kitchen tap, and then the boiler gets its service. " +
        "Before that the kitchen has to be cleared and the things under the sink moved."
    private val german = "Morgen kommt um neun der Installateur und repariert den Wasserhahn in der Küche, danach wird die " +
        "Heizung gewartet. Vorher muss die Küche aufgeräumt und alles unter der Spüle weggeräumt werden."

    /** The rules of the notes prompt, given back as a note's points: the shape of 2026-10-18 and 19. */
    private val rules = """{"title": "Plain statements", "points": ["Plain statements.", "First person.", "One idea per point.", "No filler words.", "Keep it concise."]}"""
    private val germanRules = """{"titel": "Schlichte Aussagen", "punkte": ["Schlichte Aussagen.", "Ein Gedanke pro Punkt.", "Keine Füllwörter."]}"""
    private val plumber = """{"title": "Plumber visit", "points": ["The plumber comes at nine to fix the kitchen tap.", "Clear the kitchen first."]}"""

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

    private val lines = Lines()

    private fun finalNote(llm: LlmRepository, text: String, language: String) = runBlocking {
        GenerateFinalInsightUseCase(llm, settings, shipped, lines, lines)(
            pendingText = text, sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = language
        ).getOrThrow()
    }

    @Test
    fun `a Malayalam note of the rules is asked once more, and the second answer is the note`() {
        val llm = ScriptedLlmRepository(listOf(rules, plumber))

        val note = finalNote(llm, malayalam, "en")!!

        assertEquals("Plumber visit", note.title)
        assertEquals(2, note.content.lines().size)
        assertEquals(
            listOf(
                "Notes of s1: no point left in the answer (every point left out), asking once more",
                "Notes of s1: the second answer kept, 2 lines",
                "Dropped 6 note lines that repeat the rules of their prompt in s1"
            ),
            lines.heard
        )
        // The system prompt stays; the second call's user message has the order.
        assertEquals(llm.prompts[0].second, llm.prompts[1].second)
    }

    @Test
    fun `the rules twice give no note, an honest empty page`() {
        val llm = ScriptedLlmRepository(listOf(rules, rules))

        assertNull(finalNote(llm, malayalam, "en"))
        assertEquals(2, llm.prompts.size)
        assertEquals(
            listOf(
                "Notes of s1: no point left in the answer (every point left out), asking once more",
                "Notes of s1: no note, no point in 2 answers",
                "Dropped 12 note lines that repeat the rules of their prompt in s1"
            ),
            lines.heard
        )
    }

    @Test
    fun `an English and a German note lose the rules and keep what was said`() {
        val mixed = """{"title": "Plumber visit", "points": ["The plumber comes at nine.", "One idea per point.", "Clear the kitchen first."]}"""
        val englishNote = finalNote(ScriptedLlmRepository(listOf(mixed)), english, "en")!!
        assertEquals("- The plumber comes at nine.\n- Clear the kitchen first.", englishNote.content)

        val germanMixed = """{"titel": "Installateur", "punkte": ["Der Installateur kommt um neun.", "Kurz und knapp.", "Vorher die Küche aufräumen."]}"""
        val germanNote = finalNote(ScriptedLlmRepository(listOf(germanMixed)), german, "de")!!
        assertEquals("- Der Installateur kommt um neun.\n- Vorher die Küche aufräumen.", germanNote.content)
        assertEquals(
            listOf("Dropped 1 note lines that repeat the rules of their prompt in s1", "Dropped 1 note lines that repeat the rules of their prompt in s1"),
            lines.heard
        )
    }

    @Test
    fun `a German note of the German rules is asked once more`() {
        val llm = ScriptedLlmRepository(listOf(germanRules, """{"titel": "Installateur", "punkte": ["Der Installateur kommt um neun."]}"""))

        assertEquals("- Der Installateur kommt um neun.", finalNote(llm, german, "de")!!.content)
        assertTrue(lines.heard.toString(), "Dropped 4 note lines that repeat the rules of their prompt in s1" in lines.heard)
    }

    @Test
    fun `a part of the whole pass that gives the rules back is asked once more, and its second answer is a part`() = runBlocking {
        val segments = malayalam.split(", ").mapIndexed { i, text -> segment(text, at = i.toLong()) }
        val llm = ScriptedLlmRepository(listOf(rules, plumber))
        val parts = mutableListOf<LlmInsight>()

        val summary = GenerateBatchInsightUseCase(llm, settings, shipped, echoLog = lines, notesLog = lines)(
            segments, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en", onChunkInsight = { parts += it }
        ).getOrThrow()!!

        assertEquals("Plumber visit", summary.title)
        assertEquals("Dropped 6 note lines that repeat the rules of their prompt in s1", lines.heard.last())
    }

    @Test
    fun `a live note leaves the rules out and says so`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf("""{"title": "Plumber visit", "points": ["The plumber comes at nine.", "Plain statements."]}"""))

        val notes = SyncSttLlmUseCase(llm, settings, shipped, lines, lines)(
            transcriptionFlow = flowOf(segment(english, at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en"
        ).toList()

        assertEquals("- The plumber comes at nine.", notes.single().content)
        assertEquals(listOf("Dropped 1 note lines that repeat the rules of their prompt in s1"), lines.heard)
    }

    @Test
    fun `a name of the rules is no name, says so, and the name is asked again from the transcript`() = runBlocking {
        val segments = english.split(", ").mapIndexed { i, text -> segment(text, at = i.toLong()) }
        val llm = ScriptedLlmRepository(listOf("Subject Line of an Email", "Plumber and Boiler Visit"))
        val titles = GenerateSessionTitleUseCase(llm, shipped, lines, lines)

        val first = titles.attempt(segments, emptyList(), "en", answer = 0)
        val second = titles.attempt(segments, emptyList(), "en", answer = 1)

        assertEquals(GenerateSessionTitleUseCase.Attempt.Rejected(GenerateSessionTitleUseCase.Rejection.RULES), first)
        assertEquals(GenerateSessionTitleUseCase.Attempt.Titled("Plumber and Boiler Visit"), second)
        assertEquals(listOf("Dropped 1 names that repeat the rules of their prompt in s1"), lines.heard)
    }

    @Test
    fun `a name of words the speaker said is the speaker's`() = runBlocking {
        val said = "For the course I have to write in plain statements, one idea per point, about the boiler."
        val segments = listOf(segment(said, at = 1L))

        val titled = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Plain Statements Course")), shipped, lines, lines)
            .attempt(segments, emptyList(), "en")

        assertEquals(GenerateSessionTitleUseCase.Attempt.Titled("Plain Statements Course"), titled)
        assertTrue(lines.heard.isEmpty())
    }
    @Test
    fun `an answer without the grammar, prose, loses its lines of the rules too, and one of nothing else is no note`() {
        val checks = AnswerChecks(rules = PromptRules.of(shipped.getPromptForMode(RecordingMode.SIMPLE_LISTENING, "en")))
        val keys = InsightFormat.Keys.ENGLISH

        val mixed = InsightOutputParser.parse("Plain statements.\nThe plumber comes at nine.", RecordingMode.SIMPLE_LISTENING, keys, checks)
        assertEquals("The plumber comes at nine.", mixed.content)
        assertEquals(1, mixed.ruleEchoes)

        val junk = InsightOutputParser.parse("One idea per point.\nFirst person.", RecordingMode.SIMPLE_LISTENING, keys, checks)
        assertTrue(junk.isEmpty)
        assertEquals(2, junk.droppedLines)
        // Prose with nothing left out is the note as the model wrote it.
        assertEquals("Plumber visit\nThe plumber comes at nine.", InsightOutputParser.parse("Plumber visit\nThe plumber comes at nine.", RecordingMode.SIMPLE_LISTENING, keys, checks).content)
    }

    @Test
    fun `a note title of the rules, stored by an earlier version, never stands in for the name`() = runBlocking {
        val segments = english.split(", ").mapIndexed { i, text -> segment(text, at = i.toLong()) }
        val notes = listOf(insight("Plain statements", "- The plumber comes at nine.", at = 1L), insight("Plumber visit", "- Clear the kitchen first.", at = 2L))
        val repository = FakeTranscriptionRepository(session(), segments, notes)

        val named = AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Notes", "Recording")), shipped, lines, lines), lines)("s1", "en")

        assertEquals("Plumber visit", named.getOrThrow())
    }

    // The review of 0.7.3.

    @Test
    fun `a note title that narrates the task goes as the model wrote it, though what is left of it once cleaned says nothing`() {
        val narrated = """{"title": "Goal: Provide English notes", "points": ["The plumber comes at nine to fix the tap.", "Clear the kitchen first."]}"""

        val note = finalNote(ScriptedLlmRepository(listOf(narrated)), malayalam, "en")!!

        assertNull(note.title)
        assertEquals(2, note.content.lines().size)
        assertEquals(listOf("Dropped 1 note lines that repeat the rules of their prompt in s1"), lines.heard)
    }

    @Test
    fun `a note title of the rules goes as the model wrote it, though its kind word cut leaves words of no rule`() {
        // "Short Plain Notes" is "Short Plain" once its kind of text went from its end.
        val ruled = """{"title": "Short Plain Notes", "points": ["The plumber comes at nine to fix the tap."]}"""

        val note = finalNote(ScriptedLlmRepository(listOf(ruled)), malayalam, "en")!!

        assertNull(note.title)
        assertEquals(listOf("Dropped 1 note lines that repeat the rules of their prompt in s1"), lines.heard)
    }

    @Test
    fun `a name is held to the rules as the model wrote it too`() = runBlocking {
        val segments = malayalam.split(", ").mapIndexed { i, text -> segment(text, at = i.toLong()) }
        val titles = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Short Plain Notes", "English Notes Provided", "Final Idea")), shipped, lines, lines)

        for (answer in 0..2) {
            assertEquals(
                GenerateSessionTitleUseCase.Attempt.Rejected(GenerateSessionTitleUseCase.Rejection.RULES),
                titles.attempt(segments, emptyList(), "en", answer = answer)
            )
        }
    }

    // Made up: an English talk whose first part reads a style guide's rules aloud and whose
    // second doesn't; each part is about 4,000 characters, a part of the pass each.
    private val guide = (1..4).map {
        segment("For the club letters we write plain statements, one idea per point, and we keep the names of the members. ".repeat(10), at = it.toLong())
    }
    private val boiler = (5..8).map {
        segment("The boiler in the club house needs a service before the winter, and the plumber comes on Monday. ".repeat(10), at = it.toLong())
    }

    @Test
    fun `in a pass the words a part says together are the speaker's in that part, not in another`() = runBlocking {
        val guidePart = """{"title": "Club letters", "points": ["Plain statements, one idea per point.", "Keep the names of the members."]}"""
        val boilerPart = """{"title": "Boiler service", "points": ["One idea per point.", "The plumber comes on Monday."]}"""
        val merged = """{"title": "Club house", "points": ["The boiler gets a service before the winter.", "The plumber comes on Monday."]}"""
        val parts = mutableListOf<LlmInsight>()

        GenerateBatchInsightUseCase(ScriptedLlmRepository(listOf(guidePart, boilerPart, merged)), settings, shipped, echoLog = lines, notesLog = lines)(
            guide + boiler, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en", onChunkInsight = { parts += it }
        ).getOrThrow()

        assertEquals(
            listOf("- Plain statements, one idea per point.\n- Keep the names of the members.", "- The plumber comes on Monday."),
            parts.map { it.content }
        )
        assertEquals(listOf("Dropped 1 note lines that repeat the rules of their prompt in s1"), lines.heard)
    }

    @Test
    fun `the last note after Stop keeps a rule the speaker said in those words`() {
        val said = "Before the boiler service he said one thing to me, keep it concise, as the plumber comes at nine on Monday morning."
        val answer = """{"title": "Boiler service", "points": ["Keep it concise.", "The plumber comes at nine on Monday."]}"""

        val note = finalNote(ScriptedLlmRepository(listOf(answer)), said, "en")!!

        assertEquals(2, note.content.lines().size)
        assertTrue(lines.heard.toString(), lines.heard.isEmpty())
    }

    @Test
    fun `a Malayalam recording's name of the words of its stored junk notes is no name, as the speaker said none of them`() = runBlocking {
        val segments = malayalam.split(", ").mapIndexed { i, text -> segment(text, at = i.toLong()) }
        // A note an earlier version stored: the rules said back.
        val stored = listOf(insight(null, "- Keep it concise.\n- Plain statements.", at = 1L))

        val attempt = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Keep It Concise")), shipped, lines, lines)
            .attempt(segments, stored, "en")

        assertEquals(GenerateSessionTitleUseCase.Attempt.Rejected(GenerateSessionTitleUseCase.Rejection.RULES), attempt)
    }

    @Test
    fun `a stored note title that narrates the task never stands in for the name, and the field log counts it`() = runBlocking {
        val segments = english.split(", ").mapIndexed { i, text -> segment(text, at = i.toLong()) }
        val notes = listOf(
            insight("Goal: Provide", "- The plumber comes at nine.", at = 1L),
            insight("English Note Creation", "- The boiler gets its service.", at = 2L),
            // A sentence of the notes prompt, which the rule phrases alone don't hold.
            insight("Like the Subject Line of an Email", "- The tap drips.", at = 3L),
            insight("Plumber visit", "- Clear the kitchen first.", at = 4L)
        )
        val repository = FakeTranscriptionRepository(session(), segments, notes)

        val named = AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Notes", "Recording")), shipped, lines, lines), lines)("s1", "en")

        assertEquals("Plumber visit", named.getOrThrow())
        assertTrue(lines.heard.toString(), "Dropped 3 note titles for the name that repeat the rules of their prompt in s1" in lines.heard)
    }
}
