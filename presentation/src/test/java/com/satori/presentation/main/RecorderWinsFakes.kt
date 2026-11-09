package com.satori.presentation.main

import android.content.ContextWrapper
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.model.AppSettings
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.MindLink
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindSessionTopic
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.RecordingSessionState
import com.satori.domain.model.SearchResult
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.AudioUsage
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.SttRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.RecordingServiceController
import com.satori.domain.time.TimeCandidate
import com.satori.domain.time.TimeExpressionParser
import com.satori.domain.time.TimePolicy
import com.satori.presentation.ai.AiWorkGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// Test doubles for the view models whose AI work gives way to a recorder: the recording's page,
// its audio, and the mind map's views. They hold what that work reads and writes, each with a
// place where a test can hold it as the engine, the speech model or the database would. Some are
// called off the main thread (the views run parts of their work on Dispatchers.Default and IO), so
// what they keep is safe to share. Anything else fails the test that uses it.

private fun unused(): Nothing = error("not used by these tests")

/** The size of Gemma 4 E2B's file on the phone, for the figures of a switch [ScreenEngine] refuses. */
private const val SWITCHED_FILE_BYTES = 3_349_516_256L

/** A place where a test holds the work: [reached] once the work is there, which then waits for [resume]. */
internal class Pause {
    val reached = CompletableDeferred<Unit>()
    private val resumed = CompletableDeferred<Unit>()

    fun resume() {
        resumed.complete(Unit)
    }

    /** Called by the work; returns once resumed, or throws when the work is cancelled first. */
    suspend fun here() {
        reached.complete(Unit)
        resumed.await()
    }
}

/**
 * The phone's model folder in [dir], with the live model the settings choose by default on it, as
 * the views look for it ([ModelDownloadManager.getLlmModelPath]).
 */
internal fun liveModelIn(dir: File): ModelDownloadManager {
    val models = ModelDownloadManager(ExternalFiles(dir))
    File(dir, "models/${modelConfigForVariant(AppSettings().llmModelVariant).llmFilename}").writeBytes(ByteArray(64))
    return models
}

private class ExternalFiles(private val dir: File) : ContextWrapper(null) {
    override fun getExternalFilesDir(type: String?): File = dir
}

/**
 * The AI engine as the views' work uses it: what is registered, swapped in and freed, each logged
 * with who held the [gate] then, and every text it is asked about ([prompts]) with its system
 * prompt ([systemPrompts]). Each call answers
 * [answer]. A call waits at [call] first, if set: cancellable, as the engine's reply ends with its
 * collector, or with [callThroughCancel] to its end, as a native call does, the cancel showing at
 * the first token. [load] holds the load of Gemma 4 E2B, which no cancel cuts short either.
 *
 * A switch is measured as the app's repository measures it: [margins] gets the room beyond the
 * file it asks for (nothing for the plain switch), and one that asks for more than [roomBytes] is
 * refused with a [NotEnoughMemoryException] and logged as "no room for <file>".
 */
internal class ScreenEngine(private val gate: AiWorkGate, @Volatile var answer: String = "") : LlmRepository {
    val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val systemPrompts: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val margins: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var roomBytes: Long = Long.MAX_VALUE

    @Volatile
    var call: Pause? = null

    @Volatile
    var callThroughCancel = false

    @Volatile
    var load: Pause? = null

    override val thermalThrottleFlow: Flow<ThermalThrottle> = flowOf(ThermalThrottle.Normal)
    override val isModelInMemory: Boolean get() = false
    override val isGenerating: Boolean get() = false

    override suspend fun initialize(modelPath: String, systemPrompt: String?, loadImmediately: Boolean): Result<Unit> {
        log += "register ${File(modelPath).name}"
        return Result.success(Unit)
    }

    override suspend fun reloadModel(): Result<Unit> = Result.success(Unit)

    override suspend fun switchModel(modelPath: String): Result<Unit> = switchModel(modelPath, 0L)

