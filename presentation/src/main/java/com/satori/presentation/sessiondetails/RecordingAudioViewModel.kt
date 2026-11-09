package com.satori.presentation.sessiondetails

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.ProcessingHolders
import com.satori.domain.language.SpokenLanguage
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.monitor.DeviceMemory
import com.satori.domain.repository.AudioUsage
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.stt.ModelReadiness
import com.satori.domain.usecase.stt.SpeechModelChoice
import com.satori.domain.usecase.stt.TranscribeAgainOutcome
import com.satori.domain.usecase.stt.TranscribeAgainUseCase
import com.satori.domain.usecase.stt.TranscribeOption
import com.satori.domain.usecase.stt.TranscriptLanguage
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.StoppedWork
import com.satori.presentation.main.capturing
import com.satori.presentation.main.runUntilCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The kept audio of the recording on the details page: playing it ([RecordingPlayer]), Transcribe
 * again ([TranscribeAgainUseCase]) with a speech model chosen from those on the phone that know
 * the recording's language ([SpeechModelChoice]), keeping the audio out of the settings that
 * compress or delete older audio, and deleting it. Kept apart from [SessionDetailsViewModel],
 * which reads and edits what the recording says.
 *
 * A recorder that starts (the quick key, say) stops both: it needs the microphone, and a
 * transcription next to a live recording would slow down its speech recognition. The recorder
 * wins ([runUntilCapture]): Transcribe again stops after the stretch of audio it is decoding,
 * the transcript stays as it was, and the recording's page says so once, opened again
 * ([AudioNotice.TranscribeStopped], kept in [StoppedWork]): the start closes this one.
 * Transcribe again also waits while the AI is in use ([AiWorkGate]) or a recording waits to be
 * finished ([FinishingQueue.busy]), whose steps change the transcript; playing doesn't. Audio
 * that plays or is transcribed again counts as in use ([AudioUsage]), so the settings that
 * compress or delete older audio leave it alone meanwhile.
 *
 * @property speechModels Which speech models are on the phone, and their sizes
 * @property llmRepository Whether the AI model is in memory, which keeps a large speech model out
 * @property memory The memory free, which a large speech model needs
 * @property stoppedWork Where a Transcribe again a recorder stopped waits to be told
 * @property savedStateHandle Navigation arguments: `sessionId`
 */
