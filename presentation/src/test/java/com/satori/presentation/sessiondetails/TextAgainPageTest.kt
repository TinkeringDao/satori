package com.satori.presentation.sessiondetails

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.satori.domain.language.MalayalamText
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.monitor.DeviceMemory
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.InitializeLlmUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.stt.MakeTextAgainUseCase
import com.satori.domain.usecase.stt.TranscribeAgainUseCase
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
import com.satori.presentation.main.AudioInUse
import com.satori.presentation.main.CategoriesOnPhone
import com.satori.presentation.main.FakeProcessing
import com.satori.presentation.main.FinishingModelChoice
import com.satori.presentation.main.HeldSettings
import com.satori.presentation.main.KeptAudio
import com.satori.presentation.main.NoTimes
import com.satori.presentation.main.Pause
import com.satori.presentation.main.ReaderHere
import com.satori.presentation.main.RecordingsOnPhone
import com.satori.presentation.main.ScreenEngine
import com.satori.presentation.main.ScreenPrompts
import com.satori.presentation.main.SpeechModelsOnPhone
import com.satori.presentation.main.SpeechOnPhone
import com.satori.presentation.main.StoppedWork
import com.satori.presentation.main.TodosOnPhone
import com.satori.presentation.main.liveModelIn
import com.satori.presentation.main.readingsFirst
import com.satori.presentation.main.starterCategories
import com.satori.presentation.sessions.SpeechModelsHere
import com.satori.presentation.sessions.StatusQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.util.Collections

