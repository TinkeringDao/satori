package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.TodoRepository
import com.satori.domain.time.TimePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.time.ZoneId

// Test doubles of Part T (to-dos and ideas): the shared ones are Part 0's.

/**
 * The prompts of [FakeResourceProvider], with a to-do system prompt that, like the real one,
 * holds no placeholder: "todo prompt (en)". The header is the default English one
 * ([ResourceProvider.TODO_HEADER]).
 */
internal class TodoResources : ResourceProvider by FakeResourceProvider() {
    override fun getTodoExtractionPrompt(localeCode: String) = "todo prompt ($localeCode)"
}

/**
 * To-dos and ideas in memory, answering every read by status the way TodoDao does, in the order
 * they were stored. [upsert] can be made to fail after [failAfter] stored items, [setStatus]
 * after [failStatusAfter] changes, and the reads of other recordings' to-dos and ideas with
 * [othersFail].
 */
internal class StoredTodos(
    vararg initial: Todo,
    private val failAfter: Int = Int.MAX_VALUE,
    private val failStatusAfter: Int = Int.MAX_VALUE,
    var othersFail: Exception? = null
) : TodoRepository {
    private val byId = linkedMapOf<String, Todo>().apply { initial.forEach { put(it.id, it) } }
    private var upserts = 0
    private var statusChanges = 0

    /** Everything held, in the order it was first stored. */
    val stored: List<Todo> get() = byId.values.toList()

    override fun observeOpen(): Flow<List<Todo>> = withStatus(TodoStatus.OPEN)
    override fun observeSuggested(): Flow<List<Todo>> = withStatus(TodoStatus.SUGGESTED)
    override fun observeIdeas(): Flow<List<Todo>> = withStatus(TodoStatus.IDEA)

    override fun observeForSession(sessionId: String): Flow<List<Todo>> =
        flowOf(byId.values.filter { it.sessionId == sessionId })

    override suspend fun get(id: String): Todo? = byId[id]

    override suspend fun upsert(todo: Todo) {
        if (upserts >= failAfter) throw IllegalStateException("database closed")
        upserts++
        byId[todo.id] = todo
    }

    override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?) {
        if (statusChanges >= failStatusAfter) throw IllegalStateException("database closed")
        statusChanges++
        val todo = byId[id] ?: return
        byId[id] = todo.copy(status = status, completedAt = completedAt.takeIf { status == TodoStatus.DONE })
    }

    override suspend fun setRemindAt(id: String, remindAt: Long?) {
        val todo = byId[id] ?: return
        byId[id] = todo.copy(remindAt = remindAt, reminderShownAt = null)
    }

    override suspend fun delete(id: String): Unit = unused()
    override suspend fun dueReminders(atMs: Long): List<Todo> = unused()
    override suspend fun markReminderShown(id: String, atMs: Long): Unit = unused()
    override suspend fun nextReminderAfter(afterMs: Long): Todo? = unused()

    private fun withStatus(status: TodoStatus): Flow<List<Todo>> = flow {
        othersFail?.let { throw it }
        emit(byId.values.filter { it.status == status })
    }

    private fun unused(): Nothing = error("not used by these tests")
}

/** [useCase] for the tests that look at the to-dos and ideas it finds, not at how many were over the cap. */
internal class FoundTodos(private val useCase: ExtractTodosUseCase) {
    suspend operator fun invoke(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        zone: ZoneId,
        languageCode: String,
        existingTodos: List<Todo> = emptyList(),
        policy: TimePolicy = TimePolicy()
    ): List<Todo> = useCase(sessionId, segments, zone, languageCode, existingTodos, policy).todos
}

/** Keeps every raw answer it is given, as (recording, window, answer). */
internal class RecordedAnswers : AnswerLog {
    val answers = mutableListOf<Triple<String, Int, String>>()

    override fun todoAnswer(sessionId: String, window: Int, answer: String) {
        answers += Triple(sessionId, window, answer)
    }
}
