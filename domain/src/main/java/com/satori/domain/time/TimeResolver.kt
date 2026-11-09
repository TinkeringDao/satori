package com.satori.domain.time

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * Confidence: a candidate starts at [BASE] and is multiplied by one factor for each guess made
 * while reading it. The values only need to order readings sensibly; the model sees them as a
 * hint, and the user confirms what matters.
 */
internal object Confidence {
    const val BASE = 0.9f

    /** "um 9": 09:00 and 21:00 are both waking hours. */
    const val EITHER_HALF_OF_DAY = 0.75f

    /** "um 3": only 15:00 is a waking hour. */
    const val HALF_OF_DAY_FROM_WAKING_HOURS = 0.9f

    /** A time without a day whose slot today has passed, so it moved to tomorrow. */
    const val MOVED_TO_TOMORROW = 0.85f

    /** "Freitagabend": only a part of the day, no hour. */
    const val PART_OF_DAY_ONLY = 0.85f

    /** "abends" with no day. */
    const val PART_OF_DAY_WITHOUT_DAY = 0.85f

    /** "nächsten Freitag" when the coming Friday and the Friday of next week differ. */
    const val NEXT_WEEKDAY_AMBIGUOUS = 0.65f

    /** "am Montag" said on a Monday: today or in a week? */
    const val WEEKDAY_IS_TODAY = 0.65f

    /**
     * "nächste Woche", "am Wochenende", "Ende des Monats": a run of days names no one day, so it
     * stays tentative (below 0.5).
     */
    const val DAYS_RANGE = 0.55f

    /** A time on one day of a run of days ("nächste Woche um 10"): which day is a guess. */
    const val RANGE = 0.55f

    /**
     * Two bare numbers after a day read as a range of hours ("Freitag von 10 bis 12"): they may
     * count something else, so the range stays tentative (below 0.5).
     */
    const val BARE_RANGE = 0.55f

    /** "am 16." without a month. */
    const val DAY_OF_MONTH_ONLY = 0.65f

    /** A date without a year that passed this year only days ago: maybe it was about the past. */
    const val RECENT_DATE_MOVED_TO_NEXT_YEAR = 0.85f

    /** "gegen 15 Uhr", "in about an hour". */
    const val APPROXIMATE = 0.9f

    /** A time that took its day from elsewhere in the sentence ("Morgen muss ich um 9 ..."). */
    const val DAY_FROM_SENTENCE = 0.85f

    /** A capital "Morgen" opening a sentence: tomorrow, most likely. */
    const val CAPITAL_MORGEN_AT_START = 0.85f

    /** A capital "Morgen" inside a sentence with no article: a transcription quirk, read as tomorrow. */
    const val CAPITAL_MORGEN = 0.65f

    /** "tomorrow" said between midnight and 04:00 may mean the day that has just begun. */
    const val TOMORROW_AFTER_MIDNIGHT = 0.7f

    /**
     * "Freitag, 17. Oktober" or "Freitag, den 17." when the 17th is not a Friday, also when a
     * later 17th that is one is taken instead: the weekday or the day was said wrong, so the
     * reading stays tentative (below 0.5).
     */
    const val WEEKDAY_MISMATCH = 0.5f

    /** "viertel 9" (08:15) and the British "half three" (03:30). */
    const val REGIONAL = 0.7f

    /** "in a couple of days", "in ein paar Stunden". */
    const val VAGUE_COUNT = 0.6f

    /** "freitags", "jeden Freitag", "every Friday": every Friday, read as the next one. */
    const val RECURRING = 0.7f

    /** "the next day", "am nächsten Morgen": usually told about the past. */
    const val NARRATIVE = 0.6f

    const val MIN = 0.05f
}

/**
 * The days from [first] to [last], both included ("nächste Woche", "am Wochenende"), and the
 * day they most likely mean: [likely], the first day except for "Mitte" (Wednesday, the 15th)
 * and "Ende" (Friday, the last day). A run of days is due on it, and a time said with the run
 * falls on it.
 */
internal class DayRange(val first: LocalDate, val last: LocalDate, val likely: LocalDate = first)

/**
 * A resolved expression. [saidDay] is the day the expression itself named, if any; for a run of
 * days ([saidDays]) its likely day. A time later in the sentence borrows them, with [part].
 * [startAt] is a run's first day, as [TimeCandidate.startAt].
 */
internal class Resolution(
    val at: ZonedDateTime,
    val hasTime: Boolean,
    val confidence: Float,
    val saidDay: LocalDate?,
    val part: DayPart?,
    val precision: TimePrecision,
    val endAt: ZonedDateTime? = null,
    val saidDays: DayRange? = null,
    val startAt: ZonedDateTime? = null
)

