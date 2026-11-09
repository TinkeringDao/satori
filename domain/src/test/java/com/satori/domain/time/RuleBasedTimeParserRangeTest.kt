package com.satori.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ranges of clock times and runs of days, and how exactly each candidate names its moment.
 * Unless a test says otherwise the anchor is Monday 12 October 2026, 10:00 in Berlin.
 */
class RuleBasedTimeParserRangeTest {

    private val p = Probe()

    // --- Precision --------------------------------------------------------------------------

    @Test
    fun `precision says how exactly the words name the moment`() {
        assertEquals(TimePrecision.EXACT, p.one("morgen um 9").precision)
        assertEquals(TimePrecision.EXACT, p.one("in 2 Stunden").precision)
        assertEquals(TimePrecision.EXACT, p.one("at noon").precision)
        assertEquals(TimePrecision.DAY, p.one("am Freitag").precision)
        assertEquals(TimePrecision.DAY, p.one("in 3 Tagen").precision)
        assertEquals(TimePrecision.PART_OF_DAY, p.one("Freitagabend").precision)
        assertEquals(TimePrecision.PART_OF_DAY, p.one("tonight").precision)
        assertEquals(TimePrecision.DAYS_RANGE, p.one("nächste Woche").precision)
        assertNull(p.one("am Freitag").endAt)
        assertNull(p.one("morgen um 9").endAt)
    }

    @Test
    fun `a part of the day alone invents no hour`() {
        p.partOfDay("2026-10-16T19:00+02:00", "Freitagabend")
        p.partOfDay("2026-10-13T08:00+02:00", "morgens")
        p.partOfDay("2026-10-13T08:00+02:00", "Ich gehe morgens joggen")
        p.partOfDay("2026-10-12T19:00+02:00", "tonight")
        p.partOfDay("2026-10-11T22:00+02:00", "last night")
        // Once a clock time is said, the time is exact.
        p.at("2026-10-16T20:00+02:00", "Freitagabend um 8")
    }

    @Test
    fun `a clock time later in the sentence takes the day and the part of a part-of-day expression`() {
        val found = p.all("Freitagabend gehen wir um 8 essen.")
        assertEquals(2, found.size)
        assertEquals(TimePrecision.PART_OF_DAY, found[0].precision)
        assertEquals("2026-10-16T20:00+02:00", found[1].at.toOffsetDateTime().toString())
        assertEquals(TimePrecision.EXACT, found[1].precision)
    }

    // --- Runs of days ------------------------------------------------------------------------

    @Test
    fun `weeks, weekends and months are runs of days`() {
        p.days("2026-10-19", "2026-10-25", "nächste Woche")
        p.days("2026-10-19", "2026-10-25", "next week")
        p.days("2026-10-05", "2026-10-11", "letzte Woche")
        p.days("2026-10-26", "2026-11-01", "übernächste Woche")
        p.days("2026-10-17", "2026-10-18", "am Wochenende")
        p.days("2026-10-17", "2026-10-18", "next weekend")
        p.days("2026-10-24", "2026-10-25", "next weekend", policy = TimePolicy(nextWeekdayMeansFollowingWeek = true))
        p.days("2026-11-01", "2026-11-30", "nächsten Monat")
        p.days("2026-10-21", "2026-10-31", "end of the month", "2026-10-31")
        p.days("2026-10-21", "2026-10-31", "Monatsende", "2026-10-31")
        p.days("2026-10-19", "2026-10-20", "Anfang nächster Woche")
        p.days("2026-10-20", "2026-10-22", "Mitte nächster Woche", "2026-10-21")
        p.days("2026-10-22", "2026-10-23", "Ende nächster Woche", "2026-10-23")
    }

    @Test
    fun `a run of days means its likely day, from its first day on`() {
        // A deadline at the end of the month is its last day, the end of the week Friday, the
        // middle of the month the 15th.
        val rent = p.days(
            "2026-10-21", "2026-10-31", "Die Miete muss bis Ende des Monats überwiesen sein.", "2026-10-31",
            anchor = berlin("2026-10-14T10:41")
        )
        assertEquals("Ende des Monats", rent.phrase)
        p.days("2026-10-15", "2026-10-16", "Bis Ende der Woche muss ich die Steuer machen.", "2026-10-16", anchor = berlin("2026-10-15T10:41"))
        p.days("2026-10-12", "2026-10-20", "Mitte des Monats muss ich den Vertrag kündigen.", "2026-10-15", anchor = berlin("2026-10-12T10:41"))
        p.days("2026-10-15", "2026-10-16", "by the end of the week", "2026-10-16")
        p.days("2026-10-13", "2026-10-15", "Mitte der Woche", "2026-10-14")
    }

