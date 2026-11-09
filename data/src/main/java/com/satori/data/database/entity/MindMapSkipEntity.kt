package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Room entity for a recording the AI read for the mind map without finding a topic in it, so
 * "Map N older recordings" doesn't offer it again while its transcript stays as it was: [chars]
 * is the length of its complete transcript at that read, counted as MindMapDao's unmapped
 * recordings are counted, and a transcript that changed counts differently. Deleting the
 * recording deletes its row (CASCADE). It holds no text.
 *
 * @property readAt When it was read (epoch ms)
 */
@Entity(
    tableName = "mind_map_skips",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MindMapSkipEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "chars")
    val chars: Int,

    @ColumnInfo(name = "read_at")
    val readAt: Long
)
