package com.satori.domain.repository

import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for Speech-To-Text operations.
 *
 * Provides streaming transcription from audio input using Sherpa-ONNX models.
 */
interface SttRepository {
    /** Live input loudness in 0..1 while recording, 0 otherwise. */
    val audioLevel: StateFlow<Float>

    /**
     * How far the capture of [startStreaming] has come: [CaptureState.OPENING] as it begins,
     * [CaptureState.LISTENING] once the microphone delivers audio, [CaptureState.TRANSCRIBING]
     * once that audio is transcribed too, and [CaptureState.IDLE] when the stream ends. A capture
     * cut short by a newer one leaves the state to that one.
     */
    val captureState: StateFlow<CaptureState>

    /**
     * Load the STT engine's model, which the data layer finds on its own.
     *
     * @return Result indicating success or failure
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Start streaming audio recording and transcription.
     *
     * With [keepAudio] the capture also keeps what the recognizer hears as the audio of
     * [sessionId] ([RecordingAudioRepository]), after the audio of an earlier capture of it; a
     * failing write only costs the audio, never the recording. Without, any audio an earlier
     * capture of it kept is deleted, so the recording keeps all of its audio or none.
     *
     * The capture transcribes with the live model of the recording's [language]
     * ([com.satori.domain.model.RecordingLanguage.liveModel]), which it reads as it loads
     * the model and again between two utterances: after a switch the model in memory is freed and
     * the new language's loaded in its place, while the microphone records on and the audio waits
     * to be transcribed, so nothing said is lost. A model that doesn't load then leaves the capture
     * on the one it had ([CaptureLanguage.refused]); only when neither loads does the stream fail,
     * with a [SpeechModelLoadException], as it does when the model doesn't load at the start.
     *
     * @param sessionId The recording the capture belongs to
     * @param keepAudio Whether to keep its audio ([com.satori.domain.model.AppSettings.keepAudio])
     * @param language The language of the recording, as the recorder holds it
     * @return Flow of transcription segments. Flow never completes until stopStreaming is called.
     *   Each complete segment's id is the one its place in the audio is kept under.
     */
    fun startStreaming(
        sessionId: String,
        keepAudio: Boolean,
        language: CaptureLanguage = CaptureLanguage.ENGLISH_OR_GERMAN
    ): Flow<TranscriptionSegment>

    /**
     * Whether the speech recognition of the capture that runs, or ran last ([startStreaming]),
     * heard speech, or may have, whatever reached the recording's lines: a result it gave out,
     * complete or partial, holds a letter or a digit; it stopped within an utterance whose line
     * it had not given out yet (the capture was cut off as its recorder closed); a stretch of
     * the audio was never transcribed, as a step of it failed (the stream goes on without that
     * stretch) or found the speech model released; or audio it recorded is still waiting for the
     * recognition (the microphone opens before the speech model is in). False before the first
     * capture.
     *
     * A recorder keeps a recording for which this is true even if no saved line holds a word
     * (DiscardSilentRecordingUseCase): a line can be lost on its way to the database, and audio
     * that was never transcribed may hold what was said.
     */
    fun mayHaveHeardSpeech(): Boolean

    /**
     * Stop streaming and release audio resources.
     */
    suspend fun stopStreaming()

    /**
     * Release the live speech model from memory: some 670 MB for Parakeet v3, a fifth of that for
     * the Malayalam model.
     *
     * Safe to call after stopStreaming() completes. The next capture loads the model of its
     * language again.
     */
    suspend fun releaseModel()

    /**
     * Whether a live speech model is in memory: from its load ([initialize], or a capture of
     * [startStreaming] that finds none) until [releaseModel]. The finisher of recordings waits for
     * it to be out before it loads Gemma 4 E2B with no capture live, as a recorder that closes
     * frees it. The default says it never is, for repositories that don't follow it.
     */
    val liveModelLoaded: StateFlow<Boolean>
        get() = MutableStateFlow(false)

    /**
     * How much more memory the live speech model may still take while a capture records, beyond
     * the one in memory now, by the speech models' file sizes: with the Malayalam model in, a
     * switch of the recording back to English or German frees it and loads Parakeet v3 in its
     * place, about half a gigabyte more, and a recorder's load never waits for memory. 0 with Parakeet v3
     * in; the largest live model's whole size with none. The finisher of recordings measures Gemma
     * 4 E2B's room beside a capture with this kept free as well. The default says it can't grow,
     * for repositories that don't follow it.
     */
    fun liveModelGrowthBytes(): Long = 0L

    /**
     * Transcribes kept audio again, offline, with [model] and the voice detection for a whole file
     * ([com.satori.domain.model.VadProfile.FILE]): the recognizer and VAD path of a
     * recording, fed from the file as fast as the phone allows. It loads a speech model of its own
     * and releases it at the end, so it never takes the one a recorder holds. One runs at a time; a
     * second call waits.
     *
     * Cancellable: it stops after the segment being decoded.
     *
     * @param model The speech model to transcribe with; its files must be on the phone
     * @param language The language spoken ("en", "de", ...) or null when it isn't known. A model
     *   that has to be told it ([SpeechModelId.languagePerStream]) is told this one, and fails
     *   without one; the others find it themselves or know only one.
     * @param onProgress How much of the audio has been read, 0..1; called from a background thread
     * @return The lines, in spoken order; empty if no speech was found
     * @throws Exception if the file can't be read or the model fails anywhere. A transcript with
     *   a line missing is never returned, since it would replace a complete one.
     */
    suspend fun transcribeAudio(
        audio: RecordingAudio,
        model: SpeechModelId,
        language: String?,
        onProgress: (Float) -> Unit
    ): List<SpokenLine>
}
