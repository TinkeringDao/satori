package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.TodoRepository
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.usecase.reminder.InMemoryTodos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class TodoActionsTest {

    private val now = 1_000_000L

    private val utc = ZoneOffset.UTC

    private class CountingScheduler : ReminderScheduler {
        var calls = 0
        override suspend fun reschedule() {
            calls++
        }
    }

    private fun todo(id: String, status: TodoStatus, remindAt: Long? = null, shownAt: Long? = null) = Todo(
        id = id,
        sessionId = null,
        text = "Text of $id",
        status = status,
        remindAt = remindAt,
        reminderShownAt = shownAt,
        createdAt = 0L,
        updatedAt = 0L
    )

    private val scheduler = CountingScheduler()

    private fun actions(store: InMemoryTodos) = TodoActions(store, scheduler) { now }

    @Test
    fun `completing marks the to-do done at the current time and reschedules`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.OPEN))

        actions(store).complete("a", TodoSource.HOME)

        assertEquals(TodoStatus.DONE, store.byId.getValue("a").status)
        assertEquals(now, store.byId.getValue("a").completedAt)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `taking a completion back opens the to-do again and reschedules`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.DONE))

        actions(store).reopen("a")

        assertEquals(TodoStatus.OPEN, store.byId.getValue("a").status)
        assertNull(store.byId.getValue("a").completedAt)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `keeping suggestions opens them with one reschedule`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.SUGGESTED), todo("b", TodoStatus.SUGGESTED))

        actions(store).keep(store.byId.values.toList(), TodoSource.HOME)

        assertEquals(listOf(TodoStatus.OPEN, TodoStatus.OPEN), store.byId.values.map { it.status })
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `keeping a suggestion whose reminder time has passed does not raise it at once`() = runBlocking {
        val late = todo("late", TodoStatus.SUGGESTED, remindAt = now - 60_000L)
        val ahead = todo("ahead", TodoStatus.SUGGESTED, remindAt = now + 60_000L)
        val store = InMemoryTodos(late, ahead)

        actions(store).keep(listOf(late, ahead), TodoSource.HOME)

        assertEquals(now, store.byId.getValue("late").reminderShownAt)
        assertNull(store.byId.getValue("ahead").reminderShownAt)
        assertTrue(store.dueReminders(now).isEmpty())
    }

    @Test
    fun `dismissing a suggestion drops it and reschedules`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.SUGGESTED))

        actions(store).dismiss("a", TodoSource.HOME)

        assertEquals(TodoStatus.DISMISSED, store.byId.getValue("a").status)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `editing stores the new text and due time and reschedules`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.OPEN))

        actions(store).edit("a", "New text", LocalDate.of(1970, 1, 2), LocalTime.of(10, 30), utc)

        val saved = store.byId.getValue("a")
        assertEquals("New text", saved.text)
        assertEquals(124_200_000L, saved.dueAt)
        assertEquals(124_200_000L, saved.remindAt)
        assertEquals(now, saved.updatedAt)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `editing only the text keeps a snooze made after the sheet opened`() = runBlocking {
        val snoozedTo = now + 600_000L
        val store = InMemoryTodos(todo("a", TodoStatus.OPEN, remindAt = snoozedTo))

        actions(store).edit("a", "New text", date = null, time = null, zone = utc)

        assertEquals("New text", store.byId.getValue("a").text)
        assertEquals(snoozedTo, store.byId.getValue("a").remindAt)
    }

    @Test
    fun `an edit leaves a to-do that was completed meanwhile as it is`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.DONE))

        actions(store).edit("a", "New text", date = null, time = null, zone = utc)

        assertEquals(todo("a", TodoStatus.DONE), store.byId.getValue("a"))
        assertEquals(0, scheduler.calls)
    }

    @Test
    fun `an edit does not bring back a to-do that was deleted meanwhile`() = runBlocking {
        val store = InMemoryTodos()

        actions(store).edit("a", "New text", date = null, time = null, zone = utc)

        assertTrue(store.byId.isEmpty())
        assertEquals(0, scheduler.calls)
    }

    @Test
    fun `an edit brings back, as the owner's, a to-do the app took away as it found the recording's anew meanwhile`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.OPEN), todo("b", TodoStatus.OPEN))
        val takenAway = TakenAwayTodos()
        // The finishing made again takes "a" away while its sheet is open; the owner deletes "b" himself.
        TodoActions(store, scheduler, takenAway = takenAway) { now }.delete("a", TodoSource.READ_AGAIN)
        TodoActions(store, scheduler, takenAway = takenAway) { now }.delete("b", TodoSource.HOME)
        assertTrue(store.byId.isEmpty())

        // The sheet's own actions, another instance sharing the same record.
        val sheet = TodoActions(store, scheduler, takenAway = takenAway) { now }
        sheet.edit("a", "New text", date = null, time = null, zone = utc)
        sheet.edit("b", "Other text", date = null, time = null, zone = utc)

        assertEquals(setOf("a"), store.byId.keys)
        assertEquals("New text", store.byId.getValue("a").text)
        // Touched now: a finishing made again leaves it be.
        assertEquals(now, store.byId.getValue("a").updatedAt)
        // Once only: an edit of it after it came back edits the stored one.
        assertNull(takenAway.takeBack("a"))
    }

    @Test
    fun `deleting removes the to-do and reschedules`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.OPEN))

        actions(store).delete("a", TodoSource.HOME)

        assertTrue(store.byId.isEmpty())
        assertEquals(1, scheduler.calls)
    }

    // ========== Ideas ==========

    @Test
    fun `an idea made a to-do is open and reschedules`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.IDEA))

        actions(store).makeTodo("a", TodoSource.IDEAS)

        assertEquals(TodoStatus.OPEN, store.byId.getValue("a").status)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `only an idea is made a to-do`() = runBlocking {
        val store = InMemoryTodos(todo("done", TodoStatus.DONE), todo("dismissed", TodoStatus.DISMISSED))

        actions(store).makeTodo("done", TodoSource.IDEAS)
        actions(store).makeTodo("dismissed", TodoSource.IDEAS)
        actions(store).makeTodo("deleted", TodoSource.IDEAS)

        assertEquals(TodoStatus.DONE, store.byId.getValue("done").status)
        assertEquals(TodoStatus.DISMISSED, store.byId.getValue("dismissed").status)
        assertEquals(0, scheduler.calls)
    }

    @Test
    fun `an open or suggested to-do moved to the ideas loses its reminder`() = runBlocking {
        val store = InMemoryTodos(
            todo("open", TodoStatus.OPEN, remindAt = now + 60_000L),
            todo("suggested", TodoStatus.SUGGESTED, remindAt = now - 60_000L, shownAt = now - 60_000L)
        )

        actions(store).moveToIdeas("open", TodoSource.HOME)
        actions(store).moveToIdeas("suggested", TodoSource.HOME)

        for (id in listOf("open", "suggested")) {
            assertEquals(TodoStatus.IDEA, store.byId.getValue(id).status)
            assertNull(store.byId.getValue(id).remindAt)
        }
        assertNull(store.nextReminderAfter(0L))
        assertEquals(2, scheduler.calls)
    }

    @Test
    fun `a move to the ideas cut short after its first write leaves no reminder behind`() = runBlocking {
        val store = InMemoryTodos(todo("open", TodoStatus.OPEN, remindAt = now + 60_000L))
        // The status write fails, as a process death between the two writes would leave it.
        val failing = object : TodoRepository by store {
            override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?) {
                throw IOException("disk full")
            }
        }

        assertThrows(IOException::class.java) { runBlocking { TodoActions(failing, scheduler) { now }.moveToIdeas("open", TodoSource.HOME) } }

        assertEquals(TodoStatus.OPEN, store.byId.getValue("open").status)
        assertNull(store.byId.getValue("open").remindAt)
    }

    @Test
    fun `a done, dismissed or deleted to-do is not moved to the ideas`() = runBlocking {
        val store = InMemoryTodos(todo("done", TodoStatus.DONE, remindAt = now), todo("dismissed", TodoStatus.DISMISSED))

        actions(store).moveToIdeas("done", TodoSource.HOME)
        actions(store).moveToIdeas("dismissed", TodoSource.HOME)
        actions(store).moveToIdeas("deleted", TodoSource.HOME)

        assertEquals(todo("done", TodoStatus.DONE, remindAt = now), store.byId.getValue("done"))
        assertEquals(TodoStatus.DISMISSED, store.byId.getValue("dismissed").status)
        assertEquals(0, scheduler.calls)
    }

    @Test
    fun `a deleted idea is dismissed, and Undo makes it an idea again`() = runBlocking {
        val store = InMemoryTodos(todo("a", TodoStatus.IDEA))

        actions(store).dismissIdea("a", TodoSource.IDEAS)
        assertEquals(TodoStatus.DISMISSED, store.byId.getValue("a").status)

        actions(store).restoreIdea("a")
        assertEquals(TodoStatus.IDEA, store.byId.getValue("a").status)
        assertEquals(2, scheduler.calls)
    }

    @Test
    fun `only an idea is deleted as one, and only a dismissed one restored`() = runBlocking {
        val store = InMemoryTodos(todo("open", TodoStatus.OPEN), todo("idea", TodoStatus.IDEA))

        actions(store).dismissIdea("open", TodoSource.IDEAS)
        actions(store).restoreIdea("open")
        actions(store).restoreIdea("idea")

        assertEquals(TodoStatus.OPEN, store.byId.getValue("open").status)
        assertEquals(TodoStatus.IDEA, store.byId.getValue("idea").status)
        assertEquals(0, scheduler.calls)
    }

    // ========== The field log ==========

    /** The lines [TodoChangeLog.line] writes for what [block] does with [store]'s to-dos. */
    private fun logged(store: InMemoryTodos, block: suspend (TodoActions) -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val actions = TodoActions(store, scheduler, TodoChangeLog { id, from, to, source -> lines += TodoChangeLog.line(id, from, to, source) }) { now }
        runBlocking { block(actions) }
        return lines
    }

    @Test
    fun `each change of a status is logged with the to-do's id, the statuses and where it came from, never its text`() {
        val store = InMemoryTodos(
            todo("a", TodoStatus.OPEN),
            todo("b", TodoStatus.OPEN),
            todo("s", TodoStatus.SUGGESTED),
            todo("t", TodoStatus.SUGGESTED),
            todo("i", TodoStatus.IDEA)
        )

        val lines = logged(store) { actions ->
            actions.complete("a", TodoSource.HOME)
            actions.reopen("a")
            actions.complete("b", TodoSource.LIST)
            actions.keep(listOf(store.byId.getValue("s")), TodoSource.PAGE)
            actions.dismiss("t", TodoSource.HOME)
            actions.moveToIdeas("s", TodoSource.PAGE)
            actions.makeTodo("i", TodoSource.IDEAS)
            actions.delete("i", TodoSource.HOME)
        }

        assertEquals(
            listOf(
                "To-do a: OPEN -> DONE (home)",
                "To-do a: DONE -> OPEN (undo)",
                "To-do b: OPEN -> DONE (list)",
                "To-do s: SUGGESTED -> OPEN (page)",
                "To-do t: SUGGESTED -> DISMISSED (home)",
                "To-do s: OPEN -> IDEA (page)",
                "To-do i: IDEA -> OPEN (ideas)",
                "To-do i: OPEN -> gone (home)"
            ),
            lines
        )
        assertTrue(lines.none { it.contains("Text of") })
    }

    @Test
    fun `a second tick, and one on a to-do deleted meanwhile, are logged as they found the to-do`() {
        val store = InMemoryTodos(todo("a", TodoStatus.DONE))

        val lines = logged(store) { actions ->
            actions.complete("a", TodoSource.HOME)
            actions.complete("gone", TodoSource.PAGE)
        }

        assertEquals(listOf("To-do a: DONE -> DONE (home)", "To-do gone: gone -> gone (page)"), lines)
        assertTrue(store.byId.keys == setOf("a"))
    }

    @Test
    fun `an idea deleted and taken back is logged both ways, the Undo as undo`() {
        val store = InMemoryTodos(todo("i", TodoStatus.IDEA))

        val lines = logged(store) { actions ->
            actions.dismissIdea("i", TodoSource.IDEAS)
            actions.restoreIdea("i")
        }

        assertEquals(listOf("To-do i: IDEA -> DISMISSED (ideas)", "To-do i: DISMISSED -> IDEA (undo)"), lines)
    }

    @Test
    fun `what changes nothing is not logged`() {
        val store = InMemoryTodos(todo("open", TodoStatus.OPEN), todo("done", TodoStatus.DONE))

        val lines = logged(store) { actions ->
            actions.makeTodo("open", TodoSource.IDEAS)
            actions.moveToIdeas("done", TodoSource.HOME)
            actions.dismissIdea("open", TodoSource.IDEAS)
            actions.restoreIdea("open")
            actions.edit("open", "New text", date = null, time = null, zone = utc)
        }

        assertTrue(lines.toString(), lines.isEmpty())
    }
}
