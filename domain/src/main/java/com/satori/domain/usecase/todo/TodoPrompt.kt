package com.satori.domain.usecase.todo

import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.time.TimeCandidate
import com.satori.domain.time.TimePrecision
import com.satori.domain.usecase.llm.TextBudget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Whether a parser reading names a clock time (precision EXACT), and not only a part of the day,
 * a day or a run of days. The prompt, the due time and the reminder read it here; where they
 * tell the other readings apart they read [TimeCandidate.precision].
 */
internal val TimeCandidate.hasClock: Boolean get() = hasTime

/**
 * What the model reads for one part of a recording, and the JSON schema its answer must follow.
 *
 * The system prompt is the same for every call, so the model's state after reading it can be
 * kept. What changes is in the user message: a [header] (when the recording started, the to-dos
 * and ideas already noted), then the transcript as one line per segment, "S3 text", with the
 * times found in it on indented lines below: "T2 = 'Freitag um 9' -> Fr. 16. Okt. 09:00
 * (Freitag)". The weekday is spelled out, because a 1B model can't work it out from a date.
 * Segments and times are numbered per call, so the ids stay short, and the schema lets the model
 * cite only those.
 */
internal object TodoPrompt {

    /**
     * Segment text per call. With the system prompt (under 2,000 characters, also in German), the
     * header with up to [MAX_NOTED] noted items and an answer of 768 tokens, this stays inside the
     * model's 4096-token context, also for German, which takes more tokens per character. Counted
     * in English characters ([TextBudget.cost]): a call holds 3,600 characters of Malayalam
     * segments, as many tokens.
     */
    const val MAX_WINDOW_CHARS = 6_000

    /** Segments per call: keeps the segment enum, and so the grammar, small. */
    const val MAX_WINDOW_SEGMENTS = 80

    /** Longest segment shown; voice detection cuts speech into segments of about ten seconds. */
    const val MAX_SEGMENT_CHARS = 1_500

    /** To-dos and ideas per call. More than this in ten minutes of speech is the model rambling. */
    const val MAX_ITEMS = 10

    /** Longest todo text the grammar allows: fifteen long German words. */
    const val MAX_TEXT_CHARS = 120

    /** Known to-dos and ideas shown as already noted, and the length each is cut to. */
    const val MAX_NOTED = 20
    private const val MAX_NOTED_CHARS = 80

    const val NO_TIME = "none"
    val KINDS = listOf("task", "appointment")
    val CAPTURES = listOf("explicit", "commitment", "asked", "inferred")

    // In the order the model writes them: cite the segment first, say what it is, then copy from it.
    private val TODO_FIELDS = listOf("segment", "type", "text", "when", "capture")
    private val IDEA_FIELDS = listOf("segment", "type", "text")

    // With the segment's own words before the text, for a text in another language than they are.
    private val QUOTED_TODO_FIELDS = listOf("segment", "type", "said", "text", "when", "capture")
    private val QUOTED_IDEA_FIELDS = listOf("segment", "type", "said", "text")

    /** Every key an item of the answer can have, which its text must not hold ([TodoGate.namesField]). */
    val KEYS: List<String> = QUOTED_TODO_FIELDS

    /**
     * The longest copy of a segment's words the grammar allows: about 16 Malayalam words, at 9.9
     * characters a word, for the 8 the header asks for. Its tokens add to the answer's.
     */
    const val MAX_SAID_CHARS = 160

    // Room for "S12 " and the line break, and for "  T3 = '' -> Fri 16 Oct 09:00 (Wednesday)".
    private const val SEGMENT_LINE_EXTRA = 5
    private const val TIME_LINE_EXTRA = 45

    /**
     * Voice detection ends a segment at a short pause, often inside a sentence. A sentence a
     * segment leaves open runs on into the next one only if that begins within this time of it
     * ([goesOn]).
     */
    const val MAX_SENTENCE_GAP_MS = 3_000L

    /**
     * Segments are stamped when speech recognition gives them out, at their end, so the time it
     * took to say the next one is taken off the gap between two stamps. It is reckoned at this
     * many milliseconds a character, brisk speech of 20 characters a second, so that the pause
     * comes out no shorter than it was.
     */
    private const val SPOKEN_MS_PER_CHAR = 50L

    /** Longest open sentence carried into the next segment's time parsing. */
    private const val MAX_OPEN_SENTENCE_CHARS = 300

    // What ends a sentence, as the time parser reads it; a dot inside a time ("9.30") doesn't.
    private const val SENTENCE_ENDS = ".!?;:…"

    // Words that say nothing alone: what is left of a time cut at a segment's start ("Uhr").
    private val UNIT_WORDS = setOf("uhr", "h", "o'clock", "o’clock", "oclock", "am", "pm", "a.m.", "p.m.")

