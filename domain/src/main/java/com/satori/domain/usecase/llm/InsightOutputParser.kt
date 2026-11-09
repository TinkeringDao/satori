package com.satori.domain.usecase.llm

import com.satori.domain.model.RecordingMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the model's answer to a notes prompt ([InsightFormat]) into a note.
 *
 * Shared by [com.satori.domain.usecase.sync.SyncSttLlmUseCase], [GenerateFinalInsightUseCase]
 * and [GenerateBatchInsightUseCase], so the live notes and those written after Stop read alike.
 *
 * The points become the note's text as a Markdown list ("- a\n- b"), which the notes screens
 * render. Stored notes are never parsed again, so the notes written before stay as they are.
 * Under the grammar the answer is exactly the JSON the prompt asks for. Without it (llama.cpp
 * rejected the grammar) a small model may fence the JSON, talk around it, use English keys under a
 * German prompt or write a summary instead of points, so each of these is looked for. An answer
 * cut off by the token limit keeps the points and action items it wrote out in full.
 */
object InsightOutputParser {

    /**
     * A note as read from an answer.
     *
     * @property echoes How many of the answer's title, points, summary and action items were left
     *   out as echoes of the instruction lines of the call's user message ([PromptEcho]); for the log
     * @property ruleEchoes How many of them were left out as they give back the rules of the call's
     *   system prompt ([PromptRules]), since 0.7.3; for the log
     * @property otherScript How many of them were left out as they are mostly in another script
     *   than the Latin one ([WrongScript]), since 0.7.3; for the log
     * @property repeatedLines How many of its points and action items were left out as they say
     *   nothing an earlier one of them doesn't ([NoteRepeats]), since 0.7.3; for the log
     * @property genericTitles 1 when the answer's title was left out as one that names only the
     *   kind of text ([SessionTitles.Verdict.Generic], [SessionTitles.Verdict.KindAlone]); for the log
     * @property emptyLines How many of the answer's title, points and action items were left out as
     *   they hold no word: blank, or the prompt's "..." placeholder copied; for the log
     * @property keptLines How many lines the note shows: its points, or its summary or prose as one,
     *   and its action items; 0 for a note of a title alone, or none
     * @property droppedLines How many of its points, its summary and its action items were left
     *   out, for any of the reasons above or with no word: an answer without a point that has some
     *   lost them all, one that has none wrote none in full
     */
    data class ParsedInsight(
        val title: String?,
        val content: String,
        val tasks: String?, // JSON array string, e.g. ["Task A","Task B"]
        val echoes: Int = 0,
        val genericTitles: Int = 0,
        val emptyLines: Int = 0,
        val keptLines: Int = if (content.isNotBlank()) 1 else 0,
        val droppedLines: Int = 0,
        val ruleEchoes: Int = 0,
        val otherScript: Int = 0,
        val repeatedLines: Int = 0
    ) {
        /**
         * Nothing to show: no title, no text, no action items, as from an answer that copied the
         * prompt's placeholders or was cut off inside its title. Such an answer makes no note.
         */
        val isEmpty: Boolean get() = title == null && content.isBlank() && tasks == null

        /**
         * Something to read besides a title: points, a summary, prose or action items. An answer
         * without is asked again ([GenerateFinalInsightUseCase], [GenerateBatchInsightUseCase]),
         * and a title alone makes no note.
         */
        val hasPoint: Boolean get() = content.isNotBlank() || tasks != null
    }

    private val SPACES = Regex("""\s+""")

    // A list marker the model put inside a point; the note writes its own.
    private val BULLET = Regex("""^[-*•–]\s+""")

    private val ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b', 'f' to '\u000C')

    /**
     * Parse an answer of [mode], whose prompt asked for [keys]; the English keys are tried next.
     *
     * - SIMPLE_LISTENING: the title and the points, or a summary when there are no points
     * - SHORT_MEETING / LONG_MEETING: the same, and the action items as a JSON array of strings
     * - REAL_TIME_TRANSLATION: the translated text as it is, without a title, and without quotes
     *   wrapped around all of it ([translationText])
     *
     * A title that only names the kind of text ([SessionTitles]) is dropped and counted
     * ([ParsedInsight.genericTitles]), unless its words are the note's own: it is read after the
     * points, the summary and the action items that are kept, which are its subject
     * ([SessionTitles.verdict]), so the notes of a recording about meetings and notes keep "Meeting
     * Notes and Minutes". An answer that is not JSON is prose and becomes the note as it is; JSON of
     * another shape too, so nothing is lost.
     *
     * The title, a point, the summary or an action item that repeats the call's [instructions]
     * ([NotesTopic.Prompt.instructions]) is left out and counted ([ParsedInsight.echoes]): "Text is
     * Malayalam." as a point. So are those that hold no word ([ParsedInsight.emptyLines]). An answer
     * with nothing left makes no note, as one that copied the placeholders makes none.
     */
    fun parse(rawOutput: String, mode: RecordingMode, keys: InsightFormat.Keys, instructions: List<String> = emptyList()): ParsedInsight =
        parse(rawOutput, mode, keys, AnswerChecks(instructions))

