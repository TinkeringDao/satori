package com.satori.presentation.mindmap

import androidx.lifecycle.SavedStateHandle
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.usecase.llm.InitializeLlmUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.mindmap.MapOlderRecordingsUseCase
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.FakeProcessing
import com.satori.presentation.main.FinishingModelChoice
import com.satori.presentation.main.HeldSettings
import com.satori.presentation.main.MapOnPhone
import com.satori.presentation.main.NoRecordingService
import com.satori.presentation.main.Pause
import com.satori.presentation.main.ReaderHere
import com.satori.presentation.main.RecordingsOnPhone
import com.satori.presentation.main.ScreenEngine
import com.satori.presentation.main.ScreenPrompts
import com.satori.presentation.main.StoppedWork
import com.satori.presentation.main.liveModelIn
import com.satori.presentation.main.readingsFirst
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The mind map's AI work gives way to a recorder that leaves IDLE (the quick key) at its safe
 * points only: "Map N older recordings" before its next recording, any of it before it begins once
 * the model is in. What is mapped stays, the model is freed also with the recorder open, and the
 * view says so once when it is opened again. Built with the use cases the app runs, over doubles
 * of the engine and the database, and a real [ActiveRecorder] that leaves IDLE mid-run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MindMapViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val main = StandardTestDispatcher()
    private val gate = AiWorkGate()
    private val recorders = ActiveRecorder()
    private val stopped = StoppedWork()
    // Each recording gives no topic: it is read, and no longer waits.
    private val engine = ScreenEngine(gate, answer = "{\"topics\": []}")
    private val processing = FakeProcessing()
    // The reader of the map's work: its loads and releases go in the engine's log, in order.
    private val reader = ReaderHere(log = engine.log)
    private val queue = StatusQueue()
    private val settings = HeldSettings(AppSettings(notesLanguage = NotesLanguage.EN))
    private val waiting = listOf("r1", "r2", "r3")
    private val recordings = RecordingsOnPhone(
        *waiting.map { TranscriptionSession(it, null, createdAt = 1_000L, lastModifiedAt = 1_000L) }.toTypedArray()
    )
    private val map = MapOnPhone(*waiting.toTypedArray())

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        for (id in waiting) recordings.said(id, "The workshop moves to the new building next week, and the shelves go to the basement.")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The top level of the map, as navigation opens it, once it has found that the AI can run. */
    private suspend fun TestScope.topLevel(finishing: FinishingModelRepository = FinishingModelChoice()): MindMapViewModel =
        opened(finishing, SavedStateHandle())

    /** The map of the recording [sessionId], as its page opens it, once it has found that the AI can run. */
    private suspend fun TestScope.recordingMap(sessionId: String): MindMapViewModel =
        opened(FinishingModelChoice(), SavedStateHandle(mapOf(MindMapViewModel.ARG_SESSION to sessionId)))

    private suspend fun TestScope.opened(finishing: FinishingModelRepository, state: SavedStateHandle): MindMapViewModel {
        val resources = ScreenPrompts()
        val language = NotesLanguageResolver(settings, recordings)
        val mapRecording = MapRecordingUseCase(recordings, map, engine, resources, language)
        val summaries = RefreshThemeSummariesUseCase(map, engine, resources, language)
        val view = MindMapViewModel(
            mindMapRepository = map,
            transcriptionRepository = recordings,
            mapOlderRecordings = MapOlderRecordingsUseCase(map, mapRecording, summaries, processing),
            refreshThemeSummaries = summaries,
            mapRecording = mapRecording,
            initializeLlmUseCase = InitializeLlmUseCase(engine, settings),
            loadFinishingModel = LoadFinishingModelUseCase(engine, finishing),
            llmRepository = engine,
            settingsRepository = settings,
            modelDownloadManager = liveModelIn(tmp.root),
            llmProcessingService = processing,
            activeRecorder = recorders,
            recordingService = NoRecordingService(),
            finishingQueue = queue,
            aiGate = gate,
            viewChoice = MindMapViewChoice(),
            stoppedWork = stopped,
            ensureReadings = readingsFirst(recordings, reader, engine),
            savedStateHandle = state
        )
        backgroundScope.launch { view.uiState.collect {} }
        view.refreshAi()
        view.uiState.first { it.ai.available == true }
        return view
    }

    /** What [view] shows once, collected as the screen collects it. */
    private fun TestScope.noticesOf(view: MindMapViewModel): List<MindMapNotice> {
        val told = mutableListOf<MindMapNotice>()
        backgroundScope.launch { view.notices.collect { told += it } }
        // runCurrent, not advanceUntilIdle: that one leaves background work alone once no other is left.
        runCurrent()
        return told
    }

    /** The map's work has ended and let go of the AI. */
    private suspend fun TestScope.workEnded() {
        gate.holder.first { it == null }
        advanceUntilIdle()
        runCurrent()
    }

    @Test
    fun `Map N older recordings gives way to a recording that starts before its next recording, frees the model and the view opened again says so once`() = runTest(main) {
        val call = Pause().also { engine.call = it }
        val view = topLevel()

        view.mapOlder()
        call.reached.await()
        // A quick-key press as the first recording is read: that model call ends, the next doesn't begin.
        recorders.expect("new")
        call.resume()
        workEnded()

        assertEquals(1, engine.prompts.size)
        assertEquals(listOf("r2", "r3"), map.unmapped.value)
        // Freed with the recorder open: no recorder can use the engine while the map holds it.
        assertEquals("cleanup under ${AiWorkGate.MAP}", engine.log.last())
        assertTrue(processing.holders.isEmpty())
        assertTrue(noticesOf(view).isEmpty())
        assertEquals(listOf(MindMapNotice.MappingStopped), noticesOf(topLevel()))
        assertTrue(noticesOf(topLevel()).isEmpty())
    }

    @Test
    fun `a recording that starts while Gemma 4 E2B loads stops the map's work before it begins, and the model goes`() = runTest(main) {
        val load = Pause().also { engine.load = it }
        val view = topLevel(FinishingModelChoice("/phone/models/gemma-4-e2b.gguf"))

        view.mapOlder()
        load.reached.await()
        recorders.expect("new")
        load.resume()
        workEnded()

        assertTrue(engine.prompts.isEmpty())
        assertEquals(waiting, map.unmapped.value)
        assertEquals(listOf("switch gemma-4-e2b.gguf", "cleanup under ${AiWorkGate.MAP}"), engine.log.takeLast(2))
        assertEquals(listOf(MindMapNotice.MappingStopped), noticesOf(topLevel()))
    }

    @Test
    fun `the map's work loads Gemma 4 E2B only with 600 MB to spare beyond its file`() = runTest(main) {
        engine.roomBytes = LoadFinishingModelUseCase.E2B_MARGIN_BYTES
        val view = topLevel(FinishingModelChoice("/phone/models/gemma-4-e2b.gguf"))

        view.mapOlder()
        workEnded()

        assertEquals(listOf(LoadFinishingModelUseCase.E2B_MARGIN_BYTES), engine.margins)
        assertTrue(engine.log.contains("switch gemma-4-e2b.gguf"))
        assertTrue(map.unmapped.value.isEmpty())
    }

    @Test
    fun `with a MB less to spare the map's work never loads E2B, and maps on the live model`() = runTest(main) {
        // The file's worth free and 599 MB more, which 0.5.0's bar, the file alone, let through.
        engine.roomBytes = LoadFinishingModelUseCase.E2B_MARGIN_BYTES - 1_048_576L
        val view = topLevel(FinishingModelChoice("/phone/models/gemma-4-e2b.gguf"))

        view.mapOlder()
        workEnded()

        assertEquals(listOf(LoadFinishingModelUseCase.E2B_MARGIN_BYTES), engine.margins)
        assertTrue(engine.log.contains("no room for gemma-4-e2b.gguf"))
        assertTrue(engine.log.none { it.startsWith("switch ") })
        assertEquals(3, engine.prompts.size)
        assertTrue(map.unmapped.value.isEmpty())
        assertEquals("cleanup under ${AiWorkGate.MAP}", engine.log.last())
    }

    // Made up: the workshop moves to the new building next week, and the shelves go to the basement.
    private val malayalam = "വർക്ക്ഷോപ്പ് അടുത്ത ആഴ്ച പുതിയ കെട്ടിടത്തിലേക്ക് മാറും, ഷെൽഫുകൾ ബേസ്മെന്റിലേക്ക് പോകും."

    @Test
    fun `the map reads a Malayalam recording's lines first, before its model loads, and the AI reads the reading`() = runTest(main) {
        recordings.said("r1", malayalam)
        reader.english = mapOf(malayalam to "The workshop moves to the new building next week, and the shelves go to the basement.")
        val view = topLevel(FinishingModelChoice("/phone/models/gemma-4-e2b.gguf"))

        view.mapOlder()
        workEnded()

        assertEquals(listOf("reader load", "reader read", "reader release"), engine.log.filter { it.startsWith("reader") })
        assertTrue(engine.log.toString(), engine.log.indexOf("reader release") < engine.log.indexOf("switch gemma-4-e2b.gguf"))
        assertEquals(3, engine.prompts.size)
        assertTrue(engine.prompts.none { prompt -> prompt.any { it in 'ഀ'..'ൿ' } })
        assertTrue(map.unmapped.value.isEmpty())
    }

    @Test
    fun `a recording whose readings can't be made now is left for a later run, and without the reader one is mapped from what it has`() = runTest(main) {
        recordings.said("r1", malayalam)
        reader.refusal = ReaderNotLoadedException.Why.SPEECH_MODEL
        val view = topLevel()

        view.mapOlder()
        workEnded()

        // The other two are mapped; r1 still waits, its lines unread, the model never asked of it.
        assertEquals(2, engine.prompts.size)
        assertEquals(listOf("r1"), map.unmapped.value)

        // Without the reader on the phone, a recording of nothing but such lines is read without topics, the model not asked.
        reader.refusal = null
        reader.onPhone = false
        val again = topLevel()
        again.mapOlder()
        workEnded()

        assertEquals(2, engine.prompts.size)
        assertTrue(map.unmapped.value.isEmpty())
    }

    @Test
    fun `a recorder that leaves IDLE while the map reads a recording's lines stops the reader at once, and no model loads after`() = runTest(main) {
        recordings.said("r1", malayalam)
        reader.english = mapOf(malayalam to "The workshop moves to the new building next week, and the shelves go to the basement.")
        val reading = Pause().also { reader.readPause = it }
        val view = topLevel(FinishingModelChoice("/phone/models/gemma-4-e2b.gguf"))

        view.mapOlder()
        reading.reached.await()
        // A quick-key press while the reader reads r1's line; the reading is never let go on.
        recorders.expect("new")
        runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5_000) { reader.released.await() } }
        workEnded()

        assertEquals(listOf("reader load", "reader read", "reader release"), engine.log.filter { it.startsWith("reader") })
        // Gemma 4 E2B never loads beside the capture that starts, nor does the live model.
        assertTrue(engine.log.toString(), engine.log.none { it.startsWith("switch ") })
        assertTrue(engine.prompts.isEmpty())
        assertEquals(waiting, map.unmapped.value)
        assertEquals("cleanup under ${AiWorkGate.MAP}", engine.log.last())
        assertEquals(listOf(MindMapNotice.MappingStopped), noticesOf(topLevel()))
    }

    @Test
    fun `Map this recording stops its reading for a recorder that leaves IDLE, and asks the model nothing`() = runTest(main) {
        recordings.said("r1", malayalam)
        val reading = Pause().also { reader.readPause = it }
        val view = recordingMap("r1")

        view.mapThisRecording()
        reading.reached.await()
        recorders.expect("new")
        runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5_000) { reader.released.await() } }
        workEnded()

        assertTrue(engine.log.toString(), engine.log.none { it.startsWith("switch ") })
        assertTrue(engine.prompts.isEmpty())
        assertTrue("r1" in map.unmapped.value)
        assertEquals(listOf(MindMapNotice.MappingStopped), noticesOf(recordingMap("r1")))
    }

    @Test
    fun `a run no recording interrupts maps every waiting recording, frees the model and tells no stop`() = runTest(main) {
        val view = topLevel()

        view.mapOlder()
        workEnded()

        assertEquals(3, engine.prompts.size)
        assertTrue(map.unmapped.value.isEmpty())
        assertEquals("cleanup under ${AiWorkGate.MAP}", engine.log.last())
        assertTrue(noticesOf(view).none { it == MindMapNotice.MappingStopped })
        assertTrue(noticesOf(topLevel()).isEmpty())
    }
}
