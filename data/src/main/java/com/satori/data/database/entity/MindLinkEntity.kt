package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Room entity for what one recording says about one topic of the mind map (see the domain
 * `MindLink`): one row per topic and recording.
 *
 * Both ends cascade: deleting the recording or the topic deletes the link. A topic or theme left
 * without links is removed in code
 * ([com.satori.data.database.dao.MindMapDao.cleanup]), because SQLite has no cascade for
 * "the last child is gone".
 *
 * [points] and [segmentIds] are JSON array strings, like [LlmInsightEntity.sourceSegmentIds]. The
 * recording's date is not stored here but read from transcription_sessions when the links are
 * read, so mapping an older recording today doesn't make it recent.
 *
 * The primary key's index starts with node_id, so it also serves the node_id foreign key and the
 * lookups by topic; session_id has its own index for the recording's map and for deletes.
 */
@Entity(
    tableName = "mind_links",
    primaryKeys = ["node_id", "session_id"],
    foreignKeys = [
        ForeignKey(
            entity = MindNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TranscriptionSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"])
    ]
)
data class MindLinkEntity(
    @ColumnInfo(name = "node_id")
    val nodeId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "points")
    val points: String, // JSON array string

    @ColumnInfo(name = "segment_ids")
    val segmentIds: String, // JSON array string

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
