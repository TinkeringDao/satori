/*
 * The phrases and expected values below are adapted from Duckling's time corpora,
 * Duckling/Time/DE/Corpus.hs and Duckling/Time/EN/Corpus.hs (https://github.com/facebook/duckling).
 * Only cases whose reading matches Satori's TimePolicy are used; the rest are left out.
 *
 * Duckling is licensed as follows:
 *
 * BSD License
 *
 * For Duckling software
 *
 * Copyright (c) 2016-present, Facebook, Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 *  * Neither the name Facebook nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.satori.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Duckling's corpus cases, at Duckling's reference time: Tuesday 12 February 2013, 04:30 at UTC-2.
 *
 * Where Duckling answers with an interval (a part of the day, a week), the test checks the
 * date and that the hour falls inside Duckling's interval. Known differences from Duckling, on
 * purpose: bare hours go to waking hours ("3:18" is 15:18, Duckling says 03:18 the next day), a
 * bare "10.10" without "am" is not taken as a date, "dieses Wochenende" starts on Saturday, not
 * Friday evening, and "march 3" in lower case is not a date without more around it ("on march
 * 3", "march 3rd"), because "march" is also a verb; Duckling's "march 3" is kept as "March 3".
 */
class DucklingCorpusTest {

    private val parser = RuleBasedTimeParser()
    private val reference: ZonedDateTime = ZonedDateTime.of(2013, 2, 12, 4, 30, 0, 0, ZoneOffset.ofHours(-2))

    private fun single(text: String, policy: TimePolicy = TimePolicy()): TimeCandidate {
        val found = parser.find(text, reference, policy)
        assertEquals("one expression in \"$text\", found $found", 1, found.size)
        return found.single()
    }

    private fun days(expected: String, vararg texts: String) {
        for (text in texts) {
            val found = single(text)
            assertEquals("day for \"$text\"", LocalDate.parse(expected), found.at.toLocalDate())
            assertEquals("hasTime for \"$text\"", false, found.hasTime)
        }
    }

    private fun times(expected: String, vararg texts: String) {
        for (text in texts) {
            val found = single(text)
            assertEquals("time for \"$text\"", LocalDateTime.parse(expected), found.at.toLocalDateTime())
            assertEquals("hasTime for \"$text\"", true, found.hasTime)
        }
    }

    private fun partOfDay(expectedDay: String, hours: IntRange, vararg texts: String) {
        for (text in texts) {
            val found = single(text)
            assertEquals("day for \"$text\"", LocalDate.parse(expectedDay), found.at.toLocalDate())
            assertTrue("hour ${found.at.hour} for \"$text\" in $hours", found.at.hour in hours)
        }
    }

    private fun nothing(vararg texts: String) {
        for (text in texts) {
            val found = parser.find(text, reference)
            assertTrue("no expression in \"$text\", found $found", found.isEmpty())
        }
    }

    @Test
    fun `German days`() {
        days("2013-02-12", "heute")
        days("2013-02-11", "gestern")
        days("2013-02-13", "morgen")
        days("2013-02-14", "übermorgen")
        days("2013-02-10", "vorgestern")
        days("2013-02-18", "montag", "diesen montag", "nächsten montag", "kommenden montag")
        days("2013-02-19", "dienstag", "nächsten dienstag")
        days("2013-02-14", "donnerstag")
        days("2013-02-15", "freitag")
        days("2013-02-16", "samstag")
        days("2013-02-17", "sonntag")
        days("2013-02-13", "nächsten mittwoch")
        days("2013-02-22", "übernächsten freitag")
        days("2013-02-10", "letzten sonntag", "sonntag letzte woche", "sonntag der letzten woche")
        days("2013-02-05", "letzten dienstag")
        days("2013-02-20", "mittwoch der nächsten woche", "mittwoch nächste woche")
        days("2013-02-27", "mittwoch in 2 wochen", "mittwoch in zwei wochen")
        days("2013-01-30", "mittwoch vor 2 wochen", "mittwoch vor zwei wochen")
        days("2013-02-11", "montag dieser woche")
        days("2013-02-12", "dienstag dieser woche")
        days("2013-02-13", "mittwoch dieser woche")
    }

    @Test
    fun `German dates`() {
        days("2013-03-01", "1 märz", "erster märz")
        days("2013-03-03", "märz 3")
        days("2015-03-03", "märz 3 2015")
        days("2013-02-15", "am 15ten", "15. februar", "februar 15", "15te februar", "15.2.", "am 15.2.")
        days("1974-10-31", "31.10.1974", "31.10.74")
        days("2015-04-14", "14 april 2015", "April 14, 2015")
        days("2013-12-10", "10.12.")
        days("2013-10-10", "am 10.10.", "am 10.10")
        days("1980-05-30", "30. Mai 1980")
        days("2013-12-05", "fünfter Dezember")
        days("2013-12-30", "dreißigster Dezember", "dreissigster Dezember")
        days("2013-12-04", "am vierten Dezember", "der vierte Dezember")
        days("2014-07-18", "Freitag, 18.07.14", "Freitag, den 18.07.2014", "Freitag, der 18. Juli 14")
    }

