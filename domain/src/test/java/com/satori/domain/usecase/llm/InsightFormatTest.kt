package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.model.RecordingMode
import com.satori.domain.provider.ResourceProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class InsightFormatTest {

    private val english = InsightFormat.Keys.ENGLISH
    private val german = LocalizedResourceProvider().let { InsightFormat.keys(it, "de") }

    private fun JsonObject.property(name: String): JsonObject = getValue("properties").jsonObject.getValue(name).jsonObject
    private fun JsonObject.number(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.names(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `a voice note answers with a title and one to six points, and nothing else`() {
        val schema = InsightFormat.grammar(RecordingMode.SIMPLE_LISTENING, english).schema()

        assertEquals(listOf("title", "points"), InsightFormat.grammar(RecordingMode.SIMPLE_LISTENING, english).answerKeys())
        assertEquals(listOf("title", "points"), schema.names("required"))
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)

        val points = schema.property("points")
        assertEquals(1, points.number("minItems"))
        assertEquals(InsightFormat.MAX_POINTS, points.number("maxItems"))
        assertEquals(6, InsightFormat.MAX_POINTS)
        val point = points.getValue("items").jsonObject
        assertEquals("string", point.getValue("type").jsonPrimitive.content)
        assertEquals(InsightFormat.MAX_POINT_CHARS, point.number("maxLength"))
        assertEquals(InsightFormat.MAX_TITLE_CHARS, schema.property("title").number("maxLength"))
        // An empty title or point is not an answer.
        assertEquals(2, point.number("minLength"))
        assertEquals(2, schema.property("title").number("minLength"))
    }

    @Test
    fun `a meeting adds action items, none of them required, and allows eight points`() {
        for (mode in listOf(RecordingMode.SHORT_MEETING, RecordingMode.LONG_MEETING)) {
            val grammar = InsightFormat.grammar(mode, english)
            val schema = grammar.schema()

            assertEquals(listOf("title", "points", "action_items"), grammar.answerKeys())
            assertEquals(listOf("title", "points", "action_items"), schema.names("required"))
            assertEquals(8, schema.property("points").number("maxItems"))
            assertEquals(0, schema.property("action_items").number("minItems"))
            assertEquals(InsightFormat.MAX_ACTION_ITEMS, schema.property("action_items").number("maxItems"))
        }
    }

    @Test
    fun `the answer's keys are those of the prompt's language`() {
        assertEquals(listOf("titel", "punkte"), InsightFormat.grammar(RecordingMode.SIMPLE_LISTENING, german).answerKeys())
        assertEquals(
            listOf("titel", "punkte", "aktionen"),
            InsightFormat.grammar(RecordingMode.LONG_MEETING, german).answerKeys()
        )
    }

    @Test
    fun `a translation is free text`() {
        assertNull(InsightFormat.grammar(RecordingMode.REAL_TIME_TRANSLATION, english))
    }

    @Test
    fun `the keys come from the language's strings, or the app language's without one`() {
        assertEquals(InsightFormat.Keys("titel", "punkte", "zusammenfassung", "aktionen"), german)
        assertEquals(english, InsightFormat.keys(LocalizedResourceProvider(), "en"))

        val appLanguage = object : ResourceProvider by FakeResourceProvider() {
            override fun getJsonFieldTitle() = "app-title"
            override fun getJsonFieldPoints() = "app-points"
            override fun getJsonFieldSummary() = "app-summary"
            override fun getJsonFieldActionItems() = "app-actions"
        }
        val expected = InsightFormat.Keys("app-title", "app-points", "app-summary", "app-actions")
        assertEquals(expected, InsightFormat.keys(appLanguage, null))
        assertEquals(expected, InsightFormat.keys(appLanguage, " "))
    }
}
