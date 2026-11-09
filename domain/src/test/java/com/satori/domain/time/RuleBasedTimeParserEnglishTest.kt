package com.satori.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * English expressions. Unless a test says otherwise the anchor is Monday 12 October 2026, 10:00
 * in Berlin (CEST, +02:00); Friday is the 16th, and the clocks go back on Sunday the 25th.
 */
class RuleBasedTimeParserEnglishTest {

    private val p = Probe()

    @Test
    fun `relative days are dates without a time`() {
        assertEquals("today", p.day("2026-10-12", "Let's do it today.").phrase)
        p.day("2026-10-13", "I'll call her tomorrow")
        assertEquals("the day after tomorrow", p.day("2026-10-14", "the day after tomorrow").phrase)
        p.day("2026-10-14", "Day after tomorrow works.")
        p.day("2026-10-11", "yesterday")
        p.day("2026-10-10", "the day before yesterday")
        p.day("2026-10-13", "tomorrow's meeting")
    }

    @Test
    fun `a date-only expression sits at the policy's date-only hour`() {
        p.at("2026-10-13T09:00+02:00", "tomorrow", hasTime = false)
    }

    @Test
    fun `clock times on a given day`() {
        p.at("2026-10-13T15:00+02:00", "tomorrow at 3 pm")
        p.at("2026-10-13T15:00+02:00", "tomorrow at 3pm")
        p.at("2026-10-13T15:00+02:00", "tomorrow at 3 p.m.")
        p.at("2026-10-13T09:00+02:00", "at 9 am tomorrow")
        p.at("2026-10-13T15:00+02:00", "3pm tomorrow")
        p.at("2026-10-10T08:00+02:00", "the day before yesterday at 8am")
    }

    @Test
    fun `a bare hour is read inside waking hours`() {
        // 3 can only be 15:00 between 07 and 21.
        p.at("2026-10-13T15:00+02:00", "tomorrow at 3")
        // 11 pm is outside waking hours, so 11 is 11 am, still ahead today.
        p.at("2026-10-12T11:00+02:00", "at 11")
        // 9 am has passed at 10:00 and 9 pm is a waking hour: the nearest one ahead.
        p.at("2026-10-12T21:00+02:00", "at 9")
        p.at("2026-10-12T20:00+02:00", "at 8 o'clock")
    }

    @Test
    fun `twenty-four hour times`() {
        p.at("2026-10-12T15:30+02:00", "at 15:30")
        p.at("2026-10-12T15:30+02:00", "15:30")
        p.at("2026-10-15T10:30+02:00", "the meeting is at 10:30 on Thursday")
    }

    @Test
    fun `parts of the day set the half of the clock`() {
        p.at("2026-10-12T19:00+02:00", "at 7 in the evening")
        p.at("2026-10-12T20:00+02:00", "8 tonight")
        p.at("2026-10-12T20:00+02:00", "at 8 tonight")
        p.at("2026-10-12T20:00+02:00", "tonight at 8 o'clock")
        p.at("2026-10-12T15:00+02:00", "at 3 o'clock in the afternoon")
    }

    @Test
    fun `parts of the day alone have no time, only the part's usual hour`() {
        p.partOfDay("2026-10-12T19:00+02:00", "tonight")
        p.partOfDay("2026-10-12T15:00+02:00", "this afternoon")
        p.partOfDay("2026-10-13T08:00+02:00", "tomorrow morning")
        p.at("2026-10-13T07:30+02:00", "tomorrow morning at 7:30")
        p.partOfDay("2026-10-12T19:00+02:00", "in the evening")
        p.partOfDay("2026-10-16T08:00+02:00", "Friday morning")
    }

    @Test
    fun `noon and midnight`() {
        p.at("2026-10-12T12:00+02:00", "at noon")
        p.at("2026-10-13T12:00+02:00", "tomorrow at noon")
        // Midnight ends the day it belongs to.
        p.at("2026-10-13T00:00+02:00", "at midnight")
        p.at("2026-10-14T00:00+02:00", "the deadline is tomorrow at midnight")
    }

    @Test
    fun `spoken clock phrases`() {
        p.at("2026-10-12T15:30+02:00", "at half past three")
        p.at("2026-10-12T15:45+02:00", "quarter to four")
        // 07:15 has passed; 19:15 is still ahead and inside waking hours.
        p.at("2026-10-12T19:15+02:00", "at quarter past 7")
        p.at("2026-10-12T19:50+02:00", "at ten to eight")
        p.at("2026-10-13T09:30+02:00", "at nine thirty tomorrow")
        p.at("2026-10-13T07:45+02:00", "at seven forty-five tomorrow")
    }

    @Test
    fun `weekdays`() {
        p.day("2026-10-16", "on Friday")
        p.at("2026-10-16T16:00+02:00", "Friday at 4 pm")
        p.day("2026-10-16", "this Friday")
        p.day("2026-10-09", "last Friday")
        p.day("2026-10-20", "Tuesday next week")
        p.day("2026-10-20", "next week on Tuesday")
    }

