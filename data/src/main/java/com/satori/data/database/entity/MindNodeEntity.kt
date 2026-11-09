package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a theme or a topic of the mind map (see the domain `MindNode`).
 *
 * The map is a two-level tree in one table: a theme has no parent, and a topic's `parent_id` is
 * its theme. Deleting a theme deletes its topics (ON DELETE CASCADE) and with them their links
 * ([MindLinkEntity]). So a row is never written with REPLACE, which deletes the old row first and
 * would take the theme's whole branch with it; [com.satori.data.database.dao.MindMapDao]
 * inserts new rows and updates existing ones in place.
 *
 * [kind] holds [com.satori.data.database.dao.MindMapDao.THEME] or
 * [com.satori.data.database.dao.MindMapDao.TOPIC]. The summary fields and the pin belong
 * to themes; a topic keeps them empty. How many recordings a node has is not stored: the DAO
 * counts its links each time, so it can't drift from them.
 *
 * The parent_id index serves a theme's topics and the foreign key; the match_key index serves
 * lookups of a node by the normal form of its label.
 */
@Entity(
    tableName = "mind_nodes",
    foreignKeys = [
        ForeignKey(
            entity = MindNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["parent_id"]),
        Index(value = ["match_key"])
    ]
)
data class MindNodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "match_key")
    val matchKey: String,

    @ColumnInfo(name = "parent_id")
    val parentId: String?,

    @ColumnInfo(name = "summary")
    val summary: String?,

    @ColumnInfo(name = "summary_updated_at")
    val summaryUpdatedAt: Long?,

    @ColumnInfo(name = "summary_stale", defaultValue = "0")
    val summaryStale: Boolean,

    @ColumnInfo(name = "pinned", defaultValue = "0")
    val pinned: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