/** Turns an [Expression] into a moment, against an anchor and a [TimePolicy]. */
internal class TimeResolver(anchor: ZonedDateTime, private val policy: TimePolicy) {

    private val zone: ZoneId = anchor.zone
    private val now: ZonedDateTime = anchor.truncatedTo(ChronoUnit.MINUTES)
    private val today: LocalDate = now.toLocalDate()
    private val dateOnlyTime: LocalTime = LocalTime.of(policy.dateOnlyHour.coerceIn(0, 23), 0)

    /**
     * Resolves [e]. A time without a day takes the day and part of the day of [context], an
     * expression in the same sentence that named a day or a run of days.
     */
    fun resolve(e: Expression, context: Resolution? = null): Resolution? {
        var confidence = Confidence.BASE
        for (factor in e.factors) confidence *= factor

        val timeOffset = e.timeOffset
        if (timeOffset != null) {
            val at = now.plusMinutes(timeOffset.minutes ?: 0)
            return Resolution(at, true, clamp(confidence), null, null, TimePrecision.EXACT)
        }

        var day: LocalDate? = null
        // A span of dates, week, weekend or month is a run of days; once it has begun, it starts today.
        var days: DayRange? = null
        val date = e.date
        if (date != null) {
            val (resolved, factor) = calendarDate(date, e.weekday?.day) ?: return null
            confidence *= factor
            val first = date.firstDay?.let { firstDayOf(resolved, it) }
            if (first == null) {
                day = resolved
            } else {
                val running = clip(DayRange(first, resolved))
                days = running
                day = running.likely
            }
        }
        val relativeDay = e.relativeDay
        if (relativeDay != null) {
            val resolved = today.plusDays(relativeDay.days.toLong())
            if (day == null) {
                day = resolved
                if (relativeDay.isTomorrow && now.hour < 4) confidence *= Confidence.TOMORROW_AFTER_MIDNIGHT
            } else if (day != resolved) {
                confidence *= Confidence.WEEKDAY_MISMATCH
            }
        }
        val weekday = e.weekday
        val dayOffset = e.dayOffset
        var offsetApplied = false
        if (weekday != null) {
            if (day == null && dayOffset != null && weekday.modifier == WeekdayModifier.NONE && weekday.weeks == null) {
                // "Montag in zwei Wochen", "Mittwoch vor 2 Wochen": that weekday in the week then.
                day = mondayOf(shift(today, dayOffset)).plusDays(weekday.day.value - 1L)
                offsetApplied = true
            } else if (day == null) {
                val (resolved, factor) = weekdayDate(weekday, e.week)
                day = resolved
                confidence *= factor
            } else if (day.dayOfWeek != weekday.day) {
                confidence *= Confidence.WEEKDAY_MISMATCH
            }
        }
        if (day == null) {
            val week = e.week
            val weekend = e.weekend
            val month = e.month
            val run = when {
                week != null -> weekDays(week)
                weekend != null -> {
                    val (resolved, factor) = weekendDays(weekend)
                    confidence *= factor
                    resolved
                }
                month != null -> monthDays(month)
                else -> null
            }
            if (run != null) {
                val running = clip(run)
                days = running
                day = running.likely
            }
        }
        if (dayOffset != null && !offsetApplied) day = shift(day ?: today, dayOffset)

        val saidDay = day
        var part = e.part
        var run = days
        if (day == null && context?.saidDay != null) {
            day = context.saidDay
            confidence *= Confidence.DAY_FROM_SENTENCE
            val borrowed = context.saidDays
            if (borrowed != null) {
                // One day out of "nächste Woche", "am Wochenende": a guess.
                confidence *= Confidence.RANGE
                run = borrowed
            }
            if (part == null) part = context.part
        }
        // "heute Nacht um 1", said at 00:30, is the night that is running, not the next one.
        val nightRunning = now.hour < NIGHT_ENDS_HOUR && relativeDay?.days == 0 && saidDay == today

        val clockPiece = e.clock
        if (clockPiece != null) {
            val until = clockPiece.until
            if (until == null) {
                val (readings, factor) = readings(clockPiece.clock, part)
                confidence *= factor
                if (days != null) confidence *= Confidence.RANGE
                val onDay = day
                val at = when {
                    run != null -> onDays(run, readings)
                    onDay != null -> onDay(onDay, readings, nightRunning)
                    else -> {
                        val (next, moved) = nextOccurrence(readings.map { it.time })
                        // Midnight and the small hours of the night are tomorrow by nature, not by delay.
                        if (moved && readings.none { it.nextDay }) confidence *= Confidence.MOVED_TO_TOMORROW
                        next
                    }
                }
                return Resolution(at, true, clamp(confidence), saidDay, part, TimePrecision.EXACT, saidDays = days)
            }
            val spans = spanReadings(clockPiece.clock, until, part)
            if (spans != null) {
                val (options, factor) = spans
                confidence *= factor
                if (days != null) confidence *= Confidence.RANGE
                val onDay = day
                val (start, end) = when {
                    run != null -> spanOnDays(run, options)
                    onDay != null -> spanOnDay(onDay, options, nightRunning)
                    else -> {
                        val (next, moved) = nextSpan(options)
                        if (moved) confidence *= Confidence.MOVED_TO_TOMORROW
                        next
                    }
                }
                return Resolution(start, true, clamp(confidence), saidDay, part, TimePrecision.EXACT, end, days)
            }
            // The two ends make no range ("von 1 bis 10"): no time, only what else was said.
            if (saidDay == null && e.part == null) return null
        }
        if (days != null) {
            confidence *= Confidence.DAYS_RANGE
            return Resolution(
                ZonedDateTime.of(days.likely, dateOnlyTime, zone), false, clamp(confidence), saidDay, part,
                TimePrecision.DAYS_RANGE, ZonedDateTime.of(days.last, dateOnlyTime, zone), days,
                startAt = ZonedDateTime.of(days.first, dateOnlyTime, zone)
            )
        }
        if (part != null) {
            // Only a part of the day was said: no time, though [at] holds the part's usual hour.
            confidence *= Confidence.PART_OF_DAY_ONLY
            val time = LocalTime.of(part.defaultHour, 0)
            val onDay = day
            if (onDay != null) {
                val at = ZonedDateTime.of(onDay, time, zone)
                return Resolution(at, false, clamp(confidence), saidDay, part, TimePrecision.PART_OF_DAY)
            }
            val (next, moved) = nextOccurrence(listOf(time))
            confidence *= Confidence.PART_OF_DAY_WITHOUT_DAY
            if (moved) confidence *= Confidence.MOVED_TO_TOMORROW
            return Resolution(next, false, clamp(confidence), null, part, TimePrecision.PART_OF_DAY)
        }
        val onDay = day ?: return null
        val at = ZonedDateTime.of(onDay, dateOnlyTime, zone)
        return Resolution(at, false, clamp(confidence), saidDay, null, TimePrecision.DAY)
    }

