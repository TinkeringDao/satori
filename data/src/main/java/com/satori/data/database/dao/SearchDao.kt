package com.satori.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// ── Projection data classes (data layer only, never exposed to domain) ────────

data class SegmentSearchResult(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "snippet_text") val snippetText: String,
    @ColumnInfo(name = "session_name") val sessionName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "mode") val mode: String
)

data class InsightSearchResult(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "snippet_text") val snippetText: String,
    @ColumnInfo(name = "session_name") val sessionName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "mode") val mode: String
)

data class SessionNameSearchResult(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "session_name") val sessionName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "mode") val mode: String
)

data class PlaceSearchResult(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "place_name") val placeName: String,
    @ColumnInfo(name = "session_name") val sessionName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "mode") val mode: String
)

// ── DAO ───────────────────────────────────────────────────────────────────────

/**
 * DAO for text search queries that JOIN across multiple tables.
 *
 * Every query takes the text as two LIKE patterns, `query` and `alternate` (see
 * TranscriptionRepositoryImpl): the same text with the case of its first letter flipped, so that
 * "über" also finds "Über" if the SQLite build only ignores case for ASCII letters. The patterns
 * are escaped with a backslash, so "50%" and "a_b" search for what was typed.
 */
@Dao
interface SearchDao {

    /**
     * Find transcription segments whose text contains [query] (or [alternate]).
     * Returns one row per matching segment with the containing session metadata, newest
     * recording first and the lines of a recording in the order they were said.
     */
    @Query("""
        SELECT seg.id              AS id,
               seg.session_id      AS session_id,
               seg.text            AS snippet_text,
               sess.name           AS session_name,
               (SELECT c.name FROM categories c WHERE c.id = sess.category_id) AS category_name,
               sess.created_at     AS created_at,
               sess.mode           AS mode
        FROM   transcription_segments seg
        JOIN   transcription_sessions sess ON seg.session_id = sess.id
        WHERE  seg.text LIKE '%' || :query || '%' ESCAPE '\'
           OR  seg.text LIKE '%' || :alternate || '%' ESCAPE '\'
        ORDER  BY sess.created_at DESC, seg.timestamp ASC
    """)
    fun searchSegments(query: String, alternate: String): Flow<List<SegmentSearchResult>>

    /**
     * Find LLM insights whose title or content contains [query] (or [alternate]).
     * Returns one row per matching insight. The snippet comes from the title when the title
     * matched, else from the content, so it always holds the match.
     */
    @Query("""
        SELECT ins.id                                  AS id,
               ins.session_id                          AS session_id,
               CASE WHEN ins.title LIKE '%' || :query || '%' ESCAPE '\'
                      OR ins.title LIKE '%' || :alternate || '%' ESCAPE '\'
                    THEN ins.title ELSE ins.content END AS snippet_text,
               sess.name                               AS session_name,
               (SELECT c.name FROM categories c WHERE c.id = sess.category_id) AS category_name,
               sess.created_at                         AS created_at,
               sess.mode                               AS mode
        FROM   llm_insights ins
        JOIN   transcription_sessions sess ON ins.session_id = sess.id
        WHERE  ins.title   LIKE '%' || :query || '%' ESCAPE '\'
           OR  ins.content LIKE '%' || :query || '%' ESCAPE '\'
           OR  ins.title   LIKE '%' || :alternate || '%' ESCAPE '\'
           OR  ins.content LIKE '%' || :alternate || '%' ESCAPE '\'
        ORDER  BY sess.created_at DESC, ins.timestamp ASC
    """)
    fun searchInsights(query: String, alternate: String): Flow<List<InsightSearchResult>>

    /**
     * Find sessions whose name contains [query] (or [alternate]).
     */
    @Query("""
        SELECT s.id         AS session_id,
               s.name       AS session_name,
               (SELECT c.name FROM categories c WHERE c.id = s.category_id) AS category_name,
               s.created_at AS created_at,
               s.mode       AS mode
        FROM   transcription_sessions s
        WHERE  s.name LIKE '%' || :query || '%' ESCAPE '\'
           OR  s.name LIKE '%' || :alternate || '%' ESCAPE '\'
        ORDER  BY s.created_at DESC
    """)
    fun searchSessionNames(query: String, alternate: String): Flow<List<SessionNameSearchResult>>

    /**
     * Find sessions made at a place whose name contains [query] (or [alternate]). A session
     * without a place, or with a place that has no name, never matches.
     */
    @Query("""
        SELECT s.id         AS session_id,
               s.place_name AS place_name,
               s.name       AS session_name,
               (SELECT c.name FROM categories c WHERE c.id = s.category_id) AS category_name,
               s.created_at AS created_at,
               s.mode       AS mode
        FROM   transcription_sessions s
        WHERE  s.place_name LIKE '%' || :query || '%' ESCAPE '\'
           OR  s.place_name LIKE '%' || :alternate || '%' ESCAPE '\'
        ORDER  BY s.created_at DESC
    """)
    fun searchPlaces(query: String, alternate: String): Flow<List<PlaceSearchResult>>
}
