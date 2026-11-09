package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a todo (see the domain `Todo`).
 *
 * A todo outlives its recording: deleting the session sets `session_id` to NULL
 * (ON DELETE SET NULL) instead of deleting the todo. The enums are stored by name, and
 * [sourceSegmentIds] as a JSON array string, like [LlmInsightEntity.sourceSegmentIds].
 *
 * The indexes serve the home screen (status, due_at), the reminder alarm (remind_at) and the
 * per-recording list (session_id, which the foreign key needs anyway).
 *
 * `reminder_shown_at` records when the reminder was shown, so each is posted once for each
 * time it is set to (see the domain `Todo.reminderShownAt` and
 * [com.satori.data.database.dao.TodoDao.upsert]).
 */
@Entity(
    tableName = "todos",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["status"]),
        Index(value = ["due_at"]),
        Index(value = ["remind_at"]),
        Index(value = ["session_id"])
    ]
)
data class TodoEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String?,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "certainty")
    val certainty: String,

    @ColumnInfo(name = "capture")
    val capture: String,

    @ColumnInfo(name = "due_at")
    val dueAt: Long?,

    @ColumnInfo(name = "due_has_time")
    val dueHasTime: Boolean,

    @ColumnInfo(name = "time_phrase")
    val timePhrase: String?,

    @ColumnInfo(name = "remind_at")
    val remindAt: Long?,

    @ColumnInfo(name = "reminder_shown_at")
    val reminderShownAt: Long?,

    @ColumnInfo(name = "source_segment_ids")
    val sourceSegmentIds: String, // JSON array string

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long?
)
