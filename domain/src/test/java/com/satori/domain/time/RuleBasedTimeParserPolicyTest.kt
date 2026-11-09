package com.satori.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** The policy, confidence, unusual anchors, daylight saving time and context within a text. */
class RuleBasedTimeParserPolicyTest {

    private val p = Probe()

    // --- TimePolicy -------------------------------------------------------------------------

    @Test
    fun `preferFuture false takes the nearest weekday even if it has passed`() {
        val sunday18 = berlin("2026-10-18T10:00")
        p.day("2026-10-23", "am Freitag", anchor = sunday18)
        p.day("2026-10-16", "am Freitag", anchor = sunday18, policy = TimePolicy(preferFuture = false))
    }

    @Test
    fun `preferFuture false takes the nearest hour even if it has passed`() {
        p.at("2026-10-12T09:00+02:00", "um 9", policy = TimePolicy(preferFuture = false))
    }

    @Test
    fun `preferFuture false keeps a date of this year that has just passed`() {
        p.day("2026-10-16", "16 October", anchor = berlin("2026-10-20T10:00"), policy = TimePolicy(preferFuture = false))
    }

    @Test
    fun `waking hours decide which half of the clock a bare hour is in`() {
        p.at("2026-10-12T18:00+02:00", "um 6")
        // With waking hours 5..17, 6 is 06:00, which has passed today.
        p.at("2026-10-13T06:00+02:00", "um 6", policy = TimePolicy(wakingHours = 5..17))
    }

    @Test
    fun `the date-only hour comes from the policy`() {
        p.at("2026-10-13T07:00+02:00", "morgen", hasTime = false, policy = TimePolicy(dateOnlyHour = 7))
    }

    // --- Confidence -------------------------------------------------------------------------

    @Test
    fun `an ambiguous next weekday scores lower than a plain weekday`() {
        assertTrue(p.confidence("nächsten Freitag") < p.confidence("am Freitag"))
        assertTrue(p.confidence("next Friday") < p.confidence("on Friday"))
    }

    @Test
    fun `next weekday scores higher when both readings agree`() {
        // On a Monday, "next Monday" is the 19th either way; "next Friday" is the 16th or 23rd.
        assertTrue(p.confidence("next Friday") < p.confidence("next Monday"))
    }

    @Test
    fun `an hour without am or pm scores lower than a 24-hour time`() {
        assertTrue(p.confidence("um 9") < p.confidence("um 21 Uhr"))
        assertTrue(p.confidence("at 9") < p.confidence("at 9 pm"))
    }

    @Test
    fun `vague expressions score lower than exact ones`() {
        assertTrue(p.confidence("nächste Woche") < p.confidence("am Freitag"))
        assertTrue(p.confidence("this weekend") < p.confidence("on Saturday"))
        assertTrue(p.confidence("am Montag") < p.confidence("am Dienstag"))
    }

    @Test
    fun `a weekday in a named week is exact, not a range`() {
        assertTrue(p.confidence("nächste Woche Dienstag") > p.confidence("nächste Woche"))
        assertTrue(p.confidence("Wednesday next week") > p.confidence("next week"))
    }

    @Test
    fun `a date moved to next year scores lower`() {
        val rolled = p.confidence("16 October", anchor = berlin("2026-10-20T10:00"))
        val ahead = p.confidence("16 October", anchor = berlin("2026-10-10T10:00"))
        assertTrue(rolled < ahead)
    }

    @Test
    fun `confidence stays between zero and one`() {
        val texts = listOf(
            "morgen um 9", "nächsten Freitag", "Morgen gehe ich", "in 2 Stunden", "am Wochenende",
            "um halb 8", "the day after tomorrow at 5pm", "next week", "am 16.10. um 14:00"
        )
        for (text in texts) {
            for (candidate in p.all(text)) {
                assertTrue("$text: ${candidate.confidence}", candidate.confidence > 0f && candidate.confidence <= 1f)
            }
        }
    }

    // --- Anchors ----------------------------------------------------------------------------

    @Test
    fun `late in the evening`() {
        p.day("2026-10-17", "morgen", anchor = FRIDAY_LATE)
        // Both 09:00 and 21:00 have passed today: the next one is tomorrow morning.
        p.at("2026-10-17T09:00+02:00", "um 9", anchor = FRIDAY_LATE)
        p.partOfDay("2026-10-16T19:00+02:00", "heute Abend", anchor = FRIDAY_LATE)
        p.at("2026-10-17T00:30+02:00", "in 2 Stunden", anchor = FRIDAY_LATE)
        p.at("2026-10-16T23:00+02:00", "at 11 pm", anchor = FRIDAY_LATE)
        p.day("2026-10-23", "am Freitag", anchor = FRIDAY_LATE)
    }

