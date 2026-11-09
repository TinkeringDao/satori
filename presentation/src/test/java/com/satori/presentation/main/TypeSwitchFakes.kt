package com.satori.presentation.main

import android.content.ContextWrapper
import com.satori.data.config.SpeechModels
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.model.AppSettings
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingSessionState
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.modelfile.ModelFileFormat
import com.satori.domain.place.Gazetteer
import com.satori.domain.place.GazetteerPlace
import com.satori.domain.place.LocationFix
import com.satori.domain.place.LocationProvider
import com.satori.domain.place.LocationSource
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SttRepository
import com.satori.domain.service.RecordingServiceController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

// Test doubles for the recorder while its recording is switched to another category ("Record
// as"): what it records from, writes its notes with and hands its recording to, each counting what
// is done with it, so a test can see what a switch touched. Some are called off the main thread
// (the recorder checks its model files on Dispatchers.IO), so what they keep is safe to share.

private fun unused(): Nothing = error("not used by these tests")

/** The four starter categories of a fresh install, in their order. */
internal val starterCategories = listOf(
    RecordingCategory("voice-note", "Voice note", RecordingMode.SIMPLE_LISTENING, sortOrder = 0, createdAt = 0L),
    RecordingCategory("meeting", "Meeting", RecordingMode.SHORT_MEETING, sortOrder = 1, createdAt = 0L),
    RecordingCategory("idea", "Idea", RecordingMode.SIMPLE_LISTENING, sortOrder = 2, createdAt = 0L),
    RecordingCategory("todo", "To-do", RecordingMode.SIMPLE_LISTENING, sortOrder = 3, createdAt = 0L)
)

/** A category of the owner's own that translates, as the categories screen lets him make one. */
internal val translatingCategory = RecordingCategory("trip", "Trip", RecordingMode.REAL_TIME_TRANSLATION, sortOrder = 4, createdAt = 0L)

/** The categories on the phone, which a test can change as the categories screen does. */
internal class CategoriesOnPhone(vararg categories: RecordingCategory) : CategoryRepository {
    val all = MutableStateFlow(categories.toList())

    override fun observeAll(): Flow<List<RecordingCategory>> = all
    override suspend fun get(id: String): RecordingCategory? = all.value.firstOrNull { it.id == id }

    override suspend fun delete(id: String) {
        all.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun upsert(category: RecordingCategory): Unit = unused()
    override suspend fun ensureStarterCategories(names: Map<String, String>): Unit = unused()
}

/**
 * The microphone and the speech model as the recorder uses them. Each [startStreaming] is a
 * capture whose lines a test hands in ([say]), until [stopStreaming] ends it. It counts each start,
 * each time the capture's stream is read from the start, each stop and each release of the speech
 * model: a switch of category or of language must touch none of them. The capture's [language] is
 * the recorder's, which the capture reads as it loads its model and between utterances.
 */
internal class CaptureOnPhone : SttRepository {
    val starts = AtomicInteger()
    val reads = AtomicInteger()
    val stops = AtomicInteger()
    val releases = AtomicInteger()

    /** The language the last capture was started with, as the recorder holds it. */
    @Volatile
    var language: CaptureLanguage? = null
        private set

    @Volatile
    private var lines: Channel<TranscriptionSegment>? = null

    override val captureState = MutableStateFlow(CaptureState.IDLE)
    override val audioLevel: StateFlow<Float> = MutableStateFlow(0f)

    override fun startStreaming(sessionId: String, keepAudio: Boolean, language: CaptureLanguage): Flow<TranscriptionSegment> {
        starts.incrementAndGet()
        this.language = language
        val said = Channel<TranscriptionSegment>(Channel.UNLIMITED).also { lines = it }
        return flow {
            reads.incrementAndGet()
            captureState.value = CaptureState.TRANSCRIBING
            emitAll(said)
            captureState.value = CaptureState.IDLE
        }
    }

    /** The speech model gives out [segment]. */
    fun say(segment: TranscriptionSegment) {
        checkNotNull(lines) { "No capture runs" }.trySend(segment)
    }

    override suspend fun stopStreaming() {
        stops.incrementAndGet()
        lines?.close()
    }

    override suspend fun releaseModel() {
        releases.incrementAndGet()
    }

    override fun mayHaveHeardSpeech(): Boolean = true
    override suspend fun initialize(): Result<Unit> = unused()
    override suspend fun transcribeAudio(audio: RecordingAudio, model: SpeechModelId, language: String?, onProgress: (Float) -> Unit): List<SpokenLine> =
        unused()
}

/** The recording service: it starts, and stops when it is told; how often each is counted. */
internal class ServiceOnPhone : RecordingServiceController {
    val starts = AtomicInteger()
    val stops = AtomicInteger()

    override val sessionState: StateFlow<RecordingSessionState> = MutableStateFlow(RecordingSessionState.Idle)
    override val stopRequests: Flow<Unit> = emptyFlow()
    override fun registerTranscriptionFlow(flow: Flow<TranscriptionSegment>): Long = 1L

    override fun startService(): Boolean {
        starts.incrementAndGet()
        return true
    }

    override fun stopService(registration: Long) {
        stops.incrementAndGet()
    }

    override fun isServiceRunning(): Boolean = starts.get() > stops.get()
}

/**
 * The finisher as a recorder hands its recording over: each hand-off, with the mode the recording
 * had in [recordings] as it came, which is the mode the finisher reads.
 */
internal class QueueOnPhone(private val recordings: RecordingsOnPhone) : FinishingQueue {
    val handedOff: MutableList<Pair<String, RecordingMode?>> = Collections.synchronizedList(mutableListOf())

