package com.satori.domain.time

import java.time.DayOfWeek
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

internal enum class Meridiem { AM, PM }

/**
 * A clock time as said. [hour] is 0 to 24. Unless [fixed], an hour from 0 to 12 can be either
 * half of the day ("um 9", "halb 8") and the resolver picks one.
 */
internal data class Clock(
    val hour: Int,
    val minute: Int,
    val second: Int = 0,
    val meridiem: Meridiem? = null,
    val fixed: Boolean = false,
    val midnight: Boolean = false
)

internal enum class WeekdayModifier { NONE, THIS, COMING, NEXT, AFTER_NEXT, LAST }

internal enum class Position { WHOLE, START, MIDDLE, END }

/** A run of tokens, from [from] to [to] (exclusive), that says one thing about a moment. */
internal sealed class Piece(val from: Int, val to: Int, val factor: Float)

/** "heute", "tomorrow", "übermorgen", "tonight" (today, evening), "last night". */
internal class RelativeDayPiece(
    from: Int, to: Int, val days: Int, val part: DayPart? = null, val isTomorrow: Boolean = false,
    factor: Float = 1f
) : Piece(from, to, factor)

/** "am Freitag", "next Friday", "Freitagabend", "Mittwoch nächster Woche" ([weeks] = 1). */
internal class WeekdayPiece(
    from: Int, to: Int, val day: DayOfWeek, val modifier: WeekdayModifier, val part: DayPart?,
    val weeks: Int?, factor: Float
) : Piece(from, to, factor)

/** "nächste Woche" ([weeks] = 1), "Ende der Woche", "the week after next". */
internal class WeekPiece(from: Int, to: Int, val weeks: Int, val position: Position) : Piece(from, to, 1f)

/** "am Wochenende" ([which] = 0), "next weekend" (1), "last weekend" (-1). */
internal class WeekendPiece(from: Int, to: Int, val which: Int) : Piece(from, to, 1f)

/** "nächsten Monat", "Ende des Monats". */
internal class MonthPiece(from: Int, to: Int, val months: Int, val position: Position) : Piece(from, to, 1f)

/**
 * "16.10.", "am 16. Oktober 2026", "the 16th" ([month] null: the next 16th), or with [firstDay]
 * the run of days that ends on that date ("vom 7. bis 9. Dezember": [firstDay] 7, [day] 9).
 */
internal class DatePiece(
    from: Int, to: Int, val day: Int, val month: Int?, val year: Int?, val twoDigitYear: Boolean,
    val firstDay: Int? = null
) : Piece(from, to, 1f)

/**
 * "in 2 hours" ([minutes] = 120) or "vor 3 Tagen" ([unit] DAY, [amount] -3). [extraDays] counts
 * the "from tomorrow" in "a week from tomorrow".
 */
internal class OffsetPiece(
    from: Int, to: Int, val minutes: Long?, val unit: OffsetUnit?, val amount: Long,
    val extraDays: Long, factor: Float
) : Piece(from, to, factor)

/**
 * A clock time, or with [until] a range of them ("von 10 bis 12 Uhr": [clock] 10, [until] 12).
 * [bare] for a range of two bare numbers, read as hours only because a day and "von", "from",
 * "zwischen", "between" or "ab" came right before it ("Freitag von 10 bis 12").
 */
internal class ClockPiece(
    from: Int, to: Int, val clock: Clock, factor: Float, val until: Clock? = null, val bare: Boolean = false
) : Piece(from, to, factor)

internal class DayPartPiece(from: Int, to: Int, val part: DayPart) : Piece(from, to, 1f)

/** Pieces that belong together, such as "am Freitag" and "um halb 9". */
internal class Expression {
    var from = -1
        private set
    var to = -1
        private set
    var relativeDay: RelativeDayPiece? = null
        private set
    var weekday: WeekdayPiece? = null
        private set
    var week: WeekPiece? = null
        private set
    var weekend: WeekendPiece? = null
        private set
    var month: MonthPiece? = null
        private set
    var date: DatePiece? = null
        private set
    var dayOffset: OffsetPiece? = null
        private set
    var timeOffset: OffsetPiece? = null
        private set
    var clock: ClockPiece? = null
        private set
    var part: DayPart? = null
        private set

    /** Token right after the last day piece, where "Abend" or "morning" may follow. */
    var dayEnd = -1
        private set

    /** Token right after the clock, where "abends" or "früh" may follow. */
    var clockEnd = -1
        private set

    val factors = ArrayList<Float>()

    private val isEmpty get() = from < 0

    /** Some day was said: a date, a weekday, "morgen", "next week". */
    val hasDay
        get() = relativeDay != null || weekday != null || week != null || weekend != null ||
            month != null || date != null || dayOffset != null

    /** A day exact enough to lend to a time said later in the same sentence; a span of dates is a run of days. */
    val hasExactDay: Boolean
        get() {
            val d = date
            return relativeDay != null || weekday != null || (d != null && d.firstDay == null) || dayOffset != null
        }

    fun accepts(p: Piece): Boolean {
        if (timeOffset != null) return false
        // "Am Freitag von 2 bis 4, morgen dann der Rest": a range of bare numbers takes no other day.
        if (clock?.bare == true && p !is DayPartPiece) return false
        return when (p) {
            is OffsetPiece ->
                if (p.minutes != null) {
                    isEmpty
                } else {
                    dayOffset == null && date == null && week == null && weekend == null &&
                        month == null && (p.amount > 0 || relativeDay == null)
                }
            is RelativeDayPiece ->
                relativeDay == null && date == null && dayOffset == null && week == null &&
                    weekend == null && month == null && (p.part == null || part == null)
            is WeekdayPiece ->
                weekday == null && weekend == null && month == null &&
                    (week == null || (p.modifier == WeekdayModifier.NONE && p.weeks == null)) &&
                    (p.part == null || part == null)
            is WeekPiece -> {
                val w = weekday
                week == null && relativeDay == null && date == null && dayOffset == null &&
                    weekend == null && month == null &&
                    (w == null || (w.modifier == WeekdayModifier.NONE && w.weeks == null))
            }
            is WeekendPiece -> !hasDay
            is MonthPiece -> !hasDay
            is DatePiece ->
                date == null && dayOffset == null && week == null && weekend == null && month == null
            is ClockPiece -> clock == null
            is DayPartPiece -> part == null
        }
    }

    fun add(p: Piece) {
        if (isEmpty) from = p.from
        to = p.to
        if (p.factor != 1f) factors += p.factor
        when (p) {
            is RelativeDayPiece -> {
                relativeDay = p
                dayEnd = p.to
                p.part?.let { part = it }
            }
            is WeekdayPiece -> {
                weekday = p
                dayEnd = p.to
                p.part?.let { part = it }
            }
            is WeekPiece -> {
                week = p
                dayEnd = p.to
            }
            is WeekendPiece -> {
                weekend = p
                dayEnd = p.to
            }
            is MonthPiece -> {
                month = p
                dayEnd = p.to
            }
            is DatePiece -> {
                date = p
                dayEnd = p.to
            }
            is OffsetPiece ->
                if (p.minutes != null) {
                    timeOffset = p
                } else {
                    dayOffset = p
                    dayEnd = p.to
                }
            is ClockPiece -> {
                clock = p
                clockEnd = p.to
            }
            is DayPartPiece -> part = p.part
        }
    }
}

/**
 * Finds the expressions in a list of tokens, left to right and without overlaps. Each token
 * position tries every kind of piece and keeps the longest; an expression grows while the next
 * piece (directly, or after a comma) fits with what it already has.
 */
internal class TimeGrammar(private val tokens: List<Token>) {

    private val parsers: List<(Int, Expression) -> Piece?> =
        listOf(
            ::dayPart, ::relativeDay, ::weekday, ::week, ::weekend, ::month, ::date, ::dateRange, ::offset, ::clock,
            ::clockRange
        )

    fun expressions(): List<Expression> {
        val found = ArrayList<Expression>()
        var i = 0
        while (i < tokens.size) {
            val e = expressionAt(i)
            if (e == null) {
                i++
            } else {
                found += e
                i = e.to
            }
        }
        return found
    }

    private fun expressionAt(i: Int): Expression? {
        val e = Expression()
        e.add(bestPiece(i, e) ?: return null)
        while (true) {
            val next = bestPiece(e.to, e) ?: (if (isJoiner(e.to)) bestPiece(e.to + 1, e) else null) ?: break
            e.add(next)
        }
        return e
    }