    @Test
    fun `German weeks, months and offsets in days`() {
        days("2013-02-18", "nächste woche")
        days("2013-02-25", "übernächste woche")
        days("2013-02-04", "letzte woche")
        days("2013-03-01", "nächsten monat")
        days("2013-02-19", "in 7 tagen", "in 1 woche", "in einer woche")
        days("2013-02-05", "vor 7 tagen", "vor einer woche")
        days("2013-01-29", "vor 14 tagen", "vor zwei wochen")
        days("2013-01-22", "vor drei wochen")
        days("2012-11-12", "vor drei monaten")
        days("2013-02-26", "binnen 2 wochen", "innerhalb von 2 wochen")
    }

    @Test
    fun `German clock times`() {
        times("2013-02-12T15:00", "um 3", "3 uhr", "um drei", "um 15", "um 15 uhr", "15 uhr", "um 3 am nachmittag")
        times("2013-02-12T15:00", "gegen 15 uhr", "so um 15 uhr", "ungefähr um 15 uhr", "circa 15 uhr")
        times("2013-02-13T17:00", "pünktlich um 17 uhr morgen")
        times("2013-02-12T15:15", "um viertel nach 3", "viertel nach drei Uhr", "15:15")
        times("2013-02-12T15:20", "um 20 nach 3", "zwanzig nach 3", "15:20")
        times("2013-02-12T15:30", "um halb 4", "halb vier uhr nachmittags", "halb vier am nachmittag", "15:30")
        times("2013-02-12T11:45", "viertel vor 12", "11:45", "15 minuten vor 12")
        times("2013-02-12T20:00", "8 uhr am abend", "heute abend um 20 Uhr", "heute um 20:00")
        times("2013-02-16T09:00", "am samstag um 9 Uhr")
        times("2013-02-12T14:00", "heute um 14 Uhr", "um 2", "nach 14 Uhr", "ab 14Uhr", "bis 2 Uhr nachmittag")
        times("2013-02-13T15:00", "morgen um 15 Uhr", "morgen so um 15 Uhr")
        times("2013-02-12T11:00", "bis 11 uhr", "vor 11 uhr", "spätestens 11 uhr")
        times("2013-02-12T13:30", "um 13:30 am nachmittag", "nachmittags um 1 uhr 30", "13:30")
        times("2013-02-12T10:30", "10:30")
        times("2013-02-12T10:10", "um 10.10")
        times("2013-04-01T18:00", "01.04. gegen 18Uhr")
    }

    @Test
    fun `German durations`() {
        times("2013-02-12T04:31", "in einer minute")
        times("2013-02-12T04:32", "in 2 minuten")
        times("2013-02-12T04:45", "in 15 minuten")
        times("2013-02-12T05:30", "in 60 minuten", "in einer stunde")
        times("2013-02-12T05:00", "in einer halben stunde", "in 30 minuten")
        times("2013-02-12T07:00", "in 2,5 stunden", "in zwei ein halb stunden")
        times("2013-02-12T06:30", "in zwei stunden", "in ein paar stunden")
        times("2013-02-13T04:30", "in 24 stunden")
    }

    @Test
    fun `German parts of the day`() {
        partOfDay("2013-02-12", 18..23, "heute abend", "am abend")
        partOfDay("2013-02-13", 18..23, "morgen abend")
        partOfDay("2013-02-13", 12..13, "morgen mittag", "morgen zu mittag")
        partOfDay("2013-02-11", 18..23, "gestern abend")
        partOfDay("2013-02-18", 3..11, "montag morgens")
        partOfDay("2013-02-15", 3..11, "morgens am 15. februar", "15. februar morgens", "am morgen des 15. februar")
        partOfDay("2013-02-12", 12..18, "am nachmittag")
    }

    @Test
    fun `German negatives`() {
        nothing("ein Hotel", "ein Angebot", "nächsten 5", "so")
    }

    @Test
    fun `English days and dates`() {
        days("2013-02-12", "today")
        days("2013-02-11", "yesterday")
        days("2013-02-13", "tomorrow")
        days("2013-02-14", "the day after tomorrow")
        days("2013-02-10", "the day before yesterday")
        days("2013-02-18", "monday", "this monday", "next monday")
        days("2013-02-19", "tuesday", "next tuesday", "Tuesday the 19th", "Tuesday 19th")
        days("2013-02-14", "thursday")
        days("2013-02-15", "friday")
        days("2013-02-16", "saturday")
        days("2013-02-17", "sunday")
        days("2013-02-22", "friday after next")
        days("2013-02-10", "last sunday")
        days("2013-02-05", "last tuesday")
        days("2013-02-20", "wednesday of next week", "wednesday next week")
        days("2013-02-11", "monday of this week")
        days("2013-02-12", "tuesday of this week")
        days("2013-02-13", "wednesday of this week")
        days("2013-03-01", "the 1st of march", "first of march", "the first of march", "march first")
        days("2013-03-02", "the 2nd of march", "second of march", "the second of march")
        days("2013-03-03", "the third of march", "March 3")
        days("2015-03-03", "march 3 2015", "march 3rd 2015", "march third 2015")
        days(
            "2013-02-15", "on the 15th", "the 15th of february", "15 of february", "february the 15th",
            "february 15", "15th february", "February 15"
        )
        days("2015-04-14", "April 14, 2015")
        days("2013-08-08", "Aug 8")
    }

