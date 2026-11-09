package com.satori.presentation.ideas

import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SearchResult
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.ReminderScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// Test doubles for the Ideas page and home (Part U1). Presentation has no shared fakes; these hold
// only what the two screens read and write, and anything else fails the test that uses it.

/** A recording as the list reads it; one without a transcript, notes or length holds nothing. */
internal fun recording(id: String, createdAt: Long, name: String? = null, hasTranscript: Boolean = true) = SessionListItem(
    session = TranscriptionSession(id = id, name = name, createdAt = createdAt, lastModifiedAt = createdAt),
    hasTranscript = hasTranscript,
    hasNotes = false
)

internal fun idea(id: String, sessionId: String?, createdAt: Long, segments: List<String> = emptyList()) = Todo(
    id = id,
    sessionId = sessionId,
    text = "Option to sort the notes by place",
    status = TodoStatus.IDEA,
    sourceSegmentIds = segments,
    createdAt = createdAt,
    updatedAt = createdAt
)

private fun unused(): Nothing = error("not used by the Ideas page")

/**
 * To-dos in memory; [observeIdeas] lists the ideas as the store does, newest first, and the open
 * and suggested to-dos come in the order they were stored.
 *
 * @property readLatencyMs How long [get] takes to answer, in the test's virtual time. It answers
 *   with what was stored as it began, as a query that runs on the database's thread does.
 */
class IdeaTodos(todos: List<Todo>) : TodoRepository {

    private val stored = MutableStateFlow(todos)

    var readLatencyMs = 0L

    fun status(id: String): TodoStatus? = stored.value.firstOrNull { it.id == id }?.status

    fun remindAt(id: String): Long? = stored.value.firstOrNull { it.id == id }?.remindAt

    /** Stores [todo], as a recording's to-do step does. */
    fun add(todo: Todo) {
        stored.value = stored.value + todo
    }

    override fun observeIdeas(): Flow<List<Todo>> = stored.map { todos ->
        todos.filter { it.status == TodoStatus.IDEA }.sortedWith(compareByDescending<Todo> { it.createdAt }.thenBy { it.id })
    }

    override fun observeOpen(): Flow<List<Todo>> = stored.map { todos -> todos.filter { it.status == TodoStatus.OPEN } }

    override fun observeSuggested(): Flow<List<Todo>> = stored.map { todos -> todos.filter { it.status == TodoStatus.SUGGESTED } }

    override suspend fun get(id: String): Todo? {
        val found = stored.value.firstOrNull { it.id == id }
        if (readLatencyMs > 0) delay(readLatencyMs)
        return found
    }

    override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?) {
        stored.value = stored.value.map { if (it.id == id) it.copy(status = status, completedAt = completedAt) else it }
    }

    override suspend fun setRemindAt(id: String, remindAt: Long?) {
        stored.value = stored.value.map { if (it.id == id) it.copy(remindAt = remindAt) else it }
    }

    override fun observeForSession(sessionId: String): Flow<List<Todo>> = unused()
    override suspend fun upsert(todo: Todo) = unused()
    override suspend fun delete(id: String) = unused()
    override suspend fun dueReminders(atMs: Long): List<Todo> = unused()
    override suspend fun markReminderShown(id: String, atMs: Long) = unused()
    override suspend fun nextReminderAfter(afterMs: Long): Todo? = unused()
}

/** Counts the reschedules every change to a to-do ends in. */
class CountedReminders : ReminderScheduler {
    var reschedules = 0
        private set

    override suspend fun reschedule() {
        reschedules++
    }
}

/** Lists [recordings] and has nothing else. */
class ListedRecordings(private val recordings: List<SessionListItem>) : TranscriptionRepository {

    override fun getAllSessions(): Flow<List<SessionListItem>> = flowOf(recordings)

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

    override fun getSession(sessionId: String): Flow<TranscriptionSession?> = unused()
    override fun getSessionWithDetails(sessionId: String): Flow<SessionWithDetails?> = unused()
    override suspend fun deleteSession(sessionId: String): Result<Unit> = unused()
    override suspend fun renameSession(sessionId: String, newName: String?): Result<Unit> = unused()
    override suspend fun nameSessionIfUnnamed(sessionId: String, name: String): Result<Boolean> = unused()
    override suspend fun replaceSessionName(sessionId: String, expected: String, name: String): Result<Boolean> = unused()
    override suspend fun setOutputLanguageIfUnset(sessionId: String, language: String): Result<Boolean> = unused()
    override suspend fun updateSessionDuration(sessionId: String, durationMs: Long): Result<Unit> = unused()
    override suspend fun setPlace(sessionId: String, place: RecordingPlace?): Result<Unit> = unused()
    override suspend fun setTranscriptModel(sessionId: String, modelId: String?): Result<Unit> = unused()
    override suspend fun deleteAllSessions(): Result<Unit> = unused()
    override suspend fun saveSegment(segment: TranscriptionSegment): Result<Unit> = unused()
    override suspend fun updateSegmentText(segmentId: String, newText: String): Result<Unit> = unused()
    override fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>> = unused()

    override suspend fun replaceSegments(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        insightSources: Map<String, List<String>>,
        todoSources: Map<String, List<String>>,
        linkSources: Map<String, List<String>>
    ): Result<Unit> = unused()

    override suspend fun getLinkSources(sessionId: String): Map<String, List<String>> = unused()
    override suspend fun saveInsight(insight: LlmInsight): Result<Unit> = unused()

    override suspend fun replaceInsights(
        sessionId: String,
        insights: List<LlmInsight>,
        strategy: InsightStrategy
    ): Result<Unit> = unused()

    override suspend fun updateInsight(
        insightId: String,
        newTitle: String?,
        newContent: String,
        newTasks: String?
    ): Result<Unit> = unused()

    override fun getInsightsBySession(sessionId: String): Flow<List<LlmInsight>> = unused()
    override fun searchTranscriptions(query: String): Flow<List<SearchResult>> = unused()
}
