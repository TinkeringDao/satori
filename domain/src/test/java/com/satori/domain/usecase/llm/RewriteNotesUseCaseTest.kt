package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeFinishingModelRepository
import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.LARGE_MODEL
import com.satori.domain.fakes.LIVE_MODEL
import com.satori.domain.fakes.TwoModelLlmRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.AppSettings
import com.satori.domain.model.BatchInsightProgress
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.RoomLlmRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rewriting swaps the notes in only when the new ones exist; everything else leaves the old. */
class RewriteNotesUseCaseTest {

    private val english = listOf(
        segment("We agreed to move the launch to March, and Anna will update the plan before Friday.", at = 1),
        segment("The budget review is next week, and I will send the licence list to the whole team by Monday.", at = 2),
        segment("Marketing is over budget because of the trade fair, but engineering is under because of open positions.", at = 3)
    )

    private val german = listOf(
        segment("Wir haben besprochen, dass der Umzug nach Köln erst im November stattfinden soll, weil die Wohnung noch nicht frei ist.", at = 1),
        segment("Den Transporter muss ich noch diese Woche reservieren, und die Kartons bekomme ich von Jonas aus dem Keller.", at = 2)
    )

    // Long enough to read, but with nothing in it that is English or German.
    private val undecided = listOf(
        segment("Milk eggs bread butter cheese apples bananas coffee ".repeat(5), at = 1)
    )

    private val answer = "{\"title\": \"Launch moves to March\", \"summary\": \"The launch moves to March.\", " +
        "\"action_items\": [\"Update the plan\"]}"

    private val old = listOf(insight("Old note", "Written before.", at = 10))

    /** [count] words of twelve letters: from sixteen words on the characters alone are enough. */
    private fun longWords(count: Int) = List(count) { "a".repeat(12) }.joinToString(" ")

    private fun repo(segments: List<TranscriptionSegment> = english) =
        FakeTranscriptionRepository(session().copy(mode = RecordingMode.SHORT_MEETING), segments, old)

    /** Gemma 4 E2B is not on the phone unless [finishing] says so. */
    private fun useCase(
        repo: TranscriptionRepository,
        llm: LlmRepository,
        settings: AppSettings = AppSettings(),
        finishing: FakeFinishingModelRepository = FakeFinishingModelRepository()
    ): RewriteNotesUseCase {
        val settingsRepository = FakeSettingsRepository(settings)
        return RewriteNotesUseCase(
            repo,
            settingsRepository,
            GenerateBatchInsightUseCase(llm, settingsRepository, FakeResourceProvider()),
            LoadFinishingModelUseCase(llm, finishing)
        )
    }

    // ─── What is written ─────────────────────────────────────────────────────────

    @Test
    fun `replaces the old notes with the new ones`() = runBlocking {
        val repo = repo()
        val result = useCase(repo, FakeLlmRepository(answer = answer))("s1")

        assertEquals(RewriteOutcome.Replaced, result.getOrNull())
        val note = repo.insights.single()
        assertEquals("Launch moves to March", note.title)
        assertEquals("The launch moves to March.", note.content)
        assertEquals("[\"Update the plan\"]", note.tasks)
        assertEquals("s1", note.sessionId)
        assertTrue(note.sourceSegmentIds.isEmpty())
        assertEquals(1, repo.replaceCalls)
    }

    @Test
    fun `marks the recording as written in one pass so its summary is pinned`() = runBlocking {
        val repo = repo()
        useCase(repo, FakeLlmRepository(answer = answer))("s1")
        assertEquals(InsightStrategy.END_OF_SESSION, repo.session?.insightStrategy)
    }

    @Test
    fun `a long recording gets a note per part and a summary`() = runBlocking {
        val lines = (1..12).map {
            segment("The team talked about the plan for the next quarter and the open risks. ".repeat(7), at = it.toLong())
        }
        val repo = repo(lines)
        // Each part its own note: since 0.7.3 a part that repeats the one before is none (NoteRepeats).
        val risks = "{\"title\": \"Open risks\", \"summary\": \"Two risks are still open for the quarter.\"}"
        val result = useCase(repo, FakeLlmRepository(answers = listOf(answer, risks, answer)))("s1")

        assertEquals(RewriteOutcome.Replaced, result.getOrNull())
        val (parts, summary) = repo.insights.partition { it.sourceSegmentIds.isNotEmpty() }
        assertEquals(2, parts.size)
        assertEquals(1, summary.size)
        assertEquals(lines.map { it.id }.toSet(), parts.flatMap { it.sourceSegmentIds }.toSet())
    }

