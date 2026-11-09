package com.satori.domain.usecase.todo

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * When a to-do is due, as a list puts it. The screen words [day] (and the time, when [hasTime])
 * in the app's language and the phone's 12/24-hour style; this only decides which way to say it.
 *
 * @property hasTime False for a to-do due on a day without a clock time
 * @property overdue The due time has passed (a to-do due on a day only is overdue after that day)
 */
data class TodoDue(val day: DueDay, val hasTime: Boolean, val overdue: Boolean)

/** The day part of a [TodoDue]. */
sealed interface DueDay {
    data object Today : DueDay

    data object Tomorrow : DueDay

    /** One of the five days after tomorrow, said by its name ("Fri"). */
    data class Weekday(val dayOfWeek: DayOfWeek) : DueDay

    /** Any other day, past or far ahead; [withYear] when it is not in the current year. */
    data class Date(val date: LocalDate, val withYear: Boolean) : DueDay
}

/** The wording rule of the due line under a to-do. */
object TodoDueLabel {

    /** Today and tomorrow have names; the next days after them go by weekday; the rest by date. */
    private const val LAST_WEEKDAY_OFFSET = 6L

    /**
     * Today, tomorrow, one of the days up to six days ahead ("Fri"), or the date; the same
     * wording for a day that has passed, which is then [TodoDue.overdue].
     *
     * Days count in [zone] (the phone's zone). A to-do due on a day only (its [dueAt] sits at
     * the hour dates get) is overdue only once that day is over; one with a clock time is
     * overdue as soon as that time has passed.
     *
     * @param dueAt When it is due (epoch ms)
     * @param dueHasTime False when only the day was meant
     * @param nowMs The current time (epoch ms)
     */
    fun of(dueAt: Long, dueHasTime: Boolean, nowMs: Long, zone: ZoneId): TodoDue {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val date = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        val day = when (ChronoUnit.DAYS.between(today, date)) {
            0L -> DueDay.Today
            1L -> DueDay.Tomorrow
            in 2L..LAST_WEEKDAY_OFFSET -> DueDay.Weekday(date.dayOfWeek)
            else -> DueDay.Date(date, withYear = date.year != today.year)
        }
        val overdue = if (dueHasTime) dueAt < nowMs else date.isBefore(today)
        return TodoDue(day = day, hasTime = dueHasTime, overdue = overdue)
    }
}
