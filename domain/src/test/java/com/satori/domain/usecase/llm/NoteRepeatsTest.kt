package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.usecase.sync.SyncSttLlmUseCase
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A note doesn't repeat itself (0.7.3): within one note a point that says what an earlier point
 * says goes, and within one recording's notes a later note that mostly repeats an earlier one
 * goes; both counted. Real lists that share a subject word stay, in English and German. The points
 * are made up, of the shape the 1B gave on 2026-10-19 (one made-up subject said again and again
 * with small changes), none of them the owner's.
 */
class NoteRepeatsTest {

    private fun repeats(later: String, earlier: String) = NoteRepeats.repeats(NoteRepeats.keyOf(later), NoteRepeats.keyOf(earlier))

    @Test
    fun `a point that says what an earlier one says, weighed otherwise, repeats it`() {
        assertTrue(repeats("Bluetooth firmware is essential.", "Bluetooth firmware is needed."))
        assertTrue(repeats("Bluetooth firmware is a complex task.", "Bluetooth firmware is needed."))
        assertTrue(repeats("The printer driver is important.", "A printer driver is necessary."))
        // Saying less is saying nothing new.
        assertTrue(repeats("Call Anna.", "Call Anna about the rent on Friday."))
        assertTrue(repeats("Die Treibersoftware ist wichtig.", "Die Treibersoftware wird benötigt."))
        assertTrue(repeats("Der Druckertreiber ist notwendig.", "Der Druckertreiber ist eine komplexe Aufgabe."))
    }

    @Test
    fun `real lists that share a subject word stay, in English and German`() {
        assertFalse(repeats("Buy bread for the kids.", "Buy milk for the kids."))
        assertFalse(repeats("Book a hotel in Berlin for the team.", "Book flights to Berlin for the team."))
        assertFalse(repeats("Call Anna about the car.", "Call Anna about the rent."))
        assertFalse(repeats("Meeting at 3.", "Meeting at 9."))
        assertFalse(repeats("The budget needs Anna's approval.", "The budget is 50,000 euros."))
        assertFalse(repeats("The budget is due on Friday.", "The budget needs Anna's approval."))
        // Saying more is something new.
        assertFalse(repeats("Call Anna about the rent.", "Call Anna."))
        // One word alone repeats only a point of that word alone: "Rent." is more than nothing after a longer point.
        assertFalse(repeats("Rent.", "Call Anna about the rent."))
        assertFalse(repeats("Brot für die Kinder kaufen.", "Milch für die Kinder kaufen."))
        assertFalse(repeats("Das Budget braucht die Freigabe von Anna.", "Das Budget beträgt 50.000 Euro."))
        assertFalse(repeats("Das Budget ist bis Freitag fällig.", "Das Budget braucht die Freigabe von Anna."))
    }

    private val keys = InsightFormat.Keys.ENGLISH

    @Test
    fun `a note's points that repeat an earlier point go and are counted, its other points and action items stay`() {
        val answer = """{"title": "Printer firmware", "points": ["Printer firmware update is needed.", "Printer firmware update is essential.", """ +
            """"Ask Tom for the install disk.", "Printer firmware update is a complex task."], "action_items": ["Ask Tom for the disk", "Ask Tom for the disk soon"]}"""

        val note = InsightOutputParser.parse(answer, RecordingMode.SHORT_MEETING, keys)

        assertEquals("- Printer firmware update is needed.\n- Ask Tom for the install disk.", note.content)
        assertEquals("[\"Ask Tom for the disk\",\"Ask Tom for the disk soon\"]", note.tasks)
        assertEquals(2, note.repeatedLines)
        assertEquals(2, note.droppedLines)
    }

    @Test
    fun `an action item that says an earlier one again goes, the points aside`() {
        val answer = """{"title": "Invoice", "points": ["Send the invoice to Anna."], "action_items": ["Send the invoice to Anna.", "Send Anna the invoice.", "Call the bank on Monday."]}"""

        val note = InsightOutputParser.parse(answer, RecordingMode.SHORT_MEETING, keys)

        // An action item may say a point again: meetings restate points as tasks.
        assertEquals("- Send the invoice to Anna.", note.content)
        assertEquals("[\"Send the invoice to Anna.\",\"Call the bank on Monday.\"]", note.tasks)
        assertEquals(1, note.repeatedLines)
    }

