package com.satori.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.satori.data.database.entity.MindLinkEntity
import com.satori.data.database.entity.MindNodeEntity
import kotlinx.coroutines.flow.Flow

// ── Projections (data layer only, never exposed to domain) ────────────────────

/**
 * A node with how many recordings are linked to it and when the newest was made (see the domain
 * `MindNodeItem`), counted by the query that reads it.
 */
data class MindNodeCountRow(
    @Embedded val node: MindNodeEntity,
    @ColumnInfo(name = "recording_count") val recordingCount: Int,
    @ColumnInfo(name = "last_recorded_at") val lastRecordedAt: Long
)

/** A link with the date of its recording (see the domain `MindLink.recordedAt`). */
data class MindLinkRow(
    @Embedded val link: MindLinkEntity,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long
)

/**
 * One topic of one recording with its theme, the link and the recording's date (see the domain
 * `MindSessionTopic`). Both nodes come from mind_nodes, so their columns carry a prefix.
 */
data class MindSessionTopicRow(
    @Embedded(prefix = "topic_") val topic: MindNodeEntity,
    @Embedded(prefix = "theme_") val theme: MindNodeEntity,
    @Embedded(prefix = "link_") val link: MindLinkEntity,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long
)

// ── DAO ───────────────────────────────────────────────────────────────────────

/**
 * DAO for the mind map: themes and topics in mind_nodes, links from topics to recordings in
 * mind_links, and in mind_map_skips the recordings read without a topic (see the domain
 * `MindMapRepository`).
 *
 * What a node counts (its recordings, the newest one's date) is read from its links whenever it
 * is asked for. The queries that count or date read mind_links and transcription_sessions too, so
 * Room re-runs them when a recording is linked, unlinked or deleted.
 *
 * "Storing order" is SQLite's rowid order: a row gets a higher rowid than every row before it, a
 * link replaced on its key is stored anew at the end, and a node updated in place keeps its place.
 * VACUUM copies rows in rowid order, so the order survives it even where rowids are renumbered.
 *
 * Deleting a recording goes through [deleteSessionAndTidyMap] or [deleteAllSessionsAndMap], which
 * keep the map whole in the delete's own transaction.
 */
@Dao
abstract class MindMapDao {

    // ========== Reads ==========

    /**
     * Every theme that a recording is linked to through one of its topics, with its number of
     * recordings (each counted once, however many of its topics it speaks of) and the newest
     * one's date: most recordings first, then the most recently recorded, then label and id.
     */
    @Query(
        """
        SELECT theme.*,
               COUNT(DISTINCT link.session_id) AS recording_count,
               MAX(sess.created_at)            AS last_recorded_at
        FROM   mind_nodes theme
        JOIN   mind_nodes topic                ON topic.parent_id = theme.id
        JOIN   mind_links link                 ON link.node_id = topic.id
        JOIN   transcription_sessions sess     ON sess.id = link.session_id
        WHERE  theme.kind = '$THEME'
        GROUP  BY theme.id
        ORDER  BY recording_count DESC, last_recorded_at DESC, theme.label, theme.id
        """
    )
    abstract fun observeThemes(): Flow<List<MindNodeCountRow>>

    /**
     * The topics under [parentId] that a recording is linked to, counted and ordered as
     * [observeThemes] does. Empty for a topic (nothing is under a topic) and for an unknown id.
     */
    @Query(
        """
        SELECT topic.*,
               COUNT(DISTINCT link.session_id) AS recording_count,
               MAX(sess.created_at)            AS last_recorded_at
        FROM   mind_nodes topic
        JOIN   mind_links link                 ON link.node_id = topic.id
        JOIN   transcription_sessions sess     ON sess.id = link.session_id
        WHERE  topic.parent_id = :parentId AND topic.kind = '$TOPIC'
        GROUP  BY topic.id
        ORDER  BY recording_count DESC, last_recorded_at DESC, topic.label, topic.id
        """
    )
    abstract fun observeChildren(parentId: String): Flow<List<MindNodeCountRow>>

    @Query("SELECT * FROM mind_nodes WHERE id = :id")
    abstract fun observeNode(id: String): Flow<MindNodeEntity?>

    /**
     * The links of [nodeId] with their recordings' dates: a topic's own, or for a theme those of
     * all its topics. Newest recording first, the links of one recording in storing order.
     */
    @Query(
        """
        SELECT link.*, sess.created_at AS recorded_at
        FROM   mind_links link
        JOIN   transcription_sessions sess ON sess.id = link.session_id
        WHERE  link.node_id = :nodeId
           OR  link.node_id IN (SELECT id FROM mind_nodes WHERE parent_id = :nodeId)
        ORDER  BY sess.created_at DESC, link.rowid
        """
    )
    abstract fun observeLinks(nodeId: String): Flow<List<MindLinkRow>>

