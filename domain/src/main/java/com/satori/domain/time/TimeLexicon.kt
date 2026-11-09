package com.satori.domain.time

import java.time.DayOfWeek
import java.util.Locale

/** Parts of the day, with the hour used when only the part was said ("Freitagabend" = 19:00). */
internal enum class DayPart(val defaultHour: Int) {
    MORNING(8), FORENOON(10), NOON(12), AFTERNOON(15), EVENING(19), NIGHT(22)
}

/** Units of "in 2 hours", "vor 3 Tagen". */
internal enum class OffsetUnit { MINUTE, HOUR, DAY, WEEK, FORTNIGHT, MONTH, YEAR }

/**
 * English and German words for dates and times. Every key is folded with [fold], so "Übermorgen"
 * and "uebermorgen" look up the same entry.
 */
internal object TimeLexicon {

    /** Lower case with umlauts and ß spelt out: "Nächsten" becomes "naechsten". */
    fun fold(word: String): String {
        val lower = word.lowercase(Locale.ROOT)
        val out = StringBuilder(lower.length + 4)
        for (c in lower) {
            when (c) {
                'ä' -> out.append("ae")
                'ö' -> out.append("oe")
                'ü' -> out.append("ue")
                'ß' -> out.append("ss")
                '’', '‘', '`', '´' -> out.append('\'')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    // --- Numbers ---------------------------------------------------------------------------

    private val EN_UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val EN_TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)

    // "ein", "eine", "einer" are articles as often as numbers; the rules that accept them as
    // 1 ("in einer Stunde", "ein Uhr") check them by name.
    private val DE_UNITS = mapOf(
        "null" to 0, "eins" to 1, "zwei" to 2, "zwo" to 2, "drei" to 3, "vier" to 4, "fuenf" to 5,
        "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11,
        "zwoelf" to 12, "dreizehn" to 13, "vierzehn" to 14, "fuenfzehn" to 15, "sechzehn" to 16,
        "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19
    )
    private val DE_TENS = mapOf("zwanzig" to 20, "dreissig" to 30, "vierzig" to 40, "fuenfzig" to 50)
    private val DE_UNIT_PREFIXES = mapOf(
        "ein" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fuenf" to 5, "sechs" to 6,
        "sieben" to 7, "acht" to 8, "neun" to 9
    )

    /** A number word of one token, 0 to 59: "seven", "twenty-one", "einundzwanzig". */
    fun cardinal(word: String): Int? {
        EN_UNITS[word]?.let { return it }
        EN_TENS[word]?.let { return it }
        DE_UNITS[word]?.let { return it }
        DE_TENS[word]?.let { return it }
        val dash = word.indexOf('-')
        if (dash > 0) {
            val tens = EN_TENS[word.substring(0, dash)]
            val unit = EN_UNITS[word.substring(dash + 1)]
            if (tens != null && unit != null && unit in 1..9) return tens + unit
        }
        val und = word.indexOf("und")
        if (und > 0) {
            val unit = DE_UNIT_PREFIXES[word.substring(0, und)]
            val tens = DE_TENS[word.substring(und + 3)]
            if (unit != null && tens != null) return tens + unit
        }
        return null
    }

    /** "twenty" in "twenty one", which English writes as two words. */
    fun englishTens(word: String): Int? = EN_TENS[word]

    /** "one" to "nine", the second word of "twenty one". */
    fun englishUnit(word: String): Int? = EN_UNITS[word]?.takeIf { it in 1..9 }

    private val EN_ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11,
        "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15,
        "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19,
        "twentieth" to 20, "thirtieth" to 30
    )

    // German ordinal stems; the word adds an ending: "erste", "ersten", "erster", "erstes", "erstem".
    private val DE_ORDINAL_STEMS: Map<String, Int> = buildMap {
        val small = listOf(
            "erst", "zweit", "dritt", "viert", "fuenft", "sechst", "siebt", "acht", "neunt", "zehnt",
            "elft", "zwoelft", "dreizehnt", "vierzehnt", "fuenfzehnt", "sechzehnt", "siebzehnt",
            "achtzehnt", "neunzehnt"
        )
        small.forEachIndexed { index, stem -> put(stem, index + 1) }
        put("siebent", 7)
        put("zwanzigst", 20)
        put("dreissigst", 30)
        for ((prefix, unit) in DE_UNIT_PREFIXES) {
            put("${prefix}undzwanzigst", 20 + unit)
        }
        put("einunddreissigst", 31)
    }
    private val DE_ORDINAL_ENDINGS = listOf("en", "er", "es", "em", "e")

    /** An ordinal word for a day of the month: "sixteenth", "twenty-first", "sechzehnten". */
    fun ordinal(word: String): Int? {
        EN_ORDINALS[word]?.let { return it }
        val dash = word.indexOf('-')
        if (dash > 0) {
            val tens = EN_TENS[word.substring(0, dash)]
            val unit = EN_ORDINALS[word.substring(dash + 1)]
            if (tens != null && unit != null && unit in 1..9) return tens + unit
        }
        for (ending in DE_ORDINAL_ENDINGS) {
            if (word.length > ending.length && word.endsWith(ending)) {
                DE_ORDINAL_STEMS[word.dropLast(ending.length)]?.let { return it }
            }
        }
        return null
    }

    /** "first" to "ninth", the second word of "twenty first". */
    fun englishUnitOrdinal(word: String): Int? = EN_ORDINALS[word]?.takeIf { it in 1..9 }

    /** Letters right after a day number that make it an ordinal: 16th, 15ten. */
    val ORDINAL_SUFFIXES = setOf("st", "nd", "rd", "th", "te", "ten", "ter", "tes", "tem")

    // --- Calendar words ----------------------------------------------------------------------

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
        "montag" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY,
        "mittwoch" to DayOfWeek.WEDNESDAY, "donnerstag" to DayOfWeek.THURSDAY,
        "freitag" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY,
        "sonnabend" to DayOfWeek.SATURDAY, "sonntag" to DayOfWeek.SUNDAY
    )

