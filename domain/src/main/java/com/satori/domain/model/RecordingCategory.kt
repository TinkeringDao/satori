package com.satori.domain.model

/**
 * A named way of recording, such as "Meeting" or "Idea": it picks the recording mode, and every
 * recording keeps its category so lists can be filtered and the quick key can start one.
 *
 * @property id Unique id (UUID, or a stable id for the built-in starter categories)
 * @property name Shown in the app; the user can rename it
 * @property mode How the AI treats recordings in this category
 * @property sortOrder Position in lists, lowest first
 * @property createdAt Creation time (epoch ms)
 */
data class RecordingCategory(
    val id: String,
    val name: String,
    val mode: RecordingMode,
    val sortOrder: Int,
    val createdAt: Long
)
