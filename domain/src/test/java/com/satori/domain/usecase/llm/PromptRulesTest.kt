package com.satori.domain.usecase.llm

import com.satori.domain.model.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Satori's own rules are an echo too (0.7.3): what of an answer gives back the rules of its call's
 * system prompt, against the app's own prompts (res/values and values-de, [ShippedPrompts]), both
 * ways: the rules said back go, in any order and case, and a real point that shares a word or two
 * with the long prompts stays. The rule phrases are the prompts' own and the shapes the 1B gives
 * them back in; every point and text is made up, none is the owner's.
 */
class PromptRulesTest {

    private val shipped = ShippedPrompts()

    private fun notes(mode: RecordingMode, language: String) = PromptRules.of(shipped.getPromptForMode(mode, language))

    private val englishNotes = RecordingMode.entries.filter { it != RecordingMode.REAL_TIME_TRANSLATION }.map { notes(it, "en") }
    private val germanNotes = RecordingMode.entries.filter { it != RecordingMode.REAL_TIME_TRANSLATION }.map { notes(it, "de") }
    private val voiceNote = notes(RecordingMode.SIMPLE_LISTENING, "en")
    private val name = PromptRules.of(shipped.getSessionTitlePrompt("en"))
    private val todos = PromptRules.of(shipped.getTodoExtractionPrompt("en"), shipped.getTodoHeaderTemplate("en"))
    private val topics = PromptRules.of(shipped.getMindTopicsPrompt("en"))
    private val summary = PromptRules.of(shipped.getMindSummaryPrompt("en"))

    /** A Malayalam recording's notes call: its line of instructions, then the lead-in. */
    private val malayalamLines = NotesTopic.prompt(
        "ഇന്ന് വൈകുന്നേരം ചന്തയിൽ പോയി പച്ചക്കറി വാങ്ങണം.", null, RecordingMode.SIMPLE_LISTENING, "en", shipped
    ).instructions

    @Test
    fun `the notes prompts' rules said back go, under every English prompt`() {
        for (rule in listOf(
            "Keep it concise.", "No filler words.", "One point per point.", "Plain statements.", "First person.",
            "One idea per point.", "Keep it as it is unless the sentence leaves no doubt.", "No filler.", "No repetition.",
            "Plain statements, one idea per point.", "Leave out fillers and repeats.", "Add nothing that was not said.",
            "Keep names, numbers, dates and places.", "Like the subject line of an email.", "Never report on what was said.",
            "Write down what was said as your own notes.", "Keep it short and clear."
        )) {
            for (rules in englishNotes) assertTrue(rule, rules.echoes(rule))
        }
    }

    @Test
    fun `in any order and any case`() {
        for (rule in listOf(
            "PLAIN STATEMENTS", "plain statements", "Plain Statements", "Statements: plain", "First Person, One Idea Per Point",
            "Per point one idea", "Person: first", "Concise, keep it", "no filler, no repetition"
        )) {
            assertTrue(rule, voiceNote.echoes(rule))
        }
    }

    @Test
    fun `the German prompts' rules said back go, under every German prompt`() {
        for (rule in listOf(
            "Schlichte Aussagen.", "In der Ich-Form.", "Ein Gedanke pro Punkt.", "Keine Füllwörter.", "Keine Wiederholungen.",
            "Kurz und knapp.", "Füllwörter und Wiederholungen weglassen.", "Nichts hinzufügen, was nicht gesagt wurde.",
            "Namen, Zahlen, Daten und Orte behalten.", "Wie die Betreffzeile einer E-Mail.",
            "Es so lassen, außer der Satz zeigt zweifelsfrei, welches Wort gemeint war.", "Punkt pro Punkt, ein Gedanke"
        )) {
            for (rules in germanNotes) assertTrue(rule, rules.echoes(rule))
        }
        // The English shapes go under a German prompt too, as the 1B mixes them.
        assertTrue(germanNotes.first().echoes("One idea per point."))
        assertTrue(englishNotes.first().echoes("Ein Gedanke pro Punkt."))
    }