    private fun bestPiece(i: Int, e: Expression): Piece? {
        if (i >= tokens.size) return null
        var best: Piece? = null
        for (parse in parsers) {
            val piece = parse(i, e) ?: continue
            if (!e.accepts(piece)) continue
            if (best == null || piece.to > best.to) best = piece
        }
        return best
    }

    private fun isJoiner(i: Int) = isPunct(i, ",") || isWord(i, "of", "des")

    // --- Token helpers ---------------------------------------------------------------------

    private fun tok(i: Int): Token? = tokens.getOrNull(i)

    private fun word(i: Int): String? = tokens.getOrNull(i)?.takeIf { it.kind == TokenKind.WORD }?.folded

    private fun isWord(i: Int, vararg words: String): Boolean {
        val w = word(i) ?: return false
        return w in words
    }

    private fun isWordIn(i: Int, words: Collection<String>): Boolean {
        val w = word(i) ?: return false
        return w in words
    }

    private fun isPunct(i: Int, vararg marks: String): Boolean {
        val t = tok(i) ?: return false
        return t.kind == TokenKind.PUNCT && t.text in marks
    }

    /** Token [i] and the one after it are written together, as in "15:30" or "3pm". */
    private fun touches(i: Int): Boolean {
        val a = tok(i) ?: return false
        val b = tok(i + 1) ?: return false
        return a.end == b.start
    }

    private fun digits(i: Int, maxLength: Int): Int? {
        val t = tok(i) ?: return null
        if (t.kind != TokenKind.NUMBER || t.text.length > maxLength) return null
        return t.number
    }