    @Test
    fun `reports the pipeline's progress`() = runBlocking {
        val seen = mutableListOf<BatchInsightProgress>()
        useCase(repo(), FakeLlmRepository(answer = answer))("s1", onProgress = { seen += it })
        assertEquals(BatchInsightProgress.Mapping(1, 1), seen.first())
        assertEquals(BatchInsightProgress.Complete, seen.last())
    }

    // ─── What leaves the old notes alone ─────────────────────────────────────────

    @Test
    fun `a failed model run keeps the old notes`() = runBlocking {
        val repo = repo()
        val result = useCase(repo, FakeLlmRepository(failWith = IllegalStateException("boom")))("s1")

        assertTrue(result.isFailure)
        assertEquals(old, repo.insights)
        assertEquals(0, repo.replaceCalls)
    }

    @Test
    fun `a model that can't be loaded keeps the old notes`() = runBlocking {
        val repo = repo()
        val result = useCase(repo, FakeLlmRepository(reloadFailure = IllegalStateException("no model")))("s1")

        assertTrue(result.isFailure)
        assertEquals(old, repo.insights)
        assertEquals(0, repo.replaceCalls)
    }

    @Test
    fun `a notes store that fails keeps the old notes`() = runBlocking {
        val repo = repo().apply { replaceFailure = IllegalStateException("disk full") }
        val result = useCase(repo, FakeLlmRepository(answer = answer))("s1")

        assertTrue(result.isFailure)
        assertEquals(old, repo.insights)
        assertEquals(InsightStrategy.REAL_TIME, repo.session?.insightStrategy)
    }

    @Test
    fun `a model that writes nothing fails and keeps the old notes`() = runBlocking {
        val repo = repo()
        val result = useCase(repo, FakeLlmRepository(answer = ""))("s1")

        // Not "too little": there was plenty of text to write from.
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(old, repo.insights)
        assertEquals(0, repo.replaceCalls)
    }

    @Test
    fun `a cancel keeps the old notes`() = runBlocking {
        val repo = repo()
        val started = CompletableDeferred<Unit>()
        val rewrite = useCase(repo, Hanging(started))

        val job = launch { rewrite("s1") }
        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(old, repo.insights)
        assertEquals(0, repo.replaceCalls)
    }

    @Test
    fun `a cancel is thrown, not returned as a failure`() {
        val repo = repo()
        val cancelled = FakeLlmRepository(failWith = CancellationException("stopped"))

        assertThrows(CancellationException::class.java) {
            runBlocking { useCase(repo, cancelled)("s1") }
        }
        assertEquals(old, repo.insights)
        assertEquals(0, repo.replaceCalls)
    }

    @Test
    fun `a cancel that arrives with the model's last token writes nothing`() = runBlocking {
        val repo = repo()
        val llm = CancelsAfterAnswer(answer)
        val rewrite = useCase(repo, llm)

        val job = launch { rewrite("s1") }
        llm.job = job
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(old, repo.insights)
        assertEquals(0, repo.replaceCalls)
    }

    @Test
    fun `a cancel that comes as the notes are swapped in lets the swap end, and says the notes are new`() = runBlocking {
        val repo = repo()
        val swapping = CompletableDeferred<Unit>()
        val swapped = CompletableDeferred<Unit>()
        // As Room's transaction: it commits on its own thread, then the caller goes on, where a cancel shows.
        val slowSwap = object : TranscriptionRepository by repo {
            override suspend fun replaceInsights(sessionId: String, insights: List<LlmInsight>, strategy: InsightStrategy): Result<Unit> {
                val stored = repo.replaceInsights(sessionId, insights, strategy)
                swapping.complete(Unit)
                swapped.await()
                return stored
            }
        }
        var result: Result<RewriteOutcome>? = null

        // A recording that starts stops the rewrite as its notes are swapped in.
        val job = launch { result = useCase(slowSwap, FakeLlmRepository(answer = answer))("s1") }
        swapping.await()
        job.cancel()
        swapped.complete(Unit)
        job.join()

        assertEquals(RewriteOutcome.Replaced, result?.getOrNull())
        assertEquals("Launch moves to March", repo.insights.single().title)
    }