    /** A weekday as said: the day, a part of the day joined to it, and whether it recurs. */
    data class WeekdayWord(val day: DayOfWeek, val part: DayPart?, val recurring: Boolean)

    // Joined to a German weekday: "Freitagabend", "Montagmorgen", "Sonntagnachmittag".
    private val COMPOUND_PARTS = mapOf(
        "morgen" to DayPart.MORNING, "frueh" to DayPart.MORNING, "vormittag" to DayPart.FORENOON,
        "mittag" to DayPart.NOON, "nachmittag" to DayPart.AFTERNOON, "abend" to DayPart.EVENING,
        "nacht" to DayPart.NIGHT, "morning" to DayPart.MORNING, "afternoon" to DayPart.AFTERNOON,
        "evening" to DayPart.EVENING, "night" to DayPart.NIGHT
    )

    /**
     * "Friday", "freitags" (every Friday), "Freitagabend", "freitagabends", "Freitag-Abend".
     * A plural or adverb form recurs and says less about one particular day.
     */
    fun weekday(word: String): WeekdayWord? {
        WEEKDAYS[word]?.let { return WeekdayWord(it, null, false) }
        if (word.length > 3 && word.endsWith("s")) {
            WEEKDAYS[word.dropLast(1)]?.let { return WeekdayWord(it, null, true) }
        }
        for ((name, day) in WEEKDAYS) {
            if (word.length <= name.length || !word.startsWith(name)) continue
            val rest = word.substring(name.length).removePrefix("-")
            COMPOUND_PARTS[rest]?.let { return WeekdayWord(day, it, false) }
            if (rest.endsWith("s")) COMPOUND_PARTS[rest.dropLast(1)]?.let { return WeekdayWord(day, it, true) }
        }
        return null
    }

    private val MONTHS = mapOf(
        "january" to 1, "januar" to 1, "jaenner" to 1, "jan" to 1,
        "february" to 2, "februar" to 2, "feber" to 2, "feb" to 2,
        "march" to 3, "maerz" to 3, "mar" to 3,
        "april" to 4, "apr" to 4,
        "may" to 5, "mai" to 5,
        "june" to 6, "juni" to 6, "jun" to 6,
        "july" to 7, "juli" to 7, "jul" to 7,
        "august" to 8, "aug" to 8,
        "september" to 9, "sept" to 9, "sep" to 9,
        "october" to 10, "oktober" to 10, "oct" to 10, "okt" to 10,
        "november" to 11, "nov" to 11,
        "december" to 12, "dezember" to 12, "dec" to 12, "dez" to 12
    )

    /** A month name, 1 to 12. Only read next to a day number, so "may" and "march" stay verbs. */
    fun month(word: String): Int? = MONTHS[word]

    /** Month words that are also first names or verbs: "mit Jan", "Mai", "we march", "may". */
    val NAME_OR_VERB_MONTHS = setOf("jan", "mai", "march", "may")

    /** The verbs among them, which are months when written with a capital ("March 3", "May 5 works"). */
    val VERB_MONTHS = setOf("march", "may")

    /**
     * Month names German doesn't use. English writes no dot after a day, so "5. May" is the end of
     * one sentence and the start of the next ("I work from 9 to 5. May I ask ...").
     */
    val ENGLISH_ONLY_MONTHS = setOf(
        "january", "february", "march", "mar", "may", "june", "july", "october", "oct", "december", "dec"
    )

