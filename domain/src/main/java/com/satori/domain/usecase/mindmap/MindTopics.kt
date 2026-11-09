package com.satori.domain.usecase.mindmap

import com.satori.domain.model.LlmInsight
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.usecase.llm.AnswerChecks
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.InsightOutputParser
import com.satori.domain.usecase.llm.TextBudget
import com.satori.domain.usecase.llm.groupInsights
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * The topics call of [MapRecordingUseCase]: what the model reads, the JSON schema its answer is
 * held to, and the checks a topic must pass before it goes into the map.
 *
 * The model only proposes. A topic is kept when every content word of its label ([MindWords])
 * occurs in the recording's transcript or notes, also as part of a compound ([MindWords.inVocabulary]), so a 1B model can't put a topic into the map
 * that the recording doesn't speak of, nor copy one from the prompt's example. Its label is cut to
 * [MAX_LABEL_WORDS] words and its points are tidied ([points]).
 */
internal object MindTopics {

    /** The most topics one recording gives. */
    const val MAX_TOPICS = 3

    /** The most points a topic of one recording holds. */
    const val MAX_POINTS = 3

    /** The longest label kept, in words. */
    const val MAX_LABEL_WORDS = 4

    /** The longest point kept, in words; a longer one is cut and ends with "…". */
    const val MAX_POINT_WORDS = 20

    /**
     * The most the model reads, in characters: the batch pipeline's chunk size (MAX_CHUNK_CHARS,
     * private to GenerateBatchInsightUseCase), about 1,200 tokens, which leaves the 4,096-token
     * context room for the prompt and the answer. Set for English and German: of a Malayalam text,
     * the share that costs as many tokens ([TextBudget.chars]).
     */
    const val MAX_TEXT_CHARS = 5_000

    // Bounds for the grammar, above what the prompt asks for, so that they hardly ever cut a
    // label or a point the code would keep, but stop a runaway answer.
    private const val MAX_LABEL_CHARS = 60
    private const val MAX_POINT_CHARS = 220

    // A bullet with the space after it. Not a number: "3. Oktober" is a date, "-5 Grad" a minus,
    // and the grammar keeps the model from numbering inside a JSON string anyway.
    private val BULLET = Regex("""^[-*•–]+\s+""")
    private const val EDGE_CHARACTERS = "${MindText.QUOTES}.,;:!?-–—…·*#"

    // The schema's keys first, then the names a model reaches for when llama.cpp dropped the grammar.
    private val LABEL_KEYS = listOf("label", "topic", "title", "name")
    private val POINT_KEYS = listOf("points", "details", "key_points")

    /** A topic as the model wrote it, before any check. */
    data class Answered(val label: String, val points: List<String>)

    /**
     * A topic that held up.
     *
     * @property label Its label, tidied: at most [MAX_LABEL_WORDS] words, first letter upper case
     * @property key Its match key ([ThemeMatcher.keyOf]); never empty
     * @property points Its points, tidied ([points])
     */
    data class Topic(val label: String, val key: String, val points: List<String>)

    /**
     * A segment of the transcript with the stems of its content words, worked out once per
     * recording for the check of the labels and for the segments each topic came from.
     */
    class Spoken(val segmentId: String, val stems: Set<String>)

    /**
     * The JSON schema of the answer: {"topics": [{"label": "...", "points": ["..."]}]}, with at
     * most [MAX_TOPICS] topics of at most [MAX_POINTS] points. Stays within the subset llama.cpp
     * turns into a grammar.
     */
    val SCHEMA: String = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("topics") {
                put("type", "array")
                put("maxItems", MAX_TOPICS)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("label") {
                            put("type", "string")
                            put("minLength", 1)
                            put("maxLength", MAX_LABEL_CHARS)
                        }
                        putJsonObject("points") {
                            put("type", "array")
                            put("maxItems", MAX_POINTS)
                            putJsonObject("items") {
                                put("type", "string")
                                put("minLength", 1)
                                put("maxLength", MAX_POINT_CHARS)
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("label")
                        add("points")
                    }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add("topics") }
        put("additionalProperties", false)
    }.toString()

    /**
     * What the model reads: the recording's notes, each on its own line as its title and summary
     * (the overall summary first, the others oldest first, as the details page shows them), or
     * its [transcript] when it has none. Cut at a word to [MAX_TEXT_CHARS], or to the share of it a
     * Malayalam text holds in as many tokens.
     */
    fun input(session: TranscriptionSession, notes: List<LlmInsight>, transcript: String): String {
        val lines = groupInsights(notes, session).all.mapNotNull(::noteLine)
        val text = if (lines.isEmpty()) transcript else lines.joinToString("\n")
        return MindText.cut(text.trim(), TextBudget.chars(MAX_TEXT_CHARS, text))
    }

