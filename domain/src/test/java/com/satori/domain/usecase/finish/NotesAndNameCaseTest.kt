package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeFinishingModelRepository
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.KeptAudio
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.PromptEchoLog
import com.satori.domain.usecase.llm.ShippedPrompts
import com.satori.domain.usecase.mindmap.FakeMindMapRepository
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.RecordingIndex
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase
import com.satori.domain.usecase.mindmap.topicsAnswer
import com.satori.domain.usecase.todo.CountingScheduler
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.FakeTimeParser
import com.satori.domain.usecase.todo.RecordedTodos
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's Malayalam note of 2026-10-17 11:31, in the shapes its field log allows, through the
 * real finishing with the app's own prompts (0.7.2), since 0.8.0 its notes from its readings (each
 * line's English made up as the line is): a 24-word recording about an app that records
 * meetings, gives notes and minutes, runs offline on the phone and helps people who find writing
 * hard, made up here; Stop's whole-file pass replaced its transcript, so its notes are the whole
 * pass. In 0.7.1 each shape ended without a name and without a line saying why. Now each ends with
 * a note unless the model gave nothing usable twice, and with a name or the default name's line.
 * The answers are of the shapes the 1B gives.
 */
class NotesAndNameCaseTest {

    private val strings = ShippedPrompts()

    private val lines = listOf(
        segment("ഈ ആപ്പ് മീറ്റിംഗുകൾ റെക്കോർഡ് ചെയ്യും, പിന്നെ നോട്ട്സും മിനിറ്റ്സും തരും.", at = 1_000L),
        segment("ഇതിന്റെ എഐ ഫോണിൽ തന്നെ ഓഫ്‌ലൈനായി പ്രവർത്തിക്കും, ഇന്റർനെറ്റ് വേണ്ട.", at = 2_000L),
        segment("എഴുതാൻ ബുദ്ധിമുട്ടുള്ള ആളുകൾക്ക് ഇത് വലിയ സഹായമാണ്, അവർക്ക് സംസാരിച്ചാൽ മതി.", at = 3_000L)
    )

    // The reader's English of the three lines, made up as they are.
    private val english = mapOf(
        lines[0].text to "This app records meetings, and then it gives notes and minutes.",
        lines[1].text to "Its AI works offline on the phone itself; no internet is needed.",
        lines[2].text to "It is a great help to people who find writing hard; they only need to speak."
    )

    private val subjectPoints = """"Records meetings and gives notes and minutes.", "The AI runs offline on the phone.", "Helps people who find writing hard.""""
    private val noTodos = """{"items": []}"""
    private val topics = topicsAnswer("Offline meeting app" to listOf("Records meetings and gives notes."))
    private val summary = """{"summary": "An app records meetings and gives notes offline."}"""
    // Before 0.8.0 the 1B's notes that only named the language echoed the line on reading Malayalam,
    // which the notes' prompt no longer has (D6): the notes of nothing but the rules are their shape now.
    private val allEchoes = """{"title": "Plain statements", "points": ["Plain statements.", "First person.", "One idea per point.", "No filler words."]}"""

    /** The SatoriNotes lines of a run, the echoes' and the others', in order. */
    private class Lines : NotesLog, PromptEchoLog {
        val heard = mutableListOf<String>()
        override fun log(event: NotesLog.Event) {
            heard += NotesLog.line(event)
        }

        override fun dropped(sessionId: String, count: Int, what: PromptEchoLog.What) {
            heard += PromptEchoLog.line(sessionId, count, what)
        }
    }

    private class OneRecording(private val repository: FakeTranscriptionRepository) : RecordingIndex {
        override fun sessionIds() = listOfNotNull(repository.session?.id)
        override fun recordedAt(sessionId: String) = repository.session?.takeIf { it.id == sessionId }?.createdAt
        override fun segments(sessionId: String) = repository.segments.filter { it.sessionId == sessionId }
    }

