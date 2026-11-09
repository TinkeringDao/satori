package com.satori.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * German expressions. Unless a test says otherwise the anchor is Monday 12 October 2026, 10:00
 * in Berlin (CEST, +02:00); Friday is the 16th, and the clocks go back on Sunday the 25th.
 */
class RuleBasedTimeParserGermanTest {

    private val p = Probe()

    @Test
    fun `relative days are dates without a time`() {
        p.day("2026-10-12", "Ich mache das heute.")
        assertEquals("morgen", p.day("2026-10-13", "Ich gehe morgen zum Arzt.").phrase)
        p.day("2026-10-14", "übermorgen")
        p.day("2026-10-14", "uebermorgen")
        p.day("2026-10-11", "gestern")
        p.day("2026-10-10", "vorgestern")
    }

    @Test
    fun `clock times on a given day`() {
        p.at("2026-10-13T09:00+02:00", "morgen um 9")
        p.at("2026-10-13T15:00+02:00", "morgen um 15 Uhr")
        p.at("2026-10-12T19:30+02:00", "um 19:30 Uhr")
        p.at("2026-10-12T17:00+02:00", "um siebzehn Uhr")
        p.at("2026-10-12T23:00+02:00", "um dreiundzwanzig Uhr")
        p.at("2026-10-13T00:00+02:00", "um null Uhr")
    }

    @Test
    fun `a bare hour is read inside waking hours`() {
        p.at("2026-10-12T15:00+02:00", "um 3")
        p.at("2026-10-12T15:00+02:00", "um drei")
        p.at("2026-10-12T15:00+02:00", "um 15 Uhr")
        p.at("2026-10-12T21:00+02:00", "um neun")
        p.at("2026-10-12T19:30+02:00", "um 7.30 Uhr")
        p.at("2026-10-12T12:00+02:00", "um zwölf")
        p.at("2026-10-12T13:00+02:00", "um eins")
        p.at("2026-10-12T13:00+02:00", "um ein Uhr")
    }

    @Test
    fun `morgens and abends set the half of the clock`() {
        // 09:00 has passed today, so "um 9 Uhr morgens" is tomorrow morning.
        p.at("2026-10-13T09:00+02:00", "um 9 Uhr morgens")
        p.at("2026-10-12T20:00+02:00", "um 8 Uhr abends")
        p.at("2026-10-13T20:00+02:00", "morgen Abend um 8")
    }

    @Test
    fun `halb means half an hour before`() {
        // halb 8 is 07:30 or 19:30; 07:30 has passed.
        p.at("2026-10-12T19:30+02:00", "um halb 8")
        p.at("2026-10-13T07:30+02:00", "morgen um halb 8")
        p.at("2026-10-12T15:30+02:00", "um halb vier")
        p.at("2026-10-16T08:30+02:00", "am Freitag um halb 9")
    }

    @Test
    fun `viertel and dreiviertel`() {
        p.at("2026-10-12T15:15+02:00", "Viertel nach 3")
        p.at("2026-10-12T11:45+02:00", "Viertel vor 12")
        p.at("2026-10-12T20:45+02:00", "um dreiviertel 9")
        p.at("2026-10-13T08:45+02:00", "morgen um dreiviertel 9")
    }

    @Test
    fun `minutes before and after the hour`() {
        p.at("2026-10-12T19:50+02:00", "zehn vor acht")
        p.at("2026-10-12T15:20+02:00", "zwanzig nach drei")
        // 06:35 is outside waking hours.
        p.at("2026-10-12T18:35+02:00", "fünf nach halb sieben")
    }

    @Test
    fun `noon, midnight and parts of the day`() {
        p.partOfDay("2026-10-12T12:00+02:00", "heute Mittag")
        p.partOfDay("2026-10-12T12:00+02:00", "mittags")
        p.at("2026-10-13T00:00+02:00", "um Mitternacht")
        // Midnight ends the named day: Friday's midnight is the start of Saturday.
        p.at("2026-10-17T00:00+02:00", "Freitag um Mitternacht")
        p.at("2026-10-13T02:00+02:00", "heute Nacht um 2")
        p.partOfDay("2026-10-12T19:00+02:00", "abends")
        p.partOfDay("2026-10-12T19:00+02:00", "heute Abend")
    }