    /** Whether [input] reads the recording's [notes] (one of them has a line to read), not its transcript. */
    fun readsNotes(session: TranscriptionSession, notes: List<LlmInsight>): Boolean =
        groupInsights(notes, session).all.any { noteLine(it) != null }

    /** [segments], in the order given (spoken order), each with the stems of its content words. */
    fun spoken(segments: List<TranscriptionSegment>): List<Spoken> =
        segments.map { Spoken(it.id, MindWords.contentStems(it.text).toSet()) }

    /**
     * All the words a label may take: those of the transcript ([spoken]) and of every note, read
     * the way the model reads the notes (an older note's JSON gives its summary, not its keys).
     */
    fun vocabulary(spoken: List<Spoken>, notes: List<LlmInsight>): Set<String> {
        val words = spoken.flatMapTo(HashSet()) { it.stems }
        for (note in notes) words += MindWords.contentStems(noteLine(note).orEmpty())
        return words
    }

    /**
     * The topics of [answer], as written, or null when it holds no JSON that can be read. Under
     * the grammar it is exactly {"topics": [...]}. A model that answered without it (llama.cpp
     * rejects a grammar now and then) may wrap the JSON in chatter or code fences, answer with a
     * bare list, or stop at the token limit; the JSON is looked for inside whatever surrounds it,
     * and an answer cut off midway keeps the topics written out in full. Only an empty list of
     * topics is an answer without topics: a list that holds something, but no topic, is chatter
     * ("[Note] ...") or the wrong shape, and the topics are looked for beyond it.
     */
    fun parse(answer: String): List<Answered>? {
        val text = InsightOutputParser.stripThinkingBlock(answer)
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val whole = JsonScan.endOfValue(text, start)?.let { end -> JsonScan.parseOrNull(text.substring(start, end)) }
        whole?.let(::topicsIn)?.let { return it }
        val salvaged = JsonScan.innermostObjects(text, start)
            .mapNotNull { JsonScan.parseOrNull(it) as? JsonObject }
            .mapNotNull(::answeredOf)
        return salvaged.ifEmpty { null }
    }

    /**
     * [topics] without what [AnswerChecks] leaves out ([screened]), and how many it left out, by why:
     * of the topics, whose label went, and of the other topics' points.
     */
    class Screened(val topics: List<Answered>, val droppedTopics: Map<AnswerDrop, Int>, val droppedPoints: Map<AnswerDrop, Int>)

    /**
     * [answered] held to [checks] before the check of its labels' words ([kept]), since 0.7.3: a
     * topic whose label is left out goes with its points ("Plain Statements" as a label of words
     * the notes hold), and of the other topics the points left out go ("Write them as the person's
     * own notes").
     */
    fun screened(answered: List<Answered>, checks: AnswerChecks): Screened {
        val (topics, droppedTopics) = checks.kept(answered) { it.label }
        val droppedPoints = mutableMapOf<AnswerDrop, Int>()
        val screened = topics.map { topic ->
            val (points, drops) = checks.kept(topic.points) { it }
            drops.forEach { (why, count) -> droppedPoints[why] = (droppedPoints[why] ?: 0) + count }
            topic.copy(points = points)
        }
        return Screened(screened, droppedTopics, droppedPoints)
    }

    /**
     * The topics of [answered] that hold up, in their order and at most [MAX_TOPICS]: those with
     * a label that has content words, all of them in [vocabulary] (the words of the recording's
     * transcript and notes).
     */
    fun kept(answered: List<Answered>, vocabulary: Set<String>): List<Topic> =
        answered.asSequence()
            .mapNotNull { topic ->
                val label = label(topic.label) ?: return@mapNotNull null
                if (!MindWords.contentStems(label).all { MindWords.inVocabulary(it, vocabulary) }) return@mapNotNull null
                Topic(label, ThemeMatcher.keyOf(label), points(topic.points))
            }
            .take(MAX_TOPICS)
            .toList()