    private inner class Run(answers: List<String?>) {
        val llm = ScriptedLlmRepository(answers)
        val notesLines = Lines()
        val failed = mutableListOf<FinishStep>()
        val repository = FakeTranscriptionRepository(
            session().copy(language = RecordingLanguage.MALAYALAM, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id),
            lines
        )
        private val settings = FakeSettingsRepository(AppSettings())
        private val language = NotesLanguageResolver(settings, repository) { "en" }
        private val map = FakeMindMapRepository(OneRecording(repository))
        private var nodes = 0
        val useCase = FinishRecordingUseCase(
            repository,
            GenerateFinalInsightUseCase(llm, settings, strings, notesLines, notesLines),
            GenerateBatchInsightUseCase(llm, settings, strings, echoLog = notesLines, notesLog = notesLines),
            AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(llm, strings, notesLines, notesLines), notesLines),
            ExtractSessionTodosUseCase(
                settings, repository, RecordedTodos(),
                ExtractTodosUseCase(llm, strings, FakeTimeParser(), now = { 5L }, newId = { "todo-1" }),
                CountingScheduler(), language
            ),
            MapRecordingUseCase(repository, map, llm, strings, language, now = { 6L }, newId = { "node-${++nodes}" }),
            RefreshThemeSummariesUseCase(map, llm, strings, language, now = { 7L }),
            language,
            LoadFinishingModelUseCase(llm, FakeFinishingModelRepository()),
            // The 11:31 log: "Better transcript after Stop ... by indicconformer-ml-int8".
            BetterTranscriptStep { _, _, _, _ -> BetterTranscript.Replaced(SpeechModelId.INDICCONFORMER_ML) },
            TranscriptLinesStep { 0 },
            DiscardSilentRecordingUseCase(repository, KeptAudio()),
            ReadingStep(repository, FakeReader(english = english), llm, now = { 8L }, clock = { 0L })
        )

        fun finish(): FinishRecordingUseCase.Outcome = runBlocking {
            useCase(FinishRecordingUseCase.Request("s1", aiAvailable = true), FinishRecordingUseCase.Progress(onStepFailed = { s, _ -> failed += s }))
        }

        /** Each call by its system prompt. */
        fun calls(): List<String> = llm.prompts.map { (_, system) ->
            when (system) {
                strings.getPromptForMode(RecordingMode.SIMPLE_LISTENING, "en") -> "notes"
                strings.getSessionTitlePrompt("en") -> "name"
                strings.getTodoExtractionPrompt("en") -> "todos"
                else -> "other"
            }
        }

