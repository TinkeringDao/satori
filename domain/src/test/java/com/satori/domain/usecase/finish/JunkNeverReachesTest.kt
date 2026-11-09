package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeFinishingModelRepository
import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.KeptAudio
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.PromptEchoLog
import com.satori.domain.usecase.llm.ShippedPrompts
import com.satori.domain.usecase.mindmap.FakeMindMapRepository
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.MindMapLog
import com.satori.domain.usecase.mindmap.RecordingIndex
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase
import com.satori.domain.usecase.todo.CountingScheduler
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.FakeTimeParser
import com.satori.domain.usecase.todo.RecordedTodos
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Junk never reaches the owner, and notes and names are still never empty for want of a second
 * try (0.7.3): the shapes the 1B gave the owner's Malayalam recordings of 2026-10-17 to 19, through
 * the real finishing with the app's own prompts. Since 0.8.0 the notes read the recordings'
 * readings (a reader whose English of each made-up line is made up too), and the filters stay. A note the filters leave empty is asked once more,
 * a name they reject goes through the same chain, and a Malayalam recording whose answers are all
 * junk ends with no note and the default name: an honest empty page. Nothing here says anything
 * about Malayalam on a screen; only the field log hears why. The transcripts are made up, the
 * answers are of the 1B's shapes in made-up words, none of them the owner's.
 */
class JunkNeverReachesTest {

    private val strings = ShippedPrompts()

    // Made up: "Yesterday we moved into the new house, all the things still have to be put away. The
    // kitchen cupboard is small, a big shelf has to be bought. Next week mother and father come, the room
    // must be ready before that. In the evening we bought vegetables and fruit at the market, prices were high."
    private val sentences = listOf(
        "ഇന്നലെ ഞങ്ങൾ പുതിയ വീട്ടിലേക്ക് താമസം മാറി, സാധനങ്ങൾ എല്ലാം ഇനിയും അടുക്കി വെക്കണം.",
        "അടുക്കളയിലെ അലമാര ചെറുതാണ്, ഒരു വലിയ ഷെൽഫ് വാങ്ങണം.",
        "അടുത്ത ആഴ്ച അമ്മയും അച്ഛനും വരും, അതിന് മുൻപ് മുറി ഒരുക്കണം.",
        "വൈകുന്നേരം മാർക്കറ്റിൽ പോയി പച്ചക്കറിയും പഴങ്ങളും വാങ്ങി, വില കൂടുതലായിരുന്നു."
    )

    /** [count] lines, each of the four sentences [times] times. */
    private fun malayalam(count: Int, times: Int = 1) =
        List(count) { i -> segment(List(times) { sentences.joinToString(" ") }.joinToString(" "), at = 1_000L * (i + 1)) }

    // The reader's English of the four sentences, made up as they are.
    private val readings = listOf(
        "Yesterday we moved into the new house, and all the things still have to be put away.",
        "The kitchen cupboard is small, so a big shelf has to be bought.",
        "Next week mother and father come, and the room must be ready before that.",
        "In the evening we went to the market and bought vegetables and fruit; the prices were high."
    )

    /**
     * What the reader reads each line of [malayalam] as, for the four sentences once or twice (three
     * times would say each run of words three times, a loop D5 empties).
     */
    private val english: Map<String, String> = (1..2).associate { times ->
        List(times) { sentences.joinToString(" ") }.joinToString(" ") to List(times) { readings.joinToString(" ") }.joinToString(" ")
    }

    // The 1B's shapes, in made-up words: a note in Telugu script, Satori's rules said back, a made-up
    // subject said again with small changes, a narration of the task.
    private val telugu = """{"title": "కొత్త ఇల్లు", "points": ["సామాన్లు సర్దాలి", "అల్మారా చిన్నది"]}"""
    private val rules = """{"title": "Plain statements", "points": ["Plain statements.", "First person.", "One idea per point.", "No filler words."]}"""
    private val madeUp = """{"title": "Printer driver needed", "points": ["The printer needs a new driver.", "Install it on the phone."]}"""
    private val madeUpAgain = """{"title": "Printer driver is essential", "points": ["The printer needs a new driver.", "Installing it is a complex task."]}"""
    private val narration = """{"title": "Voice Memo Transcript", "points": ["Aim: Deliver notes in English.", "Initial point: Transcript creation.", """ +
        """"Note: Audio transcribed.", "Final output: English notes produced."]}"""
    private val house = """{"title": "Moving house", "points": ["Put the things away in the new house.", "Buy a big shelf for the kitchen."]}"""

