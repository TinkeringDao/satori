package com.satori.domain.usecase.mindmap

import com.satori.domain.model.MindLink
import com.satori.domain.usecase.llm.AnswerChecks
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.InsightOutputParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The summary call of [RefreshThemeSummariesUseCase]: what the model reads about one theme, the
 * JSON schema its answer is held to, and how the answer is read.
 *
 * The model reads the theme's label on the first line, then one line for each of its
 * [MAX_RECORDINGS] most recent recordings, newest first: the recording's date, then what it said
 * about the theme (the points of its links under the theme, without repeats). A recording whose
 * links hold no points has nothing to say and gets no line.
 */
internal object ThemeSummaryPrompt {

    /** The most recordings a summary is written from: the most recent ones. */
    const val MAX_RECORDINGS = 8

    /** The longest summary kept, in characters; a longer one is cut at a sentence or a word. */
    const val MAX_SUMMARY_CHARS = 800

    // A recording's line, cut at a word: 8 of them stay near the size of a batch chunk.
    private const val MAX_LINE_CHARS = 600

    private val MARKUP = Regex("""\*\*|__|^#+\s*|^\s*[-*•]\s+""", RegexOption.MULTILINE)
    private const val SENTENCE_ENDS = ".!?…"

    /** One recording as the model reads it: when it was made, and what it said about the theme. */
    data class Recording(val sessionId: String, val recordedAt: Long, val points: List<String>)

    /**
     * The JSON schema of the answer: {"summary": "..."}. Stays within the subset llama.cpp turns
     * into a grammar.
     */
    val SCHEMA: String = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("summary") {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", MAX_SUMMARY_CHARS)
            }
        }
        putJsonArray("required") { add("summary") }
        put("additionalProperties", false)
    }.toString()

    /**
     * The recordings the summary of a theme is written from, newest first and at most
     * [MAX_RECORDINGS]: [links] (all the theme's links, newest recording first) grouped by
     * recording, each with the points of its links in their order and without repeats. Recordings
     * whose links hold no point are left out, so a theme none of whose recordings holds a point
     * gives none: there is nothing to sum up.
     */
    fun recordings(links: List<MindLink>): List<Recording> =
        links.groupBy { it.sessionId }.values
            .map { group ->
                val seen = HashSet<String>()
                val points = group.flatMap { it.points }.filter { seen.add(MindWords.words(it).joinToString(" ")) }
                Recording(group.first().sessionId, group.maxOf { it.recordedAt }, points)
            }
            .filter { it.points.isNotEmpty() }
            .sortedByDescending { it.recordedAt }
            .take(MAX_RECORDINGS)

    /**
     * What the model reads: [label] on the first line, then a line per recording, as its date in
     * [languageCode] ("10 Oct 2026", "10. Okt. 2026") in [zone], a colon, and its points.
     */
    fun input(label: String, recordings: List<Recording>, languageCode: String, zone: ZoneId): String {
        val german = languageCode == "de"
        val dates = DateTimeFormatter.ofPattern(if (german) "d. MMM yyyy" else "d MMM yyyy", Locale.forLanguageTag(languageCode))
        val lines = recordings.map { recording ->
            val date = dates.format(Instant.ofEpochMilli(recording.recordedAt).atZone(zone))
            MindText.cut("$date: ${recording.points.joinToString(" ")}", MAX_LINE_CHARS)
        }
        return (listOf(MindText.oneLine(label)) + lines).joinToString("\n")
    }

    /**
     * The summary in [answer], tidied: on one line, without Markdown, quotes at its ends or
     * repeated spaces, cut to [MAX_SUMMARY_CHARS] at the last sentence end (or word) before it.
     * Null when the answer holds no {"summary": "..."} object that can be read, as when it was
     * cut off, or the summary is empty.
     */
    fun parse(answer: String): String? {
        val text = InsightOutputParser.stripThinkingBlock(answer)
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = JsonScan.endOfValue(text, start) ?: return null
        val element = JsonScan.parseOrNull(text.substring(start, end)) as? JsonObject ?: return null
        val summary = (element["summary"] as? JsonPrimitive)?.contentOrNull ?: return null
        val tidy = MindText.oneLine(MARKUP.replace(summary, "")).trim { it in MindText.QUOTES || it.isWhitespace() }
        if (tidy.none { it.isLetterOrDigit() }) return null
        return cutSummary(tidy)
    }

    /**
     * [summary] without the sentences [checks] leaves out (since 0.7.3: "Never report on the
     * recordings.", "No lists, no headings."), and how many it left out, by why; null as the
     * summary when none is left.
     */
    fun screened(summary: String, checks: AnswerChecks): Pair<String?, Map<AnswerDrop, Int>> {
        val (sentences, drops) = checks.kept(summary.split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }) { it }
        return sentences.joinToString(" ").takeIf { it.isNotEmpty() } to drops
    }

    /** Where one sentence of a summary ends and the next begins. */
    private val SENTENCE_BREAK = Regex("""(?<=[.!?…])\s+""")

    /** [text] cut at its last sentence end in the second half of the limit, else at a word with "…". */
    private fun cutSummary(text: String): String {
        if (text.length <= MAX_SUMMARY_CHARS) return text
        val head = text.substring(0, MAX_SUMMARY_CHARS)
        val sentenceEnd = head.indexOfLast { it in SENTENCE_ENDS }
        if (sentenceEnd >= MAX_SUMMARY_CHARS / 2) return head.substring(0, sentenceEnd + 1).trim()
        return MindText.cut(text, MAX_SUMMARY_CHARS - MindText.ELLIPSIS.length) + MindText.ELLIPSIS
    }
}