    @Test
    fun `a German note's points that repeat an earlier one go too, a list of one subject stays`() {
        val keysDe = InsightFormat.Keys("titel", "punkte", "zusammenfassung", "aktionen")
        val answer = """{"titel": "Druckertreiber", "punkte": ["Der Druckertreiber wird benötigt.", "Der Druckertreiber ist wichtig.", """ +
            """"Das Budget beträgt 500 Euro.", "Das Budget braucht die Freigabe von Anna."]}"""

        val note = InsightOutputParser.parse(answer, RecordingMode.SIMPLE_LISTENING, keysDe)

        assertEquals("- Der Druckertreiber wird benötigt.\n- Das Budget beträgt 500 Euro.\n- Das Budget braucht die Freigabe von Anna.", note.content)
        assertEquals(1, note.repeatedLines)
    }

    private val shipped = ShippedPrompts()
    private val settings = FakeSettingsRepository(AppSettings(simpleListeningIntervalSeconds = 0))

    private class Lines : NotesLog, PromptEchoLog {
        val heard = mutableListOf<String>()
        override fun log(event: NotesLog.Event) {
            heard += NotesLog.line(event)
        }

        override fun dropped(sessionId: String, count: Int, what: PromptEchoLog.What) {
            heard += PromptEchoLog.line(sessionId, count, what)
        }
    }

    private val lines = Lines()

    // Three parts of a pass of the same English talk; made up.
    private val talk = (1..27).map {
        segment("We talked about the printer at the office and what it needs before the audit next month. ".repeat(6), at = it.toLong())
    }

    private val first = """{"title": "Printer driver needed", "points": ["The office printer needs a new driver.", "Install it before the audit."]}"""
    private val again = """{"title": "Printer driver is essential", "points": ["The office printer needs a new driver.", "Installing it is a complex task."]}"""
    private val more = """{"title": "Printer driver needed", "points": ["Tom has the install disk.", "The audit is on the 12th."]}"""
    private val merge = """{"title": "Office printer", "points": ["The printer needs a new driver before the audit.", "Tom has the disk."]}"""

    private fun pass(llm: FakeLlmRepository, parts: MutableList<LlmInsight>) = runBlocking {
        GenerateBatchInsightUseCase(llm, settings, shipped, echoLog = lines, notesLog = lines)(
            talk, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en", onChunkInsight = { parts += it }
        ).getOrThrow()
    }

    @Test
    fun `a part that mostly repeats an earlier part is none, one that shares its subject and says more stays, the merge is never held to them`() {
        val llm = FakeLlmRepository(answers = listOf(first, again, more, merge))
        val parts = mutableListOf<LlmInsight>()

        val summary = pass(llm, parts)!!

        assertEquals(listOf("Printer driver needed", "Printer driver needed"), parts.map { it.title })
        assertEquals(listOf("- Tom has the install disk.\n- The audit is on the 12th."), parts.drop(1).map { it.content })
        // The merge says what the parts said, and stays.
        assertEquals("Office printer", summary.title)
        // Not asked again: the model said something, the same as before.
        assertEquals(4, llm.prompts.size)
        assertEquals(listOf("Dropped 1 notes that repeat an earlier note in s1"), lines.heard)
    }

    @Test
    fun `a pass of several parts with one note left has it as a part alone, never again as its summary`() {
        val llm = FakeLlmRepository(answers = listOf(first, again, again))
        val parts = mutableListOf<LlmInsight>()

        assertNull(pass(llm, parts))

        assertEquals(1, parts.size)
        assertEquals(3, llm.prompts.size)
        assertEquals(listOf("Dropped 2 notes that repeat an earlier note in s1"), lines.heard)
    }

    @Test
    fun `the last note after Stop that mostly repeats a live note is none`() = runBlocking {
        val live = insight("Printer driver needed", "- The office printer needs a new driver.\n- Install it before the audit.", at = 1L)
        val text = talk.take(2).joinToString(" ") { it.text }
        val finalNote = GenerateFinalInsightUseCase(ScriptedLlmRepository(listOf(again)), settings, shipped, lines, lines)

        val note = finalNote(text, "s1", RecordingMode.SIMPLE_LISTENING, "en", earlierNotes = listOf(live)).getOrThrow()

        assertNull(note)
        assertEquals(listOf("Dropped 1 notes that repeat an earlier note in s1"), lines.heard)
        // One that says more is the last note.
        val kept = GenerateFinalInsightUseCase(ScriptedLlmRepository(listOf(more)), settings, shipped, lines, lines)(
            text, "s1", RecordingMode.SIMPLE_LISTENING, "en", earlierNotes = listOf(live)
        ).getOrThrow()
        assertEquals("Printer driver needed", kept!!.title)
    }