    override suspend fun switchModel(modelPath: String, extraFreeBytes: Long): Result<Unit> {
        margins += extraFreeBytes
        if (extraFreeBytes > roomBytes) {
            log += "no room for ${File(modelPath).name}"
            return Result.failure(NotEnoughMemoryException(SWITCHED_FILE_BYTES + roomBytes, SWITCHED_FILE_BYTES + extraFreeBytes))
        }
        log += "switch ${File(modelPath).name}"
        load?.let { withContext(NonCancellable) { it.here() } }
        return Result.success(Unit)
    }

    override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
        prompts += text
        systemPrompts += systemPrompt
        call?.let { pause -> if (callThroughCancel) withContext(NonCancellable) { pause.here() } else pause.here() }
        emit(answer)
    }

    override suspend fun cleanup() {
        log += "cleanup under ${gate.holder.value}"
    }

    override suspend fun forgetModel() {
        log += "forget under ${gate.holder.value}"
    }

    override fun isMemoryConstrained(): Boolean = false
    override suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit> = unused()
    override fun useConservativeThreads() = Unit
    override suspend fun checkAndCacheMemoryConstraint() = Unit
    override suspend fun recordConstrainedInference(inputChars: Int) = Unit
    override fun isLargeContext(inputChars: Int): Boolean = false
    override fun beginInference(): Unit = unused()
    override fun endInference(): Unit = unused()
}

/** Gemma 4 E2B for the work after Stop at [path], or not on the phone (null). */
internal class FinishingModelChoice(private val path: String? = null) : FinishingModelRepository {
    override fun observeEnabled(): Flow<Boolean> = flowOf(path != null)
    override suspend fun setEnabled(enabled: Boolean): Unit = unused()
    override suspend fun enabledModelPath(): String? = path
}

/** The prompts and JSON keys in English, with the app's sentence for a recording's topic. */
internal class ScreenPrompts : ResourceProvider {
    override fun getSimpleListeningPrompt() = "notes prompt"
    override fun getShortMeetingPrompt() = "notes prompt"
    override fun getLongMeetingPrompt() = "notes prompt"
    override fun getTranslationPrompt() = "translation prompt"
    override fun getPromptForMode(mode: RecordingMode, localeCode: String) = "notes prompt"
    override fun getJsonFieldTitle() = "title"
    override fun getJsonFieldPoints() = "points"
    override fun getJsonFieldSummary() = "summary"
    override fun getJsonFieldActionItems() = "action_items"
    override fun getJsonFieldTitle(localeCode: String) = "title"
    override fun getJsonFieldPoints(localeCode: String) = "points"
    override fun getJsonFieldSummary(localeCode: String) = "summary"
    override fun getJsonFieldActionItems(localeCode: String) = "action_items"
    override fun getTopicPrefix(localeCode: String) = "The topic is \"%1\$s\"."
    override fun getSessionTitlePrompt(localeCode: String) = "title prompt"
    override fun getTodoExtractionPrompt(localeCode: String) = "todo prompt"
    override fun getMindTopicsPrompt(localeCode: String) = "topics prompt"
    override fun getMindSummaryPrompt(localeCode: String) = "summary prompt"
}

/** Finds no time in any text. */
internal object NoTimes : TimeExpressionParser {
    override fun find(text: String, anchor: ZonedDateTime, policy: TimePolicy): List<TimeCandidate> = emptyList()
}

/**
 * The recordings on the phone with their lines and notes, as the views and their use cases read
 * and change them. A swap of a recording's notes ([swaps]) waits at [swap], if set, and a store of
 * its new transcript ([stores]) at [store], each once it has gone through: as Room's transaction
 * commits on its own thread and the caller then goes on, where a cancel shows. A recorder saves
 * its lines, notes and length here as it records, and a switch of what a recording is recorded
 * as ([categoryWrites]) waits at [categoryStore] before it goes through, if set.
 */
