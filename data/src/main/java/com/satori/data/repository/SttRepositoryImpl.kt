package com.satori.data.repository

import com.satori.data.audio.RecordingAudioStore
import com.satori.data.config.SpeechModels
import com.satori.data.datasource.SttDataSource
import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.SttRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Implementation of SttRepository that bridges data source to domain layer.
 *
 * @property sttDataSource Data source for STT operations
 * @property audioStore Where the audio of recordings is kept
 */
class SttRepositoryImpl @Inject constructor(
    private val sttDataSource: SttDataSource,
    private val audioStore: RecordingAudioStore
) : SttRepository {

    // One file transcription at a time: each loads a speech model of its own (0.7-2.9 GB).
    private val transcribeLock = Mutex()

    override val audioLevel: StateFlow<Float>
        get() = sttDataSource.audioLevel

    override val captureState: StateFlow<CaptureState>
        get() = sttDataSource.captureState

    override suspend fun initialize(): Result<Unit> {
        return sttDataSource.initialize()
    }

    override fun startStreaming(sessionId: String, keepAudio: Boolean, language: CaptureLanguage): Flow<TranscriptionSegment> {
        val audio = audioStore.files(sessionId)
        return sttDataSource.startRecording(audio?.takeIf { keepAudio }, language)
            // A capture that keeps no audio takes that of the earlier ones with it, so the
            // recording never has audio for only part of what it says.
            .onStart { if (!keepAudio && audio != null) audioStore.delete(sessionId) }
            .map { result ->
                TranscriptionSegment(
                    // The id the line's place in the audio is kept under.
                    id = result.id,
                    sessionId = "", // Temporary - MainViewModel will replace with actual sessionId
                    text = result.text,
                    timestamp = System.currentTimeMillis(),
                    isComplete = result.isComplete
                )
            }
    }

    override fun mayHaveHeardSpeech(): Boolean = sttDataSource.mayHaveHeardSpeech()

    override suspend fun stopStreaming() {
        sttDataSource.stopRecording()
    }

    override suspend fun releaseModel() {
        sttDataSource.releaseModel()
    }

    override val liveModelLoaded: StateFlow<Boolean>
        get() = sttDataSource.liveModelLoaded

    /**
     * By the registry's file sizes ([SpeechModels]), which the debug memory lines put near what each
     * model holds in memory: the largest live model's, less the one in memory's.
     */
    override fun liveModelGrowthBytes(): Long {
        val largest = RecordingLanguage.LIVE_MODELS.maxOf { SpeechModels.of(it).bytes }
        val inMemory = sttDataSource.liveModelInMemory ?: return largest
        return (largest - SpeechModels.of(inMemory).bytes).coerceAtLeast(0L)
    }

    /**
     * Compressed audio is first decoded into a temporary WAV (`cache/transcribe/<id>.wav`, 16 kHz
     * mono), which the speech recognizer reads as it reads a recording's own and which is deleted
     * once it is done, also when it fails or is cancelled; one a process death left behind goes
     * with the next. The places of its lines are those in the decoded audio.
     */
    override suspend fun transcribeAudio(
        audio: RecordingAudio,
        model: SpeechModelId,
        language: String?,
        onProgress: (Float) -> Unit
    ): List<SpokenLine> =
        transcribeLock.withLock {
            val decoded = audio.encoding == AudioEncoding.AAC
            val wav = if (decoded) audioStore.decodedCopy(audio) else File(audio.path)
            try {
                sttDataSource.transcribeFile(wav, model, language, onProgress).map { result ->
                    SpokenLine(result.text, checkNotNull(result.span) { "An utterance without its place in the audio" })
                }
            } finally {
                if (decoded) withContext(NonCancellable + Dispatchers.IO) { wav.delete() }
            }
        }
}