    @Test
    fun `next weekday follows the policy`() {
        p.day("2026-10-16", "next Friday")
        p.day("2026-10-23", "next Friday", policy = TimePolicy(nextWeekdayMeansFollowingWeek = true))
        // Said on a Monday, both readings of "next Monday" are the 19th.
        p.day("2026-10-19", "next Monday")
        p.day("2026-10-19", "next Monday", policy = TimePolicy(nextWeekdayMeansFollowingWeek = true))
    }

    @Test
    fun `the weekday of today means the one a week later`() {
        p.day("2026-10-19", "on Monday")
    }

    @Test
    fun `dates`() {
        p.day("2026-10-16", "16 October")
        p.day("2026-10-16", "October 16th")
        p.day("2026-10-16", "the 16th of October")
        p.day("2026-10-16", "on the sixteenth of October")
        p.at("2026-10-16T10:30+02:00", "on October 16, 2026 at 10:30")
        p.at("2026-10-16T15:00+02:00", "Friday, October 16 at 3 pm")
        // After the clocks go back the offset is +01:00.
        p.at("2026-11-01T09:00+01:00", "1 November", hasTime = false)
    }

    @Test
    fun `a date that has passed this year means next year`() {
        p.at("2027-10-16T09:00+02:00", "16 October", hasTime = false, anchor = berlin("2026-10-20T10:00"))
    }

    @Test
    fun `durations from now`() {
        p.at("2026-10-12T12:00+02:00", "in 2 hours")
        p.at("2026-10-12T12:00+02:00", "in two hours")
        p.at("2026-10-12T10:30+02:00", "in 30 minutes")
        p.at("2026-10-12T10:30+02:00", "in half an hour")
        p.at("2026-10-12T11:30+02:00", "in an hour and a half")
        p.at("2026-10-12T10:15+02:00", "in a quarter of an hour")
        p.at("2026-10-12T08:00+02:00", "2 hours ago")
    }

    @Test
    fun `days and weeks from now are dates`() {
        p.day("2026-10-15", "in 3 days")
        p.day("2026-10-19", "in a week")
        // Two weeks on is after the clocks go back.
        p.at("2026-10-26T09:00+01:00", "in two weeks", hasTime = false)
    }

    @Test
    fun `weeks and weekends are runs of days`() {
        p.days("2026-10-19", "2026-10-25", "next week")
        p.days("2026-10-17", "2026-10-18", "this weekend")
    }