internal class RecordingsOnPhone(vararg recordings: TranscriptionSession) : TranscriptionRepository {
    private val sessions = MutableStateFlow(recordings.associateBy { it.id })
    private val lines = MutableStateFlow<Map<String, List<TranscriptionSegment>>>(emptyMap())
    private val notes = MutableStateFlow<Map<String, List<LlmInsight>>>(emptyMap())

    @Volatile
    var swap: Pause? = null

    @Volatile
    var store: Pause? = null

    @Volatile
    var swaps = 0
        private set

    @Volatile
    var stores = 0
        private set

    /** Each switch of what a recording is recorded as ([setCategory]): its session, category and mode. */
    val categoryWrites: MutableList<Triple<String, String, RecordingMode>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var categoryStore: Pause? = null

    /** [said] is the transcript of [sessionId], one complete line each. */
    fun said(sessionId: String, vararg said: String) {
        lines.update { all ->
            all + (sessionId to said.mapIndexed { index, text -> TranscriptionSegment("$sessionId-$index", sessionId, text, 1_000L * (index + 1), isComplete = true) })
        }
    }

    /** [session] is on the phone, in place of the recording with its id. */
    fun recorded(session: TranscriptionSession) {
        sessions.update { it + (session.id to session) }
    }

    /** [written] are the notes of [sessionId]. */
    fun noted(sessionId: String, vararg written: LlmInsight) {
        notes.update { it + (sessionId to written.toList()) }
    }

    fun linesOf(sessionId: String): List<TranscriptionSegment> = lines.value[sessionId].orEmpty()

    fun notesOf(sessionId: String): List<LlmInsight> = notes.value[sessionId].orEmpty()

    override fun getSession(sessionId: String): Flow<TranscriptionSession?> = sessions.map { it[sessionId] }

    override fun getSessionWithDetails(sessionId: String): Flow<SessionWithDetails?> = combine(sessions, lines, notes) { all, said, written ->
        all[sessionId]?.let { SessionWithDetails(it, said[sessionId].orEmpty(), written[sessionId].orEmpty()) }
    }

    override fun getAllSessions(): Flow<List<SessionListItem>> = combine(sessions, lines, notes) { all, said, written ->
        all.values.map { SessionListItem(it, hasTranscript = !said[it.id].isNullOrEmpty(), hasNotes = !written[it.id].isNullOrEmpty()) }
    }

    override fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>> = lines.map { it[sessionId].orEmpty() }

    override fun getInsightsBySession(sessionId: String): Flow<List<LlmInsight>> = notes.map { it[sessionId].orEmpty() }

    override suspend fun replaceInsights(sessionId: String, insights: List<LlmInsight>, strategy: InsightStrategy): Result<Unit> {
        notes.update { it + (sessionId to insights) }
        swaps++
        swap?.here()
        return Result.success(Unit)
    }

    override suspend fun replaceSegments(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        insightSources: Map<String, List<String>>,
        todoSources: Map<String, List<String>>,
        linkSources: Map<String, List<String>>
    ): Result<Unit> {
        lines.update { it + (sessionId to segments) }
        stores++
        store?.here()
        return Result.success(Unit)
    }

    override suspend fun getLinkSources(sessionId: String): Map<String, List<String>> = emptyMap()
    override suspend fun setOutputLanguageIfUnset(sessionId: String, language: String): Result<Boolean> = Result.success(false)

    private val readings = MutableStateFlow<List<SegmentReading>>(emptyList())

    /** The readings stored of [sessionId]'s lines (0.8.0), in the order they were stored. */
    fun readingsIn(sessionId: String): List<SegmentReading> = readings.value.filter { it.sessionId == sessionId }