    @Test
    fun `a real point that shares a word or two with the prompts stays`() {
        for (point in listOf(
            "Speech recognition misheard the word on the cover screen.",
            "Keep the receipts for the tax return.",
            "Keep the names and numbers of the suppliers.",
            "Action items for the next meeting.",
            "Open questions about the budget for the fair.",
            "Email the landlord about the heating.",
            "The title of the book is still open.",
            "Plans for the garden in spring.",
            "First prize went to the school choir.",
            "Short trip to the lake on Sunday.",
            "Add the dates to the shared calendar.",
            "Leave the keys with the neighbour.",
            "The decision on the new car is open.",
            "Two places left for the course in May.",
            "A short story about a fox, read to the children."
        )) {
            for (rules in englishNotes) assertFalse(point, rules.echoes(point))
        }
        for (point in listOf(
            "Die Belege für die Steuer aufheben.",
            "Namen und Nummern der Lieferanten notieren.",
            "Offene Fragen zum Budget der Messe.",
            "Erster Preis für den Schulchor.",
            "Kurze Fahrt an den See am Sonntag.",
            "Der Titel des Buches ist noch offen.",
            "Die Schlüssel bei der Nachbarin lassen."
        )) {
            for (rules in germanNotes) assertFalse(point, rules.echoes(point))
        }
    }

    @Test
    fun `points about recordings, notes or speech stay the subject they are`() {
        for (point in listOf(
            "Records meetings and gives notes and minutes.", "AI writes the notes.", "Notes from recordings.", "Speech to text.",
            "Audio recording and transcription.", "Voice notes.", "The AI runs offline on the phone.", "Helps people who find writing hard."
        )) {
            assertFalse(point, voiceNote.echoes(point, malayalamLines))
            assertFalse(point, voiceNote.echoes(point))
        }
    }

    @Test
    fun `a point that only says this is a recording goes with the rules alone, as an English recording's notes have no lines`() {
        for (point in listOf("This is an audio recording.", "Recording.", "Notes.", "What was said.", "Transcript of the recording.")) {
            assertTrue(point, voiceNote.echoes(point))
        }
        // An order of such words is a to-do or an action item, as before.
        assertFalse(voiceNote.echoes("Write the letter to the school."))
    }

    @Test
    fun `a narration of the task, a stage of it and the text, the medium or a language with nothing else, goes`() {
        for (point in listOf(
            "Aim: Deliver notes in English.",
            "Initial point: Transcript creation.",
            "Final output: German notes produced.",
            "Outcome: Notes generated.",
            "Result: English text provided."
        )) {
            assertTrue(point, voiceNote.echoes(point, malayalamLines))
        }
        assertTrue(germanNotes.first().echoes("Ziel: Notizen auf Englisch erstellen."))
        // A stage of something said stays.
        for (point in listOf("Final exam in English on Monday.", "Goal: notes for the chemistry exam.", "The result of the blood test is fine.")) {
            assertFalse(point, voiceNote.echoes(point, malayalamLines))
        }
    }

    @Test
    fun `what was done to the recording itself goes as words of the medium alone`() {
        for (point in listOf("Note: Audio transcribed.", "Notes recorded.", "Speech recorded and transcribed.")) {
            assertTrue(point, PromptEcho.isEcho(point, malayalamLines))
            assertTrue(point, voiceNote.echoes(point))
        }
        assertTrue(germanNotes.first().echoes("Aufnahme transkribiert."))
        // A feature of recordings stays.
        assertFalse(voiceNote.echoes("Records and transcribes meetings."))
        assertFalse(PromptEcho.isEcho("Records conversations.", malayalamLines))
    }

    @Test
    fun `words the speaker said are the speaker's, also when they are the prompt's`() {
        val read = PromptRules.Spoken("Today I tried to keep it as it is unless the sentence leaves no doubt, in plain statements.")

        assertFalse(voiceNote.echoes("Plain statements.", spoken = read))
        assertFalse(voiceNote.echoes("Keep it as it is unless the sentence leaves no doubt.", spoken = read))
        // Not when one of its words wasn't said, nor for a Malayalam transcript, which holds no English word.
        assertTrue(voiceNote.echoes("Plain statements, one idea per point.", spoken = read))
        assertTrue(voiceNote.echoes("Plain statements.", spoken = PromptRules.Spoken("ഇന്ന് മഴ പെയ്തു, റോഡ് മുഴുവൻ വെള്ളം.")))
    }