    private val ENGLISH_CALENDAR_WORDS = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun",
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep",
        "sept", "oct", "nov", "dec"
    )

    /** An English weekday or month name: after a clock it makes "am" the English a.m. */
    fun isEnglishCalendarWord(word: String): Boolean = word in ENGLISH_CALENDAR_WORDS

    // --- Parts of the day ----------------------------------------------------------------------

    /** German adverbs that can stand alone: "abends", "morgens". */
    val PART_ADVERBS = mapOf(
        "morgens" to DayPart.MORNING, "fruehmorgens" to DayPart.MORNING,
        "vormittags" to DayPart.FORENOON, "mittags" to DayPart.NOON,
        "nachmittags" to DayPart.AFTERNOON, "abends" to DayPart.EVENING,
        "spaetabends" to DayPart.EVENING, "nachts" to DayPart.NIGHT
    )

    /** German nouns: only a part of the day after a day ("heute Abend") or "am" ("am Abend"). */
    val PART_NOUNS_DE = mapOf(
        "morgen" to DayPart.MORNING, "frueh" to DayPart.MORNING, "vormittag" to DayPart.FORENOON,
        "mittag" to DayPart.NOON, "nachmittag" to DayPart.AFTERNOON, "abend" to DayPart.EVENING,
        "nacht" to DayPart.NIGHT
    )

    /** English nouns: a part of the day after a day ("Friday morning"), "this" or "in the". */
    val PART_NOUNS_EN = mapOf(
        "morning" to DayPart.MORNING, "afternoon" to DayPart.AFTERNOON,
        "evening" to DayPart.EVENING, "night" to DayPart.NIGHT
    )

    /** Words before "Morgen", "Abend", "morning" that make them a greeting. */
    val GREETINGS = setOf("guten", "gute", "gut", "gutn", "schoenen", "schoene", "schoenes", "good")

    /** Words before "Morgen" that make it the noun, as in "jeden Morgen", not "tomorrow". */
    val MORNING_NOUN_MARKERS = setOf(
        "am", "den", "der", "des", "dem", "jeden", "jedem", "jeder", "diesen", "diesem", "dieser",
        "eines", "einem", "einen", "ein", "fruehen", "ganzen", "naechsten", "folgenden", "selben",
        "gleichen", "vom", "zum", "pro", "beim", "im"
    )

    // --- Durations ---------------------------------------------------------------------------

    private val UNITS = mapOf(
        "minute" to OffsetUnit.MINUTE, "minutes" to OffsetUnit.MINUTE, "min" to OffsetUnit.MINUTE,
        "mins" to OffsetUnit.MINUTE, "minuten" to OffsetUnit.MINUTE,
        "hour" to OffsetUnit.HOUR, "hours" to OffsetUnit.HOUR, "hr" to OffsetUnit.HOUR,
        "hrs" to OffsetUnit.HOUR, "stunde" to OffsetUnit.HOUR, "stunden" to OffsetUnit.HOUR,
        "std" to OffsetUnit.HOUR,
        "day" to OffsetUnit.DAY, "days" to OffsetUnit.DAY, "tag" to OffsetUnit.DAY,
        "tage" to OffsetUnit.DAY, "tagen" to OffsetUnit.DAY,
        "week" to OffsetUnit.WEEK, "weeks" to OffsetUnit.WEEK, "woche" to OffsetUnit.WEEK,
        "wochen" to OffsetUnit.WEEK,
        "fortnight" to OffsetUnit.FORTNIGHT, "fortnights" to OffsetUnit.FORTNIGHT,
        "month" to OffsetUnit.MONTH, "months" to OffsetUnit.MONTH, "monat" to OffsetUnit.MONTH,
        "monate" to OffsetUnit.MONTH, "monaten" to OffsetUnit.MONTH,
        "year" to OffsetUnit.YEAR, "years" to OffsetUnit.YEAR, "jahr" to OffsetUnit.YEAR,
        "jahre" to OffsetUnit.YEAR, "jahren" to OffsetUnit.YEAR
    )

    fun unit(word: String): OffsetUnit? = UNITS[word]

    /**
     * Words after a number that make "um 5" or "at 3" an amount, not a time: "um 5 Prozent",
     * "um 10 Minuten verschoben", "at one point".
     */
    val AMOUNT_WORDS = setOf(
        "prozent", "percent", "per", "prozentpunkte", "euro", "euros", "cent", "cents", "dollar",
        "dollars", "pfund", "pound", "pounds", "franken", "grad", "degree", "degrees", "meter",
        "metern", "metres", "meters", "kilometer", "km", "kilo", "kilos", "kilogramm", "kg",
        "gramm", "liter", "litres", "liters", "stueck", "mal", "times", "time", "punkte",
        "punkten", "point", "points", "plaetze", "places", "place", "stellen", "stelle", "stage",
        "level", "location", "locations", "personen", "persons", "people", "leute", "leuten",
        "menschen", "jahre", "jahren", "years", "tage", "tagen", "days", "wochen", "weeks",
        "monate", "monaten", "months", "stunden", "hours", "minuten", "minutes", "sekunden",
        "seconds", "millionen", "million", "millions", "milliarden", "billion", "tausend",
        "thousand", "hundert", "hundred", "of", "seite", "seiten", "page", "pages", "kapitel",
        "chapter", "chapters"
    )

    /** Signs after a number that make it an amount: "um 5 %". */
    val AMOUNT_SIGNS = setOf("%", "€", "$", "°", "£")
}