    /** As in the database: in place of the line's earlier one, and none of a line that isn't the recording's. */
    override suspend fun saveReading(reading: SegmentReading): Result<Unit> {
        if (linesOf(reading.sessionId).none { it.id == reading.segmentId }) return Result.failure(IllegalStateException("No such line"))
        readings.update { all -> all.filter { it.segmentId != reading.segmentId } + reading }
        return Result.success(Unit)
    }

    override suspend fun readingsOf(sessionId: String): List<SegmentReading> = readingsIn(sessionId)

    /** As in the database: the model of a recording that exists, a blank one as none. */
    override suspend fun setTranscriptModel(sessionId: String, modelId: String?): Result<Unit> {
        val stored = modelId?.trim()?.takeIf { it.isNotEmpty() }
        sessions.update { all -> all[sessionId]?.let { all + (sessionId to it.copy(transcriptModel = stored)) } ?: all }
        return Result.success(Unit)
    }

    /** Each switch of a recording's language ([setLanguage]): its session and language. */
    val languageWrites: MutableList<Pair<String, RecordingLanguage>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var languageStore: Pause? = null

    /** As in the database: the language of a recording that exists, as it is given. */
    override suspend fun setLanguage(sessionId: String, language: RecordingLanguage): Result<Boolean> {
        languageStore?.here()
        languageWrites += sessionId to language
        var stored = false
        sessions.update { all ->
            val current = all[sessionId]
            stored = current != null
            if (current != null) all + (sessionId to current.copy(language = language)) else all
        }
        return Result.success(stored)
    }

    /**
     * As in the database: the category and the mode in one write, none for a recording that is
     * gone, never for a translation or into one. The categories themselves aren't held here.
     */
    override suspend fun setCategory(sessionId: String, categoryId: String, mode: RecordingMode): Result<Boolean> {
        categoryStore?.here()
        categoryWrites += Triple(sessionId, categoryId, mode)
        val translation = RecordingMode.REAL_TIME_TRANSLATION
        var stored = false
        sessions.update { all ->
            val current = all[sessionId]
            stored = current != null && current.mode != translation && mode != translation
            if (stored) all + (sessionId to current!!.copy(categoryId = categoryId, mode = mode)) else all
        }
        return Result.success(stored)
    }

    /** A recorder's complete line, in place of one with its id. */
    override suspend fun saveSegment(segment: TranscriptionSegment): Result<Unit> {
        lines.update { all -> all + (segment.sessionId to all[segment.sessionId].orEmpty().filterNot { it.id == segment.id } + segment) }
        return Result.success(Unit)
    }

    /** A recorder's live note, after the notes there are. */
    override suspend fun saveInsight(insight: LlmInsight): Result<Unit> {
        notes.update { all -> all + (insight.sessionId to all[insight.sessionId].orEmpty() + insight) }
        return Result.success(Unit)
    }

    override suspend fun updateSessionDuration(sessionId: String, durationMs: Long): Result<Unit> {
        sessions.update { all -> all[sessionId]?.let { all + (sessionId to it.copy(durationMs = durationMs)) } ?: all }
        return Result.success(Unit)
    }

    /** The recording [sessionId] as it is stored now. */
    fun sessionOf(sessionId: String): TranscriptionSession? = sessions.value[sessionId]

    override suspend fun createSession(
        name: String?,
        mode: RecordingMode,
        outputLanguage: String?,
        insightStrategy: InsightStrategy,
        topic: String?,
        categoryId: String?,
        zoneId: String?,
        language: RecordingLanguage
    ): Result<TranscriptionSession> = unused()
    override suspend fun deleteSession(sessionId: String): Result<Unit> = unused()

    /** As in the database: a blank name, or none, clears it. */
    override suspend fun renameSession(sessionId: String, newName: String?): Result<Unit> {
        val name = newName?.trim()?.takeIf { it.isNotEmpty() }
        sessions.update { all -> all[sessionId]?.let { all + (sessionId to it.copy(name = name)) } ?: all }
        return Result.success(Unit)
    }

