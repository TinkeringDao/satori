package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.TodoRepository
import com.satori.domain.service.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Todos in memory for the tests of what happens when a recording ends: it answers
 * observeForSession, the pending to-dos and the ideas, and upsert, and can be made to fail after
 * [failAfter] stored todos.
 */
internal class RecordedTodos(
    vararg initial: Todo,
    private val failAfter: Int = Int.MAX_VALUE,
    private val failWith: Exception = IllegalStateException("database closed")
) : TodoRepository {
    val stored = initial.toMutableList()
    private var upserts = 0

    override fun observeForSession(sessionId: String): Flow<List<Todo>> =
        flowOf(stored.filter { it.sessionId == sessionId })

    override suspend fun upsert(todo: Todo) {
        if (upserts >= failAfter) throw failWith
        upserts++
        stored.removeAll { it.id == todo.id }
        stored += todo
    }

    // ExtractSessionTodosUseCase reads every pending to-do and the ideas besides the recording's own.
    override fun observeOpen(): Flow<List<Todo>> = flowOf(stored.filter { it.status == TodoStatus.OPEN })
    override fun observeSuggested(): Flow<List<Todo>> = flowOf(stored.filter { it.status == TodoStatus.SUGGESTED })
    override fun observeIdeas(): Flow<List<Todo>> = flowOf(stored.filter { it.status == TodoStatus.IDEA })
    override suspend fun get(id: String): Todo? = stored.firstOrNull { it.id == id }
    override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?) = unused()
    override suspend fun setRemindAt(id: String, remindAt: Long?) = unused()

    // The finds a finishing made again replaces go (UntouchedFinds).
    override suspend fun delete(id: String) {
        stored.removeAll { it.id == id }
    }
    override suspend fun dueReminders(atMs: Long): List<Todo> = unused()
    override suspend fun markReminderShown(id: String, atMs: Long) = unused()
    override suspend fun nextReminderAfter(afterMs: Long): Todo? = unused()

    private fun unused(): Nothing = error("not used by these tests")
}

/** Counts how often the reminder alarm was asked to re-arm; can be made to fail. */
internal class CountingScheduler(private val failWith: Exception? = null) : ReminderScheduler {
    var calls = 0
        private set

    override suspend fun reschedule() {
        calls++
        failWith?.let { throw it }
    }
}
