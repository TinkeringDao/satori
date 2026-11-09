package com.satori.domain.usecase.category

import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The starter categories follow the app language while they keep one of their own names; one the
 * owner renamed keeps his name (the owner, 2026-10-15).
 */
class StarterCategoriesLanguageTest {

    /** Categories in memory, seeded as the app seeds them: only while there are none. */
    private class InMemoryCategories(initial: List<RecordingCategory> = emptyList()) : CategoryRepository {
        val all = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<RecordingCategory>> = all
        override suspend fun get(id: String): RecordingCategory? = all.value.firstOrNull { it.id == id }
        override suspend fun upsert(category: RecordingCategory) {
            all.value = all.value.filter { it.id != category.id } + category
        }
        override suspend fun delete(id: String) {
            all.value = all.value.filter { it.id != id }
        }
        override suspend fun ensureStarterCategories(names: Map<String, String>) {
            if (all.value.isNotEmpty()) return
            all.value = CategoryRepository.STARTER_IDS.mapIndexed { index, id ->
                RecordingCategory(id, names.getValue(id), RecordingMode.SIMPLE_LISTENING, index, 0L)
            }
        }
        fun name(id: String) = all.value.first { it.id == id }.name
    }

    private val known = mapOf(
        "voice-note" to setOf("Voice note", "Sprachnotiz"),
        "meeting" to setOf("Meeting", "Besprechung"),
        "idea" to setOf("Idea", "Idee"),
        "todo" to setOf("To-do", "Aufgabe")
    )

    private suspend fun EnsureStarterCategoriesUseCase.inGerman() = invoke("Sprachnotiz", "Besprechung", "Idee", "Aufgabe", known)
    private suspend fun EnsureStarterCategoriesUseCase.inEnglish() = invoke("Voice note", "Meeting", "Idea", "To-do", known)

    @Test
    fun `switched to German, the starter categories take their German names, and back`() = runBlocking {
        val categories = InMemoryCategories()
        val ensure = EnsureStarterCategoriesUseCase(categories)

        ensure.inEnglish()
        assertEquals("Voice note", categories.name("voice-note"))

        ensure.inGerman()
        assertEquals(listOf("Sprachnotiz", "Besprechung", "Idee", "Aufgabe"), CategoryRepository.STARTER_IDS.map { categories.name(it) })

        ensure.inEnglish()
        assertEquals(listOf("Voice note", "Meeting", "Idea", "To-do"), CategoryRepository.STARTER_IDS.map { categories.name(it) })
    }

    @Test
    fun `a starter category the owner renamed keeps his name`() = runBlocking {
        val categories = InMemoryCategories()
        val ensure = EnsureStarterCategoriesUseCase(categories)
        ensure.inEnglish()
        categories.upsert(categories.all.value.first { it.id == "meeting" }.copy(name = "Team"))

        ensure.inGerman()

        assertEquals("Team", categories.name("meeting"))
        assertEquals("Sprachnotiz", categories.name("voice-note"))
    }

    @Test
    fun `a name another category has already is left alone`() {
        assertFalse(EnsureStarterCategoriesUseCase.followsLanguage("Idea", "Idee", setOf("Idea", "Idee"), otherNames = listOf("idee")))
        assertTrue(EnsureStarterCategoriesUseCase.followsLanguage("Idea", "Idee", setOf("Idea", "Idee"), otherNames = listOf("Work")))
        // Already in the language: nothing to write.
        assertFalse(EnsureStarterCategoriesUseCase.followsLanguage("Idee", "Idee", setOf("Idea", "Idee"), otherNames = emptyList()))
    }

    @Test
    fun `without the names of the languages nothing is renamed`() = runBlocking {
        val categories = InMemoryCategories()
        val ensure = EnsureStarterCategoriesUseCase(categories)
        ensure("Voice note", "Meeting", "Idea", "To-do")

        ensure("Sprachnotiz", "Besprechung", "Idee", "Aufgabe")

        assertEquals("Voice note", categories.name("voice-note"))
    }
}