    // ─── Too little to write from ────────────────────────────────────────────────

    @Test
    fun `too little text is skipped without asking the model`() = runBlocking {
        val repo = repo(listOf(segment("Just a short note to self.", at = 1)))
        val llm = FakeLlmRepository(answer = answer)
        val result = useCase(repo, llm)("s1")

        assertEquals(RewriteOutcome.TooLittle, result.getOrNull())
        assertTrue(llm.prompts.isEmpty())
        assertEquals(0, llm.reloads)
        assertEquals(old, repo.insights)
        assertEquals(0, repo.replaceCalls)
    }

    @Test
    fun `a line that is not complete does not count as text`() = runBlocking {
        val repo = repo(english.map { it.copy(isComplete = false) })
        val llm = FakeLlmRepository(answer = answer)
        val result = useCase(repo, llm)("s1")

        assertEquals(RewriteOutcome.TooLittle, result.getOrNull())
        assertTrue(llm.prompts.isEmpty())
        assertEquals(old, repo.insights)
    }

    @Test
    fun `enough text starts at the threshold`() {
        // Twenty words each, so that only the characters decide: 200 of them, and one fewer.
        val justEnough = listOf(segment("a".repeat(10) + " aaaaaaaaa".repeat(19), at = 1))
        val oneShort = listOf(segment("a".repeat(9) + " aaaaaaaaa".repeat(19), at = 1))
        assertEquals(RewriteNotesUseCase.MIN_TOTAL_CHARS, justEnough.single().text.length)
        assertTrue(RewriteNotesUseCase.hasEnoughText(justEnough))
        assertTrue(!RewriteNotesUseCase.hasEnoughText(oneShort))
        assertTrue(!RewriteNotesUseCase.hasEnoughText(emptyList()))
    }

    @Test
    fun `enough text needs the words the pipeline runs on too`() = runBlocking {
        // Long words: plenty of characters, so only the number of words decides.
        val oneWordShort = listOf(segment(longWords(19), at = 1))
        val atTheFloor = listOf(segment(longWords(20), at = 1))
        assertTrue(!RewriteNotesUseCase.hasEnoughText(oneWordShort))
        assertTrue(RewriteNotesUseCase.hasEnoughText(atTheFloor))

        // The pipeline draws the same line: the screens never offer what it would turn down.
        val llm = FakeLlmRepository(answer = answer)
        val pipeline = GenerateBatchInsightUseCase(llm, FakeSettingsRepository(), FakeResourceProvider())
        pipeline(oneWordShort, "s1", RecordingMode.SHORT_MEETING, outputLanguage = null)
        assertTrue(llm.prompts.isEmpty())
        pipeline(atTheFloor, "s1", RecordingMode.SHORT_MEETING, outputLanguage = null)
        assertEquals(1, llm.prompts.size)
    }

    @Test
    fun `too few words is skipped without asking the model`() = runBlocking {
        val repo = repo(listOf(segment(longWords(19), at = 1)))
        val llm = FakeLlmRepository(answer = answer)
        val result = useCase(repo, llm)("s1")

        assertEquals(RewriteOutcome.TooLittle, result.getOrNull())
        assertTrue(llm.prompts.isEmpty())
        assertEquals(old, repo.insights)
        assertEquals(0, repo.replaceCalls)
    }

    @Test
    fun `a recording that is gone fails`() = runBlocking {
        val repo = FakeTranscriptionRepository(session = null, segments = english, insights = old)
        val result = useCase(repo, FakeLlmRepository(answer = answer))("s1")
        assertTrue(result.exceptionOrNull() is NoSuchElementException)
    }

    // ─── The model that writes them ──────────────────────────────────────────────

    @Test
    fun `Gemma 4 E2B on the phone rewrites the notes, as after Stop`() = runBlocking {
        val repo = repo()
        val llm = TwoModelLlmRepository(listOf(answer))
        val models = mutableListOf<FinishingModel>()

        val result = useCase(repo, llm, finishing = FakeFinishingModelRepository(path = LARGE_MODEL))(
            "s1",
            onModel = { models += it }
        )

        assertEquals(RewriteOutcome.Replaced, result.getOrNull())
        assertEquals(listOf<FinishingModel>(FinishingModel.Switched), models)
        assertEquals(listOf(LARGE_MODEL), llm.answeredBy)
        assertEquals("Launch moves to March", repo.insights.single().title)
    }

