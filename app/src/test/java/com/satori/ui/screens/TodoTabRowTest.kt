package com.satori.ui.screens

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.presentation.sessiondetails.RecordingTodos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoTabRowTest {

    private fun todo(id: String, status: TodoStatus) =
        Todo(id = id, sessionId = "s", text = "Water the plants", status = status, createdAt = 0, updatedAt = 0)

    private val open1 = todo("o1", TodoStatus.OPEN)
    private val open2 = todo("o2", TodoStatus.OPEN)
    private val idea = todo("i1", TodoStatus.IDEA)

    @Test
    fun `a recording without to-dos or ideas has no rows above its notes`() {
        assertTrue(TodoTabRow.of(RecordingTodos()).isEmpty())
    }

    @Test
    fun `the to-dos come first, then the ideas, each under its heading, then the notes' heading`() {
        val rows = TodoTabRow.of(
            RecordingTodos(open = listOf(open1, open2), suggested = listOf(todo("s1", TodoStatus.SUGGESTED)), ideas = listOf(idea))
        )

        assertEquals(
            listOf(
                TodoTabRow.TodoHeading(open = 2),
                TodoTabRow.OpenTodo(open1),
                TodoTabRow.OpenTodo(open2),
                TodoTabRow.Suggestions(count = 1),
                TodoTabRow.IdeasHeading(count = 1),
                TodoTabRow.Idea(idea),
                TodoTabRow.NotesHeading
            ),
            rows
        )
    }

    @Test
    fun `suggestions alone still get the to-do heading, ideas alone only theirs`() {
        val suggestions = TodoTabRow.of(RecordingTodos(suggested = listOf(todo("s1", TodoStatus.SUGGESTED))))
        val ideas = TodoTabRow.of(RecordingTodos(ideas = listOf(idea)))

        assertEquals(listOf(TodoTabRow.TodoHeading(open = 0), TodoTabRow.Suggestions(count = 1), TodoTabRow.NotesHeading), suggestions)
        assertEquals(listOf(TodoTabRow.IdeasHeading(count = 1), TodoTabRow.Idea(idea), TodoTabRow.NotesHeading), ideas)
    }

    @Test
    fun `every row has a key of its own`() {
        val rows = TodoTabRow.of(
            RecordingTodos(open = listOf(open1, open2), suggested = listOf(todo("s1", TodoStatus.SUGGESTED)), ideas = listOf(idea))
        )

        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
