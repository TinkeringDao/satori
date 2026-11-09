package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.ReaderOutput
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.ShippedPrompts
import com.satori.domain.usecase.todo.AnswerLog
import com.satori.domain.usecase.todo.CountingScheduler
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.FakeTimeParser
import com.satori.domain.usecase.todo.RecordedTodos
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A debug build's rerun of a finishing step (0.7.2 A.6): the step the finishing ran, asked again on
 * a stored recording, storing nothing unless asked, its raw answers kept under the rerun's own tags.
 * The prompts are the app's own; the recordings and answers are made up.
 */
class FinishingStepRerunTest {

    private val strings = ShippedPrompts()
    private val settings = FakeSettingsRepository(AppSettings())

    private val malayalam = listOf(
        segment("ഈ ആപ്പ് മീറ്റിംഗുകൾ റെക്കോർഡ് ചെയ്യും, പിന്നെ നോട്ട്സും മിനിറ്റ്സും തരും.", at = 1_000L),
        segment("ഇതിന്റെ എഐ ഫോണിൽ തന്നെ ഓഫ്‌ലൈനായി പ്രവർത്തിക്കും, ഇന്റർനെറ്റ് വേണ്ട.", at = 2_000L),
        segment("എഴുതാൻ ബുദ്ധിമുട്ടുള്ള ആളുകൾക്ക് ഇത് വലിയ സഹായമാണ്, അവർക്ക് സംസാരിച്ചാൽ മതി.", at = 3_000L)
    )
    private val english = listOf(
        segment("Remind me to call the bank about the loan tomorrow, and the plumber comes on Friday morning.", at = 1_000L),
        segment("We also agreed that the garden fence gets repainted in spring, Anna brings the paint and the brushes.", at = 2_000L)
    )
    private val oldNote = insight("Offline meeting app", "- Records meetings and gives notes and minutes.", at = 4_000L)
    private val note = """{"title": "Offline meeting app", "points": ["AI writes the notes.", "It works offline on the phone."]}"""

    /** The answers kept and the tags cleared, in order. */
    private class Kept : AnswerLog {
        val events = mutableListOf<String>()
        override fun todoAnswer(sessionId: String, window: Int, answer: String) {
            events += "$sessionId-$window.json"
        }

        override fun answer(sessionId: String, call: String, answer: String) {
            events += "$sessionId-$call"
        }

        override fun clear(sessionId: String, calls: String) {
            events += "clear $sessionId-$calls"
        }
    }

    private val kept = Kept()

    private inner class Setup(answers: List<String?>, val repository: FakeTranscriptionRepository, val todos: RecordedTodos = RecordedTodos()) {
        val llm = ScriptedLlmRepository(answers)
        val reader = FakeReader()
        private val language = NotesLanguageResolver(settings, repository) { "en" }
        fun rerun(step: String = "notes") = RerunAnswerLog(kept, step).let { log ->
            log to FinishingStepRerun(
                repository,
                language,
                GenerateFinalInsightUseCase(llm, settings, strings, answerLog = log),
                GenerateBatchInsightUseCase(llm, settings, strings, answerLog = log),
                GenerateSessionTitleUseCase(llm, strings, answerLog = log),
                ExtractSessionTodosUseCase(
                    settings, repository, todos,
                    ExtractTodosUseCase(llm, strings, FakeTimeParser(), now = { 9_000L }, answerLog = log),
                    CountingScheduler(), language
                ),
                ReadingStep(repository, reader, llm, now = { 9_000L }, clock = { 0L })
            )
        }
    }

    // Made up: the reader's English of the three lines.
    private val read = listOf(
        "This app records meetings, then it gives notes and minutes.",
        "Its AI works offline on the phone itself, no internet needed.",
        "It is a great help to people who find writing hard; speaking is enough for them."
    )

    /**
     * The Malayalam recording, with the readings of its lines its finishing stored, or the rerun's
     * caller made first (EnsureReadingsUseCase, 0.8.0); with [read] false, none.
     */
    private fun malayalamRecording(notes: List<com.satori.domain.model.LlmInsight> = listOf(oldNote), read: Boolean = true) = FakeTranscriptionRepository(
        session().copy(language = RecordingLanguage.MALAYALAM, insightStrategy = InsightStrategy.END_OF_SESSION),
        malayalam,
        notes
    ).apply {
        if (read) readings = malayalam.mapIndexed { i, line -> readingOf(line, this@FinishingStepRerunTest.read[i]) }
    }

