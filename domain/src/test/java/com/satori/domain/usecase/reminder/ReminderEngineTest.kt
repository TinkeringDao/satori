package com.satori.domain.usecase.reminder

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.TodoRepository
import com.satori.domain.usecase.todo.TodoChangeLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A store that reschedules after each write, as a repository or a use case around it might. */
private class ReschedulingTodos(
    private val inner: InMemoryTodos,
    private val reschedule: suspend () -> Unit
) : TodoRepository by inner {
    override suspend fun upsert(todo: Todo) {
        inner.upsert(todo)
        reschedule()
    }

    override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?) {
        inner.setStatus(id, status, completedAt)
        reschedule()
    }

    override suspend fun setRemindAt(id: String, remindAt: Long?) {
        inner.setRemindAt(id, remindAt)
        reschedule()
    }

    override suspend fun markReminderShown(id: String, atMs: Long) {
        inner.markReminderShown(id, atMs)
        reschedule()
    }
}

class ReminderEngineTest {

    private var now = at(8)
    private val alarm = FakeAlarm()
    private val shade = FakeNotifications()

    private fun engine(todos: TodoRepository) = ReminderEngine(todos, alarm, shade) { now }

    /** The app runs at the current time (arming the alarm), then the alarm rings at [time]. */
    private suspend fun ReminderEngine.ringAt(time: Long) {
        sync()
        now = time
        sync()
    }

    @Test
    fun `posts a reminder when its alarm rings and arms the next one`() = runBlocking {
        val engine = engine(InMemoryTodos(todo("bank", remindAt = at(9)), todo("milk", remindAt = at(10))))

        engine.sync()
        assertEquals(emptyList<String>(), shade.posted)
        assertEquals(at(9), alarm.armedAt)

        now = at(9)
        engine.sync()
        assertEquals(listOf("bank"), shade.posted)
        assertEquals(at(10), alarm.armedAt)
    }

    @Test
    fun `posts each reminder once, even after it was swiped away`() = runBlocking {
        val engine = engine(InMemoryTodos(todo("bank", remindAt = at(9))))
        engine.ringAt(at(9))
        shade.swipe("bank")

        now = at(9, 30)
        engine.sync()

        assertEquals(listOf("bank"), shade.posted)
    }

    @Test
    fun `cancels the alarm once no reminder is left`() = runBlocking {
        val engine = engine(InMemoryTodos(todo("bank", remindAt = at(9))))
        engine.sync()
        assertEquals(at(9), alarm.armedAt)

        now = at(9)
        engine.sync()

        assertNull(alarm.armedAt)
    }

    @Test
    fun `posts the reminders missed while the phone was off, oldest first, once`() = runBlocking {
        val engine = engine(
            InMemoryTodos(
                todo("dentist", remindAt = at(11)),
                todo("milk", remindAt = at(9, 30)),
                todo("bank", remindAt = at(9))
            )
        )

        // The first run after a reboot at 10:00
        now = at(10)
        engine.sync()
        engine.sync()

        assertEquals(listOf("bank", "milk"), shade.posted)
        assertEquals(at(11), alarm.armedAt)
    }

    @Test
    fun `posts once a reminder written after a sync had passed its time`() = runBlocking {
        val todos = InMemoryTodos()
        val engine = engine(todos)
        now = at(9)
        engine.sync()

        todos.upsert(todo("late", remindAt = at(8, 30)))
        now = at(9, 1)
        engine.sync()
        assertEquals(listOf("late"), shade.posted)

        now = at(9, 2)
        engine.sync()
        assertEquals(listOf("late"), shade.posted)
    }

    @Test
    fun `posts both of two past-due reminders written one after the other`() = runBlocking {
        val todos = InMemoryTodos()
        val engine = engine(todos)
        engine.sync() // an earlier run, at 8:00

        // Extraction ends at 9:12 and writes two todos whose times have passed, syncing after each.
        now = at(9, 12)
        todos.upsert(todo("A", remindAt = at(9)))
        engine.sync()
        now = at(9, 12) + 50
        todos.upsert(todo("B", remindAt = at(9, 5)))
        engine.sync()

        assertEquals(listOf("A", "B"), shade.posted)
    }

    @Test
    fun `posts a reminder again at its time after the clock was set back to before it`() = runBlocking {
        val engine = engine(InMemoryTodos(todo("bank", remindAt = at(9, 30))))
        now = at(10)
        engine.sync()
        assertEquals(setOf("bank"), shade.showing())

        // The clock goes back an hour; the time-set broadcast runs a sync. The reminder's time is
        // ahead again: it leaves the shade, and the alarm waits for it.
        now = at(9)
        engine.sync()
        assertEquals(emptySet<String>(), shade.showing())
        assertEquals(at(9, 30), alarm.armedAt)

        now = at(9, 30)
        engine.sync()
        assertEquals(setOf("bank"), shade.showing())
        assertEquals(listOf("bank", "bank"), shade.posted)

        now = at(9, 31)
        engine.sync()
        assertEquals(listOf("bank", "bank"), shade.posted)
    }

