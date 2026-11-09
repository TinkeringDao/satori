package com.satori.domain.usecase.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TodoDueLabelTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    // Wednesday 14 October 2026, 10:00 in Berlin.
    private val now = millis(2026, 10, 14, 10, 0)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = berlin): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun label(dueAt: Long, hasTime: Boolean = true, at: Long = now, zone: ZoneId = berlin) =
        TodoDueLabel.of(dueAt, hasTime, at, zone)

    @Test
    fun `a time later today reads Today`() {
        val due = label(millis(2026, 10, 14, 11, 0))

        assertEquals(DueDay.Today, due.day)
        assertTrue(due.hasTime)
        assertFalse(due.overdue)
    }

    @Test
    fun `a day only for today reads Today without a time`() {
        val due = label(millis(2026, 10, 14, 9, 0), hasTime = false)

        assertEquals(DueDay.Today, due.day)
        assertFalse(due.hasTime)
    }

    @Test
    fun `tomorrow reads Tomorrow`() {
        assertEquals(DueDay.Tomorrow, label(millis(2026, 10, 15, 14, 30)).day)
        assertEquals(DueDay.Tomorrow, label(millis(2026, 10, 15, 9, 0), hasTime = false).day)
    }

    @Test
    fun `the days after tomorrow up to six days ahead read by weekday`() {
        assertEquals(DueDay.Weekday(DayOfWeek.FRIDAY), label(millis(2026, 10, 16, 9, 0)).day)
        assertEquals(DueDay.Weekday(DayOfWeek.TUESDAY), label(millis(2026, 10, 20, 9, 0)).day)
    }

    @Test
    fun `a week ahead and later reads by date`() {
        // The same weekday as today would read like today, so the seventh day is a date.
        assertEquals(DueDay.Date(LocalDate.of(2026, 10, 21), withYear = false), label(millis(2026, 10, 21, 9, 0)).day)
        assertEquals(DueDay.Date(LocalDate.of(2026, 12, 24), withYear = false), label(millis(2026, 12, 24, 18, 0)).day)
    }

    @Test
    fun `a date in another year carries the year`() {
        assertEquals(DueDay.Date(LocalDate.of(2027, 1, 5), withYear = true), label(millis(2027, 1, 5, 9, 0)).day)
    }

    @Test
    fun `a time that has passed today is overdue and still reads Today`() {
        val due = label(millis(2026, 10, 14, 9, 0))

        assertEquals(DueDay.Today, due.day)
        assertTrue(due.overdue)
    }

    @Test
    fun `a past day reads by date and is overdue`() {
        val timed = label(millis(2026, 10, 13, 17, 0))
        val dayOnly = label(millis(2026, 10, 13, 9, 0), hasTime = false)

        assertEquals(DueDay.Date(LocalDate.of(2026, 10, 13), withYear = false), timed.day)
        assertTrue(timed.overdue)
        assertTrue(dayOnly.overdue)
        assertFalse(dayOnly.hasTime)
    }

    @Test
    fun `a day only for today is not overdue even after the hour dates get`() {
        val lateInTheDay = millis(2026, 10, 14, 22, 0)

        assertFalse(label(millis(2026, 10, 14, 9, 0), hasTime = false, at = lateInTheDay).overdue)
    }

    @Test
    fun `a day only for today is overdue when the day is over`() {
        val nextMorning = millis(2026, 10, 15, 7, 0)

        assertTrue(label(millis(2026, 10, 14, 9, 0), hasTime = false, at = nextMorning).overdue)
    }

    @Test
    fun `days count on the calendar of the zone, not in 24 hour windows`() {
        // 23:30 on the 14th: half past midnight is tomorrow, though it is an hour away.
        val lateEvening = millis(2026, 10, 14, 23, 30)

        assertEquals(DueDay.Tomorrow, label(millis(2026, 10, 15, 0, 30), at = lateEvening).day)
    }

    @Test
    fun `the same moment is today in one zone and tomorrow in another`() {
        val due = millis(2026, 10, 14, 22, 30, zone = ZoneId.of("UTC"))

        assertEquals(DueDay.Tomorrow, label(due, zone = berlin).day)
        assertEquals(DueDay.Today, label(due, at = millis(2026, 10, 14, 8, 0, ZoneId.of("UTC")), zone = ZoneId.of("UTC")).day)
    }
}