    private fun readingOf(line: com.satori.domain.model.TranscriptionSegment, text: String) =
        SegmentReading(line.id, "s1", SegmentReading.sourceHashOf(line.text), text, "reader-test", -0.4, createdAt = 1L)

    @Test
    fun `the notes of a recording the whole pass wrote are the pass again, and nothing is stored`() = runBlocking {
        val setup = Setup(listOf(note), malayalamRecording())
        val (log, rerun) = setup.rerun()
        log.begin("s1")

        val ran = rerun.run("s1", FinishingStepRerun.Step.NOTES) as FinishingStepRerun.Ran.Notes

        assertTrue(ran.pass)
        assertEquals("Offline meeting app", ran.notes.single().title)
        assertEquals("1 note of the pass, 2 points, 1 titled, 0 with action items", ran.line())
        // The old notes stay, and the finishing's answers too: the rerun's go under its own tags.
        assertEquals(listOf(oldNote), setup.repository.insights)
        assertEquals(listOf("clear s1-rerun-notes", "s1-rerun-notes-1-part1-0"), kept.events)
    }

    @Test
    fun `asked to store, the run's notes replace the old ones, as Rewrite notes does`() = runBlocking {
        val setup = Setup(listOf(note), malayalamRecording())
        val (_, rerun) = setup.rerun()

        val ran = rerun.run("s1", FinishingStepRerun.Step.NOTES)

        assertTrue(rerun.store("s1", ran))
        assertEquals(listOf("Offline meeting app"), setup.repository.insights.map { it.title })
    }

    @Test
    fun `the notes of a recording with live notes are a final note on its whole transcript`() = runBlocking {
        val repository = FakeTranscriptionRepository(session(), english, listOf(insight("Old", "- Old point.", at = 3_000L)))
        val setup = Setup(listOf("""{"title": "Errands", "points": ["Call the bank.", "Repaint the fence in spring."]}"""), repository)
        val (log, rerun) = setup.rerun()

        log.run = 3
        val ran = rerun.run("s1", FinishingStepRerun.Step.NOTES) as FinishingStepRerun.Ran.Notes

        assertFalse(ran.pass)
        assertEquals(english.map { it.id }, ran.notes.single().sourceSegmentIds)
        assertTrue(setup.llm.prompts.single().first.contains("garden fence"))
        assertEquals(listOf("s1-rerun-notes-3-final-0"), kept.events)
    }

    @Test
    fun `a name is asked from the notes or from the transcript, and stored only into a recording without one`() = runBlocking {
        val repository = malayalamRecording(listOf(insight(null, "- Records meetings and gives notes and minutes.", at = 4_000L)))
        val setup = Setup(listOf("Meeting Notes & Minutes", "Voice Notes"), repository)
        val (log, rerun) = setup.rerun("name")

        val fromNotes = rerun.run("s1", FinishingStepRerun.Step.NAME, FinishingStepRerun.From.NOTES)
        log.run = 2
        val fromTranscript = rerun.run("s1", FinishingStepRerun.Step.NAME, FinishingStepRerun.From.TRANSCRIPT)

        // Generic but in the notes' words: the finishing would put a note title the rules keep alone before it.
        assertEquals("a name kept (23 chars, generic but in its subject's words)", fromNotes.line())
        assertEquals("no name, generic", fromTranscript.line())
        // The transcript it is asked from is the readings, as the finishing reads it (0.8.0).
        assertTrue(setup.llm.prompts[1].first.contains(read.first()))
        assertFalse(setup.llm.prompts[1].first.contains("ഈ ആപ്പ്"))
        assertEquals(listOf("s1-rerun-name-1-0", "s1-rerun-name-2-1"), kept.events)
        assertEquals(null, repository.session?.name)
        assertTrue(rerun.store("s1", fromNotes))
        assertEquals("Meeting Notes & Minutes", repository.session?.name)
        // A name there stays, typed or given.
        repository.session = repository.session?.copy(name = "Typed by hand")
        assertFalse(rerun.store("s1", fromNotes))
        assertEquals("Typed by hand", repository.session?.name)
    }

