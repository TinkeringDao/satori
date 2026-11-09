package com.satori.data.repository

import com.satori.data.database.dao.TodoDao
import com.satori.data.database.entity.TodoEntity
import com.satori.data.database.mapper.toDomain
import com.satori.data.database.mapper.toEntity
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.TodoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [TodoRepository] on Room.
 *
 * Only OPEN todos are listed as open and remind; the status filter is in SQL, so a todo with a
 * status this version doesn't know never reminds.
 */
class TodoRepositoryImpl @Inject constructor(
    private val todoDao: TodoDao
) : TodoRepository {

    override fun observeOpen(): Flow<List<Todo>> =
        todoDao.observeByStatusDueFirst(TodoStatus.OPEN.name).mapToDomain()

    override fun observeSuggested(): Flow<List<Todo>> =
        todoDao.observeByStatusNewestFirst(TodoStatus.SUGGESTED.name).mapToDomain()

    override fun observeIdeas(): Flow<List<Todo>> =
        todoDao.observeByStatusNewestFirst(TodoStatus.IDEA.name).mapToDomain()

    override fun observeForSession(sessionId: String): Flow<List<Todo>> =
        todoDao.observeForSession(sessionId).mapToDomain()

    override suspend fun get(id: String): Todo? = todoDao.get(id)?.toDomain()

    /**
     * Stores [todo]; if its recording was deleted meanwhile it is kept without one. The reminder's
     * shown mark is kept or cleared as [TodoDao.upsert] describes.
     */
    override suspend fun upsert(todo: Todo) {
        todoDao.upsert(todo.toEntity())
    }

    /**
     * Sets the status and stamps updatedAt. For DONE, [completedAt] is stored, or now if null;
     * for any other status completedAt is cleared.
     */
    override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?) {
        val now = System.currentTimeMillis()
        todoDao.setStatus(
            id = id,
            status = status.name,
            completedAt = if (status == TodoStatus.DONE) completedAt ?: now else null,
            updatedAt = now
        )
    }

    /** Sets or clears the reminder time, clears its shown mark and stamps updatedAt. */
    override suspend fun setRemindAt(id: String, remindAt: Long?) {
        todoDao.setRemindAt(id, remindAt, updatedAt = System.currentTimeMillis())
    }

    override suspend fun delete(id: String) {
        todoDao.delete(id)
    }

    override suspend fun dueReminders(atMs: Long): List<Todo> =
        todoDao.dueReminders(TodoStatus.OPEN.name, atMs).map { it.toDomain() }

    override suspend fun markReminderShown(id: String, atMs: Long) {
        todoDao.markReminderShown(id, TodoStatus.OPEN.name, atMs)
    }

    override suspend fun nextReminderAfter(afterMs: Long): Todo? =
        todoDao.nextReminderAfter(TodoStatus.OPEN.name, afterMs)?.toDomain()

    // Room re-runs a query on every write to a table it reads; observeForSession also reads the
    // segments, which change all through a live recording. Drop the identical results.
    private fun Flow<List<TodoEntity>>.mapToDomain(): Flow<List<Todo>> =
        map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()
}
