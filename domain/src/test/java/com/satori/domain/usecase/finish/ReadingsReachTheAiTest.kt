package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeFinishingModelRepository
import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.KeptAudio
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.session
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.llm.RewriteOutcome
import com.satori.domain.usecase.llm.ShippedPrompts
import com.satori.domain.usecase.mindmap.FakeMindMapRepository
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.RecordingIndex
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase
import com.satori.domain.usecase.mindmap.TransactionalMindMap
import com.satori.domain.usecase.stt.MakeTextAgainUseCase
import com.satori.domain.usecase.todo.CountingScheduler
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.FakeTimeParser
import com.satori.domain.usecase.todo.FoundTodosLog
import com.satori.domain.usecase.todo.RecordedTodos
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The AI steps of a Malayalam recording read its readings (0.8.0, design R6), whichever use case
 * asks: the name, the to-dos, the map, Rewrite notes, the text made again and a whole finishing. Each
 * asks the model exactly what it asks of an English recording whose lines are those readings, with
 * the app's own prompts: the same system prompts, no line on reading Malayalam or on the language of
 * the notes (D6), and no Malayalam in any call, never the recording's own text in a system prompt.
 * The language they write in is found in the transcript as it was said (D8), and a due time read in
 * a reading is kept only where its line as it was said says when (D9). The Malayalam is made up,
 * glossed in English above it; the readings are made up too.
 */
class ReadingsReachTheAiTest {

    private val prompts = ShippedPrompts()

    // Made up: "Tomorrow morning at ten I must call the bank and ask about the loan." / "The roof of the shed in
    // the garden leaks again, a carpenter must be called." / "Next week the plumber will look at the kitchen tap." /
    // "A new shelf has to be bought for the garage."
    private val said = listOf(
        "നാളെ രാവിലെ പത്ത് മണിക്ക് ബാങ്കിൽ വിളിച്ച് ലോണിനെ കുറിച്ച് ചോദിക്കണം.",
        "തോട്ടത്തിലെ ഷെഡിന്റെ മേൽക്കൂര വീണ്ടും ചോരുന്നു, ഒരു ആശാരിയെ വിളിക്കണം.",
        "അടുത്ത ആഴ്ച പ്ലംബർ അടുക്കളയിലെ ടാപ്പ് നോക്കും.",
        "ഗാരേജിനായി ഒരു പുതിയ ഷെൽഫ് വാങ്ങണം."
    )

    // The reader's English of them, made up as well; the second one names a day its line doesn't say.
    private val read = listOf(
        "Tomorrow at ten I have to call the bank about the loan.",
        "The roof of the garden shed leaks again, so a carpenter has to come on Friday.",
        "Next week the plumber will look at the kitchen tap.",
        "A new shelf has to be bought for the garage."
    )

    private fun lines(texts: List<String>) = texts.mapIndexed { i, text ->
        TranscriptionSegment(id = "seg${i + 1}", sessionId = "s1", text = text, timestamp = 1_000L * (i + 1), isComplete = true)
    }

    private fun readingOf(line: TranscriptionSegment, text: String) =
        SegmentReading(line.id, "s1", SegmentReading.sourceHashOf(line.text), text, model = "reader-test", score = -0.4, createdAt = 1L)

    /**
     * The recording s1, said in English ([malayalam] false: its lines are [read]) or in Malayalam with
     * the readings of its lines stored; with [unread], none.
     */
    private fun recording(
        malayalam: Boolean,
        unread: Boolean = false,
        mode: RecordingMode = RecordingMode.SIMPLE_LISTENING,
        session: TranscriptionSession = session()
    ): FakeTranscriptionRepository {
        val lines = lines(if (malayalam) said else read)
        val language = if (malayalam) RecordingLanguage.MALAYALAM else RecordingLanguage.ENGLISH_OR_GERMAN
        return FakeTranscriptionRepository(
            session.copy(mode = mode, language = language, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id.takeIf { malayalam }),
            lines
        ).apply {
            if (malayalam && !unread) readings = lines.mapIndexed { i, line -> readingOf(line, read[i]) }
        }
    }

    /** One call of the model: its system prompt and its user message, the lead included. */
    private data class Call(val system: String?, val text: String)

