package com.satori.domain.time

import java.time.ZonedDateTime

/**
 * Finds English and German date and time expressions with hand-written rules, resolved with
 * java.time so daylight saving changes come out right. Pure Kotlin, no state, safe to share.
 *
 * Both languages' rules run on every text. Each expression is the longest run of pieces that
 * belong together ("am Freitag" + "um halb 9"). Readings follow [TimePolicy]:
 * - "nächsten Freitag" / "next Friday" is the coming Friday unless
 *   [TimePolicy.nextWeekdayMeansFollowingWeek]; it scores lower when the two readings differ.
 * - An hour without am/pm is read inside [TimePolicy.wakingHours] ("um 3" = 15:00); when both
 *   halves fit ("um 9"), a named day takes the morning and otherwise the next one ahead wins.
 * - Without a day, a time is the next such time; a weekday or a date the next such day.
 * - A part of the day without a clock time ("Freitagabend", "tonight") has no time
 *   ([TimePrecision.PART_OF_DAY]); its candidate sits at the part's usual hour: morning 08:00,
 *   forenoon 10:00, noon 12:00, afternoon 15:00, evening 19:00, night 22:00.
 * - A range of clock times ("von 10 bis 12 Uhr", "between 9 and 11 am", "9-11 Uhr", "um 9 bis 10
 *   Uhr") is one candidate from its start to [TimeCandidate.endAt]; the start takes the end's
 *   half of the day, a range said with an evening or a night is one stretch of it ("heute Abend
 *   von 4 bis 6" is 16:00 to 18:00), and a range that is running now is today's. Two bare
 *   numbers are hours only right after a day and "von", "from", "zwischen", "between" or "ab"
 *   ("Freitag von 10 bis 12"), and then tentatively: else they count anything ("Skala von 1 bis
 *   5", "between 5 and 10 volunteers", "Am Montag 2 bis 3 Folien").
 * - Weeks, weekends, months and spans of dates ("nächste Woche", "am Wochenende", "Ende des
 *   Monats", "vom 7. bis 9. Dezember") are runs of days ([TimePrecision.DAYS_RANGE]) from
 *   [TimeCandidate.startAt] to [TimeCandidate.endAt], with [TimeCandidate.at] on the day they
 *   most likely mean ("Ende der Woche": Friday); one that has begun starts today. A run names no
 *   one day, so it is tentative.
 * - A time without a day takes the day said elsewhere in the same sentence ("Morgen muss ich um
 *   9 beim Arzt sein": "um 9" is tomorrow at 09:00), or, when only a run of days was said, the
 *   run's likely day at a low confidence.
 * - "August 15", "March 3" and "May 5" are dates; "Jan", "Mai" and "march" or "may" in lower
 *   case are as often a name or a verb and need more around them ("am 14. Jan", "on march 3";
 *   not "the meeting from 2 to 4 may be moved"). Only German writes a dot after a day ("5.
 *   November"); after an English word or before an English month it ends a sentence.
 * - German traps: "guten Morgen" is a greeting, "heute Morgen" and "Freitagmorgen" are mornings,
 *   "morgen" is tomorrow, "halb 8" is 07:30 or 19:30, "um 5 Prozent" is no time.
 */
class RuleBasedTimeParser : TimeExpressionParser {

    override fun find(text: String, anchor: ZonedDateTime, policy: TimePolicy): List<TimeCandidate> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val expressions = TimeGrammar(tokens).expressions()
        if (expressions.isEmpty()) return emptyList()
        val resolver = TimeResolver(anchor, policy)
        val own = expressions.map { resolver.resolve(it) }
        val sentence = sentenceIds(tokens, expressions)
        return expressions.indices.mapNotNull { index ->
            val e = expressions[index]
            val needsDay = !e.hasDay && e.timeOffset == null && (e.clock != null || e.part != null)
            val context = if (needsDay) dayInSentence(index, expressions, own, sentence) else null
            val resolution = (if (context != null) resolver.resolve(e, context) else own[index]) ?: return@mapNotNull null
            val start = tokens[e.from].start
            val end = tokens[e.to - 1].end
            TimeCandidate(
                phrase = text.substring(start, end),
                start = start,
                end = end,
                at = resolution.at,
                hasTime = resolution.hasTime,
                confidence = resolution.confidence,
                endAt = resolution.endAt,
                precision = resolution.precision,
                startAt = resolution.startAt
            )
        }
    }

    /**
     * The nearest expression in the same sentence that names an exact day, preferring earlier
     * ones; failing that, the nearest that names a run of days ("Next weekend we leave at 6").
     */
    private fun dayInSentence(
        index: Int,
        expressions: List<Expression>,
        own: List<Resolution?>,
        sentence: IntArray
    ): Resolution? {
        val id = sentence[expressions[index].from]
        val before = (index - 1 downTo 0).asSequence()
        val after = (index + 1 until expressions.size).asSequence()
        var run: Resolution? = null
        for (k in before + after) {
            if (sentence[expressions[k].from] != id) continue
            val r = own[k] ?: continue
            if (expressions[k].hasExactDay && r.saidDay != null) return r
            if (run == null && r.saidDays != null) run = r
        }
        return run
    }

    /** A sentence number for every token; sentences end at . ! ? ; : and line breaks outside expressions. */
    private fun sentenceIds(tokens: List<Token>, expressions: List<Expression>): IntArray {
        val inside = BooleanArray(tokens.size)
        for (e in expressions) for (k in e.from until e.to) inside[k] = true
        val ids = IntArray(tokens.size)
        var id = 0
        for (k in tokens.indices) {
            ids[k] = id
            val t = tokens[k]
            if (!inside[k] && t.kind == TokenKind.PUNCT && t.text in SENTENCE_ENDS) id++
        }
        return ids
    }

    private companion object {
        val SENTENCE_ENDS = setOf(".", "!", "?", ";", ":", "…", "\n")
    }
}
