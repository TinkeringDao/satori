package com.satori.domain.usecase.reminder

import com.satori.domain.model.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRulesTest {

    @Test
    fun `posts the open reminders that are due and not shown yet, oldest first`() {
        val candidates = listOf(
            todo("half past", remindAt = at(9, 30)),
            todo("shown", remindAt = at(9), shownAt = at(9, 1)),
            todo("quarter past", remindAt = at(9, 15)),
            // Shown at 9:01, then moved to 9:20: the new time hasn't been shown.
            todo("moved after it was shown", remindAt = at(9, 20), shownAt = at(9, 1)),
            todo("after now", remindAt = at(9, 31))
        )

        val posted = ReminderRules.toPost(candidates, nowMs = at(9, 30))

        // Now itself is included.
        assertEquals(listOf("quarter past", "moved after it was shown", "half past"), posted.map { it.id })
    }

    @Test
    fun `posts only open todos that have a reminder`() {
        val candidates = listOf(
            todo("open", remindAt = at(9)),
            todo("done", remindAt = at(9), status = TodoStatus.DONE),
            todo("dismissed", remindAt = at(9), status = TodoStatus.DISMISSED),
            todo("suggested", remindAt = at(9), status = TodoStatus.SUGGESTED),
            todo("no reminder", remindAt = null)
        )

        val posted = ReminderRules.toPost(candidates, nowMs = at(10))

        assertEquals(listOf("open"), posted.map { it.id })
    }

    @Test
    fun `a reminder counts as shown only for the time it was shown at or after`() {
        val now = at(10)
        assertFalse(ReminderRules.isShown(todo("never shown", remindAt = at(9)), now))
        assertTrue(ReminderRules.isShown(todo("shown on time", remindAt = at(9), shownAt = at(9)), now))
        assertTrue(ReminderRules.isShown(todo("shown late", remindAt = at(9), shownAt = at(9, 40)), now))
        assertTrue(ReminderRules.isShown(todo("shown just now", remindAt = at(9), shownAt = at(10)), now))
        assertFalse(ReminderRules.isShown(todo("moved after it was shown", remindAt = at(10), shownAt = at(9)), now))
        assertFalse(ReminderRules.isShown(todo("reminder removed", remindAt = null, shownAt = at(9)), now))
    }

    @Test
    fun `a mark later than now doesn't count, as the clock was set back since`() {
        // Shown at 10:00 for 9:30, then the clock went back to 9:00 ...
        val bank = todo("bank", remindAt = at(9, 30), shownAt = at(10))
        assertFalse(ReminderRules.isShown(bank, nowMs = at(9)))

        // ... and has come round to 9:30 again: the reminder posts once more.
        assertEquals(listOf("bank"), ReminderRules.toPost(listOf(bank), nowMs = at(9, 30)).map { it.id })
        assertEquals(emptyList<String>(), ReminderRules.toPost(listOf(bank), nowMs = at(10)).map { it.id })
    }

    @Test
    fun `posts a reminder up to twelve hours late and lets a later one go`() {
        val candidates = listOf(
            todo("twelve hours late", remindAt = at(9)),
            todo("a minute more", remindAt = at(8, 59)),
            todo("a day late", remindAt = at(21 - 24)),
            todo("shown", remindAt = at(8), shownAt = at(8)),
            todo("done", remindAt = at(8), status = TodoStatus.DONE),
            todo("ahead", remindAt = at(22))
        )
        val now = at(21)

        assertEquals(listOf("twelve hours late"), ReminderRules.toPost(candidates, now).map { it.id })
        assertEquals(listOf("a day late", "a minute more"), ReminderRules.tooLate(candidates, now).map { it.id })
    }

    @Test
    fun `snooze reminds again ten minutes after the tap`() {
        assertEquals(at(9, 12), ReminderRules.snoozeUntil(todo("bank", remindAt = at(9)), nowMs = at(9, 2)))
    }

    @Test
    fun `snooze leaves a todo alone that is no longer open`() {
        assertNull(ReminderRules.snoozeUntil(todo("done", remindAt = at(9), status = TodoStatus.DONE), nowMs = at(9, 2)))
        assertNull(ReminderRules.snoozeUntil(todo("dismissed", remindAt = at(9), status = TodoStatus.DISMISSED), nowMs = at(9, 2)))
        assertNull(ReminderRules.snoozeUntil(todo("suggested", remindAt = at(9), status = TodoStatus.SUGGESTED), nowMs = at(9, 2)))
    }

    @Test
    fun `the alarm rings for the next open reminder that is still ahead`() {
        assertEquals(at(10), ReminderRules.alarmAt(todo("milk", remindAt = at(10)), nowMs = at(9)))
        assertNull(ReminderRules.alarmAt(next = null, nowMs = at(9)))
        // A reminder that is not ahead would ring at once, again and again.
        assertNull(ReminderRules.alarmAt(todo("now", remindAt = at(9)), nowMs = at(9)))
        assertNull(ReminderRules.alarmAt(todo("done", remindAt = at(10), status = TodoStatus.DONE), nowMs = at(9)))
        assertNull(ReminderRules.alarmAt(todo("no reminder", remindAt = null), nowMs = at(9)))
    }

    @Test
    fun `a showing reminder stays while its todo is open and its time has come`() {
        assertTrue(ReminderRules.stillDue(todo("due", remindAt = at(9)), nowMs = at(9)))
        assertTrue(ReminderRules.stillDue(todo("due and shown", remindAt = at(9), shownAt = at(9)), nowMs = at(9, 5)))
        assertFalse(ReminderRules.stillDue(todo("moved later", remindAt = at(10)), nowMs = at(9)))
        assertFalse(ReminderRules.stillDue(todo("done", remindAt = at(9), status = TodoStatus.DONE), nowMs = at(9, 5)))
        assertFalse(ReminderRules.stillDue(todo("reminder removed", remindAt = null), nowMs = at(9)))
    }

    @Test
    fun `Done completes only an open todo`() {
        assertTrue(ReminderRules.completes(todo("open", remindAt = at(9))))
        assertFalse(ReminderRules.completes(todo("dismissed", remindAt = at(9), status = TodoStatus.DISMISSED)))
        assertFalse(ReminderRules.completes(todo("suggested", remindAt = at(9), status = TodoStatus.SUGGESTED)))
        assertFalse(ReminderRules.completes(todo("done", remindAt = at(9), status = TodoStatus.DONE)))
    }
}
