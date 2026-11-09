package com.satori.domain.usecase.mindmap

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.MindLink
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.MindSessionTopic
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SearchResult
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.LlmProcessingServiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** What the in-memory map needs to know about the recordings: which exist, when made, what was said. */
interface RecordingIndex {
    fun sessionIds(): List<String>
    fun recordedAt(sessionId: String): Long?
    fun segments(sessionId: String): List<TranscriptionSegment>
}

/**
 * The mind map in memory, by the rules MindMapRepository's KDoc gives: its orderings, upsertNode
 * keeping the stored kind, createdAt, pin and summary fields, links replaced on (topic,
 * recording) and dropped for a recording or topic that is gone, forgetSession keeping the nodes,
 * and cleanup removing linkless topics, then topicless themes. Its flows read the state when they
 * are collected.
 *
 * Every write is logged in [calls] ("forget s1", "upsert t1", "link t1 s1", "stale [a, b]",
 * "summary a", "cleanup"), so tests can check their order. [failOn] makes the write of that name
 * ("forget", "upsert", "link", "stale", "summary", "cleanup") throw, the way a database error would.
 */
class FakeMindMapRepository(private val recordings: RecordingIndex) : MindMapRepository {

    private class Stored(
        val topicId: String,
        val sessionId: String,
        val points: List<String>,
        val segmentIds: List<String>,
        val createdAt: Long
    )

    /** The nodes, in the order they were stored. */
    val nodes = LinkedHashMap<String, MindNode>()
    private val stored = mutableListOf<Stored>()
    val calls = mutableListOf<String>()
    var failOn: String? = null

    /** The links of [sessionId] as (topic label, points), in the order they were stored. */
    fun linksOf(sessionId: String): List<Pair<String, List<String>>> =
        stored.filter { it.sessionId == sessionId }.map { nodes.getValue(it.topicId).label to it.points }

    /** The node with [label], of [kind]. */
    fun named(label: String, kind: MindNodeKind = MindNodeKind.THEME): MindNode =
        nodes.values.single { it.label == label && it.kind == kind }

    override fun themes(): Flow<List<MindNodeItem>> =
        flow { emit(items(nodes.values.filter { it.kind == MindNodeKind.THEME })) }

    override fun children(parentId: String): Flow<List<MindNodeItem>> =
        flow { emit(items(nodes.values.filter { it.kind == MindNodeKind.TOPIC && it.parentId == parentId })) }

    override fun node(id: String): Flow<MindNode?> = flow { emit(nodes[id]) }

    override fun links(nodeId: String): Flow<List<MindLink>> = flow { emit(linksFor(nodeId)) }

    override fun nodesForSession(sessionId: String): Flow<List<MindSessionTopic>> = flow {
        emit(
            stored.filter { it.sessionId == sessionId }.map { link ->
                val topic = nodes.getValue(link.topicId)
                MindSessionTopic(topic, nodes.getValue(topic.parentId!!), link.toMindLink())
            }
        )
    }

    override fun allNodes(): Flow<List<MindNode>> = flow { emit(nodes.values.sortedBy { it.createdAt }) }

    override suspend fun upsertNode(node: MindNode) {
        write("upsert ${node.id}", "upsert")
        val existing = nodes[node.id]
        val kind = existing?.kind ?: node.kind
        if (kind == MindNodeKind.TOPIC && nodes[node.parentId]?.kind != MindNodeKind.THEME) return
        nodes[node.id] = existing?.copy(
            label = node.label,
            matchKey = node.matchKey,
            parentId = node.parentId,
            updatedAt = node.updatedAt
        ) ?: node
    }

    override suspend fun link(topicId: String, sessionId: String, points: List<String>, segmentIds: List<String>, at: Long) {
        write("link $topicId $sessionId", "link")
        if (nodes[topicId]?.kind != MindNodeKind.TOPIC || recordings.recordedAt(sessionId) == null) return
        stored.removeAll { it.topicId == topicId && it.sessionId == sessionId }
        stored += Stored(topicId, sessionId, points, segmentIds, at)
    }

    override suspend fun setSummary(nodeId: String, summary: String, at: Long) {
        write("summary $nodeId", "summary")
        val node = nodes[nodeId] ?: return
        nodes[nodeId] = node.copy(summary = summary, summaryUpdatedAt = at, updatedAt = at, summaryStale = false)
    }

    override suspend fun markStale(nodeIds: Collection<String>) {
        write("stale ${nodeIds.toList()}", "stale")
        stale(nodeIds)
    }

    override suspend fun setPinned(nodeId: String, pinned: Boolean) {
        val node = nodes[nodeId] ?: return
        nodes[nodeId] = node.copy(pinned = pinned)
    }