    /**
     * [parse] with every piece held to [checks] ([AnswerChecks]): the instruction lines of the
     * call's user message, counted in [ParsedInsight.echoes], and since 0.7.3 the rules of its
     * system prompt ([PromptRules]), counted in [ParsedInsight.ruleEchoes] ("Plain statements" and
     * "One idea per point" as points), and its script ([WrongScript]), counted in
     * [ParsedInsight.otherScript]: a title and points in Telugu script. Of the points kept, and of
     * the action items kept, one that says nothing an earlier one doesn't goes too ([NoteRepeats],
     * counted in [ParsedInsight.repeatedLines]): "Bluetooth firmware is essential" after
     * "Bluetooth firmware is needed". A title is held to [checks] as it is kept and, since the
     * review of 0.7.3, as the model wrote it: "Goal: Provide English notes" narrates the task,
     * though "Goal: Provide", what is left once the kind of text went from its end, says nothing.
     */
    fun parse(rawOutput: String, mode: RecordingMode, keys: InsightFormat.Keys, checks: AnswerChecks): ParsedInsight {
        if (mode == RecordingMode.REAL_TIME_TRANSLATION) return ParsedInsight(title = null, content = translationText(rawOutput), tasks = null)
        val cleaned = stripCodeFences(stripThinkingBlock(rawOutput))

        val start = cleaned.indexOf('{')
        if (start < 0) return prose(cleaned, checks)
        // Chatter before the JSON, or a fence that isn't the whole answer, is left out.
        val json = cleaned.substring(start)
        val english = InsightFormat.Keys.ENGLISH

        val pointLines = lines(stringArray(json, keys.points) ?: stringArray(json, english.points))
        val (checkedPoints, pointDrops) = checks.kept(pointLines.worded) { it }
        val (points, repeatedPoints) = NoteRepeats.withoutRepeats(checkedPoints)
        val (summaries, summaryDrops) = checks.kept(listOfNotNull(stringValue(json, keys.summary) ?: stringValue(json, english.summary))) { it }
        val summary = summaries.singleOrNull()
        val content = when {
            points.isNotEmpty() -> points.joinToString("\n") { "- $it" }
            !summary.isNullOrBlank() -> summary.trim()
            // The answer's own shape with no point in full yet: it was cut off early, or each point repeated the prompt.
            listOf(keys.title, keys.points, english.title, english.points).any { valueAt(json, it) != null } -> ""
            else -> cleaned
        }
        val actionLines = if (mode == RecordingMode.SIMPLE_LISTENING) {
            Lines(emptyList(), empty = 0)
        } else {
            lines(stringArray(json, keys.actionItems) ?: stringArray(json, english.actionItems))
        }
        val (checkedActions, actionDrops) = checks.kept(actionLines.worded) { it }
        val (actionItems, repeatedActions) = NoteRepeats.withoutRepeats(checkedActions)
        val tasks = actionItems.takeIf { it.isNotEmpty() }?.let { items -> JsonArray(items.map(::JsonPrimitive)).toString() }

        // After what the note says, which is the title's subject.
        val rawTitle = stringValue(json, keys.title) ?: stringValue(json, english.title)
        val verdict = rawTitle?.let { SessionTitles.verdict(it, subject = (listOf(content) + actionItems).joinToString("\n")) }
        val keptTitle = (verdict as? SessionTitles.Verdict.Kept)?.title
        val titleDrop = keptTitle?.let { checks.dropOfAny(it, rawTitle?.trim()) }
        val titles = listOfNotNull(keptTitle.takeIf { titleDrop == null })
        val titleDrops = titleDrop?.let { mapOf(it to 1) }.orEmpty()
        val generic = verdict == SessionTitles.Verdict.Generic || verdict == SessionTitles.Verdict.KindAlone
        val emptyTitle = verdict == SessionTitles.Verdict.Empty
        val lineDrops = listOf(pointDrops, summaryDrops, actionDrops)
        val allDrops = lineDrops + listOf(titleDrops)
        return ParsedInsight(
            title = titles.singleOrNull(),
            content = content,
            tasks = tasks,
            echoes = allDrops.sumOf { it[AnswerDrop.PROMPT] ?: 0 },
            genericTitles = if (generic) 1 else 0,
            emptyLines = pointLines.empty + actionLines.empty + if (emptyTitle) 1 else 0,
            keptLines = (if (points.isNotEmpty()) points.size else if (content.isNotBlank()) 1 else 0) + actionItems.size,
            droppedLines = lineDrops.sumOf { it.values.sum() } + pointLines.empty + actionLines.empty + repeatedPoints + repeatedActions,
            ruleEchoes = allDrops.sumOf { it[AnswerDrop.RULES] ?: 0 },
            otherScript = allDrops.sumOf { it[AnswerDrop.OTHER_SCRIPT] ?: 0 },
            repeatedLines = repeatedPoints + repeatedActions
        )
    }