    /**
     * Every topic the recording [sessionId] is linked to, with its theme and the link, in storing
     * order: the mapping step stores a recording's links in the order the AI named its topics.
     */
    @Query(
        """
        SELECT topic.id AS topic_id, topic.kind AS topic_kind, topic.label AS topic_label,
               topic.match_key AS topic_match_key, topic.parent_id AS topic_parent_id,
               topic.summary AS topic_summary, topic.summary_updated_at AS topic_summary_updated_at,
               topic.summary_stale AS topic_summary_stale, topic.pinned AS topic_pinned,
               topic.created_at AS topic_created_at, topic.updated_at AS topic_updated_at,
               theme.id AS theme_id, theme.kind AS theme_kind, theme.label AS theme_label,
               theme.match_key AS theme_match_key, theme.parent_id AS theme_parent_id,
               theme.summary AS theme_summary, theme.summary_updated_at AS theme_summary_updated_at,
               theme.summary_stale AS theme_summary_stale, theme.pinned AS theme_pinned,
               theme.created_at AS theme_created_at, theme.updated_at AS theme_updated_at,
               link.node_id AS link_node_id, link.session_id AS link_session_id,
               link.points AS link_points, link.segment_ids AS link_segment_ids,
               link.created_at AS link_created_at,
               sess.created_at AS recorded_at
        FROM   mind_links link
        JOIN   mind_nodes topic                ON topic.id = link.node_id
        JOIN   mind_nodes theme                ON theme.id = topic.parent_id
        JOIN   transcription_sessions sess     ON sess.id = link.session_id
        WHERE  link.session_id = :sessionId
        ORDER  BY link.rowid
        """
    )
    abstract fun observeSessionTopics(sessionId: String): Flow<List<MindSessionTopicRow>>

    /** Every node, themes and topics, by created_at and then in storing order. */
    @Query("SELECT * FROM mind_nodes ORDER BY created_at, rowid")
    abstract fun observeAllNodes(): Flow<List<MindNodeEntity>>

    /** [observeAllNodes], read once, for reads inside a transaction. */
    @Query("SELECT * FROM mind_nodes ORDER BY created_at, rowid")
    abstract suspend fun getAllNodes(): List<MindNodeEntity>

    /**
     * The recordings waiting to be mapped, newest first: no link, and complete transcript of at
     * least [minChars] characters, counting each complete segment with the spaces at its ends
     * removed (SQLite's TRIM). A recording read without topics ([markReadWithoutTopics]) is left
     * out while that count is the one its mark holds. Room re-runs it as segments, links or marks
     * are written; the caller drops the repeats.
     */
    @Query(
        """
        SELECT unmapped.id
        FROM  (SELECT sess.id, sess.created_at,
                      (SELECT COALESCE(SUM(LENGTH(TRIM(seg.text))), 0)
                       FROM   transcription_segments seg
                       WHERE  seg.session_id = sess.id AND seg.is_complete = 1) AS chars
               FROM   transcription_sessions sess
               WHERE  NOT EXISTS (SELECT 1 FROM mind_links link WHERE link.session_id = sess.id)) unmapped
        WHERE  unmapped.chars >= :minChars
          AND  NOT EXISTS (SELECT 1 FROM mind_map_skips skip
                           WHERE skip.session_id = unmapped.id AND skip.chars = unmapped.chars)
        ORDER  BY unmapped.created_at DESC, unmapped.id
        """
    )
    abstract fun observeUnmappedSessionIds(minChars: Int): Flow<List<String>>

    /**
     * Marks the recording [sessionId] as read without topics at [now], in place of an earlier
     * mark, while its complete transcript, counted as [observeUnmappedSessionIds] counts it, has
     * the [chars] characters it had when it was read. Nothing is written for a recording that is
     * gone, nor for one whose transcript changed since it was read.
     */
    @Query(
        """
        INSERT OR REPLACE INTO mind_map_skips (session_id, chars, read_at)
        SELECT sess.id, :chars, :now
        FROM   transcription_sessions sess
        WHERE  sess.id = :sessionId
          AND  (SELECT COALESCE(SUM(LENGTH(TRIM(seg.text))), 0)
                FROM   transcription_segments seg
                WHERE  seg.session_id = sess.id AND seg.is_complete = 1) = :chars
        """
    )
    abstract suspend fun markReadWithoutTopics(sessionId: String, chars: Int, now: Long)