    private fun clamp(confidence: Float) = confidence.coerceIn(Confidence.MIN, 1f)

    // --- Days --------------------------------------------------------------------------------

    private fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun weekdayDate(w: WeekdayPiece, week: WeekPiece?): Pair<LocalDate, Float> {
        val weeks = w.weeks ?: week?.weeks
        if (weeks != null) {
            return mondayOf(today).plusWeeks(weeks.toLong()).plusDays(w.day.value - 1L) to 1f
        }
        val ahead = Math.floorMod(w.day.value - today.dayOfWeek.value, 7)
        val coming = today.plusDays(if (ahead == 0) 7L else ahead.toLong())
        return when (w.modifier) {
            WeekdayModifier.THIS -> today.plusDays(ahead.toLong()) to 1f
            WeekdayModifier.COMING -> coming to 1f
            WeekdayModifier.NONE -> {
                val factor = if (ahead == 0) Confidence.WEEKDAY_IS_TODAY else 1f
                val chosen = when {
                    policy.preferFuture -> coming
                    ahead <= 3 -> today.plusDays(ahead.toLong())
                    else -> today.minusDays(7L - ahead)
                }
                chosen to factor
            }
            WeekdayModifier.NEXT, WeekdayModifier.AFTER_NEXT -> {
                val following = mondayOf(today).plusWeeks(1).plusDays(w.day.value - 1L)
                val chosen = if (policy.nextWeekdayMeansFollowingWeek) following else coming
                val factor = if (following == coming) 1f else Confidence.NEXT_WEEKDAY_AMBIGUOUS
                (if (w.modifier == WeekdayModifier.AFTER_NEXT) chosen.plusWeeks(1) else chosen) to factor
            }
            WeekdayModifier.LAST -> {
                val back = Math.floorMod(today.dayOfWeek.value - w.day.value, 7)
                today.minusDays(if (back == 0) 7L else back.toLong()) to 1f
            }
        }
    }