    @Test
    fun `a live note that mostly repeats the one before is none, and the next that says more is sent`() = runBlocking {
        val llm = FakeLlmRepository(answers = listOf(first, again, more))
        val written = CompletableDeferred<Unit>()
        val heard = flow {
            emit(segment("We talked about the printer at the office and the new driver it needs", at = 1))
            written.await()
            delay(100)
            emit(segment("and that installing the driver on the printer is a lot of work for us", at = 2))
            delay(300)
            emit(segment("Tom has the install disk, and the audit is on the twelfth of next month", at = 3))
        }
        val notes = mutableListOf<LlmInsight>()

        SyncSttLlmUseCase(llm, settings, shipped, lines, lines)(
            transcriptionFlow = heard,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en"
        ).collect { note ->
            notes += note
            written.complete(Unit)
        }

        assertEquals(3, llm.prompts.size)
        assertEquals(listOf("- The office printer needs a new driver.\n- Install it before the audit.", "- Tom has the install disk.\n- The audit is on the 12th."), notes.map { it.content })
        assertEquals(listOf("Dropped 1 notes that repeat an earlier note in s1"), lines.heard)
    }

    // The review of 0.7.3: names, numbers, letters and what turns a point round tell two points
    // apart; a later note that says an earlier one again keeps what is new.

    @Test
    fun `names, numbers, letters, abbreviations, months and what turns a point round tell two points apart`() {
        for ((later, earlier) in listOf(
            "Call Paula about the car." to "Call Paul about the car.",
            "Ask Harish about the visa." to "Ask Hari about the visa.",
            "Take vitamin C every morning." to "Take vitamin D every morning.",
            "Call the IT team about the offer." to "Call the sales team about the offer.",
            "Pay the May rent." to "Pay the April rent.",
            "Room B is for the workshop." to "Room A is for the workshop.",
            "Buy one ticket for Anna." to "Buy two tickets for Anna.",
            "Pay 15000 euros." to "Pay 1500 euros.",
            "Meeting at 9 AM." to "Meeting at 9 PM.",
            "Call Anna after the meeting." to "Call Anna before the meeting.",
            "The client does not want the blue logo." to "The client wants the blue logo.",
            "The client wants the blue logo." to "The client does not want the blue logo.",
            "No check-up on Friday after all." to "Check-up on Friday.",
            "Paula kommt am Montag." to "Paul kommt am Montag.",
            "Andrea bringt den Kuchen." to "Andreas bringt den Kuchen.",
            "Ask Andrea about the cake." to "Ask Andreas about the cake.",
            "Frag Andrea nach dem Kuchen." to "Frag Andreas nach dem Kuchen.",
            "Die Miete ist nicht fällig." to "Die Miete ist fällig."
        )) {
            assertFalse("'$later' after '$earlier'", repeats(later, earlier))
        }
        // A word in another form is still the word: "installing" for "install", "fällige" for "fällig".
        assertTrue(repeats("Installing the firmware is a complex task.", "Install the firmware."))
        assertTrue(repeats("Die fällige Miete ist wichtig.", "Die Miete ist fällig."))
    }

    @Test
    fun `the words the 1B weighs a made-up subject with weigh it only`() {
        assertTrue(repeats("Bluetooth firmware update is difficult.", "Bluetooth firmware update."))
        assertTrue(repeats("The firmware update is a significant challenge.", "The firmware update is needed."))
        assertTrue(repeats("Firmware updates matter.", "The firmware update is needed."))
        assertTrue(repeats("Das Firmware-Update ist schwierig.", "Das Firmware-Update ist nötig."))
        // A word that says something new stays: "Firmware update costs 40 euros".
        assertFalse(repeats("The firmware update costs 40 euros.", "The firmware update is needed."))
    }

    // An English talk of two parts; made up.
    private val garden = (1..14).map {
        segment("We talked about the garden work on Saturday and what the team brings for the hedge and the lawn. ".repeat(6), at = it.toLong())
    }

