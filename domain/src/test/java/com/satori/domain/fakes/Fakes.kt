package com.satori.domain.fakes

import com.satori.domain.model.AppSettings
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SearchResult
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Answers every prompt with [answer], split into a few tokens; records what it was asked. With
 * [answers], the calls get them in turn, round and round: since 0.7.3 a note that repeats an
 * earlier one is none, so the parts of a pass need answers of their own.
 */
class FakeLlmRepository(
    var answer: String = "",
    var failWith: Exception? = null,
    var reloadFailure: Exception? = null,
    private val answers: List<String> = emptyList()
) : LlmRepository {
    val prompts = mutableListOf<Pair<String, String?>>()
    var reloads = 0

    /** The grammar each call asked for, in call order. */
    val grammars = mutableListOf<OutputGrammar?>()

    override fun generateInsight(
        text: String,
        systemPrompt: String?,
        maxTokens: Int,
        grammar: OutputGrammar?
    ): Flow<String> = flow {
        val given = if (answers.isEmpty()) answer else answers[prompts.size % answers.size]
        prompts += text to systemPrompt
        grammars += grammar
        failWith?.let { throw it }
        given.chunked(4).forEach { emit(it) }
    }

    override suspend fun reloadModel(): Result<Unit> {
        reloads++
        return reloadFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /** The files [switchModel] was asked for, in call order. */
    val switches = mutableListOf<String>()

    override suspend fun switchModel(modelPath: String): Result<Unit> {
        switches += modelPath
        return Result.success(Unit)
    }

    override suspend fun initialize(modelPath: String, systemPrompt: String?, loadImmediately: Boolean) = Result.success(Unit)
    override fun isMemoryConstrained() = false
    override suspend fun updateSystemPrompt(systemPrompt: String) = Result.success(Unit)
    override fun useConservativeThreads() = Unit
    override suspend fun checkAndCacheMemoryConstraint() = Unit
    override suspend fun recordConstrainedInference(inputChars: Int) = Unit
    override fun isLargeContext(inputChars: Int) = false
    override val isGenerating: Boolean = false
    override fun beginInference() = Unit
    override fun endInference() = Unit
    override suspend fun cleanup() = Unit
    override val thermalThrottleFlow: Flow<ThermalThrottle> = flowOf(ThermalThrottle.Normal)
}

/**
 * Returns prompts such as "title prompt (de)" or "short_meeting prompt (en)", so tests can see
 * which language was asked for, and the English JSON field names.
 */
class FakeResourceProvider : ResourceProvider {
    override fun getSessionTitlePrompt(localeCode: String) = "title prompt ($localeCode)"
    override fun getTodoExtractionPrompt(localeCode: String) =
        "todo prompt ($localeCode): started {date} at {time}; noted: {noted}"
    override fun getMindTopicsPrompt(localeCode: String) = "mind topics prompt ($localeCode)"
    override fun getMindSummaryPrompt(localeCode: String) = "mind summary prompt ($localeCode)"
    override fun getPromptForMode(mode: RecordingMode, localeCode: String) =
        "${mode.name.lowercase()} prompt ($localeCode)"
    override fun getJsonFieldTitle(localeCode: String) = "title"
    override fun getJsonFieldPoints(localeCode: String) = "points"
    override fun getJsonFieldSummary(localeCode: String) = "summary"
    override fun getJsonFieldActionItems(localeCode: String) = "action_items"

    override fun getSimpleListeningPrompt() = unused()
    override fun getShortMeetingPrompt() = unused()
    override fun getLongMeetingPrompt() = unused()
    override fun getTranslationPrompt() = unused()
    override fun getJsonFieldTitle() = "title"
    override fun getJsonFieldPoints() = "points"
    override fun getJsonFieldSummary() = "summary"
    override fun getJsonFieldActionItems() = "action_items"
    override fun getTopicPrefix(localeCode: String) = unused()

    private fun unused(): String = error("not used by these tests")
}

/** Holds [settings] in memory. */
class FakeSettingsRepository(var settings: AppSettings = AppSettings()) : SettingsRepository {
    override fun getSettings(): Flow<AppSettings> = flowOf(settings)
    override suspend fun updateSettings(settings: AppSettings) {
        this.settings = settings
    }

    override suspend fun setHomeCategoryId(id: String): Unit = unused()
    override suspend fun setMemoryConstrainedDetected(detected: Boolean): Unit = unused()
    override suspend fun lowerConservativeThreadsCharThreshold(chars: Int): Unit = unused()
    override suspend fun clearLlmModelPath(): Unit = unused()

    private fun unused(): Nothing = error("not used by these tests")
}

/** One session, its segments and insights, held in memory. */
class FakeTranscriptionRepository(
    var session: TranscriptionSession?,
    var segments: List<TranscriptionSegment> = emptyList(),
    var insights: List<LlmInsight> = emptyList()
) : TranscriptionRepository {
    /** Set to simulate the user typing a name between the read and the write. */
    var nameTypedMeanwhile: String? = null
    var conditionalWrites = 0

    /** Set to make [replaceInsights] fail; as in the database, the old insights then stay. */
    var replaceFailure: Exception? = null
    var replaceCalls = 0

    /** Set to make [deleteSession] fail. */
    var deleteFailure: Exception? = null

    /** Set to make [replaceSegments] fail; as in the database, the old segments then stay. */
    var replaceSegmentsFailure: Exception? = null

    override fun getSession(sessionId: String): Flow<TranscriptionSession?> =
        flowOf(session?.takeIf { it.id == sessionId })

    override fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>> =
        flowOf(segments.filter { it.sessionId == sessionId })

    override fun getInsightsBySession(sessionId: String): Flow<List<LlmInsight>> =
        flowOf(insights.filter { it.sessionId == sessionId })

    override suspend fun nameSessionIfUnnamed(sessionId: String, name: String): Result<Boolean> {
        conditionalWrites++
        nameTypedMeanwhile?.let { typed -> session = session?.copy(name = typed, nameByOwner = true) }
        val current = session?.takeIf { it.id == sessionId } ?: return Result.success(false)
        if (!current.name.isNullOrBlank()) return Result.success(false)
        session = current.copy(name = name.trim(), nameByOwner = false)
        return Result.success(true)
    }

    /**
     * As in the database: only while the name is still [expected] and the app's, not one the owner
     * typed ([TranscriptionSession.nameByOwner]); [nameTypedMeanwhile] comes first.
     */
    override suspend fun replaceSessionName(sessionId: String, expected: String, name: String): Result<Boolean> {
        conditionalWrites++
        nameTypedMeanwhile?.let { typed -> session = session?.copy(name = typed, nameByOwner = true) }
        val current = session?.takeIf { it.id == sessionId } ?: return Result.success(false)
        if (current.name != expected || current.nameByOwner || name.isBlank()) return Result.success(false)
        session = current.copy(name = name.trim(), nameByOwner = false)
        return Result.success(true)
    }

    override suspend fun setOutputLanguageIfUnset(sessionId: String, language: String): Result<Boolean> {
        val current = session?.takeIf { it.id == sessionId } ?: return Result.success(false)
        if (!current.outputLanguage.isNullOrBlank()) return Result.success(false)
        session = current.copy(outputLanguage = language.trim())
        return Result.success(true)
    }

    override suspend fun saveInsight(insight: LlmInsight): Result<Unit> {
        insights = insights + insight
        return Result.success(Unit)
    }

    override suspend fun replaceInsights(
        sessionId: String,
        insights: List<LlmInsight>,
        strategy: InsightStrategy
    ): Result<Unit> {
        replaceCalls++
        replaceFailure?.let { return Result.failure(it) }
        this.insights = this.insights.filter { it.sessionId != sessionId } + insights
        session = session?.takeIf { it.id == sessionId }?.copy(insightStrategy = strategy) ?: session
        return Result.success(Unit)
    }

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
    override fun getAllSessions(): Flow<List<SessionListItem>> = unused()
    override fun getSessionWithDetails(sessionId: String): Flow<SessionWithDetails?> = unused()
    override suspend fun deleteSession(sessionId: String): Result<Unit> {
        deleteFailure?.let { return Result.failure(it) }
        if (session?.id == sessionId) {
            session = null
            segments = emptyList()
            insights = emptyList()
            readings = emptyList()
        }
        return Result.success(Unit)
    }
    override suspend fun renameSession(sessionId: String, newName: String?): Result<Unit> = unused()
    override suspend fun updateSessionDuration(sessionId: String, durationMs: Long): Result<Unit> = unused()

    /** As in the database: a place off the earth fails, a blank name is none, another session is left alone. */
    override suspend fun setPlace(sessionId: String, place: RecordingPlace?): Result<Unit> {
        if (place != null && !place.isOnEarth) return Result.failure(IllegalArgumentException("Not a place on the earth"))
        val stored = place?.copy(name = place.name?.trim()?.takeIf { it.isNotEmpty() })
        session = session?.takeIf { it.id == sessionId }?.copy(place = stored) ?: session
        return Result.success(Unit)
    }

    /** As in the database: the spoken language of this session, another session is left alone. */
    override suspend fun setInputLanguage(sessionId: String, code: String): Result<Unit> {
        session = session?.takeIf { it.id == sessionId }?.copy(inputLanguage = code) ?: session
        return Result.success(Unit)
    }

    /** As in the database: a blank id is none, another session is left alone. */
    override suspend fun setTranscriptModel(sessionId: String, modelId: String?): Result<Unit> {
        val stored = modelId?.trim()?.takeIf { it.isNotEmpty() }
        session = session?.takeIf { it.id == sessionId }?.copy(transcriptModel = stored) ?: session
        return Result.success(Unit)
    }

    /** Each [setCategory] asked for: the session, the category and the mode, in order. */
    val categoryWrites = mutableListOf<Triple<String, String, RecordingMode>>()

    /** Set to make [setCategory] fail; as in the database, the session then keeps what it had. */
    var categoryFailure: Exception? = null

    /**
     * As in the database: the category and the mode in one write, none for another session or a
     * missing one, never for a translation or into one. The categories themselves aren't held here.
     */
    override suspend fun setCategory(sessionId: String, categoryId: String, mode: RecordingMode): Result<Boolean> {
        categoryWrites += Triple(sessionId, categoryId, mode)
        categoryFailure?.let { return Result.failure(it) }
        val current = session?.takeIf { it.id == sessionId } ?: return Result.success(false)
        val translation = RecordingMode.REAL_TIME_TRANSLATION
        if (current.mode == translation || mode == translation) return Result.success(false)
        session = current.copy(categoryId = categoryId, mode = mode)
        return Result.success(true)
    }

    override suspend fun deleteAllSessions(): Result<Unit> = unused()
    /** The to-do sources the last [replaceSegments] was given; to-dos are not held here. */
    var replacedTodoSources: Map<String, List<String>>? = null

    /**
     * The source lines of the session's mind-map links, by node id ([getLinkSources]); a
     * [replaceSegments] points those it is given at the new lines, as the database does.
     */
    var linkSources: Map<String, List<String>> = emptyMap()

    /** As Room's suspend writes: a caller that has been cancelled gets no write, but the cancel. */
    override suspend fun replaceSegments(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        insightSources: Map<String, List<String>>,
        todoSources: Map<String, List<String>>,
        linkSources: Map<String, List<String>>
    ): Result<Unit> {
        currentCoroutineContext().ensureActive()
        replaceSegmentsFailure?.let { return Result.failure(it) }
        if (segments.any { it.sessionId != sessionId }) return Result.failure(IllegalArgumentException("Segments of another session"))
        this.segments = this.segments.filter { it.sessionId != sessionId } + segments
        // The old lines take their readings with them, as the database's foreign key does.
        readings = readings.filter { it.sessionId != sessionId }
        insights = insights.map { note ->
            insightSources[note.id]?.takeIf { note.sessionId == sessionId }?.let { note.copy(sourceSegmentIds = it) } ?: note
        }
        replacedTodoSources = todoSources
        this.linkSources = this.linkSources.mapValues { (nodeId, ids) -> linkSources[nodeId] ?: ids }
        return Result.success(Unit)
    }

    override suspend fun getLinkSources(sessionId: String): Map<String, List<String>> =
        if (session?.id == sessionId) linkSources else emptyMap()

    /**
     * The readings stored ([saveReading]), as the database keeps them: one per line, in place of
     * the line's earlier one, and none of a line that isn't one of the reading's recording. A
     * replaced transcript ([replaceSegments]) and a deleted recording take theirs with them.
     * [getReadableSegments] is the interface's own, over these and [segments].
     */
    var readings: List<SegmentReading> = emptyList()

    /** Set to make [saveReading] fail, as a full disk does. */
    var readingFailure: Exception? = null

    override suspend fun saveReading(reading: SegmentReading): Result<Unit> {
        readingFailure?.let { return Result.failure(it) }
        if (segments.none { it.id == reading.segmentId && it.sessionId == reading.sessionId }) {
            return Result.failure(IllegalStateException("No such line of the recording to store a reading of"))
        }
        readings = readings.filter { it.segmentId != reading.segmentId } + reading
        return Result.success(Unit)
    }

    override suspend fun readingsOf(sessionId: String): List<SegmentReading> = readings.filter { it.sessionId == sessionId }

    /** What [keepBeforeRedo] kept, as the database keeps it: the name and notes the recording had, the first time only. */
    var keptBeforeRedo: Pair<String?, List<LlmInsight>>? = null

    /** Set to make [keepBeforeRedo] fail, as a full disk does. */
    var keepFailure: Exception? = null

    override suspend fun keepBeforeRedo(sessionId: String): Result<Boolean> {
        keepFailure?.let { return Result.failure(it) }
        val current = session?.takeIf { it.id == sessionId } ?: return Result.success(false)
        if (keptBeforeRedo != null) return Result.success(false)
        keptBeforeRedo = current.name to insights.filter { it.sessionId == sessionId }
        return Result.success(true)
    }

    /** Whether the recording is due to be made again over its readings, as last stored ([noteRedoDue]). */
    var redoDue: Boolean? = null

    override suspend fun noteRedoDue(sessionId: String, due: Boolean): Result<Unit> {
        if (session?.id == sessionId) redoDue = due
        return Result.success(Unit)
    }

    override suspend fun saveSegment(segment: TranscriptionSegment): Result<Unit> = unused()
    override suspend fun updateSegmentText(segmentId: String, newText: String): Result<Unit> = unused()
    override suspend fun updateInsight(
        insightId: String,
        newTitle: String?,
        newContent: String,
        newTasks: String?
    ): Result<Unit> = unused()
    override fun searchTranscriptions(query: String): Flow<List<SearchResult>> = unused()

    private fun unused(): Nothing = error("not used by these tests")
}

fun session(id: String = "s1", name: String? = null) =
    TranscriptionSession(id = id, name = name, createdAt = 0L, lastModifiedAt = 0L)

fun segment(text: String, at: Long, complete: Boolean = true, sessionId: String = "s1") =
    TranscriptionSegment(id = "seg$at", sessionId = sessionId, text = text, timestamp = at, isComplete = complete)

fun insight(title: String?, content: String, at: Long, sessionId: String = "s1") =
    LlmInsight(id = "ins$at", sessionId = sessionId, title = title, content = content, timestamp = at, sourceSegmentIds = emptyList())

/**
 * Holds audio of the recordings [ids] only. With [readable] false none of it can be read, as for
 * a file the storage can't read at the time: [find] gives null, though the file is there.
 */
class KeptAudio(private vararg val ids: String, private val readable: Boolean = true) : RecordingAudioRepository {
    override suspend fun hasAudio(sessionId: String): Boolean = sessionId in ids

    override suspend fun find(sessionId: String): RecordingAudio? =
        if (readable && sessionId in ids) RecordingAudio(sessionId, path = "$sessionId.wav", durationMs = 54_000L) else null

    override suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>): Unit = unused()
    override suspend fun setKept(sessionId: String, kept: Boolean): Unit = unused()
    override suspend fun delete(sessionId: String): Unit = unused()
    override suspend fun deleteAll(): Unit = unused()

    private fun unused(): Nothing = error("not used by these tests")
}