    @Test
    fun `English next weekday when it means the following week`() {
        // Duckling's English rules read "next wednesday" on a Tuesday as the following week.
        val found = single("next wednesday", TimePolicy(nextWeekdayMeansFollowingWeek = true))
        assertEquals(LocalDate.parse("2013-02-20"), found.at.toLocalDate())
    }

    @Test
    fun `English weeks, months and offsets in days`() {
        days("2013-02-18", "next week")
        days("2013-02-04", "last week")
        days("2013-03-01", "next month")
        days("2013-02-13", "in a day", "a day from now")
        days("2013-02-19", "in 7 days", "in 1 week", "in a week")
        days("2013-02-05", "7 days ago", "a week ago")
        days("2013-01-29", "14 days ago", "a fortnight ago")
        days("2013-01-22", "three weeks ago")
        days("2012-11-12", "three months ago")
    }

    @Test
    fun `English clock times`() {
        times("2013-02-13T03:00", "at 3am", "at 3 AM", "at three am", "3 in the morning", "at 3 in the morning")
        times("2013-02-12T10:00", "this morning at 10am")
        times("2013-02-12T15:00", "at 3pm", "3PM", "3pm", "3 o'clock in the afternoon", "at about 3pm")
        times("2013-02-12T15:15", "a quarter past 3pm", "3:15 in the afternoon", "15:15", "3:15pm", "at 3 15")
        times("2013-02-12T15:20", "3:20 in the afternoon", "at three twenty", "this afternoon at 3:20", "20 minutes past 3pm")
        times("2013-02-12T15:30", "at half past three pm", "half past 3 pm", "15:30", "3:30pm", "3:30")
        times("2013-02-12T12:15", "12:15", "a quarter past noon")
        times("2013-02-12T11:45", "a quarter to noon", "11:45am")
        times(
            "2013-02-12T20:00", "8 tonight", "tonight at 8 o'clock", "eight tonight", "8 this evening",
            "at 8 in the evening", "in the evening at eight"
        )
        times("2013-02-16T09:00", "at 9am on Saturday", "Saturday morning at 9")
        times("2013-02-12T14:00", "today at 2pm", "at 2pm", "this afternoon at 2")
        times("2013-02-13T15:00", "3pm tomorrow")
        times("2013-02-12T13:30", "at 1:30pm", "1:30pm")
        times("2013-02-12T10:30", "10:30")
        times("2013-02-12T12:00", "at 12pm")
        times("2013-02-12T14:00", "by 2:00pm")
        times("2013-02-14T17:00", "day after tomorrow 5pm")
        times("2013-02-10T08:00", "day before yesterday 8am")
        times("2013-02-19T17:00", "in 7 days at 5pm")
    }

    @Test
    fun `English durations`() {
        times("2013-02-12T04:31", "in a minute", "in one minute")
        times("2013-02-12T04:32", "in 2 minutes", "2 minutes from now", "in a couple of minutes")
        times("2013-02-12T04:33", "in three minutes", "in a few minutes")
        times("2013-02-12T05:30", "in 60 minutes", "in one hour")
        times("2013-02-12T04:45", "in a quarter of an hour", "in 15 minutes")
        times("2013-02-12T05:00", "in half an hour", "in about half an hour")
        times("2013-02-12T05:15", "in three-quarters of an hour")
        times("2013-02-12T07:00", "in 2.5 hours")
        times("2013-02-12T06:30", "in a couple hours", "in a couple of hours")
        times("2013-02-12T07:30", "in a few hours")
        times("2013-02-13T04:30", "in 24 hours")
    }

    @Test
    fun `English parts of the day`() {
        partOfDay("2013-02-12", 18..23, "this evening", "tonight")
        partOfDay("2013-02-13", 18..23, "tomorrow evening")
        partOfDay("2013-02-11", 18..23, "last night", "yesterday evening")
        partOfDay("2013-02-12", 12..18, "in the afternoon")
    }

    @Test
    fun `English negatives`() {
        nothing(
            "laughing out loud", "1 adult", "we are separated", "25", "this is the one", "this one",
            "this past one", "at single", "at a couple of", "at pairs", "at a few", "at dozens",
            "single o'clock", "dozens o'clock", "Rat 6", "rat 6", "3 30", "three twenty",
            "at 650.650.6500", "at 650-650-6500", "two sixty a m", "Pay ABC 2000", "4a", "4a.",
            "A4 A5", "palm", "two three"
        )
    }
}
