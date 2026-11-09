package com.satori.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.satori.data.database.entity.TranscriptionSessionEntity
import kotlinx.coroutines.flow.Flow

/** A session row of the recordings list (data layer only, never exposed to domain). */
data class SessionListRow(
    @Embedded val session: TranscriptionSessionEntity,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "has_transcript") val hasTranscript: Boolean,
    @ColumnInfo(name = "has_notes") val hasNotes: Boolean
)

/**
 * DAO for transcription session operations.
 *
 * Provides CRUD operations for managing transcription sessions. Sessions are deleted through
 * [MindMapDao.deleteSessionAndTidyMap] and [MindMapDao.deleteAllSessionsAndMap], which keep the
 * mind map whole in the same transaction.
 */
@Dao
interface TranscriptionSessionDao {

    /**
     * Insert a new session.
     *
     * Only for new ids: REPLACE deletes an existing row first, which deletes its segments,
     * insights and mind-map links (CASCADE) and detaches its todos (SET NULL). The nodes those
     * links held stay until [MindMapDao.cleanup].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: TranscriptionSessionEntity)

    /**
     * Update an existing session.
     *
     * Writes every column, so a copy read earlier undoes concurrent changes (a rename, a deleted
     * category). Prefer the single-column updates below.
     */
    @Update
    suspend fun update(session: TranscriptionSessionEntity)

    /** Stamp the session as modified, leaving its other columns alone. */
    @Query("UPDATE transcription_sessions SET last_modified_at = :modifiedAt WHERE id = :sessionId")
    suspend fun updateLastModified(sessionId: String, modifiedAt: Long)

    /**
     * Set or clear the category of a session.
     *
     * An id with no category row is ignored (the session keeps its category), so a stale id,
     * such as one from a category deleted meanwhile, can't leave a dangling label.
     *
     * @return 1 if the session was changed, 0 if it or the category doesn't exist
     */
    @Query(
        """
        UPDATE transcription_sessions SET category_id = :categoryId
        WHERE id = :sessionId
          AND (:categoryId IS NULL OR EXISTS (SELECT 1 FROM categories WHERE id = :categoryId))
        """
    )
    suspend fun updateCategory(sessionId: String, categoryId: String?): Int

    /**
     * Set the category of a session and the mode it records in, together in one statement, which
     * SQLite runs as one transaction: a recording switched to another kind ("Record as", "Change
     * type") is never filed under one category with the other's mode. Only into a category that
     * exists and records in [mode], and neither the session nor [mode] may be a translation: a
     * Translate recording keeps its kind, and none becomes one. [mode] is a RecordingMode name.
     *
     * @return 1 if the session was changed, 0 if it was left as it was
     */
    @Query(
        """
        UPDATE transcription_sessions SET category_id = :categoryId, mode = :mode
        WHERE id = :sessionId
          AND transcription_sessions.mode != 'REAL_TIME_TRANSLATION'
          AND :mode != 'REAL_TIME_TRANSLATION'
          AND EXISTS (SELECT 1 FROM categories c WHERE c.id = :categoryId AND c.mode = :mode)
        """
    )
    suspend fun updateCategoryAndMode(sessionId: String, categoryId: String, mode: String): Int

    /**
     * Get all sessions ordered by creation time (most recent first), each with the name of its
     * category and whether it has any transcript line or note.
     *
     * The category name is null when the session has no category or its category row is gone
     * (category_id has no foreign key, see [TranscriptionSessionEntity.categoryId]). The two
     * EXISTS columns are what lets the list hide recordings that kept nothing, without loading
     * their text. Room re-runs the query when sessions, categories, segments or insights change.
     */
    @Query(
        """
        SELECT s.*,
               (SELECT c.name FROM categories c WHERE c.id = s.category_id) AS category_name,
               EXISTS(SELECT 1 FROM transcription_segments seg WHERE seg.session_id = s.id) AS has_transcript,
               EXISTS(SELECT 1 FROM llm_insights ins WHERE ins.session_id = s.id) AS has_notes
        FROM transcription_sessions s
        ORDER BY s.created_at DESC
        """
    )
    fun getAllSessions(): Flow<List<SessionListRow>>

    /**
     * Get a specific session by ID.
     */
    @Query("SELECT * FROM transcription_sessions WHERE id = :sessionId")
    fun getSession(sessionId: String): Flow<TranscriptionSessionEntity?>

    /**
     * The owner's name for a session, typed on its page (Rename); null clears it (the session becomes
     * unnamed). Stored with name_by_owner set while there is a name, in one statement, so the app's
     * own naming ([setNameIfUnnamed], [replaceName]) never takes its place; a cleared name is no one's.
     */
    @Query(
        "UPDATE transcription_sessions SET name = :name, name_by_owner = CASE WHEN :name IS NULL THEN 0 ELSE 1 END " +
            "WHERE id = :sessionId"
    )
    suspend fun updateName(sessionId: String, name: String?)

