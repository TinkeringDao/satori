package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for one recording to be finished in the background (see the domain `FinishingJob`):
 * one row per recording, from the moment its capture starts until it is finished. Deleting the
 * recording deletes its row (CASCADE). It holds no text, only where the job is.
 *
 * [state] is a `FinishingJobState` name and [nextStep] a `FinishStep` name. The index serves the
 * queue: the rows of a state in queue order.
 */
@Entity(
    tableName = "finishing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["state", "queued_at"])
    ]
)
data class FinishingJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "next_step")
    val nextStep: String?,

    @ColumnInfo(name = "attempts")
    val attempts: Int,

    @ColumnInfo(name = "owner")
    val owner: String,

    @ColumnInfo(name = "recognizer_failed")
    val recognizerFailed: Boolean,

    @ColumnInfo(name = "recovered")
    val recovered: Boolean,

    @ColumnInfo(name = "waits_for_recording")
    val waitsForRecording: Boolean,

    @ColumnInfo(name = "queued_at")
    val queuedAt: Long?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /**
     * The job makes a finished recording's AI steps again from its Reading step (0.8.0, design R9,
     * since version 13): the backfill of readings queued it, and the steps replace what the AI made
     * of the lines as they were said, but what the owner changed.
     */
    @ColumnInfo(name = "redo", defaultValue = "0")
    val redo: Boolean = false
)