    /** As in the database: only a recording without a name gets [name]. */
    override suspend fun nameSessionIfUnnamed(sessionId: String, name: String): Result<Boolean> {
        var named = false
        sessions.update { all ->
            val current = all[sessionId]
            named = current != null && current.name.isNullOrBlank() && name.isNotBlank()
            if (named) all + (sessionId to current!!.copy(name = name.trim())) else all
        }
        return Result.success(named)
    }

    /** As in the database: only a recording whose name is still [expected] gets [name]. */
    override suspend fun replaceSessionName(sessionId: String, expected: String, name: String): Result<Boolean> {
        var renamed = false
        sessions.update { all ->
            val current = all[sessionId]
            renamed = current != null && current.name == expected && name.isNotBlank()
            if (renamed) all + (sessionId to current!!.copy(name = name.trim())) else all
        }
        return Result.success(renamed)
    }
    override suspend fun setPlace(sessionId: String, place: RecordingPlace?): Result<Unit> = unused()
    override suspend fun deleteAllSessions(): Result<Unit> = unused()
    override suspend fun updateSegmentText(segmentId: String, newText: String): Result<Unit> = unused()
    override suspend fun updateInsight(insightId: String, newTitle: String?, newContent: String, newTasks: String?): Result<Unit> = unused()
    override fun searchTranscriptions(query: String): Flow<List<SearchResult>> = unused()
}

/** The to-dos and ideas on the phone. Each one stored waits at [store], if set, once it is in. */
internal class TodosOnPhone : TodoRepository {
    private val todos = MutableStateFlow<List<Todo>>(emptyList())

    @Volatile
    var store: Pause? = null

    val stored: List<Todo> get() = todos.value

    override fun observeOpen(): Flow<List<Todo>> = withStatus(TodoStatus.OPEN)
    override fun observeSuggested(): Flow<List<Todo>> = withStatus(TodoStatus.SUGGESTED)
    override fun observeIdeas(): Flow<List<Todo>> = withStatus(TodoStatus.IDEA)
    override fun observeForSession(sessionId: String): Flow<List<Todo>> = todos.map { all -> all.filter { it.sessionId == sessionId } }
    override suspend fun get(id: String): Todo? = todos.value.firstOrNull { it.id == id }

    override suspend fun upsert(todo: Todo) {
        todos.update { all -> all.filterNot { it.id == todo.id } + todo }
        store?.here()
    }

    override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?): Unit = unused()
    override suspend fun setRemindAt(id: String, remindAt: Long?): Unit = unused()

    override suspend fun delete(id: String) {
        todos.update { all -> all.filterNot { it.id == id } }
    }
    override suspend fun dueReminders(atMs: Long): List<Todo> = unused()
    override suspend fun markReminderShown(id: String, atMs: Long): Unit = unused()
    override suspend fun nextReminderAfter(afterMs: Long): Todo? = unused()

    private fun withStatus(status: TodoStatus): Flow<List<Todo>> = todos.map { all -> all.filter { it.status == status } }
}

/**
 * The mind map, empty, with [waiting] recordings to map. A recording read without topics no
 * longer waits ([MindMapRepository.markReadWithoutTopics]).
 */
internal class MapOnPhone(vararg waiting: String) : MindMapRepository {
    val unmapped = MutableStateFlow(waiting.toList())

    override fun themes(): Flow<List<MindNodeItem>> = flowOf(emptyList())
    override fun children(parentId: String): Flow<List<MindNodeItem>> = flowOf(emptyList())
    override fun node(id: String): Flow<MindNode?> = flowOf(null)
    override fun links(nodeId: String): Flow<List<MindLink>> = flowOf(emptyList())
    override fun nodesForSession(sessionId: String): Flow<List<MindSessionTopic>> = flowOf(emptyList())
    override fun allNodes(): Flow<List<MindNode>> = flowOf(emptyList())
    override fun unmappedSessionIds(): Flow<List<String>> = unmapped