    /**
     * Set the name only while the session has none (null or blank): the app's name, name_by_owner 0.
     *
     * @return The number of sessions changed: 1 if named, 0 if it already had a name.
     */
    @Query(
        "UPDATE transcription_sessions SET name = :name, name_by_owner = 0 " +
            "WHERE id = :sessionId AND (name IS NULL OR TRIM(name) = '')"
    )
    suspend fun setNameIfUnnamed(sessionId: String, name: String): Int

    /**
     * Set the name only while the session's name is still [expected] and the app's, not one the owner
     * typed (since version 13): the app's name again, name_by_owner 0.
     *
     * @return The number of sessions changed: 1 if renamed, 0 if it has another name by now, or the owner's.
     */
    @Query(
        "UPDATE transcription_sessions SET name = :name, name_by_owner = 0 " +
            "WHERE id = :sessionId AND name = :expected AND name_by_owner = 0"
    )
    suspend fun replaceName(sessionId: String, expected: String, name: String): Int

    /**
     * The Malayalam recordings the backfill of readings is to make again (0.8.0, design R9): those
     * recorded in Malayalam (language 'ml', RecordingLanguage.MALAYALAM's code) or whose transcript
     * the finishing found Malayalam (input_language 'ml'), whose redo is due ([setRedoDue]), without
     * a finishing job, and with fewer than [maxFailures] failures counted against them
     * ([countBackfillFailures]); oldest first. Which of them have lines the reader reads is the
     * caller's to tell, by the lines' letters.
     */
    @Query(
        """
        SELECT s.id FROM transcription_sessions s
        WHERE  (s.language = 'ml' OR s.input_language = 'ml')
          AND  s.redo_due = 1
          AND  s.backfill_failures < :maxFailures
          AND  NOT EXISTS (SELECT 1 FROM finishing_jobs j WHERE j.session_id = s.id)
        ORDER  BY s.created_at, s.id
        """
    )
    suspend fun backfillCandidates(maxFailures: Int): List<String>

    /**
     * Whether the backfill of readings is to make the recording's AI steps again over its readings
     * (redo_due): due once its finishing ended without them for want of its readings, done once a
     * finishing made again has written its notes or left them deliberately.
     *
     * @return 1 if the session exists, 0 if it doesn't
     */
    @Query("UPDATE transcription_sessions SET redo_due = :due WHERE id = :sessionId")
    suspend fun setRedoDue(sessionId: String, due: Boolean): Int

    /**
     * A finishing of the session ended with lines it has still never read: [failures] more are
     * counted against its backfill of readings.
     *
     * @return 1 if the session exists, 0 if it doesn't
     */
    @Query("UPDATE transcription_sessions SET backfill_failures = backfill_failures + :failures WHERE id = :sessionId")
    suspend fun countBackfillFailures(sessionId: String, failures: Int): Int

    /**
     * Set the output language only while the session has none (null or blank).
     *
     * @return The number of sessions changed: 1 if set, 0 if it already had a language.
     */
    @Query(
        "UPDATE transcription_sessions SET output_language = :language " +
            "WHERE id = :sessionId AND (output_language IS NULL OR TRIM(output_language) = '')"
    )
    suspend fun setOutputLanguageIfUnset(sessionId: String, language: String): Int

    /**
     * Persist the total recording duration for a session.
     */
    @Query("UPDATE transcription_sessions SET duration_ms = :durationMs WHERE id = :sessionId")
    suspend fun updateDuration(sessionId: String, durationMs: Long)

    /**
     * Set where the session was recorded, or with nulls clear it: the four place columns in one
     * statement, so a place is never half stored.
     */
    @Query(
        "UPDATE transcription_sessions SET latitude = :latitude, longitude = :longitude, " +
            "location_accuracy_m = :accuracyMeters, place_name = :placeName WHERE id = :sessionId"
    )
    suspend fun updatePlace(
        sessionId: String,
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Float?,
        placeName: String?
    )

    /** Set or clear the id of the speech model that made the session's transcript. */
    @Query("UPDATE transcription_sessions SET transcript_model = :modelId WHERE id = :sessionId")
    suspend fun updateTranscriptModel(sessionId: String, modelId: String?)

    /**
     * Set the language the session is recorded in: a RecordingLanguage code, or null for English
     * or German. One column, so a switch never undoes a change to another one meanwhile.
     *
     * @return 1 if the session exists, 0 if it doesn't
     */
    @Query("UPDATE transcription_sessions SET language = :code WHERE id = :sessionId")
    suspend fun updateLanguage(sessionId: String, code: String?): Int

    /** Set the language that was spoken in the session, as the finishing found it ("en", "de", "ml"). */
    @Query("UPDATE transcription_sessions SET input_language = :code WHERE id = :sessionId")
    suspend fun updateInputLanguage(sessionId: String, code: String)

    /**
     * Get session count (for debugging/stats).
     */
    @Query("SELECT COUNT(*) FROM transcription_sessions")
    suspend fun getSessionCount(): Int
}
