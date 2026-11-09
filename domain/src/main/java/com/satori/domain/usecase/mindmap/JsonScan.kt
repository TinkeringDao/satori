package com.satori.domain.usecase.mindmap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Finds JSON inside a model's answer by its brackets, before parsing it: where a value ends, and
 * which objects are complete. Braces inside JSON strings don't count. The mind map's two calls
 * read their answers with it ([MindTopics], [ThemeSummaryPrompt]).
 */
internal object JsonScan {

    // Lenient, as for the to-do answers: a model without its grammar may leave keys unquoted.
    private val json = Json { isLenient = true }

    /** [text] parsed as JSON, or null when it isn't JSON. */
    fun parseOrNull(text: String): JsonElement? =
        try {
            json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization reports malformed JSON as SerializationException, a subclass.
            null
        }

    /** Index just past the JSON object or array that starts at [start], or null when it never closes. */
    fun endOfValue(text: String, start: Int): Int? {
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

    /**
     * Every complete object from [start] on that holds no object itself, in order: the items of an
     * answer that was cut off before its outer brackets closed.
     */
    fun innermostObjects(text: String, start: Int): List<String> {
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

    /** Follows JSON strings character by character, so brackets inside them don't count. */
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