    private val SPACES = Regex("""\s+""")
    private val SEGMENT_ID = Regex("""S?(\d{1,4})""")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * One transcript segment as the model sees it.
     *
     * @property position Its place in the whole recording, from 0
     * @property text Its text on one line, cut to [MAX_SEGMENT_CHARS]
     * @property spokenAt When it was said, in the recording's zone
     * @property candidates The times found in [text]
     * @property continued True when [text] goes on with a sentence the previous segment left open
     */
    class Line(
        val position: Int,
        val segment: TranscriptionSegment,
        val text: String,
        val spokenAt: ZonedDateTime,
        val candidates: List<TimeCandidate>,
        val continued: Boolean = false
    )

    /** A time listed in the prompt as [id] ("T2"), found in [line]. */
    class Time(val id: String, val line: Line, val candidate: TimeCandidate)

    /** The segments of one model call, numbered S1.., and their times, numbered T1... */
    class Window(val lines: List<Line>) {
        val times: List<Time> = lines.flatMap { line -> line.candidates.map { line to it } }
            .mapIndexed { index, (line, candidate) -> Time("T${index + 1}", line, candidate) }

        /** The line cited as [id] ("S3", "s3" or 3), or null when there is none. */
        fun line(id: String): Line? =
            SEGMENT_ID.matchEntire(id.trim().uppercase(Locale.ROOT))
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.let { lines.getOrNull(it - 1) }

        /** The time listed as [id] ("T2"), or null for "none" and anything not listed. */
        fun time(id: String): Time? {
            val wanted = id.trim().uppercase(Locale.ROOT)
            return times.firstOrNull { it.id == wanted }
        }
    }

    /** A segment's text as shown: on one line, cut to [MAX_SEGMENT_CHARS]. */
    fun text(raw: String): String = SPACES.replace(raw, " ").trim().take(MAX_SEGMENT_CHARS).trim()

    /**
     * True when a segment with [text], stamped [stampedAfterMs] after the one before, goes on with
     * the sentence that one left open: it starts with a lowercase letter or a digit ("um 9 beim
     * Arzt sein", "9 Uhr beim Arzt sein", "at 3, so I leave early") and begins within
     * [MAX_SENTENCE_GAP_MS], the stamps less the time to say [text] ([SPOKEN_MS_PER_CHAR]).
     * Speech recognition writes capitals, so "At 3 I call Bob." starts a sentence of its own.
     */
    fun goesOn(text: String, stampedAfterMs: Long): Boolean {
        if (stampedAfterMs - text.length * SPOKEN_MS_PER_CHAR > MAX_SENTENCE_GAP_MS) return false
        val first = text.firstOrNull { it.isLetterOrDigit() } ?: return false
        return first.isDigit() || first.isLowerCase()
    }

    /** True for a time phrase that is only a unit word, such as "Uhr" or "o'clock". */
    fun isUnitWord(phrase: String): Boolean = phrase.trim().lowercase(Locale.ROOT) in UNIT_WORDS

    /**
     * The sentence still open at the end of [text], whose times are [candidates]: the words
     * after its last sentence end, or all of [text] after [before] (the sentence open before
     * it) when it has none. Empty when [text] ends its sentence. At most
     * [MAX_OPEN_SENTENCE_CHARS] long, cut at a word.
     */
    fun openSentence(before: String, text: String, candidates: List<TimeCandidate>): String {
        val end = text.indices.lastOrNull { endsSentenceAt(text, candidates, it) }
        val open = if (end == null) "$before $text".trim() else text.substring(end + 1).trim()
        if (open.none { it.isLetterOrDigit() }) return ""
        if (open.length <= MAX_OPEN_SENTENCE_CHARS) return open
        val cut = open.length - MAX_OPEN_SENTENCE_CHARS
        val word = open.indexOf(' ', cut)
        return if (word < 0) open.substring(cut) else open.substring(word + 1)
    }

    /**
     * True when times [a] and [b] are said in one sentence: in one segment with no sentence end
     * between them, or in neighbouring segments when the first one's sentence runs on into the
     * next ("Tomorrow I have the dentist" / "at 3.").
     */
    fun sameSentence(a: Time, b: Time): Boolean {
        val (first, second) = if (a.line.position < b.line.position ||
            (a.line === b.line && a.candidate.start <= b.candidate.start)
        ) a to b else b to a
        return when {
            first.line === second.line -> !endsSentence(first.line, first.candidate.end, second.candidate.start)
            second.line.position == first.line.position + 1 && second.line.continued ->
                !endsSentence(first.line, first.candidate.end, first.line.text.length) &&
                    !endsSentence(second.line, 0, second.candidate.start)
            else -> false
        }
    }

