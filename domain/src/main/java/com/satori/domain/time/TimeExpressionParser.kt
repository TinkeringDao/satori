package com.satori.domain.time

import java.time.ZonedDateTime

/**
 * Finds date and time expressions in English and German text ("tomorrow at 3", "nächsten
 * Freitag um halb 9") and resolves them against an anchor: the moment the words were spoken.
 * Both languages' rules run on every text, because people mix them.
 */
interface TimeExpressionParser {
    fun find(text: String, anchor: ZonedDateTime, policy: TimePolicy = TimePolicy()): List<TimeCandidate>
}

/**
 * One expression found in the text.
 *
 * @property phrase The matched words, as written
 * @property start Start index in the text
 * @property end End index in the text (exclusive)
 * @property at The resolved moment: for a range of clock times its start, for a run of days the
 *   day it most likely means (see [startAt])
 * @property hasTime False when no clock time was said ([precision] DAY, PART_OF_DAY or
 *   DAYS_RANGE). [at] then holds [TimePolicy.dateOnlyHour], except for PART_OF_DAY, where it
 *   holds the part of the day's usual hour ("Freitagabend": Friday 19:00)
 * @property confidence 0..1; ambiguous readings ("nächsten Freitag") score lower
 * @property endAt The end of a range, null for a single moment or day. A range of clock times
 *   ("von 10 bis 12 Uhr", precision EXACT) ends at its end time. A run of days ("nächste Woche",
 *   precision DAYS_RANGE) ends on its last day, at the same hour as [at].
 * @property precision How exactly the words name the moment; by default EXACT with a time and
 *   DAY without one
 * @property startAt The first day of a run of days (precision DAYS_RANGE), at the same hour as
 *   [at]; null otherwise. [at] is then the run's likely day: its first day, but the middle one
 *   for "Mitte" ("Mitte des Monats": the 15th) and the last one for "Ende" ("Ende der Woche":
 *   Friday, "bis Ende des Monats": the last day of the month).
 */
data class TimeCandidate(
    val phrase: String,
    val start: Int,
    val end: Int,
    val at: ZonedDateTime,
    val hasTime: Boolean,
    val confidence: Float,
    val endAt: ZonedDateTime? = null,
    val precision: TimePrecision = if (hasTime) TimePrecision.EXACT else TimePrecision.DAY,
    val startAt: ZonedDateTime? = null
)

/** How exactly a [TimeCandidate] names its moment. */
enum class TimePrecision {
    /** A clock time ("um 9", "15:30", "in 2 Stunden") or a range of them ("von 10 bis 12 Uhr"). */
    EXACT,

    /** Only a part of the day ("Freitagabend", "tonight", "morgens"): no hour was said. */
    PART_OF_DAY,

    /** One day ("morgen", "am 16.10.", "next Friday"). */
    DAY,

    /**
     * A run of days ("nächste Woche", "am Wochenende", "Ende des Monats", "vom 7. bis 9.
     * Dezember"), from startAt to endAt; at is its likely day.
     */
    DAYS_RANGE
}

/**
 * How ambiguous expressions are read.
 *
 * @property preferFuture A bare weekday or hour means the next one, not the last one
 * @property nextWeekdayMeansFollowingWeek "next Friday" said on a Monday means the Friday of the
 *   following week (when false: the coming Friday)
 * @property wakingHours A bare hour without am/pm is read inside these hours (e.g. "um 3" = 15:00)
 * @property dateOnlyHour Hour used for a date without a time
 */
data class TimePolicy(
    val preferFuture: Boolean = true,
    val nextWeekdayMeansFollowingWeek: Boolean = false,
    val wakingHours: IntRange = 7..21,
    val dateOnlyHour: Int = 9
)