    @Test
    fun `a run of days that has begun starts today`() {
        p.days("2026-10-12", "2026-10-18", "diese Woche")
        p.days("2026-10-14", "2026-10-18", "this week", anchor = berlin("2026-10-14T10:00"))
        p.days("2026-10-12", "2026-10-31", "diesen Monat")
        p.days("2026-10-18", "2026-10-18", "am Wochenende", anchor = berlin("2026-10-18T10:00"))
        p.days("2026-10-16", "2026-10-16", "Ende der Woche", anchor = berlin("2026-10-16T10:00"))
        p.days("2026-10-28", "2026-10-31", "Ende des Monats", "2026-10-31", anchor = berlin("2026-10-28T10:00"))
        // Once the likely day has passed, the run means today.
        p.days("2026-10-17", "2026-10-20", "Mitte des Monats", "2026-10-17", anchor = berlin("2026-10-17T10:00"))
    }

    @Test
    fun `a run of days names no one day, so it is tentative`() {
        assertTrue(p.confidence("am Wochenende") < p.confidence("am Samstag"))
        for (text in listOf("nächste Woche", "am Wochenende", "Ende des Monats", "next week", "end of the week")) {
            assertTrue("$text: ${p.confidence(text)}", p.confidence(text) < 0.5f)
        }
    }

    @Test
    fun `a clock time in a run of days is on its likely day, still ahead`() {
        p.at("2026-10-19T10:00+02:00", "nächste Woche um 10")
        p.at("2026-10-17T09:00+02:00", "am Wochenende um 9")
        // The end of a month or week is its last working day, the middle its middle.
        assertEquals("2026-10-31T10:00+01:00", p.all("Ende des Monats um 10 ist Abgabe.")[0].at.toOffsetDateTime().toString())
        p.at("2026-10-16T09:00+02:00", "Ende der Woche um 9")
        p.at("2026-10-21T10:00+02:00", "Mitte nächster Woche um 10")
        val borrowed = p.all("Ende des Monats ist Abgabe, um 10 Uhr.")
        assertEquals("2026-10-31T10:00+01:00", borrowed.last().at.toOffsetDateTime().toString())
        // A part of the day borrows the likely day too.
        val evening = p.all("Ende des Monats gehen wir abends essen.")
        assertEquals("2026-10-31T19:00+01:00", evening.last().at.toOffsetDateTime().toString())
        assertEquals(TimePrecision.PART_OF_DAY, evening.last().precision)
        // On Sunday at 10:00 the weekend's 09:00 has passed, today and for good.
        p.at("2026-10-11T21:00+02:00", "am Wochenende um 9", anchor = SUNDAY_10)
        // On Saturday at 10:00 Saturday's 08:30 has passed; Sunday's is still ahead.
        p.at("2026-10-18T08:30+02:00", "am Wochenende um 08:30", anchor = berlin("2026-10-17T10:00"))
    }

    // --- A bare time in a sentence with a run of days -----------------------------------------

    @Test
    fun `a bare time takes the first day of a run of days said in the same sentence`() {
        val weekend = p.all("Next weekend we leave at 6 pm.")
        assertEquals(2, weekend.size)
        assertEquals("2026-10-17T18:00+02:00", weekend[1].at.toOffsetDateTime().toString())
        assertTrue("the day is a guess: ${weekend[1].confidence}", weekend[1].confidence < 0.5f)

        val week = p.all("Nächste Woche treffen wir uns um 10.")
        assertEquals(2, week.size)
        assertEquals("2026-10-19T10:00+02:00", week[1].at.toOffsetDateTime().toString())
        assertTrue(week[1].confidence < 0.5f)

        val nine = p.all("Am Wochenende muss ich um 9 los.")
        assertEquals(2, nine.size)
        assertEquals("2026-10-17T09:00+02:00", nine[1].at.toOffsetDateTime().toString())
        assertTrue(nine[1].confidence < 0.5f)
    }