    @Test
    fun `the name's rules go, a name of a subject stays`() {
        for (title in listOf("Subject Line of an Email", "Title of a Story", "People, Places and Things", "Email Subject Line")) {
            assertTrue(title, name.echoes(title))
        }
        for (title in listOf("Garden Shed Repair", "Email to the Landlord", "Story Time at the Library", "Places to Visit in Spring")) {
            assertFalse(title, name.echoes(title))
        }
    }

    @Test
    fun `the to-dos' rules go, a to-do of the examples' kind stays, as the examples are quoted`() {
        for (item in listOf("Find the to-dos and the ideas.", "Never make up a time.", "Do not repeat.", "At most 10 words, without the time.")) {
            assertTrue(item, todos.echoes(item))
        }
        for (item in listOf("Call the bank about the loan.", "Send the report to Anna.", "Book a room for the workshop.", "Find the receipt for the bike.")) {
            assertFalse(item, todos.echoes(item))
        }
    }

    @Test
    fun `the map's rules go, a point and a summary of a subject stay`() {
        for (point in listOf("Write them as the person's own notes.", "Use only what the text says.", "Most important first.", "1 to 4 words.")) {
            assertTrue(point, topics.echoes(point))
        }
        assertFalse(topics.echoes("Book the car service before the holidays."))
        assertFalse(topics.echoes("The roof of the garage leaks again."))
        for (sentence in listOf("No lists, no headings.", "Never report on the recordings.", "Keep times as they were said.", "The newest one counts.")) {
            assertTrue(sentence, summary.echoes(sentence))
        }
        assertFalse(summary.echoes("The repair is planned for spring, and nothing is decided about the boards."))
    }

    // The review of 0.7.3: the shapes below got through 0.7.3 as merged, or went though they are real.

    /** What a Malayalam call read: no English word of it is said, so no piece is the speaker's. */
    private val malayalamSpoken = PromptRules.Spoken("ഇന്ന് വൈകുന്നേരം ചന്തയിൽ പോയി പച്ചക്കറി വാങ്ങണം, പിന്നെ ബാങ്കിൽ പോകണം.")

    @Test
    fun `a narration of the task without a stage word goes, as does one numbered with an ordinal or a label`() {
        for (point in listOf(
            "Note: English notes provided.", "English notes provided.", "Notes created in English.", "Task completed.",
            "Instructions followed.", "As instructed.", "First idea: Note creation.", "Last idea: English notes provided.",
            "Key idea: English notes.", "Next step: Provide notes.", "Final idea.", "Main point.", "Goal: Provide.", "Note creation.",
            "Notes created.", "Deliver English notes.", "Topic: voice notes.", "Overview: voice recording notes.",
            "Instruction to write in English.", "Following the instruction."
        )) {
            assertTrue(point, voiceNote.echoes(point, malayalamLines, malayalamSpoken))
        }
        for (point in listOf(
            "Notizen auf Deutsch erstellt.", "Deutsche Notizen erstellt.", "Erste Idee: Notiz erstellen.",
            "Letzte Idee: Deutsche Notizen bereitgestellt.", "Wie angewiesen.", "Notizen auf Deutsch liefern.",
            "Anweisung: auf Deutsch schreiben."
        )) {
            assertTrue(point, germanNotes.first().echoes(point, spoken = malayalamSpoken))
        }
    }

    /**
     * 0.7.4: the label's word is read whole, umlaut and all, as the phone reads it. Before, the
     * JVM read the pattern's \W as ASCII only and took "bersicht" of "Übersicht", so these tests
     * kept a narration the phone drops.
     */
    @Test
    fun `a German label that starts with an umlaut is a label, as on the phone`() {
        assertTrue(germanNotes.first().echoes("Übersicht: Sprachnotizen.", spoken = malayalamSpoken))
        assertTrue(germanNotes.first().echoes("- Übersicht: Aufnahme.", spoken = malayalamSpoken))
        assertFalse(germanNotes.first().echoes("Übersicht: Gartenzaun streichen.", spoken = malayalamSpoken))
    }

