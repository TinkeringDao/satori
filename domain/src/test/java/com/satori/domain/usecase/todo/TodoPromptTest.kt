package com.satori.domain.usecase.todo

import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.time.TimeCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class TodoPromptTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val startedAt = ZonedDateTime.of(2026, 10, 14, 10, 41, 0, 0, zone)

    private fun line(position: Int, text: String, vararg times: Pair<String, ZonedDateTime>) = TodoPrompt.Line(
        position = position,
        segment = TranscriptionSegment(id = "seg$position", sessionId = "s1", text = text, timestamp = 0L, isComplete = true),
        text = text,
        spokenAt = startedAt.plusSeconds(position * 20L),
        candidates = times.map { (phrase, at) ->
            val start = text.indexOf(phrase)
            TimeCandidate(phrase, start, start + phrase.length, at, hasTime = true, confidence = 0.9f)
        }
    )

    private val friday = startedAt.withDayOfMonth(16).withHour(9).withMinute(0)

    @Test
    fun `the schema holds a to-do or an idea, with the segments and times of its own window only`() {
        val first = TodoPrompt.Window(listOf(line(0, "I need to call the bank on Friday at 9.", "on Friday at 9" to friday), line(1, "Nothing else.")))
        val second = TodoPrompt.Window(listOf(line(2, "There should be a dark mode.")))

        val (todo, idea) = itemsOf(first)
        assertEquals(listOf("segment", "type", "text", "when", "capture"), todo.obj("properties").keys.toList())
        assertEquals(listOf("segment", "type", "text", "when", "capture"), todo.strings("required"))
        assertEquals(listOf("S1", "S2"), todo.enumOf("segment"))
        assertEquals(listOf("task", "appointment"), todo.enumOf("type"))
        assertEquals(listOf("none", "T1"), todo.enumOf("when"))
        assertEquals(listOf("explicit", "commitment", "asked", "inferred"), todo.enumOf("capture"))
        assertEquals(listOf("segment", "type", "text"), idea.obj("properties").keys.toList())
        assertEquals(listOf("segment", "type", "text"), idea.strings("required"))
        assertEquals(listOf("S1", "S2"), idea.enumOf("segment"))
        assertEquals(listOf("idea"), idea.enumOf("type"))
        for (shape in listOf(todo, idea)) {
            assertEquals("false", shape["additionalProperties"]!!.jsonPrimitive.content)
            assertEquals("120", shape.obj("properties").obj("text")["maxLength"]!!.jsonPrimitive.content)
        }

        val (secondTodo, secondIdea) = itemsOf(second)
        assertEquals(listOf("S1"), secondTodo.enumOf("segment"))
        assertEquals(listOf("none"), secondTodo.enumOf("when"))
        assertEquals(listOf("S1"), secondIdea.enumOf("segment"))

        val root = Json.parseToJsonElement(TodoPrompt.schema(first)).jsonObject
        assertEquals(listOf("items"), root.strings("required"))
        assertEquals("10", root.obj("properties").obj("items")["maxItems"]!!.jsonPrimitive.content)
    }

    @Test
    fun `with quotes each item gives the words of its segment after its type, in at most 160 characters`() {
        val window = TodoPrompt.Window(listOf(line(0, "I need to call the bank on Friday at 9.", "on Friday at 9" to friday)))

        val root = Json.parseToJsonElement(TodoPrompt.schema(window, quotes = true)).jsonObject
        val (todo, idea) = root.obj("properties").obj("items").obj("items")["anyOf"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("segment", "type", "said", "text", "when", "capture"), todo.obj("properties").keys.toList())
        assertEquals(listOf("segment", "type", "said", "text", "when", "capture"), todo.strings("required"))
        assertEquals(listOf("segment", "type", "said", "text"), idea.strings("required"))
        assertEquals("160", todo.obj("properties").obj("said")["maxLength"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the prompt's examples show the kinds only, as made-up sentences, and its JSON example has no text to copy`() {
        for (folder in listOf("values", "values-de")) {
            val prompt = shipped(folder, "prompt_todo_extraction")
            val example = prompt.lines().single { it.startsWith("{\"items\": [{") }
            val texts = Json.parseToJsonElement(example).jsonObject["items"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
            assertEquals(folder, listOf("...", "..."), texts)
            // The example a 1B model copied into recordings that never said it (2026-10-14).
            assertFalse(folder, prompt.contains("clock bigger", ignoreCase = true) || prompt.contains("Uhr größer"))
        }
        assertTrue(shipped("values", "prompt_todo_extraction").contains("made-up sentences only show the kinds"))
        assertTrue(shipped("values-de", "prompt_todo_extraction").contains("erfundenen Sätze zeigen nur die Arten"))
        assertEquals(ResourceProvider.TODO_SAID, shipped("values", "prompt_todo_said"))
        // The German order as it ships, in the neutral infinitive, naming the field as the schema does.
        assertEquals("Für said bis zu 8 Wörter des Abschnitts abschreiben, die den Eintrag sagen.", shipped("values-de", "prompt_todo_said"))
    }

    @Test
    fun `the segments are shown without the clock time they were said at, their times below them`() {
        val window = TodoPrompt.Window(listOf(line(0, "I need to call the bank on Friday at 9.", "on Friday at 9" to friday), line(1, "Nothing else.")))

        assertEquals(
            "S1 I need to call the bank on Friday at 9.\n  T1 = 'on Friday at 9' -> Fri 16 Oct 09:00 (Friday)\nS2 Nothing else.",
            TodoPrompt.render(window, Locale.ENGLISH)
        )
    }

    @Test
    fun `the header says when the recording started and what is noted, and ends in a blank line`() {
        assertEquals(
            "The recording started on 2026-10-14 (Wednesday) at 10:41.\n" +
                "Already noted, do not repeat unless a new time is said for it: \"Call the bank\"; \"Option to say 'hi'\"\n\n",
            TodoPrompt.header(ResourceProvider.TODO_HEADER, startedAt, listOf("Call the bank", "Option to say \"hi\""), Locale.ENGLISH)
        )
        assertEquals(
            "Started 2026-10-14, Mittwoch, 10:41: -\n\n",
            TodoPrompt.header("Started {date}, {weekday}, {time}: {noted}", startedAt, emptyList(), Locale.GERMAN)
        )
        val long = "Call ".repeat(30)
        assertEquals("\"" + long.take(80) + "\"\n\n", TodoPrompt.header("{noted}", startedAt, listOf(long), Locale.ENGLISH))
    }

    @Test
    fun `the line of instructions on the segments' language has the header's last line, before the blank line`() {
        val note = "Instruction, never to be repeated in your answer: read the Malayalam below and write in English. " +
            "Read English words written in Malayalam letters as English. ${ResourceProvider.TODO_SAID}"

        assertEquals(
            "The recording started on 2026-10-14 (Wednesday) at 10:41.\n" +
                "Already noted, do not repeat unless a new time is said for it: -\n$note\n\n",
            TodoPrompt.header(ResourceProvider.TODO_HEADER, startedAt, emptyList(), Locale.ENGLISH, note)
        )
    }

    @Test
    fun `a call holds 3,600 characters of Malayalam segments, as many tokens as 6,000 of English`() {
        // Go to the shop this evening and buy milk and bread: 51 characters, ten times over.
        val malayalam = List(10) { "ഇന്ന് വൈകുന്നേരം കടയിൽ പോയി പാലും റൊട്ടിയും വാങ്ങണം" }.joinToString(" ")
        val english = "The workshop got a new bench and the shelves go to the basement. ".repeat(8).trim()
        val malayalamLines = (0 until 20).map { line(it, malayalam) }
        val englishLines = (0 until 20).map { line(it, english.take(malayalam.length)) }

        val malayalamWindows = TodoPrompt.windows(malayalamLines)
        val englishWindows = TodoPrompt.windows(englishLines)

        // A line of 519 characters costs 870 with its line's extra in Malayalam, 524 in English.
        assertEquals(listOf(6, 6, 6, 2), malayalamWindows.map { it.lines.size })
        assertEquals(listOf(11, 9), englishWindows.map { it.lines.size })
        assertTrue(malayalamWindows.all { window -> window.lines.sumOf { it.text.length } <= 3_600 })
    }

    @Test
    fun `a time's sentence runs from the sentence end before it to the one after it`() {
        val text = "I'll send the report. Maybe on Friday at 9. Then lunch."
        val window = TodoPrompt.Window(listOf(line(0, text, "on Friday at 9" to friday)))

        assertEquals("Maybe on Friday at 9.", TodoPrompt.sentenceOf(window.times.single()))
    }

    @Test
    fun `the system prompt holds no placeholder, and the header all four, in both languages`() {
        for (folder in listOf("values", "values-de")) {
            val prompt = shipped(folder, "prompt_todo_extraction")
            for (placeholder in listOf("{date}", "{weekday}", "{time}", "{noted}")) {
                assertFalse("$folder: $placeholder", prompt.contains(placeholder))
                assertTrue("$folder: $placeholder", shipped(folder, "prompt_todo_header").contains(placeholder))
            }
            // The example's braces stay, and its keys come in the order the grammar asks for.
            val example = prompt.lines().single { it.startsWith("{\"items\": [{") }
            val items = Json.parseToJsonElement(example).jsonObject["items"]!!.jsonArray
            assertEquals(listOf("segment", "type", "text", "when", "capture"), items[0].jsonObject.keys.toList())
            assertEquals(listOf("segment", "type", "text"), items[1].jsonObject.keys.toList())
            assertTrue(folder, prompt.endsWith("{\"items\": []}"))
            // So the window, the header and the answer fit the model's context (TodoPrompt.MAX_WINDOW_CHARS).
            assertTrue("$folder: ${prompt.length}", prompt.length < 2_000)
        }
        assertEquals(ResourceProvider.TODO_HEADER, shipped("values", "prompt_todo_header"))
    }

    /** The items of [window]'s schema: the to-do's shape and the idea's. */
    private fun itemsOf(window: TodoPrompt.Window): List<JsonObject> {
        val schema = Json.parseToJsonElement(TodoPrompt.schema(window)).jsonObject
        return schema.obj("properties").obj("items").obj("items")["anyOf"]!!.jsonArray.map { it.jsonObject }
    }

    /**
     * The string [name] of the app's res/[folder]/strings_grille_todo_ai.xml, with its escapes read
     * as aapt2 reads them. Gradle runs the tests in the module's folder, next to the app's.
     */
    private fun shipped(folder: String, name: String): String {
        val file = File("../app/src/main/res/$folder/strings_grille_todo_ai.xml")
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        val raw = (0 until strings.length).map { strings.item(it) as Element }.single { it.getAttribute("name") == name }.textContent
        return raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

    private fun JsonObject.strings(key: String): List<String> = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.enumOf(key: String): List<String> = obj("properties").obj(key).strings("enum")
}