    /**
     * A week as its days: Monday to Sunday, or the part a position names: "Anfang" Monday and
     * Tuesday, "Mitte" Tuesday to Thursday, "Ende" Thursday and Friday. A part of this week that
     * has passed ("Ende der Woche" said on a Sunday) is next week's.
     */
    private fun weekDays(w: WeekPiece): DayRange {
        fun inWeek(weeks: Int): DayRange {
            val monday = mondayOf(today).plusWeeks(weeks.toLong())
            return when (w.position) {
                Position.WHOLE -> DayRange(monday, monday.plusDays(6))
                Position.START -> DayRange(monday, monday.plusDays(1))
                Position.MIDDLE -> DayRange(monday.plusDays(1), monday.plusDays(3), likely = monday.plusDays(2))
                Position.END -> DayRange(monday.plusDays(3), monday.plusDays(4), likely = monday.plusDays(4))
            }
        }
        val days = inWeek(w.weeks)
        return if (w.weeks == 0 && policy.preferFuture && days.last.isBefore(today)) inWeek(1) else days
    }

    /** A weekend as its Saturday and Sunday; "next weekend" follows the next-weekday policy. */
    private fun weekendDays(w: WeekendPiece): Pair<DayRange, Float> {
        val isWeekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
        val saturday = mondayOf(today).plusDays(5)
        fun weekend(start: LocalDate) = DayRange(start, start.plusDays(1))
        return when {
            w.which == 0 -> weekend(saturday) to 1f
            w.which < 0 -> weekend(saturday.minusWeeks(1)) to 1f
            else -> {
                val coming = if (isWeekend) saturday.plusWeeks(1) else saturday
                val following = saturday.plusWeeks(1)
                val chosen = if (policy.nextWeekdayMeansFollowingWeek) following else coming
                weekend(chosen) to (if (coming == following) 1f else Confidence.NEXT_WEEKDAY_AMBIGUOUS)
            }
        }
    }

    /**
     * A month as its days, or the part a position names: "Anfang" the 1st to the 10th, "Mitte"
     * the 11th to the 20th, "Ende" the 21st to the last. A part of this month that has passed
     * ("Anfang des Monats" in mid-month) is next month's.
     */
    private fun monthDays(m: MonthPiece): DayRange {
        fun inMonth(months: Int): DayRange {
            val first = today.withDayOfMonth(1).plusMonths(months.toLong())
            val last = first.withDayOfMonth(first.lengthOfMonth())
            return when (m.position) {
                Position.WHOLE -> DayRange(first, last)
                Position.START -> DayRange(first, first.withDayOfMonth(10))
                Position.MIDDLE -> DayRange(first.withDayOfMonth(11), first.withDayOfMonth(20), likely = first.withDayOfMonth(15))
                Position.END -> DayRange(first.withDayOfMonth(21), last, likely = last)
            }
        }
        val days = inMonth(m.months)
        return if (m.months == 0 && policy.preferFuture && days.last.isBefore(today)) inMonth(1) else days
    }

    /** A run of days that has begun ("diese Woche", "am Wochenende" on a Sunday) starts today. */
    private fun clip(days: DayRange): DayRange =
        if (policy.preferFuture && days.first.isBefore(today) && !days.last.isBefore(today)) {
            DayRange(today, days.last, if (days.likely.isBefore(today)) today else days.likely)
        } else {
            days
        }

    private fun shift(day: LocalDate, offset: OffsetPiece): LocalDate {
        val unit = when (offset.unit) {
            OffsetUnit.WEEK, OffsetUnit.FORTNIGHT -> ChronoUnit.WEEKS
            OffsetUnit.MONTH -> ChronoUnit.MONTHS
            OffsetUnit.YEAR -> ChronoUnit.YEARS
            else -> ChronoUnit.DAYS
        }
        return day.plus(offset.amount, unit).plusDays(offset.extraDays)
    }

    private fun calendarDate(d: DatePiece, weekday: DayOfWeek?): Pair<LocalDate, Float>? {
        val month = d.month ?: return dayOfMonth(d.day, weekday)
        val year = d.year
        if (year != null) {
            val full = if (d.twoDigitYear) fullYear(year) else year
            return dateOrNull(full, month, d.day)?.let { it to 1f }
        }
        if (!policy.preferFuture) {
            return (today.year - 1..today.year + 1)
                .mapNotNull { dateOrNull(it, month, d.day) }
                .minByOrNull { abs(ChronoUnit.DAYS.between(today, it)) }
                ?.let { it to 1f }
        }
        val thisYear = dateOrNull(today.year, month, d.day)
        if (thisYear != null && !thisYear.isBefore(today)) return thisYear to 1f
        val recentlyPassed = thisYear != null && ChronoUnit.DAYS.between(thisYear, today) <= RECENT_DAYS
        for (y in today.year + 1..today.year + 8) {
            val next = dateOrNull(y, month, d.day) ?: continue
            return next to (if (recentlyPassed) Confidence.RECENT_DATE_MOVED_TO_NEXT_YEAR else 1f)
        }
        return null
    }