    @Test
    fun `what a notes app does stays, and so does a real point with a stage word in it`() {
        for (point in listOf(
            "Creates notes from recordings.", "Notes are generated.", "Generates notes after the meeting.", "One idea: rent a van.",
            "First person to reply gets the tickets.", "Write the essay in the first person.", "Key decisions on the budget.",
            "Next steps for the garden.", "Final exam in English on Monday.", "Creates English notes after each meeting.",
            "Users want notes in English.", "Topic: the garden fence.", "Topic in English.", "Notes have a title, points and action items.",
            "Points for the next meeting."
        )) {
            assertFalse(point, voiceNote.echoes(point, malayalamLines, malayalamSpoken))
        }
    }

    @Test
    fun `the rules said back in other words go, in English and German`() {
        for (point in listOf(
            "Fillers removed.", "Filler words removed.", "Small talk removed.", "Repetitions removed.", "Names and places kept.",
            "Details left out.", "Without filler words.", "Without repetition.", "Don't repeat.", "Style: plain statements.",
            "Tone: plain statements.", "Perspective: first person.", "Point of view: first person.", "Single idea per point.",
            "1st person.", "Brevity.", "Clarity and brevity.", "Short and clear.", "Nothing extra.", "Max 6 points.", "One or two points.",
            "Repeats removed.", "Filler-free.", "One idea each.", "No reporting.", "Empty list if none.", "Do not start with The."
        )) {
            assertTrue(point, voiceNote.echoes(point, malayalamLines, malayalamSpoken))
        }
        for (point in listOf(
            "Ich-Perspektive.", "Erste Person.", "Füllwörter entfernt.", "Wiederholungen entfernt.", "Schlichte Aussagen verwendet.",
            "Stil: schlichte Aussagen.", "Kurze Notizen.", "Knapp formuliert.", "Nichts erfinden.", "Maximal sechs Punkte.",
            "Ein Gedanke je Punkt.", "Keine Fuellwoerter.", "Nichts hinzugefügt.", "Namen und Orte behalten.", "Unter 20 Wörtern.",
            "Aussagen: schlicht."
        )) {
            assertTrue(point, germanNotes.first().echoes(point, spoken = malayalamSpoken))
        }
        // Real points with the same words stay.
        for (point in listOf("Plain flour for the cake.", "Keep the receipts.", "Short trip to the lake.", "Removed the old shelf.", "Free parking at the station.")) {
            assertFalse(point, voiceNote.echoes(point, malayalamLines, malayalamSpoken))
        }
        // What a notes app does in the words of the rules is the speaker's when said so, and the rules' when not.
        val app = PromptRules.Spoken("In the app the notes are written in English or German, whatever language you speak.")
        assertFalse(voiceNote.echoes("Notes are written in English or German.", spoken = app))
        assertTrue(voiceNote.echoes("Notes are written in English or German.", malayalamLines, malayalamSpoken))
    }

    @Test
    fun `a to-do of everyday words stays, also for a Malayalam part, as a phrase of one such word is one only as written`() {
        for (item in listOf(
            "Fill in the form", "Make a list", "Stay in Germany", "Follow the instructions", "Translate the letter into German",
            "Answer the letter in German", "Change the names on the list", "Add names and numbers to the list"
        )) {
            assertFalse(item, todos.echoes(item, spoken = malayalamSpoken))
        }
        // As written, the phrase is the rule: "In English.", "No lists.", "At most 8 points."
        for (piece in listOf("In English.", "Write in English.", "No lists.", "At most 8 points.", "In der Ich-Form.")) {
            assertTrue(piece, voiceNote.echoes(piece, spoken = malayalamSpoken))
        }
    }

