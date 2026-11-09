package com.satori.domain.usecase.llm

import com.satori.domain.model.RecordingMode
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.OutputGrammar
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The answer the notes prompts ask for, and the grammar that holds the model to it.
 *
 * A voice note's answer is {"title": "...", "points": ["...", "..."]}; a meeting's adds
 * "action_items". The keys are those of the prompt's language (German: "titel", "punkte",
 * "aktionen"), read from the same strings the prompt names them with, so the prompt, the grammar
 * and [InsightOutputParser] agree. llama.cpp turns the schema into a grammar while it samples, so
 * the 1B model can't drift into prose, Markdown or code fences, nor write more points than the
 * prompt allows. The prompt still describes the shape: the model never sees the grammar.
 */
object InsightFormat {

    /** Points of a voice note's answer: one interval while recording, the tail after Stop, one part. */
    const val MAX_POINTS = 6

    /** Points of a meeting's answer. */
    const val MAX_MEETING_POINTS = 8

    /** Action items of a meeting's answer, as many as the to-do extraction takes from one call. */
    const val MAX_ACTION_ITEMS = 8

    /**
     * Longest point. The prompts ask for fewer than 20 words, about 150 characters in German, and
     * the grammar ends a longer point mid-word, so the limit leaves room.
     */
    const val MAX_POINT_CHARS = 180

    /** Longest title; [SessionTitles.clean] shortens it at a word to its own limit. */
    const val MAX_TITLE_CHARS = 80

    /** Longest action item, about fifteen long German words, like a to-do's text. */
    const val MAX_ACTION_ITEM_CHARS = 120

    // An empty title or point says nothing, so the grammar asks for at least this many characters.
    private const val MIN_TEXT_CHARS = 2

    /**
     * The JSON keys of one language's notes prompts.
     *
     * @property summary Not asked for any more: read from an answer that writes a summary anyway
     */
    data class Keys(val title: String, val points: String, val summary: String, val actionItems: String) {
        companion object {
            /** The keys a small model reaches for whatever the language of the prompt. */
            val ENGLISH = Keys(title = "title", points = "points", summary = "summary", actionItems = "action_items")
        }
    }

    /**
     * The keys of the prompts in [language] ("en" or "de"), or of the app's language when there is
     * none: the prompts in the settings are in the app's language then.
     */
    fun keys(resources: ResourceProvider, language: String?): Keys =
        if (language.isNullOrBlank()) {
            Keys(
                title = resources.getJsonFieldTitle(),
                points = resources.getJsonFieldPoints(),
                summary = resources.getJsonFieldSummary(),
                actionItems = resources.getJsonFieldActionItems()
            )
        } else {
            Keys(
                title = resources.getJsonFieldTitle(language),
                points = resources.getJsonFieldPoints(language),
                summary = resources.getJsonFieldSummary(language),
                actionItems = resources.getJsonFieldActionItems(language)
            )
        }

    /** The grammar for an answer of [mode] written with [keys]; none for a translation, which is free text. */
    fun grammar(mode: RecordingMode, keys: Keys): OutputGrammar? = when (mode) {
        RecordingMode.REAL_TIME_TRANSLATION -> null
        RecordingMode.SIMPLE_LISTENING -> OutputGrammar.JsonSchema(schema(keys, MAX_POINTS, actionItems = false))
        RecordingMode.SHORT_MEETING, RecordingMode.LONG_MEETING ->
            OutputGrammar.JsonSchema(schema(keys, MAX_MEETING_POINTS, actionItems = true))
    }

    /**
     * The title, 1 to [maxPoints] points and, for a meeting, up to [MAX_ACTION_ITEMS] action items
     * (none is fine), all of them required and nothing else. llama.cpp writes the keys in the
     * order of the properties, the order the prompt shows. Stays within the subset llama.cpp turns
     * into a grammar.
     */
    private fun schema(keys: Keys, maxPoints: Int, actionItems: Boolean): String = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(keys.title) { putText(MAX_TITLE_CHARS) }
            putTexts(keys.points, minItems = 1, maxItems = maxPoints, maxChars = MAX_POINT_CHARS)
            if (actionItems) putTexts(keys.actionItems, minItems = 0, maxItems = MAX_ACTION_ITEMS, maxChars = MAX_ACTION_ITEM_CHARS)
        }
        putJsonArray("required") {
            add(keys.title)
            add(keys.points)
            if (actionItems) add(keys.actionItems)
        }
        put("additionalProperties", false)
    }.toString()

    private fun JsonObjectBuilder.putTexts(name: String, minItems: Int, maxItems: Int, maxChars: Int) {
        putJsonObject(name) {
            put("type", "array")
            put("minItems", minItems)
            put("maxItems", maxItems)
            putJsonObject("items") { putText(maxChars) }
        }
    }

    private fun JsonObjectBuilder.putText(maxChars: Int) {
        put("type", "string")
        put("minLength", MIN_TEXT_CHARS)
        put("maxLength", maxChars)
    }
}
