package com.satori.domain.usecase.category

import com.satori.domain.model.RecordingCategory
import com.satori.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EnsureStarterCategoriesUseCaseTest {

    private class RecordingRepository : CategoryRepository {
        var names: Map<String, String>? = null

        override suspend fun ensureStarterCategories(names: Map<String, String>) {
            this.names = names
        }

        override fun observeAll(): Flow<List<RecordingCategory>> = error("not used")
        override suspend fun get(id: String): RecordingCategory? = error("not used")
        override suspend fun upsert(category: RecordingCategory) = error("not used")
        override suspend fun delete(id: String) = error("not used")
    }

    @Test
    fun `each name goes to its own starter id`() = runBlocking {
        val repository = RecordingRepository()

        EnsureStarterCategoriesUseCase(repository)(
            voiceNote = "Sprachnotiz",
            meeting = "Besprechung",
            idea = "Idee",
            todo = "Aufgabe"
        )

        assertEquals(
            mapOf("voice-note" to "Sprachnotiz", "meeting" to "Besprechung", "idea" to "Idee", "todo" to "Aufgabe"),
            repository.names
        )
    }

    @Test
    fun `it names every starter category the repository knows`() = runBlocking {
        val repository = RecordingRepository()

        EnsureStarterCategoriesUseCase(repository)("a", "b", "c", "d")

        assertEquals(CategoryRepository.STARTER_IDS.toSet(), repository.names?.keys)
    }
}