    /**
     * An answer that is not JSON, the note as it is ([parse]), but for its lines that [checks]
     * leaves out, counted as a list's would be: an answer without the grammar is held to them too
     * (since 0.7.3). Nothing left is no note.
     */
    private fun prose(text: String, checks: AnswerChecks): ParsedInsight {
        val (kept, drops) = checks.kept(text.lines().map { it.trim() }.filter { it.isNotEmpty() }) { it }
        if (drops.isEmpty()) return ParsedInsight(title = null, content = text, tasks = null)
        return ParsedInsight(
            title = null,
            content = kept.joinToString("\n"),
            tasks = null,
            echoes = drops[AnswerDrop.PROMPT] ?: 0,
            droppedLines = drops.values.sum(),
            ruleEchoes = drops[AnswerDrop.RULES] ?: 0,
            otherScript = drops[AnswerDrop.OTHER_SCRIPT] ?: 0
        )
    }

    /** The items of an answer's list that hold a word ([worded]), and how many held none ([empty]). */
    private class Lines(val worded: List<String>, val empty: Int)

    /**
     * [items] each on one line without a list marker of its own, without repeats, and without
     * those that say nothing, which are counted: blank, or the prompt's "..." placeholder copied as
     * it is.
     */
    private fun lines(items: List<String>?): Lines {
        val all = items.orEmpty().map { BULLET.replace(SPACES.replace(it, " ").trim(), "").trim() }
        val worded = all.filter { item -> item.any { it.isLetterOrDigit() } }
        return Lines(worded.distinctBy { it.lowercase() }, empty = all.size - worded.size)
    }

    /** Where the value of [key] starts in [json], or null when the answer has no such key. */
    private fun valueAt(json: String, key: String): Int? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*").find(json)?.let { it.range.last + 1 }

    /** The string under [key] when the answer has it in full; null when it is missing or cut off. */
    private fun stringValue(json: String, key: String): String? {
        val at = valueAt(json, key) ?: return null
        if (json.getOrNull(at) != '"') return null
        return readString(json, at)?.first
    }

    /**
     * The strings of the array under [key] that the answer has in full, or null when it has no
     * such array. An answer cut off inside the array, or inside one of its strings, keeps those
     * before the cut.
     */
    private fun stringArray(json: String, key: String): List<String>? {
        val at = valueAt(json, key) ?: return null
        if (json.getOrNull(at) != '[') return null
        val items = mutableListOf<String>()
        var i = at + 1
        while (true) {
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (json.getOrNull(i) != '"') return items
            val (item, end) = readString(json, i) ?: return items
            items += item
            i = end
        }
    }

