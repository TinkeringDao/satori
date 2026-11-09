package com.satori.domain.usecase.stt

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.usecase.todo.TodoChangeLog
import com.satori.domain.usecase.todo.TodoSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// Test doubles of the text a recording gets again after Transcribe again in another language
// (0.6.1): the recording, its to-dos and ideas, and what the field log hears of them.

/**
 * [recordings] as the database keeps a recording: a rename (null clears the name), the owner's
 * while there is one, and its language are stored too; the rest is [FakeTranscriptionRepository]'s.
 */
internal class HeldRecording(val recordings: FakeTranscriptionRepository) : TranscriptionRepository by recordings {
    override suspend fun renameSession(sessionId: String, newName: String?): Result<Unit> {
        val name = newName?.trim()?.takeIf { it.isNotEmpty() }
        recordings.session = recordings.session?.takeIf { it.id == sessionId }
            ?.copy(name = name, nameByOwner = name != null) ?: recordings.session
        return Result.success(Unit)
    }

    override suspend fun setLanguage(sessionId: String, language: RecordingLanguage): Result<Boolean> {
        val current = recordings.session?.takeIf { it.id == sessionId } ?: return Result.success(false)
        recordings.session = current.copy(language = language)
        return Result.success(true)
    }
}

/** The to-dos and ideas of every recording, in memory, read by status and by recording as TodoDao reads them. */
internal class HeldTodos(vararg initial: Todo) : TodoRepository {
    private val byId = linkedMapOf<String, Todo>().apply { initial.forEach { put(it.id, it) } }

    /** Everything held, in the order it was first stored. */
    val stored: List<Todo> get() = byId.values.toList()

    override fun observeOpen(): Flow<List<Todo>> = withStatus(TodoStatus.OPEN)
    override fun observeSuggested(): Flow<List<Todo>> = withStatus(TodoStatus.SUGGESTED)
    override fun observeIdeas(): Flow<List<Todo>> = withStatus(TodoStatus.IDEA)
    override fun observeForSession(sessionId: String): Flow<List<Todo>> = flow { emit(byId.values.filter { it.sessionId == sessionId }) }
    override suspend fun get(id: String): Todo? = byId[id]

    override suspend fun upsert(todo: Todo) {
        byId[todo.id] = todo
    }

    override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?) {
        val todo = byId[id] ?: return
        byId[id] = todo.copy(status = status, completedAt = completedAt.takeIf { status == TodoStatus.DONE })
    }

    override suspend fun delete(id: String) {
        byId.remove(id)
    }

    override suspend fun setRemindAt(id: String, remindAt: Long?): Unit = unused()
    override suspend fun dueReminders(atMs: Long): List<Todo> = unused()
    override suspend fun markReminderShown(id: String, atMs: Long): Unit = unused()
    override suspend fun nextReminderAfter(afterMs: Long): Todo? = unused()

    private fun withStatus(status: TodoStatus): Flow<List<Todo>> = flow { emit(byId.values.filter { it.status == status }) }

    private fun unused(): Nothing = error("not used by these tests")
}

/** The field log's line of each change of a to-do's status ([TodoChangeLog.line]). */
internal class ChangeLines : TodoChangeLog {
    val lines = mutableListOf<String>()

    override fun changed(id: String, from: TodoStatus?, to: TodoStatus?, source: TodoSource) {
        lines += TodoChangeLog.line(id, from, to, source)
    }
}

/** Counts how often the reminders were re-armed. */
internal class Reminders : ReminderScheduler {
    var calls = 0
        private set

    override suspend fun reschedule() {
        calls++
    }
}