@HiltViewModel
class RecordingAudioViewModel @Inject constructor(
    private val audioRepository: RecordingAudioRepository,
    private val transcribeAgainUseCase: TranscribeAgainUseCase,
    private val transcriptionRepository: TranscriptionRepository,
    private val speechModels: SpeechModelRepository,
    private val llmRepository: LlmRepository,
    private val memory: DeviceMemory,
    private val player: RecordingPlayer,
    private val processingService: LlmProcessingServiceController,
    private val aiGate: AiWorkGate,
    private val finishingQueue: FinishingQueue,
    private val audioUsage: AudioUsage,
    private val activeRecorder: ActiveRecorder,
    private val stoppedWork: StoppedWork,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"]) {
        "Session ID is required"
    }

    private val _uiState = MutableStateFlow(RecordingAudioUiState())
    val uiState: StateFlow<RecordingAudioUiState> = _uiState.asStateFlow()

    /** Where this recording's audio plays; nothing while another recording's is loaded. */
    val playback: StateFlow<RecordingPlayer.State> = player.state
        .map { it.takeIf { state -> state.sessionId == sessionId } ?: NOT_LOADED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NOT_LOADED)

    private val _notices = Channel<AudioNotice>(Channel.BUFFERED)

    /** Confirmations for the screen to show once each. */
    val notices: Flow<AudioNotice> = _notices.receiveAsFlow()

    private var transcribeJob: Job? = null

    // Whether this page's playing holds the audio in use; begin and end must pair.
    private var playingInUse = false

    init {
        refresh()
        viewModelScope.launch {
            stoppedWork.stopsOn(sessionId, setOf(StoppedWork.Kind.TRANSCRIBE_AGAIN), taker = this@RecordingAudioViewModel)
                .collect { _notices.trySend(AudioNotice.TranscribeStopped) }
        }
        viewModelScope.launch {
            playback.collect { state ->
                if (state.failed) _uiState.update { it.copy(error = AudioError.Playback) }
            }
        }
        viewModelScope.launch {
            playback.map { it.playing }.distinctUntilChanged().collect { playing -> markPlaying(playing) }
        }
        viewModelScope.launch {
            // A recording waiting to be finished goes first, also before the finisher holds the gate.
            combine(aiGate.busy, finishingQueue.busy) { gate, finishing -> gate || finishing }.collect { busy ->
                _uiState.update {
                    // An open choice of model goes, saying why: none of them could start now.
                    val closing = busy && it.transcribeChoice != null
                    it.copy(
                        aiBusy = busy,
                        transcribeChoice = it.transcribeChoice.takeUnless { closing },
                        error = if (closing) AudioError.AiBusy else it.error
                    )
                }
            }
        }
        viewModelScope.launch {
            activeRecorder.current.collect { recorder ->
                val busy = recorder.capturing
                // An open choice of model goes too: none of them could start now.
                _uiState.update { it.copy(recorderBusy = busy, transcribeChoice = it.transcribeChoice.takeUnless { busy }) }
                // A Transcribe again under way stops by itself ([transcribeAgain]).
                if (busy) player.stop(sessionId)
            }
        }
    }

    /**
     * Looks again for the audio and the speech model. The screen calls it when it comes back,
     * from the speech model's download say.
     */
    fun refresh() {
        viewModelScope.launch {
            val audio = audioRepository.find(sessionId)
            val speechModel = SpeechModelId.entries.any { speechModels.isInstalled(it) }
            _uiState.update { it.copy(audio = audio, speechModel = speechModel) }
        }
    }

    /** Plays, or pauses what plays. */
    fun togglePlay() {
        val audio = _uiState.value.audio ?: return
        // The player's own state, which is never a dispatch behind as a derived flow can be.
        val now = player.state.value
        if (now.sessionId == sessionId && now.playing) player.pause() else play(audio, fromMs = null)
    }

    /** Plays from [positionMs]: a tapped line, as [RecordingAudio.startOf] finds it. */
    fun playFrom(positionMs: Long) {
        val audio = _uiState.value.audio ?: return
        play(audio, positionMs)
    }

    /** Moves to [positionMs], playing on if it plays. */
    fun seekTo(positionMs: Long) {
        val audio = _uiState.value.audio ?: return
        player.seekTo(audio, positionMs)
    }

    /** The page closes, or another opens over it: its audio stops. */
    fun stopPlayback() {
        player.stop(sessionId)
    }

    private fun play(audio: RecordingAudio, fromMs: Long?) {
        if (_uiState.value.recorderBusy) return
        _uiState.update { it.copy(error = null) }
        player.play(audio, fromMs)
    }

    /** The audio of this page starts or stops playing: it is in use while it plays. */
    private fun markPlaying(playing: Boolean) {
        if (playing == playingInUse) return
        playingInUse = playing
        if (playing) audioUsage.begin(sessionId) else audioUsage.end(sessionId)
    }

    // ========== Keeping and deleting the audio ==========

    /**
     * Keeps the audio as it is, out of the settings that compress or delete older audio, or stops
     * keeping it ([kept] false). The audio is read again afterwards, so the page shows what was
     * stored; a choice that wasn't stored is reported.
     */
    fun setKept(kept: Boolean) {
        viewModelScope.launch {
            audioRepository.setKept(sessionId, kept)
            val audio = audioRepository.find(sessionId)
            _uiState.update { it.copy(audio = audio) }
            when {
                // The audio went meanwhile; the offer goes with it.
                audio == null -> Unit
                audio.kept != kept -> _uiState.update { it.copy(error = AudioError.Keep) }
                else -> _notices.trySend(if (kept) AudioNotice.Kept else AudioNotice.Unkept)
            }
        }
    }

    /**
     * Deletes the audio of the recording; its transcript, notes and to-dos stay. Not while
     * Transcribe again reads it. What plays stops first.
     */
    fun deleteAudio() {
        if (transcribeJob?.isActive == true) return
        viewModelScope.launch {
            player.stop(sessionId)
            audioRepository.delete(sessionId)
            // Read again: a file that couldn't be deleted is still there, and still offered.
            val audio = audioRepository.find(sessionId)
            _uiState.update { it.copy(audio = audio) }
            if (audio == null) {
                Log.i(TAG, "Audio of $sessionId deleted from its page")
                _notices.trySend(AudioNotice.Deleted)
            } else {
                _uiState.update { it.copy(error = AudioError.Delete) }
            }
        }
    }

    // ========== Transcribe again ==========

    /**
     * Opens the sheet that picks the speech model ([RecordingAudioUiState.transcribeChoice]): the
     * models on the phone that know the language of the transcript there is now, each with how
     * long it takes and whether it can run now.
     */
    fun showTranscribeChoice() {
        viewModelScope.launch {
            val options = transcribeOptions() ?: return@launch
            _uiState.update { it.copy(transcribeChoice = options) }
        }
    }

    fun hideTranscribeChoice() {
        _uiState.update { it.copy(transcribeChoice = null) }
    }

    /**
     * What the sheet lists, from what is on the phone and in memory now; null without audio or
     * once the recording is gone. The languages are read off the main thread: a two-hour meeting
     * has some 20,000 words to look through.
     */
    private suspend fun transcribeOptions(): List<TranscribeOption>? {
        val audio = _uiState.value.audio ?: return null
        val session = transcriptionRepository.getSession(sessionId).first() ?: return null
        val segments = transcriptionRepository.getSegmentsBySession(sessionId).first()
        val (language, inLanguage) = withContext(Dispatchers.Default) {
            SpokenLanguage.of(segments) to TranscriptLanguage.isIn(session.language, segments)
        }
        val installed = SpeechModelId.entries.filterTo(mutableSetOf()) { speechModels.isInstalled(it) }
        return SpeechModelChoice.options(
            language = language,
            installed = installed,
            sizeBytes = speechModels::sizeBytes,
            aiModelLoaded = llmRepository.isModelInMemory,
            availableBytes = memory.availableBytes(),
            audioMs = audio.durationMs,
            current = SpeechModelId.ofTranscript(session.transcriptModel),
            mode = session.mode,
            recording = session.language,
            transcriptInLanguage = inLanguage
        )
    }

    /**
     * Makes the transcript again from the audio with [model]. Progress is in
     * [RecordingAudioUiState.transcription]; the old transcript is replaced only once the new one
     * is complete, so a failure, no speech, [cancelTranscription] or leaving the page leaves it as
     * it was. The model's readiness is looked at once more first, since the sheet opened: if it
     * can't run now (the AI model has loaded, memory has gone) the sheet stays, saying why. It
     * runs only while the AI is free, and holds it to the end, so no finishing step changes the
     * transcript under it or loads a model beside its speech model. A recorder that leaves IDLE
     * stops it ([runUntilCapture]).
     *
     * A transcript in another language than the old one ([SpeechModelChoice.makesTextAgain]: it
     * changes the language the recording is in, or the old one wasn't in it) has the recording's
     * text made again, where the page can ([makesTextAgain]: AI notes on, with a model):
     * [AudioNotice.TextToMakeAgain] says, once the AI is free again, that the page is to make its
     * name, notes, to-dos and ideas again, which takes the AI itself. Until that work has made
     * each of them, what the old transcript gave the recording stays
     * (com.satori.domain.usecase.stt.MakeTextAgainUseCase): a recording that starts, the AI
     * busy or the page left leave it as it was. A Malayalam recording's are written in English.
     *
     * @param makesTextAgain Whether the page makes the text again after a transcript in another
     *   language; without, the old text stays, as the sheet said
     */
    fun transcribeAgain(model: SpeechModelId, makesTextAgain: Boolean = false) {
        // A job that has ended counts as none, also before it has cleared itself.
        if (transcribeJob?.isActive == true || !_uiState.value.canTranscribe) return

        transcribeJob = viewModelScope.launch {
            val options = transcribeOptions()
            val option = options?.firstOrNull { it.model == model }
            if (option?.readiness != ModelReadiness.Ready) {
                _uiState.update { it.copy(transcribeChoice = options) }
                return@launch
            }
            // Asked of the transcript there is now, before the new one takes its place.
            val textAgain = makesTextAgain && option.makesTextAgain
            // Set once the new transcript is stored, which runs to its end also after a stop.
            var replaced = false
            // The speech model is freed within the transcription; there is no AI model to free.
            val ran = aiGate.runGated(AiWorkGate.TRANSCRIBE, refused = { finishingQueue.busy.value }, release = {}) {
                activeRecorder.current.runUntilCapture(onStopped = { if (!replaced) stoppedForRecording() }) {
                    _uiState.update {
                        it.copy(transcribeChoice = null, transcription = TranscriptionProgress(model = model), error = null)
                    }
                    transcribe(model) { replaced = true }
                }
            }
            if (!ran) _uiState.update { it.copy(transcribeChoice = null, error = AudioError.AiBusy) }
            // After the gate opened: the page's work takes it next.
            if (replaced && textAgain) {
                Log.i(
                    TAG,
                    "Recording $sessionId transcribed again by ${model.id}: in ${RecordingLanguage.transcribedBy(model)} now, " +
                        "its name, notes, to-dos and ideas to be made again"
                )
                _notices.trySend(AudioNotice.TextToMakeAgain)
            }
        }
    }

    /**
     * A recording started and stopped Transcribe again before the new transcript was stored: the
     * transcript stays as it was, which the recording's page, opened again, says once
     * ([StoppedWork]). A quick-key start closes this one meanwhile.
     */
    private fun stoppedForRecording() {
        Log.i(TAG, "Transcribe again of $sessionId stopped: a recording started; the transcript stays")
        stoppedWork.post(StoppedWork.Kind.TRANSCRIBE_AGAIN, sessionId, poster = this)
    }

    /**
     * @param onReplaced Called once the new transcript is stored, before a cancel can come back instead of the result
     */
    private suspend fun transcribe(model: SpeechModelId, onReplaced: () -> Unit) {
        audioUsage.begin(sessionId)
        try {
            // A long recording takes minutes: the service keeps the process and the CPU going
            // with the phone locked. Inside the try, so a refused start still ends below.
            processingService.startProcessing(ProcessingHolders.transcribe(sessionId))
            // Off the main thread, as it reads the language of the whole transcript too.
            val result = withContext(Dispatchers.Default) {
                transcribeAgainUseCase(sessionId, model) { fraction ->
                    _uiState.update { it.copy(transcription = it.transcription?.copy(fraction = fraction)) }
                }.also { if (it.getOrNull() == TranscribeAgainOutcome.Replaced) onReplaced() }
            }
            // A cancel can come back as a failed Result; it must not be reported as a failure.
            currentCoroutineContext().ensureActive()
            result.onSuccess { outcome ->
                when (outcome) {
                    TranscribeAgainOutcome.Replaced -> _notices.trySend(AudioNotice.Transcribed)
                    TranscribeAgainOutcome.NoSpeech -> _uiState.update { it.copy(error = AudioError.NoSpeech) }
                    // The audio went meanwhile; reading it again below takes the offer away.
                    TranscribeAgainOutcome.NoAudio -> Unit
                }
            }.onFailure { e ->
                Log.e(TAG, "Transcribing the recording again with ${model.id} failed", e)
                _uiState.update { it.copy(error = AudioError.TranscribeFailed) }
            }
            // The new lines were placed in the audio's index.
            val audio = audioRepository.find(sessionId)
            _uiState.update { it.copy(audio = audio) }
        } finally {
            audioUsage.end(sessionId)
            _uiState.update { it.copy(transcription = null) }
            processingService.stopProcessing(ProcessingHolders.transcribe(sessionId))
        }
    }

    /** Stops the running Transcribe again. The transcript stays as it was. */
    fun cancelTranscription() {
        val job = transcribeJob?.takeIf { it.isActive } ?: return
        _uiState.update { it.copy(transcription = it.transcription?.copy(stopping = true)) }
        job.cancel()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        player.stop(sessionId)
        // The collector that would see the stop is gone with the scope.
        markPlaying(false)
    }

    private companion object {
        const val TAG = "RecordingAudioVM"
        val NOT_LOADED = RecordingPlayer.State()
    }
}
