package com.satori.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.satori.data.database.entity.KeptBeforeRedoEntity
import com.satori.data.database.entity.LlmInsightEntity
import com.satori.data.database.entity.NoteKeptBeforeRedoEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for LLM insight operations.
 *
 * Provides operations for managing insights within sessions.
 */
@Dao
abstract class LlmInsightDao {

    /**
     * Insert a new insight.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(insight: LlmInsightEntity)

    /** Insert several insights. Part of [replaceForSession]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(insights: List<LlmInsightEntity>)

    /**
     * Get all insights for a specific session ordered by timestamp.
     */
    @Query("SELECT * FROM llm_insights WHERE session_id = :sessionId ORDER BY timestamp ASC")
    abstract fun getInsightsBySession(sessionId: String): Flow<List<LlmInsightEntity>>

    /**
     * Get count of insights in a session.
     */
    @Query("SELECT COUNT(*) FROM llm_insights WHERE session_id = :sessionId")
    abstract suspend fun getInsightCount(sessionId: String): Int

    /**
     * Sets the title, content and tasks JSON of an existing insight, the owner's edit made at
     * [editedAt] (epoch ms), which edited_at keeps (since version 13): a note he changed, which the
     * app's notes made again leave alone. One statement, so a note edited by hand is saved whole or
     * not at all.
     *
     * @return 1, or 0 when the note is gone: notes made again replaced it meanwhile ([saveEdit])
     */
    @Query(
        "UPDATE llm_insights SET title = :newTitle, content = :newContent, tasks = :newTasks, edited_at = :editedAt " +
            "WHERE id = :insightId"
    )
    abstract suspend fun updateNote(insightId: String, newTitle: String?, newContent: String, newTasks: String?, editedAt: Long): Int

    /**
     * The owner's edit of the note [insightId] of [sessionId], saved at [editedAt], in one
     * transaction: on the note ([updateNote]), or, when the note is gone meanwhile (a finishing made
     * again over the recording's readings replaced its notes while the editor was open, design R9),
     * as a note of the recording of its own with what he wrote, under the same id, marked his. His
     * text is never dropped while the app says it saved. Throws when the recording itself is gone.
     */
    @Transaction
    open suspend fun saveEdit(insightId: String, sessionId: String, newTitle: String?, newContent: String, newTasks: String?, editedAt: Long) {
        if (updateNote(insightId, newTitle, newContent, newTasks, editedAt) > 0) return
        insert(
            LlmInsightEntity(
                id = insightId,
                sessionId = sessionId,
                title = newTitle,
                content = newContent,
                tasks = newTasks,
                timestamp = editedAt,
                sourceSegmentIds = NO_SOURCES,
                editedAt = editedAt
            )
        )
    }

    /** Whether a note of [sessionId] was saved in the note editor ([updateNote]'s edited_at). */
    @Query("SELECT EXISTS(SELECT 1 FROM llm_insights WHERE session_id = :sessionId AND edited_at IS NOT NULL)")
    abstract suspend fun hasEditedNote(sessionId: String): Boolean

    /**
     * Delete all insights for a session (usually handled by CASCADE).
     */
    @Query("DELETE FROM llm_insights WHERE session_id = :sessionId")
    abstract suspend fun deleteInsightsBySession(sessionId: String)

    /**
     * Sets the insight strategy of a session and stamps it as modified, leaving its other columns
     * alone. Part of [replaceForSession].
     */
    @Query(
        "UPDATE transcription_sessions SET insight_strategy = :strategy, last_modified_at = :modifiedAt " +
            "WHERE id = :sessionId"
    )
    abstract suspend fun markSessionInsights(sessionId: String, strategy: String, modifiedAt: Long)

    /**
     * Replaces all insights of a session with [insights] and sets its insight strategy to
     * [strategy] (an `InsightStrategy` name), in one transaction: readers see the old insights or
     * the new ones, never none, and a failure on the way (the session deleted meanwhile, say)
     * leaves the old ones in place.
     */
    @Transaction
    open suspend fun replaceForSession(
        sessionId: String,
        insights: List<LlmInsightEntity>,
        strategy: String,
        modifiedAt: Long
    ) {
        deleteInsightsBySession(sessionId)
        insertAll(insights)
        markSessionInsights(sessionId, strategy, modifiedAt)
    }

    /**
     * [replaceForSession], unless a note of the session was saved in the note editor
     * ([hasEditedNote]), looked for in the same transaction: the notes are the owner's then, and
     * stay all as they are. The notes made again over a recording's readings (design R9) are
     * written so, and so are the none that take the old ones away when too little is readable.
     *
     * @return false when the notes were his, and nothing was written
     */
    @Transaction
    open suspend fun replaceForSessionUnlessEdited(
        sessionId: String,
        insights: List<LlmInsightEntity>,
        strategy: String,
        modifiedAt: Long
    ): Boolean {
        if (hasEditedNote(sessionId)) return false
        replaceForSession(sessionId, insights, strategy, modifiedAt)
        return true
    }

    // ========== What a finishing made again keeps (design R9) ==========

    /** Part of [keepBeforeRedo]. */
    @Query("SELECT * FROM kept_before_redo WHERE session_id = :sessionId")
    abstract suspend fun keptBeforeRedo(sessionId: String): KeptBeforeRedoEntity?

