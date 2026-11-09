package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One note of a recording as it was before a finishing made again over its readings first replaced
 * its notes ([KeptBeforeRedoEntity]; since version 13): the llm_insights row as it was, kept with
 * the row of its recording's name, and gone with its recording (CASCADE).
 */
@Entity(
    tableName = "notes_kept_before_redo",
    foreignKeys = [
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
data class NoteKeptBeforeRedoEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "title")
    val title: String?,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "tasks")
    val tasks: String?,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "source_segment_ids")
    val sourceSegmentIds: String,

    @ColumnInfo(name = "edited_at")
    val editedAt: Long?
)