    @Test
    fun `an exact day in the sentence wins over a run of days`() {
        // "nächste Woche" is nearer to "um 10", but "Morgen" names an exact day.
        val found = p.all("Morgen, nicht erst nächste Woche, treffen wir uns um 10.")
        assertEquals(3, found.size)
        assertEquals("2026-10-13T10:00+02:00", found.last().at.toOffsetDateTime().toString())
        // A span of dates is a run of days too.
        val span = p.all("Morgen, nicht erst vom 7. bis 9. Dezember, treffen wir uns um 10.")
        assertEquals(3, span.size)
        assertEquals("2026-10-13T10:00+02:00", span.last().at.toOffsetDateTime().toString())
    }

    // --- Ranges of clock times -------------------------------------------------------------------

    @Test
    fun `a range of clock times that is running is today's`() {
        p.range("2026-10-12T09:00+02:00", "2026-10-12T11:00+02:00", "zwischen 9 und 11 Uhr")
        p.range("2026-10-12T09:00+02:00", "2026-10-12T11:00+02:00", "between 9 and 11 am")
        // One that ends at this very minute is not over yet, on a named day and on a day of a
        // run of days too.
        p.range("2026-10-12T09:00+02:00", "2026-10-12T10:00+02:00", "zwischen 9 und 10 Uhr")
        p.range("2026-10-12T09:00+02:00", "2026-10-12T10:00+02:00", "heute zwischen 9 und 10 Uhr")
        p.range("2026-10-17T09:00+02:00", "2026-10-17T10:00+02:00", "am Wochenende von 09:00 bis 10:00 Uhr", anchor = berlin("2026-10-17T10:00"))
        // Once it is over, it is tomorrow's, or the evening's when that fits.
        p.range("2026-10-13T09:00+02:00", "2026-10-13T11:00+02:00", "zwischen 9 und 11 Uhr", anchor = berlin("2026-10-12T11:30"))
        p.range("2026-10-12T20:00+02:00", "2026-10-12T22:00+02:00", "heute von 8 bis 10", anchor = berlin("2026-10-12T10:30"))
    }

    @Test
    fun `a cue before a bare start makes one range with its end`() {
        // Not "um 9" at 21:00 and "10 Uhr" apart: one range, which ends right now.
        val um = p.range("2026-10-12T09:00+02:00", "2026-10-12T10:00+02:00", "um 9 bis 10 Uhr")
        val gegen = p.range("2026-10-12T09:00+02:00", "2026-10-12T10:00+02:00", "gegen 9 bis 10 Uhr")
        assertTrue("gegen is approximate", gegen.confidence < um.confidence)
        val visit = p.range("2026-10-12T09:00+02:00", "2026-10-12T10:00+02:00", "so gegen 9 bis 10 Uhr komme ich vorbei")
        assertEquals("so gegen 9 bis 10 Uhr", visit.phrase)
        p.range("2026-10-13T09:00+02:00", "2026-10-13T10:00+02:00", "morgen um 9 bis 10 Uhr")
        p.range("2026-10-12T08:00+02:00", "2026-10-12T10:00+02:00", "um 8 bis 10 Uhr", anchor = berlin("2026-10-12T07:00"))
        // "ten to eleven" after a cue is still a clock time, 10:50.
        p.at("2026-10-12T10:50+02:00", "at ten to eleven")
        p.at("2026-10-12T10:50+02:00", "at 10 to 11")
    }

    // --- Bare numbers ----------------------------------------------------------------------------

    @Test
    fun `two bare numbers alone are no range of clock times`() {
        p.none("Auf einer Skala von 1 bis 5")
        p.none("Wir müssen die Punkte von 1 bis 3 besprechen.")
        p.none("zwischen 2 und 3 Tassen Kaffee")
        p.none("Kinder ab 3 bis 6")
        p.none("We need between 5 and 10 volunteers.")
        p.none("between 9 and 11")
        p.none("von 8 bis 10")
        // An hour after "um" counts like the end of its range.
        p.none("Die Preise steigen um 5 bis 10 Prozent.")
        // The day after them stays a day of its own.
        val items = p.day("2026-10-13", "We need to go through items from 2 to 4 tomorrow.")
        assertEquals("tomorrow", items.phrase)
    }

