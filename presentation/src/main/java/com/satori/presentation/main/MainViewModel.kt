package com.satori.presentation.main

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.datasource.ModelFileInspector
import com.satori.data.place.RecordingPlaceCapture
import com.satori.data.service.DownloadStateManager
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.DownloadState
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmModelChoice
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SpeechLeftForAfterStopException
import com.satori.domain.repository.SpeechModelLoadException
import com.satori.domain.repository.SttRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.RecordingServiceController
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import com.satori.domain.usecase.llm.InitializeLlmUseCase
import com.satori.domain.usecase.stt.StopSttStreamingUseCase
import com.satori.domain.usecase.sync.LiveNoteTurn
import com.satori.domain.usecase.sync.SyncSttLlmUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingLanguageUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingTypeUseCase
import com.satori.domain.usecase.transcription.SaveInsightUseCase
import com.satori.domain.usecase.transcription.SaveSegmentUseCase
import com.satori.domain.usecase.transcription.UpdateSessionDurationUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.firstrun.MicrophoneState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the main screen.
 *
 * Coordinates STT streaming, LLM inference, UI state management, foreground service,
 * and persistent storage of transcription sessions.
 *
 * Each recording session is isolated - LLM context is reset when entering this screen
 * to ensure clean separation between different transcription sessions.
 *
 * Stop saves the last words and hands the recording to the finisher ([FinishingQueue]), which
 * writes its last notes, its name and its to-dos and links it into the mind map in the
 * background, one recording at a time; the recorder goes home within about a second, and the
 * next recording can start at once ([HandOffPlan]). A recording in which nothing was said is
 * deleted instead ([RecorderExit.NOTHING_SAID]), also when the recorder closes without Stop
 * ([leave], [onCleared]), and a recorder that never recorded deletes its empty recording, so a
 * failed or cancelled start leaves nothing in the list ([DiscardSilentRecordingUseCase]). What the
 * recorder knows of speech its lines may lack is kept in [RecordingEnd].
 *
 * The AI engine is shared with the finisher and the app's other AI work ([AiWorkGate]): the live
 * model is registered, loaded and freed only while the engine is free, and each live note takes
 * its turn without waiting ([LiveNoteTurn]); [LiveModel] holds the recorder's turns. While an older
 * recording is being finished, no live note is written ([MainUiState.notesWaiting]); the first
 * note after that reads what was said meanwhile, and the Notes step after Stop reads what no note
 * covered.
 *
 * While it records, the label at the top switches the recording to another category ("Record
 * as", [recordAs]): a voice note that turns out to be a meeting is finished as one. Its live notes
 * follow the new mode from their next interval; the capture is not touched. The same sheet switches
 * its language ([recordIn]): the capture changes its speech model between two utterances, while the
 * microphone records on, and a recording in Malayalam gets no live notes (its notes are written after
 * Stop).
 *
 * The microphone is asked for in place ([onMicrophoneRead], [onMicrophoneAnswered]): a refusal
 * neither closes the recorder nor deletes its recording, and a return from the app's settings page
 * reads the permission again. The first recording of a fresh install is coached
 * ([MainUiState.showsCoach]), and while the first run downloads the AI model the notes are said to
 * follow once it is in ([MainUiState.aiModelComing]).
 *
 * @property syncSttLlmUseCase Use case for synchronized STT-LLM processing
 * @property notesLanguage Chooses the language of the live notes
 * @property stopSttStreamingUseCase Use case to stop STT
 * @property initializeLlmUseCase Use case to initialize LLM
 * @property saveSegmentUseCase Use case to persist transcription segments
 * @property saveInsightUseCase Use case to persist LLM insights
 * @property discardSilentRecording Removes the recording when nothing was said in it
 * @property settingsRepository Repository for app settings
 * @property sttRepository Direct access to STT for transcription segments
 * @property llmRepository Direct access to LLM for context reset
 * @property transcriptionRepository Repository for accessing session details
 * @property serviceController Controller for recording foreground service
 * @property finishingQueue Finishes the recording after Stop, in the background
 * @property aiWorkGate Who may use the AI engine now
 * @property closeScope Where the live model is freed as the recorder closes, and where a switch to
 *   another category is stored, which the hand-off as it closes can wait for
 * @property recordingPlace Reads where the recording starts, beside it ("Save the place"), and
 *   stops for a recording deleted because nothing was said in it
 * @property categoryRepository The recording categories, for the label at the top and its sheet
 * @property changeRecordingType Records the recording as another category ("Record as")
 * @property changeRecordingLanguage Records the recording in another language ("Record as")
 * @property savedStateHandle Navigation arguments (contains sessionId)
 * @property firstRun The first run: whether the recording ahead is the first, and whether the AI
 *   model is on its way. Null for a recorder made without one, which coaches nothing and expects no
 *   model
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val syncSttLlmUseCase: SyncSttLlmUseCase,
    private val notesLanguage: NotesLanguageResolver,
    private val stopSttStreamingUseCase: StopSttStreamingUseCase,
    private val initializeLlmUseCase: InitializeLlmUseCase,
    private val saveSegmentUseCase: SaveSegmentUseCase,
    private val saveInsightUseCase: SaveInsightUseCase,
    private val updateSessionDurationUseCase: UpdateSessionDurationUseCase,
    private val discardSilentRecording: DiscardSilentRecordingUseCase,
    private val settingsRepository: SettingsRepository,
    private val sttRepository: SttRepository,
    private val llmRepository: LlmRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val modelDownloadManager: ModelDownloadManager,
    private val downloadStateManager: DownloadStateManager,
    private val serviceController: RecordingServiceController,
    private val finishingQueue: FinishingQueue,
    private val aiWorkGate: AiWorkGate,
    closeScope: RecorderCloseScope,
    private val activeRecorder: ActiveRecorder,
    private val recordingPlace: RecordingPlaceCapture,
    private val categoryRepository: CategoryRepository,
    private val changeRecordingType: ChangeRecordingTypeUseCase,
    private val changeRecordingLanguage: ChangeRecordingLanguageUseCase,
    private val savedStateHandle: SavedStateHandle,
    private val firstRun: FirstRunCoordinator? = null
) : ViewModel() {

    /** The recording this recorder is for (passed via navigation). */
    val sessionId: String = checkNotNull(savedStateHandle["sessionId"]) {
        "Session ID is required for recording"
    }

    // One-tap recording from home, or a tap while the models load: start once initialize() has
    // finished and the screen is visible. Cleared in SavedStateHandle once used, so process
    // recreation doesn't start again. Mirrored in MainUiState.isStartingRecording.
    private var autostartPending: Boolean = savedStateHandle.get<Boolean>("autostart") == true

    private val _uiState = MutableStateFlow(MainUiState(isStartingRecording = autostartPending))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /**
     * Live input level (0..1). Kept out of [uiState] so ~10 updates per second only
     * recompose the level display, not the whole screen.
     */
    val audioLevel: StateFlow<Float> = sttRepository.audioLevel

    private val _notices = Channel<RecorderNotice>(Channel.BUFFERED)

    /** For the screen to show once: what a switch to another category came to ([recordAs]). */
    val notices: Flow<RecorderNotice> = _notices.receiveAsFlow()

    // Whether the screen is at least STARTED. Android refuses to start the recording service
    // while the app is in the background, so a pending start waits until the user is back.
    private var screenStarted = false

    private var initJob: Job? = null
    // Loads the session's mode, language, strategy, topic and length. initialize() waits for
    // it, so a start never runs on the defaults.
    private val sessionConfigJob: Job
    private var transcriptionJob: Job? = null
    private var insightsJob: Job? = null
    private var settingsWatcherJob: Job? = null
    private var timerJob: Job? = null
    // Stores the job of the capture's recording; the hand-off waits for it, so it can't be
    // overwritten by a write that comes late.
    private var captureStartedJob: Job? = null
    // Counts starts, so an error from an earlier stream, or a teardown that runs late, never
    // ends a newer recording.
    private var recordingAttempt = 0
    // True from startService() until stopService(); onCleared() uses it to never leave the
    // recording service (and its wake lock) running.
    private var recordingServiceRunning = false
    // The registration of the transcription flow the service observes, which its stop names.
    private var serviceRegistration = 0L
    // For MainUiState.listening: how far the capture has come, and whether it gave out a line.
    private var captureState = CaptureState.IDLE
    private var lineHeard = false
    // What this recorder knows of speech the recording's lines may lack, and whether the hand-off
    // or the recorder closing decides if the recording is kept.
    private val end = RecordingEnd()
    // Whether part of the audio may never have been transcribed: the finisher is told at the hand-off.
    private val gaps = TranscriptGaps(end)
    // Whether the download of an AI model is being watched (once per ViewModel).
    private var watchingModelDownloads = false
    // Whether the microphone permission was read since the recorder opened: the first read gets it ready.
    private var microphoneRead = false
    // Cumulative duration of all completed recording segments within this session.
    // Seeded from the stored length and updated on each stop, so the next start can offset
    // the timer correctly.
    private var accumulatedDurationMs: Long = 0L
    private var isInitialized = false // Set once initialize() has finished
    // The recording's mode: what it started in, then what "Record as" switched it to. The live
    // notes read it from their next interval on (SyncSttLlmUseCase's modeChanges).
    private val recordingMode = MutableStateFlow(RecordingMode.SHORT_MEETING)
    private var currentRecordingMode: RecordingMode
        get() = recordingMode.value
        set(value) {
            recordingMode.value = value
        }
    // The recording's language: what it started in, then what "Record as" switched it to. The
    // capture reads it as it loads its speech model and between utterances (captureLanguage), and
    // the live notes from their next interval.
    private val recordingLanguage = MutableStateFlow(RecordingLanguage.ENGLISH_OR_GERMAN)
    // The switch to another category or language being stored, if any; the hand-off waits for it,
    // so the finisher reads what it was switched to. It runs in switchScope.
    private var switchJob: Job? = null
    // When the current stretch of recording began (elapsedRealtime), for the position of a switch.
    private var stretchStartedAtMs = 0L
    private var currentOutputLanguage: String? = null
    private var currentInsightStrategy: InsightStrategy = InsightStrategy.REAL_TIME
    private var currentTopic: String? = null
    // The live model's file, once prepareLlm has found it usable; null while there is none.
    @Volatile private var liveModelPath: String? = null
    // The recorder's turns at the AI engine, and the release of the live model as it closes.
    private val liveModel = LiveModel(aiWorkGate, freeModel = ::freeLiveModel, closeScope = closeScope.scope)
    // Where a switch to another category is stored ([recordAs]): off the main thread and beyond this
    // view model, so the hand-off as the recorder closes can wait for it.
    private val switchScope = closeScope.scope
    // The turn of the live note being written, if any.
    private var noteTurn: LiveModel.Turn? = null

    // The capture's shared stream, retained so insightsJob can be restarted mid-session
    private var activeCapture: SharedFlow<CaptureEvent>? = null

    // Translation target language at the moment the insights job was launched
    private var activeTranslationTarget: String? = null

    /**
     * A live note's turn at the engine ([AiWorkGate.LIVE_NOTE]). It never waits, and refuses while
     * no live model is known or AI notes are off. Within the turn the live model is registered
     * again before every note: whoever had the engine since may have switched or forgotten it,
     * and a short hold of the gate can come and go between two looks at its holder. A note still
     * being written as the recorder closes frees the model as it gives the turn back ([LiveModel]).
     */
    private val liveNoteTurn = object : LiveNoteTurn {
        override fun tryTake(): Boolean {
            if (liveModelPath == null || !_uiState.value.settings.llmEnabled) return false
            noteTurn = liveModel.tryTake() ?: return false
            return true
        }

        override suspend fun prepare() {
            val path = liveModelPath ?: error("No live model to write the note with")
            initializeLlmUseCase(path, loadImmediately = false).getOrThrow()
        }

        override suspend fun release() {
            val turn = noteTurn
            noteTurn = null
            turn?.giveBack()
        }
    }

    /**
     * The recording's language as the capture reads it ([CaptureLanguage]): as it loads its speech
     * model, and between utterances, where a switch ([recordIn]) has it change the model. A model
     * that didn't load after a switch is told here, from the capture's thread: the recording goes on
     * in the language before ([takeBackLanguage]).
     */
    private val captureLanguage = object : CaptureLanguage {
        override val current: RecordingLanguage
            get() = recordingLanguage.value

        override fun refused(wanted: RecordingLanguage, instead: RecordingLanguage) {
            viewModelScope.launch { takeBackLanguage(wanted, instead) }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"

        // How often the length is saved while recording, so a recording that ends without
        // Stop (swiped away, killed by Android, a crash) keeps nearly all of it.
        private const val DURATION_SAVE_INTERVAL_MS = 20_000L

        // How long Stop waits for the capture's last words. Normally they take 0.2-0.5 s; a Stop
        // while the speech model still loads waits for the load and the decode of what the
        // microphone heard meanwhile.
        private const val DRAIN_GUARD_MS = 15_000L

        // How long a hand-off waits for a switch to another category being stored: a single
        // UPDATE, which takes milliseconds; onCleared's hand-off holds the main thread meanwhile.
        private const val SWITCH_WAIT_MS = 2_000L

        /**
         * The field log's line for a switch to another category ([recordAs]): the ids, the mode
         * and where in the recording it came, never the category's name, which the owner typed.
         */
        internal fun switchLine(sessionId: String, category: RecordingCategory, atMs: Long): String =
            "Recording $sessionId switched to ${category.id} (${category.mode}) at $atMs ms"

        /**
         * The field log's line for a switch to another language ([recordIn]): the language and its
         * live model, and where in the recording it came.
         */
        internal fun languageLine(sessionId: String, language: RecordingLanguage, atMs: Long): String =
            "Recording $sessionId switched to $language (${language.liveModel.id}) at $atMs ms"
    }

    init {
        // Tell the quick key and the finisher what this recorder does (see ActiveRecorder). It
        // opens here, as it is created, so that a recorder opened after it counts as newer.
        activeRecorder.open(this, sessionId, ActiveRecorder.activityOf(_uiState.value))
        viewModelScope.launch {
            _uiState.map { ActiveRecorder.activityOf(it) to (it.sessionMode == RecordingMode.REAL_TIME_TRANSLATION) }
                .distinctUntilChanged()
                .collect { (activity, translation) ->
                    activeRecorder.report(this@MainViewModel, sessionId, activity, translation)
                }
        }

        // Load settings. A recorder that found AI notes switched off starts them as soon as the
        // user switches them on, without leaving the recording. initialize() does that itself
        // for a switch made while it runs.
        viewModelScope.launch {
            settingsRepository.getSettings().collect { settings ->
                _uiState.update { it.copy(settings = settings) }
                if (isInitialized) prepareLlmIfDue()
            }
        }

        // Load session recording mode, language, strategy, topic and length
        sessionConfigJob = viewModelScope.launch {
            transcriptionRepository.getSession(sessionId)
                .first()
                ?.let { session ->
                    currentRecordingMode = session.mode
                    recordingLanguage.value = session.language
                    currentOutputLanguage = session.outputLanguage
                    currentInsightStrategy = session.insightStrategy
                    currentTopic = session.topic
                    // A recorder restored after process death continues the stored length,
                    // instead of restarting at 0 and overwriting it on the next stop.
                    accumulatedDurationMs = session.durationMs
                    // A recording that has a length or lines already was recorded by a recorder that
                    // Android ended with the app: no recorder saw its last capture end.
                    end.opened(session.durationMs, hasLines = transcriptionRepository.getSegmentsBySession(sessionId).first().isNotEmpty())
                    _uiState.update {
                        it.copy(
                            insightStrategy = session.insightStrategy,
                            sessionMode = session.mode,
                            language = session.language,
                            translationTarget = session.outputLanguage.takeIf { session.mode == RecordingMode.REAL_TIME_TRANSLATION },
                            recordingDurationMillis = session.durationMs
                        )
                    }
                    Log.i(TAG, "Session config: mode=$currentRecordingMode, language=${session.language}, output=$currentOutputLanguage, strategy=$currentInsightStrategy, hasTopic=${currentTopic != null}, durationMs=${session.durationMs}")
                }
        }

        // The name and the category can change while the screen is open: an unnamed recording is
        // named when it stops, and "Record as" files it under another category.
        viewModelScope.launch {
            transcriptionRepository.getSession(sessionId).collect { session ->
                if (session != null) {
                    _uiState.update { it.copy(sessionName = session.name, sessionCategoryId = session.categoryId) }
                }
            }
        }

        // The categories, for the label at the top and the ones the recording can be switched to.
        // Without them the label stays a label: recording goes on all the same.
        viewModelScope.launch {
            categoryRepository.observeAll()
                .catch { e -> Log.e(TAG, "Failed to load the categories", e) }
                .collect { categories -> _uiState.update { it.copy(categories = categories) } }
        }

        // Load existing segments and insights from database
        viewModelScope.launch {
            transcriptionRepository.getSegmentsBySession(sessionId).collect { segments ->
                _uiState.update { it.copy(completedSegments = segments) }
                Log.d(TAG, "Loaded ${segments.size} segments from database")
            }
        }

        viewModelScope.launch {
            transcriptionRepository.getInsightsBySession(sessionId).collect { insights ->
                _uiState.update { it.copy(insights = insights) }
                Log.d(TAG, "Loaded ${insights.size} insights from database")
            }
        }

        // The Stop button in the recording notification runs the same stop as the screen, so
        // the last words and the length are still saved and the recording is finished.
        viewModelScope.launch {
            serviceController.stopRequests.collect { stopStreaming() }
        }

        // A speech model downloaded again while this recorder is on show (the user came back
        // from Settings early) is picked up at once; otherwise when the screen starts again. So is
        // the Malayalam model, which "Record as" then offers.
        viewModelScope.launch {
            downloadStateManager.sttDownloadState.collect { state ->
                if (state is DownloadState.Completed) {
                    lookForMalayalam()
                    if (screenStarted) lookAgainForSpeechModel()
                }
            }
        }

        // While an older recording is being finished, no live note is written. Once anyone but a
        // live note frees the engine, the live model is registered again: the finisher forgets
        // it as it is done, and other work may have put another model in its place.
        viewModelScope.launch {
            var before: String? = null
            aiWorkGate.holder.collect { holder ->
                val waiting = holder == AiWorkGate.FINISHER
                if (waiting != (before == AiWorkGate.FINISHER) && _uiState.value.isRecording) {
                    Log.i(TAG, if (waiting) "Live notes wait for the finisher" else "Live notes go on")
                }
                _uiState.update { it.copy(notesWaiting = waiting) }
                if (LiveModel.registersAgain(before, holder)) registerLiveModel()
                before = holder
            }
        }

        // "Listening" until the speech model is in: the capture opens the microphone first.
        viewModelScope.launch {
            sttRepository.captureState.collect { state ->
                captureState = state
                updateListening()
            }
        }

        // A quick-key start that waited for this recorder replaces it. Once none is under way,
        // a recorder still left waiting for one (its start was given up) goes home itself; after
        // a start that did replace it, the screen is no longer on top and nothing happens.
        viewModelScope.launch {
            activeRecorder.startInFlight.collect { inFlight ->
                if (!inFlight) _uiState.update { it.afterStartGivenUp() }
            }
        }

        // The first run: no recording saved yet coaches this one, and an AI model on its way has
        // the notes said to follow once it is in. Once it no longer waits for the model, a model
        // found missing is looked for again, as after a download of it from Settings.
        firstRun?.let { coordinator ->
            viewModelScope.launch {
                coordinator.state.map { it.firstRecordingAhead to it.aiModelPending }
                    .distinctUntilChanged()
                    .collect { (ahead, pending) ->
                        val waited = _uiState.value.aiModelPending
                        _uiState.update { it.copy(firstRecordingCoach = ahead, aiModelPending = pending) }
                        if (waited && !pending) lookAgainForAiModel()
                    }
            }
        }
    }

    /**
     * The first run no longer waits for the AI model: it is in, most likely. A recorder that found
     * none looks again ([prepareLlm]), so it doesn't say the model is missing beside one on the
     * phone; while recording the model is only registered. Not before [initialize] has looked
     * itself, nor once Stop was pressed.
     */
    private fun lookAgainForAiModel() {
        val state = _uiState.value
        if (!isInitialized || !state.settings.llmEnabled || state.llmStatus != LlmStatus.Missing || state.isFinalizingSession) return
        viewModelScope.launch { prepareLlm(state.settings, mayLoadNow = !state.isRecording) }
    }

    /**
     * Checks the speech model's files, and finds the AI model, for this recording session.
     *
     * A speech model that is missing or damaged is reported in [MainUiState.error] and never
     * handed to the native loader. It is not loaded here: the capture loads it once the
     * microphone is open, so the first words are kept while it loads. The AI model is only
     * registered here when the session needs it later; if it is missing, off or damaged,
     * [MainUiState.llmStatus] says so. Otherwise it goes on loading after this has finished (see
     * [prepareLlm]).
     *
     * Runs once per ViewModel, once the microphone is allowed ([onMicrophoneRead]); a call after
     * that does nothing.
     */
    fun initialize() {
        if (initJob != null) {
            Log.i(TAG, "Already initialized or initializing, skipping")
            return
        }

        Log.i(TAG, "Initializing recording session: $sessionId")

        // Observe LLM download state for user-triggered downloads
        if (!watchingModelDownloads) {
            watchingModelDownloads = true
            observeLlmDownloadState()
        }

        initJob = viewModelScope.launch {
            // A recorder Android restored after ending the app can be for a recording that is
            // gone: one in which nothing was said, stopped from the notification or the power key
            // while the app was in the background and deleted then, before the screen could go
            // back home. It goes back now, as it would have then, and loads no model for it.
            val session = transcriptionRepository.getSession(sessionId).first()
            if (session == null) {
                Log.i(TAG, "Recording $sessionId is gone, leaving the recorder")
                setStartPending(false)
                _uiState.update { it.copy(isInitializing = false, exit = RecorderExit.NOTHING_SAID) }
                return@launch
            }

            // Await the first DataStore emission to guarantee we have the persisted settings
            // (llmModelVariant, llmEnabled, etc.) rather than the in-memory default that
            // the init-block collector may not have applied yet.
            val settings = settingsRepository.getSettings().first()
            _uiState.update { it.copy(settings = settings) }

            // The speech model of the recording's language: Malayalam has its own.
            if (checkSpeechModel(session.language.liveModel)) {
                // Read again: AI notes can be switched on or off while the files are checked.
                // This only registers the AI model; its load runs in the background.
                prepareLlm(settingsRepository.getSettings().first(), mayLoadNow = true)
            } else {
                // A one-tap start would fail as well, and replace this reason with a vaguer one.
                setStartPending(false)
            }

            // A start needs the session's strategy, language and stored length.
            sessionConfigJob.join()

            // The settings collector only acts once this has finished. AI notes switched on since
            // the model was looked for get it here, while the record button is still off; the
            // loop ends with no suspension before the flag below, so no switch slips through.
            while (_uiState.value.isAiPreparationDue) {
                prepareLlm(_uiState.value.settings, mayLoadNow = true)
            }

            // Mark as initialized (prevents re-initialization)
            isInitialized = true
            _uiState.update { it.copy(isInitializing = false) }
            Log.i(TAG, "Initialization complete")

            startIfPending()
        }
    }

    /**
     * Checks the files of the speech model [model], the recording's language's. On failure
     * [MainUiState.error] says why. A model that is fine on disk and still fails to load does so in
     * the capture, which ends with [SpeechModelLoadException] then.
     *
     * @return true if the files are fine
     */
    private suspend fun checkSpeechModel(model: SpeechModelId): Boolean {
        when (withContext(Dispatchers.IO) { modelDownloadManager.checkSttModel(model) }) {
            ModelFileState.Missing -> {
                Log.w(TAG, "Speech model ${model.id} not on the phone")
                fail(RecorderError.SpeechModelMissing)
                return false
            }
            ModelFileState.Damaged -> {
                Log.e(TAG, "Speech model files are damaged or incomplete, not loading them")
                fail(RecorderError.SpeechModelDamaged)
                return false
            }
            ModelFileState.Unreadable -> {
                // Not known to be damaged: trying again may find the files readable.
                Log.e(TAG, "Speech model files could not be read, not loading them")
                fail(RecorderError.SpeechModelFailed)
                return false
            }
            ModelFileState.Intact -> Unit
        }
        return true
    }

    /**
     * Finds the AI model, checks its file and registers it with the engine, then sets
     * [MainUiState.llmStatus] to what came of it.
     *
     * Registering takes no time. Loading the model into memory takes seconds (about 6 s for
     * Gemma on the razr), so that runs in the background ([loadLiveModel]) and never holds up a
     * start. Only a meeting or a translation that writes live notes loads it then, and not while
     * older recordings wait to be finished ([LiveModel.loadsInBackground]): otherwise the first
     * note loads it. A load that fails only makes the AI unavailable ([LlmStatus.Unavailable]);
     * the recording goes on.
     *
     * The engine is shared ([AiWorkGate]): the model is registered and loaded only while it is
     * free, inside the recorder's live turn ([LiveModel]). While someone else holds it (an older
     * recording is being finished), the AI counts as ready: the model is registered once the
     * engine is free again, and the first note loads it.
     *
     * @param mayLoadNow false for a model that arrives while a recording runs (a download that
     *   ends then): it is only registered, and the first note loads it. Loading at the start, next
     *   to the speech model, is fine on the razr: both together measured about 3 GB PSS of 12 GB.
     */
    private suspend fun prepareLlm(settings: AppSettings, mayLoadNow: Boolean) {
        if (!settings.llmEnabled) {
            Log.i(TAG, "LLM disabled by user, skipping initialization")
            setLlmStatus(LlmStatus.Off)
            return
        }
        val modelPath = withContext(Dispatchers.IO) { findLlmModelPath(settings) }
        if (modelPath == null) {
            Log.i(TAG, "No LLM model on disk")
            setLlmStatus(LlmStatus.Missing)
            return
        }
        // The loader aborts the process on a damaged file; LlmRepositoryImpl refuses one as well,
        // this only saves the attempt and says so.
        if (withContext(Dispatchers.IO) { ModelFileInspector.aiModel(File(modelPath)) } != ModelFileState.Intact) {
            Log.e(TAG, "LLM model file is damaged or incomplete, not loading it")
            setLlmStatus(LlmStatus.Unavailable)
            return
        }

        setLlmStatus(LlmStatus.Loading)
        // Read session fields directly to avoid a race with the init-block coroutine
        // that sets currentRecordingMode / currentInsightStrategy: initialize() can be
        // called from the UI before those coroutines have completed.
        val session = transcriptionRepository.getSession(sessionId).first()
        val sessionMode = session?.mode ?: currentRecordingMode
        val sessionStrategy = session?.insightStrategy ?: currentInsightStrategy
        val sessionLanguage = session?.language ?: recordingLanguage.value
        val loadInBackground = LiveModel.loadsInBackground(
            mayLoadNow = mayLoadNow,
            strategy = sessionStrategy,
            mode = sessionMode,
            finishing = finishingQueue.busy.value,
            writesLiveNotes = sessionLanguage.writesLiveNotes
        )
        liveModelPath = modelPath
        val turn = liveModel.tryTake()
        if (turn == null) {
            Log.i(TAG, "AI busy (${aiWorkGate.holder.value}), the live model is registered once it is free")
            setLlmStatus(LlmStatus.Ready)
            return
        }
        var loading = false
        try {
            // Apply conservative threads before loading if the device was previously detected
            // as constrained (cached in DataStore across sessions). LlmRepositoryImpl also reads
            // this at construction; this is an explicit guard in case that hasn't completed yet.
            if (settings.memoryConstrainedDetected) {
                Log.d(TAG, "LLM init: applying conservative threads (memoryConstrainedDetected=true from DataStore)")
                llmRepository.useConservativeThreads()
            }
            // Registered before anything can ask for it: every later inference loads this model,
            // or waits for the load below.
            initializeLlmUseCase(modelPath, loadImmediately = false)
                .onFailure { e ->
                    Log.e(TAG, "LLM initialization failed", e)
                    setLlmStatus(LlmStatus.Unavailable)
                    return
                }
            if (!loadInBackground) {
                Log.i(TAG, "LLM initialized successfully (loadNow=false)")
                setLlmStatus(LlmStatus.Ready)
                return
            }
            loadLiveModel(turn)
            loading = true
        } finally {
            // The load holds the turn until it ends.
            if (!loading) withContext(NonCancellable) { turn.giveBack() }
        }
    }

    /**
     * Loads the registered live model in the background, holding the [turn] [prepareLlm] took
     * until the load ends. A recorder that closes meanwhile can't free the model it is loading,
     * so the load frees it then ([LiveModel.Turn.giveBack]).
     */
    private fun loadLiveModel(turn: LiveModel.Turn) {
        // Started at once rather than dispatched: a coroutine cancelled before it runs never
        // reaches its finally, which gives the turn back.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val loadStart = SystemClock.elapsedRealtime()
                llmRepository.reloadModel()
                    .onFailure { e ->
                        Log.e(TAG, "LLM load failed, no AI notes for this recording", e)
                        setLlmStatus(LlmStatus.Unavailable)
                    }
                    .onSuccess {
                        Log.i(TAG, "LLM loaded in the background in ${SystemClock.elapsedRealtime() - loadStart} ms")
                        setLlmStatus(LlmStatus.Ready)
                        // Post-load check: model is in memory so RAM reading is accurate.
                        llmRepository.checkAndCacheMemoryConstraint()
                    }
            } finally {
                withContext(NonCancellable) { turn.giveBack() }
            }
        }
    }

    /**
     * Registers the live model with the engine again, if one was found, while nobody uses the
     * engine. When someone takes it first, the one after them or the first note registers it.
     * Not once Stop was pressed: the recording is the finisher's then, and a newer recorder
     * registers its own.
     */
    private fun registerLiveModel() {
        val path = liveModelPath ?: return
        if (_uiState.value.isFinalizingSession) return
        val turn = liveModel.tryTake() ?: return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                initializeLlmUseCase(path, loadImmediately = false)
                    .onFailure { e -> Log.w(TAG, "Live model not registered again", e) }
            } finally {
                withContext(NonCancellable) { turn.giveBack() }
            }
        }
    }

    /**
     * Prepares the AI model for a recorder that found AI notes switched off and has seen them
     * switched on since ([MainUiState.isAiPreparationDue]). While recording the model is only
     * registered, as in [prepareLlm].
     */
    private fun prepareLlmIfDue() {
        val state = _uiState.value
        if (!state.isAiPreparationDue) return
        viewModelScope.launch { prepareLlm(state.settings, mayLoadNow = !state.isRecording) }
    }

    /**
     * Resolve the LLM model path: prefer the user-selected variant, fall back to any other
     * downloaded variant so that a variant change in Settings (without downloading) does not
     * block inference. The model counts as missing only when NO variant is on disk. Gemma 4 E2B
     * is never the live model ([LlmModelChoice]): the finisher swaps it in after Stop.
     */
    private fun findLlmModelPath(settings: AppSettings): String? {
        val variant = LlmModelChoice.pick(
            selected = settings.llmModelVariant,
            recommended = null,
            installed = modelDownloadManager.installedLlmVariants()
        ) ?: return null
        return modelDownloadManager.getLlmModelPath(modelConfigForVariant(variant).llmFilename)
    }

    /**
     * The recording screen is visible (at least STARTED). A one-tap start waits for this:
     * started from the background, Android refuses the recording service.
     */
    fun onScreenStarted() {
        screenStarted = true
        lookAgainForSpeechModel()
        // Back from Settings, where the Malayalam model may have been downloaded or deleted.
        lookForMalayalam()
        startIfPending()
    }

    /** The recording screen went to the background; a pending start waits until it is back. */
    fun onScreenStopped() {
        screenStarted = false
    }

    /**
     * Back from Settings, where a speech model that was missing or damaged can be downloaded
     * again: looks at it once more and, if it is fine now, gets ready to record, so the user need
     * not leave the recorder and open another one. It does not start recording.
     */
    private fun lookAgainForSpeechModel() {
        val error = _uiState.value.error
        if (error != RecorderError.SpeechModelMissing && error != RecorderError.SpeechModelDamaged) return
        val model = recordingLanguage.value.liveModel
        viewModelScope.launch {
            val fixed = withContext(Dispatchers.IO) { modelDownloadManager.checkSttModel(model) } == ModelFileState.Intact
            // Not twice if the screen started again meanwhile.
            if (fixed && _uiState.value.error == error) initializeAgain(startWhenReady = false)
        }
    }

    /**
     * Looks whether the Malayalam model is on the phone, whole, so "Record as" offers Malayalam
     * ([MainUiState.malayalamOnPhone]); without it the sheet links to its download. A few small
     * reads (its files' sizes and what sherpa-onnx reads from them), off the main thread.
     */
    private fun lookForMalayalam() {
        viewModelScope.launch {
            val onPhone = withContext(Dispatchers.IO) {
                modelDownloadManager.checkSttModel(SpeechModelId.INDICCONFORMER_ML) == ModelFileState.Intact
            }
            _uiState.update { it.copy(malayalamOnPhone = onPhone) }
        }
    }

    /** Runs [initialize] again after the speech model failed, or was fixed. */
    private fun initializeAgain(startWhenReady: Boolean) {
        initJob = null
        isInitialized = false
        setStartPending(startWhenReady)
        _uiState.update { it.copy(error = null, isInitializing = true) }
        initialize()
    }

    /** Runs a start that waited for initialize() or for the screen to be visible again. */
    private fun startIfPending() {
        if (!autostartPending || !isInitialized || !screenStarted) return
        savedStateHandle["autostart"] = false
        setStartPending(false)
        startStreaming()
    }

    /** Sets whether a start is pending, in the field that decides it and in [MainUiState.isStartingRecording]. */
    private fun setStartPending(pending: Boolean) {
        autostartPending = pending
        _uiState.update { it.copy(isStartingRecording = pending) }
    }

    private fun fail(error: RecorderError) {
        _uiState.update { it.copy(error = error) }
    }

    private fun setLlmStatus(status: LlmStatus) {
        _uiState.update { it.copy(llmStatus = status) }
    }

    /** [MainUiState.listening] from the capture's state and whether it gave out a line yet. */
    private fun updateListening() {
        _uiState.update {
            it.copy(listening = it.isRecording && captureState == CaptureState.LISTENING && !lineHeard)
        }
    }

    /**
     * Observe LLM download state from DownloadStateManager.
     * Used when the user installs a model while this recorder is open (from the Notes tab's
     * way to Settings): once it is on disk, the LLM is initialized for this recording.
     */
    private fun observeLlmDownloadState() {
        viewModelScope.launch {
            downloadStateManager.llmDownloadState.collect { state ->
                when (state) {
                    is DownloadState.Completed -> {
                        // A leftover from onboarding, or AI notes switched off: nothing to do. Nor
                        // once Stop was pressed: the finisher finds the model on disk.
                        val current = _uiState.value
                        if (!current.settings.llmEnabled || current.llmStatus == LlmStatus.Ready || current.isFinalizingSession) {
                            return@collect
                        }
                        // Gemma 4 E2B writes no live notes and leaves the selection as it was:
                        // nothing to prepare, and the wait below could last until another model's
                        // download ends. The finisher finds its file on disk.
                        if (downloadStateManager.llmVariant.value?.writesLiveNotes == false) return@collect
                        // Race: DownloadState.Completed fires ~155 ms before AndroidDownloadManager
                        // persists the new llmModelVariant/llmModelPath to DataStore. A plain
                        // .first() would still return stale settings. Instead, collect the
                        // settings flow until we see an emission whose selected variant's file
                        // is verifiably present on disk — that signals persistence is done.
                        val freshSettings = settingsRepository.getSettings().first { settings ->
                            val filename = modelConfigForVariant(settings.llmModelVariant).llmFilename
                            modelDownloadManager.getLlmModelPath(filename) != null
                        }
                        _uiState.update { it.copy(settings = freshSettings) }
                        // A model that arrives mid-recording is only registered, so the first
                        // note loads it, not a random moment of the recording.
                        prepareLlm(freshSettings, mayLoadNow = !_uiState.value.isRecording)
                    }
                    is DownloadState.Error -> Log.w(TAG, "LLM download failed: ${state.message}")
                    // The download screen shows the progress itself.
                    is DownloadState.Downloading, is DownloadState.Verifying, is DownloadState.Paused, DownloadState.Idle -> Unit
                }
            }
        }
    }

    /**
     * Start STT streaming and periodic LLM inference.
     *
     * One capture stream, shared by the transcript, the live notes and the recording service, so
     * there is a single AudioRecord. It carries its own end ([CaptureEvent.End]), which Stop
     * waits for. The capture opens the microphone first and loads the speech model next to it;
     * one that fails to load ends it with [RecorderError.SpeechModelFailed], and its audio is kept.
     *
     * Registers the stream with SessionManager and starts the foreground service for screen-off
     * recording. If Android refuses the service, nothing is recorded and [MainUiState.error]
     * says so. Once the service runs, the recording's finishing job is stored, so a capture cut
     * off (a crash, an install, Android ending the app) is still finished. A call while
     * initialize() runs is kept and starts the recording once it is done
     * ([MainUiState.isStartingRecording] says so meanwhile). Does nothing while the recorder has
     * an error that blocks recording, which the user has to fix first.
     */
    fun startStreaming() {
        val state = _uiState.value
        if (state.isRecording || state.isFinalizingSession) {
            Log.w(TAG, "Already recording or saving")
            return
        }
        if (state.error?.recordingBlocked == true) {
            Log.w(TAG, "Recording is blocked by ${state.error}")
            return
        }
        // The grille can be tapped while the models load. Starting then would race the
        // check of the speech model, so the tap waits for initialize() instead.
        if (!isInitialized) {
            Log.i(TAG, "Start requested while initializing, deferred until ready")
            setStartPending(true)
            return
        }

        // Check that the speech model of the recording's language is downloaded before starting
        if (!modelDownloadManager.isSttModelDownloaded(recordingLanguage.value.liveModel)) {
            Log.e(TAG, "STT model not downloaded, cannot start recording")
            fail(RecorderError.SpeechModelMissing)
            return
        }

        // An earlier attempt that ended in an STT error must not keep a timer or a collector
        // running next to this one.
        cancelRecordingJobs()

        // Create a SINGLE shared STT stream. Nothing records until it is collected. It keeps the
        // recording's audio while the setting says so (read now, for this capture).
        val attempt = ++recordingAttempt
        lineHeard = false
        val capture = CaptureEvent.of(
            sttRepository.startStreaming(sessionId, keepAudio = state.settings.keepAudio, language = captureLanguage)
        ) { e ->
            onCaptureFailed(e, attempt)
        }.shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 0
        )

        // Register stream with service controller for service observation
        serviceRegistration = serviceController.registerTranscriptionFlow(capture.lines())

        // Start foreground service for screen-off recording. Without it there is no
        // notification and no wake lock, and Android may cut the microphone: record nothing.
        if (!serviceController.startService()) {
            Log.e(TAG, "Recording service refused to start, not recording")
            fail(RecorderError.ServiceRefused)
            return
        }
        recordingServiceRunning = true
        // Where the recording starts, read beside it: nothing waits for the fix. Called before
        // the service's start runs on this thread, so the service sees that a fix is being read.
        recordingPlace.onRecordingStarted(sessionId, savePlace = state.settings.savePlace)
        end.captureStarted()
        captureStartedJob = viewModelScope.launch { finishingQueue.captureStarted(sessionId) }

        _uiState.update { it.copy(isRecording = true, error = null, recordingDurationMillis = accumulatedDurationMs) }
        updateListening()

        // Store for mid-session LLM job restart
        activeCapture = capture
        activeTranslationTarget = currentOutputLanguage
            ?: _uiState.value.settings.translationTargetLanguage

        // Start recording timer offset by any previously accumulated duration so that
        // resuming within the same session produces a cumulative elapsed time display.
        val segmentStart = SystemClock.elapsedRealtime()
        stretchStartedAtMs = segmentStart
        timerJob = viewModelScope.launch {
            var lastSavedMs = accumulatedDurationMs
            while (true) {
                // Wake just after the next whole second, so the display steps by exactly one
                // second. A plain delay(1000) drifts later each tick and now and then skips one.
                val shown = accumulatedDurationMs + (SystemClock.elapsedRealtime() - segmentStart)
                delay(1000 - shown % 1000)
                val segmentElapsed = SystemClock.elapsedRealtime() - segmentStart
                val totalDurationMs = accumulatedDurationMs + segmentElapsed
                _uiState.update { it.copy(recordingDurationMillis = totalDurationMs) }

                if (totalDurationMs - lastSavedMs >= DURATION_SAVE_INTERVAL_MS) {
                    lastSavedMs = totalDurationMs
                    saveDuration(totalDurationMs)
                }
            }
        }

        // Watch settings while recording: restart the LLM job if the translation
        // target language changes (only relevant in REAL_TIME_TRANSLATION mode where
        // the output language drives the system prompt).
        settingsWatcherJob = viewModelScope.launch {
            settingsRepository.getSettings().collect { settings ->
                val newTarget = currentOutputLanguage ?: settings.translationTargetLanguage
                if (currentRecordingMode == RecordingMode.REAL_TIME_TRANSLATION
                    && newTarget != activeTranslationTarget
                    && _uiState.value.isRecording
                ) {
                    Log.i(TAG, "Translation target changed ($activeTranslationTarget → $newTarget), restarting LLM job")
                    delay(500L) // debounce rapid consecutive settings changes
                    activeTranslationTarget = newTarget
                    restartInsightsJob(
                        capture = activeCapture ?: return@collect,
                        mode = currentRecordingMode,
                        outputLanguage = newTarget
                    )
                }
            }
        }

        // Collect transcription segments for UI display AND persistence, until the capture's end.
        // DEDUPLICATION: Partial segments (isComplete=false) replace the current one
        // Complete segments (isComplete=true) are added to the completed list and saved to DB
        transcriptionJob = viewModelScope.launch {
            capture.linesUntilEnd().collect { segment ->
                // What was said stays out of the log; its length is enough to follow the flow.
                Log.d(TAG, "Received segment: ${segment.text.length} chars (complete=${segment.isComplete})")
                if (!lineHeard && segment.text.isNotBlank()) {
                    lineHeard = true
                    updateListening()
                }

                // Add sessionId to segment
                val segmentWithSession = segment.copy(sessionId = sessionId)

                // Save complete segments to database — completed segments update
                // automatically via the database Flow collector in init block
                if (segmentWithSession.isComplete) {
                    saveSegmentUseCase(segmentWithSession)
                        .onFailure { e ->
                            Log.e(TAG, "Failed to save segment to DB: ${e.message}")
                        }
                        .onSuccess {
                            Log.d(TAG, "Segment saved to DB: ${segmentWithSession.id}")
                        }
                    // Clear partial since this segment is now complete
                    _uiState.update { state ->
                        state.copy(currentPartialSegment = null)
                    }
                } else {
                    // Partial segment: replace the current partial (no duplication!)
                    _uiState.update { state ->
                        state.copy(currentPartialSegment = segmentWithSession)
                    }
                }
            }
        }

        // Collect LLM insights from the SAME shared stream AND persist them.
        // For END_OF_SESSION strategy, skip the real-time insights job entirely —
        // the batch pipeline will run after stop.
        if (currentInsightStrategy == InsightStrategy.REAL_TIME) {
            launchInsightsJob(capture, currentRecordingMode, currentOutputLanguage)
        } else {
            Log.i(TAG, "END_OF_SESSION strategy: skipping real-time LLM job")
        }

        Log.i(TAG, "STT streaming started with service support - screen-off recording enabled")
    }

    /**
     * The capture of start [attempt] failed with [e]. The stream's end follows, after which the
     * transcript collector is done. A speech model that failed to load once the microphone was
     * open ([SpeechModelLoadException]) is told apart from a failure while transcribing.
     */
    private fun onCaptureFailed(e: Throwable, attempt: Int) {
        if (e is SpeechLeftForAfterStopException) {
            // Only after Stop: the gap it leaves is told to the finisher, which transcribes the audio.
            Log.i(TAG, "Capture ended at Stop before its speech model was in; its audio is transcribed after Stop")
        } else {
            Log.e(TAG, "STT error", e)
        }
        val current = attempt == recordingAttempt
        gaps.captureFailed(e, current = current, recognizerHeard = sttRepository.mayHaveHeardSpeech())
        // Ignored for an earlier stream, and during a stop, which tears down itself and
        // only loses the last words.
        if (!current || !_uiState.value.isRecording) return
        timerJob?.cancel()
        // The length so far is kept, so a new start continues it.
        accumulatedDurationMs = _uiState.value.recordingDurationMillis
        val error = if (e is SpeechModelLoadException) RecorderError.SpeechModelFailed else RecorderError.TranscriptionFailed
        _uiState.update { it.copy(error = error, isRecording = false) }
        updateListening()
        // Posted, not run here: it then runs after startStreaming() has set up every
        // job, and outside this stream's coroutine, which ending the subscriptions cancels.
        viewModelScope.launch(Dispatchers.Main) {
            if (attempt == recordingAttempt) abortRecording()
        }
    }

    /**
     * Cancel the current LLM insights job and restart it with new parameters.
     *
     * Called mid-session when settings that affect LLM inference change
     * (e.g. translation target language). Audio/STT jobs are NOT touched.
     */
    private fun restartInsightsJob(
        capture: SharedFlow<CaptureEvent>,
        mode: RecordingMode,
        outputLanguage: String?
    ) {
        insightsJob?.cancel()
        launchInsightsJob(capture, mode, outputLanguage)
        Log.i(TAG, "Insights job restarted: mode=$mode, output=$outputLanguage")
    }

    /**
     * Writes the live notes while recording: every interval the AI reads what was said since
     * the last note, in the language that was spoken ([notesLanguage]), and each note is saved.
     * A note is written only when the engine is free ([liveNoteTurn]). The notes follow the
     * recording's mode from their next interval when it is switched ([recordAs]), and stop while it
     * is in Malayalam ([recordIn]).
     */
    private fun launchInsightsJob(
        capture: SharedFlow<CaptureEvent>,
        mode: RecordingMode,
        outputLanguage: String?
    ) {
        insightsJob = viewModelScope.launch {
            syncSttLlmUseCase(
                transcriptionFlow = capture.lines(),
                sessionId = sessionId,
                mode = mode,
                outputLanguage = outputLanguage,
                topic = currentTopic,
                notesLanguage = { text -> notesLanguage.resolve(sessionId, text) },
                turn = liveNoteTurn,
                // Engine errors carry no transcript, so they can be logged in full.
                onInferenceError = { e -> Log.w(TAG, "Live insight failed", e) },
                modeChanges = recordingMode,
                recordingLanguage = recordingLanguage.value,
                recordingLanguageChanges = recordingLanguage
            )
                .catch { e ->
                    // Handle errors without crashing
                    Log.e(TAG, "LLM error", e)
                    setLlmStatus(LlmStatus.Unavailable)
                }
                .collect { insight ->
                    Log.d(TAG, "Received insight ${insight.id}: ${insight.content.length} chars")

                    // Save insight to database — UI updates automatically via
                    // the database Flow collector in init block
                    saveInsightUseCase(insight)
                        .onFailure { e ->
                            Log.e(TAG, "Failed to save insight to DB: ${e.message}")
                        }
                        .onSuccess {
                            Log.d(TAG, "Insight saved to DB: ${insight.id}")
                        }
                }
        }
    }

    /**
     * Ends a recording whose STT stream failed. Without this the timer, the recording service
     * and its wake lock would keep running behind the error. The recording is handed to the
     * finisher only as the recorder closes, or after a new start that ends with Stop.
     */
    private suspend fun abortRecording() {
        cancelRecordingJobs()
        activeCapture = null
        stopRecordingService()
        // The failed stream leaves the data source marked as recording, maybe with its
        // AudioRecord still open.
        try {
            stopSttStreamingUseCase()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping STT after a stream error", e)
        }
        saveDuration(accumulatedDurationMs)
        Log.i(TAG, "Recording ended after an STT error, length kept: ${accumulatedDurationMs}ms")
    }

    private fun cancelRecordingJobs() {
        timerJob?.cancel()
        settingsWatcherJob?.cancel()
        transcriptionJob?.cancel()
        insightsJob?.cancel()
        timerJob = null
        settingsWatcherJob = null
        transcriptionJob = null
        insightsJob = null
    }

    private fun stopRecordingService() {
        if (!recordingServiceRunning) return
        serviceController.stopService(serviceRegistration)
        recordingServiceRunning = false
    }

    private suspend fun saveDuration(durationMs: Long) {
        updateSessionDurationUseCase(sessionId, durationMs)
            .onFailure { e -> Log.e(TAG, "Failed to persist session duration: ${e.message}") }
    }

    /**
     * Stop recording: the last words are saved, the recording is handed to the finisher (or
     * deleted if nothing was said in it) and the recorder goes home ([MainUiState.exit]). The
     * notes, the name, the to-dos and the map are written in the background meanwhile, so the
     * next recording can start at once.
     */
    fun stopStreaming() {
        if (!_uiState.value.isRecording) {
            Log.w(TAG, "Not recording")
            return
        }

        // Immediately reflect the stop in the UI so the button responds at once.
        _uiState.update { it.copy(isRecording = false, saving = true) }
        updateListening()
        // The notification stops saying "recording" while the last words are saved.
        serviceController.markStopping()

        viewModelScope.launch { saveAndHandOff() }
    }

    /**
     * Ends the capture ([stopCapturing]), then deals with the recording in the order of
     * [HandOffPlan]: the silent rule, the hand-off, the recording service's stop, the way out.
     */
    private suspend fun saveAndHandOff() {
        val stopStart = SystemClock.elapsedRealtime()
        stopCapturing()
        val savedAfterMs = SystemClock.elapsedRealtime() - stopStart
        HandOffPlan(
            deleteIfSilent = ::deleteIfSilentAtStop,
            handOff = { handOff(waitForCaptureStart = true) },
            stopService = ::stopRecordingService,
            leave = { deleted ->
                _uiState.update { it.afterHandOff(deleted, startInFlight = activeRecorder.startInFlight.value) }
                Log.i(
                    TAG,
                    "Stop: last words saved after $savedAfterMs ms, " +
                        "${if (deleted) "deleted" else "handed off"} after ${SystemClock.elapsedRealtime() - stopStart} ms " +
                        "(exit=${_uiState.value.exit})"
                )
            }
        ).run()
    }

    /**
     * Stops the capture, in this order: the timer and the settings watcher stop and the length
     * is saved; the live note being written, if any, is dropped without waiting (the Notes step
     * after Stop reads what no note covers); the microphone stops and the stream's last words
     * are saved, up to its end, for at most [DRAIN_GUARD_MS]; the speech model is released. The
     * line on screen at Stop counts as said, however its line ends up. A capture that doesn't end
     * in time may lack what it had not transcribed by then ([TranscriptGaps.drainTimedOut]).
     */
    private suspend fun stopCapturing() {
        settingsWatcherJob?.cancel()
        timerJob?.cancel()
        settingsWatcherJob = null
        timerJob = null

        // Persist total cumulative recording duration before tearing down.
        // accumulatedDurationMs is updated so that a subsequent startStreaming() call
        // within the same session continues from where the timer left off.
        accumulatedDurationMs = _uiState.value.recordingDurationMillis
        saveDuration(accumulatedDurationMs)

        insightsJob?.cancel()
        insightsJob = null

        val onScreen = _uiState.value.currentPartialSegment?.text.orEmpty()

        // Stop STT BEFORE the transcript collector is done: stopping the AudioRecord signals
        // Sherpa to flush remaining buffered audio (including any in-flight speech) and emit the
        // final complete segments, then the stream ends, which the collector waits for. A stop
        // that fails still goes on to the hand-off; the guard below ends the wait.
        try {
            stopSttStreamingUseCase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping STT at Stop", e)
        }
        if (!awaitCaptureEnd(transcriptionJob, DRAIN_GUARD_MS)) {
            val state = sttRepository.captureState.value
            Log.w(TAG, "Capture did not end within 15 s (state=$state)")
            gaps.drainTimedOut(state)
        }
        transcriptionJob = null

        // Release the Sherpa-ONNX native model from memory (~670 MB native heap), now that the
        // last words are decoded. The finisher needs the memory.
        try {
            sttRepository.releaseModel()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing STT model at Stop", e)
        }

        // The flush emits the line on screen as a complete one, so it is gone from the screen.
        _uiState.update { it.copy(currentPartialSegment = null) }
        activeCapture = null
        // The recognition is read once the speech model is released: no line can come out after
        // that, and an utterance still waiting for one is open.
        end.captureStopped(held = onScreen, recognizerHeard = sttRepository.mayHaveHeardSpeech())
    }

    /**
     * The silent rule at Stop, with what this recorder knows ([RecordingEnd.atStop]): a recording
     * in which nothing was said is deleted. One that can't be deleted is kept and handed off.
     *
     * @return true if the recording is gone
     */
    private suspend fun deleteIfSilentAtStop(): Boolean {
        val facts = end.atStop()
        val deleted = discardSilently(facts)
        if (deleted) end.handedToFinishing()
        return deleted
    }

    /**
     * Runs the silent rule with [facts], and logs what came of it. Never throws but a cancel.
     *
     * @return true if the recording is gone
     */
    private suspend fun discardSilently(facts: RecordingEnd.Facts): Boolean {
        val deleted = try {
            discardSilentRecording(sessionId, facts.captured, heard = facts.heard, mayLackSpeech = facts.mayLackSpeech)
                .onFailure { e -> Log.e(TAG, "Failed to remove a recording without speech: ${e.message}") }
                .getOrDefault(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for a recording without speech", e)
            false
        }
        if (deleted) {
            Log.i(TAG, "Removed recording $sessionId, nothing was said in it (captured=${facts.captured})")
            recordingPlace.onRecordingDiscarded(sessionId)
        }
        return deleted
    }

    /**
     * Hands the recording to the finisher: its job is queued and the AI's service taken for it.
     * Call before the recording service stops. The finisher is told whether part of the audio may
     * never have been transcribed ([TranscriptGaps.recognizerFailed]). A hand-off that fails is
     * tried again as the recorder closes ([closeWithoutStop]).
     *
     * A switch to another category being stored ([recordAs]) is waited for first, also from
     * onCleared: it runs off the main thread, so the finisher reads the mode it was switched to.
     *
     * @param waitForCaptureStart Whether to wait for the capture's job to be stored first, so the
     *   queued job can't be overwritten by it; never from onCleared, whose runBlocking holds the
     *   main thread that the store would resume on
     */
    private suspend fun handOff(waitForCaptureStart: Boolean) {
        if (waitForCaptureStart) captureStartedJob?.join()
        switchJob?.let { switching ->
            if (withTimeoutOrNull(SWITCH_WAIT_MS) { switching.join() } == null) {
                Log.w(TAG, "Recording $sessionId handed to the finisher before its switch to another category or language was stored")
            }
        }
        val recognizerFailed = gaps.recognizerFailed
        try {
            finishingQueue.handOff(sessionId, recognizerFailed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error handing recording $sessionId to the finisher", e)
            return
        }
        end.handedToFinishing()
        Log.i(TAG, "Recording $sessionId handed to the finisher (recognizerFailed=$recognizerFailed)")
    }

    /**
     * Back was pressed while the recording is being saved: the recorder goes back once it is
     * saved ([RecorderExit.BACK]). Nothing is said meanwhile; saving takes well under a second.
     */
    fun leaveWhenSaved() {
        _uiState.update { if (it.saving) it.copy(leaveWhenSaved = true) else it }
    }

    /**
     * The recorder closes without Stop: Back, a way out of the microphone dialog, the app closed
     * while recording. After a capture (one that stopped on an error, or one still running) the
     * rule Stop applies decides, with the line on screen counted as said and, for a capture cut
     * off as it closes, what its recognition still had open ([RecordingEnd.atClose]); a capture
     * that is kept is handed to the finisher. A recorder that never recorded deletes only a
     * recording that holds nothing at all, whose row was created before it opened. See
     * [DiscardSilentRecordingUseCase]. A recording that Stop, or an earlier close (Back, then
     * onCleared), has dealt with is left as it is ([RecordingEnd.close]).
     *
     * @param waitForCaptureStart See [handOff]
     * @param cutOffIn The state of a capture that still runs as the recorder closes, which is cut
     *   off ([TranscriptGaps.closedWhileCapturing]); null when none runs
     * @return true if the recording is gone
     */
    private suspend fun closeWithoutStop(waitForCaptureStart: Boolean, cutOffIn: CaptureState? = null): Boolean {
        cutOffIn?.let { state -> gaps.closedWhileCapturing(state, recognizerHeard = sttRepository::mayHaveHeardSpeech) }
        return end.close(
            onScreen = _uiState.value.currentPartialSegment?.text,
            recognizerHeard = sttRepository::mayHaveHeardSpeech,
            deleteIfSilent = ::discardSilently,
            handOff = { handOff(waitForCaptureStart) }
        )
    }

    /**
     * The user leaves the recorder: Back, or a way out of the microphone dialog. A recording in
     * which nothing was said is deleted first, and one that was recorded in is handed to the
     * finisher ([closeWithoutStop]), so the list never shows an empty one; [onLeft] runs when that
     * is done and navigates away. It is told `nothingSaid` when a recording that was recorded went
     * that way, which the list then explains; one that never recorded was never there for the
     * user. Does nothing while recording or saving, which the screen answers itself.
     */
    fun leave(onLeft: (nothingSaid: Boolean) -> Unit) {
        val state = _uiState.value
        if (state.isRecording || state.isFinalizingSession) return
        // A start that is still waiting for the models must not begin once the recording is gone.
        setStartPending(false)
        viewModelScope.launch {
            val deleted = try {
                closeWithoutStop(waitForCaptureStart = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error closing the recording on leaving", e)
                false
            }
            onLeft(deleted && end.hasCaptured)
        }
    }

    /**
     * The microphone permission as the screen reads it ([granted]), as it opens and on every return
     * to it (from the app's settings page, say); [refusedBefore] is whether Android says it was
     * refused before ([MainUiState.microphoneRead]). Allowed, the recorder gets ready, and records if
     * it was opened to; not allowed, it says so in place and waits for the owner.
     */
    fun onMicrophoneRead(granted: Boolean, refusedBefore: Boolean) {
        val before = _uiState.value.microphone
        val now = MainUiState.microphoneRead(before, granted, refusedBefore)
        val first = !microphoneRead
        microphoneRead = true
        if (first || now != before) setMicrophone(now)
    }

    /**
     * Android's microphone window was answered ([granted]); [canAskAgain] is whether Android would
     * show it again ([MicrophoneState.answered]). Allowed, the recorder gets ready and records if it
     * was opened to; refused, it says so in place, with Android's window once more after a first
     * refusal and the app's settings page after a second. The recorder stays, and so does its
     * recording.
     */
    fun onMicrophoneAnswered(granted: Boolean, canAskAgain: Boolean) {
        val before = _uiState.value.microphone
        val now = MicrophoneState.answered(before, granted, canAskAgain)
        // A return to the screen may have read it allowed already; a refusal always ends the wait.
        if (now != before || now != MicrophoneState.ALLOWED) setMicrophone(now)
    }

    /** Android's microphone window is about to show: the recording the recorder was opened for waits for it. */
    fun onMicrophoneAsked() {
        if (savedStateHandle.get<Boolean>("autostart") == true) setStartPending(true)
    }

    /**
     * The microphone is now [microphone]. Allowed: the recorder gets ready, and the recording it was
     * opened for starts once it is (the autostart is cleared only once a start runs). Not allowed:
     * nothing starts, and the recorder waits idle for the owner rather than as a start, which would
     * hold up the finishing of older recordings and the downloads.
     */
    private fun setMicrophone(microphone: MicrophoneState) {
        if (microphone == MicrophoneState.ALLOWED) {
            _uiState.update { it.copy(microphone = microphone, isInitializing = !isInitialized) }
            if (savedStateHandle.get<Boolean>("autostart") == true) setStartPending(true)
            if (isInitialized) startIfPending() else initialize()
        } else {
            setStartPending(false)
            _uiState.update { it.copy(microphone = microphone, isInitializing = false) }
            Log.i(TAG, "Microphone not allowed: $microphone")
        }
    }

    /**
     * The way forward from an error that can be tried again: a speech model that didn't load is
     * looked at again and a start follows, and a recording that couldn't start or stopped on an
     * error is started again (it continues the same recording). Does nothing for any other error.
     */
    fun retry() {
        when (_uiState.value.error) {
            RecorderError.SpeechModelFailed -> initializeAgain(startWhenReady = true)
            RecorderError.ServiceRefused, RecorderError.TranscriptionFailed -> {
                _uiState.update { it.copy(error = null) }
                startStreaming()
            }
            else -> Unit
        }
    }

    /**
     * Switches AI notes on, from the recorder's own note that they are off. The recorder then
     * prepares the model and writes notes from the next interval on; the Notes step after Stop
     * covers the rest.
     */
    fun turnOnAi() {
        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            if (!settings.llmEnabled) settingsRepository.updateSettings(settings.copy(llmEnabled = true))
        }
    }

    /**
     * Records the rest of this recording as the category [categoryId] ("Record as", from the label
     * at the top): its category and its mode are stored together, and the live notes go on in that
     * mode from their next interval, with its interval, its length of answer and its prompt in the
     * notes' language. The notes written so far stay, and a note being written finishes in the
     * mode it began in. After Stop the finisher writes the last notes, the name and the to-dos as
     * that mode does, and home lists the recording under the category.
     *
     * The capture is not touched: the microphone, the speech model, the transcript and the live
     * notes' job go on as they are; the job reads the mode as it changes. Offered only while
     * recording ([MainUiState.canChangeType]), and never into or out of a translation. Switches are
     * stored in the order they are made; [notices] says what came of each.
     *
     * The store runs off the main thread, beyond this view model: Android may clear the recorder
     * just after a pick, and the hand-off as it closes (onCleared, which holds the main thread)
     * still waits for the switch, so the finisher writes the notes of the mode it was switched to.
     */
    fun recordAs(categoryId: String) {
        if (!_uiState.value.canChangeType) return
        val atMs = recordingPositionMs()
        val before = switchJob
        switchJob = switchScope.launch {
            before?.join()
            when (val outcome = changeRecordingType(sessionId, categoryId)) {
                is ChangeRecordingTypeUseCase.Outcome.Changed -> {
                    val category = outcome.category
                    currentRecordingMode = category.mode
                    _uiState.update { it.copy(sessionMode = category.mode, sessionCategoryId = category.id) }
                    Log.i(TAG, switchLine(sessionId, category, atMs))
                    _notices.trySend(RecorderNotice.RecordingAs(category.name))
                }
                ChangeRecordingTypeUseCase.Outcome.Unchanged -> Unit
                ChangeRecordingTypeUseCase.Outcome.Refused -> {
                    Log.w(TAG, "Recording $sessionId not switched to $categoryId: it or the category is gone, or translates")
                    _notices.trySend(RecorderNotice.SwitchFailed)
                }
                is ChangeRecordingTypeUseCase.Outcome.Failed -> {
                    Log.e(TAG, "Recording $sessionId not switched to $categoryId", outcome.cause)
                    _notices.trySend(RecorderNotice.SwitchFailed)
                }
            }
        }
    }

    /**
     * Records the rest of this recording in [language] ("Record as", the sheet's language row): the
     * language is stored, with the new language's live model as the transcript's, and the capture
     * changes its speech model between two utterances, freeing the one in memory and loading the
     * other ([captureLanguage]). The microphone, the audio waiting to be transcribed and the kept
     * audio go on meanwhile: nothing said is lost, and what is said during the change is transcribed
     * by the new model once it is in. The utterance being said finishes with the model it began with.
     *
     * In Malayalam no live note is written: the small live model reads it poorly, so its notes come
     * after Stop, from the finishing, and the notice says so. Back in English or German they go on
     * from their next interval. Offered only while recording ([MainUiState.canChangeLanguage]),
     * never for a translation, and Malayalam only while its model is on the phone
     * ([MainUiState.malayalamOnPhone]). Switches are stored in the order they are made, after a
     * switch of category made before them; [notices] says what came of each. Like [recordAs], the
     * store runs off the main thread and the hand-off waits for it.
     */
    fun recordIn(language: RecordingLanguage) {
        val state = _uiState.value
        if (!state.canChangeLanguage || language == state.language) return
        if (language == RecordingLanguage.MALAYALAM && !state.malayalamOnPhone) return
        val atMs = recordingPositionMs()
        val before = switchJob
        switchJob = switchScope.launch {
            before?.join()
            when (val outcome = changeRecordingLanguage.whileRecording(sessionId, language)) {
                is ChangeRecordingLanguageUseCase.Outcome.Changed -> {
                    recordingLanguage.value = language
                    _uiState.update { it.copy(language = language) }
                    Log.i(TAG, languageLine(sessionId, language, atMs))
                    _notices.trySend(RecorderNotice.RecordingIn(language))
                }
                ChangeRecordingLanguageUseCase.Outcome.Unchanged -> Unit
                ChangeRecordingLanguageUseCase.Outcome.Refused -> {
                    Log.w(TAG, "Recording $sessionId not switched to $language: it is gone or translates, or the model isn't on the phone")
                    _notices.trySend(RecorderNotice.SwitchFailed)
                }
                is ChangeRecordingLanguageUseCase.Outcome.Failed -> {
                    Log.e(TAG, "Recording $sessionId not switched to $language", outcome.cause)
                    _notices.trySend(RecorderNotice.SwitchFailed)
                }
            }
        }
    }

    /**
     * The capture couldn't load the model of [wanted] after a switch, and goes on in [instead] with
     * the model it had ([CaptureLanguage.refused]): the recording is in [instead] again, its live
     * notes as that language has them, and that is stored, as the finisher reads it after Stop.
     * Nothing changes when the recording was switched again meanwhile.
     */
    private fun takeBackLanguage(wanted: RecordingLanguage, instead: RecordingLanguage) {
        if (recordingLanguage.value != wanted) return
        Log.w(TAG, "Recording $sessionId: the speech model of $wanted didn't load, it goes on in $instead")
        recordingLanguage.value = instead
        _uiState.update { it.copy(language = instead) }
        _notices.trySend(RecorderNotice.LanguageRefused(wanted, instead))
        val before = switchJob
        switchJob = switchScope.launch {
            before?.join()
            val outcome = changeRecordingLanguage.whileRecording(sessionId, instead)
            if (outcome !is ChangeRecordingLanguageUseCase.Outcome.Changed && outcome != ChangeRecordingLanguageUseCase.Outcome.Unchanged) {
                Log.e(TAG, "Recording $sessionId stays stored in $wanted after its speech model didn't load ($outcome)")
            }
        }
    }

    /** How far into the recording it is now, in ms: what its earlier stretches recorded, and this one so far. */
    private fun recordingPositionMs(): Long =
        accumulatedDurationMs + if (_uiState.value.isRecording) SystemClock.elapsedRealtime() - stretchStartedAtMs else 0L

    override fun onCleared() {
        super.onCleared()

        // IMPORTANT: viewModelScope is already cancelled by the time onCleared() runs —
        // lifecycle-viewmodel calls clear() which closes all tagged Closeables (including
        // viewModelScope's SupervisorJob) BEFORE invoking onCleared(). Any viewModelScope.launch {}
        // here would be a no-op. Use runBlocking for all cleanup that must actually execute, and
        // never join a job of viewModelScope in it: that job resumes on the main thread, which
        // runBlocking holds.

        // A capture this recorder never stopped is cut off, in the state it is in now: one that
        // only listens has transcribed nothing yet.
        val cutOffIn = if (activeCapture != null) sttRepository.captureState.value else null

        // Stop the AudioRecord if recording is still active (e.g. the app closed mid-session),
        // or if a stop or an error teardown was cut short before it got there (the service is
        // then still running). stopRecording() is fast: sets isRecording=false, stops and
        // releases the AudioRecord.
        if (_uiState.value.isRecording || recordingServiceRunning) {
            Log.i(TAG, "ViewModel cleared while recording - stopping AudioRecord")
            runBlocking {
                try {
                    stopSttStreamingUseCase()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping STT in onCleared", e)
                }
                // The timer's last save may be up to ~20 s old.
                saveDuration(_uiState.value.recordingDurationMillis)
            }
        }

        // The models are shared: a newer recorder that opened meanwhile (home's Record or a
        // quick key press while this one closed) uses them now, and releases them as it closes.
        val mayRelease = activeRecorder.mayRelease(this)
        runBlocking {
            if (mayRelease) {
                // Release the STT model native heap (~670 MB ONNX).
                try {
                    sttRepository.releaseModel()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing STT model in onCleared", e)
                }
            }
            // A capture Stop never dealt with: deleted if nothing was said in it, else handed
            // off, before the recording service stops below.
            try {
                closeWithoutStop(waitForCaptureStart = false, cutOffIn = cutOffIn)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing the recording in onCleared", e)
            }
        }
        // Also after Stop, when the stop flow was cut short before it reached stopService():
        // the recording service holds a wake lock until it is told to stop.
        stopRecordingService()

        if (mayRelease) releaseLlmOnClose() else Log.i(TAG, "A newer recorder uses the models, leaving them loaded")

        transcriptionJob?.cancel()
        insightsJob?.cancel()
        settingsWatcherJob?.cancel()
        timerJob?.cancel()
        // Last: the models are released, the AI model's release holding the engine until it is
        // done (or they are kept for a newer recorder), before the quick key can see no recorder.
        activeRecorder.leave(this)
    }

    /**
     * Frees the AI model as the recorder closes, holding the engine, without holding up the main
     * thread ([LiveModel.close]): at once while the engine is free; by this recorder's own work
     * (the background load, a live note) as it ends; otherwise by whoever holds the engine (the
     * finisher at the end of its queue).
     */
    private fun releaseLlmOnClose() {
        when (val closing = liveModel.close()) {
            LiveModel.Closing.Now -> Unit
            LiveModel.Closing.ByOwnWork -> Log.i(TAG, "The recorder's own AI work frees the LLM as it ends")
            is LiveModel.Closing.LeftTo -> Log.i(TAG, "AI busy (${closing.holder}), its holder frees the model")
        }
    }

    /** Frees the model in memory as the recorder closes ([releaseLlmOnClose]); never throws but a cancel. */
    private suspend fun freeLiveModel() {
        try {
            llmRepository.cleanup()
            Log.i(TAG, "LLM resources released on session end")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LLM resources as the recorder closed", e)
        }
    }
}
