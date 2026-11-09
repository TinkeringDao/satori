package com.satori.presentation.sessiondetails

import androidx.lifecycle.SavedStateHandle
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.Todo
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.InitializeLlmUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.stt.MakeTextAgainUseCase
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingLanguageUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingTypeUseCase
import com.satori.domain.usecase.transcription.DeleteSessionUseCase
import com.satori.domain.usecase.transcription.GetSessionDetailsUseCase
import com.satori.domain.usecase.transcription.RenameSessionUseCase
import com.satori.domain.usecase.transcription.UpdateInsightContentUseCase
import com.satori.domain.usecase.transcription.UpdateSegmentTextUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.ideas.CountedReminders
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.CategoriesOnPhone
import com.satori.presentation.main.FakeProcessing
import com.satori.presentation.main.FinishingModelChoice
import com.satori.presentation.main.HeldSettings
import com.satori.presentation.main.NoTimes
import com.satori.presentation.main.Pause
import com.satori.presentation.main.ReaderHere
import com.satori.presentation.main.RecordingsOnPhone
import com.satori.presentation.main.ScreenEngine
import com.satori.presentation.main.ScreenPrompts
import com.satori.presentation.main.StoppedWork
import com.satori.presentation.main.TodosOnPhone
import com.satori.presentation.main.ModePrompts
import com.satori.presentation.main.liveModelIn
import com.satori.presentation.main.malayalamModelIn
import com.satori.presentation.main.noMalayalamModelIn
import com.satori.presentation.main.readingsFirst
import com.satori.presentation.main.starterCategories
import com.satori.presentation.main.translatingCategory
import com.satori.presentation.sessions.SpeechModelsHere
import com.satori.presentation.sessions.StatusQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The recording's page gives its AI work up to a recorder that leaves IDLE (the quick key):
 * writing the notes and the new search for to-dos and ideas stop, what was there stays, and the
 * page says so once when it is opened again. Built with the use cases the app runs, over doubles
 * of the engine and the database, and a real [ActiveRecorder] that leaves IDLE mid-run.
 *
 * "Change type" stores the category and its mode, then writes the notes again with the new mode's
 * prompt, its name and to-dos left as they are; it waits while the recording is being finished
 * or the AI is in use, and a recording that starts stops its new notes as it stops Rewrite notes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailsViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val main = StandardTestDispatcher()
    private val gate = AiWorkGate()
    private val recorders = ActiveRecorder()
    private val stopped = StoppedWork()
    private val engine = ScreenEngine(gate)
    private val processing = FakeProcessing()
    // The reader of the page's work: its loads and releases go in the engine's log, in order.
    private val reader = ReaderHere(log = engine.log)
    private val queue = StatusQueue()
    private val settings = HeldSettings(AppSettings(notesLanguage = NotesLanguage.EN))
    private val todos = TodosOnPhone()
    private val reminders = CountedReminders()
    private val recordings = RecordingsOnPhone(
        TranscriptionSession("s1", "Workshop plans", createdAt = 1_000L, lastModifiedAt = 1_000L, mode = RecordingMode.SHORT_MEETING)
    )
    private val oldNote = LlmInsight("old", "s1", title = "Workshop", content = "Written before.", timestamp = 2_000L, sourceSegmentIds = emptyList())
    private val categories = CategoriesOnPhone(*starterCategories.toTypedArray(), translatingCategory)
    private val speechModels = SpeechModelsHere()

    // The recording as a voice note, as the quick key records one.
    private val voiceNote = TranscriptionSession(
        "s1", "Workshop plans", createdAt = 1_000L, lastModifiedAt = 1_000L,
        mode = RecordingMode.SIMPLE_LISTENING, categoryId = "voice-note"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        recordings.said(
            "s1",
            "Remind me to call the bank about the loan before the end of the month.",
            "Remind me to book a table at the harbour restaurant for the team dinner.",
            "The workshop moves to the new building next week, and the old shelves go to the basement."
        )
        recordings.noted("s1", oldNote)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The page of recording s1, as navigation opens it; Gemma 4 E2B is on the phone if [finishing]
     * says so, and the AI is told what to write in the words of [resources].
     */
    private fun TestScope.page(
        finishing: FinishingModelRepository = FinishingModelChoice(),
        resources: ResourceProvider = ScreenPrompts()
    ): SessionDetailsViewModel {
        val models = liveModelIn(tmp.root)
        val rewriteNotes = RewriteNotesUseCase(
            recordings,
            settings,
            GenerateBatchInsightUseCase(engine, settings, resources),
            LoadFinishingModelUseCase(engine, finishing)
        )
        val extractSessionTodos = ExtractSessionTodosUseCase(
            settings,
            recordings,
            todos,
            ExtractTodosUseCase(engine, resources, NoTimes),
            reminders,
            NotesLanguageResolver(settings, recordings)
        )
        val page = SessionDetailsViewModel(
            getSessionDetailsUseCase = GetSessionDetailsUseCase(recordings),
            deleteSessionUseCase = DeleteSessionUseCase(recordings, queue, engine),
            renameSessionUseCase = RenameSessionUseCase(recordings),
            updateSegmentTextUseCase = UpdateSegmentTextUseCase(recordings),
            updateInsightContentUseCase = UpdateInsightContentUseCase(recordings),
            rewriteNotesUseCase = rewriteNotes,
            initializeLlmUseCase = InitializeLlmUseCase(engine, settings),
            loadFinishingModel = LoadFinishingModelUseCase(engine, finishing),
            extractSessionTodos = extractSessionTodos,
            llmRepository = engine,
            settingsRepository = settings,
            todoRepository = todos,
            todoActions = TodoActions(todos, reminders),
            modelDownloadManager = models,
            llmProcessingServiceController = processing,
            finishingQueue = queue,
            aiGate = gate,
            activeRecorder = recorders,
            stoppedWork = stopped,
            categoryRepository = categories,
            changeRecordingType = ChangeRecordingTypeUseCase(recordings, categories),
            changeRecordingLanguage = ChangeRecordingLanguageUseCase(recordings, speechModels),
            makeTextAgainUseCase = MakeTextAgainUseCase(
                recordings,
                rewriteNotes,
                AutoNameSessionUseCase(recordings, GenerateSessionTitleUseCase(engine, resources)),
                extractSessionTodos,
                LoadFinishingModelUseCase(engine, finishing),
                NotesLanguageResolver(settings, recordings),
                todos,
                TodoActions(todos, reminders)
            ),
            ensureReadings = readingsFirst(recordings, reader, engine),
            savedStateHandle = SavedStateHandle(mapOf("sessionId" to "s1"))
        )
        advanceUntilIdle()
        return page
    }

    /** The Malayalam speech model on the phone the pages read, or not: its files, and what the language's store finds. */
    private fun malayalamOnPhone(onPhone: Boolean) {
        val models = liveModelIn(tmp.root)
        if (onPhone) malayalamModelIn(models) else noMalayalamModelIn(models)
        speechModels.malayalam = onPhone
    }

    /** The page has looked whether the Malayalam model is on the phone, as it does each time it shows. */
    private suspend fun TestScope.lookedForMalayalam(page: SessionDetailsViewModel, onPhone: Boolean) {
        page.lookForMalayalam()
        page.uiState.first { it.malayalamOnPhone == onPhone }
        advanceUntilIdle()
    }

    /** What [page] shows once, collected as the screen collects it. */
    private fun TestScope.noticesOf(page: SessionDetailsViewModel): List<DetailsNotice> {
        val told = mutableListOf<DetailsNotice>()
        backgroundScope.launch { page.notices.collect { told += it } }
        // runCurrent, not advanceUntilIdle: that one leaves background work alone once no other is left.
        runCurrent()
        return told
    }

    /** The work under way, which holds the AI, has ended and let go of it, wherever it ran. */
    private suspend fun TestScope.workEnded() {
        gate.holder.first { it == null }
        advanceUntilIdle()
        runCurrent()
    }

    @Test
    fun `a recording that starts stops Rewrite notes, the old notes stay, the model goes under the gate and the page opened again says so once`() = runTest(main) {
        val call = Pause().also { engine.call = it }
        val page = page()

        page.rewriteNotes()
        call.reached.await()
        // A quick-key press: its recorder counts as starting at once, before its screen is up.
        recorders.expect("new")
        workEnded()

        assertEquals(1, engine.prompts.size)
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertEquals(0, recordings.swaps)
        assertTrue(engine.log.toString(), engine.log.contains("cleanup under ${AiWorkGate.REWRITE}"))
        assertTrue(processing.holders.isEmpty())
        assertNull(page.uiState.value.rewrite)
        assertNull(page.uiState.value.error)
        assertTrue(noticesOf(page).isEmpty())
        assertEquals(listOf(DetailsNotice.RewriteStopped), noticesOf(page()))
        assertTrue(noticesOf(page()).isEmpty())
    }

    @Test
    fun `the page opened again while the stopped rewrite winds down is told as it ends`() = runTest(main) {
        // The engine's call runs to its end: the cancel shows only at its first token.
        val call = Pause().also { engine.call = it; engine.callThroughCancel = true }
        page().rewriteNotes()
        call.reached.await()
        recorders.expect("new")
        advanceUntilIdle()

        // The owner opens the recording's page again while that call still runs.
        val told = noticesOf(page())
        assertTrue(told.isEmpty())
        call.resume()
        workEnded()

        assertEquals(listOf(DetailsNotice.RewriteStopped), told)
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
    }

    @Test
    fun `a recording that starts as the new notes are swapped in lets the swap end, and tells nothing`() = runTest(main) {
        engine.answer = NOTES
        val swap = Pause().also { recordings.swap = it }
        page().rewriteNotes()
        swap.reached.await()
        recorders.expect("new")
        advanceUntilIdle()

        swap.resume()
        workEnded()

        assertEquals(listOf("Workshop moves next week"), recordings.notesOf("s1").map { it.title })
        assertTrue(noticesOf(page()).isEmpty())
    }

    @Test
    fun `a rewrite no recording interrupts swaps the notes in and tells no stop`() = runTest(main) {
        engine.answer = NOTES
        // Held at the swap only to know the work has begun: the page takes the AI in its coroutine.
        val swap = Pause().also { recordings.swap = it }
        page().rewriteNotes()
        swap.reached.await()
        swap.resume()
        workEnded()

        assertEquals(listOf("Workshop moves next week"), recordings.notesOf("s1").map { it.title })
        assertTrue(engine.log.contains("cleanup under ${AiWorkGate.REWRITE}"))
        assertTrue(noticesOf(page()).isEmpty())
    }

    @Test
    fun `a recording that starts stops Find to-dos and ideas again mid-search, nothing is stored and the page opened again says so once`() = runTest(main) {
        engine.answer = NOTHING_FOUND
        val call = Pause().also { engine.call = it }
        val page = page()

        page.findTodosAgain()
        call.reached.await()
        recorders.expect("new")
        workEnded()

        assertTrue(todos.stored.isEmpty())
        assertTrue(engine.log.toString(), engine.log.contains("cleanup under ${AiWorkGate.TODOS}"))
        assertTrue(processing.holders.isEmpty())
        assertNull(page.uiState.value.todoSearch)
        assertNull(page.uiState.value.error)
        assertTrue(noticesOf(page).isEmpty())
        assertEquals(listOf(DetailsNotice.SearchStopped), noticesOf(page()))
        assertTrue(noticesOf(page()).isEmpty())
    }

    @Test
    fun `a recording that starts as the finds are stored lets them all be stored, and tells nothing`() = runTest(main) {
        // The two to-dos said outright are found without the model; it adds nothing.
        engine.answer = NOTHING_FOUND
        val store = Pause().also { todos.store = it }
        page().findTodosAgain()
        store.reached.await()
        recorders.expect("new")
        advanceUntilIdle()

        store.resume()
        workEnded()

        assertEquals(2, todos.stored.size)
        assertTrue(todos.stored.all { it.sessionId == "s1" })
        assertEquals(1, reminders.reschedules)
        assertTrue(noticesOf(page()).isEmpty())
    }

    /**
     * Rewrite notes, then Find again, each to its end. Each is held where it stores its result only
     * to know it has begun: the page starts its work in its own coroutine, partly on IO.
     */
    private suspend fun TestScope.rewriteAndFindAgain(page: SessionDetailsViewModel) {
        val swap = Pause().also { recordings.swap = it }
        page.rewriteNotes()
        swap.reached.await()
        swap.resume()
        workEnded()
        engine.answer = NOTHING_FOUND
        val store = Pause().also { todos.store = it }
        page.findTodosAgain()
        store.reached.await()
        store.resume()
        workEnded()
    }

    @Test
    fun `Rewrite notes and Find again load Gemma 4 E2B only with 600 MB to spare beyond its file`() = runTest(main) {
        engine.answer = NOTES
        engine.roomBytes = LoadFinishingModelUseCase.E2B_MARGIN_BYTES
        val page = page(FinishingModelChoice(E2B))

        rewriteAndFindAgain(page)

        assertEquals(List(2) { LoadFinishingModelUseCase.E2B_MARGIN_BYTES }, engine.margins)
        assertEquals(2, engine.log.count { it == "switch gemma-4-e2b.gguf" })
    }

    @Test
    fun `with a MB less to spare neither loads E2B, and both run on the live model`() = runTest(main) {
        engine.answer = NOTES
        // The file's worth free and 599 MB more, which 0.5.0's bar, the file alone, let through.
        engine.roomBytes = LoadFinishingModelUseCase.E2B_MARGIN_BYTES - 1_048_576L
        val page = page(FinishingModelChoice(E2B))

        rewriteAndFindAgain(page)

        assertEquals(List(2) { LoadFinishingModelUseCase.E2B_MARGIN_BYTES }, engine.margins)
        assertEquals(List(2) { "no room for gemma-4-e2b.gguf" }, engine.log.filter { it.startsWith("no room") })
        assertTrue(engine.log.none { it.startsWith("switch ") })
        // The work itself went on, on the live model: new notes, and the to-dos said outright.
        assertEquals(listOf("Workshop moves next week"), recordings.notesOf("s1").map { it.title })
        assertEquals(2, todos.stored.size)
        assertNull(page.uiState.value.error)
    }

    // ========== Readings (0.8.0) ==========

    // Made up, the same three lines in Malayalam: remind me to call the bank about the loan before the end of the
    // month; remind me to book a table at the harbour restaurant for the team dinner; the workshop moves to the new
    // building next week, and the old shelves go to the basement.
    private val malayalam = listOf(
        "മാസാവസാനത്തിന് മുമ്പ് ലോണിനെ കുറിച്ച് ബാങ്കിൽ വിളിക്കാൻ എന്നെ ഓർമ്മിപ്പിക്കുക.",
        "ടീം ഡിന്നറിനായി ഹാർബർ റെസ്റ്റോറന്റിൽ ഒരു ടേബിൾ ബുക്ക് ചെയ്യാൻ എന്നെ ഓർമ്മിപ്പിക്കുക.",
        "വർക്ക്ഷോപ്പ് അടുത്ത ആഴ്ച പുതിയ കെട്ടിടത്തിലേക്ക് മാറും, പഴയ ഷെൽഫുകൾ ബേസ്മെന്റിലേക്ക് പോകും."
    )

    /** s1 said in Malayalam, the reader reading each line as the English line setUp gives it. */
    private fun saidInMalayalam() {
        val english = recordings.linesOf("s1").map { it.text }
        recordings.said("s1", *malayalam.toTypedArray())
        reader.english = malayalam.zip(english).toMap()
    }

    @Test
    fun `a Malayalam recording's Rewrite notes reads its lines first, before Gemma 4 E2B loads, and Find again finds them read`() = runTest(main) {
        saidInMalayalam()
        engine.answer = NOTES
        val page = page(FinishingModelChoice(E2B))

        rewriteAndFindAgain(page)

        // Read once, by the rewrite, and let go before E2B came in; Find again found the readings stored.
        assertEquals(listOf("reader load", "reader read", "reader read", "reader read", "reader release"), engine.log.filter { it.startsWith("reader") })
        assertTrue(engine.log.toString(), engine.log.indexOf("reader release") < engine.log.indexOf("switch gemma-4-e2b.gguf"))
        assertEquals(reader.english.values.toList(), recordings.readingsIn("s1").map { it.text })
        // The AI read the readings alone; the notes and the to-dos said outright come of them.
        assertTrue(engine.prompts.isNotEmpty())
        assertTrue(engine.prompts.none { prompt -> prompt.any { it in 'ഀ'..'ൿ' } })
        assertEquals(listOf("Workshop moves next week"), recordings.notesOf("s1").map { it.title })
        assertEquals(2, todos.stored.size)
        assertNull(page.uiState.value.error)
    }

    @Test
    fun `without the reader a Malayalam recording's Rewrite notes and Find again say they failed, the model never asked, and what it has stays`() = runTest(main) {
        saidInMalayalam()
        reader.onPhone = false
        engine.answer = NOTES
        val page = page()

        page.rewriteNotes()
        page.uiState.first { it.error == DetailsError.RewriteFailed }
        workEnded()
        page.clearError()
        page.findTodosAgain()
        page.uiState.first { it.error == DetailsError.FindFailed }
        workEnded()

        assertTrue(engine.prompts.isEmpty())
        assertTrue(engine.log.none { it.startsWith("reader") })
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertTrue(todos.stored.isEmpty())
        assertTrue(processing.holders.isEmpty())
    }

    // ========== Change type ==========

    /**
     * The page of s1 recorded as a voice note, the AI's notes in the words of [ModePrompts], once
     * it has looked whether the AI can write notes (on IO).
     */
    private suspend fun TestScope.voiceNotePage(): SessionDetailsViewModel {
        recordings.recorded(voiceNote)
        val page = page(resources = ModePrompts())
        page.refreshNotesAi()
        page.uiState.first { it.notesAi != null }
        return page
    }

    @Test
    fun `Change type stores the category and mode, then writes the notes again with the new mode's prompt`() = runTest(main) {
        engine.answer = MEETING_NOTES
        val page = voiceNotePage()
        val spoken = Todo(id = "todo", sessionId = "s1", text = "Call the bank", status = TodoStatus.OPEN, createdAt = 0, updatedAt = 0)
        todos.upsert(spoken)
        assertEquals(AiOffer.READY, page.uiState.value.changeTypeOffer(transcribing = false))
        assertTrue(page.uiState.value.changeRewritesNotes)

        page.changeType("meeting")
        // The work has the AI before it is waited for: the gate is free until the launched job runs.
        gate.holder.first { it == AiWorkGate.REWRITE }
        workEnded()

        // Stored together, in one write.
        assertEquals(listOf(Triple("s1", "meeting", RecordingMode.SHORT_MEETING)), recordings.categoryWrites)
        val stored = recordings.sessionOf("s1")!!
        assertEquals("meeting", stored.categoryId)
        assertEquals(RecordingMode.SHORT_MEETING, stored.mode)
        // The notes are the meeting's now, from its prompt, with its action items.
        assertTrue(engine.systemPrompts.isNotEmpty())
        assertTrue(engine.systemPrompts.toString(), engine.systemPrompts.all { it == "short_meeting prompt (en)" })
        assertEquals(1, recordings.swaps)
        assertFalse(oldNote in recordings.notesOf("s1"))
        assertTrue(recordings.notesOf("s1").any { it.tasks == "[\"Send the agenda to the team\"]" })
        // The name and the to-dos stay as they were.
        assertEquals("Workshop plans", stored.name)
        assertEquals(listOf(spoken), todos.stored)
        assertEquals(listOf(DetailsNotice.TypeChanged("Meeting")), noticesOf(page))
        assertNull(page.uiState.value.error)
        assertTrue(engine.log.contains("cleanup under ${AiWorkGate.REWRITE}"))
    }

    @Test
    fun `Change type to a type with the same notes only files the recording under it, and the notes stay`() = runTest(main) {
        engine.answer = NOTES
        val page = voiceNotePage()
        assertTrue(page.uiState.value.changeRewritesNotes)
        assertFalse(page.uiState.value.rewritesNotesFor("idea"))
        assertTrue(page.uiState.value.rewritesNotesFor("meeting"))

        page.changeType("idea")
        advanceUntilIdle()

        // Voice note and Idea both record voice notes: stored, and nothing else.
        assertEquals(listOf(Triple("s1", "idea", RecordingMode.SIMPLE_LISTENING)), recordings.categoryWrites)
        assertEquals("idea", recordings.sessionOf("s1")?.categoryId)
        assertTrue(engine.prompts.isEmpty())
        assertTrue(engine.systemPrompts.isEmpty())
        assertEquals(0, recordings.swaps)
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertTrue(engine.log.toString(), engine.log.none { it.contains(AiWorkGate.REWRITE) })
        assertNull(gate.holder.value)
        assertNull(page.uiState.value.rewrite)
        assertEquals(listOf(DetailsNotice.TypeChanged("Idea")), noticesOf(page))
    }

    @Test
    fun `Change type waits while the recording is still being finished, and changes nothing`() = runTest(main) {
        val page = voiceNotePage()
        queue.statuses.value = listOf(FinishingStatus("s1", FinishingStatus.Phase.WAITING, position = 1))
        advanceUntilIdle()

        assertEquals(AiOffer.WAITS, page.uiState.value.changeTypeOffer(transcribing = false))
        page.changeType("meeting")
        advanceUntilIdle()

        assertTrue(recordings.categoryWrites.isEmpty())
        assertTrue(engine.prompts.isEmpty())
        assertEquals(voiceNote, recordings.sessionOf("s1"))
    }

    @Test
    fun `Change type waits while other work holds the AI, and a tap then changes nothing`() = runTest(main) {
        val page = voiceNotePage()
        assertTrue(gate.tryEnter(AiWorkGate.MAP))
        advanceUntilIdle()

        assertEquals(AiOffer.WAITS, page.uiState.value.changeTypeOffer(transcribing = false))
        page.changeType("meeting")
        advanceUntilIdle()

        assertEquals(DetailsError.AiBusy, page.uiState.value.error)
        assertTrue(recordings.categoryWrites.isEmpty())
        assertEquals(RecordingMode.SIMPLE_LISTENING, recordings.sessionOf("s1")?.mode)
        gate.exit(AiWorkGate.MAP)
    }

    @Test
    fun `a recording that starts stops the new notes, the type stays changed and the old notes stay`() = runTest(main) {
        val call = Pause().also { engine.call = it }
        val page = voiceNotePage()

        page.changeType("meeting")
        call.reached.await()
        recorders.expect("new")
        workEnded()

        assertEquals(RecordingMode.SHORT_MEETING, recordings.sessionOf("s1")?.mode)
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertEquals(0, recordings.swaps)
        assertEquals(listOf(DetailsNotice.TypeChanged("Meeting")), noticesOf(page))
        assertEquals(listOf(DetailsNotice.RewriteStopped), noticesOf(page()))
    }

    @Test
    fun `with AI notes off only the type changes`() = runTest(main) {
        settings.settings.value = settings.settings.value.copy(llmEnabled = false)
        val page = voiceNotePage()
        assertEquals(AiOffer.READY, page.uiState.value.changeTypeOffer(transcribing = false))
        assertFalse(page.uiState.value.changeRewritesNotes)

        page.changeType("idea")
        advanceUntilIdle()

        assertEquals("idea", recordings.sessionOf("s1")?.categoryId)
        assertTrue(engine.prompts.isEmpty())
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertEquals(listOf(DetailsNotice.TypeChanged("Idea")), noticesOf(page))
    }

    @Test
    fun `a Translate recording isn't offered Change type, and stays as it is`() = runTest(main) {
        val translation = voiceNote.copy(mode = RecordingMode.REAL_TIME_TRANSLATION, categoryId = null, outputLanguage = "de")
        recordings.recorded(translation)
        val page = page()

        assertEquals(AiOffer.NONE, page.uiState.value.changeTypeOffer(transcribing = false))
        assertTrue(page.uiState.value.typeChoices.isEmpty())
        page.changeType("meeting")
        advanceUntilIdle()

        assertEquals(translation, recordings.sessionOf("s1"))
        assertEquals(DetailsError.ChangeType, page.uiState.value.error)
        assertTrue(recordings.categoryWrites.isEmpty())
    }

    // ========== The language ==========

    @Test
    fun `the language row stores the language alone, the transcript, its model, the notes and the type stay`() = runTest(main) {
        malayalamOnPhone(true)
        val page = voiceNotePage()
        recordings.recorded(voiceNote.copy(transcriptModel = SpeechModelId.PARAKEET_V3.id))
        lookedForMalayalam(page, onPhone = true)
        val told = noticesOf(page)
        assertTrue(page.uiState.value.changesLanguage)

        page.changeLanguage(RecordingLanguage.MALAYALAM)
        advanceUntilIdle()

        assertEquals(listOf(DetailsNotice.LanguageChanged(RecordingLanguage.MALAYALAM)), told)
        val stored = recordings.sessionOf("s1")
        assertEquals(RecordingLanguage.MALAYALAM, stored?.language)
        assertEquals(SpeechModelId.PARAKEET_V3.id, stored?.transcriptModel)
        assertEquals("voice-note", stored?.categoryId)
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertTrue(engine.prompts.isEmpty())
        assertEquals(3, recordings.linesOf("s1").size)
    }

    @Test
    fun `Malayalam needs its model on the phone, and a recording in it can go back to English or German`() = runTest(main) {
        malayalamOnPhone(false)
        val page = voiceNotePage()
        recordings.recorded(voiceNote.copy(language = RecordingLanguage.MALAYALAM))
        lookedForMalayalam(page, onPhone = false)

        page.changeLanguage(RecordingLanguage.ENGLISH_OR_GERMAN)
        advanceUntilIdle()
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.sessionOf("s1")?.language)

        page.changeLanguage(RecordingLanguage.MALAYALAM)
        advanceUntilIdle()
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.sessionOf("s1")?.language)
        assertEquals(DetailsError.ChangeLanguage, page.uiState.value.error)
    }

    @Test
    fun `the language waits while the recording is still being finished, whose finishing reads it`() = runTest(main) {
        malayalamOnPhone(true)
        val page = voiceNotePage()
        queue.statuses.value = listOf(FinishingStatus("s1", FinishingStatus.Phase.WAITING, position = 1))
        advanceUntilIdle()

        assertEquals(AiOffer.WAITS, page.uiState.value.changeTypeOffer(transcribing = false))
        page.changeLanguage(RecordingLanguage.MALAYALAM)
        advanceUntilIdle()

        assertTrue(recordings.languageWrites.isEmpty())
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.sessionOf("s1")?.language)
    }

    @Test
    fun `a Translate recording keeps its language`() = runTest(main) {
        recordings.recorded(voiceNote.copy(mode = RecordingMode.REAL_TIME_TRANSLATION, categoryId = null, outputLanguage = "de"))
        malayalamOnPhone(true)
        val page = page()

        assertFalse(page.uiState.value.changesLanguage)
        page.changeLanguage(RecordingLanguage.MALAYALAM)
        advanceUntilIdle()

        assertTrue(recordings.languageWrites.isEmpty())
    }

    private companion object {
        const val MEETING_NOTES = "{\"title\": \"Workshop moves next week\", \"points\": [\"The workshop moves to the new building.\"], " +
            "\"action_items\": [\"Send the agenda to the team\"]}"
        const val NOTES = "{\"title\": \"Workshop moves next week\", \"points\": [\"The workshop moves to the new building.\"], \"action_items\": []}"
        const val NOTHING_FOUND = "{\"items\": []}"
        const val E2B = "/phone/models/gemma-4-e2b.gguf"
    }
}