    /** Part of [keepBeforeRedo]: the recording's name, whose it is, and its notes' strategy, as they are now. */
    @Query(
        """
        INSERT INTO kept_before_redo (session_id, name, name_by_owner, insight_strategy, kept_at)
        SELECT id, name, name_by_owner, insight_strategy, :now FROM transcription_sessions WHERE id = :sessionId
        """
    )
    abstract suspend fun keepName(sessionId: String, now: Long)

    /** Part of [keepBeforeRedo]: the recording's notes, row for row. */
    @Query(
        """
        INSERT INTO notes_kept_before_redo (id, session_id, title, content, tasks, timestamp, source_segment_ids, edited_at)
        SELECT id, session_id, title, content, tasks, timestamp, source_segment_ids, edited_at
        FROM   llm_insights WHERE session_id = :sessionId
        """
    )
    abstract suspend fun keepNotes(sessionId: String)

    /** The notes [keepBeforeRedo] kept of [sessionId], oldest first. */
    @Query("SELECT * FROM notes_kept_before_redo WHERE session_id = :sessionId ORDER BY timestamp, id")
    abstract suspend fun notesKeptBeforeRedo(sessionId: String): List<NoteKeptBeforeRedoEntity>

    /**
     * Keeps what the recording [sessionId] has now, its name and notes, before a finishing made
     * again over its readings replaces any of it (design R9), in one transaction: once, the first
     * time, and never again, so the copy is the recording as an older version left it. Version 12
     * recorded no rename and no note edit, so this is what can give the owner back a name or notes
     * of his from before ([restoreBeforeRedo]).
     *
     * @return whether it was kept now; false when it was kept before, or the recording is gone
     */
    @Transaction
    open suspend fun keepBeforeRedo(sessionId: String, now: Long): Boolean {
        if (keptBeforeRedo(sessionId) != null) return false
        keepName(sessionId, now)
        if (keptBeforeRedo(sessionId) == null) return false
        keepNotes(sessionId)
        return true
    }

    /** Part of [restoreBeforeRedo]: the kept notes back in place, his now ([now]). */
    @Query(
        """
        INSERT OR REPLACE INTO llm_insights (id, session_id, title, content, tasks, timestamp, source_segment_ids, edited_at)
        SELECT id, session_id, title, content, tasks, timestamp, source_segment_ids, :now
        FROM   notes_kept_before_redo WHERE session_id = :sessionId
        """
    )
    abstract suspend fun putNotesBack(sessionId: String, now: Long)

    /**
     * Part of [restoreBeforeRedo]: the kept name back, his now when it is one, its notes' strategy
     * back, and no finishing made again due any more.
     */
    @Query(
        """
        UPDATE transcription_sessions
        SET    name = :name,
               name_by_owner = CASE WHEN :name IS NULL OR TRIM(:name) = '' THEN 0 ELSE 1 END,
               insight_strategy = :strategy,
               redo_due = 0
        WHERE  id = :sessionId
        """
    )
    abstract suspend fun putNameBack(sessionId: String, name: String?, strategy: String)

    /**
     * Puts back what [keepBeforeRedo] kept of [sessionId], in one transaction, for the debug tools
     * (DebugToolsActivity's restore-before-redo), when the owner says a name or notes of his from
     * before were replaced: its notes as they were, in place of those it has (marked his, so nothing
     * made again replaces them), its name as it was (his when it is one), and no finishing made
     * again due. The copy stays.
     *
     * @return how many notes came back, or null when nothing was kept of it
     */
    @Transaction
    open suspend fun restoreBeforeRedo(sessionId: String, now: Long): Int? {
        val kept = keptBeforeRedo(sessionId) ?: return null
        deleteInsightsBySession(sessionId)
        putNotesBack(sessionId, now)
        putNameBack(sessionId, kept.name, kept.insightStrategy)
        return notesKeptBeforeRedo(sessionId).size
    }

    /** Part of [markOwnersEdits]. */
    @Query("UPDATE transcription_sessions SET name_by_owner = 1 WHERE id = :sessionId AND name IS NOT NULL AND TRIM(name) != ''")
    abstract suspend fun markNameTheOwners(sessionId: String): Int

    /** Part of [markOwnersEdits]. */
    @Query("UPDATE llm_insights SET edited_at = :now WHERE session_id = :sessionId AND edited_at IS NULL")
    abstract suspend fun markNotesTheOwners(sessionId: String, now: Long): Int

    /**
     * Marks the name and notes of [sessionId] as the owner's, in one transaction, for the debug
     * tools (DebugToolsActivity's mark-owner-edits), when he says he renamed a recording or edited its
     * notes before version 13 recorded it: a finishing made again over its readings then leaves them
     * as they are (design R9).
     *
     * @return how many of its name and notes were marked now
     */
    @Transaction
    open suspend fun markOwnersEdits(sessionId: String, now: Long): Int =
        markNameTheOwners(sessionId) + markNotesTheOwners(sessionId, now)

    companion object {
        /** source_segment_ids of a note that cites no line: an empty JSON array. */
        const val NO_SOURCES = "[]"
    }
}