    @Test
    fun `three words of a rule sentence out of its order are no echo of it`() {
        // The to-do prompt: "... for a meeting or visit at a set time ...".
        assertFalse(todos.echoes("Set a time for the visit", spoken = malayalamSpoken))
        // In its order, they are: "Keep names, numbers and places".
        assertTrue(voiceNote.echoes("Keep names, numbers and places.", spoken = malayalamSpoken))
    }

    @Test
    fun `words a long recording says far apart are not the speaker's, words said together are`() {
        // Made up: a meeting of some 400 words that says "idea" and "point" far apart, "first person" and "short notes" together.
        val meeting = PromptRules.Spoken(
            ("We met to plan the move to the new office in June. Anna had an idea about the desks, and Tom said the budget is tight. " +
                "Ben was the first person to ask about parking. Keep the old printer, he said, and short notes on the costs " +
                "go to the board. At this point nobody knows the rent, and the landlord answers next week. ").repeat(4)
        )

        for (rule in listOf("One idea per point.", "Keep it short.", "No small talk.", "At most 6 points.")) {
            assertTrue(rule, voiceNote.echoes(rule, spoken = meeting))
        }
        for (said in listOf("First person.", "Short notes.")) {
            assertFalse(said, voiceNote.echoes(said, spoken = meeting))
        }
    }

    @Test
    fun `what was said in another German form is the speaker's`() {
        val said = PromptRules.Spoken("Wir schreiben die Briefe in einfacher Sprache und verwenden kurze Sätze.")

        assertFalse(germanNotes.first().echoes("Einfache Sprache verwenden.", spoken = said))
        assertTrue(germanNotes.first().echoes("Einfache Sprache verwenden.", spoken = malayalamSpoken))
    }

    @Test
    fun `a piece of the medium's words alone is the speaker's when they said it so`() {
        val said = PromptRules.Spoken("The app makes an audio recording, records audio and writes the transcript, and the voice recording stays on the phone.")

        for (point in listOf("Audio recording.", "Records audio and writes the transcript.", "Voice recording.")) {
            assertFalse(point, voiceNote.echoes(point, spoken = said))
            assertTrue(point, voiceNote.echoes(point, spoken = malayalamSpoken))
        }
    }

    @Test
    fun `a short real point that shares one or two words with a rule sentence stays`() {
        // One word of "The words come from speech recognition, ...", two of "You turn what was said in a long meeting ...".
        assertFalse(voiceNote.echoes("Speech recognition.", spoken = malayalamSpoken))
        assertFalse(notes(RecordingMode.LONG_MEETING, "en").echoes("Long meeting.", spoken = malayalamSpoken))
    }

    @Test
    fun `a long piece with five words in a row of a rule sentence is an echo of it, whatever else it says`() {
        assertTrue(voiceNote.echoes("The garden plan: keep names, numbers, dates and places in it.", spoken = malayalamSpoken))
    }

    @Test
    fun `a rule phrase with a word of the medium is still the rule`() {
        assertTrue(voiceNote.echoes("First-person notes.", spoken = malayalamSpoken))
        assertTrue(voiceNote.echoes("Plain statements in the notes.", spoken = malayalamSpoken))
    }

    @Test
    fun `the to-do prompt's quoted examples are no rules, and its header's fixed words are`() {
        assertFalse(todos.echoes("Remind me to call the bank.", spoken = malayalamSpoken))
        assertTrue(todos.echoes("Repeat unless a new time is said for it.", spoken = malayalamSpoken))
        assertFalse(PromptRules.of(shipped.getTodoExtractionPrompt("en")).echoes("Repeat unless a new time is said for it.", spoken = malayalamSpoken))
    }

    @Test
    fun `without a system prompt nothing gives its rules back`() {
        assertFalse(PromptRules.NONE.echoes("Plain statements."))
        assertFalse(voiceNote.echoes(""))
    }

    @Test
    fun `the checks of a call say which rule left a piece out`() {
        val checks = AnswerChecks(malayalamLines, voiceNote)

        assertEquals(AnswerDrop.PROMPT, checks.dropOf("Text is Malayalam."))
        assertEquals(AnswerDrop.RULES, checks.dropOf("One idea per point."))
        assertEquals(null, checks.dropOf("Buy vegetables at the market this evening."))
    }
}