    /**
     * The sentence of its line that [time] was said in, from the sentence end before it to the
     * one after it: "maybe on Friday" of "I'll send the report. Maybe on Friday."
     */
    fun sentenceOf(time: Time): String {
        val line = time.line
        val start = (time.candidate.start - 1 downTo 0).firstOrNull { endsSentenceAt(line.text, line.candidates, it) }
        val end = (time.candidate.end until line.text.length).firstOrNull { endsSentenceAt(line.text, line.candidates, it) }
        return line.text.substring(start?.plus(1) ?: 0, end?.plus(1) ?: line.text.length).trim()
    }

    /** True when [line] has a sentence end from [from] until [to], outside the times found in it. */
    private fun endsSentence(line: Line, from: Int, to: Int): Boolean =
        (from until minOf(to, line.text.length)).any { endsSentenceAt(line.text, line.candidates, it) }

    private fun endsSentenceAt(text: String, candidates: List<TimeCandidate>, index: Int): Boolean =
        text[index] in SENTENCE_ENDS && candidates.none { index >= it.start && index < it.end }

    fun localeFor(languageCode: String): Locale =
        if (languageCode.trim().lowercase(Locale.ROOT).startsWith("de")) Locale.GERMAN else Locale.ENGLISH

    /**
     * Splits a recording into calls of at most [MAX_WINDOW_CHARS] and [MAX_WINDOW_SEGMENTS], each
     * segment counted by what its script costs in tokens ([TextBudget.cost]).
     */
    fun windows(lines: List<Line>): List<Window> {
        val windows = mutableListOf<Window>()
        var current = mutableListOf<Line>()
        var size = 0
        for (line in lines) {
            val cost = TextBudget.cost(line.text) + SEGMENT_LINE_EXTRA +
                line.candidates.sumOf { it.phrase.length + TIME_LINE_EXTRA }
            if (current.isNotEmpty() && (size + cost > MAX_WINDOW_CHARS || current.size >= MAX_WINDOW_SEGMENTS)) {
                windows += Window(current)
                current = mutableListOf()
                size = 0
            }
            current += line
            size += cost
        }
        if (current.isNotEmpty()) windows += Window(current)
        return windows
    }

