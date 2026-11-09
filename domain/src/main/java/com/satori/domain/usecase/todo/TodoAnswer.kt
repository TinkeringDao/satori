package com.satori.domain.usecase.todo

import com.satori.domain.usecase.llm.InsightOutputParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the model's answer to the todo prompt into [Item]s, with every value as written.
 * Checking the values against the prompt is the caller's job.
 *
 * Under the grammar the answer is exactly {"items": [...]}, to-dos and ideas. Answers of earlier
 * builds, {"todos": [...]} with "kind" and "certainty", read as well: the first list in the answer
 * is taken, and a key the item has no use for is left. Without the grammar, small models wrap the
 * JSON in code fences or chatter, answer with a bare list, or stop at the token limit; Gemma 4
 * can also let a thinking block through a JSON schema. So the JSON is looked for inside whatever
 * surrounds it, and an answer cut off mid-way keeps the items that were written out in full.
 */
internal object TodoAnswer {

    /**
     * One to-do or idea as the model wrote it.
     *
     * @property segments The cited segment ids ("S3"); usually one
     * @property text What to do, or for an idea the change it names
     * @property time The chosen time id ("T2"), "none", or null when missing (an idea has none)
     * @property kind Its type: "task", "appointment" or "idea" ("kind" in earlier answers)
     * @property capture How a to-do came up: "explicit", "commitment", "asked" or "inferred"
     * @property said The words of its segment it was read from, as written there: asked for when
     *   the text is written in another language than the segments ([TodoPrompt.schema] with
     *   quotes), so that it can be checked against them ([TodoGrounding]); null otherwise
     */
    data class Item(
        val segments: List<String>,
        val text: String?,
        val time: String?,
        val kind: String?,
        val capture: String?,
        val said: String? = null
    ) {
        /** The model called it an idea. */
        val idea: Boolean get() = kind.equals(IDEA, ignoreCase = true)
    }

    /** The type of an idea. */
    const val IDEA = "idea"

    private val json = Json { isLenient = true }

    // The schema's keys first, then the names small models reach for without a grammar. "kind" is
    // the key of earlier answers, "type" the schema's.
    private val TEXT_KEYS = listOf("text", "task", "todo", "title", "description")
    private val SEGMENT_KEYS = listOf("segment", "segments", "source", "seg")
    private val TIME_KEYS = listOf("when", "time", "due")
    private val KIND_KEYS = listOf("kind", "type")
    private val SAID_KEYS = listOf("said", "quote")
    private val ID_SEPARATORS = Regex("""[,;\s]+""")

    fun parse(answer: String): List<Item> {
        val text = InsightOutputParser.stripThinkingBlock(answer)
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return emptyList()
        val whole = endOfValue(text, start)?.let { end -> parseOrNull(text.substring(start, end)) }?.let(::itemsIn)
        if (!whole.isNullOrEmpty()) return whole
        // Cut off, malformed, or the first brackets were chatter: keep each todo object that is
        // complete on its own.
        return innermostObjects(text, start)
            .mapNotNull { parseOrNull(it) as? JsonObject }
            .filter(::isTodo)
            .map(::itemOf)
    }

    private fun itemsIn(element: JsonElement): List<Item> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }.filter(::isTodo).map(::itemOf)
        is JsonObject -> when {
            isTodo(element) -> listOf(itemOf(element))
            else -> (element["todos"] as? JsonArray ?: element.values.firstOrNull { it is JsonArray })
                ?.let(::itemsIn)
                .orEmpty()
        }
        else -> emptyList()
    }

    private fun isTodo(item: JsonObject): Boolean = TEXT_KEYS.any { it in item }

    private fun itemOf(item: JsonObject) = Item(
        segments = idsOf(item),
        text = stringOf(item, TEXT_KEYS),
        time = stringOf(item, TIME_KEYS),
        kind = stringOf(item, KIND_KEYS),
        capture = stringOf(item, listOf("capture")),
        said = stringOf(item, SAID_KEYS)
    )

    private fun stringOf(item: JsonObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> (item[key] as? JsonPrimitive)?.contentOrNull?.trim() }

    /** One id, a list of ids, or ids in one string ("S3, S4"). */
    private fun idsOf(item: JsonObject): List<String> {
        val ids = when (val value = SEGMENT_KEYS.firstNotNullOfOrNull { item[it] }) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }
        return ids.flatMap { it.split(ID_SEPARATORS) }.filter { it.isNotEmpty() }
    }

    private fun parseOrNull(text: String): JsonElement? =
        try {
            json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization reports malformed JSON as SerializationException, a subclass.
            null
        }

    /** Index just past the JSON value that starts at [start], or null when it never closes. */
    private fun endOfValue(text: String, start: Int): Int? {
        var depth = 0
        val strings = StringTracker()
        for (i in start until text.length) {
            val c = text[i]
            if (strings.inString(c)) continue
            when (c) {
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return i + 1
            }
        }
        return null
    }

    /** Every complete object from [start] on that holds no object itself: the todo items. */
    private fun innermostObjects(text: String, start: Int): List<String> {
        val objects = mutableListOf<String>()
        var open = -1
        val strings = StringTracker()
        for (i in start until text.length) {
            val c = text[i]
            if (strings.inString(c)) continue
            when (c) {
                '{' -> open = i
                '}' -> if (open >= 0) {
                    objects += text.substring(open, i + 1)
                    open = -1
                }
            }
        }
        return objects
    }

    /** Follows JSON strings character by character, so braces inside them don't count. */
    private class StringTracker {
        private var inside = false
        private var escaped = false

        /** True when [c] is part of a string, its quotes included. */
        fun inString(c: Char): Boolean {
            if (inside) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inside = false
                }
                return true
            }
            if (c == '"') {
                inside = true
                return true
            }
            return false
        }
    }
}