        val note get() = repository.insights.single()
    }

    @Test
    fun `the brief's reading - the only point about the app's AI stays, and the name comes from the transcript`() {
        val run = Run(
            listOf(
                """{"title": "Voice Notes and Minutes", "points": ["AI writes the notes."]}""",
                "Meeting Notes & Minutes",
                "Offline Meeting App",
                noTodos, topics, summary
            )
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, run.finish())

        assertNull(run.note.title)
        assertEquals("- AI writes the notes.", run.note.content)
        assertEquals("Offline Meeting App", run.repository.session?.name)
        assertEquals(listOf("notes", "name", "name", "todos"), run.calls().take(4))
        assertEquals(
            listOf(
                "Dropped 1 note titles that name only the kind of text in s1",
                "Dropped 1 names that name only the kind of text in s1",
                "Name of s1: asking once more, from its transcript",
                "Name of s1: from its transcript, after 2 answers"
            ),
            run.notesLines.heard
        )
    }

    @Test
    fun `the log's reading - the note keeps its title and points, and the name in the subject's words gives way to that title`() {
        val run = Run(listOf("""{"title": "AI Speech Notes", "points": [$subjectPoints]}""", "Meeting Notes & Minutes", noTodos, topics, summary))

        run.finish()

        assertEquals("AI Speech", run.note.title)
        assertEquals(3, run.note.content.lines().size)
        // "Meeting Notes & Minutes" is in the notes' words only; the note's title is kept by the rules alone (review of 0.7.2).
        assertEquals("AI Speech", run.repository.session?.name)
        assertEquals(
            listOf("Dropped 1 names that name only the kind of text in s1", "Name of s1: the notes' title, after 1 answer"),
            run.notesLines.heard
        )
    }

    @Test
    fun `the log's other reading - a generic title and a point about notes, then a generic name, end with every point and a name`() {
        val run = Run(
            listOf(
                """{"title": "Voice Notes and Minutes", "points": ["Records meetings and gives notes and minutes.", "AI writes the notes.", "Helps people who find writing hard."]}""",
                "Voice Notes & Minutes",
                "Meeting Notes & Minutes",
                noTodos, topics, summary
            )
        )

        run.finish()

        assertNull(run.note.title)
        assertEquals(3, run.note.content.lines().size)
        assertEquals("Meeting Notes & Minutes", run.repository.session?.name)
        assertEquals(
            listOf(
                "Dropped 1 note titles that name only the kind of text in s1",
                "Dropped 1 names that name only the kind of text in s1",
                "Name of s1: asking once more, from its transcript",
                "Name of s1: from its transcript, after 2 answers"
            ),
            run.notesLines.heard
        )
    }

    @Test
    fun `a kept title over a point about notes and one that only repeats the rules`() {
        // Before 0.8.0 the second point named the language, an echo of the line on reading Malayalam (D6).
        val run = Run(
            listOf("""{"title": "Offline Meeting App", "points": ["Uses AI to write notes.", "One idea per point."]}""", "Meeting Notes", noTodos, topics, summary)
        )

        run.finish()

        assertEquals("Offline Meeting App", run.note.title)
        assertEquals("- Uses AI to write notes.", run.note.content)
        // The name in the notes' words only gives way to the note's own title.
        assertEquals("Offline Meeting App", run.repository.session?.name)
        assertEquals(
            listOf(
                "Dropped 1 note lines that repeat the rules of their prompt in s1",
                "Dropped 1 names that name only the kind of text in s1",
                "Name of s1: the notes' title, after 1 answer"
            ),
            run.notesLines.heard
        )
    }

    @Test
    fun `a name call that fails says so, as a failed step and as the name's line, and is not asked again`() {
        val run = Run(listOf("""{"title": "Voice Notes and Minutes", "points": ["Records meetings and gives notes and minutes."]}""", null, noTodos, topics, summary))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, run.finish())

        assertNull(run.repository.session?.name)
        assertEquals(listOf(FinishStep.Name), run.failed)
        assertEquals(1, run.calls().count { it == "name" })
        assertEquals(
            listOf(
                "Dropped 1 note titles that name only the kind of text in s1",
                "Name of s1: none, the model failed (IllegalStateException); the default name shows"
            ),
            run.notesLines.heard
        )
    }

    @Test
    fun `notes of nothing but the rules are asked once more, and the second answer is the note`() {
        val run = Run(listOf(allEchoes, """{"title": "Offline meeting app", "points": [$subjectPoints]}""", "Offline Meeting App", noTodos, topics, summary))

        run.finish()

        assertEquals("Offline meeting app", run.note.title)
        assertEquals(3, run.note.content.lines().size)
        assertEquals("Offline Meeting App", run.repository.session?.name)
        assertEquals(listOf("notes", "notes", "name", "todos"), run.calls().take(4))
        // The second call's system prompt is the first one's; its user message has the order.
        assertEquals(run.llm.prompts[0].second, run.llm.prompts[1].second)
        assertTrue(run.llm.prompts[1].first.lines().first().endsWith("never about this recording, its text or its language."))
    }

    @Test
    fun `nothing usable twice gives no note, and the name ends as the default name, with its line`() {
        val run = Run(listOf(allEchoes, allEchoes, "Voice Notes", "Audio Recording", noTodos))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, run.finish())

        assertTrue(run.repository.insights.isEmpty())
        assertNull(run.repository.session?.name)
        // Since R6 the map reads the readings too, which hold enough to map: it asks for their topics.
        assertEquals(listOf("notes", "notes", "name", "name", "todos", "other"), run.calls())
        assertEquals(
            listOf(
                "Notes of s1: no point left in the answer (every point left out), asking once more",
                "Notes of s1: no note, no point in 2 answers",
                "Dropped 10 note lines that repeat the rules of their prompt in s1",
                "Dropped 1 names that name only the kind of text in s1",
                "Name of s1: asking once more, from its transcript",
                "Dropped 1 names that name only the kind of text in s1",
                "Name of s1: none after 2 answers, the default name shows"
            ),
            run.notesLines.heard
        )
    }
}