    @Test
    fun `rings each reminder at its time after the clock ran ahead and was put right`() = runBlocking {
        val engine = engine(
            InMemoryTodos(todo("noon", remindAt = at(12)), todo("three", remindAt = at(15)), todo("six", remindAt = at(18)))
        )
        engine.sync()

        // The clock jumps to 20:00 by mistake, so all three post at once ...
        now = at(20)
        engine.sync()
        assertEquals(setOf("noon", "three", "six"), shade.showing())

        // ... and is put right at 11:01: none is due any more.
        now = at(11, 1)
        engine.sync()
        assertEquals(emptySet<String>(), shade.showing())
        assertEquals(at(12), alarm.armedAt)

        now = at(12)
        engine.sync()
        assertEquals(setOf("noon"), shade.showing())
        assertEquals(at(15), alarm.armedAt)

        now = at(15)
        engine.sync()
        now = at(18)
        engine.sync()
        assertEquals(setOf("noon", "three", "six"), shade.showing())
        assertEquals(listOf("noon", "three", "six", "noon", "three", "six"), shade.posted)
        assertNull(alarm.armedAt)
    }

    @Test
    fun `posts a reminder twelve hours late, and only marks one later still, whose todo stays open`() = runBlocking {
        val todos = InMemoryTodos(todo("bank", remindAt = at(9)), todo("milk", remindAt = at(8, 59)))
        val engine = engine(todos)

        // The first run after the phone was off all day
        now = at(21)
        engine.sync()

        assertEquals(listOf("bank"), shade.posted)
        val milk = todos.byId.getValue("milk")
        assertEquals(TodoStatus.OPEN, milk.status)
        assertEquals(at(21), milk.reminderShownAt)

        now = at(21, 30)
        engine.sync()
        assertEquals(listOf("bank"), shade.posted)
    }

    @Test
    fun `once notifications are on again, posts only the held-back reminders at most twelve hours late`() = runBlocking {
        val engine = engine(InMemoryTodos(todo("bank", remindAt = at(9)), todo("milk", remindAt = at(20))))
        shade.enabled = false
        engine.ringAt(at(9))
        now = at(20)
        engine.sync()
        assertEquals(emptyList<String>(), shade.posted)

        // Allowed again at 8:00 the next morning: bank is 23 hours late, milk 12.
        shade.enabled = true
        now = at(24 + 8)
        engine.sync()

        assertEquals(listOf("milk"), shade.posted)
    }

    @Test
    fun `still posts a new reminder after the clock was set back`() = runBlocking {
        val todos = InMemoryTodos()
        val engine = engine(todos)
        now = at(10)
        engine.sync()

        // The clock goes back an hour; the time-set broadcast runs a sync.
        now = at(9)
        engine.sync()
        todos.upsert(todo("call", remindAt = at(9, 10)))
        engine.sync()
        assertEquals(at(9, 10), alarm.armedAt)

        now = at(9, 10)
        engine.sync()

        assertEquals(listOf("call"), shade.posted)
    }

    @Test
    fun `keeps a reminder while notifications are off and shows it once they are on`() = runBlocking {
        val engine = engine(InMemoryTodos(todo("bank", remindAt = at(9))))
        shade.enabled = false
        engine.ringAt(at(9))
        assertEquals(emptyList<String>(), shade.posted)

        // Notifications are allowed, and the app is opened later, which syncs.
        shade.enabled = true
        now = at(9, 30)
        engine.sync()
        engine.sync()

        assertEquals(listOf("bank"), shade.posted)
    }

    @Test
    fun `Done marks the todo done, removes its reminder and keeps the next alarm`() = runBlocking {
        val todos = InMemoryTodos(todo("bank", remindAt = at(9)), todo("milk", remindAt = at(10)))
        val engine = engine(todos)
        engine.ringAt(at(9))
        assertEquals(setOf("bank"), shade.showing())

        now = at(9, 5)
        engine.complete("bank")

        assertEquals(TodoStatus.DONE, todos.byId.getValue("bank").status)
        assertEquals(at(9, 5), todos.byId.getValue("bank").completedAt)
        assertEquals(emptySet<String>(), shade.showing())
        assertEquals(at(10), alarm.armedAt)
    }

    @Test
    fun `Done leaves a todo alone that was dismissed in the app meanwhile`() = runBlocking {
        val todos = InMemoryTodos(todo("bank", remindAt = at(9)))
        val engine = engine(todos)
        engine.ringAt(at(9))
        assertEquals(setOf("bank"), shade.showing())
        todos.setStatus("bank", TodoStatus.DISMISSED)

        engine.complete("bank")

        assertEquals(TodoStatus.DISMISSED, todos.byId.getValue("bank").status)
        assertEquals(emptySet<String>(), shade.showing())
    }