    private fun sentenceStart(i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && tokens[k].kind == TokenKind.PUNCT && tokens[k].text in OPENERS) k--
        if (k < 0) return true
        return tokens[k].kind == TokenKind.PUNCT && tokens[k].text in SENTENCE_ENDS
    }

    /** The text or its sentence ends at [k]. */
    private fun endsSentence(k: Int): Boolean {
        val t = tok(k) ?: return true
        return t.kind == TokenKind.PUNCT && t.text in SENTENCE_ENDS
    }

    // --- Numbers -----------------------------------------------------------------------------

    /** A number in digits or words ("7", "seven", "twenty one", "einundzwanzig"). */
    private class Num(val value: Int, val end: Int, val digits: String?)

    private fun numberAt(i: Int): Num? {
        val t = tok(i) ?: return null
        if (t.kind == TokenKind.NUMBER) return t.number?.let { Num(it, i + 1, t.text) }
        if (t.kind != TokenKind.WORD) return null
        TimeLexicon.englishTens(t.folded)?.let { tens ->
            val unit = word(i + 1)?.let(TimeLexicon::englishUnit)
            if (unit != null) return Num(tens + unit, i + 2, null)
        }
        return TimeLexicon.cardinal(t.folded)?.let { Num(it, i + 1, null) }
    }

    /** "um 5 Prozent", "at one point": the number before [i] counts something. */
    private fun isAmount(i: Int): Boolean {
        val t = tok(i) ?: return false
        return when (t.kind) {
            TokenKind.WORD -> t.folded in TimeLexicon.AMOUNT_WORDS
            TokenKind.PUNCT -> t.text in TimeLexicon.AMOUNT_SIGNS
            TokenKind.NUMBER -> false
        }
    }

    // --- Relative days -------------------------------------------------------------------------

    private fun relativeDay(i: Int, e: Expression): Piece? {
        val w = word(i) ?: return null
        when (w.removeSuffix("'s")) {
            "today", "heute" -> return RelativeDayPiece(i, i + 1, 0)
            "tonight", "tonite" -> return RelativeDayPiece(i, i + 1, 0, DayPart.EVENING)
            "tomorrow", "tomorrows" -> return RelativeDayPiece(i, i + 1, 1, isTomorrow = true)
            "yesterday", "gestern" -> return RelativeDayPiece(i, i + 1, -1)
            "uebermorgen" -> return RelativeDayPiece(i, i + 1, 2)
            "vorgestern" -> return RelativeDayPiece(i, i + 1, -2)
            "morgen" -> return germanMorgen(i)
        }
        val j = if (w == "the") i + 1 else i
        if (isWord(j, "day") && isWord(j + 1, "after") && isWord(j + 2, "tomorrow")) {
            return RelativeDayPiece(i, j + 3, 2)
        }
        if (isWord(j, "day") && isWord(j + 1, "before") && isWord(j + 2, "yesterday")) {
            return RelativeDayPiece(i, j + 3, -2)
        }
        if (isWord(j, "next", "following")) {
            // "the next day", "the next morning": said in a story, so less likely a plan.
            if (isWord(j + 1, "day")) return RelativeDayPiece(i, j + 2, 1, factor = Confidence.NARRATIVE)
            TimeLexicon.PART_NOUNS_EN[word(j + 1)]?.let {
                return RelativeDayPiece(i, j + 2, 1, it, factor = Confidence.NARRATIVE)
            }
        }
        if (w == "this") TimeLexicon.PART_NOUNS_EN[word(i + 1)]?.let { return RelativeDayPiece(i, i + 2, 0, it) }
        if (w == "last" && isWord(i + 1, "night")) return RelativeDayPiece(i, i + 2, -1, DayPart.NIGHT)
        if (w == "am" && isWord(i + 1, "naechsten", "folgenden")) {
            if (isWord(i + 2, "tag")) return RelativeDayPiece(i, i + 3, 1, factor = Confidence.NARRATIVE)
            TimeLexicon.PART_NOUNS_DE[word(i + 2)]?.let {
                return RelativeDayPiece(i, i + 3, 1, it, factor = Confidence.NARRATIVE)
            }
        }
        return null
    }

    /**
     * "morgen" is tomorrow; "guten Morgen" is a greeting and "jeden Morgen" the noun. A capital
     * "Morgen" opening a sentence is still tomorrow ("Morgen gehe ich..."), just less certainly;
     * after a day ("heute Morgen") [dayPart] reads it as the morning first.
     */
    private fun germanMorgen(i: Int): Piece? {
        val previous = word(i - 1)
        if (previous != null && (previous in TimeLexicon.GREETINGS || previous in TimeLexicon.MORNING_NOUN_MARKERS)) {
            return null
        }
        // "Hallo, Morgen!" opens like "Morgen!" does.
        val afterGreeting = isPunct(i - 1, ",") && isWordIn(i - 2, GREETING_OPENERS) && sentenceStart(i - 2)
        val atStart = sentenceStart(i) || afterGreeting
        // "Morgen!" and "Morgen zusammen" open a conversation like "Guten Morgen". Missing a
        // "tomorrow" here costs less than giving a todo the wrong day.
        if (tokens[i].capitalized && atStart) {
            if (isPunct(i + 1, "!")) return null
            if (afterGreeting && endsSentence(i + 1)) return null
            val k = if (isPunct(i + 1, ",")) i + 2 else i + 1
            if (isWordIn(k, GREETING_FOLLOWERS)) return null
        }
        val factor = when {
            !tokens[i].capitalized -> 1f
            atStart -> Confidence.CAPITAL_MORGEN_AT_START
            else -> Confidence.CAPITAL_MORGEN
        }
        return RelativeDayPiece(i, i + 1, 1, isTomorrow = true, factor = factor)
    }

    // --- Weekdays ------------------------------------------------------------------------------

    private fun weekday(i: Int, e: Expression): Piece? {
        var j = i
        // "jeden Freitag", "every Friday": recurring, like "freitags".
        val every = isWordIn(j, EVERY_WORDS)
        if (every) j++
        if (!every && isWord(j, "on", "am")) j++
        if (isWord(j, "the") && isWord(j + 1, "next", "coming")) j++
        var modifier = WeekdayModifier.NONE
        val w = word(j)
        when {
            w == "this" -> {
                j++
                modifier = if (isWord(j, "coming")) {
                    j++
                    WeekdayModifier.COMING
                } else {
                    WeekdayModifier.THIS
                }
            }
            w in THIS_WORDS -> { j++; modifier = WeekdayModifier.THIS }
            w in NEXT_WORDS -> { j++; modifier = WeekdayModifier.NEXT }
            w in COMING_WORDS -> { j++; modifier = WeekdayModifier.COMING }
            w in AFTER_NEXT_WORDS -> { j++; modifier = WeekdayModifier.AFTER_NEXT }
            w in LAST_WORDS -> { j++; modifier = WeekdayModifier.LAST }
        }
        val name = word(j)?.removeSuffix("'s")?.let(TimeLexicon::weekday) ?: return null
        j++
        var weeks: Int? = null
        if (modifier == WeekdayModifier.NONE) {
            if (isWord(j, "after") && isWord(j + 1, "next")) {
                modifier = WeekdayModifier.AFTER_NEXT
                j += 2
            } else if (isWord(j, "nach") && isWord(j + 1, "dem") && isWord(j + 2, "naechsten")) {
                modifier = WeekdayModifier.AFTER_NEXT
                j += 3
            } else {
                // "Freitag nächste Woche", "Mittwoch der nächsten Woche", "wednesday of next week"
                var k = j
                if (isWord(k, "of", "der", "from")) k++
                val offset = WEEK_MODIFIERS[word(k)]
                if (offset != null && isWord(k + 1, "week", "woche")) {
                    weeks = offset
                    j = k + 2
                }
            }
        }
        if (every && modifier != WeekdayModifier.NONE) return null
        val factor = if (name.recurring || every) Confidence.RECURRING else 1f
        return WeekdayPiece(i, j, name.day, modifier, name.part, weeks, factor)
    }

    // --- Weeks, weekends, months ------------------------------------------------------------

    /** "Anfang", "end of", "mid": where in a week or month. Returns the position and the next token. */
    private fun positionAt(i: Int): Pair<Position, Int> {
        when (word(i)) {
            "anfang", "early" -> return Position.START to i + 1
            "mitte", "mid" -> return Position.MIDDLE to i + 1
            "ende" -> return Position.END to i + 1
        }
        val k = if (isWord(i, "the")) i + 1 else i
        val position = when (word(k)) {
            "end" -> Position.END
            "beginning", "start" -> Position.START
            "middle" -> Position.MIDDLE
            else -> return Position.WHOLE to i
        }
        return if (isWord(k + 1, "of")) position to k + 2 else Position.WHOLE to i
    }

    private fun week(i: Int, e: Expression): Piece? {
        val (position, start) = positionAt(i)
        var j = start
        if (position == Position.WHOLE && isWord(j, "in")) j++
        if (isWord(j, "der", "the", "die")) j++
        var weeks: Int? = WEEK_MODIFIERS[word(j)]
        if (weeks != null) j++
        if (!isWord(j, "week", "woche")) return null
        j++
        if (weeks == null) {
            weeks = when {
                isWord(j, "after") && isWord(j + 1, "next") -> {
                    j += 2
                    2
                }
                position != Position.WHOLE -> 0
                else -> return null
            }
        }
        return WeekPiece(i, j, weeks, position)
    }

    private fun weekend(i: Int, e: Expression): Piece? {
        var j = i
        if (isWord(j, "am", "on", "at", "over", "uebers", "during", "for")) j++
        if (isWord(j, "ueber") && isWord(j + 1, "das")) j += 2
        if (isWord(j, "the")) j++
        var which = 0
        when (word(j)) {
            "this", "dieses", "diesem", "coming", "kommendes", "kommenden" -> j++
            "next", "naechstes", "naechsten" -> { which = 1; j++ }
            "last", "letztes", "letzten", "vergangenes", "vergangenen", "past" -> { which = -1; j++ }
        }
        if (isWord(j, "coming")) j++
        if (!isWord(j, "weekend", "week-end", "wochenende", "wochenend")) return null
        return WeekendPiece(i, j + 1, which)
    }

    private fun month(i: Int, e: Expression): Piece? {
        if (isWord(i, "monatsende")) return MonthPiece(i, i + 1, 0, Position.END)
        if (isWord(i, "monatsanfang")) return MonthPiece(i, i + 1, 0, Position.START)
        val (position, start) = positionAt(i)
        var j = start
        if (isWord(j, "des", "the")) j++
        val months = MONTH_MODIFIERS[word(j)]
        if (months != null) j++
        if (!isWord(j, "month", "monat", "monats")) return null
        if (months == null && position == Position.WHOLE) return null
        return MonthPiece(i, j + 1, months ?: 0, position)
    }

    // --- Dates -----------------------------------------------------------------------------------

    private fun date(i: Int, e: Expression): Piece? {
        var j = i
        val datePrefix = isWordIn(j, DATE_PREFIXES)
        if (datePrefix) j++
        if (isWord(j, "the")) j++
        val afterWeekday = e.weekday != null && (e.to == i || (e.to == i - 1 && isPunct(i - 1, ",")))
        return numericDate(i, j, j > i || afterWeekday)
            ?: dayThenMonth(i, j, guarded = !(datePrefix || afterWeekday))
            ?: monthThenDay(i, j, datePrefix || afterWeekday)
            ?: dayOfMonthOnly(i, j, datePrefix || afterWeekday)
    }

    /** "16.10.", "16.10.2026", "18.07.14", "2026-10-16", and "16.10" after "am" or a weekday. */
    private fun numericDate(i: Int, j: Int, inDateContext: Boolean): Piece? {
        val first = tok(j) ?: return null
        if (first.kind != TokenKind.NUMBER) return null
        if (first.text.length == 4 && isPunct(j + 1, "-") && touches(j) && touches(j + 1)) {
            val month = digits(j + 2, 2) ?: return null
            if (!(isPunct(j + 3, "-") && touches(j + 2) && touches(j + 3))) return null
            val day = digits(j + 4, 2) ?: return null
            return datePiece(i, j + 5, day, month, first.number, false)
        }
        if (first.text.length > 2) return null
        if (!(isPunct(j + 1, ".") && touches(j) && touches(j + 1))) return null
        val month = digits(j + 2, 2) ?: return null
        val day = first.number ?: return null
        val end = j + 3
        if (isPunct(end, ".") && touches(end - 1)) {
            val year = tok(end + 1)
            if (year != null && year.kind == TokenKind.NUMBER &&
                (year.text.length == 4 || (year.text.length == 2 && touches(end)))
            ) {
                return datePiece(i, end + 2, day, month, year.number, year.text.length == 2)
            }
            return datePiece(i, end + 1, day, month, null, false)
        }
        // "16.10" without the last dot is a time after "um" and a date after "am".
        if (!inDateContext || isWord(end, "uhr", "h")) return null
        return datePiece(i, end, day, month, null, false)
    }

    /**
     * "16 October", "16. Oktober", "the 16th of October". With [guarded], a month that may be a
     * name or a verb ([mayNotBeMonth]) needs a day with its dot or ordinal ending before it: "5.
     * Mai", but not the "5 may" of "between 3 and 5 may work" or "the meeting from 2 to 4 may be
     * moved". English writes no dot after a day, so after an English word or before an English
     * month the dot ends a sentence: "I work from 9 to 5. May I ask ...", "... to 5. November is
     * busy".
     */
    private fun dayThenMonth(i: Int, j: Int, guarded: Boolean = false): DatePiece? {
        val (day, afterDay) = dayNumber(j, requireMarker = false) ?: return null
        var k = afterDay
        if (isWord(k, "of")) k++
        val name = word(k) ?: return null
        val month = TimeLexicon.month(name) ?: return null
        val dotted = afterDay == j + 2 && isPunct(j + 1, ".")
        if (dotted && (isWordIn(j - 1, ENGLISH_DAY_LEADS) || name in TimeLexicon.ENGLISH_ONLY_MONTHS)) return null
        val markedDay = afterDay > j + 1 || tokens[j].kind == TokenKind.WORD
        if (guarded && !markedDay && mayNotBeMonth(k)) return null
        k = afterMonth(k)
        val (year, twoDigits, end) = yearAt(k)
        return datePiece(i, end, day, month, year, twoDigits)
    }

    /**
     * "von 7 bis 9. Dezember", "vom 7. bis zum 9. Dezember", "zwischen dem 3. und dem 5.
     * November", "from 3 to 5 November", "3.-5. Nov.": the days from the first number to the
     * date. Without "von", "vom", "from", "ab" or "between" the first number needs its dot or
     * ordinal ending, or a dash written onto both numbers ("3-5 November"). Right after "heute"
     * or "morgen" a first number without them is an hour ("heute von 9 bis 17. März ist ...":
     * [clockRange]).
     */
    private fun dateRange(i: Int, e: Expression): Piece? {
        val opener = word(i)
        val between = opener == "zwischen" || opener == "between"
        val from = opener in DATE_RANGE_OPENERS
        var j = if (between || from) i + 1 else i
        if (isWord(j, "the", "dem")) j++
        val (first, afterFirst) = dayNumber(j, requireMarker = false) ?: return null
        var k = afterFirst
        val dash = isPunct(k, "-", "–", "—")
        val markedFirst = afterFirst > j + 1 || tokens[j].kind == TokenKind.WORD
        if (!between && !from && !markedFirst && !(dash && touches(k - 1) && touches(k))) return null
        if (!markedFirst && e.relativeDay != null) return null
        when {
            between && isWord(k, "und", "and") -> k++
            dash || isWord(k, "bis") || isWordIn(k, RANGE_TO) -> k++
            else -> return null
        }
        if (isWord(k, "zum", "the", "dem")) k++
        val last = dayThenMonth(i, k, guarded = true) ?: return null
        return DatePiece(i, last.to, last.day, last.month, last.year, last.twoDigitYear, firstDay = first)
    }

    /**
     * "October 16", "March 3rd", "Jan. 14". A number after "Uhr" or a count ("Jan 14 Uhr",
     * "march 10 km") is no day. A month that may be a name or a verb ([mayNotBeMonth]) needs a
     * date around it: an ordinal day, a year, "on"/"am"/"bis" or a weekday before it, or the dot
     * of "Jan.".
     */
    private fun monthThenDay(i: Int, j: Int, inDateContext: Boolean): Piece? {
        val name = word(j) ?: return null
        val month = TimeLexicon.month(name) ?: return null
        val monthEnd = afterMonth(j)
        var k = monthEnd
        if (isWord(k, "the")) k++
        val (day, afterDay) = dayNumber(k, requireMarker = false) ?: return null
        if (isPunct(afterDay, ":")) return null
        if (isWord(afterDay, "uhr", "h") || isWordIn(afterDay, OCLOCK) || meridiemAt(afterDay, null) != null) return null
        if (isAmount(afterDay) && !isWord(afterDay, "of")) return null
        val (year, twoDigits, end) = yearAt(afterDay)
        if (mayNotBeMonth(j)) {
            val ordinal = tokens[k].kind == TokenKind.WORD ||
                (afterDay == k + 2 && isWordIn(k + 1, TimeLexicon.ORDINAL_SUFFIXES))
            val dated = ordinal || year != null || inDateContext || isWordIn(j - 1, DATE_CUES) || monthEnd == j + 2
            if (!dated) return null
        }
        return datePiece(i, end, day, month, year, twoDigits)
    }

    /**
     * The month word at [k] is as often a name or a verb: "Jan", "Mai", and "march" or "may" in
     * lower case. "March" and "May" with a capital are months, at the start of a sentence too.
     */
    private fun mayNotBeMonth(k: Int): Boolean {
        val name = word(k) ?: return false
        if (name !in TimeLexicon.NAME_OR_VERB_MONTHS) return false
        return !(name in TimeLexicon.VERB_MONTHS && tokens[k].capitalized)
    }

    /**
     * "on the 15th", "am 16.", "am Sechzehnten", "Tuesday 19th": the next such day of a month.
     * Needs "on", "am" or a weekday before it ([inDateContext]); "the first time" is no date.
     */
    private fun dayOfMonthOnly(i: Int, j: Int, inDateContext: Boolean): Piece? {
        if (!inDateContext) return null
        val (day, end) = dayNumber(j, requireMarker = true) ?: return null
        val next = tok(end)
        if (next != null && next.kind == TokenKind.NUMBER) return null
        if (next != null && next.kind == TokenKind.WORD) {
            // "am 3. Tag", "on the first try": the ordinal counts something else.
            if (next.capitalized) return null
            if (tokens[j].kind == TokenKind.WORD && next.folded !in DAY_ONLY_FOLLOWERS) return null
        }
        return DatePiece(i, end, day, null, null, false)
    }

    /** The token after a month name, skipping the dot of "Okt.". */
    private fun afterMonth(k: Int): Int =
        if (isPunct(k + 1, ".") && touches(k) && isWordIn(k, ABBREVIATED_MONTHS)) k + 2 else k + 1

    /** A day of the month: "16", "16.", "16th", "15ten", "sixteenth", "ersten", "twenty first". */
    private fun dayNumber(j: Int, requireMarker: Boolean): Pair<Int, Int>? {
        val t = tok(j) ?: return null
        if (t.kind == TokenKind.NUMBER) {
            if (t.text.length > 2) return null
            val day = t.number?.takeIf { it in 1..31 } ?: return null
            if (touches(j) && isPunct(j + 1, ".")) return day to j + 2
            if (touches(j) && isWordIn(j + 1, TimeLexicon.ORDINAL_SUFFIXES)) return day to j + 2
            return if (requireMarker) null else day to j + 1
        }
        if (t.kind != TokenKind.WORD) return null
        TimeLexicon.englishTens(t.folded)?.let { tens ->
            word(j + 1)?.let(TimeLexicon::englishUnitOrdinal)?.let { return tens + it to j + 2 }
        }
        return TimeLexicon.ordinal(t.folded)?.takeIf { it in 1..31 }?.let { it to j + 1 }
    }

    /** A year after a date: "2026", ", 2026", or two digits at the end ("18. Juli 14"). */
    private fun yearAt(k: Int): Triple<Int?, Boolean, Int> {
        var m = k
        if (isPunct(m, ",") && tok(m + 1)?.let { it.kind == TokenKind.NUMBER && it.text.length == 4 } == true) m++
        val t = tok(m)
        if (t != null && t.kind == TokenKind.NUMBER) {
            val value = t.number ?: return Triple(null, false, k)
            if (t.text.length == 4 && value in 1900..2199 && !isPunct(m + 1, ":")) return Triple(value, false, m + 1)
            if (t.text.length == 2 && m == k) {
                val next = tok(m + 1)
                if (next == null || (next.kind == TokenKind.PUNCT && next.text != ":" && next.text != ".")) {
                    return Triple(value, true, m + 1)
                }
            }
        }
        return Triple(null, false, k)
    }

    private fun datePiece(from: Int, to: Int, day: Int, month: Int?, year: Int?, twoDigitYear: Boolean): DatePiece? {
        if (day !in 1..31 || (month != null && month !in 1..12)) return null
        return DatePiece(from, to, day, month, year, twoDigitYear)
    }

    // --- Offsets: "in 2 Stunden", "vor 3 Tagen", "a week ago" ----------------------------------

    private class Amount(val value: Double, val end: Int, val vague: Boolean = false)

    private fun offset(i: Int, e: Expression): Piece? {
        var j = i
        var sign = 1
        var needsTail = false
        when (word(i)) {
            "in" -> j++
            "within", "binnen" -> j++
            "innerhalb" -> {
                j++
                if (isWord(j, "von")) j++
            }
            "vor" -> {
                j++
                sign = -1
            }
            else -> needsTail = true
        }
        var approximate = false
        while (isWordIn(j, OFFSET_APPROX)) {
            approximate = true
            j++
            if (isPunct(j, ".") && word(j - 1) == "ca") j++
        }
        val amount = amountAt(j) ?: return null
        // "in 999999999 Jahren" would run past the calendar.
        if (amount.value > MAX_OFFSET) return null
        var k = amount.end
        if (isWord(k, "more", "weitere", "weiteren")) k++
        var value = amount.value
        val unit = when (word(k)) {
            "viertelstunde", "viertelstunden" -> {
                value *= 15
                OffsetUnit.MINUTE
            }
            "dreiviertelstunde" -> {
                value *= 45
                OffsetUnit.MINUTE
            }
            else -> word(k)?.let(TimeLexicon::unit) ?: return null
        }
        k++
        if (isWord(k, "and") && isWord(k + 1, "a", "an") && isWord(k + 2, "half")) {
            value += 0.5
            k += 3
        }
        var extraDays = 0L
        if (needsTail) {
            when {
                isWord(k, "ago") -> {
                    sign = -1
                    k++
                }
                isWord(k, "hence") -> k++
                isWord(k, "from") && isWord(k + 1, "now", "today") -> k += 2
                isWord(k, "from") && isWord(k + 1, "right") && isWord(k + 2, "now") -> k += 3
                isWord(k, "from") && isWord(k + 1, "tomorrow") -> {
                    extraDays = 1
                    k += 2
                }
                else -> return null
            }
        }
        var factor = if (amount.vague) Confidence.VAGUE_COUNT else 1f
        if (approximate) factor *= Confidence.APPROXIMATE
        if (unit == OffsetUnit.MINUTE || unit == OffsetUnit.HOUR) {
            if (extraDays != 0L) return null
            val minutes = value * (if (unit == OffsetUnit.HOUR) 60 else 1)
            if (minutes <= 0 || abs(minutes - minutes.roundToLong()) > 1e-6) return null
            return OffsetPiece(i, k, sign * minutes.roundToLong(), null, 0, 0, factor)
        }
        if (value <= 0 || value != floor(value)) return null
        val whole = value.toLong()
        return if (unit == OffsetUnit.FORTNIGHT) {
            OffsetPiece(i, k, null, OffsetUnit.WEEK, sign * whole * 2, extraDays, factor)
        } else {
            OffsetPiece(i, k, null, unit, sign * whole, extraDays, factor)
        }
    }

    private fun amountAt(j: Int): Amount? {
        // Spoken "in 'ner Stunde", "in 'ne Woche".
        if (isPunct(j, "'", "’") && touches(j) && isWord(j + 1, "ner", "ne", "nen", "nem")) {
            return if (isWord(j + 2, "halben", "halbe")) Amount(0.5, j + 3) else Amount(1.0, j + 2)
        }
        when (val w = word(j)) {
            "a", "an" -> return when {
                isWord(j + 1, "couple") -> Amount(2.0, if (isWord(j + 2, "of")) j + 3 else j + 2, vague = true)
                isWord(j + 1, "pair") && isWord(j + 2, "of") -> Amount(2.0, j + 3, vague = true)
                isWord(j + 1, "few") -> Amount(3.0, j + 2, vague = true)
                isWord(j + 1, "quarter") && isWord(j + 2, "of") && isWord(j + 3, "an") -> Amount(0.25, j + 4)
                isWord(j + 1, "quarter") -> Amount(0.25, j + 2)
                isWord(j + 1, "half") -> Amount(0.5, j + 2)
                else -> Amount(1.0, j + 1)
            }
            "couple" -> return Amount(2.0, if (isWord(j + 1, "of")) j + 2 else j + 1, vague = true)
            "few" -> return Amount(3.0, j + 1, vague = true)
            "several", "einige", "einigen", "mehrere", "mehreren" -> return Amount(3.0, j + 1, vague = true)
            "paar" -> return Amount(2.0, j + 1, vague = true)
            "half" -> return if (isWord(j + 1, "an")) Amount(0.5, j + 2) else null
            "quarter" -> return if (isWord(j + 1, "of") && isWord(j + 2, "an")) Amount(0.25, j + 3) else null
            "three-quarters" -> return if (isWord(j + 1, "of") && isWord(j + 2, "an")) Amount(0.75, j + 3) else null
            "three" -> if (isWord(j + 1, "quarters") && isWord(j + 2, "of") && isWord(j + 3, "an")) {
                return Amount(0.75, j + 4)
            }
            "anderthalb", "eineinhalb" -> return Amount(1.5, j + 1)
            "ein", "eine", "einer", "einem", "einen", "eins", "one" -> {
                if (w == "ein" && isWord(j + 1, "paar")) return Amount(2.0, j + 2, vague = true)
                if (isWord(j + 1, "halben", "halbe")) return Amount(0.5, j + 2)
                return withHalf(Amount(1.0, j + 1))
            }
        }
        val w = word(j)
        if (w != null && w.endsWith("einhalb")) {
            TimeLexicon.cardinal(w.removeSuffix("einhalb"))?.let { return Amount(it + 0.5, j + 1) }
        }
        val t = tok(j) ?: return null
        if (t.kind == TokenKind.NUMBER) {
            val whole = t.number ?: return null
            val fraction = tok(j + 2)
            if ((isPunct(j + 1, ",") || isPunct(j + 1, ".")) && touches(j) && touches(j + 1) &&
                fraction != null && fraction.kind == TokenKind.NUMBER
            ) {
                return Amount("$whole.${fraction.text}".toDouble(), j + 3)
            }
            return withHalf(Amount(whole.toDouble(), j + 1))
        }
        val n = numberAt(j) ?: return null
        return withHalf(Amount(n.value.toDouble(), n.end))
    }

    /** "zwei ein halb", "2 and a half". */
    private fun withHalf(a: Amount): Amount {
        val k = a.end
        return when {
            isWord(k, "ein") && isWord(k + 1, "halb") -> Amount(a.value + 0.5, k + 2)
            isWord(k, "einhalb") -> Amount(a.value + 0.5, k + 1)
            isWord(k, "and") && isWord(k + 1, "a", "an") && isWord(k + 2, "half") -> Amount(a.value + 0.5, k + 3)
            else -> a
        }
    }

    // --- Parts of the day ------------------------------------------------------------------------

    private fun dayPart(i: Int, e: Expression): Piece? {
        val w = word(i) ?: return null
        TimeLexicon.PART_ADVERBS[w]?.let { return DayPartPiece(i, i + 1, it) }
        when (w) {
            "am" -> {
                var k = i + 1
                if (isWord(k, "fruehen", "spaeten")) k++
                val noun = word(k)
                if (noun != "frueh") TimeLexicon.PART_NOUNS_DE[noun]?.let { return DayPartPiece(i, k + 1, it) }
            }
            "in" -> {
                if (isWord(i + 1, "der") && isWord(i + 2, "frueh")) return DayPartPiece(i, i + 3, DayPart.MORNING)
                if (isWord(i + 1, "der") && isWord(i + 2, "nacht")) return DayPartPiece(i, i + 3, DayPart.NIGHT)
                if (isWord(i + 1, "the")) {
                    var k = i + 2
                    if (isWord(k, "early", "late")) k++
                    TimeLexicon.PART_NOUNS_EN[word(k)]?.let { return DayPartPiece(i, k + 1, it) }
                }
            }
            "at" -> {
                if (isWord(i + 1, "night")) return DayPartPiece(i, i + 2, DayPart.NIGHT)
                if (isWord(i + 1, "lunchtime", "lunch")) return DayPartPiece(i, i + 2, DayPart.NOON)
            }
            "ueber" -> if (isWord(i + 1, "mittag")) return DayPartPiece(i, i + 2, DayPart.NOON)
            "zu" -> if (isWord(i + 1, "mittag") && e.dayEnd == i) return DayPartPiece(i, i + 2, DayPart.NOON)
        }
        // A noun right after a day ("heute Abend", "Friday morning", "morgen früh") or after a
        // clock ("um 8 Uhr früh", "2 Uhr nachmittag"). After a clock "morgen" is tomorrow.
        if (e.dayEnd == i) {
            (TimeLexicon.PART_NOUNS_DE[w] ?: TimeLexicon.PART_NOUNS_EN[w])?.let { return DayPartPiece(i, i + 1, it) }
        }
        if (e.clockEnd == i && w != "morgen") {
            TimeLexicon.PART_NOUNS_DE[w]?.let { return DayPartPiece(i, i + 1, it) }
        }
        return null
    }

    /** A part of the day starts at [k]: lets a bare number before it be an hour ("8 tonight"). */
    private fun startsDayPart(k: Int): Boolean {
        val w = word(k) ?: return false
        return when {
            w == "tonight" || w == "tonite" || w in TimeLexicon.PART_ADVERBS -> true
            w == "this" -> word(k + 1) in TimeLexicon.PART_NOUNS_EN
            w == "in" && isWord(k + 1, "the") ->
                word(k + 2) in TimeLexicon.PART_NOUNS_EN ||
                    (isWord(k + 2, "early", "late") && word(k + 3) in TimeLexicon.PART_NOUNS_EN)
            w == "in" && isWord(k + 1, "der") -> isWord(k + 2, "frueh", "nacht")
            w == "at" -> isWord(k + 1, "night")
            w == "am" || w == "heute" -> word(k + 1) in TimeLexicon.PART_NOUNS_DE
            else -> false
        }
    }

    // --- Clock times -----------------------------------------------------------------------------

    /** The clock as read so far, before trailing words like "Uhr" or "pm". */
    private class Core(
        val hour: Int,
        val minute: Int,
        val end: Int,
        val second: Int = 0,
        val fixed: Boolean = false,
        val meridiem: Meridiem? = null,
        val marked: Boolean = true,
        val sawUhr: Boolean = false,
        val midnight: Boolean = false,
        val regional: Boolean = false
    )

    /** An hour said as a number, a word, "noon" or "midnight", with "am"/"pm" if given. */
    private class Hour(val value: Int, val end: Int, val fixed: Boolean, val meridiem: Meridiem?)

    /**
     * The words before a clock time: [end] is the token after them, [language] "um" or "at"
     * when one of those was said, [cue] whether any word makes the number an hour, and
     * [approximate] whether one was "gegen", "around" or the like.
     */
    private class Cue(val end: Int, val language: String?, val cue: Boolean, val approximate: Boolean)

    /** The words from [i] that make a number after them an hour: "um", "at", "@", "gegen", "so um", "ca.". */
    private fun cueAt(i: Int): Cue {
        var j = i
        var language: String? = null
        var cue = false
        var approximate = false
        while (j - i < 4) {
            val w = word(j)
            when {
                w == "um" || w == "at" -> {
                    language = language ?: w
                    cue = true
                    j++
                }
                isPunct(j, "@") -> {
                    language = language ?: "at"
                    cue = true
                    j++
                }
                w != null && w in CLOCK_APPROX -> {
                    approximate = true
                    cue = true
                    j++
                    if (w == "ca" && isPunct(j, ".")) j++
                }
                w == "so" && isWord(j + 1, "um", "gegen") -> {
                    approximate = true
                    j++
                }
                else -> break
            }
        }
        return Cue(j, language, cue, approximate)
    }

    private fun clock(i: Int, e: Expression): Piece? {
        val cue = cueAt(i)
        val body = clockBody(i, cue.end, cue.language, cue.cue, bareHour = false, approximate = cue.approximate)
            ?: return null
        // "um 5 bis 10 Prozent": a bare hour counts like the end of the range it starts.
        if (!body.marked && amountRangeFollows(body.end)) return null
        return ClockPiece(i, body.end, body.clock, body.factor)
    }

    /**
     * "bis 10 Prozent", "- 10 %": a range joiner at [k] and a number that counts something. "und"
     * and "and" join no range after "um" or "at" (that takes "zwischen" or "between"): "at 8 and
     * 10 people are coming" is 8 o'clock.
     */
    private fun amountRangeFollows(k: Int): Boolean {
        if (!(isWord(k, "bis") || isWordIn(k, RANGE_TO) || isPunct(k, "-", "–", "—"))) return false
        val n = numberAt(k + 1) ?: return false
        return isAmount(n.end)
    }

    /**
     * The word before [j] is one that the number at [j] counts or labels: "Kapitel 3", "chapters
     * 3 to 5", "€ 5". Before a number "mal", "time" and "of" are other words ("komm mal 9 bis 10
     * Uhr vorbei", "this time 7 to 9 pm").
     */
    private fun countedBefore(j: Int): Boolean = isAmount(j - 1) && !isWordIn(j - 1, NOT_COUNTED_BEFORE)

    /** The number at [j] ends a range of numbers that counts or labels something: the 5 of "chapters 3 to 5". */
    private fun endsCountedRange(j: Int): Boolean {
        if (!(isWord(j - 1, "bis") || isWordIn(j - 1, RANGE_TO) || isPunct(j - 1, "-", "–", "—"))) return false
        return numberAt(j - 2)?.end == j - 1 && countedBefore(j - 2)
    }

    /** A clock read from [j] on with what follows it ("Uhr", "pm", "sharp", "rum"), and its factor. */
    private class ClockBody(val clock: Clock, val end: Int, val marked: Boolean, val factor: Float)

    /**
     * The clock at [j]. [cue] says an "um", "at" or "gegen" came before it, [bareHour] that a
     * bare number is an hour here (the ends of "von 10 bis 12"); [start] is where the piece
     * began, for "from 5 to 8".
     */
    private fun clockBody(
        start: Int,
        j: Int,
        language: String?,
        cue: Boolean,
        bareHour: Boolean,
        approximate: Boolean = false
    ): ClockBody? {
        val core = noonOrMidnight(j, cue)
            ?: englishSpoken(start, j, language, cue)
            ?: germanSpoken(j, language, cue)
            ?: numericClock(j, language, cue || bareHour)
            ?: hourWithMarker(j, language, cue || bareHour)
            ?: return null
        var k = core.end
        var meridiem = core.meridiem
        var marked = core.marked
        var sawUhr = core.sawUhr
        var roughly = approximate
        while (true) {
            if (!sawUhr && isWord(k, "uhr")) {
                k++
                sawUhr = true
                marked = true
                continue
            }
            if (isWordIn(k, OCLOCK)) {
                k++
                marked = true
                continue
            }
            if (isWord(k, "o") && isWord(k + 1, "clock")) {
                k += 2
                marked = true
                continue
            }
            if (meridiem == null) {
                val m = meridiemAt(k, language, sawUhr)
                if (m != null) {
                    meridiem = m.first
                    k = m.second
                    marked = true
                    continue
                }
            }
            if (isWord(k, "sharp")) {
                k++
                continue
            }
            if (isWord(k, "rum", "herum") || (isWord(k, "ish") && touches(k - 1))) {
                roughly = true
                k++
                continue
            }
            if (isWord(k, "in") && isWord(k + 1, "etwa")) {
                roughly = true
                k += 2
                continue
            }
            break
        }
        if (!marked && isAmount(k)) return null
        if (core.hour == 24 && core.minute != 0) return null
        var factor = 1f
        if (roughly) factor *= Confidence.APPROXIMATE
        if (core.regional) factor *= Confidence.REGIONAL
        val clock = Clock(core.hour, core.minute, core.second, meridiem, core.fixed, core.midnight)
        return ClockBody(clock, k, marked, factor)
    }

    /**
     * "von 10 bis 12 Uhr", "zwischen 9 und 11 Uhr", "from 9 am to 5 pm", "9-11 Uhr", "um 9 bis 10
     * Uhr", "gegen 9 bis 10 Uhr": both clock times in one piece; the resolver reads the two ends
     * together. Two bare numbers alone count anything ("Skala von 1 bis 5", "between 5 and 10
     * volunteers"), so one end has to show it is a clock ("Uhr", "pm", "9:30", "halb 9",
     * "Mittag"), or a cue ("um", "at", "gegen") or a part of the day ("abends von 7 bis 9", "from 7
     * to 9 tonight") has to come with them. Else they are hours only right after a day and "von",
     * "from", "zwischen", "between" or "ab" ("Freitag von 10 bis 12", "morgen von 10 bis 12"; not
     * "Am Montag 2 bis 3 Folien"); such a [ClockPiece.bare] range scores as a guess and takes no
     * day after it. Numbers after a word they count ("Kapitel 3 bis 5 heute Abend") are none. An
     * end that reads as a date with the month after it ("von 7 bis 9. Dezember", [dayThenMonth])
     * is a day of the month, which [dateRange] reads, except right after "heute" or "morgen".
     */
    private fun clockRange(i: Int, e: Expression): Piece? {
        val opener = word(i)
        val between = opener == "zwischen" || opener == "between"
        val from = opener == "von" || opener == "from" || opener == "ab"
        val cue = if (between || from) null else cueAt(i).takeIf { it.cue }
        val j = when {
            between || from -> i + 1
            cue != null -> cue.end
            else -> i
        }
        if (j == i && countedBefore(i)) return null
        val language = when (opener) {
            "from", "between" -> "at"
            "von", "zwischen", "ab" -> "um"
            else -> cue?.language
        }
        val start = clockBody(j, j, language, cue = cue != null, bareHour = true, approximate = cue?.approximate == true)
            ?: return null
        var k = start.end
        val connector = word(k)
        val spokenTo = connector in RANGE_TO
        when {
            between && (connector == "und" || connector == "and") -> k++
            connector == "bis" || spokenTo || isPunct(k, "-", "–", "—") -> k++
            else -> return null
        }
        if (connector == "bis" && isWord(k, "um")) k++
        val end = clockBody(k, k, language, cue = false, bareHour = true) ?: return null
        if (!end.marked && e.relativeDay == null && dayThenMonth(k, k, guarded = true) != null) return null
        // "9 to 11 am" is a range; "ten to eleven" is 10:50 and left to [clock].
        if (!between && !from && spokenTo && tokens[j].kind != TokenKind.NUMBER) return null
        val marked = start.marked || end.marked || cue != null || e.part != null || startsDayPart(end.end)
        if (!marked && (!e.hasDay || !(between || from))) return null
        val factor = start.factor * end.factor * (if (marked) 1f else Confidence.BARE_RANGE)
        return ClockPiece(i, end.end, start.clock, factor, until = end.clock, bare = !marked)
    }

    /** "noon", "midnight", and "Mittag" after "gegen", "um", "bis", "ab" or "vor" ("zu Mittag essen" is lunch). */
    private fun noonOrMidnight(j: Int, cue: Boolean): Core? = when (word(j)) {
        "noon", "midday" -> Core(12, 0, j + 1, fixed = true)
        "midnight", "mitternacht" -> Core(0, 0, j + 1, fixed = true, midnight = true)
        "mittag" -> if (cue || isWord(j - 1, "bis", "ab", "vor")) Core(12, 0, j + 1, fixed = true) else null
        else -> null
    }

    private fun hourAt(i: Int, language: String?): Hour? {
        when (word(i)) {
            "noon", "midday" -> return Hour(12, i + 1, true, null)
            "midnight", "mitternacht" -> return Hour(0, i + 1, true, null)
        }
        val n = numberAt(i) ?: return null
        if (n.value !in 0..24) return null
        if (n.digits != null && n.digits.length > 2) return null
        val meridiem = meridiemAt(n.end, language)
        return Hour(n.value, meridiem?.second ?: n.end, isFixed(n), meridiem?.first)
    }

    /** 13 to 24, 0, or written with a leading zero ("08:00"): no am/pm question. */
    private fun isFixed(n: Num): Boolean =
        n.value == 0 || n.value >= 13 || (n.digits != null && n.digits.length == 2 && n.digits[0] == '0')

    /**
     * [minutes] after ([before] false) or before the hour at [i]: "quarter past 3",
     * "halb 8" (30 before 8), "zehn vor acht".
     */
    private fun relativeTo(i: Int, minutes: Int, before: Boolean, language: String?, regional: Boolean = false): Core? {
        val h = hourAt(i, language) ?: return null
        var hour = h.value
        var minute = minutes
        if (before) {
            hour -= 1
            minute = 60 - minutes
        }
        if (minute == 60) {
            hour += 1
            minute = 0
        }
        var fixed = h.fixed
        if (hour < 0) {
            hour = 23
            fixed = true
        }
        if (hour >= 24) return null
        return Core(hour, minute, h.end, fixed = fixed || hour >= 13, meridiem = h.meridiem, regional = regional)
    }

    private fun englishSpoken(start: Int, j: Int, language: String?, cue: Boolean): Core? {
        var k = j
        if (isWord(k, "a") && isWord(k + 1, "quarter")) k++
        when (word(k)) {
            "half" -> {
                if (isWord(k + 1, "past")) return relativeTo(k + 2, 30, false, language)
                // British "half three" is 3:30 (the German "halb drei" is 2:30).
                if (cue) return relativeTo(k + 1, 30, false, language, regional = true)
                return null
            }
            "quarter" -> return when {
                isWord(k + 1, "past", "after") -> relativeTo(k + 2, 15, false, language)
                isWord(k + 1, "to", "before", "til", "till") -> relativeTo(k + 2, 15, true, language)
                else -> null
            }
        }
        val n = numberAt(k) ?: return null
        if (n.value !in 1..30) return null
        var m = n.end
        val minutesSaid = isWord(m, "minute", "minutes", "min", "mins")
        if (minutesSaid) m++
        val before = when (word(m)) {
            "past", "after" -> false
            "to", "before", "til", "till" -> true
            else -> return null
        }
        // "from 5 to 8" is a range of hours.
        if (isWord(start - 1, "from", "between")) return null
        val round = n.digits == null && n.value in ROUND_MINUTES
        if (!cue && !minutesSaid && !round) return null
        return relativeTo(m + 1, n.value, before, language)
    }

    private fun germanSpoken(j: Int, language: String?, cue: Boolean): Core? {
        when (word(j)) {
            "halb" -> return relativeTo(j + 1, 30, true, language)
            "viertel" -> return when {
                isWord(j + 1, "nach") -> relativeTo(j + 2, 15, false, language)
                isWord(j + 1, "vor") -> relativeTo(j + 2, 15, true, language)
                // "viertel 9" is 8:15 in parts of Germany.
                cue -> relativeTo(j + 1, 45, true, language, regional = true)
                else -> null
            }
            "dreiviertel" -> return relativeTo(j + 1, 15, true, language)
            "drei" -> if (isWord(j + 1, "viertel")) relativeTo(j + 2, 15, true, language)?.let { return it }
        }
        val n = numberAt(j) ?: return null
        if (n.value !in 1..29) return null
        var m = n.end
        val minutesSaid = isWord(m, "minute", "minuten", "min")
        if (minutesSaid) m++
        val after = when (word(m)) {
            "nach" -> true
            "vor" -> false
            else -> return null
        }
        val half = isWord(m + 1, "halb")
        val hourAt = if (half) m + 2 else m + 1
        if (!cue && !minutesSaid && n.value !in ROUND_MINUTES) return null
        return when {
            half && after -> relativeTo(hourAt, 30 - n.value, true, language)
            half -> relativeTo(hourAt, 30 + n.value, true, language)
            after -> relativeTo(hourAt, n.value, false, language)
            else -> relativeTo(hourAt, n.value, true, language)
        }
    }

    /** "15:30", "9:01:10", "um 7.30", "7.30 Uhr", "15h", "15h30". */
    private fun numericClock(j: Int, language: String?, cue: Boolean): Core? {
        val t = tok(j) ?: return null
        if (t.kind != TokenKind.NUMBER || t.text.length > 2) return null
        val hour = t.number ?: return null
        if (hour > 24) return null
        val fixed = isFixed(Num(hour, j + 1, t.text))
        if (isPunct(j + 1, ":") && touches(j) && touches(j + 1)) {
            val minuteToken = tok(j + 2)
            if (minuteToken == null || minuteToken.kind != TokenKind.NUMBER || minuteToken.text.length != 2) return null
            val minute = minuteToken.number ?: return null
            if (minute > 59) return null
            var end = j + 3
            var second = 0
            if (isPunct(end, ":") && touches(end - 1) && touches(end)) {
                val secondToken = tok(end + 1)
                if (secondToken != null && secondToken.kind == TokenKind.NUMBER && secondToken.text.length == 2) {
                    second = secondToken.number ?: 0
                    if (second > 59) return null
                    end += 2
                }
            }
            return Core(hour, minute, end, second = second, fixed = fixed)
        }
        if (isPunct(j + 1, ".") && touches(j) && touches(j + 1)) {
            val minuteToken = tok(j + 2)
            if (minuteToken == null || minuteToken.kind != TokenKind.NUMBER || minuteToken.text.length != 2) return null
            if (isPunct(j + 3, ".") && touches(j + 2)) return null
            val minute = minuteToken.number ?: return null
            if (minute > 59 || !(cue || isWord(j + 3, "uhr", "h"))) return null
            // "um 2.50 Euro" is a price.
            if (isAmount(j + 3)) return null
            return Core(hour, minute, j + 3, fixed = fixed)
        }
        if (isWord(j + 1, "h") && touches(j)) {
            val minuteToken = tok(j + 2)
            if (minuteToken != null && minuteToken.kind == TokenKind.NUMBER && minuteToken.text.length == 2 && touches(j + 1)) {
                val minute = minuteToken.number ?: return null
                if (minute > 59) return null
                return Core(hour, minute, j + 3, fixed = fixed)
            }
            return Core(hour, 0, j + 2, fixed = fixed)
        }
        return null
    }

    /** "9 Uhr", "9 Uhr 30", "neun Uhr dreißig", "3 o'clock", "3pm", "um 9", "at nine thirty", "8 tonight". */
    private fun hourWithMarker(j: Int, language: String?, cue: Boolean): Core? {
        val h = (if (isWord(j, "ein") && isWord(j + 1, "uhr")) Num(1, j + 1, null) else numberAt(j)) ?: return null
        if (h.value !in 0..24 || (h.digits != null && h.digits.length > 2)) return null
        val fixed = isFixed(h)
        val k = h.end
        if (isWord(k, "uhr")) {
            val m = numberAt(k + 1)
            if (m != null && m.value in 0..59 && (m.digits == null || m.digits.length == 2) && !isAmount(m.end)) {
                return Core(h.value, m.value, m.end, fixed = fixed, sawUhr = true)
            }
            return Core(h.value, 0, k + 1, fixed = fixed, sawUhr = true)
        }
        if (isWordIn(k, OCLOCK)) return Core(h.value, 0, k + 1, fixed = fixed)
        if (isWord(k, "o") && isWord(k + 1, "clock")) return Core(h.value, 0, k + 2, fixed = fixed)
        meridiemAt(k, language)?.let { (meridiem, end) -> return Core(h.value, 0, end, fixed = fixed, meridiem = meridiem) }
        if (h.value == 12 && isWord(k, "noon", "midday")) return Core(12, 0, k + 1, fixed = true)
        if (h.value == 12 && isWord(k, "midnight")) return Core(0, 0, k + 1, fixed = true, midnight = true)
        if (cue) {
            // "um 2,5", "um 1,80": a decimal number, not an hour.
            if (isPunct(k, ",", ".") && touches(k - 1) && touches(k) && tok(k + 1)?.kind == TokenKind.NUMBER) return null
            // Minutes said as a number after the hour: "at nine thirty", "at 3 15", "um neun dreißig".
            val m = numberAt(k)
            if (m != null && m.value in 10..59 && (m.digits == null || m.digits.length == 2) && !isAmount(m.end)) {
                val meridiem = meridiemAt(m.end, language)
                return Core(h.value, m.value, meridiem?.second ?: m.end, fixed = fixed, meridiem = meridiem?.first)
            }
            if (isWord(k, "oh")) {
                val unit = word(k + 1)?.let(TimeLexicon::englishUnit)
                if (unit != null) return Core(h.value, unit, k + 2, fixed = fixed)
            }
            return Core(h.value, 0, k, fixed = fixed, marked = false)
        }
        // A bare number is only an hour when a part of the day follows, and then not when it, or
        // the range of numbers it ends, counts something ("Kapitel 5 heute Abend", "chapters 3 to
        // 5 tonight").
        if (startsDayPart(k) && !countedBefore(j) && !endsCountedRange(j)) return Core(h.value, 0, k, fixed = fixed)
        return null
    }

    /** "am", "pm", "a.m.", "p.m." at [i]: the half of the day and the token after it. */
    private fun meridiemAt(i: Int, language: String?, sawUhr: Boolean = false): Pair<Meridiem, Int>? {
        val t = tok(i) ?: return null
        if (t.kind != TokenKind.WORD) return null
        when (t.folded) {
            "pm" -> return Meridiem.PM to i + 1
            "am" -> return if (isEnglishAm(i, language, sawUhr)) Meridiem.AM to i + 1 else null
            "a", "p" -> if (isPunct(i + 1, ".") && touches(i) && isWord(i + 2, "m") && touches(i + 1)) {
                val end = if (isPunct(i + 3, ".") && touches(i + 2)) i + 4 else i + 3
                return (if (t.folded == "a") Meridiem.AM else Meridiem.PM) to end
            }
        }
        return null
    }

    /**
     * Is "am" after a clock the English a.m. ("at 6 am Friday", "6am"), or the German "am" of
     * "um 9 am Freitag", "9 Uhr am 16. Oktober"? "um" and "Uhr" make it German, "at" English;
     * without either the next word decides.
     */
    private fun isEnglishAm(i: Int, language: String?, sawUhr: Boolean): Boolean {
        if (tokens[i].text == "AM") return true
        // German never writes "am" onto the number: "6am".
        if (i > 0 && tokens[i - 1].kind == TokenKind.NUMBER && touches(i - 1)) return true
        if (sawUhr) return false
        val next = tok(i + 1) ?: return true
        if (next.kind == TokenKind.PUNCT) return true
        if (language == "um") return false
        if (language == "at") return true
        // "6 am 16 October" is English, "9 am 16. Oktober" German.
        if (next.kind == TokenKind.NUMBER) return !(touches(i + 1) && isPunct(i + 2, "."))
        if (TimeLexicon.isEnglishCalendarWord(next.folded)) return true
        if (startsGermanAmPhrase(i + 1)) return false
        return !next.capitalized
    }

    private fun startsGermanAmPhrase(k: Int): Boolean {
        val t = tok(k) ?: return false
        if (t.kind == TokenKind.NUMBER) return true
        if (t.kind != TokenKind.WORD) return false
        val w = t.folded
        return TimeLexicon.weekday(w) != null || TimeLexicon.month(w) != null ||
            TimeLexicon.ordinal(w) != null || w in TimeLexicon.PART_NOUNS_DE || w in GERMAN_AM_WORDS
    }

    private companion object {
        val OPENERS = setOf("\"", "„", "“", "”", "'", "‚", "‘", "(", "[", "«", "»", "-", "–", "—")
        val GREETING_FOLLOWERS = setOf("zusammen", "allerseits", "leute", "alle", "miteinander", "ihr")
        val GREETING_OPENERS = setOf("hallo", "hi", "hey", "moin", "servus")
        val SENTENCE_ENDS = setOf(".", "!", "?", ":", ";", "…", "\n")

        val EVERY_WORDS = setOf("jeden", "jede", "jedem", "every", "each")
        val THIS_WORDS = setOf("diesen", "diese", "dieser", "diesem", "dieses")
        val NEXT_WORDS = setOf("next", "naechsten", "naechster", "naechste", "naechstem", "naechstes")
        val COMING_WORDS = setOf("coming", "kommenden", "kommender", "kommende", "kommendem")
        val AFTER_NEXT_WORDS = setOf("uebernaechsten", "uebernaechster", "uebernaechste")
        val LAST_WORDS = setOf(
            "last", "letzten", "letzter", "letzte", "vergangenen", "vergangener", "previous", "past"
        )

        val WEEK_MODIFIERS = mapOf(
            "next" to 1, "naechste" to 1, "naechsten" to 1, "naechster" to 1, "kommende" to 1,
            "kommenden" to 1, "kommender" to 1, "following" to 1, "upcoming" to 1, "coming" to 1,
            "this" to 0, "diese" to 0, "dieser" to 0, "current" to 0, "laufende" to 0,
            "laufenden" to 0,
            "last" to -1, "letzte" to -1, "letzten" to -1, "letzter" to -1, "vergangene" to -1,
            "vergangenen" to -1, "vergangener" to -1, "previous" to -1, "past" to -1,
            "uebernaechste" to 2, "uebernaechsten" to 2, "uebernaechster" to 2
        )

        val MONTH_MODIFIERS = mapOf(
            "next" to 1, "naechsten" to 1, "naechster" to 1, "naechstes" to 1, "kommenden" to 1,
            "this" to 0, "diesen" to 0, "dieses" to 0, "diesem" to 0, "current" to 0,
            "last" to -1, "letzten" to -1, "vergangenen" to -1, "previous" to -1,
            "uebernaechsten" to 2
        )

        val DATE_PREFIXES = setOf("am", "on", "den", "der", "dem", "zum", "vom", "ab", "seit")

        /** Words that open a span of dates, as "between" does: "vom 7. bis 9. Dezember", "from 3 to 5 November". */
        val DATE_RANGE_OPENERS = setOf("von", "vom", "from", "ab")

        /** English words before a day number: after them a dot ends a sentence ("from 9 to 5. November is ..."). */
        val ENGLISH_DAY_LEADS = setOf(
            "to", "till", "til", "until", "through", "thru", "and", "from", "between", "on", "by", "the"
        )

        /** Words before a month name that make it a date: "by March 3", "bis Mai 5". */
        val DATE_CUES = setOf(
            "by", "until", "till", "til", "before", "after", "since", "from", "due", "bis", "ab", "seit",
            "vor", "nach"
        )
        val DAY_ONLY_FOLLOWERS = setOf("um", "at", "gegen", "and", "und", "or", "oder", "bis", "ab", "from", "to")
        val ABBREVIATED_MONTHS = setOf(
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "okt", "nov",
            "dec", "dez"
        )
        val OCLOCK = setOf("o'clock", "oclock")

        /** Amount words that are other words before a number: "komm mal 9 bis 10 Uhr", "this time 7 to 9 pm". */
        val NOT_COUNTED_BEFORE = setOf("of", "mal", "time")

        val RANGE_TO = setOf("to", "till", "til", "until", "through", "thru")
        val CLOCK_APPROX = setOf(
            "gegen", "around", "about", "approximately", "roughly", "ungefaehr", "etwa", "circa",
            "zirka", "ca"
        )
        val OFFSET_APPROX = setOf(
            "about", "around", "approximately", "roughly", "etwa", "ungefaehr", "circa", "zirka",
            "ca", "knapp", "just", "only", "nur"
        )
        val ROUND_MINUTES = setOf(5, 10, 15, 20, 25)
        const val MAX_OFFSET = 9999.0
        val GERMAN_AM_WORDS = setOf(
            "wochenende", "naechsten", "kommenden", "letzten", "uebernaechsten", "folgenden",
            "selben", "gleichen", "fruehen", "spaeten", "anfang", "ende", "tag"
        )
    }
}