    override val statuses = MutableStateFlow<List<FinishingStatus>>(emptyList())
    override val busy: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun captureStarted(sessionId: String) = Unit

    override suspend fun handOff(sessionId: String, recognizerFailed: Boolean) {
        handedOff += sessionId to recordings.getSession(sessionId).first()?.mode
    }

    override suspend fun cancel(sessionId: String): Unit = unused()
    override fun skipBetterTranscript(sessionId: String): Unit = unused()
    override fun resume(mayStartForeground: Boolean): Unit = unused()
    override fun runUnderWakeJob(done: () -> Unit): Unit = unused()
    override fun wakeJobStopped(): Unit = unused()
}

/**
 * The AI engine as the live notes use it: each note's call ([calls]) with its system prompt, its
 * length of answer and whether its grammar asks for a meeting's action items. The calls answer
 * [answer], then each of [later] in turn, round and round (since 0.7.3 a live note that repeats one
 * before it is none, so the notes of a recording need answers of their own); the next one waits at
 * [hold] first, if set.
 */
internal class NotesEngine(@Volatile var answer: String, private val later: List<String> = emptyList()) : LlmRepository {
    class Call(val systemPrompt: String?, val maxTokens: Int, val actionItems: Boolean)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var hold: Pause? = null

    override val thermalThrottleFlow: Flow<ThermalThrottle> = flowOf(ThermalThrottle.Normal)
    override val isModelInMemory: Boolean get() = true
    override val isGenerating: Boolean get() = false

    override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
        val schema = (grammar as? OutputGrammar.JsonSchema)?.schema.orEmpty()
        val given = listOf(answer) + later
        val reply = given[calls.size % given.size]
        calls += Call(systemPrompt, maxTokens, actionItems = "action_items" in schema)
        hold?.let { pause ->
            hold = null
            pause.here()
        }
        emit(reply)
    }

    override suspend fun initialize(modelPath: String, systemPrompt: String?, loadImmediately: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun reloadModel(): Result<Unit> = Result.success(Unit)
    override suspend fun switchModel(modelPath: String): Result<Unit> = Result.success(Unit)
    override fun isMemoryConstrained(): Boolean = false
    override suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit> = unused()
    override fun useConservativeThreads() = Unit
    override suspend fun checkAndCacheMemoryConstraint() = Unit
    override suspend fun recordConstrainedInference(inputChars: Int) = Unit
    override fun isLargeContext(inputChars: Int): Boolean = false
    override fun beginInference() = Unit
    override fun endInference() = Unit
    override suspend fun cleanup() = Unit
}

/** The prompts of [ScreenPrompts], with each mode's notes prompt named after it: "short_meeting prompt (en)". */
internal class ModePrompts : ResourceProvider by ScreenPrompts() {
    override fun getPromptForMode(mode: RecordingMode, localeCode: String) = "${mode.name.lowercase()} prompt ($localeCode)"
}

/** No recording kept its audio. */
internal object NoAudioKept : RecordingAudioRepository {
    override suspend fun find(sessionId: String): RecordingAudio? = null
    override suspend fun hasAudio(sessionId: String): Boolean = false
    override suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>): Unit = unused()
    override suspend fun setKept(sessionId: String, kept: Boolean): Unit = unused()
    override suspend fun delete(sessionId: String): Unit = unused()
    override suspend fun deleteAll(): Unit = unused()
}

/** Location is not allowed: no place is read. */
internal object NoLocation : LocationSource {
    override fun isAllowed(): Boolean = false
    override fun elapsedRealtimeNanos(): Long = 0L
    override fun lastFix(provider: LocationProvider): LocationFix? = unused()
    override suspend fun currentFix(provider: LocationProvider): LocationFix? = unused()
}

/** No place names. */
internal object NoPlaces : Gazetteer {
    override suspend fun placesNear(latitude: Double, longitude: Double, radiusMeters: Double): List<GazetteerPlace> = unused()
}

/**
 * The phone's model folder in [dir] with Parakeet v3 and the live model the settings choose, each
 * file as large as the real one and starting as it does, so the recorder's checks find them whole.
 * Sparse: they take no room on the disk.
 */
internal fun modelsOnPhone(dir: File): ModelDownloadManager {
    val models = ModelDownloadManager(ExternalFilesIn(dir))
    val live = modelConfigForVariant(AppSettings().llmModelVariant).llmSpec
    sparse(File(dir, "models/${live.name}"), live.bytes ?: live.minBytes, headerOf(live.format))
    SpeechModels.PARAKEET_V3.specs.forEach { spec ->
        sparse(File(models.speechModelDir(), spec.name), spec.bytes ?: spec.minBytes, headerOf(spec.format))
    }
    return models
}

private fun headerOf(format: ModelFileFormat): ByteArray = when (format) {
    ModelFileFormat.Gguf -> "GGUF".toByteArray(Charsets.US_ASCII)
    // The tag of ir_version, the field a serialized ModelProto starts with.
    ModelFileFormat.Onnx -> byteArrayOf(0x08)
    ModelFileFormat.Text, ModelFileFormat.Data -> "x".toByteArray(Charsets.US_ASCII)
}

private fun sparse(file: File, bytes: Long, header: ByteArray) {
    file.parentFile?.mkdirs()
    RandomAccessFile(file, "rw").use { model ->
        model.setLength(bytes)
        model.seek(0)
        model.write(header)
    }
}

private class ExternalFilesIn(private val dir: File) : ContextWrapper(null) {
    override fun getExternalFilesDir(type: String?): File = dir
}