    @Test
    fun `a marked end, a cue or a part of the day makes two numbers clock times`() {
        p.range("2026-10-12T10:00+02:00", "2026-10-12T12:00+02:00", "von 10 bis 12 Uhr")
        p.range("2026-10-12T14:00+02:00", "2026-10-12T16:00+02:00", "between 2 and 4 pm")
        p.range("2026-10-12T20:30+02:00", "2026-10-12T22:00+02:00", "von halb 9 bis 10", anchor = berlin("2026-10-12T11:00"))
        p.range("2026-10-12T19:00+02:00", "2026-10-12T21:00+02:00", "abends von 7 bis 9")
        p.range("2026-10-12T19:00+02:00", "2026-10-12T21:00+02:00", "von 7 bis 9 abends")
        p.range("2026-10-12T19:00+02:00", "2026-10-12T21:00+02:00", "from 7 to 9 tonight")
        p.range("2026-10-12T11:00+02:00", "2026-10-12T12:00+02:00", "um 11 bis 12")
    }

    @Test
    fun `two bare numbers right after a day are a tentative range`() {
        val friday = p.range("2026-10-16T10:00+02:00", "2026-10-16T12:00+02:00", "Friday from 10 to 12")
        assertTrue("${friday.confidence}", friday.confidence < 0.5f)
        val tomorrow = p.range("2026-10-13T10:00+02:00", "2026-10-13T12:00+02:00", "morgen von 10 bis 12")
        assertTrue("${tomorrow.confidence}", tomorrow.confidence < 0.5f)
        // Even when only a 24-hour reading fits them.
        val afternoon = p.range("2026-10-16T13:00+02:00", "2026-10-16T15:00+02:00", "Freitag von 13 bis 15")
        assertTrue("${afternoon.confidence}", afternoon.confidence < 0.5f)
        // An end marked as a clock time is firm.
        assertTrue(p.confidence("Freitag von 10 bis 12 Uhr") > 0.5f)
    }

    @Test
    fun `two bare numbers after a day without von, from, zwischen, between or ab count something`() {
        // The day stays a day of its own, and the numbers stay in the todo's words.
        assertEquals("Am Montag", p.day("2026-10-19", "Am Montag 2 bis 3 Folien vorbereiten.").phrase)
        assertEquals("Friday", p.day("2026-10-16", "Friday 2 to 3 slides for the board.").phrase)
        assertEquals("Morgen", p.day("2026-10-13", "Morgen 3 bis 4 Angebote einholen.").phrase)
        assertEquals("Tomorrow", p.day("2026-10-13", "Tomorrow 3 to 4 candidates to interview.").phrase)
        assertEquals("Freitag", p.day("2026-10-16", "Freitag 2-3 Folien").phrase)
        // A run of days stays a run.
        assertEquals("Next week", p.days("2026-10-19", "2026-10-25", "Next week 3 to 4 sessions").phrase)
        // An end marked as a clock time still makes them clock times.
        p.range("2026-10-16T10:00+02:00", "2026-10-16T12:00+02:00", "Freitag 10-12 Uhr")
    }

    @Test
    fun `numbers after a word they count are no clock times, also before a part of the day`() {
        assertEquals("tonight", p.partOfDay("2026-10-12T19:00+02:00", "Read chapters 3 to 5 tonight.").phrase)
        assertEquals("heute Abend", p.partOfDay("2026-10-12T19:00+02:00", "Lies die Kapitel 3 bis 5 heute Abend.").phrase)
        assertEquals("heute Abend", p.partOfDay("2026-10-12T19:00+02:00", "Lies Seite 5 heute Abend.").phrase)
        // Without such a word they are a range of the evening.
        p.range("2026-10-12T19:00+02:00", "2026-10-12T21:00+02:00", "We meet 7 to 9 tonight.")
        // "mal", "time" and "of" before a number count nothing.
        p.range("2026-10-12T09:00+02:00", "2026-10-12T10:00+02:00", "Komm mal 9 bis 10 Uhr vorbei.")
        p.range("2026-10-12T19:00+02:00", "2026-10-12T21:00+02:00", "This time 7 to 9 pm works.")
        p.range("2026-10-12T09:00+02:00", "2026-10-12T17:00+02:00", "Call during the hours of 9 to 5 pm.")
    }

    @Test
    fun `and after a clock time starts a clause of its own, not a range of amounts`() {
        assertEquals("at 8", p.at("2026-10-12T20:00+02:00", "Dinner is at 8 and 10 people are coming.").phrase)
        p.at("2026-10-12T19:00+02:00", "We meet at 7 and 20 people will join.")
        assertEquals("Morgen um 8", p.at("2026-10-13T08:00+02:00", "Morgen um 8 und 10 Leute kommen mit.").phrase)
        // "bis" does start one.
        p.none("Die Preise steigen um 5 bis 10 Prozent.")
    }

