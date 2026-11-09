package com.satori.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.satori.data.database.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/** DAO for recording categories. */
@Dao
abstract class CategoryDao {

    @Query("SELECT * FROM categories ORDER BY sort_order ASC, created_at ASC, id ASC")
    abstract fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    abstract suspend fun get(id: String): CategoryEntity?

    /**
     * Insert or update by id. An update changes the row in place; it never deletes and re-inserts
     * it the way REPLACE would.
     */
    @Upsert
    abstract suspend fun upsert(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM categories")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnoringExisting(categories: List<CategoryEntity>)

    /** Clears the label from the category's recordings. Part of [deleteAndUnlink]. */
    @Query("UPDATE transcription_sessions SET category_id = NULL WHERE category_id = :categoryId")
    abstract suspend fun clearFromSessions(categoryId: String)

    /**
     * Deletes only the row, and only if another category is left. Use [deleteAndUnlink].
     *
     * @return 1 if it was deleted, 0 if it was the last category or doesn't exist
     */
    @Query("DELETE FROM categories WHERE id = :id AND (SELECT COUNT(*) FROM categories) > 1")
    abstract suspend fun deleteRowUnlessLast(id: String): Int

    /**
     * Deletes a category and clears it from its recordings in one transaction: ON DELETE SET NULL
     * for `transcription_sessions.category_id`, done in code because that column has no foreign key.
     *
     * The last category is never deleted, so the list can't run empty (CategoryRepository.observeAll)
     * however many screens delete at once; it then stays on its recordings too.
     *
     * @return true if it was deleted, false if it was the last one or doesn't exist
     */
    @Transaction
    open suspend fun deleteAndUnlink(id: String): Boolean {
        if (deleteRowUnlessLast(id) == 0) return false
        clearFromSessions(id)
        return true
    }

    /**
     * Inserts [categories] if the table is empty, in one transaction, so two callers can't both
     * seed it.
     *
     * @return true if they were inserted, false if there already were categories
     */
    @Transaction
    open suspend fun insertAllIfEmpty(categories: List<CategoryEntity>): Boolean {
        if (count() > 0) return false
        insertIgnoringExisting(categories)
        return true
    }
}