    @Test
    fun `number words one to twenty-four count hours`() {
        val words = listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen", "twenty", "twenty-one", "twenty two", "twenty-three", "twenty-four"
        )
        words.forEachIndexed { index, word ->
            val hours = index + 1
            val unit = if (hours == 1) "hour" else "hours"
            val found = p.one("in $word $unit")
            assertEquals("in $word $unit", MONDAY_10.plusHours(hours.toLong()), found.at)
        }
    }

    @Test
    fun `number words one to twelve as clock hours on a given day`() {
        // On a given day an hour that fits both halves of the clock is read in the morning.
        val expectedHours = listOf(13, 14, 15, 16, 17, 18, 7, 8, 9, 10, 11, 12)
        val words = listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve"
        )
        words.zip(expectedHours).forEach { (word, hour) ->
            val found = p.one("tomorrow at $word o'clock")
            assertEquals("tomorrow at $word o'clock", "2026-10-13", found.at.toLocalDate().toString())
            assertEquals("tomorrow at $word o'clock", hour, found.at.hour)
        }
    }

    @Test
    fun `greetings and look-alikes are not times`() {
        p.none("Good morning, everyone!")
        p.none("May I ask you something?")
        p.none("We march on.")
        p.none("At one point we stopped.")
        p.none("Prices went up at 3 percent.")
        p.none("Android 16 runs fine.")
        p.none("Version 2.5 is out.")
    }

    @Test
    fun `an ordinal alone is a day of the month only after on or a weekday`() {
        p.none("the first time we met")
        p.none("the second half")
        p.none("We got it on the first try.")
        p.day("2026-10-16", "on the 16th")
        p.day("2026-11-01", "on the first")
    }

    @Test
    fun `am after a clock is a m before an English day or date`() {
        p.at("2026-10-19T05:00+02:00", "flight at 5 am Monday")
        p.at("2026-10-16T06:00+02:00", "Wake me at 6 am Friday")
        p.at("2026-10-16T06:00+02:00", "at 6am Friday")
        p.at("2026-10-16T06:00+02:00", "6 am Friday")
        p.at("2026-10-16T06:00+02:00", "at 6 am 16 October")
        p.at("2026-10-16T06:00+02:00", "at 6 am on Friday")
        p.at("2026-10-16T06:00+02:00", "at 6 a.m. Friday")
        p.at("2026-10-16T06:00+02:00", "at 6 AM Friday")
        p.at("2026-10-13T06:00+02:00", "6 am tomorrow")
        // Written onto the number, "am" is a.m. even before a German day.
        p.at("2026-10-16T06:00+02:00", "Wecker 6am Freitag")
    }

    @Test
    fun `a number after a month name is no day when a clock or a count follows`() {
        p.at("2026-10-12T14:00+02:00", "Treffen mit April 14 Uhr")
        p.at("2026-10-12T17:00+02:00", "Call June 5 pm")
        p.none("June 5 people came.")
    }

    @Test
    fun `a range of clock times is one candidate from its start to its end`() {
        // Running at 10:00, so it is today's.
        p.range("2026-10-12T09:00+02:00", "2026-10-12T17:00+02:00", "from 9 am to 5 pm")
        val dentist = p.range("2026-10-16T10:00+02:00", "2026-10-16T12:00+02:00", "Friday from 10 to 12 dentist")
        assertEquals("Friday from 10 to 12", dentist.phrase)
        p.range("2026-10-13T09:00+02:00", "2026-10-13T11:00+02:00", "tomorrow between 9 and 11")
        p.range("2026-10-12T14:00+02:00", "2026-10-12T16:00+02:00", "between 2 and 4 pm")
        // Two bare numbers alone count anything.
        p.none("between 2 and 4")
        p.range("2026-10-12T19:00+02:00", "2026-10-12T21:00+02:00", "from 7 to 9 pm")
        p.range("2026-10-13T09:00+02:00", "2026-10-13T11:30+02:00", "tomorrow 9-11:30 am")
        p.range("2026-10-13T10:00+02:00", "2026-10-13T14:00+02:00", "tomorrow from 10 to 2 pm")
        // Across midnight the end is on the next day.
        p.range("2026-10-13T22:00+02:00", "2026-10-14T02:00+02:00", "tomorrow from 10 pm to 2 am")
    }

    @Test
    fun `march and may are verbs unless a date is around them`() {
        assertEquals("tomorrow", p.one("We march 10 km tomorrow").phrase)
        p.none("May 5 people join?")
        p.none("They march 3 times a year.")
        p.none("march 3")
        p.day("2027-03-03", "on march 3")
        p.day("2027-03-03", "March 3rd")
        p.day("2027-03-03", "march third")
        p.day("2027-05-05", "It is due May 5.")
        p.day("2027-03-05", "Friday, March 5", anchor = berlin("2027-02-27T10:00"))
    }

    @Test
    fun `a bare number before may or march in lower case is no date`() {
        // Once "from 2 to 4" is no range of clock times, its 4 is no day of May either.
        p.none("The meeting from 2 to 4 may be moved.")
        p.none("Between 3 and 5 may work for me.")
        p.none("I think 5 may work.")
        p.none("Then 3 march on.")
        // With its ending, with a capital or after "on" the month is one.
        p.day("2027-05-05", "the 5th of may")
        p.day("2027-05-05", "5 May")
        p.day("2027-05-05", "on 5 may")
    }

    @Test
    fun `a month name before a day is a date, August and a capital March or May too`() {
        p.at("2027-08-15T15:00+02:00", "The meeting is August 15 at 3 pm.")
        p.day("2027-08-15", "Book the venue for August 15.")
        p.at("2027-08-15T06:00+02:00", "Our flight is August 15 at 6 am.")
        p.day("2027-08-15", "Remind me August 15 to pay the bill.")
        val trip = p.all("The trip starts August 2 and ends August 9.")
        assertEquals(listOf("2027-08-02", "2027-08-09"), trip.map { it.at.toLocalDate().toString() })
        // With a capital, at the start of a sentence as well.
        p.day("2027-03-03", "March 3 is the deadline.")
        p.day("2027-05-05", "May 5 works for me.")
        p.none("May I ask you something?")
        // Jan is a name as often: before a clock time it stays one.
        p.at("2026-10-12T14:00+02:00", "Treffen mit Jan 14 Uhr")
    }

    @Test
    fun `the small hours tonight are after midnight`() {
        p.at("2026-10-13T02:00+02:00", "tonight at 2 am", anchor = berlin("2026-10-12T23:30"))
        p.at("2026-10-13T02:00+02:00", "at 2 tonight")
        // Said after midnight: the night that is already running.
        p.at("2026-10-13T01:00+02:00", "tonight at 1", anchor = berlin("2026-10-13T00:30"))
        p.at("2026-10-12T20:00+02:00", "at 8 tonight")
    }

    @Test
    fun `a price with a decimal point is not a time`() {
        p.none("The price rose at 2.50 dollars")
        p.at("2026-10-12T19:30+02:00", "at 7.30")
    }

    @Test
    fun `every Friday recurs and scores lower`() {
        assertEquals("every Friday", p.day("2026-10-16", "every Friday").phrase)
        assertTrue(p.confidence("every Friday") < p.confidence("on Friday"))
        assertTrue(p.confidence("each Monday") < p.confidence("on Tuesday"))
    }

    @Test
    fun `the phrase and its offsets are the matched words`() {
        val text = "Remember to call the bank tomorrow at 3 pm, please."
        val found = p.one(text)
        assertEquals("tomorrow at 3 pm", found.phrase)
        assertEquals(text.indexOf("tomorrow"), found.start)
        assertEquals(text.indexOf(","), found.end)
        assertTrue(found.confidence > 0f && found.confidence <= 1f)
    }
}