    /**
     * "the 16th", "am 16.": the next 16th. With a weekday that the 16th is not on ("Freitag, den
     * 17." when the 17th is a Saturday) a matching 16th a few weeks on wins ("Friday the 13th"),
     * else the next 16th stays and [resolve] scores the mismatch: a weekday or day said wrong
     * should not move a todo by months. Either way the reading is tentative.
     */
    private fun dayOfMonth(day: Int, weekday: DayOfWeek?): Pair<LocalDate, Float>? {
        val start = YearMonth.from(today)
        val months = if (policy.preferFuture) 0L..12L else -1L..1L
        val dates = months.mapNotNull { k ->
            val month = start.plusMonths(k)
            if (day > month.lengthOfMonth()) null else month.atDay(day)
        }
        val next = if (policy.preferFuture) {
            dates.firstOrNull { !it.isBefore(today) }
        } else {
            dates.minByOrNull { abs(ChronoUnit.DAYS.between(today, it)) }
        } ?: return null
        if (weekday == null) return next to Confidence.DAY_OF_MONTH_ONLY
        if (next.dayOfWeek == weekday) return next to 1f
        val later = dates.firstOrNull {
            it.isAfter(next) && it.dayOfWeek == weekday && ChronoUnit.DAYS.between(today, it) <= MATCH_WITHIN_DAYS
        }
        return if (later != null) later to Confidence.WEEKDAY_MISMATCH else next to 1f
    }

    /**
     * The first day of a span of dates that ends on [last]: [firstDay] in its month, or in the
     * month before when it is the higher number ("vom 28. bis 2. November"). Null when that month
     * has no such day; the date then stands alone.
     */
    private fun firstDayOf(last: LocalDate, firstDay: Int): LocalDate? {
        if (firstDay <= last.dayOfMonth) return last.withDayOfMonth(firstDay)
        val before = YearMonth.from(last).minusMonths(1)
        return if (firstDay <= before.lengthOfMonth()) before.atDay(firstDay) else null
    }

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        if (day <= YearMonth.of(year, month).lengthOfMonth()) LocalDate.of(year, month, day) else null

    private fun fullYear(twoDigits: Int): Int =
        if (2000 + twoDigits <= today.year + 50) 2000 + twoDigits else 1900 + twoDigits

    // --- Times --------------------------------------------------------------------------------

    /** A possible wall time; [nextDay] for midnight and small hours "nachts" after a named day. */
    private class Reading(val time: LocalTime, val nextDay: Boolean = false)

    /** The wall times [clock] may mean, and the factor for having to choose. */
    private fun readings(clock: Clock, part: DayPart?): Pair<List<Reading>, Float> {
        val m = clock.minute
        val s = clock.second
        if (clock.midnight) return listOf(Reading(LocalTime.of(0, m, s), nextDay = true)) to 1f
        if (clock.hour == 24) return listOf(Reading(LocalTime.of(0, m, s), nextDay = true)) to 1f
        val meridiem = clock.meridiem
        if (meridiem != null && clock.hour in 1..12) {
            val hour = clock.hour % 12 + (if (meridiem == Meridiem.PM) 12 else 0)
            // "tonight at 2 am": the small hours after the evening.
            val night = meridiem == Meridiem.AM && hour <= SMALL_HOURS_END &&
                (part == DayPart.EVENING || part == DayPart.NIGHT)
            return listOf(Reading(LocalTime.of(hour, m, s), nextDay = night)) to 1f
        }
        if (clock.fixed || clock.hour > 12) return listOf(Reading(LocalTime.of(clock.hour, m, s))) to 1f
        if (part != null) return listOf(withPart(clock.hour % 12, m, s, part)) to 1f
        val morning = clock.hour % 12
        val waking = listOf(morning, morning + 12).filter { it in policy.wakingHours }
        return when (waking.size) {
            2 -> waking.map { Reading(LocalTime.of(it, m, s)) } to Confidence.EITHER_HALF_OF_DAY
            1 -> listOf(Reading(LocalTime.of(waking[0], m, s))) to Confidence.HALF_OF_DAY_FROM_WAKING_HOURS
            else -> listOf(Reading(LocalTime.of(clock.hour, m, s))) to Confidence.HALF_OF_DAY_FROM_WAKING_HOURS
        }
    }