    @Test
    fun `on a Sunday next week starts tomorrow`() {
        p.days("2026-10-12", "2026-10-18", "nächste Woche", anchor = SUNDAY_10)
        p.days("2026-10-12", "2026-10-18", "next week", anchor = SUNDAY_10)
        p.day("2026-10-12", "am Montag", anchor = SUNDAY_10)
    }

    @Test
    fun `on a Sunday both readings of next Monday are tomorrow`() {
        p.day("2026-10-12", "nächsten Montag", anchor = SUNDAY_10)
        p.day("2026-10-12", "nächsten Montag", anchor = SUNDAY_10, policy = TimePolicy(nextWeekdayMeansFollowingWeek = true))
        p.day("2026-10-18", "next Sunday", anchor = SUNDAY_10)
        p.day("2026-10-18", "next Sunday", anchor = SUNDAY_10, policy = TimePolicy(nextWeekdayMeansFollowingWeek = true))
    }

    @Test
    fun `the end of the week that has passed is next week's`() {
        // The end of the week is its Thursday and Friday, most likely Friday; this week's were
        // three days ago.
        p.days("2026-10-15", "2026-10-16", "Ende der Woche", "2026-10-16", anchor = SUNDAY_10)
        p.days("2026-10-15", "2026-10-16", "by the end of the week", "2026-10-16", anchor = SUNDAY_10)
        p.days("2026-10-08", "2026-10-09", "Ende der Woche", "2026-10-09", anchor = berlin("2026-10-07T10:00"))
        // A month's end is from the 21st to its last day, its start the first ten days.
        p.days("2026-10-21", "2026-10-31", "Ende des Monats", "2026-10-31")
        p.days("2026-11-01", "2026-11-10", "Anfang des Monats")
    }

    @Test
    fun `on a Sunday the weekend is today and Sunday is a week later`() {
        p.days("2026-10-11", "2026-10-11", "this weekend", anchor = SUNDAY_10)
        p.day("2026-10-18", "am Sonntag", anchor = SUNDAY_10)
    }

    @Test
    fun `a weekday that does not match the day of the month stays close and scores low`() {
        // The 17th is a Saturday: the date stays the 17th, and the mismatch costs confidence.
        for (text in listOf("am Freitag, den 17.", "Freitag, der 17.", "Friday the 17th")) {
            val found = p.day("2026-10-17", text)
            assertTrue("$text: ${found.confidence}", found.confidence <= 0.5f)
        }
        // The 19th is a Monday.
        for (text in listOf("Dienstag, 19.", "Tuesday the 19th")) {
            val found = p.day("2026-10-19", text)
            assertTrue("$text: ${found.confidence}", found.confidence <= 0.5f)
        }
        // The mismatch costs the same with or without the month.
        assertEquals(p.confidence("Freitag, 17. Oktober"), p.confidence("Freitag, den 17."), 0.001f)
        // A match: full confidence.
        assertEquals(0.9f, p.day("2026-10-16", "Freitag, den 16.").confidence, 0.001f)
        // The next 13th is a Tuesday, but 13 November is a Friday, a month away: taken, but as
        // tentative as a mismatch, because the weekday or the day was said wrong.
        for ((text, day) in listOf(
            "Friday the 13th" to "2026-11-13", "am Freitag, den 13." to "2026-11-13", "Saturday the 14th" to "2026-11-14"
        )) {
            val found = p.day(day, text)
            assertTrue("$text: ${found.confidence}", found.confidence < 0.5f)
        }
        // No year-long jumps without a month: the nearest 31st on a Monday is a year away.
        val far = p.day("2026-10-31", "Monday the 31st")
        assertTrue(far.confidence <= 0.5f)
    }

    @Test
    fun `tomorrow said just after midnight is the calendar day after, with less certainty`() {
        val afterMidnight = berlin("2026-10-17T00:20")
        p.day("2026-10-18", "morgen", anchor = afterMidnight)
        val noon = p.confidence("morgen", anchor = berlin("2026-10-17T12:00"))
        assertTrue(p.confidence("morgen", anchor = afterMidnight) < noon)
    }