    private val hedge = """{"title": "Garden work", "points": ["Cut the hedge on Saturday.", "Mow the lawn after the hedge."]}"""
    private val hedgeAgain = """{"title": "Garden work", "points": ["Cut the hedge on Saturday.", "Anna brings the trailer for the green waste."]}"""
    private val gardenMerge = """{"title": "Garden work", "points": ["Cut the hedge on Saturday.", "Anna brings the trailer."]}"""

    @Test
    fun `a later note that says an earlier one again keeps what is new, as a live note, the last note and a part`() = runBlocking {
        // The last note after Stop, after the live note it says again.
        val live = insight("Garden work", "- Cut the hedge on Saturday.\n- Mow the lawn after the hedge.", at = 1L)
        val text = garden.take(2).joinToString(" ") { it.text }
        val last = GenerateFinalInsightUseCase(ScriptedLlmRepository(listOf(hedgeAgain)), settings, shipped, lines, lines)(
            text, "s1", RecordingMode.SIMPLE_LISTENING, "en", earlierNotes = listOf(live)
        ).getOrThrow()!!

        assertNull(last.title)
        assertEquals("- Anna brings the trailer for the green waste.", last.content)
        assertEquals(listOf("Dropped 2 note lines that repeat an earlier note in s1"), lines.heard)

        // A part of a pass after the part it says again.
        lines.heard.clear()
        val parts = mutableListOf<LlmInsight>()
        GenerateBatchInsightUseCase(FakeLlmRepository(answers = listOf(hedge, hedgeAgain, gardenMerge)), settings, shipped, echoLog = lines, notesLog = lines)(
            garden, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en", onChunkInsight = { parts += it }
        ).getOrThrow()
        assertEquals(listOf("- Cut the hedge on Saturday.\n- Mow the lawn after the hedge.", "- Anna brings the trailer for the green waste."), parts.map { it.content })
        assertEquals(listOf("Dropped 2 note lines that repeat an earlier note in s1"), lines.heard)
    }

    @Test
    fun `a live note that says the one before again keeps what is new`() = runBlocking {
        val llm = FakeLlmRepository(answers = listOf(hedge, hedgeAgain))
        val written = CompletableDeferred<Unit>()
        val heard = flow {
            emit(segment("We cut the hedge on Saturday, and then we mow the lawn after the hedge is done", at = 1))
            written.await()
            delay(100)
            emit(segment("so the hedge on Saturday, and Anna brings the trailer for the green waste then", at = 2))
        }
        val notes = mutableListOf<LlmInsight>()

        SyncSttLlmUseCase(llm, settings, shipped, lines, lines)(
            transcriptionFlow = heard,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en"
        ).collect { note ->
            notes += note
            written.complete(Unit)
        }

        assertEquals(listOf("Garden work", null), notes.map { it.title })
        assertEquals("- Anna brings the trailer for the green waste.", notes[1].content)
        assertEquals(listOf("Dropped 2 note lines that repeat an earlier note in s1"), lines.heard)
    }

    @Test
    fun `a part of a Malayalam pass that says an earlier part again goes whole, new words and all`() = runBlocking {
        // Made up: "We have to fix the garden fence; the wood costs a lot." Three parts of it.
        val fence = (1..18).map {
            segment("പൂന്തോട്ടത്തിലെ വേലി നന്നാക്കണം, മരത്തിന് നല്ല വിലയാണ്. ".repeat(20), at = it.toLong())
        }
        val first = """{"title": "Fence repair", "points": ["The garden fence needs repair.", "The wood is expensive."]}"""
        val again = """{"title": "Fence repair", "points": ["The garden fence needs repair.", "The wood is very expensive this year."]}"""
        val parts = mutableListOf<LlmInsight>()

        GenerateBatchInsightUseCase(FakeLlmRepository(answers = listOf(first, again, again)), settings, shipped, echoLog = lines, notesLog = lines)(
            fence, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en", onChunkInsight = { parts += it }
        ).getOrThrow()

        assertEquals(listOf("Fence repair"), parts.map { it.title })
        assertTrue(lines.heard.toString(), lines.heard.any { it.startsWith("Dropped") && it.endsWith("notes that repeat an earlier note in s1") })
    }
}