    /**
     * The JSON string whose opening quote is at [open], unescaped, and the index after its closing
     * quote; null when the text ends first. A quote ends the string only where JSON could go on
     * after it (before `,`, `}`, `]` or the end), so an unescaped quote inside a value, which small
     * models write without a grammar ("a "so-called" fix"), stays part of it.
     */
    private fun readString(text: String, open: Int): Pair<String, Int>? {
        val value = StringBuilder()
        var i = open + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> {
                    val escaped = text.getOrNull(i + 1) ?: return null
                    if (escaped == 'u') {
                        if (i + 6 > text.length) return null
                        val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) {
                            value.append(code.toChar())
                            i += 6
                            continue
                        }
                    }
                    value.append(ESCAPES[escaped] ?: escaped)
                    i += 2
                }
                c == '"' && endsString(text, i + 1) -> return value.toString() to i + 1
                else -> {
                    value.append(c)
                    i++
                }
            }
        }
        return null
    }

    private fun endsString(text: String, from: Int): Boolean {
        val next = (from until text.length).firstOrNull { !text[it].isWhitespace() } ?: return true
        return text[next] == ',' || text[next] == '}' || text[next] == ']'
    }

    /**
     * Strip reasoning/thinking blocks before parsing the JSON payload.
     *
     * Supported formats:
     *  - Qwen3.5: <think>...</think>
     *  - Gemma 4:  <|channel>thought\n...\n<channel|>
     *
     * NOTE: this is a *defensive fallback* only. The primary suppression for Qwen3.5
     * happens at the C++ layer in ai_chat.cpp by pre-filling an empty think block.
     * Gemma 4 thinking is stripped here because its Jinja2 template inserts <|think|>
     * automatically; a C++ suppression equivalent is not yet implemented.
     *
     * Reasoning tokens still consume the token budget even when stripped here,
     * degrading the quality of the JSON that follows them.
     */
    fun stripThinkingBlock(text: String): String {
        // Qwen3.5: <think>...</think>
        val qwenStart = text.indexOf("<think>")
        if (qwenStart != -1) {
            val qwenEnd = text.indexOf("</think>", startIndex = qwenStart)
            return if (qwenEnd == -1) {
                text.substring(0, qwenStart).trim()
            } else {
                (text.substring(0, qwenStart) + text.substring(qwenEnd + "</think>".length)).trim()
            }
        }
        // Gemma 4: <|channel>thought\n...\n<channel|>
        val gemmaStart = text.indexOf("<|channel>thought")
        if (gemmaStart != -1) {
            val gemmaEnd = text.indexOf("<channel|>", startIndex = gemmaStart)
            return if (gemmaEnd == -1) {
                text.substring(0, gemmaStart).trim()
            } else {
                (text.substring(0, gemmaStart) + text.substring(gemmaEnd + "<channel|>".length)).trim()
            }
        }
        return text
    }

    /**
     * A translation as it is to be read: without a thinking block or code fences, and without
     * quotes the model wrapped the whole of it in. Gemma 3 1B put one of three live translations
     * of a German meeting in curly quotes (2026-10-14). Quotes inside, a sentence quoting someone,
     * stay, and so does a text whose opening quote is closed before its end.
     */
    fun translationText(raw: String): String = withoutWrappingQuotes(stripCodeFences(stripThinkingBlock(raw)))

    /** [text] without one pair of quotes around all of it, when it holds no other quote of that pair. */
    private fun withoutWrappingQuotes(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length < 2) return trimmed
        val close = WRAPPING_QUOTES[trimmed.first()] ?: return trimmed
        if (trimmed.last() != close) return trimmed
        val inner = trimmed.substring(1, trimmed.length - 1)
        if (inner.any { it == trimmed.first() || it == close }) return trimmed
        return inner.trim()
    }

    /** Each opening quote, with the closing one that ends a text it wraps. */
    private val WRAPPING_QUOTES = mapOf(
        '"' to '"', '“' to '”', '„' to '“', '«' to '»', '»' to '«', '‘' to '’', '‚' to '‘', '\'' to '\''
    )

    /**
     * Remove Markdown code fences that small models sometimes emit despite instructions.
     * Handles variants: ```json, ```JSON, ``` (plain), and leading/trailing whitespace.
     */
    fun stripCodeFences(text: String): String {
        val trimmed = text.trim()
        val codeFenceRegex = Regex("""^```[a-zA-Z]*\s*\n?([\s\S]*?)\n?```$""")
        val match = codeFenceRegex.find(trimmed)
        return match?.groupValues?.getOrNull(1)?.trim() ?: trimmed
    }

    /**
     * Extract a string field from a JSON-like string.
     *
     * Unlike a regex approach, this scanner handles unescaped inner quotes that small
     * models sometimes emit (e.g. `"variabili nascoste che "nascondono" le info"`).
     * A `"` is treated as the closing delimiter only when the next non-whitespace
     * character is `,` or `}` — indicating a JSON boundary.
     */
    fun extractJsonField(json: String, field: String): String? {
        val keyPattern = Regex(""""$field"\s*:\s*"""")
        val match = keyPattern.find(json) ?: return null
        var i = match.range.last + 1
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                sb.append(json[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                val rest = json.substring(i + 1).trimStart()
                if (rest.startsWith(',') || rest.startsWith('}')) return sb.toString()
                i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString().ifBlank { null }
    }
}
