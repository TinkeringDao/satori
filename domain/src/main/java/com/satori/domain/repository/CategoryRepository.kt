package com.satori.domain.repository

import com.satori.domain.model.RecordingCategory
import kotlinx.coroutines.flow.Flow

/** Stores recording categories (see [RecordingCategory]). */
interface CategoryRepository {

    /** All categories by sortOrder. Never empty once [ensureStarterCategories] has run. */
    fun observeAll(): Flow<List<RecordingCategory>>

    suspend fun get(id: String): RecordingCategory?

    suspend fun upsert(category: RecordingCategory)

    /** Deletes a category; its recordings keep their transcript and lose only the label. */
    suspend fun delete(id: String)

    /**
     * Adds the starter categories if none exist yet, with names in the app language.
     *
     * @param names Localized names keyed by the starter id (see STARTER_IDS)
     */
    suspend fun ensureStarterCategories(names: Map<String, String>)

    companion object {
        /** Stable ids of the starter categories: voice note, meeting, idea, to-do. */
        val STARTER_IDS = listOf("voice-note", "meeting", "idea", "todo")
    }
}