    @Test
    fun `a range of bare numbers takes no day after it`() {
        val found = p.all("Am Freitag von 2 bis 4, morgen dann der Rest.")
        assertEquals(2, found.size)
        assertEquals("Am Freitag von 2 bis 4", found[0].phrase)
        assertEquals("2026-10-16T14:00+02:00", found[0].at.toOffsetDateTime().toString())
        assertEquals("2026-10-16T16:00+02:00", found[0].endAt?.toOffsetDateTime()?.toString())
        assertEquals("morgen", found[1].phrase)
        assertEquals("2026-10-13", found[1].at.toLocalDate().toString())
    }

    @Test
    fun `numbers before a month are a span of days, not clock times`() {
        val conference = p.days("2026-12-07", "2026-12-09", "Die Konferenz ist von 7 bis 9. Dezember.")
        assertEquals("von 7 bis 9. Dezember", conference.phrase)
        assertTrue("${conference.confidence}", conference.confidence < 0.5f)
        p.days("2026-11-03", "2026-11-05", "Urlaub von 3 bis 5. November")
        p.days("2026-11-03", "2026-11-05", "from 3 to 5 November")
        p.days("2026-11-03", "2026-11-05", "between 3 and 5 November")
        p.days("2026-11-03", "2026-11-05", "zwischen 3 und 5. November")
        p.days("2026-11-03", "2026-11-05", "zwischen dem 3. und dem 5. November")
        p.days("2026-11-03", "2026-11-05", "zwischen 3 bis 5. November")
        p.days("2026-12-07", "2026-12-09", "vom 7. bis zum 9. Dezember")
        p.days("2026-11-03", "2026-11-05", "3.-5. Nov.")
        p.days("2026-11-03", "2026-11-05", "3-5 November")
        p.days("2026-11-03", "2026-11-05", "from the 3rd to the 5th of November")
        // Across the end of a month.
        p.days("2026-10-28", "2026-11-02", "vom 28. bis 2. November")
        // A span that has begun starts today; one that is over is next year's.
        p.days("2026-12-08", "2026-12-09", "vom 7. bis 9. Dezember", anchor = berlin("2026-12-08T10:00"))
        p.days("2027-12-07", "2027-12-09", "vom 7. bis 9. Dezember", anchor = berlin("2026-12-10T10:00"))
        // A time said with the span is on its first day.
        p.at("2026-12-07T10:00+01:00", "vom 7. bis 9. Dezember um 10 Uhr")
        // A first day the month before doesn't have leaves the date alone.
        p.day("2027-03-02", "vom 30. bis 2. März")
        // A verb is no month: here two hours after a day.
        p.range("2026-10-16T15:00+02:00", "2026-10-16T17:00+02:00", "Friday between 3 and 5 may work")
    }

    @Test
    fun `English writes no dot after a day, so there a dot before a month ends the sentence`() {
        val tomorrow = p.range("2026-10-13T14:00+02:00", "2026-10-13T16:00+02:00", "Tomorrow between 2 and 4. May works too.")
        assertEquals("Tomorrow between 2 and 4", tomorrow.phrase)
        p.none("I work from 9 to 5. May I ask you something?")
        p.none("The shop is open from 9 to 6. March is busy.")
        // November is German too, but not after "to".
        p.none("I work from 9 to 5. November is busy.")
        p.range("2026-10-13T09:00+02:00", "2026-10-13T17:00+02:00", "Tomorrow from 9 to 5. November is busy.")
        p.none("It is due 5. May.")
        // German writes it.
        p.days("2026-11-03", "2026-11-05", "Urlaub von 3 bis 5. November")
        p.day("2026-11-05", "bis 5. November")
    }

    @Test
    fun `after heute or morgen the numbers of von and bis are hours even before a dot and a month`() {
        val work = p.range("2026-10-12T09:00+02:00", "2026-10-12T17:00+02:00", "Ich arbeite heute von 9 bis 17. März ist bald vorbei.")
        assertEquals("heute von 9 bis 17", work.phrase)
        assertTrue("${work.confidence}", work.confidence < 0.5f)
    }