    /** A 12-hour clock (0 to 11) read with a part of the day: "um 8 abends" = 20:00, "at 2 tonight" = 02:00. */
    private fun withPart(hour: Int, m: Int, s: Int, part: DayPart): Reading = when (part) {
        DayPart.MORNING, DayPart.FORENOON -> Reading(LocalTime.of(hour, m, s))
        DayPart.NOON -> Reading(LocalTime.of(if (hour in 1..5) hour + 12 else if (hour == 0) 12 else hour, m, s))
        DayPart.AFTERNOON -> Reading(LocalTime.of(if (hour == 0) 12 else hour + 12, m, s))
        DayPart.EVENING -> when (hour) {
            0 -> Reading(LocalTime.of(0, m, s), nextDay = true)
            in 1..4 -> Reading(LocalTime.of(hour, m, s), nextDay = true)
            else -> Reading(LocalTime.of(hour + 12, m, s))
        }
        DayPart.NIGHT -> when (hour) {
            0 -> Reading(LocalTime.of(0, m, s), nextDay = true)
            in 1..SMALL_HOURS_END -> Reading(LocalTime.of(hour, m, s), nextDay = true)
            else -> Reading(LocalTime.of(hour + 12, m, s))
        }
    }

    /** On a named day: the reading still ahead (or nearest, without preferFuture). */
    private fun onDay(day: LocalDate, readings: List<Reading>, nightRunning: Boolean = false): ZonedDateTime {
        val options = readings.map { ZonedDateTime.of(day.plusDays(daysAfter(day, it, nightRunning)), it.time, zone) }
        if (options.size == 1) return options[0]
        if (!policy.preferFuture) return options.minBy { abs(ChronoUnit.SECONDS.between(now, it)) }
        return options.filter { !it.isBefore(now) }.minOrNull() ?: options.max()
    }

    /** 1 for midnight and the small hours after a named day, 0 while that night is running. */
    private fun daysAfter(day: LocalDate, r: Reading, nightRunning: Boolean): Long {
        if (!r.nextDay) return 0
        val smallHours = r.time.hour in 1..SMALL_HOURS_END
        if (nightRunning && smallHours && !ZonedDateTime.of(day, r.time, zone).isBefore(now)) return 0
        return 1
    }

    /** In a run of days: from its likely day on, the first day on which the time is still ahead. */
    private fun onDays(run: DayRange, readings: List<Reading>): ZonedDateTime {
        var d = run.likely
        while (!d.isAfter(run.last)) {
            val at = onDay(d, readings)
            if (!policy.preferFuture || !at.isBefore(now)) return at
            d = d.plusDays(1)
        }
        return onDay(run.likely, readings)
    }

    /**
     * Without a day: the soonest of [times] from now on (today or tomorrow), or the nearest one
     * either way without preferFuture. Also says whether today's slot had passed.
     */
    private fun nextOccurrence(times: List<LocalTime>): Pair<ZonedDateTime, Boolean> {
        val days = if (policy.preferFuture) listOf(today, today.plusDays(1)) else listOf(today.minusDays(1), today, today.plusDays(1))
        var best: ZonedDateTime? = null
        for (time in times) {
            for (d in days) {
                val at = ZonedDateTime.of(d, time, zone)
                if (policy.preferFuture && at.isBefore(now)) continue
                val current = best
                best = when {
                    current == null -> at
                    policy.preferFuture -> if (at.isBefore(current)) at else current
                    else -> {
                        val gap = abs(ChronoUnit.SECONDS.between(now, at))
                        val currentGap = abs(ChronoUnit.SECONDS.between(now, current))
                        if (gap < currentGap || (gap == currentGap && at.isAfter(current))) at else current
                    }
                }
            }
        }
        val chosen = best ?: ZonedDateTime.of(today.plusDays(1), times.first(), zone)
        return chosen to (policy.preferFuture && chosen.toLocalDate() != today)
    }

    // --- Ranges of clock times -----------------------------------------------------------------

    /** One reading of a range: its start and end, [startDays] and [endDays] after the day it is on. */
    private class Span(val startDays: Int, val start: LocalTime, val endDays: Int, val end: LocalTime)

    /** A wall time one end of a range may mean, and whether it is a likely one. */
    private class EndReading(val reading: Reading, val likely: Boolean)

    /** An hour said without am, pm or the 24-hour clock ("10", "halb 9"): it fits either half of the day. */
    private fun eitherHalf(clock: Clock): Boolean =
        clock.meridiem == null && !clock.fixed && !clock.midnight && clock.hour in 1..12

    /** A bare hour that could be either half of the day ("von 10", "between 9"), with no part of the day to tell. */
    private fun isFree(clock: Clock, part: DayPart?): Boolean = part == null && eitherHalf(clock)