    /**
     * [raw] as a label: on one line, without quotes, a bullet or punctuation at its ends, cut to
     * [MAX_LABEL_WORDS] words without a stop word at its end ("Repair of the" is "Repair"), first
     * letter upper case. Null when no content word is left ("Of the").
     */
    fun label(raw: String): String? {
        var words = tidy(raw).split(' ').filter { it.isNotEmpty() }.take(MAX_LABEL_WORDS)
        while (words.isNotEmpty() && !endsWithContent(words.last())) words = words.dropLast(1)
        val label = trimEdges(words.joinToString(" "))
        if (MindWords.contentStems(label).isEmpty()) return null
        return label.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /**
     * [raw] as points: each on one line, without quotes or a bullet at its ends, first letter
     * upper case, and at most [MAX_POINT_WORDS] words (a longer one is cut, loses a stop word at
     * its end and ends with "…"). Points with no letter or digit go, and so do repeats, whatever
     * their case, accents and punctuation; at most [MAX_POINTS] are kept.
     */
    fun points(raw: List<String>): List<String> {
        val seen = HashSet<String>()
        return raw.asSequence()
            .mapNotNull(::point)
            .filter { seen.add(MindWords.words(it).joinToString(" ")) }
            .take(MAX_POINTS)
            .toList()
    }

    /**
     * The segments the topic with the content stems [stems] came from, in the order of [spoken]
     * (spoken order): those that hold the most of its words, as long as they hold one. Empty when
     * none does, as for a topic the model took from the notes.
     */
    fun segmentIds(stems: Set<String>, spoken: List<Spoken>): List<String> {
        val held = spoken.map { it to it.stems.count { stem -> stem in stems } }
        val most = held.maxOfOrNull { it.second } ?: 0
        if (most == 0) return emptyList()
        return held.filter { it.second == most }.map { it.first.segmentId }
    }

    private fun point(raw: String): String? {
        val words = tidy(raw).split(' ').filter { it.isNotEmpty() }
        if (words.none { word -> word.any { it.isLetterOrDigit() } }) return null
        val text = if (words.size <= MAX_POINT_WORDS) {
            words.joinToString(" ")
        } else {
            var kept = words.take(MAX_POINT_WORDS)
            while (kept.size > 1 && !endsWithContent(kept.last())) kept = kept.dropLast(1)
            trimEdges(kept.joinToString(" ")) + MindText.ELLIPSIS
        }
        return text.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /** On one line, without a bullet or quotes at its ends. Other punctuation stays. */
    private fun tidy(raw: String): String =
        BULLET.replace(MindText.oneLine(raw), "").trim { it in MindText.QUOTES || it.isWhitespace() }

    private fun trimEdges(text: String): String = text.trim { it in EDGE_CHARACTERS || it.isWhitespace() }

    /** Whether [word], without punctuation, is a content word: a label or a cut point may end on it. */
    private fun endsWithContent(word: String): Boolean =
        MindWords.words(word).lastOrNull()?.let(MindWords::isContentWord) == true

    /** A note as one line: its title, then its summary. Null for a note with neither. */
    private fun noteLine(note: LlmInsight): String? {
        val title = MindText.oneLine(note.title.orEmpty())
        val summary = MindText.oneLine(summaryOf(note.content))
        return when {
            title.isEmpty() && summary.isEmpty() -> null
            title.isEmpty() -> summary
            summary.isEmpty() -> title
            else -> "$title: $summary"
        }
    }

    /** Older notes store their content as JSON with a summary field. */
    private fun summaryOf(content: String): String {
        if (!content.trimStart().startsWith("{")) return content
        return InsightOutputParser.extractJsonField(content, "summary")
            ?: InsightOutputParser.extractJsonField(content, "zusammenfassung")
            ?: content
    }

    /**
     * The topics in [element]: its topics list, a bare list of topics, or one topic. Null when it
     * holds none and isn't an empty list either, so the answer is looked at further.
     */
    private fun topicsIn(element: JsonElement): List<Answered>? = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonObject)?.let(::answeredOf) }
            .takeIf { it.isNotEmpty() || element.isEmpty() }
        is JsonObject -> when (val topics = element["topics"]) {
            is JsonArray -> topicsIn(topics)
            else -> answeredOf(element)?.let(::listOf)
        }
        else -> null
    }

    private fun answeredOf(item: JsonObject): Answered? {
        val label = LABEL_KEYS.firstNotNullOfOrNull { (item[it] as? JsonPrimitive)?.contentOrNull } ?: return null
        val points = when (val value = POINT_KEYS.firstNotNullOfOrNull { item[it] }) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }
        return Answered(label, points)
    }
}
