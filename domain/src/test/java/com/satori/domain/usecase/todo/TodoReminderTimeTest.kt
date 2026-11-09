package com.satori.domain.usecase.todo

import com.satori.domain.model.TodoKind
import com.satori.domain.time.TimePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TodoReminderTimeTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    // Wednesday 14 October 2026, 7:00 in Berlin.
    private val now = millis(14, 7, 0)

    private fun millis(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(berlin).toInstant().toEpochMilli()

    private fun remindAt(
        dueAt: Long?,
        hasTime: Boolean = true,
        kind: TodoKind = TodoKind.TASK,
        at: Long = now
    ) = TodoReminderTime.of(dueAt, hasTime, kind, at, berlin)

    @Test
    fun `a to-do without a due time has no reminder`() {
        assertNull(remindAt(null))
        assertNull(remindAt(null, kind = TodoKind.APPOINTMENT))
    }

    @Test
    fun `a to-do with a clock time reminds at that time`() {
        assertEquals(millis(14, 15, 30), remindAt(millis(14, 15, 30)))
    }

    @Test
    fun `an appointment with a clock time reminds an hour before`() {
        assertEquals(millis(15, 8, 0), remindAt(millis(15, 9, 0), kind = TodoKind.APPOINTMENT))
    }

    @Test
    fun `an appointment mentioned less than an hour ahead reminds at its start`() {
        val halfAnHourBefore = millis(14, 8, 30)

        assertEquals(millis(14, 9, 0), remindAt(millis(14, 9, 0), kind = TodoKind.APPOINTMENT, at = halfAnHourBefore))
    }

    @Test
    fun `no reminder is set for a moment that has passed`() {
        val afterIt = millis(14, 9, 1)

        assertNull(remindAt(millis(14, 9, 0), at = afterIt))
        assertNull(remindAt(millis(14, 9, 0), kind = TodoKind.APPOINTMENT, at = afterIt))
    }

    @Test
    fun `a reminder at exactly the current time is still set`() {
        assertEquals(now, remindAt(now))
    }

    @Test
    fun `a to-do due on a day only reminds at nine that day`() {
        // The due moment of such a to-do sits at nine already; any other hour in it is ignored.
        assertEquals(millis(16, 9, 0), remindAt(millis(16, 9, 0), hasTime = false))
        assertEquals(millis(16, 9, 0), remindAt(millis(16, 14, 45), hasTime = false))
    }

    @Test
    fun `an appointment on a day only has no lead time`() {
        assertEquals(millis(16, 9, 0), remindAt(millis(16, 9, 0), hasTime = false, kind = TodoKind.APPOINTMENT))
    }

    @Test
    fun `a day only for today after nine has no reminder`() {
        assertNull(remindAt(millis(14, 9, 0), hasTime = false, at = millis(14, 10, 0)))
    }

    @Test
    fun `the hour dates get follows the policy`() {
        val eightOClock = TimePolicy(dateOnlyHour = 8)

        assertEquals(
            millis(16, 8, 0),
            TodoReminderTime.of(millis(16, 9, 0), false, TodoKind.TASK, now, berlin, eightOClock)
        )
    }

    @Test
    fun `a due moment is the day at the given time, or at nine for a day only`() {
        val friday = LocalDate.of(2026, 10, 16)

        assertEquals(millis(16, 14, 30), TodoReminderTime.dueAt(friday, LocalTime.of(14, 30), berlin))
        assertEquals(millis(16, 9, 0), TodoReminderTime.dueAt(friday, time = null, zone = berlin))
    }
}