    @Test
    fun `a name the script and the rules reject says why in words`() {
        val rejected = { why: GenerateSessionTitleUseCase.Rejection ->
            FinishingStepRerun.Ran.Name(GenerateSessionTitleUseCase.Attempt.Rejected(why)).line()
        }

        assertEquals("no name, rules", rejected(GenerateSessionTitleUseCase.Rejection.RULES))
        assertEquals("no name, other script", rejected(GenerateSessionTitleUseCase.Rejection.OTHER_SCRIPT))
    }

    @Test
    fun `the to-dos are found with those known at the finishing, not the recording's own, and none is stored`() = runBlocking {
        val repository = FakeTranscriptionRepository(session().copy(durationMs = 60_000L), english)
        val own = Todo(id = "own", sessionId = "s1", text = "Call the bank about the loan", status = TodoStatus.OPEN, createdAt = 70_000L, updatedAt = 70_000L)
        val before = Todo(id = "before", sessionId = "s0", text = "Buy the paint and the brushes", status = TodoStatus.OPEN, createdAt = 50_000L, updatedAt = 50_000L)
        val after = Todo(id = "after", sessionId = "s2", text = "Book the plumber for Friday", status = TodoStatus.OPEN, createdAt = 90_000L, updatedAt = 90_000L)
        val todos = RecordedTodos(own, before, after)
        val answer = """{"items": [{"segment": "S1", "type": "task", "text": "Call the bank about the loan", "when": "none", "capture": "explicit"}]}"""
        val setup = Setup(listOf(answer), repository, todos)
        val (_, rerun) = setup.rerun("todos")

        val ran = rerun.run("s1", FinishingStepRerun.Step.TODOS) as FinishingStepRerun.Ran.Todos

        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(count = 1), ran.outcome)
        assertEquals(listOf(own, before, after), todos.stored)
        val noted = setup.llm.prompts.single().first.lines().first { it.contains("Already noted") }
        assertTrue(noted, noted.contains("Buy the paint"))
        assertFalse(noted, noted.contains("Call the bank"))
        assertFalse(noted, noted.contains("Book the plumber"))
        assertEquals(listOf("s1-rerun-todos-1-0"), kept.events)
    }

    @Test
    fun `a recording that is gone, or without words, gives nothing to rerun`() = runBlocking {
        val (_, rerun) = Setup(listOf(note), FakeTranscriptionRepository(null)).rerun()
        assertEquals("no such recording", rerun.run("s1", FinishingStepRerun.Step.NOTES).line())
        val (_, silent) = Setup(listOf(note), FakeTranscriptionRepository(session(), listOf(segment(" ", at = 1L)))).rerun()
        assertEquals("no line with words", silent.run("s1", FinishingStepRerun.Step.NAME).line())
    }

    @Test
    fun `a reading again reads every Malayalam line, read before or not, stores nothing, and logs counts only`() = runBlocking {
        val repository = malayalamRecording(read = false)
        val setup = Setup(emptyList(), repository)
        setup.reader.english = malayalam.map { it.text }.zip(read).toMap()
        val old = SegmentReading(malayalam[0].id, "s1", SegmentReading.sourceHashOf(malayalam[0].text), "An older reading.", "reader-test", -0.3, createdAt = 1L)
        repository.saveReading(old)
        val (_, rerun) = setup.rerun("reading")

        val ran = rerun.run("s1", FinishingStepRerun.Step.READING) as FinishingStepRerun.Ran.Readings

        assertEquals(malayalam.map { it.id }, ran.lines.map { it.segment.id })
        assertEquals(List(3) { ReadingStep.Verdict.KEPT }, ran.lines.map { it.verdict })
        assertEquals("Its AI works offline on the phone itself, no internet needed.", ran.lines[1].output?.text)
        assertEquals(listOf("load") + setup.reader.english.values.map { "read $it" } + "release", setup.reader.events)
        // Nothing stored, nothing asked of the AI model.
        assertEquals(listOf(old), repository.readings)
        assertTrue(setup.llm.prompts.isEmpty())
        assertFalse(rerun.store("s1", ran))
        assertEquals(
            "3 lines read in 0 ms, 3 kept, 0 empty (0 fragments, 0 under the floor, 0 outside the band, 0 repeating, 0 blank, 0 not read)",
            ran.line()
        )
        assertEquals(FinishingStepRerun.Step.READING, FinishingStepRerun.Step.of("reading"))
    }

    @Test
    fun `a reading again without the reader on the phone says so, and reads nothing`() = runBlocking {
        val setup = Setup(emptyList(), malayalamRecording(read = false))
        setup.reader.onPhone = false
        val (_, rerun) = setup.rerun("reading")

        val ran = rerun.run("s1", FinishingStepRerun.Step.READING) as FinishingStepRerun.Ran.Readings

        assertTrue(ran.lines.isEmpty())
        assertTrue(ran.line(), ran.line().startsWith("3 lines not loaded (not on phone) in 0 ms, 0 kept"))
    }

    @Test
    fun `with the reader off the notes, the name and the to-dos read the lines as they were said, as 0_7_3 did, in the language they were said in`() = runBlocking {
        val todosAnswer = """{"items": []}"""
        val names = listOf("Offline Meeting App")
        val off = Setup(listOf(note) + names + todosAnswer, malayalamRecording())
        val (_, offRerun) = off.rerun()
        offRerun.run("s1", FinishingStepRerun.Step.NOTES, reader = false)
        offRerun.run("s1", FinishingStepRerun.Step.NAME, FinishingStepRerun.From.TRANSCRIPT, reader = false)
        offRerun.run("s1", FinishingStepRerun.Step.TODOS, reader = false)
        val on = Setup(listOf(note) + names + todosAnswer, malayalamRecording())
        val (_, onRerun) = on.rerun()
        onRerun.run("s1", FinishingStepRerun.Step.NOTES)
        onRerun.run("s1", FinishingStepRerun.Step.NAME, FinishingStepRerun.From.TRANSCRIPT)
        onRerun.run("s1", FinishingStepRerun.Step.TODOS)

        // Off: the Malayalam itself, with the orders to read it, as the 1B read it before the reader.
        assertEquals(3, off.llm.prompts.size)
        assertTrue(off.llm.prompts.all { (text, _) -> text.contains("ഈ ആപ്പ്") })
        assertTrue(off.llm.prompts.all { (text, _) -> text.contains("read the Malayalam below and write in English") })
        // On: the readings, with an English recording's prompts and no orders (D6); the same system prompts, in English.
        assertEquals(off.llm.prompts.map { it.second }, on.llm.prompts.map { it.second })
        assertTrue(on.llm.prompts.all { (text, _) -> text.contains(read.first()) && !text.contains("ഈ ആപ്പ്") && !text.contains("Malayalam") })
        assertEquals(strings.getSessionTitlePrompt("en"), on.llm.prompts[1].second)
    }

    @Test
    fun `with the reader on, a recording whose Malayalam lines have no readings gives nothing to rerun, and the AI model is never asked`() = runBlocking {
        val setup = Setup(listOf(note), malayalamRecording(read = false))
        val (_, rerun) = setup.rerun()

        assertEquals("no line read", rerun.run("s1", FinishingStepRerun.Step.NOTES).line())
        assertEquals("no line read", rerun.run("s1", FinishingStepRerun.Step.NAME).line())
        assertTrue(setup.llm.prompts.isEmpty())
    }

    @Test
    fun `a reading's answer file has a row a line, with what D5 made of it, its counts and both texts on one line each`() {
        val kept = ReadingStep.LineRead(malayalam[0], ReaderOutput(read[0], pieces = 24, tokens = 15, meanLogProb = -0.41234, capped = false), ReadingStep.Verdict.KEPT)
        // Made up: "Okay."
        val fragment = ReadingStep.LineRead(segment("ശരി.", at = 4_000L), output = null, verdict = ReadingStep.Verdict.FRAGMENT)
        val broken = ReadingStep.LineRead(
            segment("ഒന്ന്\tരണ്ട്\nമൂന്ന്", at = 5_000L),
            ReaderOutput("One\ttwo\r\nthree", pieces = 30, tokens = 40, meanLogProb = null, capped = true),
            ReadingStep.Verdict.BAND
        )
        val ran = FinishingStepRerun.Ran.Readings(listOf(kept, fragment, broken), ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 3, kept = 1, fragments = 1, band = 1)))

        assertEquals(
            "line\tsegment\tverdict\tpieces\ttokens\tmean_log_prob\tcapped\tsaid\treading\n" +
                "1\tseg1000\tkept\t24\t15\t-0.412\tfalse\t${malayalam[0].text}\t${read[0]}\n" +
                "2\tseg4000\tfragment\t\t\t\t\tശരി.\t\n" +
                "3\tseg5000\tband\t30\t40\t\ttrue\tഒന്ന് രണ്ട് മൂന്ന്\tOne two  three\n",
            ran.answer()
        )
    }

    @Test
    fun `a reading in windows has a row a window, with its lines' ids and texts, and counts windows`() = runBlocking {
        val window = ReadingStep.LineRead(
            malayalam[0],
            ReaderOutput("${read[0]} ${read[1]}", pieces = 50, tokens = 33, meanLogProb = -0.5, capped = false),
            ReadingStep.Verdict.KEPT,
            window = listOf(malayalam[0], malayalam[1])
        )
        val alone = ReadingStep.LineRead(malayalam[2], ReaderOutput(read[2], pieces = 20, tokens = 14, meanLogProb = -0.25, capped = false), ReadingStep.Verdict.KEPT, window = listOf(malayalam[2]))
        val ran = FinishingStepRerun.Ran.Readings(listOf(window, alone), ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 2, kept = 2)), window = 2)

        assertEquals(
            "line\tsegment\tverdict\tpieces\ttokens\tmean_log_prob\tcapped\tsaid\treading\n" +
                "1\t${malayalam[0].id},${malayalam[1].id}\tkept\t50\t33\t-0.500\tfalse\t${malayalam[0].text} ${malayalam[1].text}\t${read[0]} ${read[1]}\n" +
                "2\t${malayalam[2].id}\tkept\t20\t14\t-0.250\tfalse\t${malayalam[2].text}\t${read[2]}\n",
            ran.answer()
        )
        assertTrue(ran.line(), ran.line().startsWith("2 windows of 2 lines read in 0 ms, 2 kept, 0 empty"))

        // The rerun reads them so: the three Malayalam lines as two windows, nothing stored.
        val repository = malayalamRecording(read = false)
        val setup = Setup(emptyList(), repository)
        val (_, rerun) = setup.rerun("reading")
        val windows = rerun.run("s1", FinishingStepRerun.Step.READING, window = 2) as FinishingStepRerun.Ran.Readings
        assertEquals(listOf(malayalam.take(2).map { it.id }, listOf(malayalam[2].id)), windows.lines.map { line -> line.window.map { it.id } })
        assertEquals(2, windows.window)
        assertTrue(repository.readings.isEmpty())
        assertTrue(setup.llm.prompts.isEmpty())
    }

    @Test
    fun `the rerun's tags are the step's, the run's and the call's own`() {
        val log = RerunAnswerLog(kept, "notes")
        log.run = 4
        log.answer("s1", "notes-part2-1", "{}")
        log.answer("s1", "notes-merge-0", "{}")
        log.clear("s1", "notes")
        RerunAnswerLog(kept, "todos").apply { run = 2 }.todoAnswer("s1", 1, "{}")
        // A reading's answer file (0.8.0): the debug rerun keeps each run's lines under its call "reading-lines".
        RerunAnswerLog(kept, "reading").apply { run = 3 }.answer("s1", "reading-lines", "line\tsegment")
        // Read in windows of lines (design D1), under "reading-windows".
        RerunAnswerLog(kept, "reading").apply { run = 1 }.answer("s1", "reading-windows", "line\tsegment")

        assertEquals(
            listOf("s1-rerun-notes-4-part2-1", "s1-rerun-notes-4-merge-0", "s1-rerun-todos-2-1", "s1-rerun-reading-3-lines", "s1-rerun-reading-1-windows"),
            kept.events
        )
        assertTrue(kept.events.all { AnswerLog.CALL.matches(it.removePrefix("s1-")) })
    }
}