    @Test
    fun `Mittag after gegen, um, bis or ab is noon`() {
        p.at("2026-10-12T12:00+02:00", "gegen Mittag")
        p.at("2026-10-12T12:00+02:00", "Ich melde mich gegen Mittag.")
        p.at("2026-10-12T12:00+02:00", "Das muss bis Mittag fertig sein.")
        p.at("2026-10-12T12:00+02:00", "heute gegen Mittag")
        p.at("2026-10-13T12:00+02:00", "morgen gegen Mittag")
        val found = p.all("morgen bis Mittag")
        assertEquals("2026-10-13T12:00+02:00", found.last().at.toOffsetDateTime().toString())
        assertEquals(true, found.last().hasTime)
        p.none("Wir gehen zu Mittag essen.")
        p.none("Das Mittagessen war gut.")
    }

    @Test
    fun `weekdays`() {
        p.day("2026-10-16", "am Freitag")
        p.at("2026-10-16T09:00+02:00", "Freitag um 9")
        p.day("2026-10-16", "diesen Freitag")
        p.day("2026-10-16", "kommenden Freitag")
        p.day("2026-10-23", "übernächsten Freitag")
        p.day("2026-10-09", "letzten Freitag")
        p.day("2026-10-20", "nächste Woche Dienstag")
        p.day("2026-10-20", "Dienstag nächster Woche")
    }

    @Test
    fun `naechsten Freitag follows the policy`() {
        p.day("2026-10-16", "nächsten Freitag")
        p.day("2026-10-23", "nächsten Freitag", policy = TimePolicy(nextWeekdayMeansFollowingWeek = true))
        p.day("2026-10-16", "am nächsten Freitag")
    }

    @Test
    fun `a weekday joined with a part of the day has no time`() {
        p.partOfDay("2026-10-16T08:00+02:00", "Freitagmorgen")
        p.partOfDay("2026-10-16T19:00+02:00", "Freitagabend")
        p.partOfDay("2026-10-16T19:00+02:00", "Freitag Abend")
        p.at("2026-10-16T15:00+02:00", "am Freitagnachmittag um 3")
    }

    @Test
    fun `guten Morgen is a greeting`() {
        p.none("Guten Morgen!")
        p.none("Guten Morgen zusammen")
        // The short greeting.
        p.none("Morgen!")
        p.none("Morgen zusammen, fangen wir an.")
        p.none("guten Abend")
        p.none("Gute Nacht")
        val found = p.one("Guten Morgen, ich rufe morgen an.")
        assertEquals("morgen", found.phrase)
        assertEquals("2026-10-13", found.at.toLocalDate().toString())
    }

    @Test
    fun `Morgen as a noun is the morning`() {
        p.partOfDay("2026-10-12T08:00+02:00", "heute Morgen")
        // Old spelling, as a transcript may write it.
        p.partOfDay("2026-10-12T08:00+02:00", "heute morgen")
        // 08:00 today has passed, so the next morning is tomorrow's.
        p.partOfDay("2026-10-13T08:00+02:00", "am Morgen")
        p.none("jeden Morgen")
    }

    @Test
    fun `Morgen at the start of a sentence is tomorrow`() {
        val capital = p.day("2026-10-13", "Morgen gehe ich zum Arzt.")
        val lower = p.day("2026-10-13", "Ich gehe morgen zum Arzt.")
        assertTrue("a capital Morgen is less certain", capital.confidence < lower.confidence)
    }

    @Test
    fun `morgen frueh is tomorrow morning`() {
        p.partOfDay("2026-10-13T08:00+02:00", "morgen früh")
        p.at("2026-10-13T07:00+02:00", "morgen früh um 7")
    }

    @Test
    fun `dates`() {
        assertEquals("am 16.10.", p.day("2026-10-16", "Wir sehen uns am 16.10.").phrase)
        p.day("2026-10-16", "16.10.")
        p.day("2026-10-16", "am 16. Oktober")
        p.day("2026-10-16", "16.10.2026")
        p.day("2026-10-16", "am sechzehnten Oktober")
        p.day("2027-10-16", "16. Oktober 2027")
        p.at("2026-10-16T14:00+02:00", "am 16.10. um 14:00")
    }

