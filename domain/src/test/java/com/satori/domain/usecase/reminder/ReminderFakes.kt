package com.satori.domain.usecase.reminder

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.TodoRepository
import kotlinx.coroutines.flow.Flow

/** Epoch ms of [hour]:[minute] on the epoch's first day; reminders only compare times. */
internal fun at(hour: Int, minute: Int = 0): Long = (hour * 60L + minute) * 60_000L

internal fun todo(
    id: String,
    remindAt: Long?,
    status: TodoStatus = TodoStatus.OPEN,
    shownAt: Long? = null
) = Todo(
    id = id,
    sessionId = null,
    text = "Text of $id",
    status = status,
    remindAt = remindAt,
    reminderShownAt = shownAt,
    createdAt = 0L,
    updatedAt = 0L
)

/**
 * Todos in memory, answering the reminder calls the way the TodoRepository contract says and
 * TodoDao does: a reminder counts as shown for its time while its shown mark lies between that
 * time and now (a later mark was made before the clock was set back), so no reminder still ahead
 * counts as shown. A whole update keeps the stored mark unless remindAt changes, setRemindAt
 * clears it, and markReminderShown only marks an open todo whose reminder is due.
 */
internal class InMemoryTodos(vararg initial: Todo) : TodoRepository {
    val byId = linkedMapOf<String, Todo>().apply { initial.forEach { put(it.id, it) } }

    override suspend fun get(id: String): Todo? = byId[id]

    override suspend fun upsert(todo: Todo) {
        val stored = byId[todo.id]
        byId[todo.id] = when {
            stored == null -> todo
            stored.remindAt == todo.remindAt -> todo.copy(reminderShownAt = stored.reminderShownAt)
            else -> todo.copy(reminderShownAt = null)
        }
    }

    override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?) {
        val todo = byId[id] ?: return
        byId[id] = todo.copy(status = status, completedAt = completedAt.takeIf { status == TodoStatus.DONE })
    }

    override suspend fun setRemindAt(id: String, remindAt: Long?) {
        val todo = byId[id] ?: return
        byId[id] = todo.copy(remindAt = remindAt, reminderShownAt = null)
    }

    override suspend fun delete(id: String) {
        byId.remove(id)
    }

    override suspend fun dueReminders(atMs: Long): List<Todo> = byId.values
        .filter { it.status == TodoStatus.OPEN && it.remindAt != null && it.remindAt <= atMs && !it.shownBy(atMs) }
        .sortedBy { it.remindAt }

    override suspend fun markReminderShown(id: String, atMs: Long) {
        val todo = byId[id] ?: return
        if (todo.status == TodoStatus.OPEN && todo.remindAt != null && todo.remindAt <= atMs) {
            byId[id] = todo.copy(reminderShownAt = atMs)
        }
    }

    override suspend fun nextReminderAfter(afterMs: Long): Todo? = byId.values
        .filter { it.status == TodoStatus.OPEN && it.remindAt != null && it.remindAt > afterMs }
        .minByOrNull { it.remindAt!! }

    override fun observeOpen(): Flow<List<Todo>> = unused()
    override fun observeSuggested(): Flow<List<Todo>> = unused()
    override fun observeIdeas(): Flow<List<Todo>> = unused()
    override fun observeForSession(sessionId: String): Flow<List<Todo>> = unused()

    private fun Todo.shownBy(nowMs: Long): Boolean {
        val mark = reminderShownAt ?: return false
        return remindAt != null && mark >= remindAt && mark <= nowMs
    }

    private fun unused(): Nothing = error("not used by the reminder tests")
}

/** The one alarm: armed at a time, or not armed. */
internal class FakeAlarm : ReminderAlarm {
    var armedAt: Long? = null
        private set

    override fun arm(atMs: Long) {
        armedAt = atMs
    }

    override fun cancel() {
        armedAt = null
    }
}

/** The notification shade: what is showing now, and every reminder ever posted, in order. */
internal class FakeNotifications : ReminderNotifications {
    /** False while the app may not post notifications: nothing is shown then. */
    var enabled = true
    val shown = linkedMapOf<String, Todo>()
    val posted = mutableListOf<String>()

    override fun show(todo: Todo): Boolean {
        if (!enabled) return false
        shown[todo.id] = todo
        posted += todo.id
        return true
    }

    override fun dismiss(todoId: String) {
        shown.remove(todoId)
    }

    override fun showing(): Set<String> = shown.keys.toSet()

    /** The user swipes a reminder away. */
    fun swipe(todoId: String) {
        shown.remove(todoId)
    }
}