    @Test
    fun `a day of the month after a clock time is no end of a range of clock times`() {
        val found = p.all("Die Straße ist von 8 Uhr bis 15. November gesperrt.")
        assertEquals(listOf("8 Uhr", "15. November"), found.map { it.phrase })
        assertEquals("2026-11-15", found[1].at.toLocalDate().toString())
        assertNull(found[0].endAt)
    }

    @Test
    fun `the start of a range is read on the end's half of the day or clock`() {
        p.range("2026-10-12T18:00+02:00", "2026-10-12T20:00+02:00", "from 6 to 8 pm")
        p.range("2026-10-12T13:00+02:00", "2026-10-12T15:00+02:00", "from 1 to 3 pm")
        p.range("2026-10-13T11:00+02:00", "2026-10-13T13:00+02:00", "tomorrow from 11 to 1 pm")
        p.range("2026-10-13T09:00+02:00", "2026-10-13T17:00+02:00", "morgen von 9 bis 17 Uhr")
        // A 24-hour end puts the start on the same clock.
        p.range("2026-10-13T06:00+02:00", "2026-10-13T22:00+02:00", "morgen von 6 bis 22 Uhr")
        p.range("2026-10-13T14:00+02:00", "2026-10-13T16:00+02:00", "morgen von 2 bis 4")
        p.range("2026-10-13T10:00+02:00", "2026-10-13T12:00+02:00", "morgen von 10 bis 12")
        p.range("2026-10-13T11:00+02:00", "2026-10-13T13:00+02:00", "morgen von 11 bis 1")
    }

    @Test
    fun `a range may end after midnight`() {
        p.range("2026-10-12T22:00+02:00", "2026-10-13T02:00+02:00", "von 22 bis 2 Uhr")
        p.range("2026-10-12T22:00+02:00", "2026-10-13T00:00+02:00", "von 22 Uhr bis Mitternacht")
    }

    @Test
    fun `a range said with the evening or the night is one stretch of it`() {
        // Not 04:00 the next morning to 18:00.
        p.range("2026-10-12T16:00+02:00", "2026-10-12T18:00+02:00", "tonight from 4 to 6")
        p.range("2026-10-12T16:00+02:00", "2026-10-12T18:00+02:00", "heute Abend von 4 bis 6")
        p.range("2026-10-13T01:00+02:00", "2026-10-13T03:00+02:00", "heute Nacht von 1 bis 3")
        // A night runs into the morning.
        p.range("2026-10-13T03:00+02:00", "2026-10-13T07:00+02:00", "heute Nacht von 3 bis 7")
        p.range("2026-10-12T22:00+02:00", "2026-10-13T06:00+02:00", "heute Nacht von 10 bis 6")
        p.range("2026-10-12T23:00+02:00", "2026-10-13T01:00+02:00", "tonight from 11 to 1")
        p.range("2026-10-12T19:00+02:00", "2026-10-12T21:00+02:00", "heute Abend von 7 bis 9")
        // An hour said with am, pm or on the 24-hour clock stays where it was said.
        p.range("2026-10-13T02:00+02:00", "2026-10-13T06:00+02:00", "tonight from 2 am to 6")
        // Longer than half a day it is no range of that part of the day, only the part.
        p.partOfDay("2026-10-12T19:00+02:00", "tonight from 2 am to 6 pm")
        p.partOfDay("2026-10-12T19:00+02:00", "abends von 9 bis 19 Uhr")
    }

    @Test
    fun `two ends at the same moment are no range`() {
        p.none("von Mitternacht bis 0 Uhr")
        p.none("between 24 to 0")
        // The day said with them stays.
        p.day("2026-10-13", "morgen von Mitternacht bis 0 Uhr")
    }

    @Test
    fun `a range of clock times lends its day to a later time`() {
        val found = p.all("Am Freitag von 10 bis 12 Uhr Zahnarzt, danach um 14 Uhr Friseur.")
        assertEquals(2, found.size)
        assertEquals("2026-10-16T14:00+02:00", found[1].at.toOffsetDateTime().toString())
    }

    @Test
    fun `a range without a day takes the day said in the sentence`() {
        val found = p.all("Morgen bin ich von 9 bis 11 Uhr beim Arzt.")
        assertEquals(2, found.size)
        assertEquals("2026-10-13T09:00+02:00", found[1].at.toOffsetDateTime().toString())
        assertEquals("2026-10-13T11:00+02:00", found[1].endAt?.toOffsetDateTime()?.toString())
    }
}