    override fun unmappedSessionIds(): Flow<List<String>> = flow {
        emit(
            recordings.sessionIds()
                .filter { id -> stored.none { it.sessionId == id } }
                .filter { id ->
                    recordings.segments(id).filter { it.isComplete }.sumOf { it.text.trim(' ').length } >=
                        MindMapRepository.MIN_TRANSCRIPT_CHARS
                }
                .sortedByDescending { recordings.recordedAt(it) }
        )
    }

    override suspend fun forgetSession(sessionId: String) {
        write("forget $sessionId", "forget")
        val themes = stored.filter { it.sessionId == sessionId }.mapNotNull { nodes[it.topicId]?.parentId }.toSet()
        stored.removeAll { it.sessionId == sessionId }
        stale(themes)
    }

    override suspend fun cleanup() {
        write("cleanup", "cleanup")
        nodes.values.removeAll { node -> node.kind == MindNodeKind.TOPIC && stored.none { it.topicId == node.id } }
        nodes.values.removeAll { theme ->
            theme.kind == MindNodeKind.THEME && nodes.values.none { it.kind == MindNodeKind.TOPIC && it.parentId == theme.id }
        }
    }

    private fun write(call: String, name: String) {
        calls += call
        if (failOn == name) throw IllegalStateException("database closed")
    }

    private fun stale(ids: Collection<String>) {
        for (id in ids) {
            val node = nodes[id]?.takeIf { it.kind == MindNodeKind.THEME } ?: continue
            nodes[id] = node.copy(summaryStale = true)
        }
    }

    private fun linksFor(nodeId: String): List<MindLink> {
        val node = nodes[nodeId] ?: return emptyList()
        val topicIds = if (node.kind == MindNodeKind.TOPIC) {
            setOf(nodeId)
        } else {
            nodes.values.filter { it.parentId == nodeId }.map { it.id }.toSet()
        }
        return stored.filter { it.topicId in topicIds }.map { it.toMindLink() }.sortedByDescending { it.recordedAt }
    }

    private fun items(of: List<MindNode>): List<MindNodeItem> =
        of.mapNotNull { node ->
            val links = linksFor(node.id)
            if (links.isEmpty()) null else MindNodeItem(node, links.map { it.sessionId }.distinct().size, links.maxOf { it.recordedAt })
        }.sortedWith(
            compareByDescending<MindNodeItem> { it.recordingCount }
                .thenByDescending { it.lastRecordedAt }
                .thenBy { it.node.label }
                .thenBy { it.node.id }
        )

    private fun Stored.toMindLink() =
        MindLink(topicId, sessionId, points, segmentIds, createdAt, recordedAt = recordings.recordedAt(sessionId) ?: 0L)
}

/**
 * Recordings in memory, with their segments and notes: the parts of TranscriptionRepository the
 * mind map reads, and the [RecordingIndex] the in-memory map needs. [readFailure] makes reading a
 * recording throw.
 */
class FakeRecordings : TranscriptionRepository, RecordingIndex {
    val sessions = LinkedHashMap<String, TranscriptionSession>()
    val segmentList = mutableListOf<TranscriptionSegment>()
    val insights = mutableListOf<LlmInsight>()
    var readFailure: Exception? = null

    /** Adds the recording [id], made at [at], with a segment for each of [lines], a second apart. */
    fun add(id: String, at: Long, vararg lines: String, mode: RecordingMode = RecordingMode.SIMPLE_LISTENING): TranscriptionSession {
        val session = TranscriptionSession(id = id, name = null, createdAt = at, lastModifiedAt = at, mode = mode)
        sessions[id] = session
        lines.forEachIndexed { index, line ->
            segmentList += TranscriptionSegment("$id-seg${index + 1}", id, line, at + index * 1_000L, isComplete = true)
        }
        return session
    }

    /** Adds a note to the recording [sessionId]. */
    fun note(sessionId: String, title: String?, content: String, at: Long, sources: List<String> = listOf("x")) {
        insights += LlmInsight("$sessionId-note$at", sessionId, title, content, timestamp = at, sourceSegmentIds = sources)
    }

    override fun sessionIds(): List<String> = sessions.keys.toList()
    override fun recordedAt(sessionId: String): Long? = sessions[sessionId]?.createdAt
    override fun segments(sessionId: String): List<TranscriptionSegment> = segmentList.filter { it.sessionId == sessionId }

    override fun getSession(sessionId: String): Flow<TranscriptionSession?> = flow {
        readFailure?.let { throw it }
        emit(sessions[sessionId])
    }

    override fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>> =
        flow { emit(segments(sessionId)) }

    override fun getInsightsBySession(sessionId: String): Flow<List<LlmInsight>> =
        flow { emit(insights.filter { it.sessionId == sessionId }) }

