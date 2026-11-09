package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * What a recording had before a finishing made again over its readings first replaced any of it
 * (0.8.0, design R9; since version 13): its name, whose it was, and its notes' strategy, one row a
 * recording, written once, before that finishing's first write, and never written over, so it holds
 * the recording as an older version left it. Its notes are [NoteKeptBeforeRedoEntity]'s. Version 12
 * recorded no rename and no note edit, so a name or notes the owner set before then can't be told
 * from the app's, and are replaced as the app's: this keeps them, for the debug tools to put back
 * (DebugToolsActivity's restore-before-redo). The row goes with its recording (CASCADE).
 *
 * @property name The name it had, null for none
 * @property nameByOwner Whether that name was one the owner typed, as far as the database knew
 * @property insightStrategy The strategy of its notes then (an InsightStrategy name)
 * @property keptAt When it was kept, epoch ms
 */
@Entity(
    tableName = "kept_before_redo",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class KeptBeforeRedoEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "name")
    val name: String?,

    @ColumnInfo(name = "name_by_owner")
    val nameByOwner: Boolean,

    @ColumnInfo(name = "insight_strategy")
    val insightStrategy: String,

    @ColumnInfo(name = "kept_at")
    val keptAt: Long
)
