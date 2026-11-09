package com.satori.domain.usecase.transcription

import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Test doubles for switching what a recording is recorded as ("Record as", "Change type").

/** The four starter categories of a fresh install, in their order. */
val starterCategories = listOf(
    RecordingCategory("voice-note", "Voice note", RecordingMode.SIMPLE_LISTENING, sortOrder = 0, createdAt = 0L),
    RecordingCategory("meeting", "Meeting", RecordingMode.SHORT_MEETING, sortOrder = 1, createdAt = 0L),
    RecordingCategory("idea", "Idea", RecordingMode.SIMPLE_LISTENING, sortOrder = 2, createdAt = 0L),
    RecordingCategory("todo", "To-do", RecordingMode.SIMPLE_LISTENING, sortOrder = 3, createdAt = 0L)
)

/** A category of the owner's own that translates, as the categories screen lets him make one. */
val translatingCategory = RecordingCategory("trip", "Trip", RecordingMode.REAL_TIME_TRANSLATION, sortOrder = 4, createdAt = 0L)

/** A category of the owner's own for long meetings. */
val longMeetingCategory = RecordingCategory("board", "Board", RecordingMode.LONG_MEETING, sortOrder = 5, createdAt = 0L)

/** The categories on the phone: [categories], which a test can change, as the categories screen does. */
class CategoriesHeld(vararg categories: RecordingCategory) : CategoryRepository {
    val all = MutableStateFlow(categories.toList())

    /** Set to make [get] fail, as a database that can't be read. */
    var readFailure: Exception? = null

    override fun observeAll(): Flow<List<RecordingCategory>> = all

    override suspend fun get(id: String): RecordingCategory? {
        readFailure?.let { throw it }
        return all.value.firstOrNull { it.id == id }
    }

    override suspend fun upsert(category: RecordingCategory): Unit = unused()
    override suspend fun delete(id: String): Unit = unused()
    override suspend fun ensureStarterCategories(names: Map<String, String>): Unit = unused()

    private fun unused(): Nothing = error("not used by these tests")
}
