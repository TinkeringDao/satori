package com.satori.data.datasource

import com.satori.data.audio.RecordingAudioFiles
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.SpeechModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/**
 * Data source interface for Speech-To-Text operations.
 *
 * Provides low-level audio recording and recognition functionality.
 */
interface SttDataSource {
    /**
     * Loudness of the most recent 100 ms of captured audio, 0..1 on a 40 dB scale that puts normal
     * speech mid-range for the microphone input in use (-50..-10 dBFS for the general input, 15 dB
     * lower for the speech recognition input, which has no automatic gain). 0 while not
     * recording. Drives the live level display.
     */
    val audioLevel: StateFlow<Float>

    /**
     * How far the capture of [startRecording] has come (see SttRepository.captureState):
     * [CaptureState.OPENING] as it begins, [CaptureState.LISTENING] at the first audio from the
     * microphone (or the first chunk of a debug feed), [CaptureState.TRANSCRIBING] once that is
     * reached and the recognizer and VAD are ready, and [CaptureState.IDLE] when its flow ends,
     * unless a newer capture has begun meanwhile.
     */
    val captureState: StateFlow<CaptureState>

    /**
     * Load the STT engine's model. The engine finds the model files itself, so a missing or
     * unusable model is reported here rather than in the middle of a recording.
     *
     * @return Result indicating success or failure
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Start audio recording and real-time recognition.
     *
     * @param audio Where to keep what the recognizer hears, after the audio of an earlier capture
     *   of the same recording ([com.satori.data.audio.CaptureAudioWriter]); null keeps
     *   none. Writing never holds up or breaks the capture.
     * @param language The recording's language, whose live model transcribes it
     *   ([com.satori.domain.model.RecordingLanguage.liveModel]); read as the model loads and
     *   between two utterances, where a switch frees the model and loads the other one (see
     *   SttRepository.startStreaming)
     * @return Flow of recognition results
     */
    fun startRecording(
        audio: RecordingAudioFiles?,
        language: CaptureLanguage = CaptureLanguage.ENGLISH_OR_GERMAN
    ): Flow<RecognitionResult>

    /**
     * Whether the recognition of the capture that runs, or ran last ([startRecording]), heard
     * speech, or may have: a result it gave out holds a letter or a digit, it stopped within an
     * utterance whose line was not out yet, a stretch of the audio was never transcribed, or audio
     * recorded is still waiting for the recognition (see SttRepository.mayHaveHeardSpeech). False
     * before the first capture.
     */
    fun mayHaveHeardSpeech(): Boolean

    /**
     * Stop audio recording and release resources.
     */
    suspend fun stopRecording()

    /**
     * Release the live speech model from memory (Parakeet v3's some 670 MB, or the Malayalam
     * model's fifth of that).
     *
     * Safe to call after stopRecording() completes. The next capture loads the model of its
     * language again.
     */
    suspend fun releaseModel()

    /**
     * Whether a live speech model is in memory, from its load until [releaseModel] (see
     * SttRepository.liveModelLoaded). The default says it never is.
     */
    val liveModelLoaded: StateFlow<Boolean>
        get() = MutableStateFlow(false)

    /**
     * The live speech model in memory now, of the language a capture records in ([liveModelLoaded]
     * says whether one is), or null. The default says none is.
     */
    val liveModelInMemory: SpeechModelId?
        get() = null

    /**
     * Recognizes a WAV file Satori wrote ([com.satori.data.audio.Wav]) as a recording is
     * recognized, VAD and all, but as fast as the phone allows, with complete utterances only and
     * the voice detection for a whole file ([com.satori.domain.model.VadProfile.FILE]).
     * Uses a speech model and a VAD of its own, released at the end, so the one a recorder holds is
     * never touched.
     *
     * Cancellable: it stops after the utterance being decoded.
     *
     * @param model The speech model to recognize with; its files must be on the phone
     * @param language The language spoken, for a model that is told it ([SpeechModelId.languagePerStream])
     * @param onProgress How much of the file has been read, 0..1; called from a background thread
     * @return The complete utterances in order, each with its [RecognitionResult.span]
     * @throws Exception if the file can't be read, the model can't be loaded or fails on any part
     *   of it, or the model is told the language and none is given
     */
    suspend fun transcribeFile(
        wav: File,
        model: SpeechModelId,
        language: String?,
        onProgress: (Float) -> Unit
    ): List<RecognitionResult>
}

/**
 * Result from speech recognition.
 *
 * @property text Recognized text
 * @property isComplete Whether this represents a complete utterance (speech segment ended)
 * @property id The id of the transcript line it becomes; where a complete utterance was said is
 *   kept with the recording's audio under it
 * @property span Where a complete utterance was said, from the first sample the recognizer was
 *   given; null for a partial one
 */
data class RecognitionResult(
    val text: String,
    val isComplete: Boolean,
    val id: String = UUID.randomUUID().toString(),
    val span: AudioSpan? = null
)
