package com.satori.presentation.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.satori.data.place.RecordingPlaceCapture
import com.satori.data.service.DownloadStateManager
import com.satori.data.speech.InstalledSpeechModels
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

/**
 * A recording switched to another language while it records ("Record as", its language row), on
 * the recorder the app runs, over doubles of the microphone, the speech model, the engine and the
 * database: the language is stored with its live model as the transcript's, the capture reads the
 * new language between utterances (its model changes there) and is never restarted, the live notes
 * stop while it is in Malayalam and go on once it is back, a model that didn't load takes the switch
 * back, the hand-off waits for the store, Malayalam is offered only with its model on the phone, and
 * a translation keeps its language. The recorder's language key, whose tap records in the other
 * language, switches to Malayalam and back, flips back with a model that didn't load, and is there
 * only while the recording is recorded. The Malayalam lines are made up, as the English ones.
 *
 * As in [MainViewModelRecordAsTest], the recorder runs on a main thread of its own, in real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelRecordInTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var main: ExecutorCoroutineDispatcher

    // The recorders each test opened, whose work ends with the test.
    private val opened = mutableListOf<MainViewModel>()

    private val answer = """{"title": "Room booking", "points": ["Book the room for Friday."]}"""

    private val settings = HeldSettings(
        AppSettings(notesLanguage = NotesLanguage.EN, simpleListeningIntervalSeconds = 0, shortMeetingIntervalSeconds = 0)
    )
    private val gate = AiWorkGate()
    private val engine = NotesEngine(answer, later = listOf("""{"title": "Agenda", "points": ["The agenda goes out today."]}"""))
    private val capture = CaptureOnPhone()
    private val service = ServiceOnPhone()
    private val categories = CategoriesOnPhone(*starterCategories.toTypedArray(), translatingCategory)
    private val voiceNote = TranscriptionSession(
        "s1", name = null, createdAt = 1_000L, lastModifiedAt = 1_000L,
        mode = RecordingMode.SIMPLE_LISTENING, categoryId = "voice-note", transcriptModel = SpeechModelId.PARAKEET_V3.id
    )
    private val malayalamNote = voiceNote.copy(language = RecordingLanguage.MALAYALAM, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id)

    private val english = line(1, "We still have to book the room for the Friday review")
    private val malayalam = line(2, "വെള്ളിയാഴ്ചത്തേക്ക് മുറി ബുക്ക് ചെയ്യണം, അജണ്ട ഇന്ന് ടീമിന് അയക്കണം")
    private val englishAgain = line(3, "and the agenda goes out to the whole team today")

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

    /** The recorder of [recording], as navigation opens it, with the Malayalam model on the phone or not. */
    private inner class Recorder(recording: TranscriptionSession, malayalamModel: Boolean = true) {
        val recordings = RecordingsOnPhone(recording)
        val queue = QueueOnPhone(recordings)
        val models = modelsOnPhone(tmp.root).also { if (malayalamModel) malayalamModelIn(it) else noMalayalamModelIn(it) }
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

        /** The speech model gives out [segment], and its line and the notes it leads to are stored. */
        suspend fun say(segment: TranscriptionSegment, notesAfter: Int) {
            capture.say(segment)
            until { recordings.linesOf("s1").any { it.id == segment.id } && recordings.notesOf("s1").size == notesAfter }
            // A note gives its turn back.
            until { gate.holder.value == null }
        }

        suspend fun recordIn(language: RecordingLanguage) = onMain { viewModel.recordIn(language) }

        suspend fun notice(): RecorderNotice = withTimeout(TIMEOUT_MS) { viewModel.notices.first() }
    }

    private suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T = withContext(main, block)

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    @Test
    fun `a recording switched to Malayalam stores it with its model, the capture follows, and live notes stop`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, capture.language?.current)
        recorder.say(english, notesAfter = 1)

        recorder.recordIn(RecordingLanguage.MALAYALAM)

        assertEquals(RecorderNotice.RecordingIn(RecordingLanguage.MALAYALAM), recorder.notice())
        assertEquals(listOf("s1" to RecordingLanguage.MALAYALAM), recorder.recordings.languageWrites)
        assertEquals(RecordingLanguage.MALAYALAM, settings.settings.value.homeLanguage)
        val stored = recorder.recordings.sessionOf("s1")
        assertEquals(RecordingLanguage.MALAYALAM, stored?.language)
        assertEquals(SpeechModelId.INDICCONFORMER_ML.id, stored?.transcriptModel)
        // Its category and mode stay.
        assertEquals("voice-note", stored?.categoryId)
        assertEquals(RecordingMode.SIMPLE_LISTENING, stored?.mode)
        // The capture reads the new language as the next utterance begins: its model changes there.
        assertEquals(RecordingLanguage.MALAYALAM, capture.language?.current)
        val state = recorder.viewModel.uiState.value
        assertEquals(RecordingLanguage.MALAYALAM, state.language)
        assertTrue(state.liveNotesOff)

        // No live note while it is in Malayalam, not even of the English line the old model still finishes.
        recorder.say(malayalam, notesAfter = 1)
        recorder.say(englishAgain, notesAfter = 1)
        delay(SETTLE_MS)
        assertEquals(1, recorder.recordings.notesOf("s1").size)
        assertEquals(1, engine.calls.size)
        // One capture, read once from its start: nothing restarted it, nor the speech model's release.
        assertEquals(1, capture.starts.get())
        assertEquals(1, capture.reads.get())
        assertEquals(0, capture.stops.get())
        assertEquals(0, capture.releases.get())
        assertEquals(1, service.starts.get())
        assertEquals(0, service.stops.get())
        assertEquals(listOf(english.id, malayalam.id, englishAgain.id), recorder.recordings.linesOf("s1").map { it.id })
    }

    @Test
    fun `switched back to English or German, the live notes go on with what is said in it`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        recorder.say(english, notesAfter = 1)
        recorder.recordIn(RecordingLanguage.MALAYALAM)
        recorder.notice()
        recorder.say(malayalam, notesAfter = 1)

        recorder.recordIn(RecordingLanguage.ENGLISH_OR_GERMAN)

        assertEquals(RecorderNotice.RecordingIn(RecordingLanguage.ENGLISH_OR_GERMAN), recorder.notice())
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recorder.recordings.sessionOf("s1")?.language)
        assertEquals(SpeechModelId.PARAKEET_V3.id, recorder.recordings.sessionOf("s1")?.transcriptModel)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, capture.language?.current)
        recorder.say(englishAgain, notesAfter = 2)
        // The Malayalam line is no live note's: the notes after Stop read it.
        assertEquals(listOf(listOf(english.id), listOf(englishAgain.id)), recorder.recordings.notesOf("s1").map { it.sourceSegmentIds })
    }

    @Test
    fun `Malayalam is offered only once its model is on the phone`() = runBlocking {
        val recorder = Recorder(voiceNote, malayalamModel = false)
        recorder.record()
        delay(SETTLE_MS)
        assertFalse(recorder.viewModel.uiState.value.malayalamOnPhone)
        // No other language to record in: the sheet has no language row.
        assertFalse(recorder.viewModel.uiState.value.canChangeLanguage)

        recorder.recordIn(RecordingLanguage.MALAYALAM)
        delay(SETTLE_MS)

        assertTrue(recorder.recordings.languageWrites.isEmpty())
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, capture.language?.current)

        // Downloaded in Settings meanwhile: the recorder looks again as it comes back.
        malayalamModelIn(recorder.models)
        onMain { recorder.viewModel.onScreenStarted() }
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        recorder.recordIn(RecordingLanguage.MALAYALAM)

        assertEquals(RecorderNotice.RecordingIn(RecordingLanguage.MALAYALAM), recorder.notice())
        assertEquals(RecordingLanguage.MALAYALAM, capture.language?.current)
    }

    @Test
    fun `a recording made in Malayalam starts with its model and writes no live note`() = runBlocking {
        val recorder = Recorder(malayalamNote)
        recorder.record()

        assertEquals(RecordingLanguage.MALAYALAM, capture.language?.current)
        assertTrue(recorder.viewModel.uiState.value.liveNotesOff)
        recorder.say(malayalam, notesAfter = 0)
        delay(SETTLE_MS)

        assertTrue(recorder.recordings.notesOf("s1").isEmpty())
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a recording made in Malayalam whose model is gone can't start, and says the speech model is missing`() = runBlocking {
        val recorder = Recorder(malayalamNote, malayalamModel = false)

        onMain { recorder.viewModel.initialize() }
        until { !recorder.viewModel.uiState.value.isInitializing || recorder.viewModel.uiState.value.error != null }

        assertEquals(RecorderError.SpeechModelMissing, recorder.viewModel.uiState.value.error)
        assertEquals(RecorderPhase.Blocked, recorder.viewModel.uiState.value.phase)
        assertEquals(0, capture.starts.get())
    }

    @Test
    fun `a Malayalam model that didn't load takes the switch back, stored and said`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        recorder.recordIn(RecordingLanguage.MALAYALAM)
        recorder.notice()

        // The capture couldn't load it between two utterances, and goes on with Parakeet v3.
        checkNotNull(capture.language).refused(RecordingLanguage.MALAYALAM, RecordingLanguage.ENGLISH_OR_GERMAN)

        assertEquals(
            RecorderNotice.LanguageRefused(RecordingLanguage.MALAYALAM, RecordingLanguage.ENGLISH_OR_GERMAN),
            recorder.notice()
        )
        until { recorder.recordings.languageWrites.size == 2 }
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recorder.recordings.sessionOf("s1")?.language)
        assertEquals(SpeechModelId.PARAKEET_V3.id, recorder.recordings.sessionOf("s1")?.transcriptModel)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recorder.viewModel.uiState.value.language)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, capture.language?.current)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, settings.settings.value.homeLanguage)
        assertFalse(recorder.viewModel.uiState.value.liveNotesOff)
    }

    @Test
    fun `Stop right after a switch of the language hands the recording over once it is stored`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        recorder.say(english, notesAfter = 1)
        val storing = Pause().also { recorder.recordings.languageStore = it }

        recorder.recordIn(RecordingLanguage.MALAYALAM)
        storing.reached.await()
        onMain { recorder.viewModel.stopStreaming() }
        delay(SETTLE_MS)

        // The hand-off waits for the switch, so the finisher reads the recording as Malayalam.
        assertTrue(recorder.queue.handedOff.isEmpty())
        storing.resume()
        until { recorder.viewModel.uiState.value.exit == RecorderExit.HOME }

        assertEquals(1, recorder.queue.handedOff.size)
        assertEquals(RecordingLanguage.MALAYALAM, recorder.recordings.sessionOf("s1")?.language)
    }

    @Test
    fun `a switch of the language made right after one of the category is stored after it`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        val storingType = Pause().also { recorder.recordings.categoryStore = it }

        onMain { recorder.viewModel.recordAs("meeting") }
        storingType.reached.await()
        recorder.recordIn(RecordingLanguage.MALAYALAM)
        delay(SETTLE_MS)

        // The language waits while the category is being stored.
        assertTrue(recorder.recordings.languageWrites.isEmpty())
        storingType.resume()
        until { recorder.recordings.languageWrites.size == 1 }
        assertEquals(listOf(Triple("s1", "meeting", RecordingMode.SHORT_MEETING)), recorder.recordings.categoryWrites)
        val stored = recorder.recordings.sessionOf("s1")
        assertEquals("meeting", stored?.categoryId)
        assertEquals(RecordingLanguage.MALAYALAM, stored?.language)
    }

    @Test
    fun `a language taken back is stored after a switch made before it`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        recorder.recordIn(RecordingLanguage.MALAYALAM)
        recorder.notice()
        val storingType = Pause().also { recorder.recordings.categoryStore = it }
        onMain { recorder.viewModel.recordAs("meeting") }
        storingType.reached.await()

        // The Malayalam model didn't load while the category was being stored.
        checkNotNull(capture.language).refused(RecordingLanguage.MALAYALAM, RecordingLanguage.ENGLISH_OR_GERMAN)
        recorder.notice()
        delay(SETTLE_MS)

        assertEquals(listOf("s1" to RecordingLanguage.MALAYALAM), recorder.recordings.languageWrites.toList())
        storingType.resume()
        until { recorder.recordings.languageWrites.size == 2 }
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recorder.recordings.languageWrites.last().second)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recorder.recordings.sessionOf("s1")?.language)
        assertEquals("meeting", recorder.recordings.sessionOf("s1")?.categoryId)
    }

    @Test
    fun `the language key's tap, the other language, switches to Malayalam and back, as the sheet's row does`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        assertTrue(recorder.viewModel.uiState.value.canChangeLanguage)

        recorder.recordIn(checkNotNull(recorder.viewModel.uiState.value.language).other)

        assertEquals(RecorderNotice.RecordingIn(RecordingLanguage.MALAYALAM), recorder.notice())
        assertEquals(RecordingLanguage.MALAYALAM, recorder.viewModel.uiState.value.language)
        assertEquals(RecordingLanguage.MALAYALAM, capture.language?.current)
        // The key stays to switch back.
        assertTrue(recorder.viewModel.uiState.value.canChangeLanguage)

        recorder.recordIn(checkNotNull(recorder.viewModel.uiState.value.language).other)

        assertEquals(RecorderNotice.RecordingIn(RecordingLanguage.ENGLISH_OR_GERMAN), recorder.notice())
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recorder.viewModel.uiState.value.language)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, capture.language?.current)
        assertEquals(
            listOf(RecordingLanguage.MALAYALAM, RecordingLanguage.ENGLISH_OR_GERMAN),
            recorder.recordings.languageWrites.map { it.second }
        )
    }

    @Test
    fun `a Malayalam model that didn't load flips the key back, and it stays to try again`() = runBlocking {
        val recorder = Recorder(voiceNote)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        recorder.recordIn(RecordingLanguage.ENGLISH_OR_GERMAN.other)
        recorder.notice()

        checkNotNull(capture.language).refused(RecordingLanguage.MALAYALAM, RecordingLanguage.ENGLISH_OR_GERMAN)
        recorder.notice()

        // The key reads the language: English or German again.
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recorder.viewModel.uiState.value.language)
        assertTrue(recorder.viewModel.uiState.value.canChangeLanguage)
        until { recorder.recordings.languageWrites.size == 2 }

        recorder.recordIn(checkNotNull(recorder.viewModel.uiState.value.language).other)
        assertEquals(RecorderNotice.RecordingIn(RecordingLanguage.MALAYALAM), recorder.notice())
    }

    @Test
    fun `the key is there only while the recording is recorded`() = runBlocking {
        val recorder = Recorder(voiceNote)
        assertFalse(recorder.viewModel.uiState.value.canChangeLanguage)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }
        assertTrue(recorder.viewModel.uiState.value.canChangeLanguage)

        onMain { recorder.viewModel.stopStreaming() }
        until { recorder.viewModel.uiState.value.exit == RecorderExit.HOME }

        assertFalse(recorder.viewModel.uiState.value.canChangeLanguage)
        // A tap that comes as it stops changes nothing.
        recorder.recordIn(RecordingLanguage.ENGLISH_OR_GERMAN.other)
        delay(SETTLE_MS)
        assertTrue(recorder.recordings.languageWrites.isEmpty())
    }

    @Test
    fun `a translation being recorded keeps its language`() = runBlocking {
        settings.settings.value = settings.settings.value.copy(translationIntervalSeconds = 0)
        val translation = voiceNote.copy(mode = RecordingMode.REAL_TIME_TRANSLATION, categoryId = null, outputLanguage = "de")
        val recorder = Recorder(translation)
        recorder.record()
        until { recorder.viewModel.uiState.value.malayalamOnPhone }

        assertFalse(recorder.viewModel.uiState.value.canChangeLanguage)
        assertFalse(recorder.viewModel.uiState.value.opensRecordAs)
        recorder.recordIn(RecordingLanguage.MALAYALAM)
        delay(SETTLE_MS)

        assertTrue(recorder.recordings.languageWrites.isEmpty())
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, capture.language?.current)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L
    }
}
