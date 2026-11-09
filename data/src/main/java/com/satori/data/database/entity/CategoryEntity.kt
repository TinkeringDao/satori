package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a recording category (see the domain `RecordingCategory`).
 *
 * Sessions point at a category through `transcription_sessions.category_id`, which has no
 * foreign key (see [TranscriptionSessionEntity.categoryId]); deleting a category clears that
 * label in code, in the same transaction ([com.satori.data.database.dao.CategoryDao.deleteAndUnlink]).
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "mode")
    val mode: String,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
