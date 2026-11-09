package com.satori.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

internal fun berlin(localDateTime: String): ZonedDateTime =
    LocalDateTime.parse(localDateTime).atZone(BERLIN)

/** Monday 12 October 2026, 10:00 in Berlin (CEST): the default anchor. */
internal val MONDAY_10: ZonedDateTime = berlin("2026-10-12T10:00")

/** Sunday 11 October 2026, 10:00. */
internal val SUNDAY_10: ZonedDateTime = berlin("2026-10-11T10:00")

/** Friday 16 October 2026, 22:30: late in the evening. */
internal val FRIDAY_LATE: ZonedDateTime = berlin("2026-10-16T22:30")

/** The parser under test with the default policy, plus assertions that name the text. */
internal class Probe(private val parser: TimeExpressionParser = RuleBasedTimeParser()) {

    fun all(text: String, anchor: ZonedDateTime = MONDAY_10, policy: TimePolicy = TimePolicy()) =
        parser.find(text, anchor, policy)

    /** The single expression in [text]; fails if there is none or more than one. */
    fun one(text: String, anchor: ZonedDateTime = MONDAY_10, policy: TimePolicy = TimePolicy()): TimeCandidate {
        val found = all(text, anchor, policy)
        assertEquals("one expression in \"$text\", found $found", 1, found.size)
        return found.single()
    }

    fun none(text: String, anchor: ZonedDateTime = MONDAY_10) {
        val found = all(text, anchor)
        assertTrue("no expression in \"$text\", found $found", found.isEmpty())
    }

    /**
     * Asserts a single moment as an ISO offset date-time, e.g. "2026-10-13T09:00+02:00": a clock
     * time (precision EXACT) or, with [hasTime] false, a day (precision DAY).
     */
    fun at(
        expected: String,
        text: String,
        hasTime: Boolean = true,
        anchor: ZonedDateTime = MONDAY_10,
        policy: TimePolicy = TimePolicy()
    ): TimeCandidate {
        val candidate = one(text, anchor, policy)
        assertEquals("moment for \"$text\"", expected, candidate.at.toOffsetDateTime().toString())
        assertEquals("hasTime for \"$text\"", hasTime, candidate.hasTime)
        val precision = if (hasTime) TimePrecision.EXACT else TimePrecision.DAY
        assertEquals("precision for \"$text\"", precision, candidate.precision)
        assertNull("no end for \"$text\"", candidate.endAt)
        assertNull("no first day for \"$text\"", candidate.startAt)
        return candidate
    }

    /** A date without a time: [expectedDay] is "yyyy-MM-dd", the time is the policy's date-only hour. */
    fun day(
        expectedDay: String,
        text: String,
        anchor: ZonedDateTime = MONDAY_10,
        policy: TimePolicy = TimePolicy()
    ): TimeCandidate {
        val candidate = one(text, anchor, policy)
        assertEquals("day for \"$text\"", expectedDay, candidate.at.toLocalDate().toString())
        assertEquals("hour for \"$text\"", policy.dateOnlyHour, candidate.at.hour)
        assertEquals("minute for \"$text\"", 0, candidate.at.minute)
        assertEquals("hasTime for \"$text\"", false, candidate.hasTime)
        assertEquals("precision for \"$text\"", TimePrecision.DAY, candidate.precision)
        assertNull("no end for \"$text\"", candidate.endAt)
        assertNull("no first day for \"$text\"", candidate.startAt)
        return candidate
    }

    /** Only a part of the day: the day at the part's usual hour, without a time. */
    fun partOfDay(
        expected: String,
        text: String,
        anchor: ZonedDateTime = MONDAY_10,
        policy: TimePolicy = TimePolicy()
    ): TimeCandidate {
        val candidate = one(text, anchor, policy)
        assertEquals("moment for \"$text\"", expected, candidate.at.toOffsetDateTime().toString())
        assertEquals("hasTime for \"$text\"", false, candidate.hasTime)
        assertEquals("precision for \"$text\"", TimePrecision.PART_OF_DAY, candidate.precision)
        assertNull("no end for \"$text\"", candidate.endAt)
        assertNull("no first day for \"$text\"", candidate.startAt)
        return candidate
    }

    /** A range of clock times, both ends as ISO offset date-times. */
    fun range(
        expectedStart: String,
        expectedEnd: String,
        text: String,
        anchor: ZonedDateTime = MONDAY_10,
        policy: TimePolicy = TimePolicy()
    ): TimeCandidate {
        val candidate = one(text, anchor, policy)
        assertEquals("start for \"$text\"", expectedStart, candidate.at.toOffsetDateTime().toString())
        assertEquals("end for \"$text\"", expectedEnd, candidate.endAt?.toOffsetDateTime()?.toString())
        assertEquals("hasTime for \"$text\"", true, candidate.hasTime)
        assertEquals("precision for \"$text\"", TimePrecision.EXACT, candidate.precision)
        assertNull("no first day for \"$text\"", candidate.startAt)
        return candidate
    }

    /**
     * A run of days from [expectedFirst] to [expectedLast] ("yyyy-MM-dd"), most likely meaning
     * [expectedLikely] (by default its first day), all at the date-only hour.
     */
    fun days(
        expectedFirst: String,
        expectedLast: String,
        text: String,
        expectedLikely: String = expectedFirst,
        anchor: ZonedDateTime = MONDAY_10,
        policy: TimePolicy = TimePolicy()
    ): TimeCandidate {
        val candidate = one(text, anchor, policy)
        assertEquals("first day for \"$text\"", expectedFirst, candidate.startAt?.toLocalDate()?.toString())
        assertEquals("likely day for \"$text\"", expectedLikely, candidate.at.toLocalDate().toString())
        assertEquals("last day for \"$text\"", expectedLast, candidate.endAt?.toLocalDate()?.toString())
        assertEquals("hour of the first day for \"$text\"", policy.dateOnlyHour, candidate.startAt?.hour)
        assertEquals("hour for \"$text\"", policy.dateOnlyHour, candidate.at.hour)
        assertEquals("hour of the last day for \"$text\"", policy.dateOnlyHour, candidate.endAt?.hour)
        assertEquals("hasTime for \"$text\"", false, candidate.hasTime)
        assertEquals("precision for \"$text\"", TimePrecision.DAYS_RANGE, candidate.precision)
        return candidate
    }

    fun confidence(text: String, anchor: ZonedDateTime = MONDAY_10, policy: TimePolicy = TimePolicy()): Float =
        one(text, anchor, policy).confidence
}