    override suspend fun setOutputLanguageIfUnset(sessionId: String, language: String): Result<Boolean> {
        val current = sessions[sessionId] ?: return Result.success(false)
        if (!current.outputLanguage.isNullOrBlank()) return Result.success(false)
        sessions[sessionId] = current.copy(outputLanguage = language)
        return Result.success(true)
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
    override suspend fun deleteSession(sessionId: String): Result<Unit> = unused()
    override suspend fun renameSession(sessionId: String, newName: String?): Result<Unit> = unused()
    override suspend fun nameSessionIfUnnamed(sessionId: String, name: String): Result<Boolean> = unused()
    override suspend fun replaceSessionName(sessionId: String, expected: String, name: String): Result<Boolean> = unused()
    override suspend fun updateSessionDuration(sessionId: String, durationMs: Long): Result<Unit> = unused()
    override suspend fun setPlace(sessionId: String, place: RecordingPlace?): Result<Unit> = unused()
    override suspend fun setTranscriptModel(sessionId: String, modelId: String?): Result<Unit> = unused()
    override suspend fun deleteAllSessions(): Result<Unit> = unused()
    override suspend fun saveSegment(segment: TranscriptionSegment): Result<Unit> = unused()
    override suspend fun updateSegmentText(segmentId: String, newText: String): Result<Unit> = unused()
    override suspend fun replaceSegments(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        insightSources: Map<String, List<String>>,
        todoSources: Map<String, List<String>>,
        linkSources: Map<String, List<String>>
    ): Result<Unit> = unused()
    override suspend fun getLinkSources(sessionId: String): Map<String, List<String>> = unused()
    override suspend fun saveInsight(insight: LlmInsight): Result<Unit> = unused()
    override suspend fun replaceInsights(sessionId: String, insights: List<LlmInsight>, strategy: InsightStrategy): Result<Unit> = unused()
    override suspend fun updateInsight(insightId: String, newTitle: String?, newContent: String, newTasks: String?): Result<Unit> = unused()
    override fun searchTranscriptions(query: String): Flow<List<SearchResult>> = unused()

    private fun unused(): Nothing = error("not used by these tests")
}

/**
 * Answers the topics call with [topics] and the summary call with [summary], each given what the
 * call reads; a null answer fails the call the way a native error would. Tells the calls apart by
 * their prompt (FakeResourceProvider's "mind summary prompt (en)"). Records every call, and
 * completes [called] at the first. With [gate] set, each call waits for it, so a test can hold a
 * run in the middle.
 */
class MindLlm(
    var topics: (text: String) -> String? = { """{"topics": []}""" },
    // Not "Nothing much.": since 0.7.3 a summary that says nothing is left out (PromptRules).
    var summary: (text: String) -> String? = { """{"summary": "The shed needs a new roof."}""" },
    var reloadFailure: Exception? = null
) : LlmRepository by FakeLlmRepository() {

    class Call(val text: String, val systemPrompt: String?, val grammar: OutputGrammar?) {
        val isSummary: Boolean get() = systemPrompt.orEmpty().startsWith("mind summary")
    }

    val calls = mutableListOf<Call>()
    val called = CompletableDeferred<Unit>()
    var gate: CompletableDeferred<Unit>? = null

    val topicCalls: List<Call> get() = calls.filterNot { it.isSummary }
    val summaryCalls: List<Call> get() = calls.filter { it.isSummary }

    override suspend fun reloadModel(): Result<Unit> =
        reloadFailure?.let { Result.failure(it) } ?: Result.success(Unit)

    override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
        val call = Call(text, systemPrompt, grammar)
        calls += call
        called.complete(Unit)
        gate?.await()
        val answer = if (call.isSummary) summary(text) else topics(text)
        emit(answer ?: throw IllegalStateException("native crash"))
    }
}

/**
 * Counts the starts and stops of the LLM processing service, and keeps the holders that hold it,
 * as the controller does: it is [protected] while any holder holds it.
 */
class CountingProcessingService : LlmProcessingServiceController {
    var starts = 0
        private set
    var stops = 0
        private set

    /** The holder of each start, in order. */
    val startedFor = mutableListOf<String>()

    /** The holders that hold the service now. */
    val holders = mutableSetOf<String>()

    private val _protected = MutableStateFlow(false)
    override val protected: StateFlow<Boolean> = _protected

    override fun startProcessing(holder: String): Boolean {
        starts++
        startedFor += holder
        holders += holder
        _protected.value = true
        return true
    }

    override fun stopProcessing(holder: String) {
        stops++
        holders -= holder
        _protected.value = holders.isNotEmpty()
    }
}

/** A topics answer with one topic per (label, points). */
fun topicsAnswer(vararg topics: Pair<String, List<String>>): String =
    topics.joinToString(prefix = """{"topics": [""", postfix = "]}") { (label, points) ->
        """{"label": "$label", "points": [${points.joinToString { "\"$it\"" }}]}"""
    }