    private fun endReadings(clock: Clock, part: DayPart?, isEnd: Boolean, literal: Boolean): List<EndReading> {
        if (literal) return listOf(EndReading(Reading(LocalTime.of(clock.hour, clock.minute, clock.second)), true))
        if (!isFree(clock, part)) return readings(clock, part).first.map { EndReading(it, true) }
        val morning = clock.hour % 12
        return listOf(morning, morning + 12).map { hour ->
            val time = LocalTime.of(hour, clock.minute, clock.second)
            EndReading(Reading(time), if (isEnd) isLikelyEnd(time) else hour in policy.wakingHours)
        }
    }

    /** An end inside waking hours or right at their close ("bis 22 Uhr" with waking hours to 21). */
    private fun isLikelyEnd(time: LocalTime): Boolean {
        val minutes = time.hour * 60 + time.minute
        return minutes >= policy.wakingHours.first * 60 && minutes <= (policy.wakingHours.last + 1) * 60
    }

    private fun minuteOf(r: Reading): Int = r.time.hour * 60 + r.time.minute + (if (r.nextDay) MINUTES_PER_DAY else 0)

    /**
     * The readings of a range, read together: the end comes after the start, on the next day if
     * the clock goes past midnight. A bare start takes the end's half of the day ("from 6 to 8
     * pm" is 18:00 to 20:00) or its 24-hour clock ("von 6 bis 22 Uhr" is 06:00 to 22:00); with an
     * evening or a night both ends are one stretch of it ([oneStretch]). Two bare ends are at
     * most 8 hours apart; one bare end, or a range said with a part of the day, 12. Readings
     * inside waking hours win; null when no reading makes a range ("von 1 bis 10").
     */
    private fun spanReadings(start: Clock, end: Clock, part: DayPart?): Pair<List<Span>, Float>? {
        val startFree = isFree(start, part)
        val endFree = isFree(end, part)
        val literalStart = startFree && !endFree && end.meridiem == null && end.fixed && !end.midnight &&
            end.hour in 13..23 && start.hour < end.hour
        var startOptions = endReadings(start, part, isEnd = false, literal = literalStart)
        var endOptions = endReadings(end, part, isEnd = true, literal = false)
        if (part == DayPart.EVENING || part == DayPart.NIGHT) {
            // With a part of the day each end has its one reading.
            val (s, e) = oneStretch(start, end, part, startOptions.single().reading, endOptions.single().reading)
            startOptions = listOf(EndReading(s, true))
            endOptions = listOf(EndReading(e, true))
        }
        val ambiguous = (startFree && !literalStart) || endFree
        val longest = when {
            startFree && endFree -> MAX_BARE_RANGE_MINUTES
            ambiguous || part != null -> MAX_RANGE_MINUTES
            else -> MINUTES_PER_DAY - 1
        }
        var best = -1
        val top = ArrayList<Span>()
        for (s in startOptions) {
            for (e in endOptions) {
                val from = minuteOf(s.reading)
                var to = minuteOf(e.reading)
                if (to <= from) to += MINUTES_PER_DAY
                // Still not after the start ("Mitternacht bis 0 Uhr"), or too long: no range.
                if (to <= from || to - from > longest) continue
                val score = (if (s.likely) 2 else 0) + (if (e.likely) 1 else 0)
                if (score < best) continue
                if (score > best) {
                    best = score
                    top.clear()
                }
                top += Span(from / MINUTES_PER_DAY, s.reading.time, to / MINUTES_PER_DAY, e.reading.time)
            }
        }
        if (top.isEmpty()) return null
        val factor = when {
            !ambiguous -> 1f
            top.size > 1 -> Confidence.EITHER_HALF_OF_DAY
            else -> Confidence.HALF_OF_DAY_FROM_WAKING_HOURS
        }
        return top to factor
    }

    /**
     * The ends [s] and [e] of a range said with an evening or a night ([part]), as one stretch of
     * it. Read alone, a start in the small hours comes after an end in the evening; in the evening
     * the start is then the afternoon's ("heute Abend von 4 bis 6" is 16:00 to 18:00, not 04:00
     * the next morning to 18:00), at night the end is the next morning's ("heute Nacht von 3 bis
     * 7" is 03:00 to 07:00). An end before an evening start is the next morning's too ("nachts
     * von 10 bis 6" is 22:00 to 06:00). Only an hour said without am, pm or the 24-hour clock
     * moves.
     */
    private fun oneStretch(start: Clock, end: Clock, part: DayPart, s: Reading, e: Reading): Pair<Reading, Reading> {
        fun afternoon(c: Clock) = Reading(LocalTime.of(c.hour % 12 + 12, c.minute, c.second))
        fun nextMorning(c: Clock) = Reading(LocalTime.of(c.hour % 12, c.minute, c.second), nextDay = true)
        return when {
            s.nextDay && !e.nextDay && part == DayPart.EVENING && eitherHalf(start) -> afternoon(start) to e
            s.nextDay && !e.nextDay && eitherHalf(end) -> s to nextMorning(end)
            !s.nextDay && !e.nextDay && e.time.isBefore(s.time) && eitherHalf(end) -> s to nextMorning(end)
            else -> s to e
        }
    }

