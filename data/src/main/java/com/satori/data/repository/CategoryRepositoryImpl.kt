package com.satori.data.repository

import com.satori.data.database.dao.CategoryDao
import com.satori.data.database.entity.CategoryEntity
import com.satori.data.database.mapper.toDomain
import com.satori.data.database.mapper.toEntity
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** [CategoryRepository] on Room. */
class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao
) : CategoryRepository {

    override fun observeAll(): Flow<List<RecordingCategory>> =
        categoryDao.observeAll()
            .map { rows -> rows.map { it.toDomain() } }
            .distinctUntilChanged()

    override suspend fun get(id: String): RecordingCategory? = categoryDao.get(id)?.toDomain()

    override suspend fun upsert(category: RecordingCategory) {
        categoryDao.upsert(category.toEntity())
    }

    /**
     * Deletes the category and, in the same transaction, clears it from its recordings. The last
     * category is not deleted (nor taken off its recordings), which keeps [observeAll] non-empty.
     */
    override suspend fun delete(id: String) {
        categoryDao.deleteAndUnlink(id)
    }

    /**
     * Seeds the starter categories, in [CategoryRepository.STARTER_IDS] order, when the table is
     * empty, and does nothing otherwise: renamed, reordered or deleted categories stay as the user
     * left them. As [delete] never removes the last category, the table is empty only before the
     * first seeding, and [observeAll] stays non-empty from then on.
     *
     * A starter id missing from [names] (or with a blank name) is named after its id, so it is
     * still created and the gap is visible.
     */
    override suspend fun ensureStarterCategories(names: Map<String, String>) {
        val now = System.currentTimeMillis()
        val starters = CategoryRepository.STARTER_IDS.mapIndexed { index, id ->
            CategoryEntity(
                id = id,
                name = names[id]?.trim()?.takeIf { it.isNotEmpty() } ?: id,
                mode = (STARTER_MODES[id] ?: RecordingMode.SIMPLE_LISTENING).name,
                sortOrder = index,
                createdAt = now
            )
        }
        categoryDao.insertAllIfEmpty(starters)
    }

    private companion object {
        /** Modes of the starter categories; a meeting gets the meeting mode, the rest simple notes. */
        val STARTER_MODES = mapOf(
            "voice-note" to RecordingMode.SIMPLE_LISTENING,
            "meeting" to RecordingMode.SHORT_MEETING,
            "idea" to RecordingMode.SIMPLE_LISTENING,
            "todo" to RecordingMode.SIMPLE_LISTENING
        )
    }
}
