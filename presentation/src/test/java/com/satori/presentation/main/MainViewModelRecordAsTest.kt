package com.satori.presentation.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.satori.data.place.RecordingPlaceCapture
import com.satori.data.service.DownloadStateManager
import com.satori.data.speech.InstalledSpeechModels
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import com.satori.domain.usecase.llm.InitializeLlmUseCase
import com.satori.domain.usecase.stt.StopSttStreamingUseCase
import com.satori.domain.usecase.sync.SyncSttLlmUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingLanguageUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingTypeUseCase
import com.satori.domain.usecase.transcription.SaveInsightUseCase
import com.satori.domain.usecase.transcription.SaveSegmentUseCase
import com.satori.domain.usecase.transcription.UpdateSessionDurationUseCase
import com.satori.presentation.ai.AiWorkGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

/**
 * A recording switched to another category while it records ("Record as"), on the recorder the app
 * runs, over doubles of the microphone, the speech model, the engine and the database: the category
 * and the mode are stored together, the live notes go on in the new mode from their next interval,
 * a note being written finishes as it began, the notice says what it is recorded as, the finisher
 * is handed it as switched (also by a recorder Android clears just after the pick), and the capture
 * is never touched. A translation can't be switched.
 *
 * The recorder runs on a main thread of its own, in real time: its timer ticks once a second for
 * as long as it records, which virtual time would run for ever. Each test ends its recorders'
 * work before it lets go of that thread: left running, a timer would tick on in the main
 * dispatcher of the tests after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelRecordAsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher

    // The recorders each test opened, whose work ends with the test.
    private val opened = mutableListOf<MainViewModel>()

    // A voice note and a meeting's answer: a voice note reads its points, a meeting its action items too.
    private val answer = """{"title": "Room booking", "points": ["Book the room for Friday."], "action_items": ["Send the agenda to the team"]}"""

    private val settings = HeldSettings(
        AppSettings(notesLanguage = NotesLanguage.EN, simpleListeningIntervalSeconds = 0, shortMeetingIntervalSeconds = 0)
    )
    private val gate = AiWorkGate()
    private val engine = NotesEngine(
        answer,
        later = listOf("""{"title": "Agenda", "points": ["The agenda goes out today."], "action_items": ["Send the agenda to the team"]}""")
    )
    private val capture = CaptureOnPhone()
    private val service = ServiceOnPhone()
    private val categories = CategoriesOnPhone(*starterCategories.toTypedArray(), translatingCategory)
    private val voiceNote = TranscriptionSession(
        "s1", name = null, createdAt = 1_000L, lastModifiedAt = 1_000L,
        mode = RecordingMode.SIMPLE_LISTENING, categoryId = "voice-note"
    )

    private val first = line(1, "We still have to book the room for the Friday review")
    private val second = line(2, "and the agenda goes out to the whole team today")

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { task -> Thread(task, "recorder-main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        runBlocking { opened.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        main.close()
    }

    private fun line(at: Long, text: String) =
        TranscriptionSegment(id = "line$at", sessionId = "s1", text = text, timestamp = at * 1_000L, isComplete = true)

    /** The recorder of [recording], as navigation opens it, with what it works with. */
    private inner class Recorder(recording: TranscriptionSession) {
        val recordings = RecordingsOnPhone(recording)
        val queue = QueueOnPhone(recordings)
        val models = modelsOnPhone(tmp.root)
        val viewModel = MainViewModel(
            syncSttLlmUseCase = SyncSttLlmUseCase(engine, settings, ModePrompts()),
            notesLanguage = NotesLanguageResolver(settings, recordings),
            stopSttStreamingUseCase = StopSttStreamingUseCase(capture),
            initializeLlmUseCase = InitializeLlmUseCase(engine, settings),
            saveSegmentUseCase = SaveSegmentUseCase(recordings),
            saveInsightUseCase = SaveInsightUseCase(recordings),
            updateSessionDurationUseCase = UpdateSessionDurationUseCase(recordings),
            discardSilentRecording = DiscardSilentRecordingUseCase(recordings, NoAudioKept),
            settingsRepository = settings,
            sttRepository = capture,
            llmRepository = engine,
            transcriptionRepository = recordings,
            modelDownloadManager = models,
            downloadStateManager = DownloadStateManager(),
            serviceController = service,
            finishingQueue = queue,
            aiWorkGate = gate,
            closeScope = RecorderCloseScope(),
            activeRecorder = ActiveRecorder(),
            recordingPlace = RecordingPlaceCapture(NoLocation, NoPlaces, recordings) { null },
            categoryRepository = categories,
            changeRecordingType = ChangeRecordingTypeUseCase(recordings, categories),
            changeRecordingLanguage = ChangeRecordingLanguageUseCase(recordings, InstalledSpeechModels(models)),
            savedStateHandle = SavedStateHandle(mapOf("sessionId" to "s1"))
        ).also { opened += it }

        /** Gets ready and starts recording, as a tap on the grille does, once both readers of the capture are on it. */
        suspend fun record() {
            onMain { viewModel.initialize() }
            until { !viewModel.uiState.value.isInitializing }
            onMain {
                viewModel.onScreenStarted()
                viewModel.startStreaming()
            }
            until { viewModel.uiState.value.isRecording && capture.reads.get() == 1 }
            // The live notes' reader joins the capture as the transcript's does.
            delay(SETTLE_MS)
        }

        /** The speech model gives out [segment], and its line and the note it starts are stored. */
        suspend fun say(segment: TranscriptionSegment, notesAfter: Int) {
            capture.say(segment)
            until { recordings.linesOf("s1").any { it.id == segment.id } && recordings.notesOf("s1").size == notesAfter }
            // The note gives its turn back.
            until { gate.holder.value == null }
        }

        suspend fun recordAs(categoryId: String) = onMain { viewModel.recordAs(categoryId) }

        suspend fun notice(): RecorderNotice = withTimeout(TIMEOUT_MS) { viewModel.notices.first() }
    }

    private suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T = withContext(main, block)

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    @Test
    fun `a voice note switched to a meeting stores both, notes on as a meeting and says so`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        recorder.say(first, notesAfter = 1)

        recorder.recordAs("meeting")

        assertEquals(RecorderNotice.RecordingAs("Meeting"), recorder.notice())
        // Stored together, in one write.
        assertEquals(listOf(Triple("s1", "meeting", RecordingMode.SHORT_MEETING)), recorder.recordings.categoryWrites)
        val stored = recorder.recordings.sessionOf("s1")
        assertEquals("meeting", stored?.categoryId)
        assertEquals(RecordingMode.SHORT_MEETING, stored?.mode)
        val state = recorder.viewModel.uiState.value
        assertEquals("Meeting", state.recordingAs?.name)
        assertEquals(RecordingMode.SHORT_MEETING, state.sessionMode)

        recorder.say(second, notesAfter = 2)

        // The note before the switch stays a voice note's; the next one is a meeting's.
        assertEquals(listOf("simple_listening prompt (en)", "short_meeting prompt (en)"), engine.calls.map { it.systemPrompt })
        assertEquals(listOf(512, 600), engine.calls.map { it.maxTokens })
        assertEquals(listOf(false, true), engine.calls.map { it.actionItems })
        val notes = recorder.recordings.notesOf("s1")
        assertNull(notes[0].tasks)
        assertEquals("[\"Send the agenda to the team\"]", notes[1].tasks)
    }

    @Test
    fun `the switch touches neither the microphone nor the speech model, and every line is kept`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        recorder.say(first, notesAfter = 1)

        recorder.recordAs("meeting")
        recorder.notice()
        recorder.say(second, notesAfter = 2)

        // One capture, read once from its start: nothing restarted it, nor the speech model.
        assertEquals(1, capture.starts.get())
        assertEquals(1, capture.reads.get())
        assertEquals(0, capture.stops.get())
        assertEquals(0, capture.releases.get())
        assertEquals(1, service.starts.get())
        assertEquals(0, service.stops.get())
        assertTrue(recorder.viewModel.uiState.value.isRecording)
        assertEquals(listOf(first.id, second.id), recorder.recordings.linesOf("s1").map { it.id })
        // Each note cites the lines it read, once.
        assertEquals(listOf(listOf(first.id), listOf(second.id)), recorder.recordings.notesOf("s1").map { it.sourceSegmentIds })
    }

    @Test
    fun `a note being written as the switch comes finishes as a voice note's, the next is a meeting's`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        val writing = Pause().also { engine.hold = it }
        capture.say(first)
        writing.reached.await()

        recorder.recordAs("meeting")
        assertEquals(RecorderNotice.RecordingAs("Meeting"), recorder.notice())
        writing.resume()
        until { recorder.recordings.notesOf("s1").size == 1 && gate.holder.value == null }
        recorder.say(second, notesAfter = 2)

        assertEquals(listOf("simple_listening prompt (en)", "short_meeting prompt (en)"), engine.calls.map { it.systemPrompt })
        assertEquals(listOf(false, true), engine.calls.map { it.actionItems })
        assertNull(recorder.recordings.notesOf("s1")[0].tasks)
        assertEquals(listOf(listOf(first.id), listOf(second.id)), recorder.recordings.notesOf("s1").map { it.sourceSegmentIds })
    }

    @Test
    fun `Stop right after a switch hands the recording over once it is stored, as a meeting`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        recorder.say(first, notesAfter = 1)
        val storing = Pause().also { recorder.recordings.categoryStore = it }

        recorder.recordAs("meeting")
        storing.reached.await()
        onMain { recorder.viewModel.stopStreaming() }
        delay(SETTLE_MS)

        // The hand-off waits for the switch, so the finisher reads the meeting's mode.
        assertTrue(recorder.queue.handedOff.isEmpty())
        storing.resume()
        until { recorder.viewModel.uiState.value.exit == RecorderExit.HOME }

        assertEquals(listOf("s1" to RecordingMode.SHORT_MEETING), recorder.queue.handedOff)
        assertEquals(1, capture.stops.get())
    }

    @Test
    fun `a recorder Android clears right after a switch hands the recording over once it is stored, as a meeting`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        recorder.say(first, notesAfter = 1)
        val storing = Pause().also { recorder.recordings.categoryStore = it }
        // The recorder as Android holds it, in a store it clears when the task goes.
        val store = ViewModelStore()
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = recorder.viewModel as T
        })[MainViewModel::class.java]

        recorder.recordAs("meeting")
        storing.reached.await()
        // onCleared runs on the main thread, and holds it while it hands the recording over.
        val cleared = launch(main) { store.clear() }
        delay(SETTLE_MS)

        assertTrue(recorder.queue.handedOff.isEmpty())
        storing.resume()
        withTimeout(TIMEOUT_MS) { cleared.join() }

        assertEquals(listOf("s1" to RecordingMode.SHORT_MEETING), recorder.queue.handedOff)
        assertEquals("meeting", recorder.recordings.sessionOf("s1")?.categoryId)
    }

    @Test
    fun `Translate is not offered, and a translation stays one`() = runBlocking {
        val voiceNoteRecorder = Recorder(voiceNote)
        voiceNoteRecorder.record()
        assertEquals(listOf("voice-note", "meeting", "idea", "todo"), voiceNoteRecorder.viewModel.uiState.value.typeChoices.map { it.id })

        voiceNoteRecorder.recordAs("trip")

        assertEquals(RecorderNotice.SwitchFailed, voiceNoteRecorder.notice())
        assertTrue(voiceNoteRecorder.recordings.categoryWrites.isEmpty())
        assertEquals(RecordingMode.SIMPLE_LISTENING, voiceNoteRecorder.viewModel.uiState.value.sessionMode)
    }

    @Test
    fun `a translation being recorded can't be switched`() = runBlocking {
        settings.settings.value = settings.settings.value.copy(translationIntervalSeconds = 0)
        val translation = voiceNote.copy(mode = RecordingMode.REAL_TIME_TRANSLATION, categoryId = null, outputLanguage = "de")
        val recorder = Recorder(translation)
        recorder.record()

        val state = recorder.viewModel.uiState.value
        assertTrue(state.isRecording)
        assertTrue(state.typeChoices.isEmpty())
        assertTrue(!state.canChangeType)

        recorder.recordAs("meeting")
        delay(SETTLE_MS)

        assertTrue(recorder.recordings.categoryWrites.isEmpty())
        assertEquals(RecordingMode.REAL_TIME_TRANSLATION, recorder.recordings.sessionOf("s1")?.mode)
    }

    @Test
    fun `a category deleted before the switch is stored changes nothing, and the notice says so`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        recorder.say(first, notesAfter = 1)
        categories.delete("meeting")

        recorder.recordAs("meeting")

        assertEquals(RecorderNotice.SwitchFailed, recorder.notice())
        assertEquals(voiceNote.mode, recorder.recordings.sessionOf("s1")?.mode)
        assertEquals("voice-note", recorder.recordings.sessionOf("s1")?.categoryId)
        recorder.say(second, notesAfter = 2)
        assertEquals(listOf("simple_listening prompt (en)", "simple_listening prompt (en)"), engine.calls.map { it.systemPrompt })
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L
    }
}
