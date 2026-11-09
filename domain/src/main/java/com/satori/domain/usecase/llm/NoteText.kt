package com.satori.domain.usecase.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What of a note is worth showing, so the details page, the recorder's notes tab and the
 * Markdown export all read the same.
 */
object NoteText {

    private val json = Json { isLenient = true }

    /**
     * The title to show above a note, or null to show none.
     *
     * A note has no title when the model gave none (a translation has none), and one that only
     * repeats the recording's own name says nothing the page doesn't say already.
     *
     * @param title The note's title as stored.
     * @param recordingTitle The recording's name as the page shows it; null where there is none
     *   to compare with.
     */
    fun visibleTitle(title: String?, recordingTitle: String?): String? {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (recordingTitle != null && trimmed.equals(recordingTitle.trim(), ignoreCase = true)) return null
        return trimmed
    }

    /**
     * The action items stored with a note: a JSON array of strings or, in older notes, of objects
     * with a "description" (or "task") field. Entries without text are dropped; a [tasksJson]
     * that is not such an array has no items.
     */
    fun actionItems(tasksJson: String?): List<String> {
        if (tasksJson.isNullOrBlank()) return emptyList()
        val array = try {
            json.parseToJsonElement(tasksJson) as? JsonArray
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization reports malformed JSON as SerializationException, a subclass.
            null
        } ?: return emptyList()
        return array.map { itemText(it).trim() }.filter { it.isNotEmpty() }
    }

    private fun itemText(item: JsonElement): String =
        if (item is JsonObject) text(item["description"]).ifBlank { text(item["task"]) } else text(item)

    /** The text of a JSON value; nothing for a missing or null one. */
    private fun text(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    /**
     * [items] that [shown] does not already list. Items are compared without case or
     * surrounding space, and a repeat within [items] itself is dropped too.
     *
     * The overall summary of a long recording lists the action items of the whole, and the notes
     * of its parts list their own; the same item twice only makes the page longer.
     */
    fun withoutRepeats(items: List<String>, shown: Collection<String>): List<String> {
        val seen = shown.mapTo(HashSet()) { key(it) }
        return items.filter { seen.add(key(it)) }
    }

    private fun key(item: String): String = item.trim().lowercase()
}
