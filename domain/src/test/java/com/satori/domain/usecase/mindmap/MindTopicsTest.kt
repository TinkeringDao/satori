package com.satori.domain.usecase.mindmap

import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.usecase.mindmap.MindTopics.Answered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindTopicsTest {

    private fun vocabulary(text: String, notes: List<LlmInsight> = emptyList()) =
        MindTopics.vocabulary(MindTopics.spoken(listOf(segment("x", text, at = 0L))), notes)

    private fun note(title: String?, content: String, at: Long, sources: List<String> = listOf("seg1")) =
        LlmInsight("n$at", "s1", title, content, timestamp = at, sourceSegmentIds = sources)

    private fun segment(id: String, text: String, at: Long) = TranscriptionSegment(id, "s1", text, at, isComplete = true)

    // --- Reading the answer ---

    @Test
    fun `an answer held to the grammar gives its topics as written`() {
        val answer = """{"topics": [{"label": "Garden shed", "points": ["The roof leaks."]}, {"label": "Rent", "points": []}]}"""

        assertEquals(
            listOf(Answered("Garden shed", listOf("The roof leaks.")), Answered("Rent", emptyList())),
            MindTopics.parse(answer)
        )
    }

    @Test
    fun `JSON in chatter or code fences, or a bare list, is found`() {
        val fenced = "Sure!\n```json\n{\"topics\": [{\"label\": \"Rent\", \"points\": [\"It goes up.\"]}]}\n```"
        val bare = """[{"label": "Rent", "points": ["It goes up."]}]"""

        assertEquals(listOf(Answered("Rent", listOf("It goes up."))), MindTopics.parse(fenced))
        assertEquals(listOf(Answered("Rent", listOf("It goes up."))), MindTopics.parse(bare))
    }

    @Test
    fun `other key names and a point as one string are read too`() {
        val answer = """{"topics": [{"topic": "Rent", "details": "It goes up."}]}"""

        assertEquals(listOf(Answered("Rent", listOf("It goes up."))), MindTopics.parse(answer))
    }

    @Test
    fun `an answer cut off midway keeps the topics written out in full`() {
        val answer = """{"topics": [{"label": "Rent", "points": ["It goes up."]}, {"label": "Garden", "points": ["The ro"""

        assertEquals(listOf(Answered("Rent", listOf("It goes up."))), MindTopics.parse(answer))
    }

    @Test
    fun `an answer with no topic in full, or no JSON, can't be read`() {
        assertNull(MindTopics.parse("""{"topics": [{"label": "Rent", "points": ["It go"""))
        assertNull(MindTopics.parse("I could not find any topics."))
        assertNull(MindTopics.parse("""{"answer": "none"}"""))
    }

    @Test
    fun `a bracket of chatter before the JSON is passed over`() {
        val answer = """[Note] {"topics": [{"label": "Rent", "points": ["It goes up."]}]}"""

        assertEquals(listOf(Answered("Rent", listOf("It goes up."))), MindTopics.parse(answer))
    }

    @Test
    fun `a list that holds something but no topic can't be read`() {
        assertNull(MindTopics.parse("""{"topics": ["Garden shed", "Rent"]}"""))
        assertNull(MindTopics.parse("[1, 2, 3]"))
    }

    @Test
    fun `an answer with no topics is read, and has none`() {
        assertEquals(emptyList<Answered>(), MindTopics.parse("""{"topics": []}"""))
    }

    @Test
    fun `brackets inside strings don't end the JSON`() {
        val answer = """{"topics": [{"label": "Plan {B}", "points": ["Use ] and } here."]}]}"""

        assertEquals(listOf(Answered("Plan {B}", listOf("Use ] and } here."))), MindTopics.parse(answer))
    }

    // --- Which topics hold up ---

    @Test
    fun `a label is kept when its words occur in the text in any case, umlaut or ending`() {
        val german = vocabulary("Wir müssen die GÄRTEN und das Gartenhaus streichen.")
        val english = vocabulary("We need to fix the roofs of both sheds.")

        assertEquals(listOf("Garten"), MindTopics.kept(listOf(Answered("garten", emptyList())), german).map { it.label })
        assertEquals(
            listOf("Gartenhäuser streichen"),
            MindTopics.kept(listOf(Answered("Gartenhäuser streichen", emptyList())), german).map { it.label }
        )
        assertEquals(listOf("Shed roof"), MindTopics.kept(listOf(Answered("Shed roof", emptyList())), english).map { it.label })
    }

    @Test
    fun `a compound of words said, or one with a misheard part put right, is a label of the text`() {
        // What Gemma 4 E2B answered on the phone for a German note; all three used to be dropped.
        val text = vocabulary("Die Heizung wird repariert. Ich muss morgen um 9 beim Arzt anrufen. Freitag ist das Teemeeting.")
        val answered = listOf("Heizungsreparatur", "Arzttermin", "Teameeting", "Autoinspektion").map { Answered(it, emptyList()) }

        assertEquals(listOf("Heizungsreparatur", "Arzttermin", "Teameeting"), MindTopics.kept(answered, text).map { it.label })
    }

    @Test
    fun `a label with a word the text doesn't have is dropped, such as the prompt's example`() {
        val text = vocabulary("The rent goes up in March, and the shed needs a new roof.")

        assertTrue(MindTopics.kept(listOf(Answered("Garden shed", listOf("The roof leaks."))), text).isEmpty())
        assertEquals(listOf("Rent"), MindTopics.kept(listOf(Answered("Rent", emptyList())), text).map { it.label })
    }

    @Test
    fun `a label made only of stop words names nothing`() {
        assertTrue(MindTopics.kept(listOf(Answered("The other", emptyList())), vocabulary("the other one")).isEmpty())
        assertNull(MindTopics.label("Of the"))
    }

    @Test
    fun `a label is cut to four words and doesn't end on a stop word`() {
        assertEquals("Shed roof", MindTopics.label("Shed roof and the gutter"))
        assertEquals("Garden shed", MindTopics.label("  \"garden   shed.\" "))
        assertEquals("Miete", MindTopics.label("- miete"))
    }

    @Test
    fun `at most three topics are kept, in the answer's order, after the dropped ones`() {
        val text = vocabulary("rent, heating, garden, kitchen and car")
        val answered = listOf("Rent", "Holiday", "Heating", "Garden", "Kitchen").map { Answered(it, emptyList()) }

        assertEquals(listOf("Rent", "Heating", "Garden"), MindTopics.kept(answered, text).map { it.label })
    }

    @Test
    fun `a label may take its words from the notes`() {
        val words = vocabulary("We talked for a while.", listOf(note("Budget for 2027", "Spending stays flat.", at = 1L)))

        assertEquals(listOf("Budget 2027"), MindTopics.kept(listOf(Answered("Budget 2027", emptyList())), words).map { it.label })
    }

    @Test
    fun `a kept topic carries its match key`() {
        val kept = MindTopics.kept(listOf(Answered("Sheds in the garden", emptyList())), vocabulary("the garden sheds"))

        assertEquals(listOf("gard shed"), kept.map { it.key })
    }

    // --- Points ---

    @Test
    fun `points are trimmed, lose list marks and quotes, and start upper case`() {
        assertEquals(
            listOf("The roof leaks.", "New boards cost 80 euros."),
            MindTopics.points(listOf("  - the roof   leaks.  ", "\"New boards cost 80 euros.\""))
        )
    }

    @Test
    fun `a point longer than twenty words is cut and marked, without a stop word at its end`() {
        val words = (1..19).joinToString(" ") { "word$it" } + " the end of it all"

        assertEquals(listOf((1..19).joinToString(" ") { "word$it" }.replaceFirstChar { it.uppercase() } + "…"), MindTopics.points(listOf(words)))
    }

    @Test
    fun `repeats go whatever their case and punctuation, and so do empty points`() {
        assertEquals(
            listOf("The roof leaks."),
            MindTopics.points(listOf("The roof leaks.", "the roof leaks", "THE ROOF LEAKS!", "", "  ", "..."))
        )
    }

    @Test
    fun `a date, a number or a minus at the start of a point or a label stays`() {
        val points = listOf("3. Oktober ist der Termin beim Arzt.", "15. März: Zahnarzt", "-5 Grad heute Nacht")

        assertEquals(points, MindTopics.points(points))
        assertEquals("1. FC Köln", MindTopics.label("1. FC Köln"))
    }

    @Test
    fun `at most three points are kept`() {
        assertEquals(listOf("A1", "B2", "C3"), MindTopics.points(listOf("a1", "b2", "c3", "d4")))
    }

    // --- Where a topic came from ---

    @Test
    fun `the JSON keys of an older note are no words of the recording`() {
        val content = """{"title": "Budget", "summary": "Spending stays flat.", "action_items": ["Call the bank"]}"""
        val words = vocabulary("We talked for a while.", listOf(note("Budget", content, at = 1L)))

        assertTrue(MindTopics.kept(listOf(Answered("Summary", emptyList()), Answered("Action items", emptyList())), words).isEmpty())
        assertEquals(listOf("Spending"), MindTopics.kept(listOf(Answered("Spending", emptyList())), words).map { it.label })
    }

    @Test
    fun `a topic comes from the segments that hold the most of its words, in spoken order`() {
        val stems = MindWords.contentStems("Garden shed").toSet()
        val big = segment("a", "The garden is big.", at = 1L)
        val leaks = segment("b", "The shed in the garden leaks.", at = 2L)
        val old = segment("c", "The shed is old.", at = 3L)

        assertEquals(listOf("b"), MindTopics.segmentIds(stems, MindTopics.spoken(listOf(big, leaks, old))))
        assertEquals(listOf("a", "c"), MindTopics.segmentIds(stems, MindTopics.spoken(listOf(big, old))))
        assertTrue(MindTopics.segmentIds(stems, MindTopics.spoken(listOf(segment("d", "Nothing about it.", at = 4L)))).isEmpty())
    }

    // --- What the model reads ---

    @Test
    fun `the model reads the notes, the overall summary first, each as its title and summary`() {
        val session = TranscriptionSession("s1", null, 0L, 0L, insightStrategy = InsightStrategy.END_OF_SESSION)
        val notes = listOf(
            note("Part two", "The roof leaks.", at = 3L),
            note("Part one", """{"title": "Part one", "summary": "Boards cost 80 euros."}""", at = 2L),
            note("Shed repair", "The shed gets a new roof.", at = 9L, sources = emptyList()),
            note(null, "   ", at = 4L)
        )

        assertEquals(
            "Shed repair: The shed gets a new roof.\nPart one: Boards cost 80 euros.\nPart two: The roof leaks.",
            MindTopics.input(session, notes, transcript = "what was said")
        )
    }

    @Test
    fun `the model reads the transcript when there are no notes, cut at a word to the chunk size`() {
        val session = TranscriptionSession("s1", null, 0L, 0L)
        val transcript = "word ".repeat(1_200).trim()

        val input = MindTopics.input(session, emptyList(), transcript)

        assertTrue(input.length <= MindTopics.MAX_TEXT_CHARS)
        assertTrue(input.endsWith("word"))
        assertEquals("short talk", MindTopics.input(session, emptyList(), "short talk"))
    }

    @Test
    fun `the schema allows three topics of a label and three points`() {
        val schema = Json.parseToJsonElement(MindTopics.SCHEMA).jsonObject
        val topics = schema.getValue("properties").jsonObject.getValue("topics").jsonObject
        val item = topics.getValue("items").jsonObject

        assertEquals(MindTopics.MAX_TOPICS, topics.getValue("maxItems").jsonPrimitive.int)
        assertEquals(
            MindTopics.MAX_POINTS,
            item.getValue("properties").jsonObject.getValue("points").jsonObject.getValue("maxItems").jsonPrimitive.int
        )
        assertEquals(listOf("label", "points"), item.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }
}