    /** The SatoriNotes and SatoriTodos lines of a run, in order. */
    private class Lines : NotesLog, PromptEchoLog, MindMapLog {
        val heard = mutableListOf<String>()
        override fun log(event: NotesLog.Event) {
            heard += NotesLog.line(event)
        }

        override fun dropped(sessionId: String, count: Int, what: PromptEchoLog.What) {
            heard += PromptEchoLog.line(sessionId, count, what)
        }

        override fun dropped(id: String, count: Int, what: MindMapLog.What, why: AnswerDrop) {
            heard += MindMapLog.line(id, count, what, why)
        }
    }

    /**
     * The model of a run, answering each call by its system prompt: the notes calls with [notes] in
     * turn, the name calls with [names] in turn (the last again after), the to-dos with none, the
     * map with no topic.
     */
    private inner class Model(private val notes: List<String>, private val names: List<String>) : LlmRepository by FakeLlmRepository() {
        val calls = mutableListOf<String>()

        // The name's call with its line of instructions: the delegate would answer it otherwise.
        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?, lead: String?): Flow<String> =
            generateInsight(if (lead.isNullOrBlank()) text else "$lead\n\n$text", systemPrompt, maxTokens, grammar)

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
            val kind = when (systemPrompt) {
                in RecordingMode.entries.map { strings.getPromptForMode(it, "en") } -> "notes"
                strings.getSessionTitlePrompt("en") -> "name"
                strings.getTodoExtractionPrompt("en") -> "todos"
                else -> "map"
            }
            val asked = calls.count { it == kind }
            calls += kind
            emit(
                when (kind) {
                    "notes" -> notes[minOf(asked, notes.lastIndex)]
                    "name" -> names[minOf(asked, names.lastIndex)]
                    "todos" -> """{"items": []}"""
                    else -> """{"topics": []}"""
                }
            )
        }
    }

    private class OneRecording(private val repository: FakeTranscriptionRepository) : RecordingIndex {
        override fun sessionIds() = listOfNotNull(repository.session?.id)
        override fun recordedAt(sessionId: String) = repository.session?.takeIf { it.id == sessionId }?.createdAt
        override fun segments(sessionId: String) = repository.segments.filter { it.sessionId == sessionId }
    }

    /**
     * One recording finished after Stop: by default a whole pass after the whole-file transcript;
     * with [liveNotes], the notes it had while it recorded and the last note on the lines they
     * don't cite.
     */
    private inner class Run(
        notes: List<String>,
        names: List<String>,
        lines: List<TranscriptionSegment>,
        malayalamRecording: Boolean = true,
        liveNotes: List<LlmInsight>? = null
    ) {
        val llm = Model(notes, names)
        val log = Lines()
        val repository = FakeTranscriptionRepository(
            session().copy(
                language = if (malayalamRecording) RecordingLanguage.MALAYALAM else RecordingLanguage.ENGLISH_OR_GERMAN,
                transcriptModel = if (malayalamRecording) SpeechModelId.INDICCONFORMER_ML.id else null,
                insightStrategy = if (liveNotes == null) InsightStrategy.END_OF_SESSION else InsightStrategy.REAL_TIME
            ),
            lines,
            liveNotes.orEmpty()
        )
        private val settings = FakeSettingsRepository(AppSettings())
        private val language = NotesLanguageResolver(settings, repository) { "en" }
        private val map = FakeMindMapRepository(OneRecording(repository))
        val useCase = FinishRecordingUseCase(
            repository,
            GenerateFinalInsightUseCase(llm, settings, strings, log, log),
            GenerateBatchInsightUseCase(llm, settings, strings, echoLog = log, notesLog = log),
            AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(llm, strings, log, log), log),
            ExtractSessionTodosUseCase(
                settings, repository, RecordedTodos(),
                ExtractTodosUseCase(llm, strings, FakeTimeParser(), now = { 5L }, newId = { "todo-1" }),
                CountingScheduler(), language
            ),
            MapRecordingUseCase(repository, map, llm, strings, language, now = { 6L }, newId = { "node-1" }, mapLog = log),
            RefreshThemeSummariesUseCase(map, llm, strings, language, now = { 7L }, mapLog = log),
            language,
            LoadFinishingModelUseCase(llm, FakeFinishingModelRepository()),
            // As after Stop's whole-file pass: the notes are the whole pass; with live notes, none ran.
            BetterTranscriptStep { _, _, _, _ ->
                if (liveNotes == null) BetterTranscript.Replaced(SpeechModelId.INDICCONFORMER_ML) else BetterTranscript.Skipped(null, BetterTranscript.Reason.Off)
            },
            TranscriptLinesStep { 0 },
            DiscardSilentRecordingUseCase(repository, KeptAudio()),
            ReadingStep(repository, FakeReader(english = english), llm, now = { 8L }, clock = { 0L })
        )

        fun finish(): FinishRecordingUseCase.Outcome = runBlocking {
            useCase(FinishRecordingUseCase.Request("s1", aiAvailable = true))
        }
    }

    @Test
    fun `a long Malayalam recording's parts in another script, of the rules and of a subject said again leave the one note of something`() {
        // Four parts of its English readings: Telugu twice, the rules twice, a made-up subject, the same again.
        val run = Run(listOf(telugu, telugu, rules, rules, madeUp, madeUpAgain), listOf("Printer Driver"), malayalam(25, times = 2))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, run.finish())

        // The one part of something stays, alone, never again as a summary.
        assertEquals(listOf("Printer driver needed"), run.repository.insights.map { it.title })
        assertEquals("Printer Driver", run.repository.session?.name)
        assertEquals(6, run.llm.calls.count { it == "notes" })
        assertEquals(
            listOf(
                "Notes of s1, part 1 of 4: no point left in the answer (every point left out), asking once more",
                "Notes of s1, part 1 of 4: no note, no point in 2 answers",
                "Notes of s1, part 2 of 4: no point left in the answer (every point left out), asking once more",
                "Notes of s1, part 2 of 4: no note, no point in 2 answers",
                "Dropped 10 note lines that repeat the rules of their prompt in s1",
                "Dropped 6 note lines in another script in s1",
                "Dropped 1 notes that repeat an earlier note in s1",
                "Name of s1: from its notes, after 1 answer"
            ),
            run.log.heard
        )
    }

    @Test
    fun `a short Malayalam note of nothing but the rules gets no note and no name, the default name shows`() {
        val run = Run(listOf(rules, rules), listOf("Plain Statements", "First Person Notes"), malayalam(2))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, run.finish())

        assertTrue(run.repository.insights.isEmpty())
        assertNull(run.repository.session?.name)
        // Since R6 the map reads the readings too, which hold enough to map: it asks for their topics.
        assertEquals(listOf("notes", "notes", "name", "name", "todos", "map"), run.llm.calls)
        assertEquals(
            listOf(
                "Notes of s1: no point left in the answer (every point left out), asking once more",
                "Notes of s1: no note, no point in 2 answers",
                "Dropped 10 note lines that repeat the rules of their prompt in s1",
                "Dropped 1 names that repeat the rules of their prompt in s1",
                "Name of s1: asking once more, from its transcript",
                "Dropped 1 names that repeat the rules of their prompt in s1",
                "Name of s1: none after 2 answers, the default name shows"
            ),
            run.log.heard
        )
    }

    @Test
    fun `a Malayalam note that narrates its task is asked once more, and its second answer of the subject is the note`() {
        val run = Run(listOf(narration, house), listOf("Recorded Meeting", "Moving House"), malayalam(2))

        run.finish()

        assertEquals("Moving house", run.repository.insights.single().title)
        assertEquals(2, run.repository.insights.single().content.lines().size)
        assertEquals("Moving House", run.repository.session?.name)
        assertTrue(run.log.heard.toString(), "Notes of s1: the second answer kept, 2 lines" in run.log.heard)
        // "Note: Audio transcribed." says only what was done to the recording. Since 0.8.0 the notes read
        // the readings with no line on reading Malayalam (D6): it goes with the narration, a repeat of the rules.
        assertTrue(run.log.heard.toString(), "Dropped 4 note lines that repeat the rules of their prompt in s1" in run.log.heard)
        assertFalse(run.log.heard.toString(), run.log.heard.any { "repeat their prompt" in it })
        assertTrue(run.log.heard.toString(), "Dropped 1 note titles that name only the kind of text in s1" in run.log.heard)
    }

    @Test
    fun `a name in another script goes through the chain to the note's title`() {
        val run = Run(listOf(house), listOf("కొత్త ఇల్లు", "నూతన గృహం"), malayalam(2))

        run.finish()

        assertEquals("Moving house", run.repository.session?.name)
        assertEquals(
            listOf(
                "Dropped 1 names in another script in s1",
                "Name of s1: asking once more, from its transcript",
                "Dropped 1 names in another script in s1",
                "Name of s1: the notes' title, after 2 answers"
            ),
            run.log.heard
        )
    }

    @Test
    fun `an English meeting's notes of plans, decisions and action items stay as they are`() {
        val talk = "We decided the budget is due Friday and Anna takes the action items from the last meeting. " +
            "The plans for the trip to Munich in May are open, and Tom books the hotel for the whole team."
        val meeting = """{"title": "Budget and Munich trip", "points": ["The budget is due Friday.", "Anna takes the action items from the last meeting.", """ +
            """"Plans for the trip to Munich in May are open.", "Tom books the hotel for the team."]}"""
        val run = Run(listOf(meeting), listOf("Budget and Munich Trip"), listOf(segment(talk, at = 1_000L)), malayalamRecording = false)

        run.finish()

        assertEquals("Budget and Munich trip", run.repository.insights.single().title)
        assertEquals(4, run.repository.insights.single().content.lines().size)
        assertEquals("Budget and Munich Trip", run.repository.session?.name)
        assertEquals(listOf("Name of s1: from its notes, after 1 answer"), run.log.heard)
    }

    // The review of 0.7.3.

    @Test
    fun `a short Malayalam note that narrates its task without a stage word, as 0_7_1 stored one, gets no note and no name`() {
        // The shapes of the 28-second note's answers, in made-up words.
        val narrated = """{"title": "Voice Memo Transcript", "points": ["Note: English notes provided.", "English notes provided.", """ +
            """"Notes created in English.", "Last idea: English notes provided."]}"""
        val run = Run(listOf(narrated, narrated), listOf("English Notes Provided", "Final Idea"), malayalam(2))

        run.finish()

        assertTrue(run.repository.insights.isEmpty())
        assertNull(run.repository.session?.name)
        assertTrue(run.log.heard.toString(), "Notes of s1: no note, no point in 2 answers" in run.log.heard)
        assertTrue(run.log.heard.toString(), "Name of s1: none after 2 answers, the default name shows" in run.log.heard)
    }

    // Made up: an English voice note about the garden, its first lines read by a live note.
    private val gardenLines = listOf(
        "Tomorrow we cut the hedge in the front garden, and after that the lawn needs mowing before the guests come.",
        "So the hedge is first, tomorrow morning, and then the lawn when the hedge is done and swept up.",
        "Anna brings the trailer for the green waste, and Tom takes it to the depot on Friday afternoon."
    ).mapIndexed { i, text -> segment(text, at = 1_000L * (i + 1)) }

    private val liveNote = LlmInsight(
        id = "live-1", sessionId = "s1", title = "Garden work", content = "- Cut the hedge tomorrow.\n- Mow the lawn after the hedge.",
        timestamp = 1_500L, sourceSegmentIds = listOf("seg1000")
    )

    @Test
    fun `the last note after Stop that says a live note again is none, and one with something new keeps it`() {
        val again = """{"title": "Garden work", "points": ["Cut the hedge tomorrow.", "Mow the lawn after the hedge."]}"""
        val repeated = Run(listOf(again), listOf("Garden Work Tomorrow"), gardenLines, malayalamRecording = false, liveNotes = listOf(liveNote))

        repeated.finish()

        assertEquals(listOf("live-1"), repeated.repository.insights.map { it.id })
        assertTrue(repeated.log.heard.toString(), "Dropped 1 notes that repeat an earlier note in s1" in repeated.log.heard)

        val more = """{"title": "Garden work", "points": ["Cut the hedge tomorrow.", "Anna brings the trailer for the green waste."]}"""
        val kept = Run(listOf(more), listOf("Garden Work Tomorrow"), gardenLines, malayalamRecording = false, liveNotes = listOf(liveNote))

        kept.finish()

        assertEquals(listOf("Garden work", null), kept.repository.insights.map { it.title })
        assertEquals("- Anna brings the trailer for the green waste.", kept.repository.insights.last().content)
        assertTrue(kept.log.heard.toString(), "Dropped 2 note lines that repeat an earlier note in s1" in kept.log.heard)
    }
}