    override suspend fun markReadWithoutTopics(sessionId: String, chars: Int) {
        unmapped.update { it - sessionId }
    }

    override suspend fun forgetSession(sessionId: String) = Unit
    override suspend fun cleanup() = Unit
    override suspend fun upsertNode(node: MindNode): Unit = unused()
    override suspend fun link(topicId: String, sessionId: String, points: List<String>, segmentIds: List<String>, at: Long): Unit = unused()
    override suspend fun setSummary(nodeId: String, summary: String, at: Long): Unit = unused()
    override suspend fun markStale(nodeIds: Collection<String>): Unit = unused()
    override suspend fun setPinned(nodeId: String, pinned: Boolean): Unit = unused()
}

/** No recording service runs. */
internal class NoRecordingService : RecordingServiceController {
    override val sessionState: StateFlow<RecordingSessionState> = MutableStateFlow(RecordingSessionState.Idle)
    override val stopRequests: Flow<Unit> = emptyFlow()
    override fun registerTranscriptionFlow(flow: Flow<TranscriptionSegment>): Long = unused()
    override fun startService(): Boolean = unused()
    override fun stopService(registration: Long): Unit = unused()
    override fun isServiceRunning(): Boolean = unused()
}

/** The speech models [installed] on the phone, each of the size of Parakeet v3. */
internal class SpeechModelsOnPhone(private vararg val installed: SpeechModelId) : SpeechModelRepository {
    override suspend fun isInstalled(model: SpeechModelId): Boolean = model in installed
    override fun sizeBytes(model: SpeechModelId): Long = 640L * 1024 * 1024
}

/**
 * The speech model as Transcribe again uses it: it hears [heard] in the audio, after waiting at
 * [recognition], if set, cancellable, as the decoding stops after the stretch it is on.
 */
internal class SpeechOnPhone(private val heard: List<SpokenLine>) : SttRepository {
    @Volatile
    var recognition: Pause? = null

    override val captureState: StateFlow<CaptureState> = MutableStateFlow(CaptureState.IDLE)
    override val audioLevel: StateFlow<Float> = MutableStateFlow(0f)

    override suspend fun transcribeAudio(audio: RecordingAudio, model: SpeechModelId, language: String?, onProgress: (Float) -> Unit): List<SpokenLine> {
        recognition?.here()
        return heard
    }

    override suspend fun initialize(): Result<Unit> = unused()
    override fun startStreaming(sessionId: String, keepAudio: Boolean, language: CaptureLanguage): Flow<TranscriptionSegment> = unused()
    override fun mayHaveHeardSpeech(): Boolean = unused()
    override suspend fun stopStreaming(): Unit = unused()
    override suspend fun releaseModel(): Unit = unused()
}

/** The kept [audio] of one recording; the places of its new lines are taken ([placed]). */
internal class KeptAudio(private val audio: RecordingAudio) : RecordingAudioRepository {
    val placed: MutableList<Map<String, AudioSpan>> = Collections.synchronizedList(mutableListOf())

    override suspend fun find(sessionId: String): RecordingAudio? = audio.takeIf { it.sessionId == sessionId }
    override suspend fun hasAudio(sessionId: String): Boolean = sessionId == audio.sessionId

    override suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>) {
        placed += lines
    }

    override suspend fun setKept(sessionId: String, kept: Boolean): Unit = unused()
    override suspend fun delete(sessionId: String): Unit = unused()
    override suspend fun deleteAll(): Unit = unused()
}

/** Which recordings' audio is in use, as the settings that compress or delete older audio ask. */
internal class AudioInUse : AudioUsage {
    private val uses = ConcurrentHashMap<String, Int>()

    override fun begin(sessionId: String) {
        uses.merge(sessionId, 1, Int::plus)
    }

    override fun end(sessionId: String) {
        uses.computeIfPresent(sessionId) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    override fun inUse(): Set<String> = uses.keys.toSet()
}