    @Test
    fun `durations from now`() {
        p.at("2026-10-12T12:00+02:00", "in zwei Stunden")
        p.at("2026-10-12T12:00+02:00", "in 2 Stunden")
        p.at("2026-10-12T10:30+02:00", "in einer halben Stunde")
        p.at("2026-10-12T11:00+02:00", "in einer Stunde")
        p.at("2026-10-12T10:15+02:00", "in 15 Minuten")
        p.at("2026-10-12T11:30+02:00", "in anderthalb Stunden")
        p.at("2026-10-12T10:15+02:00", "in einer Viertelstunde")
    }

    @Test
    fun `days and weeks from now are dates`() {
        p.day("2026-10-15", "in drei Tagen")
        p.day("2026-10-19", "in einer Woche")
        p.at("2026-10-26T09:00+01:00", "in 14 Tagen", hasTime = false)
    }

    @Test
    fun `a weekday with weeks from now is that weekday in the week then`() {
        // Two weeks from Monday the 12th is the week of the 26th.
        p.at("2026-10-26T09:00+01:00", "In zwei Wochen am Montag", hasTime = false)
        p.at("2026-10-26T09:00+01:00", "Montag in zwei Wochen", hasTime = false)
        p.day("2026-10-20", "Dienstag in einer Woche")
    }

    @Test
    fun `colloquial articles count as one`() {
        p.at("2026-10-12T10:30+02:00", "Ruf mich in 'ner halben Stunde an.")
        p.at("2026-10-12T11:00+02:00", "in 'ner Stunde")
    }

    @Test
    fun `weeks and weekends are runs of days`() {
        p.days("2026-10-19", "2026-10-25", "nächste Woche")
        p.days("2026-10-17", "2026-10-18", "am Wochenende")
    }

    @Test
    fun `number words eins to vierundzwanzig count hours`() {
        val words = listOf(
            "einer", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn",
            "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn",
            "achtzehn", "neunzehn", "zwanzig", "einundzwanzig", "zweiundzwanzig",
            "dreiundzwanzig", "vierundzwanzig"
        )
        words.forEachIndexed { index, word ->
            val hours = index + 1
            val unit = if (hours == 1) "Stunde" else "Stunden"
            val found = p.one("in $word $unit")
            assertEquals("in $word $unit", MONDAY_10.plusHours(hours.toLong()), found.at)
        }
    }

    @Test
    fun `number words eins to zwoelf as clock hours on a given day`() {
        val expectedHours = listOf(13, 14, 15, 16, 17, 18, 7, 8, 9, 10, 11, 12)
        val words = listOf(
            "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn",
            "elf", "zwölf"
        )
        words.zip(expectedHours).forEach { (word, hour) ->
            val found = p.one("morgen um $word")
            assertEquals("morgen um $word", "2026-10-13", found.at.toLocalDate().toString())
            assertEquals("morgen um $word", hour, found.at.hour)
        }
    }

    @Test
    fun `number words dreizehn to vierundzwanzig are 24-hour clock hours`() {
        val words = listOf(
            "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
            "zwanzig", "einundzwanzig", "zweiundzwanzig", "dreiundzwanzig"
        )
        words.forEachIndexed { index, word ->
            val found = p.one("morgen um $word Uhr")
            assertEquals("morgen um $word Uhr", "2026-10-13", found.at.toLocalDate().toString())
            assertEquals("morgen um $word Uhr", 13 + index, found.at.hour)
        }
        // 24:00 is the midnight that ends the day.
        p.at("2026-10-14T00:00+02:00", "morgen um vierundzwanzig Uhr")
    }

    @Test
    fun `um before an amount is not a time`() {
        p.none("Die Preise steigen um 5 Prozent.")
        p.none("Die Zinsen steigen um 2,5.")
        p.none("Er ist um 1,80 groß.")
        p.none("Der Termin wurde um 10 Minuten verschoben.")
        p.none("Das kostet halb so viel.")
        p.none("Ein Viertel der Kosten bleibt.")
        p.none("Der Preis steigt um 2.50 Euro.")
        p.at("2026-10-12T19:30+02:00", "um 7.30 Uhr")
    }

    @Test
    fun `am after a clock stays German before a German day`() {
        p.at("2026-10-16T09:00+02:00", "um 9 am Freitag")
        p.at("2026-10-12T15:00+02:00", "um 3 am Nachmittag")
        p.at("2026-10-16T09:00+02:00", "9 Uhr am Freitag")
        p.at("2026-10-16T09:00+02:00", "um 9 am 16. Oktober")
    }

