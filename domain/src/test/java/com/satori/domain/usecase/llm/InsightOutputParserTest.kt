package com.satori.domain.usecase.llm

import com.satori.domain.model.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightOutputParserTest {

    private val english = InsightFormat.Keys.ENGLISH
    private val german = InsightFormat.Keys(title = "titel", points = "punkte", summary = "zusammenfassung", actionItems = "aktionen")

    private fun note(answer: String, keys: InsightFormat.Keys = english) =
        InsightOutputParser.parse(answer, RecordingMode.SIMPLE_LISTENING, keys)

    private fun meeting(answer: String, keys: InsightFormat.Keys = english) =
        InsightOutputParser.parse(answer, RecordingMode.SHORT_MEETING, keys)

    // ─── Points ──────────────────────────────────────────────────────────────────

    @Test
    fun `points become a Markdown list under the title`() {
        val parsed = note(
            """{"title": "Garden shed repairs", "points": ["Replace the broken hinge on the shed door.", "Buy wood stain before Saturday."]}"""
        )
        assertEquals("Garden shed repairs", parsed.title)
        assertEquals("- Replace the broken hinge on the shed door.\n- Buy wood stain before Saturday.", parsed.content)
        assertNull(parsed.tasks)
    }

    @Test
    fun `German keys are read, and the English ones a German answer slips into`() {
        val localized = note("""{"titel": "Fahrrad in die Werkstatt", "punkte": ["Neuen Schlauch kaufen.", "Bremsen prüfen lassen."]}""", german)
        assertEquals("Fahrrad in die Werkstatt", localized.title)
        assertEquals("- Neuen Schlauch kaufen.\n- Bremsen prüfen lassen.", localized.content)

        val slipped = note("""{"title": "Fahrrad in die Werkstatt", "points": ["Neuen Schlauch kaufen."]}""", german)
        assertEquals("Fahrrad in die Werkstatt", slipped.title)
        assertEquals("- Neuen Schlauch kaufen.", slipped.content)
    }

    @Test
    fun `each point is one line without a list marker of its own`() {
        val parsed = note("""{"title": "Packing list", "points": ["- Pack the  rain jacket.", "Charge the\ncamera.", "* Fill the flask."]}""")
        assertEquals("- Pack the rain jacket.\n- Charge the camera.\n- Fill the flask.", parsed.content)
    }

    @Test
    fun `a point said twice is kept once, and blank points are dropped`() {
        val parsed = note("""{"title": "Plants", "points": ["Water the ferns.", "water the ferns.", "  ", "Repot the cactus."]}""")
        assertEquals("- Water the ferns.\n- Repot the cactus.", parsed.content)
    }

    @Test
    fun `the prompt's placeholders copied as they are say nothing`() {
        val parsed = note("""{"title": "...", "points": ["...", "Fix the garden gate.", "-"]}""")
        assertNull(parsed.title)
        assertEquals("- Fix the garden gate.", parsed.content)
        assertFalse(parsed.isEmpty)

        assertTrue(note("""{"title": "...", "points": ["...", "..."]}""").isEmpty)
        assertTrue(meeting("""{"title": "...", "points": ["..."], "action_items": ["..."]}""").isEmpty)
        assertTrue(note("").isEmpty)
    }

    @Test
    fun `escapes are read, and an unescaped quote inside a point stays in it`() {
        val escaped = note("""{"title": "Café visit", "points": ["Order the \"small\" cake.", "Tip 10%\\15%."]}""")
        assertEquals("Café visit", escaped.title)
        assertEquals("- Order the \"small\" cake.\n- Tip 10%\\15%.", escaped.content)

        // Without a grammar a small model writes inner quotes as they are.
        val unescaped = note("""{"title": "Talk rehearsal", "points": ["Practise the "short" version twice.", "Time it."]}""")
        assertEquals("- Practise the \"short\" version twice.\n- Time it.", unescaped.content)
    }

    // ─── A summary instead ───────────────────────────────────────────────────────

    @Test
    fun `a summary is read when there are no points, in either language's key`() {
        assertEquals(
            "The heating stays off until October.",
            note("""{"title": "Heating", "summary": "The heating stays off until October."}""").content
        )
        assertEquals(
            "Die Heizung bleibt bis Oktober aus.",
            note("""{"titel": "Heizung", "zusammenfassung": "Die Heizung bleibt bis Oktober aus."}""", german).content
        )
        assertEquals(
            "Die Heizung bleibt bis Oktober aus.",
            note("""{"titel": "Heizung", "summary": "Die Heizung bleibt bis Oktober aus."}""", german).content
        )
    }

    @Test
    fun `points win over a summary`() {
        val parsed = note("""{"title": "Heating", "summary": "Heating talk.", "points": ["Bleed the radiators."]}""")
        assertEquals("- Bleed the radiators.", parsed.content)
    }

    // ─── What surrounds the JSON ─────────────────────────────────────────────────

    @Test
    fun `fences, chatter and a thinking block around the JSON are left out`() {
        val json = """{"title": "Bike repair", "points": ["Patch the rear tyre."]}"""
        for (answer in listOf(
            "```json\n$json\n```",
            "Here are the notes:\n$json",
            "Sure.\n```json\n$json\n```\nI hope this helps.",
            "<think>The speaker wants notes.</think>$json"
        )) {
            val parsed = note(answer)
            assertEquals(answer, "Bike repair", parsed.title)
            assertEquals(answer, "- Patch the rear tyre.", parsed.content)
        }
    }

    @Test
    fun `an answer that is not JSON is the note as it is`() {
        val parsed = note("Pick up the parcel from the post office on the way home.")
        assertNull(parsed.title)
        assertEquals("Pick up the parcel from the post office on the way home.", parsed.content)
    }

    @Test
    fun `JSON of another shape is kept as written, so nothing is lost`() {
        val answer = """{"notes": ["Pick up the parcel."]}"""
        assertEquals(answer, note(answer).content)
    }

    // ─── Cut off by the token limit ──────────────────────────────────────────────

    @Test
    fun `an answer cut off by the token limit keeps the points written in full`() {
        val parsed = note("""{"title": "Weekend chores", "points": ["Mow the lawn.", "Clean the gutters before the ra""")
        assertEquals("Weekend chores", parsed.title)
        assertEquals("- Mow the lawn.", parsed.content)
    }

    @Test
    fun `a cut right after a point or inside an escape keeps the full points`() {
        assertEquals("- Mow the lawn.", note("""{"title": "Weekend chores", "points": ["Mow the lawn."""").content)
        assertEquals("- Mow the lawn.", note("""{"title": "Weekend chores", "points": ["Mow the lawn.", "Say \""").content)
        assertEquals("- Mow the lawn.", note("""{"title": "Weekend chores", "points": ["Mow the lawn.", "Caf\u00""").content)
    }

    @Test
    fun `an answer cut off before its first full point has no text, and one cut in the title no title`() {
        val noPoint = note("""{"title": "Weekend chores", "points": ["Mow the""")
        assertEquals("Weekend chores", noPoint.title)
        assertEquals("", noPoint.content)

        val noTitle = note("""{"title": "Weekend cho""")
        assertNull(noTitle.title)
        assertEquals("", noTitle.content)
    }

    // ─── The title ───────────────────────────────────────────────────────────────

    @Test
    fun `a title that only names the kind of text is dropped`() {
        assertNull(note("""{"title": "Recording summary", "points": ["Order new ink cartridges."]}""").title)
        assertNull(note("""{"title": "Request for analysis", "points": ["Order new ink cartridges."]}""").title)
        assertNull(note("""{"titel": "Zusammenfassung der Aufnahme", "punkte": ["Druckerpatronen bestellen."]}""", german).title)
    }

    @Test
    fun `a title in the words of the note's own points stays, generic or not`() {
        // Made up: the notes of a recording about an app that records meetings and writes notes.
        val points = """["Records meetings and writes notes and minutes.", "Works offline on the phone."]"""

        assertEquals("Meeting Notes and Minutes", note("""{"title": "Meeting Notes and Minutes", "points": $points}""").title)
        // A word the points never say: generic, as before.
        assertNull(note("""{"title": "Voice Notes and Minutes", "points": $points}""").title)
        // The same title over another subject's points goes.
        assertNull(note("""{"title": "Meeting Notes and Minutes", "points": ["The budget moves to March."]}""").title)
        // A meeting's action items are the title's subject too.
        assertEquals(
            "Meeting Minutes",
            meeting("""{"title": "Meeting Minutes", "points": [], "action_items": ["Send the meeting minutes to the team."]}""").title
        )
        assertNull(meeting("""{"title": "Meeting Minutes", "points": [], "action_items": ["Book the room for Monday."]}""").title)
        assertEquals(
            "Besprechungsnotizen und Protokolle",
            note("""{"titel": "Besprechungsnotizen und Protokolle", "punkte": ["Die App schreibt Besprechungsnotizen und Protokolle."]}""", german).title
        )
    }

    // ─── What an answer loses, for the field log (0.7.2) ─────────────────────────

    @Test
    fun `the lines with no word and a title of the kind of text are counted, beside what the note keeps`() {
        val parsed = note("""{"title": "Audio Transcription Summary", "points": ["...", "Buy the paint.", "  ", "Buy the paint."]}""")

        assertNull(parsed.title)
        assertEquals("- Buy the paint.", parsed.content)
        assertEquals(1, parsed.genericTitles)
        assertEquals(2, parsed.emptyLines)
        assertEquals(1, parsed.keptLines)
        assertEquals(2, parsed.droppedLines)
        assertTrue(parsed.hasPoint)
        // An empty title is a line with no word, not a title of the kind of text.
        val blankTitle = note("""{"title": " ", "points": ["Buy the paint."]}""")
        assertEquals(1, blankTitle.emptyLines)
        assertEquals(0, blankTitle.genericTitles)
    }

    @Test
    fun `a note of a title alone has no point, nor one whose points all repeat the prompt`() {
        val instructions = listOf("Instruction, never to be repeated in your answer: read the Malayalam below and write in English.")

        assertFalse(note("""{"title": "Garden plans", "points": []}""").hasPoint)
        val echoed = InsightOutputParser.parse(
            """{"title": "Garden plans", "points": ["Text is Malayalam."]}""", RecordingMode.SIMPLE_LISTENING, english, instructions
        )
        assertFalse(echoed.hasPoint)
        assertEquals(1, echoed.droppedLines)
        assertEquals(0, echoed.keptLines)
        // A meeting's action items, a summary and prose are something to read.
        assertTrue(meeting("""{"title": "Budget", "points": [], "action_items": ["Send the budget."]}""").hasPoint)
        assertTrue(note("""{"title": "Budget", "summary": "The budget moves to March."}""").hasPoint)
        assertTrue(note("The budget moves to March.").hasPoint)
    }

    @Test
    fun `a title loses quotes and a full stop`() {
        assertEquals("Printer supplies", note("""{"title": "\"Printer supplies.\"", "points": ["Order ink."]}""").title)
    }

    // ─── Meetings ────────────────────────────────────────────────────────────────

    @Test
    fun `a meeting's action items become a JSON array of strings`() {
        val parsed = meeting(
            """{"title": "Office move", "points": ["The move is on the 14th."], "action_items": ["Book the van", "Label the boxes \"fragile\"", "Book the van"]}"""
        )
        assertEquals("Office move", parsed.title)
        assertEquals("- The move is on the 14th.", parsed.content)
        assertEquals("""["Book the van","Label the boxes \"fragile\""]""", parsed.tasks)
        assertEquals(listOf("Book the van", "Label the boxes \"fragile\""), NoteText.actionItems(parsed.tasks))
    }

    @Test
    fun `action items are read under German and English keys`() {
        assertEquals(
            """["Transporter buchen"]""",
            meeting("""{"titel": "Umzug", "punkte": ["Umzug am 14."], "aktionen": ["Transporter buchen"]}""", german).tasks
        )
        assertEquals(
            """["Transporter buchen"]""",
            meeting("""{"titel": "Umzug", "punkte": ["Umzug am 14."], "action_items": ["Transporter buchen"]}""", german).tasks
        )
    }

    @Test
    fun `a meeting without action items has none`() {
        assertNull(meeting("""{"title": "Office move", "points": ["The move is on the 14th."], "action_items": []}""").tasks)
        assertNull(meeting("""{"title": "Office move", "points": ["The move is on the 14th."]}""").tasks)
    }

    @Test
    fun `a meeting answer cut off in its action items keeps those written in full`() {
        val parsed = meeting(
            """{"title": "Office move", "points": ["The move is on the 14th."], "action_items": ["Book the van", "Label the bo"""
        )
        assertEquals("- The move is on the 14th.", parsed.content)
        assertEquals("""["Book the van"]""", parsed.tasks)
    }

    @Test
    fun `a voice note never has action items`() {
        assertNull(note("""{"title": "Office move", "points": ["Pack the desk."], "action_items": ["Book the van"]}""").tasks)
    }

    // ─── Translation ─────────────────────────────────────────────────────────────

    @Test
    fun `a translation is the text as it is`() {
        val parsed = InsightOutputParser.parse("Der Raum ist für Freitag gebucht.", RecordingMode.REAL_TIME_TRANSLATION, english)
        assertNull(parsed.title)
        assertEquals("Der Raum ist für Freitag gebucht.", parsed.content)
        assertNull(parsed.tasks)
    }
}