    /**
     * The transcript part the model reads, one line per segment, "S3 text", with the times found
     * in it below, the dates in the language of [locale]. A segment's clock time is in no line:
     * the times below it say what the model needs, and the [header] when the recording started.
     */
    fun render(window: Window, locale: Locale): String = buildString {
        window.lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            append('S').append(index + 1).append(' ').append(line.text)
            for (time in window.times) {
                if (time.line !== line) continue
                append("\n  ").append(time.id).append(" = '").append(time.candidate.phrase).append("' -> ")
                append(describe(time.candidate, line.spokenAt, locale))
            }
        }
    }

    /**
     * What the user message says above the segments: [template] with {date}, {weekday} and {time}
     * set to when the recording started, the weekday in the language of [locale], and {noted} to
     * the to-dos and ideas already known ("-" for none), each in quotes and cut to 80 characters;
     * then, on a line of its own, [note], the line of instructions, when the segments are in
     * another language than the to-dos are written in
     * ([com.satori.domain.usecase.llm.TextLanguageNote]: a Malayalam recording's to-dos are
     * English). It ends with one blank line before the segments, whatever the template ends with.
     */
    fun header(template: String, startedAt: ZonedDateTime, noted: List<String>, locale: Locale, note: String? = null): String {
        val notedList = if (noted.isEmpty()) "-" else {
            noted.joinToString("; ") { "\"" + it.replace('"', '\'').take(MAX_NOTED_CHARS) + "\"" }
        }
        val filled = template
            .replace("{date}", DateTimeFormatter.ISO_LOCAL_DATE.format(startedAt))
            .replace("{weekday}", DateTimeFormatter.ofPattern("EEEE", locale).format(startedAt))
            .replace("{time}", CLOCK.format(startedAt))
            .replace("{noted}", notedList)
            .trimEnd()
        return (if (note.isNullOrBlank()) filled else filled + "\n" + note) + "\n\n"
    }

    /** The words of [window]'s segments, as one text: what the language of a call is told from. */
    fun textOf(window: Window): String = window.lines.joinToString(" ") { it.text }

    /**
     * The JSON schema for one call: a list of at most [MAX_ITEMS] items, each a to-do (segment,
     * type task or appointment, text, when and capture) or an idea (segment, type idea and text).
     * Segment and when can only be ids listed in [window], the other fields only their fixed
     * values, and the text is limited to [MAX_TEXT_CHARS]. With [quotes] each item also gives the
     * words of its segment it was read from ("said", at most [MAX_SAID_CHARS]) after its type: for
     * a text written in another language than the segments, whose own words can't be found in
     * them ([TodoGrounding]). Stays within the subset llama.cpp turns into a grammar, which takes
     * anyOf.
     */
    fun schema(window: Window, quotes: Boolean = false): String = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("items") {
                put("type", "array")
                put("maxItems", MAX_ITEMS)
                putJsonObject("items") {
                    putJsonArray("anyOf") {
                        add(
                            itemSchema(if (quotes) QUOTED_TODO_FIELDS else TODO_FIELDS) {
                                putEnum("segment", segmentIds(window))
                                putEnum("type", KINDS)
                                if (quotes) putSaid()
                                putText()
                                putEnum("when", listOf(NO_TIME) + window.times.map { it.id })
                                putEnum("capture", CAPTURES)
                            }
                        )
                        add(
                            itemSchema(if (quotes) QUOTED_IDEA_FIELDS else IDEA_FIELDS) {
                                putEnum("segment", segmentIds(window))
                                putEnum("type", listOf(TodoAnswer.IDEA))
                                if (quotes) putSaid()
                                putText()
                            }
                        )
                    }
                }
            }
        }
        putJsonArray("required") { add("items") }
        put("additionalProperties", false)
    }.toString()

    private fun segmentIds(window: Window): List<String> = window.lines.indices.map { "S${it + 1}" }

    /** An object whose properties are [properties], each of [fields] required, and no other. */
    private fun itemSchema(fields: List<String>, properties: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties", properties)
        putJsonArray("required") { fields.forEach { add(it) } }
        put("additionalProperties", false)
    }

    private fun JsonObjectBuilder.putSaid() {
        putJsonObject("said") {
            put("type", "string")
            put("minLength", 2)
            put("maxLength", MAX_SAID_CHARS)
        }
    }

    private fun JsonObjectBuilder.putText() {
        putJsonObject("text") {
            put("type", "string")
            put("minLength", 2)
            put("maxLength", MAX_TEXT_CHARS)
        }
    }

    private fun JsonObjectBuilder.putEnum(name: String, values: List<String>) {
        putJsonObject(name) {
            put("type", "string")
            putJsonArray("enum") { values.forEach { add(it) } }
        }
    }

    /**
     * What was said, never an hour that wasn't: a clock time "Fri 16 Oct 09:00 (Friday)", a range
     * of them with its end "Fr. 16. Okt. 10:00-12:00 (Freitag)", a part of the day by name "Fri
     * 16 Oct evening (Friday)", a day "Fr. 16. Okt. (Freitag)", a run of days from its first to
     * its last day "Mon 19 Oct - Sun 25 Oct". The year only when it differs from the one the
     * words were said in.
     */
    private fun describe(candidate: TimeCandidate, spokenAt: ZonedDateTime, locale: Locale): String {
        val german = locale.language == Locale.GERMAN.language
        fun day(at: ZonedDateTime): String {
            val pattern = (if (german) "EEE d. MMM" else "EEE d MMM") + (if (at.year != spokenAt.year) " yyyy" else "")
            return DateTimeFormatter.ofPattern(pattern, locale).format(at)
        }
        val at = candidate.at
        val weekday = " (" + DateTimeFormatter.ofPattern("EEEE", locale).format(at) + ")"
        val first = candidate.startAt
        val last = candidate.endAt
        return when {
            candidate.hasClock -> day(at) + " " + CLOCK.format(at) + last?.let { "-" + CLOCK.format(it) }.orEmpty() + weekday
            candidate.precision == TimePrecision.PART_OF_DAY -> day(at) + " " + partOfDay(at.hour, german) + weekday
            candidate.precision == TimePrecision.DAYS_RANGE && first != null && last != null &&
                first.toLocalDate() != last.toLocalDate() -> day(first) + " - " + day(last)
            else -> day(at) + weekday
        }
    }

    /** The part of the day that a reading's usual hour stands for: 19 is the evening, "abends". */
    private fun partOfDay(hour: Int, german: Boolean): String {
        val part = PARTS.first { hour < it.until }
        return if (german) part.german else part.english
    }

    /** A part of the day, up to the hour [until]. */
    private class Part(val until: Int, val english: String, val german: String)

    private val PARTS = listOf(
        Part(5, "night", "nachts"),
        Part(10, "morning", "morgens"),
        Part(12, "late morning", "vormittags"),
        Part(14, "midday", "mittags"),
        Part(18, "afternoon", "nachmittags"),
        Part(22, "evening", "abends"),
        Part(24, "night", "nachts")
    )
}