    @Test
    fun `a range of clock times is one candidate from its start to its end`() {
        val dentist = p.range("2026-10-16T10:00+02:00", "2026-10-16T12:00+02:00", "Am Freitag von 10 bis 12 Uhr Zahnarzt")
        assertEquals("Am Freitag von 10 bis 12 Uhr", dentist.phrase)
        p.range("2026-10-13T09:00+02:00", "2026-10-13T11:00+02:00", "Termin morgen von 9 bis 11 Uhr")
        p.range("2026-10-12T14:00+02:00", "2026-10-12T16:00+02:00", "Heute von 14 bis 16 Uhr")
        p.range("2026-10-12T09:00+02:00", "2026-10-12T17:00+02:00", "Heute von 9 Uhr bis 17 Uhr")
        p.range("2026-10-13T09:00+02:00", "2026-10-13T11:00+02:00", "morgen zwischen 9 und 11 Uhr")
        p.range("2026-10-13T09:00+02:00", "2026-10-13T11:00+02:00", "morgen 9-11 Uhr")
        p.range("2026-10-13T09:00+02:00", "2026-10-13T11:00+02:00", "morgen 9 bis 11 Uhr")
        p.range("2026-10-13T08:30+02:00", "2026-10-13T10:00+02:00", "morgen von halb 9 bis 10")
        p.range("2026-10-14T14:00+02:00", "2026-10-14T15:30+02:00", "am Mittwoch von 14 bis 15:30 Uhr")
        p.range("2026-10-12T19:00+02:00", "2026-10-12T21:00+02:00", "heute Abend von 7 bis 9")
    }

    @Test
    fun `numbers joined by bis or a dash without a clock are no time`() {
        p.none("Lies Seite 10 bis 12.")
        p.none("Das Spiel endete 2-1.")
        p.none("Die Preise steigen zwischen 5 und 10 Prozent.")
        p.none("Bewerte es von 1 bis 10.")
    }

    @Test
    fun `month names that are first names need a date around them`() {
        p.at("2026-10-12T14:00+02:00", "Treffen mit Jan 14 Uhr")
        p.at("2026-10-12T15:00+02:00", "Termin mit August 15 Uhr")
        p.none("Ich habe Jan 3 Mal angerufen")
        p.none("Frag Jan 2 Sachen.")
        p.day("2027-01-14", "am 14. Januar")
        p.day("2027-08-15", "am 15. August")
        p.day("2027-05-05", "am 5. Mai")
    }

    @Test
    fun `Mai or Jan after a bare number needs its dot or am before it`() {
        p.none("Treffen von 10 bis 12 Mai")
        p.none("Wir waren 3 Jan und ich.")
        p.day("2027-05-12", "Treffen am 12 Mai")
        p.days("2027-05-10", "2027-05-12", "Treffen von 10 bis 12. Mai")
        p.day("2027-01-14", "14. Jan")
        // After a weekday a date is expected, so the month needs nothing more.
        p.day("2027-05-14", "Freitag, 14 Mai")
    }

    @Test
    fun `jeden Freitag recurs and scores lower`() {
        assertEquals("jeden Freitag", p.day("2026-10-16", "jeden Freitag").phrase)
        assertTrue(p.confidence("jeden Freitag") < p.confidence("am Freitag"))
    }

    @Test
    fun `Morgen after a greeting word is a greeting too`() {
        p.none("Hallo, Morgen!")
        p.none("Hallo, Morgen zusammen!")
        p.none("Hi, Morgen allerseits.")
        p.day("2026-10-13", "Hallo, Morgen gehe ich zum Arzt.")
    }

    @Test
    fun `heute Nacht said after midnight is the night that is running`() {
        p.at("2026-10-13T01:00+02:00", "heute Nacht um 1", anchor = berlin("2026-10-13T00:30"))
        p.at("2026-10-13T02:00+02:00", "heute Nacht um 2")
    }

    @Test
    fun `the phrase keeps the transcript's words`() {
        val text = "Ruf mich morgen um halb 8 an"
        val found = p.one(text)
        assertEquals("morgen um halb 8", found.phrase)
        assertEquals(text.indexOf("morgen"), found.start)
        assertEquals(text.indexOf(" an"), found.end)
    }
}
