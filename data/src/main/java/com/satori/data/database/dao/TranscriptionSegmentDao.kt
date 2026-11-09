package com.satori.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.satori.data.database.entity.TranscriptionSegmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * The transcript lines a mind-map link of a recording was taken from: its node, and the line ids
 * as a JSON array, as mind_links stores them. Data layer only.
 */
data class LinkSourcesRow(
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "segment_ids") val segmentIds: String
)

/**
 * DAO for transcription segment operations.
 *
 * Provides operations for managing segments within sessions. An abstract class rather than an
 * interface for [replaceForSession], whose steps Room runs in one transaction.
 */
@Dao
abstract class TranscriptionSegmentDao {

    /**
     * Insert a new segment.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(segment: TranscriptionSegmentEntity)

    /** Insert several segments. Part of [replaceForSession]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(segments: List<TranscriptionSegmentEntity>)

    /**
     * Get all segments for a specific session ordered by timestamp.
     */
    @Query("SELECT * FROM transcription_segments WHERE session_id = :sessionId ORDER BY timestamp ASC")
    abstract fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegmentEntity>>

    /**
     * Get count of complete segments in a session.
     */
    @Query("SELECT COUNT(*) FROM transcription_segments WHERE session_id = :sessionId AND is_complete = 1")
    abstract suspend fun getCompleteSegmentCount(sessionId: String): Int

    /**
     * Update the text of an existing segment.
     */
    @Query("UPDATE transcription_segments SET text = :newText WHERE id = :segmentId")
    abstract suspend fun updateText(segmentId: String, newText: String)

    /**
     * Delete all segments for a session (usually handled by CASCADE).
     */
    @Query("DELETE FROM transcription_segments WHERE session_id = :sessionId")
    abstract suspend fun deleteSegmentsBySession(sessionId: String)

    /**
     * Stamps a session as modified, leaving its other columns alone. Part of [replaceForSession].
     *
     * @return How many sessions it stamped: 0 if there is no such session
     */
    @Query("UPDATE transcription_sessions SET last_modified_at = :modifiedAt WHERE id = :sessionId")
    abstract suspend fun markSessionModified(sessionId: String, modifiedAt: Long): Int

    /** Points a note of a session at other source lines (a JSON array of ids). Part of [replaceForSession]. */
    @Query("UPDATE llm_insights SET source_segment_ids = :sourceIds WHERE id = :id AND session_id = :sessionId")
    abstract suspend fun setInsightSources(sessionId: String, id: String, sourceIds: String)

    /** Points a to-do of a session at other source lines (a JSON array of ids). Part of [replaceForSession]. */
    @Query("UPDATE todos SET source_segment_ids = :sourceIds WHERE id = :id AND session_id = :sessionId")
    abstract suspend fun setTodoSources(sessionId: String, id: String, sourceIds: String)

    /**
     * Points the session's mind-map link to [nodeId] at other source lines (a JSON array of ids).
     * Part of [replaceForSession].
     */
    @Query("UPDATE mind_links SET segment_ids = :sourceIds WHERE node_id = :nodeId AND session_id = :sessionId")
    abstract suspend fun setLinkSources(sessionId: String, nodeId: String, sourceIds: String)

    /** The source lines of every mind-map link of a session, read once. */
    @Query("SELECT node_id, segment_ids FROM mind_links WHERE session_id = :sessionId")
    abstract suspend fun linkSourcesOf(sessionId: String): List<LinkSourcesRow>

    /**
     * Replaces all segments of a session with [segments] and points its notes, to-dos and
     * mind-map links in [insightSources], [todoSources] and [linkSources] (JSON arrays of the new
     * ids, by note id, to-do id and node id) at them, in one transaction: readers see the old
     * transcript or the new one, never none, and a failure on the way (the session deleted
     * meanwhile, say) leaves everything as it was.
     */
    @Transaction
    open suspend fun replaceForSession(
        sessionId: String,
        segments: List<TranscriptionSegmentEntity>,
        insightSources: Map<String, String>,
        todoSources: Map<String, String>,
        modifiedAt: Long,
        linkSources: Map<String, String> = emptyMap()
    ) {
        check(markSessionModified(sessionId, modifiedAt) == 1) { "No recording to replace the transcript of" }
        deleteSegmentsBySession(sessionId)
        insertAll(segments)
        insightSources.forEach { (id, sourceIds) -> setInsightSources(sessionId, id, sourceIds) }
        todoSources.forEach { (id, sourceIds) -> setTodoSources(sessionId, id, sourceIds) }
        linkSources.forEach { (nodeId, sourceIds) -> setLinkSources(sessionId, nodeId, sourceIds) }
    }
}