    /** Removes the mark of [markReadWithoutTopics]. Part of [link] and [forgetSession]. */
    @Query("DELETE FROM mind_map_skips WHERE session_id = :sessionId")
    abstract suspend fun deleteSkipOfSession(sessionId: String)

    // ========== Nodes ==========

    @Query("SELECT * FROM mind_nodes WHERE id = :id")
    abstract suspend fun getNode(id: String): MindNodeEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM mind_nodes WHERE id = :id AND kind = '$THEME')")
    abstract suspend fun isTheme(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM mind_nodes WHERE id = :id AND kind = '$TOPIC')")
    abstract suspend fun isTopic(id: String): Boolean

    /** Inserts a new node; fails on an existing id instead of replacing it. Part of [upsertNode]. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertNode(node: MindNodeEntity)

    /** Writes the columns an update of a node may change. Part of [upsertNode]. */
    @Query(
        """
        UPDATE mind_nodes
        SET    label = :label, match_key = :matchKey, parent_id = :parentId, updated_at = :updatedAt
        WHERE  id = :id
        """
    )
    abstract suspend fun updateNode(id: String, label: String, matchKey: String, parentId: String?, updatedAt: Long)

    /**
     * Inserts [node], or updates the stored node with its id, in one transaction, so the map stays
     * a two-level tree:
     * - The stored kind wins over [node]'s, and a theme is stored without a parent.
     * - A topic is stored only under a theme that exists, so one whose theme went in a cleanup
     *   meanwhile is dropped rather than failing on the foreign key.
     * - An update writes label, match key, parent and updated_at only. The summary fields and the
     *   pin keep their stored values, so a copy read before a summary or a pin was written can't
     *   undo it, and created_at stays when the node was made.
     * - A new theme is stored with the summary and pin it carries; a new topic without them,
     *   since only themes have them.
     *
     * @return true if it was stored, false if it was a topic without a theme
     */
    @Transaction
    open suspend fun upsertNode(node: MindNodeEntity): Boolean {
        val stored = getNode(node.id)
        val kind = stored?.kind ?: node.kind
        val parentId = when {
            kind == THEME -> null
            node.parentId != null && isTheme(node.parentId) -> node.parentId
            else -> return false
        }
        if (stored != null) {
            updateNode(node.id, node.label, node.matchKey, parentId, node.updatedAt)
        } else if (kind == THEME) {
            insertNode(node.copy(parentId = null))
        } else {
            insertNode(
                node.copy(
                    parentId = parentId,
                    summary = null,
                    summaryUpdatedAt = null,
                    summaryStale = false,
                    pinned = false
                )
            )
        }
        return true
    }

    /**
     * Stores [summary] as the summary of the theme [id], written at [at], which is also its
     * updated_at, and clears its stale mark. Topics and unknown ids are left alone.
     */
    @Query(
        """
        UPDATE mind_nodes
        SET    summary = :summary, summary_updated_at = :at, updated_at = :at, summary_stale = 0
        WHERE  id = :id AND kind = '$THEME'
        """
    )
    abstract suspend fun setSummary(id: String, summary: String, at: Long)

    /** Marks the themes among [ids] stale. Part of [markStale], which keeps each list short. */
    @Query("UPDATE mind_nodes SET summary_stale = 1 WHERE kind = '$THEME' AND id IN (:ids)")
    abstract suspend fun markStaleRows(ids: List<String>)

    /**
     * Marks the themes among [ids] stale, in one transaction; topics and unknown ids are skipped.
     * The ids go in lists of [MAX_BOUND_IDS], under the 999 bound parameters older SQLite builds
     * allow in one statement.
     */
    @Transaction
    open suspend fun markStale(ids: Collection<String>) {
        ids.distinct().chunked(MAX_BOUND_IDS).forEach { markStaleRows(it) }
    }

    /** Pins or unpins the theme [id]; topics and unknown ids are left alone. */
    @Query("UPDATE mind_nodes SET pinned = :pinned WHERE id = :id AND kind = '$THEME'")
    abstract suspend fun setPinned(id: String, pinned: Boolean)

    // ========== Links ==========

    @Query("SELECT EXISTS(SELECT 1 FROM transcription_sessions WHERE id = :sessionId)")
    abstract suspend fun sessionExists(sessionId: String): Boolean

    /**
     * Inserts [link], replacing the link of the same topic and recording. REPLACE is safe here:
     * nothing refers to a link. Part of [link].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOrReplaceLink(link: MindLinkEntity)

    /**
     * Stores [link], replacing the one between the same topic and recording, in one transaction.
     * Nothing is stored when its node isn't a topic (links join topics only; a theme reaches its
     * recordings through its topics) or when the topic or the recording is gone, deleted while
     * the recording was being mapped, say: the write is dropped instead of failing on the
     * foreign key. A recording with a link is mapped, so a mark of [markReadWithoutTopics] goes.
     *
     * @return true if it was stored
     */
    @Transaction
    open suspend fun link(link: MindLinkEntity): Boolean {
        if (!isTopic(link.nodeId) || !sessionExists(link.sessionId)) return false
        insertOrReplaceLink(link)
        deleteSkipOfSession(link.sessionId)
        return true
    }

    /**
     * Marks stale the themes the recording [sessionId] is linked to through their topics. Part of
     * [forgetSession] and [deleteSessionAndTidyMap], before the links go.
     */
    @Query(
        """
        UPDATE mind_nodes SET summary_stale = 1
        WHERE  kind = '$THEME'
          AND  id IN (SELECT topic.parent_id
                      FROM   mind_links link
                      JOIN   mind_nodes topic ON topic.id = link.node_id
                      WHERE  link.session_id = :sessionId)
        """
    )
    abstract suspend fun markThemesOfSessionStale(sessionId: String)

    /** Part of [forgetSession]. */
    @Query("DELETE FROM mind_links WHERE session_id = :sessionId")
    abstract suspend fun deleteLinksOfSession(sessionId: String)

    /**
     * Drops every link of the recording [sessionId] and marks the themes it was linked to stale,
     * in one transaction. The nodes stay, with their pins and summaries, for the recording's new
     * links; [cleanup] removes those it isn't linked to again. A mark of [markReadWithoutTopics]
     * goes too: the recording is being mapped again.
     */
    @Transaction
    open suspend fun forgetSession(sessionId: String) {
        markThemesOfSessionStale(sessionId)
        deleteLinksOfSession(sessionId)
        deleteSkipOfSession(sessionId)
    }

    // ========== Tidying ==========

    /** Part of [cleanup]. */
    @Query("DELETE FROM mind_nodes WHERE kind = '$TOPIC' AND id NOT IN (SELECT node_id FROM mind_links)")
    abstract suspend fun deleteTopicsWithoutLinks()

    /**
     * Part of [cleanup]. The subquery leaves out the themes' own NULL parent_id: one NULL in a
     * NOT IN list makes the test unknown for every row, and nothing would be deleted.
     */
    @Query(
        """
        DELETE FROM mind_nodes
        WHERE  kind = '$THEME'
          AND  id NOT IN (SELECT parent_id FROM mind_nodes WHERE parent_id IS NOT NULL)
        """
    )
    abstract suspend fun deleteThemesWithoutTopics()

    /**
     * Removes the topics without links, then the themes without topics, in one transaction, in
     * that order, so a theme whose last topic goes in the first step goes in the second.
     */
    @Transaction
    open suspend fun cleanup() {
        deleteTopicsWithoutLinks()
        deleteThemesWithoutTopics()
    }

    // ========== Deleting recordings ==========

    /** Deletes only the recording's row. Use [deleteSessionAndTidyMap]. */
    @Query("DELETE FROM transcription_sessions WHERE id = :sessionId")
    abstract suspend fun deleteSessionRow(sessionId: String)

    /** Deletes only the recordings' rows. Use [deleteAllSessionsAndMap]. */
    @Query("DELETE FROM transcription_sessions")
    abstract suspend fun deleteAllSessionRows()

    /**
     * Deletes the recording [sessionId] and keeps the map whole, in one transaction:
     * 1. the themes that lose it are marked stale, while its links still tell which they are;
     * 2. its row is deleted: its segments, notes and links go with it (CASCADE) and its to-dos
     *    stay without it (SET NULL);
     * 3. [cleanup] removes the topics and themes it leaves without links.
     *
     * Here rather than in TranscriptionSessionDao, so no way of deleting a recording can skip
     * the map.
     */
    @Transaction
    open suspend fun deleteSessionAndTidyMap(sessionId: String) {
        markThemesOfSessionStale(sessionId)
        deleteSessionRow(sessionId)
        cleanup()
    }

    /**
     * Deletes every recording and, since nothing is then linked, the whole map, in one
     * transaction. To-dos stay without their recordings (SET NULL).
     */
    @Transaction
    open suspend fun deleteAllSessionsAndMap() {
        deleteAllSessionRows()
        cleanup()
    }

    companion object {
        /** mind_nodes.kind of a theme (the domain `MindNodeKind.THEME`). */
        const val THEME = "THEME"

        /** mind_nodes.kind of a topic (the domain `MindNodeKind.TOPIC`). */
        const val TOPIC = "TOPIC"

        /** The most ids [markStale] binds in one statement. */
        const val MAX_BOUND_IDS = 500
    }
}
