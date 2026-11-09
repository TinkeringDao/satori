package com.satori.data.database.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.data.database.entity.TodoEntity
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoCertainty
import com.satori.domain.model.TodoKind
import com.satori.domain.model.TodoStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reminder side of [TodoDao] on an in-memory database: how [TodoDao.upsert] keeps or clears
 * the shown mark, [TodoDao.markReminderShown], [TodoDao.setRemindAt], and which reminders
 * [TodoDao.dueReminders] and [TodoDao.nextReminderAfter] count as shown.
 */
@RunWith(AndroidJUnit4::class)
class TodoDaoReminderTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TodoDao

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        dao = db.todoDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun upsertOfACopyReadBeforeTheReminderShowedKeepsTheMark() = runBlocking {
        dao.upsert(todo("bank", remindAt = at(9)))
        // The todo list read it at 8:59; the reminder shows at 9:00; the text is edited at 9:01.
        val copy = dao.get("bank")!!
        assertEquals(1, dao.markReminderShown("bank", OPEN, at(9)))

        dao.upsert(copy.copy(text = "Call the bank about the loan", updatedAt = at(9, 1)))

        val stored = dao.get("bank")!!
        assertEquals("Call the bank about the loan", stored.text)
        assertEquals(at(9), stored.reminderShownAt)
        assertEquals(emptyList<String>(), dao.dueReminders(OPEN, at(9, 1)).ids())
    }

    @Test
    fun upsertWithAChangedRemindAtClearsTheMark() = runBlocking {
        dao.upsert(todo("bank", remindAt = at(9)))
        dao.markReminderShown("bank", OPEN, at(9))
        val shown = dao.get("bank")!!

        // Carries the mark of the old time
        dao.upsert(shown.copy(remindAt = at(11)))

        assertNull(dao.get("bank")!!.reminderShownAt)
        assertEquals(listOf("bank"), dao.dueReminders(OPEN, at(11)).ids())
    }

    @Test
    fun upsertOfANewRowKeepsTheMarkItCarries() = runBlocking {
        dao.upsert(todo("bank", remindAt = at(9), shownAt = at(9, 5)))

        assertEquals(at(9, 5), dao.get("bank")!!.reminderShownAt)
    }

    @Test
    fun upsertKeepsATodoWhoseRecordingIsGone() = runBlocking {
        dao.upsert(todo("bank", remindAt = at(9), sessionId = "deleted-recording"))

        assertNull(dao.get("bank")!!.sessionId)
    }

    @Test
    fun markReminderShownMarksOnlyAnOpenTodoWhoseReminderIsDue() = runBlocking {
        dao.upsert(todo("due", remindAt = at(9)))
        dao.upsert(todo("snoozed", remindAt = at(9, 40)))
        dao.upsert(todo("done", remindAt = at(9), status = TodoStatus.DONE))
        dao.upsert(todo("no reminder", remindAt = null))

        assertEquals(1, dao.markReminderShown("due", OPEN, at(9, 30)))
        assertEquals(0, dao.markReminderShown("snoozed", OPEN, at(9, 30)))
        assertEquals(0, dao.markReminderShown("done", OPEN, at(9, 30)))
        assertEquals(0, dao.markReminderShown("no reminder", OPEN, at(9, 30)))

        assertEquals(at(9, 30), dao.get("due")!!.reminderShownAt)
        assertNull(dao.get("snoozed")!!.reminderShownAt)
        assertNull(dao.get("done")!!.reminderShownAt)
        assertNull(dao.get("no reminder")!!.reminderShownAt)
    }

    @Test
    fun setRemindAtMovesTheReminderClearsItsMarkAndKeepsTheRest() = runBlocking {
        dao.upsert(todo("bank", remindAt = at(9), text = "Call the bank"))
        dao.markReminderShown("bank", OPEN, at(9))

        dao.setRemindAt("bank", at(9, 10), updatedAt = at(9, 1))

        val stored = dao.get("bank")!!
        assertEquals(at(9, 10), stored.remindAt)
        assertNull(stored.reminderShownAt)
        assertEquals(at(9, 1), stored.updatedAt)
        assertEquals("Call the bank", stored.text)
        assertEquals(TodoStatus.OPEN.name, stored.status)
    }

    @Test
    fun setRemindAtNullRemovesTheReminder() = runBlocking {
        dao.upsert(todo("bank", remindAt = at(9)))

        dao.setRemindAt("bank", null, updatedAt = at(8, 30))

        assertNull(dao.get("bank")!!.remindAt)
        assertEquals(emptyList<String>(), dao.dueReminders(OPEN, at(10)).ids())
        assertNull(dao.nextReminderAfter(OPEN, at(8, 30)))
    }

    @Test
    fun dueRemindersListsTheOpenRemindersNotShownForTheirTimeEarliestFirst() = runBlocking {
        dao.upsert(todo("half past", remindAt = at(9, 30)))
        dao.upsert(todo("shown", remindAt = at(9), shownAt = at(9, 1)))
        dao.upsert(todo("quarter past", remindAt = at(9, 15)))
        // Shown at 9:01, then moved to 9:20: the new time hasn't been shown.
        dao.upsert(todo("moved after it was shown", remindAt = at(9, 20), shownAt = at(9, 1)))
        dao.upsert(todo("after now", remindAt = at(9, 31)))
        dao.upsert(todo("done", remindAt = at(9), status = TodoStatus.DONE))
        dao.upsert(todo("no reminder", remindAt = null))

        assertEquals(
            listOf("quarter past", "moved after it was shown", "half past"),
            dao.dueReminders(OPEN, at(9, 30)).ids()
        )
    }

    @Test
    fun dueRemindersCountsAMarkLaterThanNowAsNotShown() = runBlocking {
        dao.upsert(todo("bank", remindAt = at(9, 30)))
        dao.markReminderShown("bank", OPEN, at(10))

        // The clock went back to 9:00 and comes round to 9:30 again.
        assertEquals(emptyList<String>(), dao.dueReminders(OPEN, at(9)).ids())
        assertEquals(listOf("bank"), dao.dueReminders(OPEN, at(9, 30)).ids())
        assertEquals(emptyList<String>(), dao.dueReminders(OPEN, at(10)).ids())
    }

    @Test
    fun nextReminderAfterGivesTheEarliestOpenReminderAhead() = runBlocking {
        dao.upsert(todo("eleven", remindAt = at(11)))
        dao.upsert(todo("ten", remindAt = at(10)))
        dao.upsert(todo("done", remindAt = at(9, 30), status = TodoStatus.DONE))
        dao.upsert(todo("now", remindAt = at(9)))
        dao.upsert(todo("no reminder", remindAt = null))

        assertEquals("ten", dao.nextReminderAfter(OPEN, at(9))?.id)
        assertEquals("eleven", dao.nextReminderAfter(OPEN, at(10))?.id)
        assertNull(dao.nextReminderAfter(OPEN, at(11)))
    }

    @Test
    fun nextReminderAfterGivesAReminderShownBeforeTheClockWasSetBack() = runBlocking {
        dao.upsert(todo("bank", remindAt = at(9, 30)))
        dao.upsert(todo("milk", remindAt = at(10, 30)))
        dao.markReminderShown("bank", OPEN, at(10))

        // The clock went back to 9:00: the alarm has to ring for bank at 9:30 again.
        assertEquals("bank", dao.nextReminderAfter(OPEN, at(9))?.id)
    }

    private fun List<TodoEntity>.ids(): List<String> = map { it.id }

    private companion object {
        val OPEN = TodoStatus.OPEN.name

        /** Epoch ms of [hour]:[minute] on the epoch's first day; reminders only compare times. */
        fun at(hour: Int, minute: Int = 0): Long = (hour * 60L + minute) * 60_000L

        fun todo(
            id: String,
            remindAt: Long?,
            status: TodoStatus = TodoStatus.OPEN,
            shownAt: Long? = null,
            sessionId: String? = null,
            text: String = "Text of $id"
        ) = TodoEntity(
            id = id,
            sessionId = sessionId,
            text = text,
            kind = TodoKind.TASK.name,
            status = status.name,
            certainty = TodoCertainty.CONFIRMED.name,
            capture = TodoCapture.EXPLICIT.name,
            dueAt = null,
            dueHasTime = false,
            timePhrase = null,
            remindAt = remindAt,
            reminderShownAt = shownAt,
            sourceSegmentIds = "[]",
            createdAt = 0L,
            updatedAt = 0L,
            completedAt = null
        )
    }
}