    /**
     * [s] on [day] in the zone. A start that the clocks skip in spring ("heute Nacht von 2:30 bis
     * 3 Uhr") moves past the gap, as a single time does, and the end keeps the range's length.
     */
    private fun place(day: LocalDate, s: Span, nightRunning: Boolean = false): Pair<ZonedDateTime, ZonedDateTime> {
        val back = if (
            nightRunning && s.startDays == 1 && s.start.hour in 1..SMALL_HOURS_END &&
            !ZonedDateTime.of(day, s.start, zone).isBefore(now)
        ) 1 else 0
        val startWall = LocalDateTime.of(day.plusDays((s.startDays - back).toLong()), s.start)
        val endWall = LocalDateTime.of(day.plusDays((s.endDays - back).toLong()), s.end)
        val start = ZonedDateTime.of(startWall, zone)
        val end = ZonedDateTime.of(endWall, zone)
        return start to (if (end.isAfter(start)) end else start.plus(Duration.between(startWall, endWall)))
    }

    /**
     * On a named day: the reading not over yet, earliest first (or the nearest start, without
     * preferFuture). A range that ends at this very minute is not over, as a time said for now
     * is not past.
     */
    private fun spanOnDay(
        day: LocalDate,
        spans: List<Span>,
        nightRunning: Boolean = false
    ): Pair<ZonedDateTime, ZonedDateTime> {
        val options = spans.map { place(day, it, nightRunning) }
        if (options.size == 1) return options[0]
        if (!policy.preferFuture) return options.minBy { abs(ChronoUnit.SECONDS.between(now, it.first)) }
        return options.filter { !it.second.isBefore(now) }.minByOrNull { it.first } ?: options.maxBy { it.first }
    }

    /** In a run of days: from its likely day on, the first day on which the range is not over yet. */
    private fun spanOnDays(run: DayRange, spans: List<Span>): Pair<ZonedDateTime, ZonedDateTime> {
        var d = run.likely
        while (!d.isAfter(run.last)) {
            val option = spanOnDay(d, spans)
            if (!policy.preferFuture || !option.second.isBefore(now)) return option
            d = d.plusDays(1)
        }
        return spanOnDay(run.likely, spans)
    }

    /**
     * Without a day: the next range not over yet, today's or tomorrow's, so a range that is
     * running or ends right now is today's ("um 9 bis 10 Uhr" said at 10:00); the nearest start
     * either way without preferFuture. Also says whether it moved to tomorrow.
     */
    private fun nextSpan(spans: List<Span>): Pair<Pair<ZonedDateTime, ZonedDateTime>, Boolean> {
        val days = if (policy.preferFuture) listOf(today, today.plusDays(1)) else listOf(today.minusDays(1), today, today.plusDays(1))
        val options = days.flatMap { d -> spans.map { d to place(d, it) } }
        val chosen = if (policy.preferFuture) {
            options.filter { !it.second.second.isBefore(now) }.minByOrNull { it.second.first } ?: options.last()
        } else {
            options.minBy { abs(ChronoUnit.SECONDS.between(now, it.second.first)) }
        }
        val moved = policy.preferFuture && chosen.first != today && spans.none { it.startDays > 0 }
        return chosen.second to moved
    }

    private companion object {
        const val RECENT_DAYS = 60L

        /** "Friday the 13th" looks this many days ahead for a 13th that is a Friday. */
        const val MATCH_WITHIN_DAYS = 35L

        /** Small hours, 01:00 to 05:59: "um 2 nachts", "tonight at 2 am". */
        const val SMALL_HOURS_END = 5

        /** Before this hour "heute Nacht" and "tonight" can still mean the night that is running. */
        const val NIGHT_ENDS_HOUR = 5

        const val MINUTES_PER_DAY = 24 * 60
        const val MAX_RANGE_MINUTES = 12 * 60
        const val MAX_BARE_RANGE_MINUTES = 8 * 60
    }
}
