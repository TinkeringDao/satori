package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the English reading of one transcript line (see the domain `SegmentReading`):
 * one row per line, which the line takes with it when it goes (CASCADE), as when Transcribe again
 * or the joining after Stop replace a transcript, or the recording is deleted. An edit of the
 * line's text leaves the row, whose [sourceSha256] then no longer matches it: the domain reads it
 * as no reading. Indexed by recording, as the AI steps read a recording's lines and readings
 * together. It holds English only, never the line's own text.
 *
 * @property score The mean log-probability per token, null when the model wasn't asked
 * @property windowId The window of lines read together (design D1), null for a line read alone
 */
@Entity(
    tableName = "segment_readings",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segment_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"])
    ]
)
data class SegmentReadingEntity(
    @PrimaryKey
    @ColumnInfo(name = "segment_id")
    val segmentId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "source_sha256")
    val sourceSha256: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "model")
    val model: String,

    @ColumnInfo(name = "score")
    val score: Double?,

    @ColumnInfo(name = "window_id")
    val windowId: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
