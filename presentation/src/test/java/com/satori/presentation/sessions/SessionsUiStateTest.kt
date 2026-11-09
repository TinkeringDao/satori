package com.satori.presentation.sessions

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsUiStateTest {

    private fun todo(id: String, status: TodoStatus, createdAt: Long = 0L) = Todo(
        id = id,
        sessionId = "s1",
        text = "Option to sort the notes by place",
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private fun ideas(count: Int) = (1..count).map { todo("i$it", TodoStatus.IDEA) }

    @Test
    fun `ideas alone make the To do section, so home is not empty`() {
        val state = SessionsUiState(ideas = ideas(1), isLoading = false)

        assertTrue(state.hasTodoSection)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `to-dos or suggestions make the section as before, and nothing makes none`() {
        assertTrue(SessionsUiState(openTodos = listOf(todo("t1", TodoStatus.OPEN))).hasTodoSection)
        assertTrue(SessionsUiState(suggestions = listOf(todo("t2", TodoStatus.SUGGESTED))).hasTodoSection)
        assertFalse(SessionsUiState().hasTodoSection)
        assertTrue(SessionsUiState(isLoading = false).isEmpty)
    }

    @Test
    fun `the ideas row says how many there are, and how many are new only when some are`() {
        assertNull(SessionsUiState().ideasRow)
        assertEquals(IdeasRow(count = 1, newCount = null), SessionsUiState(ideas = ideas(1)).ideasRow)
        assertEquals(IdeasRow(count = 4, newCount = null), SessionsUiState(ideas = ideas(4), newIdeas = 0).ideasRow)
        assertEquals(IdeasRow(count = 4, newCount = 2), SessionsUiState(ideas = ideas(4), newIdeas = 2).ideasRow)
    }

    @Test
    fun `an idea is new when it was found after home last showed the row`() {
        val ideas = listOf(
            todo("found-after", TodoStatus.IDEA, createdAt = 2_000),
            todo("found-as-it-showed", TodoStatus.IDEA, createdAt = 1_500),
            todo("found-before", TodoStatus.IDEA, createdAt = 1_000)
        )

        assertEquals(1, SessionsUiState.newIdeas(ideas, seenAt = 1_500))
        assertEquals(0, SessionsUiState.newIdeas(ideas, seenAt = 2_000))
        assertEquals(3, SessionsUiState.newIdeas(ideas, seenAt = 999))
        assertEquals(0, SessionsUiState.newIdeas(emptyList(), seenAt = 0))
    }
}
