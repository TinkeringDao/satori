package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeFinishingModelRepository
import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.KeptAudio
import com.satori.domain.fakes.LARGE_MODEL
import com.satori.domain.fakes.LIVE_MODEL
import com.satori.domain.fakes.TwoModelLlmRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.BatchInsightProgress
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.reading.ReadableSegments
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.LoadStoppedForMemoryException
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.ReaderOutput
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
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
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.todo.TodoChangeLog
import com.satori.domain.usecase.todo.TodoSource
import com.satori.domain.usecase.todo.UntouchedFinds
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import com.satori.domain.usecase.transcription.CategoriesHeld
import com.satori.domain.usecase.transcription.ChangeRecordingTypeUseCase
import com.satori.domain.usecase.transcription.longMeetingCategory
import com.satori.domain.usecase.transcription.starterCategories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinishRecordingUseCaseTest {

    private val talk = "We agreed to move the launch to March, and Anna will update the plan before Friday " +
        "so the whole team can review it on Monday morning."
    private val germanTalk = "Wir haben vereinbart, den Start auf März zu verschieben, und Anna aktualisiert den " +
        "Plan vor Freitag, damit das ganze Team ihn am Montagmorgen prüfen kann."
    // We agreed to move the launch to March; the plan goes out before Friday (Malayalam, with
    // "launch" and "plan" in Malayalam letters).
    private val malayalamTalk = "ലോഞ്ച് മാർച്ചിലേക്ക് മാറ്റാൻ നമ്മൾ തീരുമാനിച്ചു, വെള്ളിയാഴ്ചയ്ക്ക് മുമ്പ് പ്ലാൻ " +
        "അയക്കണം, എല്ലാവരും തിങ്കളാഴ്ച രാവിലെ അത് നോക്കണം, പിന്നെ ടീമിന് ഒരു മീറ്റിംഗ് വേണം, അതിൽ ബജറ്റ് പറയണം."

    // The reader's English of malayalamTalk, made up as it is.
    private val malayalamReading = "We decided to move the launch to March, and the plan has to be sent before Friday; everyone " +
        "must look at it on Monday morning, then the team needs a meeting, and the budget has to be said in it."
    private val note = """{"title": "Launch moves to March", "summary": "The launch moves to March."}"""

    // The second part's own note: since 0.7.3 a part that repeats the one before is none (NoteRepeats).
    private val secondNote = """{"title": "Plan update by Friday", "summary": "Anna updates the plan before Friday."}"""
    private val title = "Launch plan for March"
    private val todo = """{"todos": [{"segment": "S1", "text": "Update the plan", "when": "none", """ +
        """"kind": "task", "capture": "commitment", "certainty": "confirmed"}]}"""
    private val noTodos = """{"todos": []}"""
    private val topics = topicsAnswer("Launch in March" to listOf("The launch moves to March."))
    private val summary = """{"summary": "The launch moves to March, and the team reviews the plan on Monday."}"""

    /** Every step an English recording runs, in order: it has no line only the reader reads, so no Reading step. */
    private val allSteps = FinishStep.entries.filter { it != FinishStep.Reading }.map { it.name }

    /** The one recording of [repository], as the in-memory map sees it. */
    private class OneRecording(private val repository: FakeTranscriptionRepository) : RecordingIndex {
        override fun sessionIds() = listOfNotNull(repository.session?.id)
        override fun recordedAt(sessionId: String) = repository.session?.takeIf { it.id == sessionId }?.createdAt
        override fun segments(sessionId: String) = repository.segments.filter { it.sessionId == sessionId }
    }

    /**
     * One recording with everything the use case works with, and a log of what it reports: each
     * step as it begins, by name. Gemma 4 E2B is not on the phone unless [finishing] says so;
     * [events] can be shared with a [TwoModelLlmRepository]'s log to see the steps and the model
     * swaps in one order.
     */
    private class Rig(
        llm: LlmRepository,
        recording: TranscriptionSession = session(),
        segments: List<TranscriptionSegment>,
        settings: AppSettings = AppSettings(),
        insights: List<LlmInsight> = emptyList(),
        finishing: FakeFinishingModelRepository = FakeFinishingModelRepository(),
        val events: MutableList<String> = mutableListOf(),
        better: (FakeTranscriptionRepository) -> BetterTranscriptStep = { NO_BETTER_TRANSCRIPT },
        lines: (FakeTranscriptionRepository) -> TranscriptLinesStep = { TranscriptLinesStep { 0 } },
        private val resources: ResourceProvider = FakeResourceProvider(),
        val reader: FakeReader = FakeReader()
    ) {
        val repository = FakeTranscriptionRepository(recording, segments, insights)
        val betterTranscripts = mutableListOf<BetterTranscript>()
        val runs = mutableListOf<BetterTranscriptRun>()
        val transcriptProgress = mutableListOf<Float>()
        val todos = RecordedTodos()
        val scheduler = CountingScheduler()
        val models = mutableListOf<FinishingModel>()
        val map = FakeMindMapRepository(OneRecording(repository))
        private var nodes = 0
        val batch = mutableListOf<BatchInsightProgress>()
        val todoOutcomes = mutableListOf<ExtractSessionTodosUseCase.Outcome>()
        val stepFailures = mutableListOf<FinishStep>()

        /** What each failed step threw, in the order of [stepFailures]. */
        val stepErrors = mutableListOf<Throwable>()

        /** Whether a capture of another recording is live, as the finisher would say. */
        var captureLive = false

        /** How often the model step waited for the speech model to be freed, and what it does then. */
        var speechModelWaits = 0
        var onSpeechModelWait: () -> Unit = {}

        /** What the model step said of its waits for memory, in order. */
        val modelWaits = mutableListOf<FinishRecordingUseCase.ModelWait>()

        /** The steps the finisher defers the run at. */
        val deferAt = mutableSetOf<FinishStep>()

        /** What the Notes step said of too little text; it also goes to [events] as "too little". */
        val tooLittle = mutableListOf<FinishRecordingUseCase.TooLittleText>()

        /** How often a run said that no AI step had anything to do, so no model was chosen. */
        var noModelNeeded = 0

        /**
         * Whether the live capture's speech model is in as E2B is to load beside it; each ask goes
         * to [events] as "settled?".
         */
        var captureSettled = true

        /** The languages each run said it reads and writes in. */
        val languages = mutableListOf<FinishRecordingUseCase.Languages>()

        /** What the live capture's speech model may still take, as the finisher would say; and how often it was asked. */
        var captureGrowth = 0L
        var growthAsks = 0

        /** The readings handed out as the reader loads, what the finisher does with each, and what each reading came to. */
        val readingRuns = mutableListOf<ReadingRun>()
        var onReadingStart: (ReadingRun) -> Unit = {}
        val readings = mutableListOf<ReadingStep.Counts>()

        /** What each run made again over the readings said of the owner's (design R9). */
        val redos = mutableListOf<FinishRecordingUseCase.Redo>()

        /** The to-dos' field log: each one taken away as the run made again replaces it. */
        val todoLines = mutableListOf<String>()
        private val untouched = UntouchedFinds(
            todos,
            TodoActions(todos, CountingScheduler(), TodoChangeLog { id, from, to, source -> todoLines += TodoChangeLog.line(id, from, to, source) })
        )

        val progress = FinishRecordingUseCase.Progress(
            beforeStep = { step ->
                events += step.name
                step !in deferAt
            },
            onModel = { models += it },
            onBatchProgress = { batch += it },
            onTranscriptStart = { runs += it },
            onTranscriptProgress = { transcriptProgress += it },
            onBetterTranscript = { betterTranscripts += it },
            captureLive = { captureLive },
            awaitSpeechModelFreed = {
                speechModelWaits++
                onSpeechModelWait()
            },
            onModelWait = { modelWaits += it },
            onTodos = { todoOutcomes += it },
            onStepFailed = { step, e ->
                stepFailures += step
                stepErrors += e
            },
            onTooLittleText = {
                events += "too little"
                tooLittle += it
            },
            onNoModelNeeded = { noModelNeeded++ },
            awaitCaptureSettled = {
                events += "settled?"
                captureSettled
            },
            onLanguages = { languages += it },
            captureGrowthBytes = {
                growthAsks++
                captureGrowth
            },
            onReadingStart = {
                readingRuns += it
                onReadingStart(it)
            },
            onReading = { readings += it },
            onRedo = { redos += it }
        )

        private val settingsRepository = FakeSettingsRepository(settings)
        private val language = NotesLanguageResolver(settingsRepository, repository) { "en" }
        val useCase = FinishRecordingUseCase(
            repository,
            GenerateFinalInsightUseCase(llm, settingsRepository, resources),
            GenerateBatchInsightUseCase(llm, settingsRepository, resources),
            AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(llm, resources)),
            ExtractSessionTodosUseCase(
                settingsRepository,
                repository,
                todos,
                ExtractTodosUseCase(llm, resources, FakeTimeParser(), now = { 5L }, newId = { "todo-1" }),
                scheduler,
                language
            ),
            MapRecordingUseCase(repository, map, llm, resources, language, now = { 6L }, newId = { "node-${++nodes}" }),
            RefreshThemeSummariesUseCase(map, llm, resources, language, now = { 7L }),
            language,
            LoadFinishingModelUseCase(llm, finishing),
            better(repository),
            lines(repository),
            DiscardSilentRecordingUseCase(repository, KeptAudio()),
            ReadingStep(repository, reader, llm, now = { 8L }, clock = { 0L }),
            untouched
        )

        suspend fun finish(
            aiAvailable: Boolean = true,
            mayChangeModel: Boolean = true,
            resumeFrom: FinishStep? = null,
            recovered: Boolean = false,
            recognizerFailed: Boolean = false,
            modelPending: Boolean = false,
            readerPending: Boolean = false,
            redo: Boolean = false
        ) = useCase(
            FinishRecordingUseCase.Request("s1", aiAvailable, mayChangeModel, resumeFrom, recovered, recognizerFailed, modelPending, readerPending, redo),
            progress
        )
    }

    @Test
    fun `writes the last note, names the recording, finds its to-dos and maps it, in that order`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(allSteps, rig.events)
        assertEquals(listOf("Launch moves to March"), rig.repository.insights.map { it.title })
        assertEquals(title, rig.repository.session?.name)
        assertEquals(listOf("Update the plan"), rig.todos.stored.map { it.text })
        assertEquals(1, rig.scheduler.calls)
        assertEquals(listOf("Launch in March" to listOf("The launch moves to March.")), rig.map.linksOf("s1"))
        assertEquals(
            "The launch moves to March, and the team reviews the plan on Monday.",
            rig.map.named("Launch in March").summary
        )
        assertEquals(5, llm.prompts.size)
        // What the search came to goes to the log.
        assertEquals(1, (rig.todoOutcomes.single() as ExtractSessionTodosUseCase.Outcome.Found).count)
    }

    @Test
    fun `the map reads the notes the first step wrote`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        rig.finish()

        assertEquals("Launch moves to March: The launch moves to March." to "mind topics prompt (en)", llm.prompts[3])
    }

    @Test
    fun `a model that failed to map leaves every summary for later`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, null, summary))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))
        rig.map.upsertNode(MindNode("old", MindNodeKind.THEME, "Old theme", "old them", createdAt = 0L, updatedAt = 0L))
        rig.map.upsertNode(MindNode("old-topic", MindNodeKind.TOPIC, "Old theme", "old them", parentId = "old", createdAt = 0L, updatedAt = 0L))
        rig.map.link("old-topic", "s1", listOf("Something said before."), emptyList(), at = 0L)
        rig.map.markStale(listOf("old"))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(allSteps, rig.events)
        assertEquals(4, llm.prompts.size)
        assertTrue(rig.map.nodes.getValue("old").summaryStale)
        assertEquals(listOf("Old theme" to listOf("Something said before.")), rig.map.linksOf("s1"))
    }

    @Test
    fun `with the AI off only the better transcript and the lines run`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(aiAvailable = false))

        assertEquals(listOf("Transcript", "Lines"), rig.events)
        assertTrue(llm.prompts.isEmpty())
        assertNull(rig.repository.session?.name)
        assertTrue(rig.todos.stored.isEmpty())
        assertTrue(rig.models.isEmpty())
    }

    @Test
    fun `an end-of-session recording gets its notes from the whole transcript, stored in one go`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo))
        val recording = session().copy(insightStrategy = InsightStrategy.END_OF_SESSION)
        val rig = Rig(llm, recording, listOf(segment(talk, at = 1_000L), segment("That was all for today.", at = 2_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(allSteps, rig.events)
        assertEquals(1, rig.repository.insights.size)
        assertEquals(1, rig.repository.replaceCalls)
        assertEquals(BatchInsightProgress.Mapping(1, 1), rig.batch.first())
        assertEquals(BatchInsightProgress.Complete, rig.batch.last())
        assertEquals(title, rig.repository.session?.name)
    }

    @Test
    fun `an end-of-session recording run again leaves one set of notes`() = runBlocking {
        val llm = FakeLlmRepository(answers = listOf(note, secondNote))
        val recording = session().copy(insightStrategy = InsightStrategy.END_OF_SESSION)
        val rig = Rig(llm, recording, List(60) { segment(talk, at = 1_000L + it) })

        rig.finish()
        val first = rig.repository.insights.size
        // The process died after the notes were stored, before the next step began.
        rig.finish(resumeFrom = FinishStep.Notes)

        assertEquals(3, first)
        assertEquals(first, rig.repository.insights.size)
        assertEquals(2, rig.repository.replaceCalls)
    }

    @Test
    fun `a note that cannot be written does not stop the name or the to-dos`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(null, title, todo))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.NotesFailed, rig.finish())

        assertEquals(allSteps, rig.events)
        assertTrue(rig.repository.insights.isEmpty())
        assertEquals(title, rig.repository.session?.name)
        assertEquals(listOf("Update the plan"), rig.todos.stored.map { it.text })
    }

    @Test
    fun `a model that cannot be loaded gives no notes, no name, no to-dos and no map, and ends cleanly`() = runBlocking {
        val llm = FakeLlmRepository(answer = note, reloadFailure = IllegalStateException("no model"))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.NotesFailed, rig.finish())

        assertTrue(llm.prompts.isEmpty())
        assertTrue(rig.repository.insights.isEmpty())
        assertNull(rig.repository.session?.name)
        assertTrue(rig.todos.stored.isEmpty())
        assertEquals(0, rig.scheduler.calls)
        assertTrue(rig.map.calls.isEmpty())
    }

    // ─── A model load the phone's memory stopped (0.6.1's paced loads) ──────────

    private val stopped = LoadStoppedForMemoryException("The load of live.gguf was stopped: MemFree stayed under 150 MB for 10 s while its files were read in")

    @Test
    fun `a live model whose load the phone's memory stopped ends the run at its first AI step, to go on from there`() = runBlocking {
        val llm = FakeLlmRepository(answer = note, reloadFailure = stopped)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.LoadStopped(FinishStep.Notes), rig.finish())

        // No step failed on it, and none came after it.
        assertEquals(listOf("Transcript", "Lines", "Notes"), rig.events)
        assertTrue(rig.stepFailures.isEmpty())
        assertTrue(rig.models.isEmpty())
        assertTrue(llm.prompts.isEmpty())
        assertTrue(rig.repository.insights.isEmpty())
        assertNull(rig.repository.session?.name)
        assertTrue(rig.todos.stored.isEmpty())
        assertEquals(1, llm.reloads)
    }

    @Test
    fun `the run that goes on from the stopped step finishes the recording once the model loads`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Notes))

        assertEquals(listOf("Notes", "Name", "Todos", "Map"), rig.events)
        assertEquals(listOf("Launch moves to March"), rig.repository.insights.map { it.title })
        assertEquals(title, rig.repository.session?.name)
    }

    @Test
    fun `with too little for notes the name is the first AI step, where a stopped load ends the run`() = runBlocking {
        val llm = FakeLlmRepository(answer = title, reloadFailure = stopped)
        val rig = Rig(llm, segments = listOf(segment("Call the bank about the loan.", at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.LoadStopped(FinishStep.Name), rig.finish())

        assertEquals(listOf("Transcript", "Lines", "too little", "Name"), rig.events)
        assertNull(rig.repository.session?.name)
    }

    @Test
    fun `Gemma 4 E2B stopped for want of memory gives way to the live model, which finishes the recording`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(note, title, todo), switchFailure = stopped)
        val rig = Rig(
            llm,
            segments = listOf(segment(talk, at = 1_000L)),
            finishing = FakeFinishingModelRepository(path = LARGE_MODEL),
            events = llm.log
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf<FinishingModel>(FinishingModel.FellBack(stopped)), rig.models)
        assertEquals(listOf("Transcript", "Lines", "Notes", "switch $LARGE_MODEL", "load $LIVE_MODEL"), rig.events.take(5))
        // No lack of memory by the margin's measure: the steps don't wait for room for E2B.
        assertTrue(rig.modelWaits.isEmpty())
        assertEquals(List(4) { LIVE_MODEL }, llm.answeredBy)
    }

    @Test
    fun `a failed last note is no failure when the live notes are there`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(null, title, noTodos))
        val live = insight("Launch", "The launch moves.", at = 500L)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), insights = listOf(live))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())
    }

    // ─── The tail ────────────────────────────────────────────────────────────────

    @Test
    fun `the final note is written on the lines no live note cites, and cites them`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val earlier = "Before that we went through the budget for the spring and agreed that the numbers look fine."
        val live = insight("Budget", "The budget looks fine.", at = 1_500L).copy(sourceSegmentIds = listOf("seg1000"))
        val rig = Rig(
            llm,
            segments = listOf(segment(earlier, at = 1_000L), segment(talk, at = 2_000L)),
            insights = listOf(live)
        )

        rig.finish()

        assertEquals(talk, llm.prompts[0].first)
        val final = rig.repository.insights.single { it.id != live.id }
        assertEquals(listOf("seg2000"), final.sourceSegmentIds)
        assertEquals(0, rig.repository.replaceCalls)
    }

    @Test
    fun `a voice note switched to a meeting while it was recorded gets a meeting's last note, and its name and to-dos`() = runBlocking {
        val meetingNote = "{\"title\": \"Launch moves to March\", \"points\": [\"The launch moves to March.\"], " +
            "\"action_items\": [\"Update the plan before Friday\"]}"
        val llm = ScriptedLlmRepository(listOf(meetingNote, title, todo, topics, summary))
        val voiceNote = session().copy(mode = RecordingMode.SIMPLE_LISTENING, categoryId = "voice-note")
        val rig = Rig(llm, voiceNote, listOf(segment(talk, at = 1_000L)))
        // "Record as" while it was recorded, stored before the hand-off: the finisher reads it.
        val switched = ChangeRecordingTypeUseCase(rig.repository, CategoriesHeld(*starterCategories.toTypedArray()))("s1", "meeting")
        assertTrue(switched is ChangeRecordingTypeUseCase.Outcome.Changed)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        // The last note is a meeting's, with its action items.
        assertEquals("short_meeting prompt (en)", llm.prompts[0].second)
        assertEquals("[\"Update the plan before Friday\"]", rig.repository.insights.single().tasks)
        // The name and the to-dos come as for any recording.
        assertEquals(title, rig.repository.session?.name)
        assertEquals(listOf("Update the plan"), rig.todos.stored.map { it.text })
        assertEquals("meeting", rig.repository.session?.categoryId)
    }

    @Test
    fun `a recording switched to a long meeting is finished as one`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, session().copy(categoryId = "voice-note"), listOf(segment(talk, at = 1_000L)))
        ChangeRecordingTypeUseCase(rig.repository, CategoriesHeld(longMeetingCategory))("s1", "board")

        rig.finish()

        assertEquals("long_meeting prompt (en)", llm.prompts[0].second)
        assertEquals(RecordingMode.LONG_MEETING, rig.repository.session?.mode)
    }

    @Test
    fun `lines the live notes cite leave no final note to write`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(title, todo))
        val live = insight("Launch", "The launch moves.", at = 1_500L).copy(sourceSegmentIds = listOf("seg1000"))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), insights = listOf(live))

        rig.finish()

        assertEquals(listOf("Transcript", "Lines", "Name", "Todos", "Map"), rig.events)
        assertEquals(listOf(live), rig.repository.insights)
        assertEquals(3, llm.prompts.size)
    }

    @Test
    fun `the final note cites the tail, so a second run writes no second final note`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        rig.finish()
        rig.events.clear()
        // A process death before the row was deleted: the run goes on from where it was.
        rig.finish(resumeFrom = FinishStep.Notes)

        assertFalse("Notes" in rig.events)
        assertEquals(1, rig.repository.insights.size)
    }

    @Test
    fun `a tail of up to 6,000 characters gets one final note`() = runBlocking {
        val line = "We went through the plan for the spring again, and everybody agreed on the order of work. "
        val lines = List(GenerateFinalInsightUseCase.MAX_USER_PROMPT_CHARS / line.length) { segment(line, at = 1_000L + it) }
        val llm = ScriptedLlmRepository(listOf(note, title, noTodos))
        val rig = Rig(llm, segments = lines)
        assertTrue(lines.joinToString(" ") { it.text.trim() }.length <= GenerateFinalInsightUseCase.MAX_USER_PROMPT_CHARS)

        rig.finish()

        assertEquals(1, rig.repository.insights.size)
        assertEquals(lines.map { it.id }, rig.repository.insights.single().sourceSegmentIds)
        assertEquals(0, rig.repository.replaceCalls)
    }

    @Test
    fun `a longer tail gets the whole pass, whose notes replace the live ones`() = runBlocking {
        // Two parts of the pass and their merge.
        val llm = ScriptedLlmRepository(listOf(note, secondNote, note, title, noTodos))
        val live = insight("Launch", "The launch moves.", at = 1_500L).copy(sourceSegmentIds = listOf("seg1000"))
        val rig = Rig(llm, segments = List(60) { segment(talk, at = 1_000L + it) }, insights = listOf(live))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(1, rig.repository.replaceCalls)
        assertEquals(3, rig.repository.insights.size)
        assertTrue(rig.repository.insights.none { it.id == live.id })
        assertEquals(InsightStrategy.END_OF_SESSION, rig.repository.session?.insightStrategy)
    }

    @Test
    fun `a Translate recording's long tail gets a final note on its last part, which cites all of it`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf("Der Start wird verschoben.", "Start verschoben", noTodos))
        val translation = session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de")
        val lines = List(60) { segment(talk, at = 1_000L + it) }
        val settings = AppSettings(translationSystemPrompt = "Translate into {target_language}.")
        val rig = Rig(llm, translation, lines, settings)

        rig.finish()

        assertEquals(0, rig.repository.replaceCalls)
        assertEquals(GenerateFinalInsightUseCase.MAX_USER_PROMPT_CHARS, llm.prompts[0].first.length)
        assertEquals("Translate into German.", llm.prompts[0].second)
        assertEquals(lines.map { it.id }, rig.repository.insights.single().sourceSegmentIds)
        assertEquals(InsightStrategy.REAL_TIME, rig.repository.session?.insightStrategy)
    }

    @Test
    fun `a recording that has a name keeps it`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, todo))
        val rig = Rig(llm, session(name = "Launch call"), listOf(segment(talk, at = 1_000L)))

        rig.finish()

        assertEquals(listOf("Transcript", "Lines", "Notes", "Todos", "Map"), rig.events)
        assertEquals("Launch call", rig.repository.session?.name)
    }

    @Test
    fun `too little said gives no note, no name and no map, but the to-dos are still looked for`() = runBlocking {
        val llm = FakeLlmRepository(answer = noTodos)
        val rig = Rig(llm, segments = listOf(segment("Okay then", at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        // The Notes step doesn't begin: it found too little text before any model was chosen.
        assertEquals(listOf("Transcript", "Lines", "too little", "Todos"), rig.events)
        assertEquals(listOf(FinishRecordingUseCase.TooLittleText(2, 20, keepsNotes = false, translation = false)), rig.tooLittle)
        assertTrue(rig.tooLittle.single().leavesNoNotes)
        assertNull(rig.repository.session?.name)
        assertTrue(rig.repository.insights.isEmpty())
        assertTrue(rig.map.calls.isEmpty())
        assertEquals(0, rig.noModelNeeded)
    }

    // ─── Too little text for notes, known before a model is chosen ───────────────

    /** [words] words of speech in lines of five, a second apart, as a short voice note has. */
    private fun shortNote(words: Int): List<TranscriptionSegment> {
        val vocabulary = talk.split(" ")
        return (0 until words).chunked(5).mapIndexed { line, range ->
            segment(range.joinToString(" ") { vocabulary[it % vocabulary.size] }, at = 1_000L * (line + 1))
        }
    }

    @Test
    fun `a 17-word note says at once that it has too little for notes, and E2B is chosen only as the name begins`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(title, todo, topics, summary))
        val rig = Rig(llm, segments = shortNote(17), finishing = FakeFinishingModelRepository(path = LARGE_MODEL), events = llm.log)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        // As on the phone at 14:20 on 2026-10-13: before, the Notes step chose E2B, waited 16 s
        // for room for it, and only then found the text too short.
        assertEquals(
            listOf(
                "Transcript", "Lines", "too little",
                "Name", "switch $LARGE_MODEL", "prompt $LARGE_MODEL",
                "Todos", "prompt $LARGE_MODEL",
                "Map", "prompt $LARGE_MODEL", "prompt $LARGE_MODEL"
            ),
            rig.events
        )
        assertEquals(FinishRecordingUseCase.TooLittleText(17, 20, keepsNotes = false, translation = false), rig.tooLittle.single())
        assertTrue(rig.repository.insights.isEmpty())
        assertEquals(title, rig.repository.session?.name)
        assertEquals(listOf<FinishingModel>(FinishingModel.Switched), rig.models)
    }

    @Test
    fun `the bars stay as they were, 20 words get their final note and 19 none, with no model chosen for it`() = runBlocking {
        val enough = TwoModelLlmRepository(listOf(note, title, todo), log = mutableListOf())
        val atBar = Rig(enough, session(name = "Named"), shortNote(20), finishing = FakeFinishingModelRepository(path = LARGE_MODEL), events = enough.log)
        atBar.finish()
        assertEquals(listOf("Transcript", "Lines", "Notes", "switch $LARGE_MODEL", "prompt $LARGE_MODEL"), atBar.events.take(5))
        assertEquals(1, atBar.repository.insights.size)
        assertTrue(atBar.tooLittle.isEmpty())

        val short = TwoModelLlmRepository(listOf(todo), log = mutableListOf())
        val belowBar = Rig(short, session(name = "Named"), shortNote(19), finishing = FakeFinishingModelRepository(path = LARGE_MODEL), events = short.log)
        belowBar.finish()
        assertEquals(listOf("Transcript", "Lines", "too little", "Todos", "switch $LARGE_MODEL"), belowBar.events.take(5))
        assertEquals(19, belowBar.tooLittle.single().words)
        assertEquals(GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT, belowBar.tooLittle.single().neededWords)
        assertTrue(belowBar.repository.insights.isEmpty())
    }

    @Test
    fun `an end-of-session recording with too little for the whole pass chooses no model for it`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(title, todo), log = mutableListOf())
        val recording = session().copy(insightStrategy = InsightStrategy.END_OF_SESSION)
        val rig = Rig(llm, recording, shortNote(12), finishing = FakeFinishingModelRepository(path = LARGE_MODEL), events = llm.log)

        rig.finish()

        assertEquals(listOf("Transcript", "Lines", "too little", "Name", "switch $LARGE_MODEL"), rig.events.take(5))
        assertEquals(FinishRecordingUseCase.TooLittleText(12, GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH, false, false), rig.tooLittle.single())
        assertEquals(0, rig.repository.replaceCalls)
    }

    @Test
    fun `a short tail after the live notes gets no final note and no model for it, and the live notes stay`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(title, noTodos), log = mutableListOf())
        val live = insight("Launch", "The launch moves.", at = 1_500L).copy(sourceSegmentIds = listOf("seg1000"))
        val rig = Rig(
            llm,
            segments = listOf(segment(talk, at = 1_000L), segment("That was all for today.", at = 2_000L)),
            insights = listOf(live),
            finishing = FakeFinishingModelRepository(path = LARGE_MODEL),
            events = llm.log
        )

        rig.finish()

        assertEquals(listOf("Transcript", "Lines", "too little", "Name", "switch $LARGE_MODEL"), rig.events.take(5))
        val tooLittle = rig.tooLittle.single()
        assertEquals(5, tooLittle.words)
        assertTrue(tooLittle.keepsNotes)
        // The recording has notes: its page and home say nothing of too little text.
        assertFalse(tooLittle.leavesNoNotes)
        assertEquals(listOf(live), rig.repository.insights)
    }

    @Test
    fun `a Translate recording with too little to translate leaves no notes to speak of`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf("Start", noTodos))
        val translation = session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de")
        val rig = Rig(llm, translation, shortNote(8))

        rig.finish()

        val tooLittle = rig.tooLittle.single()
        assertTrue(tooLittle.translation)
        assertFalse(tooLittle.leavesNoNotes)
    }

    @Test
    fun `a recording no AI step has anything to do for loads no model, and says so`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(note), log = mutableListOf())
        // Named already, and its one line holds no word: no notes, name, to-dos or map.
        val rig = Rig(llm, session(name = "Named"), listOf(segment("   ", at = 1_000L)), finishing = FakeFinishingModelRepository(path = LARGE_MODEL))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertTrue(llm.log.isEmpty())
        assertTrue(rig.models.isEmpty())
        assertEquals(1, rig.noModelNeeded)
        assertEquals(0, rig.tooLittle.single().words)
        assertEquals(listOf("Transcript", "Lines", "too little"), rig.events)
    }

    @Test
    fun `a run that goes on past the Notes step says again that the recording has too little for notes`() = runBlocking {
        val llm = FakeLlmRepository(answer = noTodos)
        val rig = Rig(llm, segments = shortNote(9))

        rig.finish(resumeFrom = FinishStep.Name)

        assertEquals(listOf("too little", "Name", "Todos", "Map"), rig.events)
        assertTrue(rig.tooLittle.single().leavesNoNotes)
    }

    @Test
    fun `a run past the Notes step of a recording with notes says nothing of too little text`() = runBlocking {
        val llm = FakeLlmRepository(answer = noTodos)
        val rig = Rig(llm, segments = shortNote(9), insights = listOf(insight("Launch", "The launch moves.", at = 500L)))

        rig.finish(resumeFrom = FinishStep.Todos)

        assertTrue(rig.tooLittle.isEmpty())
        assertEquals(listOf("Todos", "Map"), rig.events)
    }

    // ─── The AI model on its way (the first run downloads it after the first recording) ───

    @Test
    fun `with the AI model on its way the run stops as the notes would begin, before their waits, and loads nothing`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = FakeFinishingModelRepository(path = LARGE_MODEL))

        assertEquals(FinishRecordingUseCase.Outcome.WaitsForModel(FinishStep.Notes), rig.finish(aiAvailable = false, modelPending = true))

        // The better transcript and the lines ran; the notes' hook didn't, so nothing waited for a
        // capture or for memory, and no model was chosen or loaded.
        assertEquals(listOf("Transcript", "Lines"), rig.events)
        assertTrue(rig.models.isEmpty())
        assertTrue(llm.prompts.isEmpty())
        assertTrue(rig.repository.insights.isEmpty())
        assertNull(rig.repository.session?.name)
        assertTrue(rig.todos.stored.isEmpty())
        assertEquals(0, rig.noModelNeeded)
        assertTrue(rig.tooLittle.isEmpty())
    }

    @Test
    fun `too little for notes is said at once while the model is on its way, and the run waits for it at the name`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(title, todo, topics, summary))
        val rig = Rig(llm, segments = shortNote(17))

        assertEquals(FinishRecordingUseCase.Outcome.WaitsForModel(FinishStep.Name), rig.finish(aiAvailable = false, modelPending = true))

        assertEquals(listOf("Transcript", "Lines", "too little"), rig.events)
        assertTrue(rig.tooLittle.single().leavesNoNotes)
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `a named recording too short for notes waits for the model at its to-dos`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(todo))
        val rig = Rig(llm, session(name = "Named"), shortNote(9))

        assertEquals(FinishRecordingUseCase.Outcome.WaitsForModel(FinishStep.Todos), rig.finish(aiAvailable = false, modelPending = true))

        assertEquals(listOf("Transcript", "Lines", "too little"), rig.events)
    }

    @Test
    fun `once the model is in, the run goes on from the step it waited at and finishes the recording`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))
        assertEquals(FinishRecordingUseCase.Outcome.WaitsForModel(FinishStep.Notes), rig.finish(aiAvailable = false, modelPending = true))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Notes))

        assertEquals(allSteps, rig.events)
        assertEquals(listOf("Launch moves to March"), rig.repository.insights.map { it.title })
        assertEquals(title, rig.repository.session?.name)
        assertEquals(listOf("Update the plan"), rig.todos.stored.map { it.text })
    }

    @Test
    fun `a recording no AI step has anything to do for is finished while the model is on its way`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note))
        val rig = Rig(llm, session(name = "Named"), listOf(segment("   ", at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(aiAvailable = false, modelPending = true))

        assertEquals(listOf("Transcript", "Lines", "too little"), rig.events)
        assertEquals(1, rig.noModelNeeded)
    }

    @Test
    fun `German talk gets German notes, and the language is kept on the recording`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, "Startverschiebung", noTodos))
        val rig = Rig(llm, segments = listOf(segment(germanTalk, at = 1_000L)))

        rig.finish()

        assertEquals("simple_listening prompt (de)", llm.prompts[0].second)
        assertEquals("title prompt (de)", llm.prompts[1].second)
        assertTrue(llm.prompts[2].second!!.startsWith("todo prompt (de)"))
        assertEquals("de", rig.repository.session?.outputLanguage)
    }

    @Test
    fun `a Malayalam recording's notes are written from its readings, with an English recording's prompts and no line on reading Malayalam`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val recording = session().copy(language = RecordingLanguage.MALAYALAM, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id)
        val rig = Rig(
            llm, recording, segments = listOf(segment(malayalamTalk, at = 1_000L)),
            reader = FakeReader(english = mapOf(malayalamTalk to malayalamReading))
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf("Transcript", "Lines", "Reading", "Notes", "Name", "Todos", "Map"), rig.events)
        val (notes, name) = llm.prompts
        assertEquals("simple_listening prompt (en)", notes.second)
        // D6: what an English recording's notes are asked with: the reading in place of the line, no line on reading Malayalam.
        assertEquals(malayalamReading, notes.first)
        assertFalse(ReadableSegments.needsReader(notes.first))
        // Named from the English notes the first step wrote.
        assertEquals("title prompt (en)", name.second)
        assertEquals(listOf("Launch moves to March"), rig.repository.insights.map { it.title })
        // D8: the transcript stays as it was said; its reading is kept beside it.
        assertEquals(listOf(malayalamTalk), rig.repository.segments.map { it.text })
        assertEquals(listOf(malayalamReading), rig.repository.readings.map { it.text })
        // Malayalam is never stored as the notes' language: the app doesn't write it.
        assertNotEquals("ml", rig.repository.session?.outputLanguage)
        assertEquals(listOf(ReadingStep.Counts(lines = 1, kept = 1)), rig.readings)
    }

    @Test
    fun `a Malayalam recording's notes are sized by its English readings, as an English recording's are`() = runBlocking {
        val malayalamLines = (1..8).map { segment(List(6) { malayalamTalk }.joinToString(" ").take(500), at = it * 1_000L) }
        // Varied sentences: a reading that says a run of words three times is a loop, and empty (D5).
        val reading = listOf(
            talk,
            "The budget meeting moves to Thursday afternoon in the small room upstairs.",
            "Tom brings the slides and the printed copies for everyone who joins late.",
            "After lunch the designers show their new screens to the product group.",
            "Then we choose the date of the launch party and book a place near the station."
        ).joinToString(" ").take(500)
        val english = malayalamLines.associate { it.text to reading }
        val malayalam = Rig(ScriptedLlmRepository(listOf(note, title, noTodos)), segments = malayalamLines, reader = FakeReader(english = english))
        val englishLines = (1..8).map { segment(talk.repeat(4).take(500), at = it * 1_000L) }
        val spoken = Rig(ScriptedLlmRepository(listOf(note, title, noTodos)), segments = englishLines)

        malayalam.finish()
        spoken.finish()

        // 4,000 characters of English get a final note: Malayalam's own budget no longer counts, as the AI model reads the readings.
        assertTrue(malayalam.batch.isEmpty())
        assertTrue(spoken.batch.isEmpty())
        assertEquals(1, malayalam.repository.insights.size)
    }

    @Test
    fun `the language spoken is stored with the recording, German for the German meeting feed, none when unsure`() = runBlocking {
        // The first line of the synthetic German meeting feed, whose recording stored "it" on 2026-10-14.
        val meeting = "Guten Morgen zusammen, fangen wir mit der Wochenplanung an, erstens der Umzug ins neue Büro. " +
            "Die Möbel werden am Montag geliefert."
        val translation = session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "en")
        val german = Rig(ScriptedLlmRepository(listOf("Good morning.", title, noTodos)), translation, listOf(segment(meeting, at = 1_000L)))
        val malayalam = Rig(ScriptedLlmRepository(listOf(note, title, noTodos)), segments = listOf(segment(malayalamTalk, at = 1_000L)))
        val short = Rig(ScriptedLlmRepository(listOf(note, title, noTodos)), segments = listOf(segment("Call Anna.", at = 1_000L)))

        german.finish()
        malayalam.finish()
        short.finish()

        assertEquals("de", german.repository.session?.inputLanguage)
        assertEquals("ml", malayalam.repository.session?.inputLanguage)
        assertEquals("", short.repository.session?.inputLanguage)
    }

    @Test
    fun `a Translate recording is written in its target language and named in it`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf("Das Treffen wurde verschoben.", "Treffen verschoben", noTodos))
        val translation = session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de")
        val settings = AppSettings(translationSystemPrompt = "Translate into {target_language}.")
        val rig = Rig(llm, translation, listOf(segment(talk, at = 1_000L)), settings)

        rig.finish()

        assertEquals("Translate into German.", llm.prompts[0].second)
        assertEquals("title prompt (de)", llm.prompts[1].second)
        assertEquals("de", rig.repository.session?.outputLanguage)
    }

    @Test
    fun `cancellation ends the run and starts no later step`() {
        val cancelled = object : LlmRepository by FakeLlmRepository() {
            override fun generateInsight(
                text: String,
                systemPrompt: String?,
                maxTokens: Int,
                grammar: OutputGrammar?
            ): Flow<String> = flow { throw CancellationException("the finishing is cancelled") }
        }
        val rig = Rig(cancelled, segments = listOf(segment(talk, at = 1_000L)))

        assertThrows(CancellationException::class.java) { runBlocking { rig.finish() } }

        assertEquals(listOf("Transcript", "Lines", "Notes"), rig.events)
        assertTrue(rig.todos.stored.isEmpty())
    }

    // ─── The joined lines ────────────────────────────────────────────────────────

    /** Joins the recording's two lines into one, as the joining after Stop would, and logs it. */
    private fun joining(events: MutableList<String>) = { repository: FakeTranscriptionRepository ->
        TranscriptLinesStep { sessionId ->
            events += "join $sessionId"
            repository.segments = listOf(segment(talk, at = 1_000L))
            1
        }
    }

    private val cutLines = listOf(
        segment("We agreed to move the launch to March, and Anna will update", at = 1_000L),
        segment("the plan before Friday so the whole team can review it on Monday morning.", at = 2_000L)
    )

    @Test
    fun `the lines are joined after the better transcript and before the notes, which read them joined`() = runBlocking {
        val events = mutableListOf<String>()
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, segments = cutLines, events = events, lines = joining(events))

        rig.finish()

        assertEquals(listOf("Transcript", "Lines", "join s1", "Notes", "Name", "Todos", "Map"), rig.events)
        assertEquals(talk, llm.prompts[0].first)
        assertEquals(listOf("seg1000"), rig.repository.insights.single().sourceSegmentIds)
    }

    @Test
    fun `lines that can't be joined stay as they are, and the notes, the name, the to-dos and the map still come`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val broken = { _: FakeTranscriptionRepository -> TranscriptLinesStep { error("the audio index could not be written") } }
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), lines = broken)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(allSteps, rig.events)
        assertEquals(listOf(FinishStep.Lines), rig.stepFailures)
        assertEquals(listOf("Launch moves to March"), rig.repository.insights.map { it.title })
        assertEquals(title, rig.repository.session?.name)
        assertEquals(listOf("Update the plan"), rig.todos.stored.map { it.text })
        assertEquals(listOf("Launch in March" to listOf("The launch moves to March.")), rig.map.linksOf("s1"))
    }

    @Test
    fun `a better transcript step that throws leaves the transcript, and the last note is written from it`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val broken = { _: FakeTranscriptionRepository -> BetterTranscriptStep { _, _, _, _ -> error("the settings could not be read") } }
        val live = insight("Budget", "The budget looks fine.", at = 500L)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), insights = listOf(live), better = broken)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf(FinishStep.Transcript), rig.stepFailures)
        assertEquals(allSteps, rig.events)
        // The live note stays and the tail gets its own: no notes rewritten for a transcript that didn't change.
        assertEquals(0, rig.repository.replaceCalls)
        assertEquals(2, rig.repository.insights.size)
    }

    // ─── A step that throws an error (0.7.4) ─────────────────────────────────────

    /**
     * 0.7.3 on the phone, 2026-10-19: PromptRules could not start on ICU, so the to-dos step threw
     * ExceptionInInitializerError, and each later use of the class NoClassDefFoundError.
     */
    @Test
    fun `a to-dos step and a map that throw errors of code that could not start fail alone, and the recording keeps its note and name`() = runBlocking {
        val brokenRules = object : ResourceProvider by FakeResourceProvider() {
            override fun getTodoExtractionPrompt(localeCode: String): String =
                throw ExceptionInInitializerError(java.util.regex.PatternSyntaxException("Syntax error in regexp pattern", "a pattern", 10))

            override fun getMindTopicsPrompt(localeCode: String): String = throw NoClassDefFoundError("Could not initialize the rules")
        }
        val llm = ScriptedLlmRepository(listOf(note, title))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), resources = brokenRules)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(allSteps, rig.events)
        assertEquals(listOf(FinishStep.Todos, FinishStep.Map), rig.stepFailures)
        assertTrue(rig.stepErrors[0] is ExceptionInInitializerError)
        assertTrue(rig.stepErrors[1] is NoClassDefFoundError)
        assertEquals(listOf("Launch moves to March"), rig.repository.insights.map { it.title })
        assertEquals(title, rig.repository.session?.name)
        assertTrue(rig.todos.stored.isEmpty())
        assertTrue(rig.map.linksOf("s1").isEmpty())
    }

    @Test
    fun `a notes step that runs out of memory fails alone, and the name, the to-dos and the map still come`() = runBlocking {
        val scripted = ScriptedLlmRepository(listOf(title, todo, topics, summary))
        var calls = 0
        val llm = object : LlmRepository by scripted {
            override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> =
                if (calls++ == 0) flow { throw OutOfMemoryError("no room for the answer") } else scripted.generateInsight(text, systemPrompt, maxTokens, grammar)
        }
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.NotesFailed, rig.finish())

        assertEquals(allSteps, rig.events)
        assertEquals(listOf(FinishStep.Notes), rig.stepFailures)
        assertTrue(rig.stepErrors.single() is OutOfMemoryError)
        assertTrue(rig.repository.insights.isEmpty())
        assertEquals(title, rig.repository.session?.name)
        assertEquals(listOf("Update the plan"), rig.todos.stored.map { it.text })
        assertEquals(listOf("Launch in March" to listOf("The launch moves to March.")), rig.map.linksOf("s1"))
    }

    @Test
    fun `a step that throws an error of code that could not start fails alone with the AI off too`() = runBlocking {
        val broken = { _: FakeTranscriptionRepository -> TranscriptLinesStep { throw ExceptionInInitializerError("the joiner could not start") } }
        val rig = Rig(ScriptedLlmRepository(listOf(note)), segments = cutLines, lines = broken)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(aiAvailable = false))

        assertEquals(listOf("Transcript", "Lines"), rig.events)
        assertEquals(listOf(FinishStep.Lines), rig.stepFailures)
        assertEquals(cutLines.map { it.text }, rig.repository.segments.map { it.text })
    }

    @Test
    fun `an error that is no step's alone ends the run, and no later step begins`() {
        val broken = { _: FakeTranscriptionRepository -> TranscriptLinesStep { throw AssertionError("a bug of the joiner") } }
        val rig = Rig(ScriptedLlmRepository(listOf(note, title, todo, topics, summary)), segments = listOf(segment(talk, at = 1_000L)), lines = broken)

        assertThrows(AssertionError::class.java) { runBlocking { rig.finish() } }

        assertEquals(listOf("Transcript", "Lines"), rig.events)
        assertTrue(rig.stepFailures.isEmpty())
        assertTrue(rig.repository.insights.isEmpty())
    }

    @Test
    fun `a cancel thrown by a step's work is still a cancel, never a failed step`() {
        val cancelled = { _: FakeTranscriptionRepository -> TranscriptLinesStep { throw CancellationException("the finishing is cancelled") } }
        val rig = Rig(ScriptedLlmRepository(listOf(note, title, todo, topics, summary)), segments = listOf(segment(talk, at = 1_000L)), lines = cancelled)

        assertThrows(CancellationException::class.java) { runBlocking { rig.finish() } }

        assertEquals(listOf("Transcript", "Lines"), rig.events)
        assertTrue(rig.stepFailures.isEmpty())
    }

    @Test
    fun `the lines are joined with the AI off too`() = runBlocking {
        val events = mutableListOf<String>()
        val rig = Rig(ScriptedLlmRepository(listOf(note)), segments = cutLines, events = events, lines = joining(events))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(aiAvailable = false))

        assertEquals(listOf("Transcript", "Lines", "join s1"), rig.events)
        assertEquals(talk, rig.repository.segments.single().text)
    }

    // ─── Going on from a step, and deferring ─────────────────────────────────────

    @Test
    fun `the steps before the one a run goes on from are not run again`() = runBlocking {
        val events = mutableListOf<String>()
        val llm = ScriptedLlmRepository(listOf(todo, topics, summary))
        val better = { _: FakeTranscriptionRepository ->
            BetterTranscriptStep { _, _, _, _ -> error("the better transcript ran again") }
        }
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), events = events, better = better, lines = joining(events))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Todos))

        assertEquals(listOf("Todos", "Map"), rig.events)
        assertTrue(rig.repository.insights.isEmpty())
        assertNull(rig.repository.session?.name)
        assertTrue(rig.betterTranscripts.isEmpty())
        assertEquals(listOf("Update the plan"), rig.todos.stored.map { it.text })
    }

    @Test
    fun `a run that goes on past a better transcript rewrites the notes from it, without transcribing again`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val made = session().copy(transcriptModel = SpeechModelId.COHERE_TRANSCRIBE.id)
        val better = { _: FakeTranscriptionRepository ->
            BetterTranscriptStep { _, _, _, _ -> error("the better transcript ran again") }
        }
        val rig = Rig(llm, made, listOf(segment(talk, at = 1_000L)), insights = listOf(liveNote), better = better)

        rig.finish(resumeFrom = FinishStep.Notes)

        assertEquals(listOf("Notes", "Name", "Todos", "Map"), rig.events)
        assertEquals(1, rig.repository.replaceCalls)
        assertEquals(listOf("Launch moves to March"), rig.repository.insights.map { it.title })
    }

    @Test
    fun `a run that goes on past the Transcript step of a Malayalam recording keeps its live notes, as its transcript is the live one`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        // Made live by the Malayalam model: no better transcript after Stop replaced it.
        val made = session().copy(transcriptModel = SpeechModelId.INDICCONFORMER_ML.id)
        val better = { _: FakeTranscriptionRepository ->
            BetterTranscriptStep { _, _, _, _ -> error("the better transcript ran again") }
        }
        val rig = Rig(llm, made, listOf(segment(talk, at = 1_000L)), insights = listOf(liveNote), better = better)

        rig.finish(resumeFrom = FinishStep.Notes)

        assertEquals(listOf("Notes", "Name", "Todos", "Map"), rig.events)
        // The whole pass of a replaced transcript would have replaced the live note.
        assertEquals(0, rig.repository.replaceCalls)
    }

    @Test
    fun `a run deferred at a step keeps what the steps before stored, and goes on from that step`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))
        rig.deferAt += FinishStep.Name

        assertEquals(FinishRecordingUseCase.Outcome.Deferred(FinishStep.Name), rig.finish())
        assertEquals(listOf("Transcript", "Lines", "Notes", "Name"), rig.events)
        assertEquals(1, rig.repository.insights.size)
        assertNull(rig.repository.session?.name)
        assertTrue(rig.todos.stored.isEmpty())

        rig.deferAt.clear()
        rig.events.clear()
        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Name))

        assertEquals(listOf("Name", "Todos", "Map"), rig.events)
        assertEquals(1, rig.repository.insights.size)
        assertEquals(title, rig.repository.session?.name)
    }

    @Test
    fun `a run can be deferred before the better transcript and before the lines`() = runBlocking {
        val rig = Rig(ScriptedLlmRepository(listOf(note)), segments = listOf(segment(talk, at = 1_000L)))

        rig.deferAt += FinishStep.Transcript
        assertEquals(FinishRecordingUseCase.Outcome.Deferred(FinishStep.Transcript), rig.finish())
        rig.deferAt.clear()
        rig.deferAt += FinishStep.Lines
        assertEquals(FinishRecordingUseCase.Outcome.Deferred(FinishStep.Lines), rig.finish())
        // Only the second run made it past the first step.
        assertEquals(1, rig.betterTranscripts.size)
    }

    // ─── A recording whose capture was cut off ───────────────────────────────────

    @Test
    fun `a recovered recording with nothing said is deleted, and no step runs and no model loads`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(
            llm,
            segments = listOf(segment(".", at = 1_000L), segment(" ", at = 2_000L)),
            finishing = FakeFinishingModelRepository(path = LARGE_MODEL),
            events = llm.log
        )

        assertEquals(FinishRecordingUseCase.Outcome.Discarded, rig.finish(recovered = true))

        assertTrue(rig.events.isEmpty())
        assertTrue(rig.models.isEmpty())
        assertTrue(llm.answeredBy.isEmpty())
        assertNull(rig.repository.session)
        assertTrue(rig.repository.segments.isEmpty())
        assertTrue(rig.todos.stored.isEmpty())
        assertEquals(0, rig.scheduler.calls)
        assertTrue(rig.map.calls.isEmpty())
    }

    /** A Transcript step that notes what it was told of the live transcript and heard no speech in all of the audio. */
    private class HeardNothing : BetterTranscriptStep {
        val told = mutableListOf<Boolean>()

        override suspend fun run(
            sessionId: String,
            mayChangeModel: Boolean,
            onStart: (BetterTranscriptRun) -> Unit,
            onProgress: (Float) -> Unit
        ): BetterTranscript = run(sessionId, mayChangeModel, onStart, onProgress, mayLackSpeech = false)

        override suspend fun run(
            sessionId: String,
            mayChangeModel: Boolean,
            onStart: (BetterTranscriptRun) -> Unit,
            onProgress: (Float) -> Unit,
            mayLackSpeech: Boolean
        ): BetterTranscript {
            told += mayLackSpeech
            return BetterTranscript.Skipped(SpeechModelId.PARAKEET_V3, BetterTranscript.Reason.NoSpeech)
        }
    }

    @Test
    fun `the Transcript step is told when the live recognizer left audio untranscribed`() = runBlocking {
        val step = HeardNothing()
        val rig = Rig(ScriptedLlmRepository(listOf(note, title, todo, topics, summary, note)), segments = listOf(segment(talk, at = 1_000L)), better = { step })

        rig.finish()
        rig.finish(resumeFrom = FinishStep.Transcript, recognizerFailed = true)

        assertEquals(listOf(false, true), step.told)
    }

    @Test
    fun `a recording whose audio waited untranscribed is deleted once all of it holds no speech and no line a word`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo))
        // A quick-key press stopped before the speech model was in: a length, its audio, no line.
        val rig = Rig(llm, recording = session().copy(durationMs = 3_000L), segments = emptyList(), better = { HeardNothing() })

        assertEquals(FinishRecordingUseCase.Outcome.Discarded, rig.finish(recognizerFailed = true))

        assertNull(rig.repository.session)
        assertEquals(listOf("Transcript"), rig.events)
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `one with a line that holds a word is kept, and so is one whose transcript lacked nothing`() = runBlocking {
        val withWords = Rig(ScriptedLlmRepository(listOf(note, title, todo, topics, summary)), segments = listOf(segment(talk, at = 1_000L)), better = { HeardNothing() })
        assertEquals(FinishRecordingUseCase.Outcome.Finished, withWords.finish(recognizerFailed = true))
        assertNotNull(withWords.repository.session)

        val lackedNothing = Rig(ScriptedLlmRepository(listOf(note)), recording = session().copy(durationMs = 3_000L), segments = emptyList(), better = { HeardNothing() })
        assertEquals(FinishRecordingUseCase.Outcome.Finished, lackedNothing.finish())
        assertNotNull(lackedNothing.repository.session)
    }

    @Test
    fun `with the AI off a recovered recording with nothing said is deleted as well`() = runBlocking {
        val rig = Rig(ScriptedLlmRepository(listOf(note)), segments = emptyList())

        assertEquals(FinishRecordingUseCase.Outcome.Discarded, rig.finish(aiAvailable = false, recovered = true))

        assertNull(rig.repository.session)
    }

    @Test
    fun `a recovered recording with a length is kept though no line holds a word`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo))
        val rig = Rig(llm, recording = session().copy(durationMs = 20_000L), segments = emptyList())

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(recovered = true, recognizerFailed = true))

        assertNotNull(rig.repository.session)
        // No word for notes either, and the screens hear so at once.
        assertEquals(listOf("Transcript", "Lines", "too little"), rig.events)
        assertEquals(0, rig.tooLittle.single().words)
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `a recovered recording with words is finished like any other`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(recovered = true))

        assertEquals(allSteps, rig.events)
        assertEquals(1, rig.repository.insights.size)
    }

    @Test
    fun `a recovered recording with nothing said that can't be deleted runs no step either`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo))
        val rig = Rig(llm, segments = listOf(segment(".", at = 1_000L)))
        rig.repository.deleteFailure = IllegalStateException("disk full")

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(recovered = true))

        assertNotNull(rig.repository.session)
        assertTrue(rig.events.isEmpty())
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `a handed-off recording is never deleted here, whatever its lines, as the recorder applied the silent rule`() = runBlocking {
        val rig = Rig(FakeLlmRepository(answer = noTodos), segments = listOf(segment(".", at = 1_000L)))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertNotNull(rig.repository.session)
        assertEquals(listOf("Transcript", "Lines"), rig.events.take(2))
    }

    // ─── The model for the work after Stop ───────────────────────────────────────

    @Test
    fun `with Gemma 4 E2B on the phone every AI step runs on it, loaded once as the first begins`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(note, title, todo))
        val rig = Rig(
            llm,
            segments = listOf(segment(talk, at = 1_000L)),
            finishing = FakeFinishingModelRepository(path = LARGE_MODEL),
            events = llm.log
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(
            listOf(
                "Transcript", "Lines",
                "Notes", "switch $LARGE_MODEL", "prompt $LARGE_MODEL",
                "Name", "prompt $LARGE_MODEL",
                "Todos", "prompt $LARGE_MODEL",
                "Map", "prompt $LARGE_MODEL"
            ),
            rig.events
        )
        assertEquals(listOf<FinishingModel>(FinishingModel.Switched), rig.models)
        assertEquals("Launch moves to March", rig.repository.insights.single().title)
        assertEquals(title, rig.repository.session?.name)
        assertEquals(listOf("Update the plan"), rig.todos.stored.map { it.text })
    }

    @Test
    fun `an end-of-session recording gets all its notes from E2B`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(note, title, todo))
        val recording = session().copy(insightStrategy = InsightStrategy.END_OF_SESSION)
        val rig = Rig(
            llm,
            recording,
            listOf(segment(talk, at = 1_000L), segment("That was all for today.", at = 2_000L)),
            finishing = FakeFinishingModelRepository(path = LARGE_MODEL)
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(List(4) { LARGE_MODEL }, llm.answeredBy)
        assertEquals(1, rig.repository.insights.size)
    }

    @Test
    fun `when E2B can't be loaded the recording is finished on the live model, loaded again`() = runBlocking {
        val tooBig = IllegalStateException("Not enough free memory for $LARGE_MODEL")
        val llm = TwoModelLlmRepository(listOf(note, title, todo), switchFailure = tooBig)
        val rig = Rig(
            llm,
            segments = listOf(segment(talk, at = 1_000L)),
            finishing = FakeFinishingModelRepository(path = LARGE_MODEL),
            events = llm.log
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf<FinishingModel>(FinishingModel.FellBack(tooBig)), rig.models)
        assertEquals(List(4) { LIVE_MODEL }, llm.answeredBy)
        // Tried once, not again for every step, nor for a while: no lack of memory, so the steps
        // run on the live model from then on.
        assertEquals(1, rig.events.count { it.startsWith("switch") })
        assertTrue(rig.modelWaits.isEmpty())
        assertEquals(listOf("Transcript", "Lines", "Notes", "switch $LARGE_MODEL", "load $LIVE_MODEL"), rig.events.take(5))
        assertEquals("Launch moves to March", rig.repository.insights.single().title)
        assertEquals(title, rig.repository.session?.name)
        assertEquals(listOf("Update the plan"), rig.todos.stored.map { it.text })
    }

    @Test
    fun `with E2B switched off the steps stay on the live model`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(note, title, todo))
        val rig = Rig(
            llm,
            segments = listOf(segment(talk, at = 1_000L)),
            finishing = FakeFinishingModelRepository(enabled = false, path = LARGE_MODEL)
        )

        rig.finish()

        assertEquals(listOf<FinishingModel>(FinishingModel.Live), rig.models)
        assertEquals(List(4) { LIVE_MODEL }, llm.answeredBy)
        assertTrue(llm.log.none { it.startsWith("switch") })
    }

    @Test
    fun `a model that may not change is not swapped out`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(note, title, todo))
        val rig = Rig(
            llm,
            segments = listOf(segment(talk, at = 1_000L)),
            finishing = FakeFinishingModelRepository(path = LARGE_MODEL)
        )

        rig.finish(mayChangeModel = false)

        assertEquals(listOf<FinishingModel>(FinishingModel.Live), rig.models)
        assertEquals(List(4) { LIVE_MODEL }, llm.answeredBy)
        assertTrue(llm.log.none { it.startsWith("switch") })
    }

    @Test
    fun `a recording that needs no model never loads E2B`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(note))
        val finishing = FakeFinishingModelRepository(path = LARGE_MODEL)

        Rig(llm, segments = emptyList(), finishing = finishing).finish()
        Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = finishing).finish(aiAvailable = false)

        assertTrue(llm.log.isEmpty())
    }

    @Test
    fun `cancellation while E2B loads ends the run before the step's work`() {
        val llm = TwoModelLlmRepository(listOf(note, title, todo), switchFailure = CancellationException("the finishing is cancelled"))
        val rig = Rig(
            llm,
            segments = listOf(segment(talk, at = 1_000L)),
            finishing = FakeFinishingModelRepository(path = LARGE_MODEL)
        )

        assertThrows(CancellationException::class.java) { runBlocking { rig.finish() } }

        assertEquals(listOf("Transcript", "Lines", "Notes"), rig.events)
        assertTrue(rig.models.isEmpty())
        assertTrue(llm.answeredBy.isEmpty())
    }

    // ─── E2B beside a live capture ───────────────────────────────────────────────

    @Test
    fun `beside a live capture E2B needs room to spare, and without it the run is deferred, not finished small`() = runBlocking {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val llm = RoomLlmRepository(models, roomBytes = 0)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = FakeFinishingModelRepository(path = LARGE_MODEL))
        rig.captureLive = true

        val outcome = rig.finish()

        assertEquals(FinishStep.Notes, (outcome as FinishRecordingUseCase.Outcome.Deferred).step)
        val noRoom = outcome.noRoom!!
        assertTrue(noRoom.neededBytes > noRoom.availableBytes)
        assertEquals(listOf(MARGIN), llm.margins)
        assertTrue(models.answeredBy.isEmpty())
        assertTrue(rig.models.isEmpty())
        assertTrue(rig.repository.insights.isEmpty())
        // The speech model beside it is the capture's own.
        assertEquals(0, rig.speechModelWaits)
        assertTrue(rig.modelWaits.isEmpty())
    }

    @Test
    fun `beside a live capture with room to spare E2B does the work`() = runBlocking {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val llm = RoomLlmRepository(models)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = FakeFinishingModelRepository(path = LARGE_MODEL))
        rig.captureLive = true

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf(MARGIN), llm.margins)
        assertEquals(List(4) { LARGE_MODEL }, models.answeredBy)
    }

    @Test
    fun `beside a capture with the Malayalam model in, E2B keeps the room for Parakeet v3 too`() = runBlocking {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val llm = RoomLlmRepository(models)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = FakeFinishingModelRepository(path = LARGE_MODEL))
        rig.captureLive = true
        rig.captureGrowth = SWITCH_BACK

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf(MARGIN + SWITCH_BACK), llm.margins)
        assertEquals(1, rig.growthAsks)
        assertEquals(List(4) { LARGE_MODEL }, models.answeredBy)
    }

    @Test
    fun `beside a capture with the Malayalam model in, room for the margin alone defers the run`() = runBlocking {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        // Room for E2B and its margin, as there was beside the small model; not for a switch back.
        val llm = RoomLlmRepository(models, roomBytes = MARGIN)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = FakeFinishingModelRepository(path = LARGE_MODEL))
        rig.captureLive = true
        rig.captureGrowth = SWITCH_BACK

        val outcome = rig.finish()

        assertEquals(FinishStep.Notes, (outcome as FinishRecordingUseCase.Outcome.Deferred).step)
        assertEquals(SWITCH_BACK, outcome.noRoom!!.neededBytes - outcome.noRoom!!.availableBytes)
        assertTrue(models.answeredBy.isEmpty())
        assertTrue(rig.models.isEmpty())
    }

    @Test
    fun `with no capture live, nothing is kept for a capture's speech model`() = runBlocking {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val llm = RoomLlmRepository(models)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = FakeFinishingModelRepository(path = LARGE_MODEL))
        rig.captureGrowth = SWITCH_BACK

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf(MARGIN), llm.margins)
        assertEquals(0, rig.growthAsks)
    }

    @Test
    fun `beside a capture whose speech model isn't in, E2B's room isn't measured and the run is deferred`() = runBlocking {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val llm = RoomLlmRepository(models)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = FakeFinishingModelRepository(path = LARGE_MODEL))
        // Its live model being changed after a switch of its language, as at its start.
        rig.captureLive = true
        rig.captureSettled = false

        val outcome = rig.finish()

        assertEquals(FinishRecordingUseCase.Outcome.Deferred(FinishStep.Notes, captureOpening = true), outcome)
        assertTrue(llm.margins.isEmpty())
        assertTrue(rig.models.isEmpty())
        assertEquals(listOf("Transcript", "Lines", "Notes", "settled?"), rig.events)
    }

    @Test
    fun `beside a capture whose speech model is in, E2B's room is measured once that is asked`() = runBlocking {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val llm = RoomLlmRepository(models)
        val rig = Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = FakeFinishingModelRepository(path = LARGE_MODEL))
        rig.captureLive = true
        llm.onMeasure = { rig.events += "measure" }

        rig.finish()

        assertEquals(listOf("Transcript", "Lines", "Notes", "settled?", "measure"), rig.events.take(5))
    }

    @Test
    fun `the run says the languages it reads and writes in, a Malayalam recording's English notes among them`() = runBlocking {
        val malayalam = Rig(
            ScriptedLlmRepository(listOf(note, title, noTodos)),
            session().copy(language = RecordingLanguage.MALAYALAM),
            segments = listOf(segment(malayalamTalk, at = 1_000L))
        )
        val german = Rig(ScriptedLlmRepository(listOf(note, title, noTodos)), segments = listOf(segment(germanTalk, at = 1_000L)))

        malayalam.finish()
        german.finish()

        assertEquals(listOf(FinishRecordingUseCase.Languages("ml", RecordingLanguage.MALAYALAM, "en")), malayalam.languages)
        assertEquals(listOf(FinishRecordingUseCase.Languages("de", RecordingLanguage.ENGLISH_OR_GERMAN, "de")), german.languages)
    }

    // ─── E2B with no capture live ────────────────────────────────────────────────

    /** A recording with Gemma 4 E2B on the phone ([finishing]) and [roomBytes] of room for it, as the app's repository measures it. */
    private fun idleRig(
        models: TwoModelLlmRepository,
        roomBytes: Long,
        finishing: FakeFinishingModelRepository = FakeFinishingModelRepository(path = LARGE_MODEL)
    ): Pair<Rig, RoomLlmRepository> {
        val llm = RoomLlmRepository(models, roomBytes, measuresPlainSwitch = true)
        return Rig(llm, segments = listOf(segment(talk, at = 1_000L)), finishing = finishing) to llm
    }

    @Test
    fun `with no capture live E2B needs the same room to spare, and with it loads at once, after the speech model of the recorder that closed is freed`() = runTest {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val (rig, llm) = idleRig(models, roomBytes = MARGIN)
        rig.onSpeechModelWait = { models.log += "speech model freed" }

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(0L, currentTime)
        assertEquals(listOf(MARGIN), llm.margins)
        assertEquals(listOf("speech model freed", "switch $LARGE_MODEL"), models.log.take(2))
        assertEquals(listOf<FinishingModel>(FinishingModel.Switched), rig.models)
        assertTrue(rig.modelWaits.isEmpty())
        assertEquals(List(4) { LARGE_MODEL }, models.answeredBy)
    }

    @Test
    fun `E2B that doesn't fit is tried again once a second, and memory back after 2 s gives it`() = runTest {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        // 98 MB short of the room to spare, until memory comes back 2 s on.
        val (rig, llm) = idleRig(models, roomBytes = MARGIN - 98 * MB)
        launch {
            delay(2_000L)
            llm.roomBytes = MARGIN
        }

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(2_000L, currentTime)
        assertEquals(List(3) { MARGIN }, llm.margins)
        assertEquals(1, rig.speechModelWaits)
        val short = (rig.modelWaits.first() as FinishRecordingUseCase.ModelWait.Short).noRoom
        assertEquals(98 * MB, short.neededBytes - short.availableBytes)
        assertEquals(FinishRecordingUseCase.ModelWait.Room(tries = 2), rig.modelWaits.last())
        assertEquals(listOf<FinishingModel>(FinishingModel.Switched), rig.models)
        assertEquals(List(4) { LARGE_MODEL }, models.answeredBy)
    }

    @Test
    fun `E2B never loads with less room to spare, not with its file's worth free nor with 300 MB less`() = runTest {
        // 300 MB short of the file (0.5.1's last try), the file's worth (0.5.0's bar, the load of
        // 2026-10-13 that ended 45 apps), and a byte short of the margin.
        for (room in listOf(-300 * MB, 0L, MARGIN - 1)) {
            val models = TwoModelLlmRepository(listOf(note, title, todo))
            val (rig, llm) = idleRig(models, roomBytes = room)

            assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

            // The first try and one a second for 15 s, each asking for the margin, and no other.
            assertEquals(List(16) { MARGIN }, llm.margins)
            assertTrue(models.log.none { it == "switch $LARGE_MODEL" })
            assertTrue(rig.models.single() is FinishingModel.FellBack)
            assertEquals(List(4) { LIVE_MODEL }, models.answeredBy)
        }
    }

    @Test
    fun `memory that never comes back gives the live model after 15 s, with how much was missing`() = runTest {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        // 98 MB short even of the file, as 3 s after a capture's Stop on the phone.
        val (rig, llm) = idleRig(models, roomBytes = -98 * MB)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(FinishRecordingUseCase.IDLE_RETRY_MS, currentTime)
        assertEquals(16, llm.margins.size)
        assertEquals(
            listOf(FinishRecordingUseCase.ModelWait.Short::class, FinishRecordingUseCase.ModelWait.StillShort::class),
            rig.modelWaits.map { it::class }
        )
        // What the log says, from the last try: the margin and the 98 MB on top of it.
        val still = (rig.modelWaits.last() as FinishRecordingUseCase.ModelWait.StillShort).noRoom
        val cause = (rig.models.single() as FinishingModel.FellBack).cause as NotEnoughMemoryException
        assertSame(still, cause)
        assertEquals(MARGIN + 98 * MB, cause.neededBytes - cause.availableBytes)
        assertEquals(List(4) { LIVE_MODEL }, models.answeredBy)
        assertEquals("Launch moves to March", rig.repository.insights.single().title)
    }

    @Test
    fun `a capture that goes live during the tries defers the run, and nothing loads beside it`() = runTest {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val (rig, llm) = idleRig(models, roomBytes = MARGIN - 98 * MB)
        launch {
            delay(3_500L)
            rig.captureLive = true
        }

        val outcome = rig.finish()

        assertEquals(FinishStep.Notes, (outcome as FinishRecordingUseCase.Outcome.Deferred).step)
        assertEquals(4_000L, currentTime)
        assertEquals(List(4) { MARGIN }, llm.margins)
        // The tries measured the bar beside a capture as well: what the last one missed.
        val noRoom = outcome.noRoom!!
        assertEquals(98 * MB, noRoom.neededBytes - noRoom.availableBytes)
        assertEquals(FinishRecordingUseCase.ModelWait.CaptureStarted, rig.modelWaits.last())
        assertTrue(rig.models.isEmpty())
        assertTrue(models.answeredBy.isEmpty())
        assertTrue(rig.repository.insights.isEmpty())
    }

    @Test
    fun `a capture that goes live during the wait for the speech model defers the run, and nothing loads while it opens`() = runTest {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        // Room enough even beside a capture, once it is open: this one has only just started.
        val (rig, llm) = idleRig(models, roomBytes = MARGIN + 100 * MB)
        rig.onSpeechModelWait = { rig.captureLive = true }

        val outcome = rig.finish()

        assertEquals(FinishRecordingUseCase.Outcome.Deferred(FinishStep.Notes, noRoom = null, captureOpening = true), outcome)
        assertEquals(1, rig.speechModelWaits)
        // Not measured, not loaded: the capture's speech model isn't in yet.
        assertTrue(llm.margins.isEmpty())
        assertTrue(models.log.none { it.startsWith("switch") })
        assertTrue(rig.models.isEmpty())
        assertTrue(rig.modelWaits.isEmpty())
        assertTrue(models.answeredBy.isEmpty())
        assertTrue(rig.repository.insights.isEmpty())
    }

    @Test
    fun `a capture that goes live as the last try measures defers the run, and the live model doesn't load beside it`() = runTest {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val (rig, llm) = idleRig(models, roomBytes = MARGIN - 98 * MB)
        // The first try and 15 more: the capture starts as the last of them measures.
        llm.onMeasure = { if (llm.margins.size == 16) rig.captureLive = true }

        val outcome = rig.finish()

        assertEquals(FinishStep.Notes, (outcome as FinishRecordingUseCase.Outcome.Deferred).step)
        assertEquals(FinishRecordingUseCase.IDLE_RETRY_MS, currentTime)
        assertEquals(List(16) { MARGIN }, llm.margins)
        val noRoom = outcome.noRoom!!
        assertEquals(98 * MB, noRoom.neededBytes - noRoom.availableBytes)
        assertEquals(
            listOf(FinishRecordingUseCase.ModelWait.Short::class, FinishRecordingUseCase.ModelWait.CaptureStarted::class),
            rig.modelWaits.map { it::class }
        )
        assertTrue(rig.models.isEmpty())
        assertTrue(models.log.none { it.startsWith("load ") })
        assertTrue(models.answeredBy.isEmpty())
    }

    @Test
    fun `a try past the memory check that still can't load E2B is no room found, and the live model does the work`() = runTest {
        val damaged = IllegalStateException("The AI model file is damaged or incomplete")
        val models = TwoModelLlmRepository(listOf(note, title, todo), switchFailure = damaged)
        val (rig, llm) = idleRig(models, roomBytes = MARGIN - 98 * MB)
        launch {
            delay(2_000L)
            llm.roomBytes = MARGIN
        }

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(2_000L, currentTime)
        assertEquals(List(3) { MARGIN }, llm.margins)
        // The memory came back, but E2B didn't load: the field log says so once, in the model's line.
        assertEquals(listOf(FinishRecordingUseCase.ModelWait.Short::class), rig.modelWaits.map { it::class })
        assertEquals(listOf<FinishingModel>(FinishingModel.FellBack(damaged)), rig.models)
        assertEquals(List(4) { LIVE_MODEL }, models.answeredBy)
    }

    @Test
    fun `E2B switched off during the tries is no room found either, and the steps stay on the live model`() = runTest {
        val models = TwoModelLlmRepository(listOf(note, title, todo))
        val finishing = FakeFinishingModelRepository(path = LARGE_MODEL)
        val (rig, llm) = idleRig(models, roomBytes = MARGIN - 98 * MB, finishing = finishing)
        launch {
            delay(1_500L)
            finishing.enabled = false
        }

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(2_000L, currentTime)
        assertEquals(List(2) { MARGIN }, llm.margins)
        assertEquals(listOf(FinishRecordingUseCase.ModelWait.Short::class), rig.modelWaits.map { it::class })
        assertEquals(listOf<FinishingModel>(FinishingModel.Live), rig.models)
        assertEquals(List(4) { LIVE_MODEL }, models.answeredBy)
    }

    @Test
    fun `the tries are 15 s, once a second, and E2B needs 600 MB to spare with a capture or without`() {
        assertEquals(15_000L, FinishRecordingUseCase.IDLE_RETRY_MS)
        assertEquals(1_000L, FinishRecordingUseCase.IDLE_RETRY_INTERVAL_MS)
        assertEquals(600 * MB, LoadFinishingModelUseCase.E2B_MARGIN_BYTES)
    }

    // ─── The better transcript after Stop ────────────────────────────────────────

    /** A step that puts [lines] in place of the transcript, as Transcribe again with [model] would. */
    private fun replacing(lines: List<TranscriptionSegment>, model: SpeechModelId = SpeechModelId.COHERE_TRANSCRIBE) =
        { repository: FakeTranscriptionRepository ->
            BetterTranscriptStep { _, _, onStart, onProgress ->
                onStart(BetterTranscriptRun(model) {})
                onProgress(1f)
                repository.segments = lines
                BetterTranscript.Replaced(model)
            }
        }

    private val firstPass = listOf(segment("We agreed to move the lunch to march and Anna will up date the plan", at = 1_000L))
    private val liveNote = insight("Lunch in March", "The lunch moves.", at = 500L)

    @Test
    fun `the better transcript comes first, and notes of it replace the live ones`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(
            llm,
            segments = firstPass,
            insights = listOf(liveNote),
            better = replacing(listOf(segment(talk, at = 1_000L)))
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(allSteps, rig.events)
        // The whole pass over the final transcript, not a last note over the first pass's tail.
        assertEquals(talk, llm.prompts[0].first)
        assertEquals(listOf("Launch moves to March"), rig.repository.insights.map { it.title })
        assertEquals(InsightStrategy.END_OF_SESSION, rig.repository.session?.insightStrategy)
        assertEquals(1, rig.repository.replaceCalls)
        assertEquals(listOf<BetterTranscript>(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE)), rig.betterTranscripts)
        assertEquals(listOf(SpeechModelId.COHERE_TRANSCRIBE), rig.runs.map { it.model })
        assertEquals(listOf(1f), rig.transcriptProgress)
    }

    @Test
    fun `a better transcript that was skipped leaves the live notes and writes the last one`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val skipped = BetterTranscript.Skipped(SpeechModelId.COHERE_TRANSCRIBE, BetterTranscript.Reason.NotEnoughMemory)
        val rig = Rig(
            llm,
            segments = listOf(segment(talk, at = 1_000L)),
            insights = listOf(liveNote),
            better = { BetterTranscriptStep { _, _, _, _ -> skipped } }
        )

        rig.finish()

        assertEquals(allSteps, rig.events)
        assertEquals(listOf("Lunch in March", "Launch moves to March"), rig.repository.insights.map { it.title })
        assertEquals(0, rig.repository.replaceCalls)
        assertEquals(listOf<BetterTranscript>(skipped), rig.betterTranscripts)
    }

    @Test
    fun `a better transcript that failed leaves the live notes and writes the last one`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val failed = BetterTranscript.Failed(SpeechModelId.PARAKEET_V2_EN, IllegalStateException("decode failed"))
        val rig = Rig(
            llm,
            segments = listOf(segment(talk, at = 1_000L)),
            insights = listOf(liveNote),
            better = {
                BetterTranscriptStep { _, _, onStart, _ ->
                    onStart(BetterTranscriptRun(SpeechModelId.PARAKEET_V2_EN) {})
                    failed
                }
            }
        )

        rig.finish()

        assertEquals(allSteps, rig.events)
        assertEquals(listOf("Lunch in March", "Launch moves to March"), rig.repository.insights.map { it.title })
        assertEquals(0, rig.repository.replaceCalls)
    }

    @Test
    fun `the better transcript is made with the AI off too, and nothing else runs`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo))
        val rig = Rig(llm, segments = firstPass, better = replacing(listOf(segment(talk, at = 1_000L))))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(aiAvailable = false))

        assertEquals(listOf("Transcript", "Lines"), rig.events)
        assertEquals(talk, rig.repository.segments.single().text)
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `the step hears whether the models may change`() = runBlocking {
        val owned = mutableListOf<Boolean>()
        val rig = Rig(
            ScriptedLlmRepository(listOf(note, title, todo)),
            segments = firstPass,
            better = {
                BetterTranscriptStep { _, mayChangeModel, _, _ ->
                    owned += mayChangeModel
                    BetterTranscript.Skipped(SpeechModelId.COHERE_TRANSCRIBE, BetterTranscript.Reason.NewerRecorder)
                }
            }
        )

        rig.finish(mayChangeModel = false)

        assertEquals(listOf(false), owned)
    }

    @Test
    fun `notes that can't be written from the better transcript leave the live ones`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(null, title, noTodos, topics, summary))
        val rig = Rig(
            llm,
            segments = firstPass,
            insights = listOf(liveNote),
            better = replacing(listOf(segment(talk, at = 1_000L)))
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf(liveNote), rig.repository.insights)
        assertEquals(0, rig.repository.replaceCalls)
        assertEquals(title, rig.repository.session?.name)
    }

    @Test
    fun `a final transcript with too little for notes leaves the live ones`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(title, noTodos))
        val rig = Rig(
            llm,
            segments = firstPass,
            insights = listOf(liveNote),
            better = replacing(listOf(segment("We agreed to move the launch to March", at = 1_000L)))
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf(liveNote), rig.repository.insights)
        assertEquals(0, rig.repository.replaceCalls)
    }

    @Test
    fun `a Translate recording keeps its live translation, and only its tail is translated`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf("Und Anna aktualisiert den Plan.", "Start verschoben", noTodos))
        val translation = session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de")
        val live = listOf(
            insight(null, "Wir haben vereinbart, den Start auf März zu verschieben.", at = 400L)
                .copy(sourceSegmentIds = listOf("seg1000")),
            insight(null, "Das ganze Team prüft den Plan am Montag.", at = 800L)
                .copy(sourceSegmentIds = listOf("seg1000"))
        )
        val tail = "and Anna will update the plan before Friday so the whole team can review it on Monday morning after the stand up"
        val rig = Rig(
            llm,
            translation,
            firstPass,
            AppSettings(translationSystemPrompt = "Translate into {target_language}."),
            insights = live,
            better = replacing(listOf(segment(talk, at = 1_000L), segment(tail, at = 2_000L)))
        )

        rig.finish()

        assertEquals(0, rig.repository.replaceCalls)
        assertEquals(live, rig.repository.insights.take(2))
        assertEquals(3, rig.repository.insights.size)
        assertEquals(tail to "Translate into German.", llm.prompts[0])
        assertEquals(InsightStrategy.REAL_TIME, rig.repository.session?.insightStrategy)
        // The transcript is the better one all the same.
        assertEquals(talk, rig.repository.segments.first().text)
    }

    @Test
    fun `an end-of-session recording keeps the parts written before a later step of its pass fails`() = runBlocking {
        // Two parts, then the merge of them fails.
        val llm = ScriptedLlmRepository(listOf(note, secondNote, null, title, noTodos, topics, summary))
        val recording = session().copy(insightStrategy = InsightStrategy.END_OF_SESSION)
        val rig = Rig(llm, recording, firstPass, better = replacing(List(60) { segment(talk, at = 1_000L + it) }))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(2, rig.repository.insights.size)
        assertTrue(rig.repository.insights.all { it.sourceSegmentIds.isNotEmpty() })
        assertEquals(1, rig.repository.replaceCalls)
    }

    @Test
    fun `a recovered recording with nothing said is deleted before the better transcript, which never runs`() = runBlocking {
        val asked = mutableListOf<String>()
        val rig = Rig(
            ScriptedLlmRepository(listOf(note, title, todo)),
            segments = listOf(segment(".", at = 1_000L)),
            better = { repository ->
                BetterTranscriptStep { sessionId, _, _, _ ->
                    asked += sessionId
                    repository.segments = listOf(segment(talk, at = 1_000L))
                    BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE)
                }
            }
        )

        assertEquals(FinishRecordingUseCase.Outcome.Discarded, rig.finish(recovered = true))

        assertTrue(asked.isEmpty())
        assertTrue(rig.betterTranscripts.isEmpty())
        assertTrue(rig.events.isEmpty())
        assertNull(rig.repository.session)
    }

    @Test
    fun `a cancel during the better transcript ends the run before any AI step`() {
        val llm = ScriptedLlmRepository(listOf(note, title, todo))
        val rig = Rig(
            llm,
            segments = firstPass,
            better = {
                BetterTranscriptStep { _, _, onStart, _ ->
                    onStart(BetterTranscriptRun(SpeechModelId.COHERE_TRANSCRIBE) {})
                    throw CancellationException("the finishing is cancelled")
                }
            }
        )

        assertThrows(CancellationException::class.java) { runBlocking { rig.finish() } }

        assertEquals(listOf("Transcript"), rig.events)
        assertTrue(llm.prompts.isEmpty())
    }

    // ─── The Reading step (0.8.0, R5) ───────────────────────────────────────────

    // Made up: "We will meet tomorrow morning." / "Bring the papers to the office." / "After that we go to the market."
    private val meet = "നമുക്ക് നാളെ രാവിലെ ഓഫീസിൽ കാണാം, എല്ലാവരും സമയത്ത് എത്തണം."
    private val papers = "പേപ്പറുകൾ എല്ലാം ഓഫീസിലേക്ക് കൊണ്ടുവരണം, പിന്നെ ബജറ്റ് നോക്കണം."
    private val market = "അതിനുശേഷം നമ്മൾ ഒരുമിച്ച് മാർക്കറ്റിൽ പോയി സാധനങ്ങൾ വാങ്ങും."
    private val threeLines = listOf(segment(meet, at = 1_000L), segment(papers, at = 2_000L), segment(market, at = 3_000L))
    private val threeReadings = mapOf(
        meet to "We will meet at the office tomorrow morning, and everyone has to be there on time.",
        papers to "All the papers have to be brought to the office, and then the budget has to be checked.",
        market to "After that we will go to the market together and buy the things we need for the trip."
    )

    /** A recording made in Malayalam, as the Malayalam model transcribed it live. */
    private val inMalayalam = session().copy(language = RecordingLanguage.MALAYALAM, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id)

    @Test
    fun `a recording without a Malayalam line has no Reading step, and the reader never loads`() = runBlocking {
        val rig = Rig(ScriptedLlmRepository(listOf(note, title, todo, topics, summary)), segments = listOf(segment(talk, at = 1_000L)))

        rig.finish(readerPending = true)

        assertEquals(allSteps, rig.events)
        assertTrue(rig.reader.events.isEmpty())
        assertTrue(rig.readingRuns.isEmpty())
    }

    @Test
    fun `a Malayalam recording waits for a reader on its way at its Reading step, before the step's waits and any model`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo))
        val rig = Rig(llm, inMalayalam, threeLines, reader = FakeReader(onPhone = false, english = threeReadings))

        assertEquals(FinishRecordingUseCase.Outcome.WaitsForModel(FinishStep.Reading, reader = true), rig.finish(readerPending = true))

        assertEquals(listOf("Transcript", "Lines"), rig.events)
        assertTrue(llm.prompts.isEmpty())
        assertTrue(rig.models.isEmpty())
        assertTrue(rig.repository.readings.isEmpty())
        // The language detection read the transcript as it was said (D8).
        assertEquals("ml", rig.repository.session?.inputLanguage)

        // The reader comes: the run goes on from its Reading step, and the notes read the readings.
        rig.reader.onPhone = true
        rig.events.clear()

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Reading))

        assertEquals(listOf("Reading", "Notes", "Name", "Todos", "Map"), rig.events)
        assertTrue(llm.prompts.first().first.contains(threeReadings.getValue(papers)))
        assertEquals(3, rig.repository.readings.size)
    }

    @Test
    fun `without the reader and none coming, a Malayalam recording gets no AI step, never the AI model on Malayalam`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, inMalayalam, threeLines, reader = FakeReader(onPhone = false))

        assertEquals(FinishRecordingUseCase.Outcome.Unread(FinishRecordingUseCase.Outcome.Unread.Why.NO_READER), rig.finish())

        assertEquals(listOf("Transcript", "Lines"), rig.events)
        assertTrue(llm.prompts.isEmpty())
        assertTrue(rig.models.isEmpty())
        assertTrue(rig.repository.insights.isEmpty())
        assertNull(rig.repository.session?.name)
        assertTrue(rig.todos.stored.isEmpty())
        assertEquals(0, rig.noModelNeeded)
        // Its transcript stays, and so does the language found in it.
        assertEquals(3, rig.repository.segments.size)
        assertEquals("ml", rig.repository.session?.inputLanguage)
    }

    @Test
    fun `the AI model on its way and the reader not coming leave the recording without AI steps too`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note))
        val rig = Rig(llm, inMalayalam, threeLines, reader = FakeReader(onPhone = false))

        assertEquals(
            FinishRecordingUseCase.Outcome.Unread(FinishRecordingUseCase.Outcome.Unread.Why.NO_READER),
            rig.finish(aiAvailable = false, modelPending = true)
        )
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `with the AI off a Malayalam recording is not read at all`() = runBlocking {
        val rig = Rig(ScriptedLlmRepository(listOf(note)), inMalayalam, threeLines, reader = FakeReader(english = threeReadings))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(aiAvailable = false))

        assertEquals(listOf("Transcript", "Lines"), rig.events)
        assertTrue(rig.reader.events.isEmpty())
    }

    @Test
    fun `the reader on the phone reads with the AI model on its way, and the run waits for the model at its notes`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note))
        val rig = Rig(llm, inMalayalam, threeLines, reader = FakeReader(english = threeReadings))

        assertEquals(FinishRecordingUseCase.Outcome.WaitsForModel(FinishStep.Notes), rig.finish(aiAvailable = false, modelPending = true))

        assertEquals(listOf("Transcript", "Lines", "Reading"), rig.events)
        assertEquals(3, rig.repository.readings.size)
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `the Reading step waits for the speech model to be freed, and defers beside a live capture, loading nothing`() = runBlocking {
        val rig = Rig(ScriptedLlmRepository(listOf(note)), inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        rig.captureLive = true

        assertEquals(FinishRecordingUseCase.Outcome.Deferred(FinishStep.Reading, captureOpening = true), rig.finish())
        assertEquals(listOf("Transcript", "Lines", "Reading"), rig.events)
        assertTrue(rig.reader.events.isEmpty())
        assertEquals(0, rig.speechModelWaits)

        // A capture that goes live while it waits for the speech model defers it as well.
        val opening = Rig(ScriptedLlmRepository(listOf(note)), inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        opening.onSpeechModelWait = { opening.captureLive = true }

        assertEquals(FinishRecordingUseCase.Outcome.Deferred(FinishStep.Reading, captureOpening = true), opening.finish())
        assertEquals(1, opening.speechModelWaits)
        assertTrue(opening.reader.events.isEmpty())
    }

    @Test
    fun `a capture that opens while the reader reads stops it, and the run goes on from the next line once it is over`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        rig.reader.yieldsAt = 2

        assertEquals(FinishRecordingUseCase.Outcome.Deferred(FinishStep.Reading, captureOpening = true), rig.finish())

        assertEquals(listOf(ReadingStep.Counts(lines = 3, kept = 1)), rig.readings)
        assertEquals(1, rig.repository.readings.size)
        assertTrue(llm.prompts.isEmpty())

        rig.reader.yieldsAt = null
        rig.events.clear()

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Reading))

        assertEquals(ReadingStep.Counts(lines = 2, kept = 2), rig.readings.last())
        assertEquals(listOf("Reading", "Notes", "Name", "Todos", "Map"), rig.events)
        assertEquals(threeReadings.values.toList(), rig.repository.getReadableSegments("s1").map { it.text })
    }

    @Test
    fun `the finisher's stop of the reading, as a capture starts, defers the run as well`() = runBlocking {
        val rig = Rig(ScriptedLlmRepository(listOf(note)), inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        // The finisher sees a capture start as the reader is handed out.
        rig.onReadingStart = { it.stop() }

        assertEquals(FinishRecordingUseCase.Outcome.Deferred(FinishStep.Reading, captureOpening = true), rig.finish())

        assertEquals(1, rig.readingRuns.size)
        assertTrue(rig.reader.events.isEmpty())
    }

    @Test
    fun `a run that goes on past the Reading step reads its stored readings, and one whose step was skipped gets no AI step`() = runBlocking {
        // The answers of two runs.
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary, note, title, todo, topics, summary))
        val rig = Rig(llm, inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())
        rig.repository.insights = emptyList()
        rig.repository.session = rig.repository.session?.copy(name = null)
        rig.events.clear()
        rig.reader.events.clear()

        // A process death during the notes: the readings are there, and nothing is read again.
        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Notes))
        assertEquals(listOf("Notes", "Name", "Todos", "Map"), rig.events)
        assertTrue(rig.reader.events.isEmpty())

        // The Reading step began twice and was skipped: its lines were never read (D7).
        val skipped = Rig(ScriptedLlmRepository(listOf(note)), inMalayalam, threeLines, reader = FakeReader(english = threeReadings))

        assertEquals(FinishRecordingUseCase.Outcome.Unread(FinishRecordingUseCase.Outcome.Unread.Why.SKIPPED), skipped.finish(resumeFrom = FinishStep.Notes))
        assertTrue(skipped.events.isEmpty())
        assertTrue(skipped.reader.events.isEmpty())
    }

    @Test
    fun `a reader the phone's memory stops ends the run to go on from Reading, and one held off by a speech model tries again`() = runBlocking {
        val memory = Rig(ScriptedLlmRepository(listOf(note)), inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        memory.reader.loadStops = true
        assertEquals(FinishRecordingUseCase.Outcome.LoadStopped(FinishStep.Reading), memory.finish())

        val held = Rig(ScriptedLlmRepository(listOf(note)), inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        held.reader.refusal = ReaderNotLoadedException.Why.SPEECH_MODEL
        assertEquals(FinishRecordingUseCase.Outcome.ReaderHeldOff(FinishStep.Reading, ReaderNotLoadedException.Why.SPEECH_MODEL), held.finish())

        // Files that went bad after the check: no reader coming, unless it is on its way.
        val damaged = Rig(ScriptedLlmRepository(listOf(note)), inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        damaged.reader.refusal = ReaderNotLoadedException.Why.NOT_ON_PHONE
        assertEquals(FinishRecordingUseCase.Outcome.Unread(FinishRecordingUseCase.Outcome.Unread.Why.NO_READER), damaged.finish())
        assertEquals(FinishRecordingUseCase.Outcome.WaitsForModel(FinishStep.Reading, reader = true), damaged.finish(readerPending = true))
    }

    @Test
    fun `a reader that fails leaves the recording without AI steps, and says so as a failed step`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note))
        val rig = Rig(llm, inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        rig.reader.failsAfter = 1

        assertEquals(FinishRecordingUseCase.Outcome.Unread(FinishRecordingUseCase.Outcome.Unread.Why.NOT_READ), rig.finish())

        assertEquals(listOf(FinishStep.Reading), rig.stepFailures)
        assertTrue(llm.prompts.isEmpty())
        assertEquals(ReadingStep.Counts(lines = 3, kept = 1), rig.readings.single())
    }

    @Test
    fun `the AI model is let go before the reader loads`() = runBlocking {
        val order = mutableListOf<String>()
        val scripted = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        var inMemory = true
        val llm = object : LlmRepository by scripted {
            override val isModelInMemory: Boolean get() = inMemory
            override suspend fun cleanup() {
                order += "unload"
                inMemory = false
            }
        }
        val rig = Rig(llm, inMalayalam, threeLines, reader = FakeReader(english = threeReadings))
        rig.reader.onRead = { order += "read" }

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf("unload", "read", "read", "read"), order.take(4))
    }

    @Test
    fun `a Malayalam recording's notes are English with German notes set, as the transcript gives, and nothing is stored as their language`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(note, title, todo, topics, summary))
        val rig = Rig(llm, inMalayalam, threeLines, settings = AppSettings(notesLanguage = NotesLanguage.DE), reader = FakeReader(english = threeReadings))

        rig.finish()

        // The readings are English, which the German setting would have given German notes of.
        assertEquals("simple_listening prompt (en)", llm.prompts.first().second)
        assertNull(rig.repository.session?.outputLanguage)
    }

    @Test
    fun `lines that are all fragments need no reader, and leave too little text for notes`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(title))
        // Made up: "Okay." / "Yes."
        val rig = Rig(llm, inMalayalam, listOf(segment("ശരി.", at = 1_000L), segment("അതെ.", at = 2_000L)), reader = FakeReader(onPhone = false))

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish())

        assertEquals(listOf("", ""), rig.repository.readings.map { it.text })
        assertTrue(rig.readingRuns.isEmpty())
        assertEquals(0, rig.tooLittle.single().words)
        assertTrue(llm.prompts.isEmpty())
    }

    // ─── Made again over the readings (0.8.0, R9: the backfill) ─────────────────

    // What the AI model made of the three lines as they were said, before the reader: junk.
    private val junkName = "Office Market Challenges"
    private val junkNote = insight("Workflow challenges", "- The workflow has problems.", at = 4_000L)
    private val junkTodo = Todo(
        id = "junk", sessionId = "s1", text = "Fix the workflow", status = TodoStatus.OPEN, capture = TodoCapture.INFERRED,
        sourceSegmentIds = listOf("seg2000"), createdAt = 5_000L, updatedAt = 5_000L
    )

    // The answers over the readings.
    private val readNote = """{"title": "Papers and the market", "summary": "All the papers go to the office, then the team goes to the market."}"""
    private val readTitle = "Office papers and market trip"
    private val readTodo = """{"items": [{"segment": "S2", "type": "task", "text": "Bring all the papers to the office", """ +
        """"when": "none", "capture": "commitment"}]}"""

    /** inMalayalam as an older version left it: named, noted and with a to-do from the lines as they were said. */
    private fun junkRig(llm: LlmRepository, recording: TranscriptionSession = inMalayalam.copy(name = junkName), notes: List<LlmInsight> = listOf(junkNote)) =
        Rig(llm, recording, threeLines, insights = notes, reader = FakeReader(english = threeReadings)).apply { todos.stored += junkTodo }

    @Test
    fun `made again over its readings, the 1B's notes, name and untouched to-dos give way to new ones, and the map is made again`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(readNote, readTitle, readTodo, topics, summary))
        val rig = junkRig(llm)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Reading, redo = true))

        assertEquals(listOf("Reading", "Notes", "Name", "Todos", "Map"), rig.events)
        assertEquals(3, rig.repository.readings.size)
        assertEquals(listOf(FinishRecordingUseCase.Redo(notesKept = false, nameKept = false, todosKept = 0, todosReplaced = 1)), rig.redos)
        // The notes are written as if it had none: one final note on every line, from the readings.
        assertEquals(listOf("Papers and the market"), rig.repository.insights.map { it.title })
        assertEquals(threeLines.map { it.id }, rig.repository.insights.single().sourceSegmentIds)
        threeReadings.values.forEach { assertTrue(llm.prompts[0].first.contains(it)) }
        // The name is made again, from the new notes, in the app's name's place.
        assertEquals(readTitle, rig.repository.session?.name)
        assertFalse(rig.repository.session!!.nameByOwner)
        assertFalse(llm.prompts[1].first.contains("Workflow"))
        // The 1B's to-do goes once the new one is stored, to the field log as read again.
        assertEquals(listOf("Bring all the papers to the office"), rig.todos.stored.map { it.text })
        assertEquals(listOf(TodoChangeLog.line("junk", TodoStatus.OPEN, null, TodoSource.READ_AGAIN)), rig.todoLines)
        // The map reads it again, the new notes and the readings.
        assertEquals("mind topics prompt (en)", llm.prompts[3].second)
        assertTrue(llm.prompts[3].first.contains("Papers and the market"))
        // What it had was kept first, to be given back should the owner say it was his.
        assertEquals(junkName to listOf(junkNote), rig.repository.keptBeforeRedo)
    }

    @Test
    fun `made again, what it has is kept before anything is replaced, and a keep that fails replaces nothing`() = runBlocking {
        val failing = junkRig(ScriptedLlmRepository(listOf(readNote, readTitle, readTodo, topics, summary)))
        failing.repository.keepFailure = IllegalStateException("database or disk is full")

        assertThrows(IllegalStateException::class.java) { runBlocking { failing.finish(resumeFrom = FinishStep.Reading, redo = true) } }

        assertEquals(listOf(junkNote), failing.repository.insights)
        assertEquals(junkName, failing.repository.session?.name)
        assertTrue(failing.todos.stored.any { it.id == "junk" })
    }

    @Test
    fun `made again, what the owner changed stays as he left it, told apart by the database's fields`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(readTodo, topics, summary))
        // A note he saved in the editor (edited_at), the name he typed (name_by_owner), a to-do he
        // changed a minute after it was found (updated_at), and one nobody touched.
        val hisNote = junkNote.copy(editedAt = 9_000L)
        val hisTodo = junkTodo.copy(id = "his", text = "Call the office", updatedAt = junkTodo.createdAt + 60_000L)
        val rig = junkRig(llm, inMalayalam.copy(name = "My own name", nameByOwner = true), notes = listOf(hisNote, junkNote.copy(id = "ins5000")))
        rig.todos.stored += hisTodo

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Reading, redo = true))

        assertEquals(listOf(FinishRecordingUseCase.Redo(notesKept = true, nameKept = true, todosKept = 1, todosReplaced = 1)), rig.redos)
        // No note written, no name asked for: only the to-dos and the map ask the model.
        assertEquals(listOf("Reading", "Todos", "Map"), rig.events)
        assertEquals(listOf("mind topics prompt (en)"), llm.prompts.drop(1).map { it.second })
        // Any note he changed keeps them all: the notes are one text.
        assertEquals(listOf(hisNote, junkNote.copy(id = "ins5000")), rig.repository.insights)
        assertEquals("My own name", rig.repository.session?.name)
        assertEquals(setOf("his", "todo-1"), rig.todos.stored.map { it.id }.toSet())
    }

    @Test
    fun `made again with too little readable text, the 1B's notes go, notes that fail leave them, and the name never reads them`() = runBlocking {
        // Readings of a few words each: too little for a final note.
        val few = Rig(
            ScriptedLlmRepository(listOf(readTitle, readTodo, topics, summary)), inMalayalam.copy(name = junkName), threeLines,
            insights = listOf(junkNote), reader = FakeReader(english = threeLines.associate { it.text to "Office tomorrow." })
        )

        assertEquals(FinishRecordingUseCase.Outcome.Finished, few.finish(resumeFrom = FinishStep.Reading, redo = true))

        assertTrue(few.repository.insights.isEmpty())
        assertEquals(1, few.tooLittle.size)

        // A note the model fails to write: the old notes stay, and the name is made from the readings;
        // the run's notes failed, so the backfill tries it again.
        val llm = ScriptedLlmRepository(listOf(null, readTitle, readTodo, topics, summary))
        val failed = junkRig(llm)

        assertEquals(FinishRecordingUseCase.Outcome.NotesFailed, failed.finish(resumeFrom = FinishStep.Reading, redo = true))

        assertEquals("simple_listening prompt (en)", llm.prompts[0].second)
        assertEquals(listOf(junkNote), failed.repository.insights)
        assertEquals(readTitle, failed.repository.session?.name)
        assertFalse(llm.prompts[1].first.contains("Workflow"))
        assertTrue(llm.prompts[1].first.contains(threeReadings.getValue(papers)))
    }

    @Test
    fun `made again, notes that come out empty leave the old ones, and the run's notes failed`() = runBlocking {
        // Both answers of the note leave no point: nothing written.
        val empty = """{"title": "", "points": []}"""
        val llm = ScriptedLlmRepository(listOf(empty, empty, readTitle, readTodo, topics, summary))
        val rig = junkRig(llm)

        assertEquals(FinishRecordingUseCase.Outcome.NotesFailed, rig.finish(resumeFrom = FinishStep.Reading, redo = true))

        assertEquals(listOf(junkNote), rig.repository.insights)
    }

    @Test
    fun `a note the owner saves in the editor while the notes are made again stays, and the new ones are dropped`() = runBlocking {
        val scripted = ScriptedLlmRepository(listOf(readNote, readTitle, readTodo, topics, summary))
        lateinit var rig: Rig
        // As the notes are asked for, he saves the old note in the editor.
        val llm = object : LlmRepository by scripted {
            override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?, lead: String?): Flow<String> {
                if (scripted.prompts.isEmpty()) rig.repository.insights = rig.repository.insights.map { it.copy(editedAt = 9_000L) }
                return scripted.generateInsight(text, systemPrompt, maxTokens, grammar, lead)
            }

            override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> =
                generateInsight(text, systemPrompt, maxTokens, grammar, lead = null)
        }
        rig = junkRig(llm)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Reading, redo = true))

        assertEquals(listOf(junkNote.copy(editedAt = 9_000L)), rig.repository.insights)
        // The name is still the app's, made again from the readings.
        assertEquals(readTitle, rig.repository.session?.name)
        assertFalse(scripted.prompts[1].first.contains("Workflow"))
    }

    @Test
    fun `made again with nothing readable, the 1B's notes and topics go without the model, and its name and to-do stay`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(readNote))
        // Readings far shorter than their lines: D5 empties every one (outside the band).
        val reader = FakeReader(english = threeReadings, output = { _, text -> ReaderOutput(text, pieces = 40, tokens = 2, meanLogProb = -0.4, capped = false) })
        val rig = Rig(llm, inMalayalam.copy(name = junkName), threeLines, insights = listOf(junkNote), reader = reader).apply { todos.stored += junkTodo }
        rig.map.upsertNode(MindNode("old", MindNodeKind.THEME, "Workflow", "workflow", createdAt = 0L, updatedAt = 0L))
        rig.map.upsertNode(MindNode("old-topic", MindNodeKind.TOPIC, "Workflow", "workflow", parentId = "old", createdAt = 0L, updatedAt = 0L))
        rig.map.link("old-topic", "s1", listOf("The workflow has problems."), emptyList(), at = 0L)

        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Reading, redo = true))

        // The Notes step finds too little text, before any model.
        assertEquals(listOf("Reading", "too little", "Map"), rig.events)
        assertEquals(listOf(ReadingStep.Counts(lines = 3, kept = 0, band = 3)), rig.readings)
        // The 1B's notes and the topics they gave go, and no model was loaded for it.
        assertTrue(rig.repository.insights.isEmpty())
        assertTrue(rig.map.linksOf("s1").isEmpty())
        assertTrue(llm.prompts.isEmpty())
        assertTrue(rig.models.isEmpty())
        assertEquals(1, rig.noModelNeeded)
        // Nothing to name it after or to find to-dos in: what it had stays, as after Transcribe again.
        assertEquals(junkName, rig.repository.session?.name)
        assertEquals(listOf("junk"), rig.todos.stored.map { it.id })
    }

    @Test
    fun `made again with the AI model on its way, it reads, waits at its notes, and goes on as a run made again`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(readNote, readTitle, readTodo, topics, summary))
        val rig = junkRig(llm)

        assertEquals(
            FinishRecordingUseCase.Outcome.WaitsForModel(FinishStep.Notes),
            rig.finish(aiAvailable = false, modelPending = true, resumeFrom = FinishStep.Reading, redo = true)
        )
        assertEquals(listOf("Reading"), rig.events)
        assertEquals(3, rig.repository.readings.size)
        // Nothing of before has changed yet.
        assertEquals(listOf(junkNote), rig.repository.insights)
        assertEquals(junkName, rig.repository.session?.name)
        assertTrue(llm.prompts.isEmpty())

        // The model is in: the job goes on from its Notes step, still made again.
        rig.events.clear()
        assertEquals(FinishRecordingUseCase.Outcome.Finished, rig.finish(resumeFrom = FinishStep.Notes, redo = true))

        assertEquals(listOf("Notes", "Name", "Todos", "Map"), rig.events)
        assertEquals(listOf("Papers and the market"), rig.repository.insights.map { it.title })
        assertEquals(readTitle, rig.repository.session?.name)
        // Kept once, by the first run: the copy is what an older version left.
        assertEquals(junkName to listOf(junkNote), rig.repository.keptBeforeRedo)
    }

    @Test
    fun `a run that isn't made again leaves the 1B's work as it is, the old way`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf(readNote, readTitle, readTodo, topics, summary))
        val rig = junkRig(llm)

        rig.finish(resumeFrom = FinishStep.Reading)

        assertTrue(rig.redos.isEmpty())
        assertEquals(junkName, rig.repository.session?.name)
        assertTrue(rig.repository.insights.contains(junkNote))
        assertTrue(rig.todos.stored.any { it.id == "junk" })
    }

    private companion object {
        /** The default: no model is set for after Stop. */
        val NO_BETTER_TRANSCRIPT = BetterTranscriptStep { _, _, _, _ ->
            BetterTranscript.Skipped(null, BetterTranscript.Reason.Off)
        }

        const val MB = 1_048_576L

        /** The room Gemma 4 E2B must leave free beyond its file, beside a capture or not. */
        const val MARGIN = LoadFinishingModelUseCase.E2B_MARGIN_BYTES

        /** What a switch of a Malayalam recording back to English or German adds: Parakeet v3 less the Malayalam model. */
        const val SWITCH_BACK = 508L * MB
    }
}