    /** The model, answering each call by its kind, as its system prompt tells it, and keeping every call. */
    private inner class Model : LlmRepository by FakeLlmRepository() {
        val calls = mutableListOf<Call>()

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?, lead: String?): Flow<String> =
            generateInsight(if (lead.isNullOrBlank()) text else "$lead\n\n$text", systemPrompt, maxTokens, grammar)

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
            calls += Call(systemPrompt, text)
            emit(
                when (systemPrompt) {
                    in RecordingMode.entries.flatMap { listOf(prompts.getPromptForMode(it, "en"), prompts.getPromptForMode(it, "de")) } ->
                        """{"title": "Bank loan and shed roof", "points": ["Call the bank about the loan.", "A carpenter has to fix the shed roof."]}"""
                    prompts.getSessionTitlePrompt("en") -> "Bank Loan and Shed Roof"
                    prompts.getTodoExtractionPrompt("en") ->
                        """{"items": [{"segment": "S1", "type": "task", "text": "Call the bank about the loan", "when": "T1", "capture": "commitment"}, """ +
                            """{"segment": "S2", "type": "task", "text": "Call a carpenter on Friday about the shed roof", "when": "T2", "capture": "commitment"}]}"""
                    prompts.getMindTopicsPrompt("en") -> """{"topics": [{"label": "Bank loan", "points": ["Call the bank about the loan."]}]}"""
                    else -> "A summary of the bank loan."
                }
            )
        }
    }

    /** Neither a Malayalam letter nor sign in any call, and none of the recording's lines or readings in a system prompt. */
    private fun assertReadsNoMalayalam(calls: List<Call>) {
        assertTrue(calls.isNotEmpty())
        for (call in calls) {
            assertFalse(call.text, (call.text + call.system).any { it in 'ഀ'..'ൿ' })
            assertFalse(call.text, "Malayalam" in call.text || "Malayalam" in call.system.orEmpty())
            for (line in said + read) assertFalse(call.system, line in call.system.orEmpty())
        }
    }

    private val times = FakeTimeParser()
        .at("tomorrow at ten", LocalDateTime.of(1970, 1, 2, 10, 0))
        .on("on Friday", LocalDate.of(1970, 1, 2))

    /** The search for to-dos over [repository] with [model], storing in [todos], and its found items' log. */
    private class Todos(
        repository: FakeTranscriptionRepository,
        model: LlmRepository,
        times: FakeTimeParser,
        settings: FakeSettingsRepository = FakeSettingsRepository(AppSettings()),
        val todos: RecordedTodos = RecordedTodos()
    ) {
        val ungated = mutableListOf<Pair<Int, Int>>()
        private var ids = 0
        private val log = object : FoundTodosLog {
            override fun dropped(sessionId: String, count: Int, why: FoundTodosLog.Why) = Unit
            override fun ungated(sessionId: String, kept: Int, wouldDrop: Int) {
                ungated += kept to wouldDrop
            }
        }
        val useCase = ExtractSessionTodosUseCase(
            settings,
            repository,
            todos,
            ExtractTodosUseCase(model, ShippedPrompts(), times, now = { 5L }, newId = { "todo-${++ids}" }, foundLog = log),
            CountingScheduler(),
            NotesLanguageResolver(settings, repository) { "en" }
        )
    }

    @Test
    fun `the name is asked from the readings as an English recording's, and never in Malayalam`() = runBlocking {
        val english = Model()
        val malayalam = Model()
        val repository = recording(malayalam = true)

        AutoNameSessionUseCase(recording(malayalam = false), GenerateSessionTitleUseCase(english, prompts))("s1", "en")
        AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(malayalam, prompts))("s1", "en")

        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)
        assertEquals("Bank Loan and Shed Roof", repository.session?.name)
    }

    @Test
    fun `the to-dos are found in the readings as an English recording's, cite the Malayalam lines and keep a due time only where the line says when`() = runBlocking {
        val english = Model()
        val malayalam = Model()
        val englishTodos = Todos(recording(malayalam = false), english, times)
        val read = Todos(recording(malayalam = true), malayalam, times)

        assertTrue(englishTodos.useCase("s1") is ExtractSessionTodosUseCase.Outcome.Found)
        assertTrue(read.useCase("s1") is ExtractSessionTodosUseCase.Outcome.Found)

        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)
        // The to-do prompt of an English recording: no line of instructions on reading another language.
        assertEquals(prompts.getTodoExtractionPrompt("en"), malayalam.calls.single().system)
        // Nor the order to copy the words of each item's line, which a part in another language gets.
        assertFalse(malayalam.calls.single().text, ResourceProvider.TODO_SAID in malayalam.calls.single().text)

        val bySource = { todos: RecordedTodos -> todos.stored.associateBy { it.sourceSegmentIds.first() } }
        val englishFound = bySource(englishTodos.todos)
        val readFound = bySource(read.todos)
        assertEquals(englishFound.mapValues { it.value.text }, readFound.mapValues { it.value.text })
        // The bank's line says tomorrow and ten o'clock: the due time read in its English stays.
        assertEquals(englishFound.getValue("seg1").dueAt, readFound.getValue("seg1").dueAt)
        assertNotNull(readFound.getValue("seg1").dueAt)
        // The shed's line says no day: the reading's Friday sets no due time (D9), nor stays in the to-do's words.
        // The English one keeps it.
        assertNotNull(englishFound.getValue("seg2").dueAt)
        assertNull(readFound.getValue("seg2").dueAt)
        assertNull(readFound.getValue("seg2").remindAt)
        assertEquals("Call a carpenter about the shed roof", readFound.getValue("seg2").text)
        // Each cites a read line, and the gate of Malayalam lines, off for them, would have dropped none:
        // each line asks for what it says.
        assertEquals(listOf(read.todos.stored.size to 0), read.ungated)
        assertTrue(englishTodos.ungated.isEmpty())
    }

    @Test
    fun `what the gate of Malayalam lines would have dropped of a reading's to-dos is counted, not dropped`() = runBlocking {
        // Made up: "The roof of the shed leaks again." No request, no promise, no time in its Malayalam: the small
        // model's to-do of it would have gone, were the line read as it was said.
        val roof = TranscriptionSegment("seg1", "s1", "ഷെഡിന്റെ മേൽക്കൂര വീണ്ടും ചോരുന്നു.", timestamp = 1_000L, isComplete = true)
        val repository = FakeTranscriptionRepository(session().copy(language = RecordingLanguage.MALAYALAM), listOf(roof)).apply {
            readings = listOf(readingOf(roof, "The roof of the shed is leaking again, and I have to get it fixed."))
        }
        val model = object : LlmRepository by FakeLlmRepository(
            answer = """{"items": [{"segment": "S1", "type": "task", "text": "Get the shed roof fixed", "when": "none", "capture": "commitment"}]}"""
        ) {}
        val todos = Todos(repository, model, FakeTimeParser())

        todos.useCase("s1")

        // Kept, the gate off for a read line, and counted as one it would have dropped.
        val stored = todos.todos.stored
        assertTrue(stored.isNotEmpty())
        assertTrue(stored.all { it.sourceSegmentIds == listOf("seg1") })
        assertEquals(listOf(stored.size to stored.size), todos.ungated)
    }

    @Test
    fun `the map reads the readings as an English recording's, its links on the Malayalam lines, and one with nothing read is read without topics, the model not asked`() = runBlocking {
        fun map(repository: FakeTranscriptionRepository, model: Model): Pair<MapRecordingUseCase.Outcome, FakeMindMapRepository> {
            val index = object : RecordingIndex {
                override fun sessionIds() = listOfNotNull(repository.session?.id)
                override fun recordedAt(sessionId: String) = repository.session?.createdAt
                override fun segments(sessionId: String) = repository.segments.filter { it.sessionId == sessionId }
            }
            val settings = FakeSettingsRepository(AppSettings())
            val mindMap = FakeMindMapRepository(index)
            var ids = 0
            val useCase = MapRecordingUseCase(
                repository, TransactionalMindMap(mindMap), model, prompts, NotesLanguageResolver(settings, repository) { "en" },
                now = { 6L }, newId = { "n${++ids}" }
            )
            return runBlocking { useCase("s1") } to mindMap
        }
        val english = Model()
        val malayalam = Model()

        val (englishMapped, englishMap) = map(recording(malayalam = false), english)
        val (readMapped, readMap) = map(recording(malayalam = true), malayalam)

        assertEquals(englishMapped, readMapped)
        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)
        assertEquals(englishMap.linksOf("s1"), readMap.linksOf("s1"))
        // The topic of the bank links to the Malayalam line it was read from.
        assertEquals(listOf(listOf("seg1")), readMap.nodesForSession("s1").first().map { it.link.segmentIds })

        // None of its lines read, and no notes: no topic could be told from it, and the model isn't asked.
        val nothing = Model()
        val (unread, unreadMap) = map(recording(malayalam = true, unread = true), nothing)
        assertEquals(MapRecordingUseCase.Outcome.Mapped(emptyList()), unread)
        assertTrue(nothing.calls.isEmpty())
        val chars = said.sumOf { it.codePointCount(0, it.length) }
        assertTrue(unreadMap.calls.toString(), "skip s1 $chars" in unreadMap.calls)
    }

    @Test
    fun `Rewrite notes writes from the readings with an English recording's prompts, in English whatever the setting, and not at all with a line never read`() = runBlocking {
        fun rewrite(repository: FakeTranscriptionRepository, model: Model, setting: NotesLanguage = NotesLanguage.AUTO): Result<RewriteOutcome> {
            val settings = FakeSettingsRepository(AppSettings(notesLanguage = setting))
            val useCase = RewriteNotesUseCase(
                repository,
                settings,
                GenerateBatchInsightUseCase(model, settings, prompts),
                LoadFinishingModelUseCase(model, FakeFinishingModelRepository())
            )
            return runBlocking { useCase("s1") }
        }
        val english = Model()
        val malayalam = Model()
        val repository = recording(malayalam = true)

        assertEquals(RewriteOutcome.Replaced, rewrite(recording(malayalam = false), english).getOrThrow())
        assertEquals(RewriteOutcome.Replaced, rewrite(repository, malayalam).getOrThrow())

        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)
        assertEquals("Bank loan and shed roof", repository.insights.single().title)

        // German notes for every recording: the Malayalam one's still come in English, from what was said (D8).
        val german = Model()
        rewrite(recording(malayalam = true), german, NotesLanguage.DE)
        assertEquals(prompts.getPromptForMode(RecordingMode.SIMPLE_LISTENING, "en"), german.calls.first().system)

        // A line never read: none of its notes come from the rest, and the old ones stay.
        val unread = recording(malayalam = true, unread = true)
        unread.insights = listOf(insight("Old notes", "- Written before.", at = 9_000L))
        val none = Model()
        assertTrue(rewrite(unread, none).exceptionOrNull() is LinesNeverReadException)
        assertTrue(none.calls.isEmpty())
        assertEquals(listOf("Old notes"), unread.insights.map { it.title })
    }

    @Test
    fun `the text made again reads the readings as an English recording's, and with a line never read changes nothing`() = runBlocking {
        fun makeAgain(repository: FakeTranscriptionRepository, model: Model): Result<MakeTextAgainUseCase.Outcome> {
            val settings = FakeSettingsRepository(AppSettings())
            val language = NotesLanguageResolver(settings, repository) { "en" }
            val todos = RecordedTodos()
            val scheduler = CountingScheduler()
            val useCase = MakeTextAgainUseCase(
                repository,
                RewriteNotesUseCase(repository, settings, GenerateBatchInsightUseCase(model, settings, prompts), LoadFinishingModelUseCase(model, FakeFinishingModelRepository())),
                AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(model, prompts)),
                Todos(repository, model, times, settings, todos).useCase,
                LoadFinishingModelUseCase(model, FakeFinishingModelRepository()),
                language,
                todos,
                TodoActions(todos, scheduler)
            )
            return runBlocking { useCase("s1") }
        }
        val english = Model()
        val malayalam = Model()

        val englishMade = makeAgain(recording(malayalam = false), english).getOrThrow()
        val readMade = makeAgain(recording(malayalam = true), malayalam).getOrThrow()

        assertEquals(MakeTextAgainUseCase.Notes.Written, readMade.notes)
        assertTrue(readMade.name)
        assertEquals(englishMade.notes, readMade.notes)
        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)

        val unread = recording(malayalam = true, unread = true, session = session(name = "Old name"))
        unread.insights = listOf(insight("Old notes", "- Written before.", at = 9_000L))
        val none = Model()
        assertTrue(makeAgain(unread, none).exceptionOrNull() is LinesNeverReadException)
        assertTrue(none.calls.isEmpty())
        assertEquals("Old name", unread.session?.name)
        assertEquals(listOf("Old notes"), unread.insights.map { it.title })
    }

    // Made up: "the bank's loan", a name the owner could have typed in Malayalam.
    private val malayalamName = "ബാങ്കിന്റെ ലോൺ"

    // Made up: "call the carpenter", an older version's to-do copied from a Malayalam line.
    private val malayalamTodo = "ആശാരിയെ വിളിക്കണം"

    @Test
    fun `a name in Malayalam letters never reaches the model as the rewrite's topic`() = runBlocking {
        fun rewrite(repository: FakeTranscriptionRepository, model: Model) {
            val settings = FakeSettingsRepository(AppSettings())
            val useCase = RewriteNotesUseCase(
                repository,
                settings,
                GenerateBatchInsightUseCase(model, settings, prompts),
                LoadFinishingModelUseCase(model, FakeFinishingModelRepository())
            )
            assertEquals(RewriteOutcome.Replaced, runBlocking { useCase("s1") }.getOrThrow())
        }
        val english = Model()
        val malayalam = Model()

        rewrite(recording(malayalam = false), english)
        rewrite(recording(malayalam = true, session = session(name = malayalamName)), malayalam)

        // As an English recording without a name asks it: the topic is left out.
        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)
    }

    @Test
    fun `notes holding a Malayalam word are no input for the name, which comes from the readings`() = runBlocking {
        val english = Model()
        val malayalam = Model()
        val repository = recording(malayalam = true).apply {
            insights = listOf(insight("Bank", "- Call the bank about the $malayalamName.", at = 9_000L))
        }

        AutoNameSessionUseCase(recording(malayalam = false), GenerateSessionTitleUseCase(english, prompts))("s1", "en")
        AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(malayalam, prompts))("s1", "en")

        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)
        assertEquals("Bank Loan and Shed Roof", repository.session?.name)
    }

    @Test
    fun `a to-do of the recording in Malayalam letters is not shown to the model as noted, and stays as it was`() = runBlocking {
        val english = Model()
        val malayalam = Model()
        // An older version's to-do of the shed's line, copied from it, and one the owner dismissed.
        val old = listOf(
            Todo("old-1", "s1", malayalamTodo, sourceSegmentIds = listOf("seg2"), createdAt = 1L, updatedAt = 20_000L),
            Todo("old-2", "s1", "$malayalamTodo ഇന്ന്", status = TodoStatus.DISMISSED, sourceSegmentIds = listOf("seg2"), createdAt = 1L, updatedAt = 1L)
        )
        val read = Todos(recording(malayalam = true), malayalam, times, todos = RecordedTodos(*old.toTypedArray()))
        Todos(recording(malayalam = false), english, times).useCase("s1")
        read.useCase("s1")

        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)
        // The old ones stay as they were.
        assertTrue(read.todos.stored.containsAll(old))
    }

    @Test
    fun `notes holding a Malayalam word are no input for the map, which reads the readings`() = runBlocking {
        fun map(repository: FakeTranscriptionRepository, model: Model): MapRecordingUseCase.Outcome {
            val index = object : RecordingIndex {
                override fun sessionIds() = listOfNotNull(repository.session?.id)
                override fun recordedAt(sessionId: String) = repository.session?.createdAt
                override fun segments(sessionId: String) = repository.segments.filter { it.sessionId == sessionId }
            }
            val settings = FakeSettingsRepository(AppSettings())
            var ids = 0
            val useCase = MapRecordingUseCase(
                repository, TransactionalMindMap(FakeMindMapRepository(index)), model, prompts,
                NotesLanguageResolver(settings, repository) { "en" }, now = { 6L }, newId = { "n${++ids}" }
            )
            return runBlocking { useCase("s1") }
        }
        val english = Model()
        val malayalam = Model()
        val repository = recording(malayalam = true).apply {
            insights = listOf(insight("Bank", "- Call the bank about the $malayalamName.", at = 9_000L))
        }

        assertEquals(map(recording(malayalam = false), english), map(repository, malayalam))
        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)
    }

    @Test
    fun `a reading's to-do that names no action counts as one the gate of Malayalam lines would have dropped`() = runBlocking {
        // Made up: "A carpenter must be called for the shed's roof." Its Malayalam asks for it.
        val roof = TranscriptionSegment("seg1", "s1", "ഷെഡിന്റെ മേൽക്കൂരയ്ക്ക് ഒരു ആശാരിയെ വിളിക്കണം.", timestamp = 1_000L, isComplete = true)
        val repository = FakeTranscriptionRepository(session().copy(language = RecordingLanguage.MALAYALAM), listOf(roof)).apply {
            readings = listOf(readingOf(roof, "A carpenter has to be called for the roof of the shed."))
        }
        val model = object : LlmRepository by FakeLlmRepository(
            answer = """{"items": [{"segment": "S1", "type": "task", "text": "A carpenter for the shed roof", "when": "none", "capture": "commitment"}]}"""
        ) {}
        val todos = Todos(repository, model, FakeTimeParser())

        todos.useCase("s1")

        // Kept, the gate off for a read line; counted as one it would have dropped for naming no action.
        val stored = todos.todos.stored
        assertEquals(1, stored.size)
        assertEquals(listOf(1 to 1), todos.ungated)
    }

    @Test
    fun `under a German notes setting a Malayalam recording's to-dos and topics are still asked in English`() = runBlocking {
        val german = FakeSettingsRepository(AppSettings(notesLanguage = NotesLanguage.DE))
        val todosModel = Model()
        Todos(recording(malayalam = true), todosModel, times, settings = german).useCase("s1")
        assertEquals(prompts.getTodoExtractionPrompt("en"), todosModel.calls.single().system)

        val mapModel = Model()
        val repository = recording(malayalam = true)
        val index = object : RecordingIndex {
            override fun sessionIds() = listOfNotNull(repository.session?.id)
            override fun recordedAt(sessionId: String) = repository.session?.createdAt
            override fun segments(sessionId: String) = repository.segments.filter { it.sessionId == sessionId }
        }
        var ids = 0
        val map = MapRecordingUseCase(
            repository, TransactionalMindMap(FakeMindMapRepository(index)), mapModel, prompts,
            NotesLanguageResolver(german, repository) { "en" }, now = { 6L }, newId = { "n${++ids}" }
        )
        map("s1")
        assertEquals(prompts.getMindTopicsPrompt("en"), mapModel.calls.single().system)
    }

    @Test
    fun `a whole finishing of a Malayalam voice note asks the AI what it asks of the same English one, and never in Malayalam`() = runBlocking {
        fun finish(malayalam: Boolean, model: Model): FakeTranscriptionRepository {
            val repository = recording(malayalam = malayalam, unread = true)
            val settings = FakeSettingsRepository(AppSettings())
            val language = NotesLanguageResolver(settings, repository) { "en" }
            val index = object : RecordingIndex {
                override fun sessionIds() = listOfNotNull(repository.session?.id)
                override fun recordedAt(sessionId: String) = repository.session?.createdAt
                override fun segments(sessionId: String) = repository.segments.filter { it.sessionId == sessionId }
            }
            val map = FakeMindMapRepository(index)
            var nodes = 0
            val useCase = FinishRecordingUseCase(
                repository,
                GenerateFinalInsightUseCase(model, settings, prompts),
                GenerateBatchInsightUseCase(model, settings, prompts),
                AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(model, prompts)),
                Todos(repository, model, times, settings).useCase,
                MapRecordingUseCase(repository, map, model, prompts, language, now = { 6L }, newId = { "n${++nodes}" }),
                RefreshThemeSummariesUseCase(map, model, prompts, language, now = { 7L }),
                language,
                LoadFinishingModelUseCase(model, FakeFinishingModelRepository()),
                BetterTranscriptStep { _, _, _, _ -> BetterTranscript.Skipped(null, BetterTranscript.Reason.Off) },
                TranscriptLinesStep { 0 },
                DiscardSilentRecordingUseCase(repository, KeptAudio()),
                // The reader reads each Malayalam line as its English above.
                ReadingStep(repository, FakeReader(english = said.zip(read).toMap()), model, now = { 8L }, clock = { 0L })
            )
            assertEquals(FinishRecordingUseCase.Outcome.Finished, runBlocking { useCase(FinishRecordingUseCase.Request("s1", aiAvailable = true)) })
            return repository
        }
        val english = Model()
        val malayalam = Model()

        val englishRecording = finish(malayalam = false, english)
        val malayalamRecording = finish(malayalam = true, malayalam)

        // Notes, name, to-dos, map and the themes' summary, each asked alike.
        assertEquals(english.calls.map { it.system }, malayalam.calls.map { it.system })
        assertEquals(english.calls, malayalam.calls)
        assertReadsNoMalayalam(malayalam.calls)
        assertEquals(englishRecording.insights.map { it.title to it.content }, malayalamRecording.insights.map { it.title to it.content })
        assertEquals(englishRecording.session?.name, malayalamRecording.session?.name)
        // The transcript stays as it was said (D8), and its language is the one spoken.
        assertEquals(said, malayalamRecording.segments.map { it.text })
        assertEquals("ml", malayalamRecording.session?.inputLanguage)
    }
}