    @Test
    fun `Done goes to the field log as from the notification, also when it leaves the todo as it was`() = runBlocking {
        val lines = mutableListOf<String>()
        val todos = InMemoryTodos(todo("bank", remindAt = at(9)), todo("milk", remindAt = at(9)))
        val engine = ReminderEngine(todos, alarm, shade, TodoChangeLog { id, from, to, source -> lines += TodoChangeLog.line(id, from, to, source) }) { now }
        engine.ringAt(at(9))
        todos.setStatus("milk", TodoStatus.DONE)

        engine.complete("bank")
        engine.complete("milk")
        engine.complete("gone")

        assertEquals(
            listOf(
                "To-do bank: OPEN -> DONE (notification)",
                "To-do milk: DONE -> DONE (notification)",
                "To-do gone: gone -> gone (notification)"
            ),
            lines
        )
    }

    @Test
    fun `Snooze reminds again ten minutes after the tap`() = runBlocking {
        val todos = InMemoryTodos(todo("bank", remindAt = at(9)), todo("milk", remindAt = at(10)))
        val engine = engine(todos)
        engine.ringAt(at(9))
        assertEquals(setOf("bank"), shade.showing())

        now = at(9, 2)
        engine.snooze("bank")

        assertEquals(at(9, 12), todos.byId.getValue("bank").remindAt)
        assertEquals(emptySet<String>(), shade.showing())
        assertEquals(at(9, 12), alarm.armedAt)

        now = at(9, 12)
        engine.sync()

        assertEquals(listOf("bank", "bank"), shade.posted)
        assertEquals(at(10), alarm.armedAt)
    }

    @Test
    fun `Snooze changes only the reminder time, so an edit made meanwhile stays`() = runBlocking {
        val inner = InMemoryTodos(todo("bank", remindAt = at(9)))
        var editAfterNextRead = false
        // The text is edited in the app right after Snooze reads the todo, before it writes.
        val todos = object : TodoRepository by inner {
            override suspend fun get(id: String): Todo? = inner.get(id).also {
                if (editAfterNextRead) {
                    editAfterNextRead = false
                    inner.byId[id] = inner.byId.getValue(id).copy(text = "Call the bank at 10")
                }
            }
        }
        val engine = engine(todos)
        engine.ringAt(at(9))

        now = at(9, 2)
        editAfterNextRead = true
        engine.snooze("bank")

        assertEquals("Call the bank at 10", inner.byId.getValue("bank").text)
        assertEquals(at(9, 12), inner.byId.getValue("bank").remindAt)
    }

    @Test
    fun `Snooze on a deleted todo only removes its reminder`() = runBlocking {
        val todos = InMemoryTodos(todo("bank", remindAt = at(9)))
        val engine = engine(todos)
        engine.ringAt(at(9))
        assertEquals(setOf("bank"), shade.showing())
        todos.delete("bank")

        engine.snooze("bank")

        assertNull(todos.byId["bank"])
        assertEquals(emptySet<String>(), shade.showing())
    }

    @Test
    fun `syncs, Done and Snooze finish when the store reschedules on each write`() = runBlocking {
        val inner = InMemoryTodos(todo("bank", remindAt = at(9)), todo("milk", remindAt = at(9)))
        lateinit var engine: ReminderEngine
        engine = ReminderEngine(ReschedulingTodos(inner) { engine.sync() }, alarm, shade) { now }

        withTimeout(5_000) {
            // Posting marks each reminder shown: a write made while the sync holds its lock.
            engine.ringAt(at(9))
            engine.complete("bank")
            engine.snooze("milk")
        }

        assertEquals(listOf("bank", "milk"), shade.posted)
        assertEquals(TodoStatus.DONE, inner.byId.getValue("bank").status)
        assertEquals(at(9, 10), inner.byId.getValue("milk").remindAt)
    }

    @Test
    fun `removes the reminders whose todo was done, deleted or moved later in the app`() = runBlocking {
        val todos = InMemoryTodos(
            todo("done", remindAt = at(9)),
            todo("deleted", remindAt = at(9)),
            todo("moved", remindAt = at(9)),
            todo("unchanged", remindAt = at(9))
        )
        val engine = engine(todos)
        engine.ringAt(at(9))
        assertEquals(setOf("done", "deleted", "moved", "unchanged"), shade.showing())

        todos.setStatus("done", TodoStatus.DONE, completedAt = at(9, 1))
        todos.delete("deleted")
        todos.upsert(todos.byId.getValue("moved").copy(remindAt = at(11)))
        now = at(9, 5)
        engine.sync()

        assertEquals(setOf("unchanged"), shade.showing())
        assertEquals(at(11), alarm.armedAt)
    }
}
