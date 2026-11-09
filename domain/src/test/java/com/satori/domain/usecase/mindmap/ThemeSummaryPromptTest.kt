package com.satori.domain.usecase.mindmap

import com.satori.domain.model.MindLink
import com.satori.domain.usecase.mindmap.ThemeSummaryPrompt.Recording
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ThemeSummaryPromptTest {

    // 10 Oct 2026 12:00 and 12 Oct 2026 09:00, UTC
    private val tenth = 1_791_633_600_000L
    private val twelfth = 1_791_795_600_000L

    private fun link(topicId: String, sessionId: String, at: Long, vararg points: String) =
        MindLink(topicId, sessionId, points.toList(), createdAt = 0L, recordedAt = at)

    @Test
    fun `the recordings are read newest first, each once with the points of all its links`() {
        val links = listOf(
            link("roof", "r2", twelfth, "The roof leaks.", "Boards cost 80 euros."),
            link("paint", "r2", twelfth, "the roof leaks", "Paint is needed."),
            link("roof", "r1", tenth, "The shed is old.")
        )

        assertEquals(
            listOf(
                Recording("r2", twelfth, listOf("The roof leaks.", "Boards cost 80 euros.", "Paint is needed.")),
                Recording("r1", tenth, listOf("The shed is old."))
            ),
            ThemeSummaryPrompt.recordings(links)
        )
    }

    @Test
    fun `only the eight newest recordings with points are read`() {
        val links = (1..10).map { link("t", "r$it", it * 1_000L, "Point $it.") } + link("t", "r11", 11_000L)

        val recordings = ThemeSummaryPrompt.recordings(links.sortedByDescending { it.recordedAt })

        assertEquals((10 downTo 3).map { "r$it" }, recordings.map { it.sessionId })
    }

    @Test
    fun `the model reads the label, then each recording's date and points`() {
        val recordings = listOf(Recording("r2", twelfth, listOf("The roof leaks.", "Paint is needed.")), Recording("r1", tenth, listOf("The shed is old.")))

        assertEquals(
            "Garden shed\n12 Oct 2026: The roof leaks. Paint is needed.\n10 Oct 2026: The shed is old.",
            ThemeSummaryPrompt.input("Garden  shed", recordings, "en", ZoneOffset.UTC)
        )
        assertEquals(
            "Gartenhaus\n10. Okt. 2026: Das Dach ist undicht.",
            ThemeSummaryPrompt.input("Gartenhaus", listOf(Recording("r1", tenth, listOf("Das Dach ist undicht."))), "de", ZoneOffset.UTC)
        )
    }

    @Test
    fun `a long recording line is cut at a word`() {
        val points = List(3) { "word ".repeat(60).trim() }

        val line = ThemeSummaryPrompt.input("Theme", listOf(Recording("r1", tenth, points)), "en", ZoneOffset.UTC).lines()[1]

        assertTrue(line.length <= 600)
        assertTrue(line.endsWith("word"))
    }

    @Test
    fun `a summary is read from its JSON, on one line and without Markdown or quotes`() {
        assertEquals(
            "The roof leaks. It gets fixed in spring.",
            ThemeSummaryPrompt.parse("""{"summary": "**The roof leaks.**\n\n- It gets fixed in spring."}""")
        )
        assertEquals("Quoted.", ThemeSummaryPrompt.parse("""Here: {"summary": "\"Quoted.\""} done"""))
    }

    @Test
    fun `an answer cut off, without JSON or with an empty summary can't be read`() {
        assertNull(ThemeSummaryPrompt.parse("""{"summary": "The roof"""))
        assertNull(ThemeSummaryPrompt.parse("The roof leaks."))
        assertNull(ThemeSummaryPrompt.parse("""{"summary": "  ..  "}"""))
        assertNull(ThemeSummaryPrompt.parse("""{"text": "The roof leaks."}"""))
    }

    @Test
    fun `a summary too long is cut at a sentence end`() {
        val sentence = "The shed needs work. "
        val answer = """{"summary": "${sentence.repeat(50).trim()}"}"""

        val summary = ThemeSummaryPrompt.parse(answer)!!

        assertTrue(summary.length <= ThemeSummaryPrompt.MAX_SUMMARY_CHARS)
        assertTrue(summary.endsWith("work."))
    }

    @Test
    fun `a summary too long without a sentence end is cut at a word and marked`() {
        val answer = """{"summary": "${"word ".repeat(200).trim()}"}"""

        val summary = ThemeSummaryPrompt.parse(answer)!!

        assertTrue(summary.length <= ThemeSummaryPrompt.MAX_SUMMARY_CHARS)
        assertTrue(summary.endsWith("word…"))
    }

    @Test
    fun `the schema asks for one summary of bounded length`() {
        val summary = Json.parseToJsonElement(ThemeSummaryPrompt.SCHEMA).jsonObject
            .getValue("properties").jsonObject.getValue("summary").jsonObject

        assertEquals(ThemeSummaryPrompt.MAX_SUMMARY_CHARS, summary.getValue("maxLength").jsonPrimitive.int)
    }
}
