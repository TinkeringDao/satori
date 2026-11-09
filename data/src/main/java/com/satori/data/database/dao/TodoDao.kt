package com.satori.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.satori.data.database.entity.TodoEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for todos.
 *
 * Statuses are passed in by name (`TodoStatus.name`) so the persisted strings are defined in
 * one place, the mapper.
 */
@Dao
abstract class TodoDao {

    /** Todos in [status]: those with a due time first (soonest first), then the rest, newest first. */
    @Query(
        """
        SELECT * FROM todos
        WHERE status = :status
        ORDER BY due_at IS NULL, due_at ASC, created_at DESC, id ASC
        """
    )
    abstract fun observeByStatusDueFirst(status: String): Flow<List<TodoEntity>>

    /** Todos in [status], newest first. */
    @Query("SELECT * FROM todos WHERE status = :status ORDER BY created_at DESC, id ASC")
    abstract fun observeByStatusNewestFirst(status: String): Flow<List<TodoEntity>>

    /**
     * Every todo from one recording, in transcript order: by the timestamp of the earliest of its
     * source segments still in that recording. Todos with none (added by hand, or their segments
     * are gone) follow, oldest first.
     *
     * Segment ids are matched inside the JSON array text (`["id1","id2"]`), so the query needs no
     * JSON functions. It reads transcription_segments too, so it also re-runs when segments change.
     */
    @Query(
        """
        SELECT * FROM todos
        WHERE session_id = :sessionId
        ORDER BY COALESCE(
                     (SELECT MIN(seg.timestamp)
                      FROM transcription_segments seg
                      WHERE seg.session_id = todos.session_id
                        AND instr(todos.source_segment_ids, '"' || seg.id || '"') > 0),
                     9223372036854775807),
                 created_at ASC,
                 id ASC
        """
    )
    abstract fun observeForSession(sessionId: String): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id")
    abstract suspend fun get(id: String): TodoEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM transcription_sessions WHERE id = :sessionId)")
    abstract suspend fun sessionExists(sessionId: String): Boolean

    /** Insert or update by id. Prefer [upsert], which also handles a deleted recording. */
    @Upsert
    abstract suspend fun upsertRow(todo: TodoEntity)

    /**
     * Insert or update by id, in one transaction.
     *
     * If its recording no longer exists (deleted while the todo was being extracted, say), the
     * todo is kept without it, as if it had been saved first and the recording deleted afterwards
     * (ON DELETE SET NULL), instead of failing on the foreign key.
     *
     * The reminder's shown mark belongs to [markReminderShown]: an update keeps the stored one
     * while remind_at stays the same and clears it when remind_at changes, whatever [todo]
     * carries, so a copy read before its reminder was shown can't bring the reminder back. A new
     * row gets the mark it carries.
     */
    @Transaction
    open suspend fun upsert(todo: TodoEntity) {
        val stored = get(todo.id)
        upsertRow(
            todo.copy(
                sessionId = todo.sessionId?.takeIf { sessionExists(it) },
                reminderShownAt = when {
                    stored == null -> todo.reminderShownAt
                    stored.remindAt == todo.remindAt -> stored.reminderShownAt
                    else -> null
                }
            )
        )
    }

    @Query(
        """
        UPDATE todos
        SET status = :status, completed_at = :completedAt, updated_at = :updatedAt
        WHERE id = :id
        """
    )
    abstract suspend fun setStatus(id: String, status: String, completedAt: Long?, updatedAt: Long)

    /**
     * Set or clear the reminder time, clearing its shown mark so the new time reminds. Writes no
     * other column but updated_at, so an edit made meanwhile is kept.
     */
    @Query(
        """
        UPDATE todos
        SET remind_at = :remindAt, reminder_shown_at = NULL, updated_at = :updatedAt
        WHERE id = :id
        """
    )
    abstract suspend fun setRemindAt(id: String, remindAt: Long?, updatedAt: Long)

    @Query("DELETE FROM todos WHERE id = :id")
    abstract suspend fun delete(id: String)

    /**
     * Todos in [status] with a reminder at or before [atMs], which is now, that hasn't been shown
     * for that time, earliest reminder first. A shown mark counts only from remind_at to now: a
     * reminder is never shown before its time, so an earlier mark was made for a time the
     * reminder had before, and a mark later than now was made before the clock was set back.
     */
    @Query(
        """
        SELECT * FROM todos
        WHERE status = :status AND remind_at IS NOT NULL AND remind_at <= :atMs
          AND (reminder_shown_at IS NULL OR reminder_shown_at < remind_at OR reminder_shown_at > :atMs)
        ORDER BY remind_at ASC, created_at ASC, id ASC
        """
    )
    abstract suspend fun dueReminders(status: String, atMs: Long): List<TodoEntity>

    /**
     * Marks the reminder of [id] shown at [atMs], but only while the todo is in [status] and its
     * reminder is at or before [atMs]: one snoozed or moved later meanwhile stays to be shown.
     *
     * @return 1 if it was marked, 0 if not
     */
    @Query(
        """
        UPDATE todos SET reminder_shown_at = :atMs
        WHERE id = :id AND status = :status AND remind_at IS NOT NULL AND remind_at <= :atMs
        """
    )
    abstract suspend fun markReminderShown(id: String, status: String, atMs: Long): Int

    /**
     * The todo in [status] with the earliest reminder after [afterMs], which is now, or null. No
     * reminder still ahead has been shown for its time: a mark at or after that time would be
     * later than now, made before the clock was set back (see [dueReminders]).
     */
    @Query(
        """
        SELECT * FROM todos
        WHERE status = :status AND remind_at > :afterMs
        ORDER BY remind_at ASC, created_at ASC, id ASC
        LIMIT 1
        """
    )
    abstract suspend fun nextReminderAfter(status: String, afterMs: Long): TodoEntity?
}