    // --- Daylight saving time in Europe/Berlin -------------------------------------------------

    @Test
    fun `hours from now count real hours across the autumn change`() {
        // Saturday 24 October 2026, 22:00 CEST; the clocks go back at 03:00 on Sunday.
        val anchor = berlin("2026-10-24T22:00")
        p.at("2026-10-25T03:00+01:00", "in 6 Stunden", anchor = anchor)
        val tomorrow = p.at("2026-10-25T10:00+01:00", "morgen um 10", anchor = anchor)
        assertEquals(Duration.ofHours(13), Duration.between(anchor, tomorrow.at))
        p.at("2026-10-26T09:00+01:00", "in 2 Tagen", hasTime = false, anchor = anchor)
    }

    @Test
    fun `a wall time that happens twice takes the earlier one`() {
        p.at("2026-10-25T02:30+02:00", "am 25.10. um 02:30", anchor = berlin("2026-10-24T22:00"))
    }

    @Test
    fun `days ahead keep the wall clock across the spring change`() {
        // Saturday 28 March 2026; the clocks go forward at 02:00 on Sunday.
        val anchor = berlin("2026-03-28T10:00")
        val found = p.at("2026-03-29T09:00+02:00", "tomorrow at 9", anchor = anchor)
        assertEquals(Duration.ofHours(22), Duration.between(anchor, found.at))
    }

    @Test
    fun `hours from now count real hours across the spring change`() {
        p.at("2026-03-29T03:30+02:00", "in 3 hours", anchor = berlin("2026-03-28T23:30"))
    }

    @Test
    fun `a wall time that does not exist moves past the gap`() {
        p.at("2026-03-29T03:30+02:00", "am 29.03. um 02:30", anchor = berlin("2026-03-28T10:00"))
    }

    @Test
    fun `a range that starts in the gap moves past it and keeps its length`() {
        // 02:30 does not exist that night: the range is half an hour from 03:30, not 03:30 to 03:00.
        p.range("2026-03-29T03:30+02:00", "2026-03-29T04:00+02:00", "heute Nacht von 2:30 bis 3 Uhr", anchor = berlin("2026-03-28T22:00"))
    }

    @Test
    fun `the result keeps the anchor's zone`() {
        assertEquals(BERLIN, p.one("morgen um 9").at.zone)
        assertEquals(BERLIN, p.one("in 2 hours").at.zone)
    }

    // --- Robustness -------------------------------------------------------------------------------

    @Test
    fun `absurd amounts and impossible dates give nothing instead of failing`() {
        p.none("in 999999999 Jahren")
        p.none("in 999999999 years")
        p.none("am 31.02.")
        p.none("um 99")
        p.none("")
        p.none("   ")
    }

    @Test
    fun `random word salad never throws and keeps the candidate invariants`() {
        val vocabulary = listOf(
            "um", "at", "am", "on", "the", "next", "nächsten", "halb", "viertel", "vor", "nach", "in",
            "Stunden", "hours", "Tagen", "Morgen", "morgen", "guten", "Abend", "Freitag", "Friday",
            "Oktober", "October", "16.", "16.10.", "15:30", "9", "neun", "twenty", "one", "Uhr", "pm",
            "a.m.", ",", ".", "!", "2,5", "Mitternacht", "noon", "Wochenende", "Ende", "der", "Woche",
            "week", "ago", "from", "now", "heute", "tomorrow", "1st", "3rd", "24", "0", "99", "2026",
            "-", "Prozent", "%", "zwölf", "dreiviertel", "o'clock", "nachts", "früh", "of", "des",
            "von", "bis", "zwischen", "und", "between", "and", "to", "Mittag", "jeden", "every", "Jan",
            "march", "May", "AM", "tonight", "Nacht", "2.50", "Euro", "–", "11", "22", "vom", "zum", "3.",
            "Dezember", "August", "gegen", "so", "ab", "Mitte", "Monats", "7", "31.", "abends"
        )
        // Anchors include the spring gap and the autumn overlap in Berlin.
        val anchors = listOf(MONDAY_10, SUNDAY_10, FRIDAY_LATE, berlin("2026-03-29T01:30"), berlin("2026-10-25T02:30"))
        val random = kotlin.random.Random(20261010)
        repeat(3000) {
            val text = (1..random.nextInt(1, 12)).joinToString(" ") { vocabulary[random.nextInt(vocabulary.size)] }
            val anchor = anchors[random.nextInt(anchors.size)]
            var previousEnd = 0
            for (candidate in p.all(text, anchor)) {
                assertTrue(text, candidate.start >= previousEnd && candidate.end > candidate.start)
                assertEquals(text, candidate.phrase, text.substring(candidate.start, candidate.end))
                assertTrue(text, candidate.confidence > 0f && candidate.confidence <= 1f)
                assertEquals(text, anchor.zone, candidate.at.zone)
                assertEquals(text, candidate.precision == TimePrecision.EXACT, candidate.hasTime)
                // A range of clock times ends after it starts; a run of days may be one day long.
                val endAt = candidate.endAt
                if (endAt != null) {
                    val ordered = if (candidate.precision == TimePrecision.DAYS_RANGE) {
                        !endAt.isBefore(candidate.at)
                    } else {
                        endAt.isAfter(candidate.at)
                    }
                    assertTrue("$text: $candidate", ordered)
                }
                // A run of days means a day from its first to its last; nothing else has a first day.
                val startAt = candidate.startAt
                if (candidate.precision == TimePrecision.DAYS_RANGE) {
                    assertTrue("$text: $candidate", endAt != null && startAt != null)
                    assertTrue("$text: $candidate", !candidate.at.isBefore(startAt) && !candidate.at.isAfter(endAt))
                } else {
                    assertTrue("$text: $candidate", startAt == null)
                }
                previousEnd = candidate.end
            }
        }
    }