    @Test
    fun `a rewrite falls back to the live model when E2B can't be loaded`() = runBlocking {
        val repo = repo()
        val llm = TwoModelLlmRepository(listOf(answer), switchFailure = IllegalStateException("Not enough free memory"))
        val models = mutableListOf<FinishingModel>()

        val result = useCase(repo, llm, finishing = FakeFinishingModelRepository(path = LARGE_MODEL))(
            "s1",
            onModel = { models += it }
        )

        assertEquals(RewriteOutcome.Replaced, result.getOrNull())
        assertTrue(models.single() is FinishingModel.FellBack)
        assertEquals(listOf(LIVE_MODEL), llm.answeredBy)
        assertEquals(listOf("switch $LARGE_MODEL", "load $LIVE_MODEL", "prompt $LIVE_MODEL"), llm.log)
    }

    @Test
    fun `a rewrite loads Gemma 4 E2B only with 600 MB to spare beyond its file, as after Stop`() = runBlocking {
        val models = TwoModelLlmRepository(listOf(answer))
        val llm = RoomLlmRepository(models, roomBytes = LoadFinishingModelUseCase.E2B_MARGIN_BYTES)

        val result = useCase(repo(), llm, finishing = FakeFinishingModelRepository(path = LARGE_MODEL))("s1")

        assertEquals(RewriteOutcome.Replaced, result.getOrNull())
        assertEquals(listOf(LoadFinishingModelUseCase.E2B_MARGIN_BYTES), llm.margins)
        assertEquals(listOf(LARGE_MODEL), models.answeredBy)
    }

    @Test
    fun `with a MB less to spare E2B never loads, and the live model rewrites the notes`() = runBlocking {
        val repo = repo()
        val models = TwoModelLlmRepository(listOf(answer))
        // The file's worth free and 599 MB more, which 0.5.0's bar, the file alone, let through.
        val llm = RoomLlmRepository(
            models,
            roomBytes = LoadFinishingModelUseCase.E2B_MARGIN_BYTES - 1_048_576L,
            measuresPlainSwitch = true
        )
        val loaded = mutableListOf<FinishingModel>()

        val result = useCase(repo, llm, finishing = FakeFinishingModelRepository(path = LARGE_MODEL))(
            "s1",
            onModel = { loaded += it }
        )

        assertEquals(RewriteOutcome.Replaced, result.getOrNull())
        assertTrue((loaded.single() as FinishingModel.FellBack).cause is NotEnoughMemoryException)
        assertEquals(listOf(LIVE_MODEL), models.answeredBy)
        assertFalse(models.log.contains("switch $LARGE_MODEL"))
        assertEquals("Launch moves to March", repo.insights.single().title)
    }

