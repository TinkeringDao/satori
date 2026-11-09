package com.satori.presentation.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.satori.data.config.modelConfigForVariant
import com.satori.data.place.RecordingPlaceCapture
import com.satori.data.service.DownloadStateManager
import com.satori.data.speech.InstalledSpeechModels
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingMode
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
import com.satori.presentation.firstrun.FakeFirstRunCoordinator
import com.satori.presentation.firstrun.MicrophoneState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

/**
 * The microphone in the recorder, asked for in place (design 3.7, tests D1 to D3), on the recorder
 * the app runs: a recorder opened without it says so and waits idle, with its recording kept;
 * Android's window answered no once asks again, twice sends to the app's settings page, and the
 * recorder stays; allowed there and back, it gets ready and records the recording it was opened
 * for. And the first run: the first recording is coached, and an AI model on its way is said to
 * come, as the first run's coordinator says; once the coordinator has the model, a recorder that
 * found it missing looks again.
 *
 * As in [MainViewModelRecordAsTest], the recorder runs on a main thread of its own, in real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelMicrophoneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher

    // The recorders each test opened, whose work ends with the test.
    private val opened = mutableListOf<MainViewModel>()

    private val settings = HeldSettings(AppSettings(notesLanguage = NotesLanguage.EN, simpleListeningIntervalSeconds = 0))
    private val gate = AiWorkGate()
    private val engine = NotesEngine("""{"title": "Room booking", "points": ["Book the room for Friday."]}""")
    private val capture = CaptureOnPhone()
    private val service = ServiceOnPhone()
    private val categories = CategoriesOnPhone(*starterCategories.toTypedArray())
    private val voiceNote = TranscriptionSession(
        "s1", name = null, createdAt = 1_000L, lastModifiedAt = 1_000L,
        mode = RecordingMode.SIMPLE_LISTENING, categoryId = "voice-note"
    )

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

    /** The recorder of [voiceNote], as home's Record opens it to record at once, beside [firstRun]. */
    private inner class Recorder(firstRun: FakeFirstRunCoordinator? = null) {
        val recordings = RecordingsOnPhone(voiceNote)
        val active = ActiveRecorder()
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
            finishingQueue = QueueOnPhone(recordings),
            aiWorkGate = gate,
            closeScope = RecorderCloseScope(),
            activeRecorder = active,
            recordingPlace = RecordingPlaceCapture(NoLocation, NoPlaces, recordings) { null },
            categoryRepository = categories,
            changeRecordingType = ChangeRecordingTypeUseCase(recordings, categories),
            changeRecordingLanguage = ChangeRecordingLanguageUseCase(recordings, InstalledSpeechModels(models)),
            savedStateHandle = SavedStateHandle(mapOf("sessionId" to "s1", "autostart" to true)),
            firstRun = firstRun
        ).also { opened += it }

        val state: MainUiState get() = viewModel.uiState.value
    }

    private suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T = withContext(main, block)

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    @Test
    fun `a recorder opened without the microphone says so in place and waits idle, its recording kept`() = runBlocking {
        val recorder = Recorder()

        onMain {
            recorder.viewModel.onScreenStarted()
            recorder.viewModel.onMicrophoneRead(granted = false, refusedBefore = false)
        }

        assertEquals(MicrophoneState.NOT_ALLOWED, recorder.state.microphone)
        assertEquals(RecorderPhase.Blocked, recorder.state.phase)
        // Not a start that waits: the finishing of older recordings and the downloads go on.
        until { recorder.active.current.value?.activity == ActiveRecorder.Activity.IDLE }
        assertFalse(recorder.state.isStartingRecording)
        assertEquals(0, service.starts.get())
        assertNotNull(recorder.recordings.getSession("s1").first())
        assertTrue(recorder.state.exit == null)
    }

    @Test
    fun `refused once it asks again, twice it sends to the settings page, and allowed there it records on its return`() = runBlocking {
        val recorder = Recorder()
        onMain {
            recorder.viewModel.onScreenStarted()
            recorder.viewModel.onMicrophoneRead(granted = false, refusedBefore = false)
        }

        // Android's window is up: the recording it was opened for waits for it.
        onMain { recorder.viewModel.onMicrophoneAsked() }
        assertTrue(recorder.state.isStartingRecording)
        onMain { recorder.viewModel.onMicrophoneAnswered(granted = false, canAskAgain = true) }
        assertEquals(MicrophoneState.REFUSED_ONCE, recorder.state.microphone)
        assertFalse(recorder.state.isStartingRecording)

        onMain {
            recorder.viewModel.onMicrophoneAsked()
            recorder.viewModel.onMicrophoneAnswered(granted = false, canAskAgain = false)
        }
        assertEquals(MicrophoneState.REFUSED_FOR_GOOD, recorder.state.microphone)

        // Back from the app's settings page without allowing it: as it was, and still there.
        onMain { recorder.viewModel.onMicrophoneRead(granted = false, refusedBefore = false) }
        assertEquals(MicrophoneState.REFUSED_FOR_GOOD, recorder.state.microphone)
        assertTrue(recorder.state.exit == null)

        // Allowed there: back, it gets ready and records, with no tap.
        onMain { recorder.viewModel.onMicrophoneRead(granted = true, refusedBefore = false) }
        until { recorder.state.isRecording }
        assertEquals(MicrophoneState.ALLOWED, recorder.state.microphone)
        assertEquals(1, service.starts.get())
    }

    @Test
    fun `allowed in Android's window, the recorder records the recording it was opened for`() = runBlocking {
        val recorder = Recorder()
        onMain {
            recorder.viewModel.onScreenStarted()
            recorder.viewModel.onMicrophoneRead(granted = false, refusedBefore = true)
        }
        assertEquals(MicrophoneState.REFUSED_ONCE, recorder.state.microphone)

        onMain {
            recorder.viewModel.onMicrophoneAsked()
            recorder.viewModel.onMicrophoneAnswered(granted = true, canAskAgain = false)
        }

        until { recorder.state.isRecording }
    }

    @Test
    fun `the first recording is coached and an AI model on its way said to come, as long as the first run says so`() = runBlocking {
        val plan = FirstRunPlan.initial(speechBytes = 1L, malayalamBytes = null, aiModelBytes = 1L).done(FirstRunPart.SPEECH)
        val firstRun = FakeFirstRunCoordinator(FirstRunSnapshot(stage = FirstRunStage.READY, plan = plan))
        val recorder = Recorder(firstRun)

        until { recorder.state.firstRecordingCoach && recorder.state.aiModelPending }

        // The first recording is saved, and the AI model is checked.
        firstRun.snapshot.value = FirstRunSnapshot(stage = FirstRunStage.DONE, plan = plan.done(FirstRunPart.AI_MODEL))
        until { !recorder.state.firstRecordingCoach && !recorder.state.aiModelPending }
    }

    @Test
    fun `once the first run has the AI model, a recorder that found it missing looks again`() = runBlocking {
        val plan = FirstRunPlan.initial(speechBytes = 1L, malayalamBytes = null, aiModelBytes = 1L).done(FirstRunPart.SPEECH)
        val firstRun = FakeFirstRunCoordinator(FirstRunSnapshot(stage = FirstRunStage.DONE, plan = plan))
        val recorder = Recorder(firstRun)
        // Not on the phone yet: the first run downloads it.
        val live = File(tmp.root, "models/${modelConfigForVariant(AppSettings().llmModelVariant).llmSpec.name}")
        val away = File(tmp.root, "downloading.gguf")
        assertTrue(live.renameTo(away))
        onMain { recorder.viewModel.onMicrophoneRead(granted = true, refusedBefore = false) }
        until { !recorder.state.isInitializing && recorder.state.llmStatus == LlmStatus.Missing }
        assertTrue(recorder.state.aiModelComing)

        // The download is checked, and the first run has it.
        assertTrue(away.renameTo(live))
        firstRun.snapshot.value = FirstRunSnapshot(stage = FirstRunStage.DONE, plan = plan.done(FirstRunPart.AI_MODEL))

        until { recorder.state.llmStatus == LlmStatus.Ready }
        assertFalse(recorder.state.aiModelComing)
        assertEquals(null, recorder.state.aiProblem)
    }

    @Test
    fun `a recorder made without a first run coaches nothing and expects no model`() = runBlocking {
        val recorder = Recorder(firstRun = null)
        onMain { recorder.viewModel.onMicrophoneRead(granted = true, refusedBefore = false) }
        until { !recorder.state.isInitializing }

        assertFalse(recorder.state.firstRecordingCoach)
        assertFalse(recorder.state.aiModelPending)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