    // --- Several expressions, context and mixed languages ---------------------------------------

    @Test
    fun `several expressions come out in order with their offsets`() {
        val text = "Am Freitag um 9 Zahnarzt und am Samstag um 14 Uhr Fußball."
        val found = p.all(text)
        assertEquals(2, found.size)
        assertEquals("Am Freitag um 9", found[0].phrase)
        assertEquals(0, found[0].start)
        assertEquals("Am Freitag um 9".length, found[0].end)
        assertEquals("2026-10-16T09:00+02:00", found[0].at.toOffsetDateTime().toString())
        assertEquals("am Samstag um 14 Uhr", found[1].phrase)
        assertEquals(text.indexOf("am Samstag"), found[1].start)
        assertEquals("2026-10-17T14:00+02:00", found[1].at.toOffsetDateTime().toString())
        for (candidate in found) {
            assertEquals(candidate.phrase, text.substring(candidate.start, candidate.end))
        }
    }

    @Test
    fun `a time later in the sentence takes the day said earlier`() {
        val found = p.all("Morgen muss ich um 9 beim Arzt sein.")
        assertEquals(2, found.size)
        assertEquals("Morgen", found[0].phrase)
        assertEquals("2026-10-13", found[0].at.toLocalDate().toString())
        assertEquals(false, found[0].hasTime)
        assertEquals("um 9", found[1].phrase)
        assertEquals("2026-10-13T09:00+02:00", found[1].at.toOffsetDateTime().toString())
    }

    @Test
    fun `an English time later in the sentence takes the day said earlier`() {
        val found = p.all("Tomorrow I have to call Anna at 3.")
        assertEquals(2, found.size)
        assertEquals("at 3", found[1].phrase)
        assertEquals("2026-10-13T15:00+02:00", found[1].at.toOffsetDateTime().toString())
    }

    @Test
    fun `a time later in the sentence takes the day and the part of the day`() {
        val found = p.all("Morgen Abend gehen wir um 8 essen.")
        assertEquals(2, found.size)
        assertEquals("2026-10-13T19:00+02:00", found[0].at.toOffsetDateTime().toString())
        assertEquals("2026-10-13T20:00+02:00", found[1].at.toOffsetDateTime().toString())
    }

    @Test
    fun `a day does not carry over into the next sentence`() {
        val found = p.all("Morgen habe ich keine Zeit. Um 9 passt es.")
        assertEquals(2, found.size)
        assertEquals("Um 9", found[1].phrase)
        assertEquals("2026-10-12T21:00+02:00", found[1].at.toOffsetDateTime().toString())
    }

    @Test
    fun `English and German mix within one expression`() {
        val meeting = p.at("2026-10-13T09:00+02:00", "Meeting tomorrow um 9")
        assertEquals("tomorrow um 9", meeting.phrase)
        p.at("2026-10-16T15:00+02:00", "Termin next Friday um 15 Uhr")
        p.at("2026-10-13T15:00+02:00", "Ich ruf dich tomorrow at 3 an")
    }
}