    @Test
    fun `a rewrite while a recorder owns the loaded model stays on it`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(answer))
        val models = mutableListOf<FinishingModel>()

        useCase(repo(), llm, finishing = FakeFinishingModelRepository(path = LARGE_MODEL))(
            "s1",
            mayChangeModel = false,
            onModel = { models += it }
        )

        assertEquals(listOf<FinishingModel>(FinishingModel.Live), models)
        assertEquals(listOf(LIVE_MODEL), llm.answeredBy)
    }

    @Test
    fun `too little text loads no model at all`() = runBlocking {
        val llm = TwoModelLlmRepository(listOf(answer))
        val models = mutableListOf<FinishingModel>()

        val result = useCase(
            repo(listOf(segment("Just a short note to self.", at = 1))),
            llm,
            finishing = FakeFinishingModelRepository(path = LARGE_MODEL)
        )("s1", onModel = { models += it })

        assertEquals(RewriteOutcome.TooLittle, result.getOrNull())
        assertTrue(models.isEmpty())
        assertTrue(llm.log.isEmpty())
    }

    // ─── The language of the notes ───────────────────────────────────────────────

    @Test
    fun `auto writes German notes for a German recording`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer)
        useCase(repo(german), llm)("s1")
        assertEquals("short_meeting prompt (de)", llm.prompts.single().second)
    }

    @Test
    fun `a chosen language wins over what was said`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer)
        useCase(repo(german), llm, AppSettings(notesLanguage = NotesLanguage.EN))("s1")
        assertEquals("short_meeting prompt (en)", llm.prompts.single().second)
    }

    @Test
    fun `a translation keeps its own target language`() = runBlocking {
        val settings = AppSettings(notesLanguage = NotesLanguage.EN, translationSystemPrompt = "Translate into {target_language}.")
        val repo = FakeTranscriptionRepository(
            session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de"), english, old
        )
        val llm = FakeLlmRepository(answer = "Wir haben den Start auf März verschoben.")
        useCase(repo, llm, settings)("s1")
        assertEquals("Translate into German.", llm.prompts.single().second)
    }

    @Test
    fun `languageFor follows the setting, the transcript, then the stored language`() {
        val short = session().copy(mode = RecordingMode.SHORT_MEETING)
        val de = short.copy(outputLanguage = "de")

        assertEquals("en", RewriteNotesUseCase.languageFor(short, german, NotesLanguage.EN))
        assertEquals("de", RewriteNotesUseCase.languageFor(short, english, NotesLanguage.DE))
        assertEquals("en", RewriteNotesUseCase.languageFor(de, english, NotesLanguage.AUTO))
        assertEquals("de", RewriteNotesUseCase.languageFor(short, german, NotesLanguage.AUTO))
        assertEquals("de", RewriteNotesUseCase.languageFor(de, undecided, NotesLanguage.AUTO))
    }

    @Test
    fun `languageFor writes a Malayalam transcript's notes in English`() {
        val short = session().copy(mode = RecordingMode.SHORT_MEETING)
        // Go to the shop this evening and buy milk and bread; tomorrow at ten there is a meeting.
        val malayalam = listOf(
            segment("ഇന്ന് വൈകുന്നേരം കടയിൽ പോയി പാലും റൊട്ടിയും വാങ്ങണം", at = 1),
            segment("നാളെ രാവിലെ പത്ത് മണിക്ക് ടീമുമായി മീറ്റിംഗ് ഉണ്ട്", at = 2)
        )

        assertEquals("en", RewriteNotesUseCase.languageFor(short, malayalam, NotesLanguage.AUTO))
        assertEquals("en", RewriteNotesUseCase.languageFor(short.copy(outputLanguage = "de"), malayalam, NotesLanguage.AUTO))
        // Whatever the setting: German chosen there is for English and German recordings.
        assertEquals("en", RewriteNotesUseCase.languageFor(short, malayalam, NotesLanguage.DE))
    }

    @Test
    fun `languageFor leaves the language open when nothing says which`() {
        val short = session().copy(mode = RecordingMode.SHORT_MEETING)
        assertNull(RewriteNotesUseCase.languageFor(short, undecided, NotesLanguage.AUTO))
        // A language the app has no prompts for is not a reason to write in it.
        assertNull(RewriteNotesUseCase.languageFor(short.copy(outputLanguage = "fr"), undecided, NotesLanguage.AUTO))
    }

    @Test
    fun `languageFor gives a translation its target whatever the setting`() {
        val translation = session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de")
        assertEquals("de", RewriteNotesUseCase.languageFor(translation, english, NotesLanguage.EN))
        assertEquals("de", RewriteNotesUseCase.languageFor(translation, english, NotesLanguage.AUTO))
        assertNull(RewriteNotesUseCase.languageFor(translation.copy(outputLanguage = null), english, NotesLanguage.AUTO))
    }

    // ─── Test doubles ────────────────────────────────────────────────────────────

    /** Never answers; says it has started so the test can cancel it mid-run. */
    private class Hanging(private val started: CompletableDeferred<Unit>) : LlmRepository by FakeLlmRepository() {
        override fun generateInsight(
            text: String,
            systemPrompt: String?,
            maxTokens: Int,
            grammar: OutputGrammar?
        ): Flow<String> = flow {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    /** Answers in full and cancels [job] as it finishes, so the cancel arrives with the answer. */
    private class CancelsAfterAnswer(private val answer: String) : LlmRepository by FakeLlmRepository() {
        lateinit var job: Job

        override fun generateInsight(
            text: String,
            systemPrompt: String?,
            maxTokens: Int,
            grammar: OutputGrammar?
        ): Flow<String> = flow {
            emit(answer)
            job.cancel()
        }
    }
}
