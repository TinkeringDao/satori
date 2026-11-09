package com.satori.domain.repository

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import kotlinx.coroutines.flow.Flow

/** Stores todos (see [Todo]); every change is visible through the flows. */
interface TodoRepository {

    /** Open todos: those with a due time first (soonest first), then the rest, newest first. */
    fun observeOpen(): Flow<List<Todo>>

    /** Todos the model inferred and the user hasn't kept or dismissed yet, newest first. */
    fun observeSuggested(): Flow<List<Todo>>

    /** The ideas ([TodoStatus.IDEA]), newest first. */
    fun observeIdeas(): Flow<List<Todo>>

    /** Every todo from one recording, in transcript order. */
    fun observeForSession(sessionId: String): Flow<List<Todo>>

    suspend fun get(id: String): Todo?

    /**
     * Inserts or replaces by id.
     *
     * [Todo.reminderShownAt] is left to the reminder engine: replacing a todo keeps the stored
     * mark while remindAt stays the same and clears it when remindAt changes, whatever [todo]
     * carries, so a copy read before its reminder was shown can't make it show again. A new
     * todo is stored with the mark it carries.
     */
    suspend fun upsert(todo: Todo)

    /** Sets the status; [completedAt] is stored for DONE and cleared otherwise. */
    suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long? = null)

    /**
     * Sets the reminder time of [id], or removes the reminder with null, and clears its shown
     * mark so the new time reminds. Stamps updatedAt and changes nothing else, so an edit made
     * meanwhile is kept.
     */
    suspend fun setRemindAt(id: String, remindAt: Long?)

    suspend fun delete(id: String)

    /**
     * Open todos whose reminder time is at or before [atMs] and whose reminder hasn't been shown
     * for that time yet ([Todo.reminderShownAt]), oldest first. A reminder written with a time
     * that has passed is therefore still due, until [markReminderShown].
     */
    suspend fun dueReminders(atMs: Long): List<Todo>

    /**
     * Records that the reminder of [id] was shown at [atMs], so [dueReminders] leaves it out while
     * its remindAt stays the same and the clock isn't set back to before the mark. Only takes
     * effect while the todo is open and its reminder time is at or before [atMs]: a reminder
     * snoozed or moved later meanwhile stays to be shown. Changes nothing else.
     */
    suspend fun markReminderShown(id: String, atMs: Long)

    /** The earliest reminder after [afterMs] of an open todo, not yet shown for its time, or null. */
    suspend fun nextReminderAfter(afterMs: Long): Todo?
}
