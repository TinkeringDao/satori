package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The to-dos and ideas the app found, told apart from those the owner changed by their last change
 * (updated_at more than 5 s after created_at), for the finishing made again over a Malayalam
 * recording's readings (design R9): his stay, the app's give way.
 */
class UntouchedFindsTest {

    private fun found(id: String, changedAfterMs: Long, sessionId: String = "s1", status: TodoStatus = TodoStatus.OPEN) = Todo(
        id = id, sessionId = sessionId, text = "Bring the papers ($id)", status = status, capture = TodoCapture.COMMITMENT,
        sourceSegmentIds = listOf("seg1000"), createdAt = 10_000L, updatedAt = 10_000L + changedAfterMs
    )

    private val lines = mutableListOf<String>()
    private val scheduler = CountingScheduler()

    private fun finds(todos: RecordedTodos) =
        UntouchedFinds(todos, TodoActions(todos, scheduler, TodoChangeLog { id, from, to, source -> lines += TodoChangeLog.line(id, from, to, source) }))

    @Test
    fun `the app's finds are those nobody changed, or only within 5 s of finding them, and the rest are the owner's`() = runBlocking {
        val todos = RecordedTodos(
            found("never", 0L),
            found("at once", UNTOUCHED_MS),
            found("ticked", UNTOUCHED_MS + 1L, status = TodoStatus.DONE),
            found("idea", 60_000L, status = TodoStatus.IDEA),
            found("other recording", 0L, sessionId = "s2")
        )

        assertEquals(UntouchedFinds.Finds(untouched = setOf("never", "at once"), touched = 2), finds(todos).of("s1"))
        assertEquals(UntouchedFinds.Finds(untouched = emptySet(), touched = 0), finds(todos).of("none"))
    }

    @Test
    fun `taking away removes only those still untouched, each to the field log as read again with its reminder`() = runBlocking {
        val todos = RecordedTodos(found("app", 0L), found("his", 60_000L), found("app too", 0L))
        val finds = finds(todos)
        val before = finds.of("s1").untouched
        // The owner ticks one of them while the search runs.
        todos.upsert(found("app too", 30_000L, status = TodoStatus.DONE))

        assertEquals(1, finds.takeAway("s1", before, TodoSource.READ_AGAIN))

        assertEquals(setOf("his", "app too"), todos.stored.map { it.id }.toSet())
        assertEquals(listOf(TodoChangeLog.line("app", TodoStatus.OPEN, null, TodoSource.READ_AGAIN)), lines)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `a read that fails takes nothing away`() = runBlocking {
        val todos = object : com.satori.domain.repository.TodoRepository by RecordedTodos() {
            override fun observeForSession(sessionId: String) = throw IllegalStateException("database closed")
        }
        val finds = UntouchedFinds(todos, TodoActions(todos, scheduler))

        assertEquals(0, finds.takeAway("s1", setOf("app"), TodoSource.READ_AGAIN))
        assertEquals(0, scheduler.calls)
    }
}