/**
 * Transcribe again that changes the language makes the recording's text again on its page, as the
 * screen wires its two view models (brief item 2, the phone's case of 2026-10-15): a Malayalam note
 * Parakeet had made gibberish of, with the gibberish's name and idea, made again in Malayalam, gets
 * an English name, English notes written without the old name as their topic, and its to-dos and
 * ideas found again in English, the old idea gone and a to-do the owner kept still there. A
 * recorder that starts stops the work as it stops Rewrite notes and Find again. Until a step has
 * made what replaces it, what the old transcript gave the recording stays (the 0.6.1 memory
 * review): a recorder that starts, the AI refused, the page left or a model that doesn't load
 * leave the old name, notes, to-dos and ideas as they were. Built with the use cases the app runs,
 * over doubles of the engine, the speech model and the database. The sentences are made up, of the
 * shape of the owner's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextAgainPageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val main = StandardTestDispatcher()
    private val gate = AiWorkGate()
    private val recorders = ActiveRecorder()
    private val stopped = StoppedWork()
    private val screen = ScreenEngine(gate)
    private val processing = FakeProcessing()
    private val queue = StatusQueue()
    // The owner writes notes in German; a Malayalam recording's are English all the same.
    private val settings = HeldSettings(AppSettings(notesLanguage = NotesLanguage.DE))
    private val todos = TodosOnPhone()
    private val audio = KeptAudio(RecordingAudio("s1", "s1.wav", durationMs = 21_000L))

    // Tomorrow at ten I must go to the bank and hand over the loan papers; before that look over all the papers once more.
    private val bank = "നാളെ രാവിലെ പത്തു മണിക്ക് ബാങ്കിൽ പോയി ലോണിന്റെ രേഖകൾ കൊടുക്കണം, അതിനു മുമ്പ് എല്ലാ പേപ്പറുകളും ഒന്നുകൂടി നോക്കണം."

    // On the way home in the evening don't forget to buy medicine for mother, the pharmacy closes at eight.
    private val medicine = "വൈകുന്നേരം വീട്ടിലേക്ക് വരുമ്പോൾ അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ മറക്കരുത്, ഫാർമസി എട്ടു മണിക്ക് അടയ്ക്കും."

    // The Malayalam model hears the note right.
    private val speech = SpeechOnPhone(listOf(SpokenLine(bank, AudioSpan(0L, 9_000L)), SpokenLine(medicine, AudioSpan(9_500L, 18_000L))))

    private val oldName = "Workflow Kettle Challenges"
    private val recordings = RecordingsOnPhone(TranscriptionSession("s1", oldName, createdAt = 1_000L, lastModifiedAt = 1_000L))
    private val oldNote = LlmInsight(
        "old", "s1", title = "Workflow challenges", content = "- The workflow has problems.\n- The kettle needs a plan.",
        timestamp = 2_000L, sourceSegmentIds = emptyList()
    )

    // What the gibberish gave the note, untouched, and a suggestion of it the owner kept.
    private val oldIdea = Todo(
        "idea", "s1", "No para in the kettle", status = TodoStatus.IDEA, capture = TodoCapture.INFERRED, createdAt = 3_000L, updatedAt = 3_000L
    )
    private val kept = Todo(
        "kept", "s1", "Water the garden", status = TodoStatus.OPEN, capture = TodoCapture.INFERRED, createdAt = 3_000L, updatedAt = 180_000L
    )

    // The reader's English of the two lines, made up as they are: the page reads them before the text is made again.
    private val reader = ReaderHere(
        english = mapOf(
            MalayalamText.clean(bank) to
                "Tomorrow morning at ten I have to go to the bank and hand over the loan papers, and before that look over all the papers once more.",
            MalayalamText.clean(medicine) to "When coming home in the evening, don't forget to buy medicine for mother; the pharmacy closes at eight."
        ),
        log = screen.log
    )

    private val engine = PromptedEngine(
        screen,
        mapOf(
            "notes prompt" to "{\"title\": \"Bank papers and medicine\", \"points\": " +
                "[\"Hand over the loan papers at the bank tomorrow at ten.\", \"Buy medicine for mother on the way home.\"]}",
            "title prompt" to "Bank Papers and Medicine",
            "todo prompt" to """{"items": [""" +
                """{"segment": "S1", "type": "task", "said": "ബാങ്കിൽ പോയി ലോണിന്റെ രേഖകൾ കൊടുക്കണം", "text": "Hand over the loan papers at the bank", "when": "none", "capture": "commitment"}, """ +
                """{"segment": "S2", "type": "task", "said": "അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ മറക്കരുത്", "text": "Buy medicine for mother", "when": "none", "capture": "explicit"}, """ +
                """{"segment": "S2", "type": "task", "said": "മരുന്ന് വാങ്ങാൻ", "text": "$medicine", "when": "none", "capture": "explicit"}""" +
                "]}"
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        recordings.said("s1", "Uh a lay the porch in a key run on the wall here, then the marrow of a cotton.", "Ah no para in the kettle so they can say them all.")
        recordings.noted("s1", oldNote)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The page of recording s1 as navigation opens it: its view model, with the AI's notes looked at. */
    private suspend fun TestScope.details(): SessionDetailsViewModel {
        val models = liveModelIn(tmp.root)
        val resources = ScreenPrompts()
        val load = LoadFinishingModelUseCase(engine, FinishingModelChoice())
        val language = NotesLanguageResolver(settings, recordings)
        val rewriteNotes = RewriteNotesUseCase(recordings, settings, GenerateBatchInsightUseCase(engine, settings, resources), load)
        val extractTodos = ExtractSessionTodosUseCase(
            settings, recordings, todos, ExtractTodosUseCase(engine, resources, NoTimes), CountedReminders(), language
        )
        val categories = CategoriesOnPhone(*starterCategories.toTypedArray())
        val page = SessionDetailsViewModel(
            getSessionDetailsUseCase = GetSessionDetailsUseCase(recordings),
            deleteSessionUseCase = DeleteSessionUseCase(recordings, queue, engine),
            renameSessionUseCase = RenameSessionUseCase(recordings),
            updateSegmentTextUseCase = UpdateSegmentTextUseCase(recordings),
            updateInsightContentUseCase = UpdateInsightContentUseCase(recordings),
            rewriteNotesUseCase = rewriteNotes,
            initializeLlmUseCase = InitializeLlmUseCase(engine, settings),
            loadFinishingModel = load,
            extractSessionTodos = extractTodos,
            llmRepository = engine,
            settingsRepository = settings,
            todoRepository = todos,
            todoActions = TodoActions(todos, CountedReminders()),
            modelDownloadManager = models,
            llmProcessingServiceController = processing,
            finishingQueue = queue,
            aiGate = gate,
            activeRecorder = recorders,
            stoppedWork = stopped,
            categoryRepository = categories,
            changeRecordingType = ChangeRecordingTypeUseCase(recordings, categories),
            changeRecordingLanguage = ChangeRecordingLanguageUseCase(recordings, SpeechModelsHere()),
            makeTextAgainUseCase = MakeTextAgainUseCase(
                recordings,
                rewriteNotes,
                AutoNameSessionUseCase(recordings, GenerateSessionTitleUseCase(engine, resources)),
                extractTodos,
                load,
                language,
                todos,
                TodoActions(todos, CountedReminders())
            ),
            ensureReadings = readingsFirst(recordings, reader, engine),
            savedStateHandle = SavedStateHandle(mapOf("sessionId" to "s1"))
        )
        advanceUntilIdle()
        page.refreshNotesAi()
        page.uiState.first { it.notesAi != null }
        return page
    }

    /** The audio of recording s1 on its page, with Parakeet v3 and the Malayalam model on the phone. */
    private fun TestScope.audioOf(): RecordingAudioViewModel {
        val page = RecordingAudioViewModel(
            audioRepository = audio,
            transcribeAgainUseCase = TranscribeAgainUseCase(speech, recordings, todos, audio),
            transcriptionRepository = recordings,
            speechModels = SpeechModelsOnPhone(SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML),
            llmRepository = engine,
            memory = DeviceMemory { 8L * 1024 * 1024 * 1024 },
            player = RecordingPlayer(ContextWrapper(null)),
            processingService = processing,
            aiGate = gate,
            finishingQueue = queue,
            audioUsage = AudioInUse(),
            activeRecorder = recorders,
            stoppedWork = stopped,
            savedStateHandle = SavedStateHandle(mapOf("sessionId" to "s1"))
        )
        advanceUntilIdle()
        return page
    }

    /**
     * Transcribe again in Malayalam from the page's menu, as the screen runs it: the screen passes
     * whether the page makes the text again, and starts that on the notice that asks for it.
     */
    private suspend fun TestScope.transcribeInMalayalam(details: SessionDetailsViewModel, audio: RecordingAudioViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            audio.notices.collect { if (it == AudioNotice.TextToMakeAgain && details.uiState.value.makesTextAgain) details.makeTextAgain() }
        }
        audio.transcribeAgain(SpeechModelId.INDICCONFORMER_ML, makesTextAgain = details.uiState.value.makesTextAgain)
    }

    /** What [page] shows once, collected as the screen collects it. */
    private fun TestScope.noticesOf(page: SessionDetailsViewModel): List<DetailsNotice> {
        val told = mutableListOf<DetailsNotice>()
        backgroundScope.launch { page.notices.collect { told += it } }
        runCurrent()
        return told
    }

    /**
     * The page's work, which holds the AI, has ended and let go of it, on whichever thread its
     * parts ran; called while it holds it (a test holds it at a pause until a recorder stops it).
     */
    private suspend fun TestScope.workEnded() {
        gate.holder.first { it == null }
        advanceUntilIdle()
        runCurrent()
    }

    @Test
    fun `a gibberish name and idea give way to an English name, notes and to-dos of the Malayalam transcript`() = runTest(main) {
        todos.upsert(oldIdea)
        todos.upsert(kept)
        val details = details()
        val told = mutableListOf<DetailsNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { details.notices.collect { told += it } }

        transcribeInMalayalam(details, audioOf())
        // Its last store: the to-dos and ideas found again, beside the one kept, the old idea gone with it.
        todos.observeForSession("s1").first { found -> found.size == 3 && found.none { it.id == oldIdea.id } }
        workEnded()

        val session = recordings.sessionOf("s1")
        assertEquals(RecordingLanguage.MALAYALAM, session?.language)
        // The name is written again, in English.
        assertEquals("Bank Papers and Medicine", session?.name)
        // The notes, in English, without the old name as their topic.
        assertEquals(listOf("Bank papers and medicine"), recordings.notesOf("s1").map { it.title })
        val notesCall = engine.asked.single { it.first == "notes prompt" }.second
        assertFalse(notesCall, notesCall.contains(oldName))
        assertFalse(notesCall, notesCall.contains("The topic is"))
        // Since 0.8.0 the page reads the new lines first, before the model, and the AI reads their readings
        // with an English recording's prompts: no line on reading Malayalam, and no Malayalam (D6, D7).
        assertTrue(notesCall, notesCall.contains(reader.english.getValue(MalayalamText.clean(bank))))
        assertTrue(engine.asked.none { (_, text) -> text.contains("read the Malayalam") || text.any { it in 'ഀ'..'ൿ' } })
        assertEquals(listOf("reader load", "reader read", "reader read", "reader release"), screen.log.filter { it.startsWith("reader") })
        assertEquals(2, recordings.readingsIn("s1").size)
        // The old idea is gone, the kept to-do stays, the new ones are English; the copied Malayalam line is no item.
        assertEquals(
            listOf("Water the garden", "Hand over the loan papers at the bank", "Buy medicine for mother"),
            todos.stored.map { it.text }
        )
        assertEquals(listOf(DetailsNotice.Found(TodosFound(todos = 2, ideas = 0))), told)
        assertNull(details.uiState.value.error)
        // The model was freed under the page's hold of the AI, and nothing holds the service.
        assertTrue(screen.log.toString(), screen.log.contains("cleanup under ${AiWorkGate.REWRITE}"))
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `without the reader the text made again of the Malayalam transcript says it failed, and the old name, notes and idea stay`() = runTest(main) {
        todos.upsert(oldIdea)
        reader.onPhone = false
        val details = details()

        transcribeInMalayalam(details, audioOf())
        details.uiState.first { it.error == DetailsError.RewriteFailed }
        workEnded()

        // The new transcript is in; nothing of the AI's comes from it without its readings (D7).
        assertEquals(RecordingLanguage.MALAYALAM, recordings.sessionOf("s1")?.language)
        assertEquals(oldName, recordings.sessionOf("s1")?.name)
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertEquals(listOf(oldIdea), todos.stored)
        assertTrue(engine.asked.isEmpty())
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `a recording that starts during the search keeps the new notes and name and the old idea, and the page opened again says the search stopped`() = runTest(main) {
        todos.upsert(oldIdea)
        val search = Pause().also { engine.pauses["todo prompt"] = it }
        val details = details()

        transcribeInMalayalam(details, audioOf())
        search.reached.await()
        assertTrue(details.uiState.value.todoSearch != null)
        recorders.expect("new")
        workEnded()

        assertEquals("Bank Papers and Medicine", recordings.sessionOf("s1")?.name)
        assertEquals(listOf("Bank papers and medicine"), recordings.notesOf("s1").map { it.title })
        // Nothing was found in its place, so the old idea stays.
        assertEquals(listOf(oldIdea), todos.stored)
        assertTrue(noticesOf(details).isEmpty())
        assertEquals(listOf(DetailsNotice.SearchStopped), noticesOf(details()))
    }

    @Test
    fun `a recording that starts while the notes are written keeps the old name, notes and idea, and the page opened again says so`() = runTest(main) {
        todos.upsert(oldIdea)
        val notes = Pause().also { engine.pauses["notes prompt"] = it }
        val details = details()

        transcribeInMalayalam(details, audioOf())
        notes.reached.await()
        assertTrue(details.uiState.value.rewrite != null)
        recorders.expect("new")
        workEnded()

        // The transcript is the new one; nothing was made in place of the rest, which stays.
        assertEquals(RecordingLanguage.MALAYALAM, recordings.sessionOf("s1")?.language)
        // As a line is stored: its nta spelt the way the standard recommends ([MalayalamText]).
        assertEquals(listOf(bank, medicine).map(MalayalamText::clean), recordings.linesOf("s1").map { it.text })
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertEquals(oldName, recordings.sessionOf("s1")?.name)
        assertEquals(listOf(oldIdea), todos.stored)
        assertEquals(listOf(DetailsNotice.RewriteStopped), noticesOf(details()))
    }

    @Test
    fun `a recording that starts while the name is written keeps the new notes, and the old name and idea`() = runTest(main) {
        todos.upsert(oldIdea)
        val title = Pause().also { engine.pauses["title prompt"] = it }
        val details = details()

        transcribeInMalayalam(details, audioOf())
        title.reached.await()
        recorders.expect("new")
        workEnded()

        assertEquals(listOf("Bank papers and medicine"), recordings.notesOf("s1").map { it.title })
        assertEquals(oldName, recordings.sessionOf("s1")?.name)
        assertEquals(listOf(oldIdea), todos.stored)
        assertEquals(listOf(DetailsNotice.SearchStopped), noticesOf(details()))
    }

    @Test
    fun `the text refused as the AI is taken leaves the old name, notes and idea beside the new transcript`() = runTest(main) {
        todos.upsert(oldIdea)
        val details = details()
        val audio = audioOf()
        // A recording that waits to be finished takes the AI as the transcript is in.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            audio.notices.collect {
                if (it == AudioNotice.TextToMakeAgain) {
                    assertTrue(gate.tryEnter(AiWorkGate.FINISHER))
                    details.makeTextAgain()
                }
            }
        }

        audio.transcribeAgain(SpeechModelId.INDICCONFORMER_ML, makesTextAgain = details.uiState.value.makesTextAgain)
        details.uiState.first { it.error == DetailsError.AiBusy }
        advanceUntilIdle()

        assertEquals(RecordingLanguage.MALAYALAM, recordings.sessionOf("s1")?.language)
        // As a line is stored: its nta spelt the way the standard recommends ([MalayalamText]).
        assertEquals(listOf(bank, medicine).map(MalayalamText::clean), recordings.linesOf("s1").map { it.text })
        assertEquals(oldName, recordings.sessionOf("s1")?.name)
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertEquals(listOf(oldIdea), todos.stored)
        assertTrue(engine.asked.isEmpty())
        gate.exit(AiWorkGate.FINISHER)
    }

    @Test
    fun `the page left while the notes are written leaves the old name, notes and idea`() = runTest(main) {
        todos.upsert(oldIdea)
        val notes = Pause().also { engine.pauses["notes prompt"] = it }
        val details = details()

        transcribeInMalayalam(details, audioOf())
        notes.reached.await()
        // Back: the page's view model is cleared, which ends its work.
        details.viewModelScope.cancel()
        workEnded()

        assertEquals(oldName, recordings.sessionOf("s1")?.name)
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertEquals(listOf(oldIdea), todos.stored)
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `a model that doesn't load leaves the old name, notes and idea`() = runTest(main) {
        todos.upsert(oldIdea)
        engine.loads = false
        val details = details()

        transcribeInMalayalam(details, audioOf())
        details.uiState.first { it.error != null }
        workEnded()

        assertEquals(DetailsError.RewriteFailed, details.uiState.value.error)
        assertEquals(oldName, recordings.sessionOf("s1")?.name)
        assertEquals(listOf(oldNote), recordings.notesOf("s1"))
        assertEquals(listOf(oldIdea), todos.stored)
        assertTrue(engine.asked.isEmpty())
    }

    @Test
    fun `the page makes the text again only where AI notes are on with a model, and never for a Translate recording`() {
        val ready = SessionDetailsUiState(
            sessionDetails = SessionWithDetails(recordings.sessionOf("s1")!!, emptyList(), emptyList()),
            notesAi = NotesAi.Ready
        )

        assertTrue(ready.makesTextAgain)
        assertFalse(ready.copy(notesAi = NotesAi.TurnedOff).makesTextAgain)
        assertFalse(ready.copy(notesAi = NotesAi.NoModel).makesTextAgain)
        assertFalse(ready.copy(notesAi = null).makesTextAgain)
        assertFalse(SessionDetailsUiState(notesAi = NotesAi.Ready).makesTextAgain)
        val translation = ready.sessionDetails!!.let { it.copy(session = it.session.copy(mode = RecordingMode.REAL_TIME_TRANSLATION)) }
        assertFalse(ready.copy(sessionDetails = translation).makesTextAgain)
    }

    /**
     * [screen]'s engine (the gate, what is registered and freed), answering each kind of call by
     * its system prompt ([ScreenPrompts]: the notes', the name's, the to-dos'), and keeping what it
     * was asked ([asked], system prompt and text). A call waits at the pause set for its prompt
     * first, if one is set. With [loads] false no model can be put in memory, as when the paced
     * load stops one for want of memory.
     */
    private class PromptedEngine(private val screen: ScreenEngine, private val answers: Map<String, String>) : LlmRepository by screen {
        val asked: MutableList<Pair<String?, String>> = Collections.synchronizedList(mutableListOf())
        val pauses: MutableMap<String, Pause> = Collections.synchronizedMap(mutableMapOf())

        @Volatile
        var loads = true

        override suspend fun reloadModel(): Result<Unit> =
            if (loads) screen.reloadModel() else Result.failure(java.io.IOException("The load of the live model was stopped: MemFree stayed under 150 MB for 10 s"))

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
            asked += systemPrompt to text
            systemPrompt?.let { pauses[it] }?.here()
            emit(answers[systemPrompt].orEmpty())
        }

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?, lead: String?): Flow<String> =
            generateInsight(if (lead.isNullOrBlank()) text else "$lead\n\n$text", systemPrompt, maxTokens, grammar)
    }
}
