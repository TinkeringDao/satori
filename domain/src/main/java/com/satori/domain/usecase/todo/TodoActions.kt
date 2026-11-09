package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.TodoRepository
import com.satori.domain.service.ReminderScheduler
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * What the user does to a to-do on the home screen: complete it, take it back, keep or dismiss
 * a suggestion, edit it, delete it; and to an idea: make it a to-do, move a to-do to the ideas,
 * delete an idea and take that back. Every change ends in [ReminderScheduler.reschedule], so the
 * reminder alarm and the notifications on show follow the to-dos.
 *
 * Each change of a to-do's status goes to [log] with the [TodoSource] the caller names: the
 * status it had, read just before, and the one it has now.
 *
 * @property takenAway The to-dos the app took away as it found a recording's anew (design R9), for
 *   an edit of one to bring it back; shared by every instance in the app
 * @property clock The current time, epoch ms
 */
class TodoActions(
    private val todos: TodoRepository,
    private val reminders: ReminderScheduler,
    private val log: TodoChangeLog = TodoChangeLog.NONE,
    private val takenAway: TakenAwayTodos = TakenAwayTodos(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Notifications were just allowed: post the reminders held back while they were off, now
     * rather than at the next alarm.
     */
    suspend fun notificationsAllowed() {
        reminders.reschedule()
    }

    /** Marks [id] done. A reminder showing for it goes away. */
    suspend fun complete(id: String, source: TodoSource) {
        val before = todos.get(id)?.status
        todos.setStatus(id, TodoStatus.DONE, completedAt = clock())
        log.changed(id, before, TodoStatus.DONE.takeIf { before != null }, source)
        reminders.reschedule()
    }

    /** Takes [complete] back (Undo): [id] is open again. */
    suspend fun reopen(id: String) {
        val before = todos.get(id)?.status
        todos.setStatus(id, TodoStatus.OPEN)
        log.changed(id, before, TodoStatus.OPEN.takeIf { before != null }, TodoSource.UNDO)
        reminders.reschedule()
    }

    /**
     * Turns suggestions into open to-dos. A reminder whose time went by while it was only a
     * suggestion counts as shown: the user is looking at the to-do, and keeping several old
     * suggestions must not raise a notification for each.
     */
    suspend fun keep(suggestions: List<Todo>, source: TodoSource) {
        val now = clock()
        suggestions.forEach { suggestion ->
            todos.setStatus(suggestion.id, TodoStatus.OPEN)
            log.changed(suggestion.id, suggestion.status, TodoStatus.OPEN, source)
            if (suggestion.remindAt != null && suggestion.remindAt <= now) {
                todos.markReminderShown(suggestion.id, now)
            }
        }
        reminders.reschedule()
    }

    /** Drops a suggestion. It stays stored as dismissed, so it is not suggested again. */
    suspend fun dismiss(id: String, source: TodoSource) {
        val before = todos.get(id)?.status
        todos.setStatus(id, TodoStatus.DISMISSED)
        log.changed(id, before, TodoStatus.DISMISSED.takeIf { before != null }, source)
        reminders.reschedule()
    }

    /**
     * Makes the idea [id] an open to-do. Does nothing unless it is still an idea: the page that
     * offered it may show a copy read before it changed.
     */
    suspend fun makeTodo(id: String, source: TodoSource) {
        if (todos.get(id)?.status != TodoStatus.IDEA) return
        todos.setStatus(id, TodoStatus.OPEN)
        log.changed(id, TodoStatus.IDEA, TodoStatus.OPEN, source)
        reminders.reschedule()
    }

    /**
     * Moves the open or suggested to-do [id] to the ideas, where nothing reminds: its reminder
     * goes, and a notification on show for it with it. Does nothing for a to-do in any other
     * state, or one deleted meanwhile.
     *
     * The reminder goes first: a move cut short between the two writes then leaves a to-do
     * without its reminder, never an idea that keeps one, which "Make it a to-do" would bring
     * back, due at once.
     */
    suspend fun moveToIdeas(id: String, source: TodoSource) {
        val current = todos.get(id) ?: return
        if (current.status != TodoStatus.OPEN && current.status != TodoStatus.SUGGESTED) return
        if (current.remindAt != null) todos.setRemindAt(id, null)
        todos.setStatus(id, TodoStatus.IDEA)
        log.changed(id, current.status, TodoStatus.IDEA, source)
        reminders.reschedule()
    }

    /**
     * Deletes the idea [id]. It stays stored as dismissed, so it is not found again and
     * [restoreIdea] can take the delete back. Does nothing unless it is still an idea.
     */
    suspend fun dismissIdea(id: String, source: TodoSource) {
        if (todos.get(id)?.status != TodoStatus.IDEA) return
        todos.setStatus(id, TodoStatus.DISMISSED)
        log.changed(id, TodoStatus.IDEA, TodoStatus.DISMISSED, source)
        reminders.reschedule()
    }

    /** Takes [dismissIdea] back (Undo): [id] is an idea again. Does nothing unless it is dismissed. */
    suspend fun restoreIdea(id: String) {
        if (todos.get(id)?.status != TodoStatus.DISMISSED) return
        todos.setStatus(id, TodoStatus.IDEA)
        log.changed(id, TodoStatus.DISMISSED, TodoStatus.IDEA, TodoSource.UNDO)
        reminders.reschedule()
    }

    /**
     * Stores the new text and due day and time of [id], on the to-do as it is stored now, not as
     * it was when the edit sheet opened: a Done or Snooze tapped on its reminder meanwhile stays,
     * and a to-do deleted meanwhile is not brought back. Does nothing unless the to-do is still
     * open, which is the only kind the sheet edits. One the app took away meanwhile, as it found the
     * recording's to-dos anew over its readings ([TakenAwayTodos]; it takes only those nobody has
     * touched), comes back with the edit, as the owner's now: he was editing it.
     *
     * @param date The due day, or null for none
     * @param time The due clock time; ignored without [date]
     */
    suspend fun edit(id: String, text: String, date: LocalDate?, time: LocalTime?, zone: ZoneId) {
        val current = (todos.get(id) ?: takenAway.takeBack(id))?.takeIf { it.status == TodoStatus.OPEN } ?: return
        todos.upsert(TodoEdit.edited(current, text, date, time, clock(), zone))
        reminders.reschedule()
    }

    /** Deletes [id]; one the app takes away as it finds the recording's to-dos anew ([TodoSource.READ_AGAIN]) is kept for [edit]. */
    suspend fun delete(id: String, source: TodoSource) {
        val before = todos.get(id)
        todos.delete(id)
        if (before != null && source == TodoSource.READ_AGAIN) takenAway.record(before)
        log.changed(id, before?.status, null, source)
        reminders.reschedule()
    }
}
