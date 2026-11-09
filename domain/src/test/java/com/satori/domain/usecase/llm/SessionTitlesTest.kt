package com.satori.domain.usecase.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTitlesTest {

    @Test
    fun `keeps a plain specific title as it is`() {
        assertEquals("Budget review with Anna", SessionTitles.clean("Budget review with Anna"))
        assertEquals("Einkaufsliste für Samstag", SessionTitles.clean("Einkaufsliste für Samstag"))
    }

    @Test
    fun `removes labels, quotes, Markdown and the full stop`() {
        assertEquals("Budget review", SessionTitles.clean("Title: \"Budget review.\""))
        assertEquals("Budget review", SessionTitles.clean("**Title:** Budget review"))
        assertEquals("Budget review", SessionTitles.clean("# Budget review"))
        assertEquals("Budget review", SessionTitles.clean("Here is a title: Budget review"))
        assertEquals("Umzug nach Köln", SessionTitles.clean("Titel: „Umzug nach Köln“"))
        assertEquals("Dentist on Friday", SessionTitles.clean("  'Dentist on Friday'  "))
    }

    @Test
    fun `removes other labels a small model puts first`() {
        assertEquals("Umzug nach Köln", SessionTitles.clean("Thema: Umzug nach Köln"))
        assertEquals("Budget review", SessionTitles.clean("Topic: Budget review"))
        assertEquals("Budget review", SessionTitles.clean("Summary: Budget review"))
    }

    @Test
    fun `reads a fenced answer`() {
        assertEquals("Trip to Lisbon", SessionTitles.clean("```json\n{\"title\": \"Trip to Lisbon\"}\n```"))
        assertEquals("Trip to Lisbon", SessionTitles.clean("```\nTrip to Lisbon\n```"))
        assertEquals("Garden plans", SessionTitles.clean("```markdown\nGarden plans\n```"))
    }

    @Test
    fun `takes the line after an introduction`() {
        assertEquals("Garden plans", SessionTitles.clean("Here's a title:\nGarden plans\n"))
    }

    @Test
    fun `takes the title field of a JSON answer`() {
        assertEquals("Trip to Lisbon", SessionTitles.clean("{\"title\": \"Trip to Lisbon\", \"summary\": \"…\"}"))
        assertEquals("Reise nach Lissabon", SessionTitles.clean("{\"titel\": \"Reise nach Lissabon\"}"))
        assertNull(SessionTitles.clean("{\"summary\": \"no title here\"}"))
    }

    @Test
    fun `keeps a question mark`() {
        assertEquals("Where did the keys go?", SessionTitles.clean("Where did the keys go?"))
    }

    @Test
    fun `rejects titles that only name the kind of text`() {
        assertNull(SessionTitles.clean("Audio Transcription Summary"))
        assertNull(SessionTitles.clean("Summary"))
        assertNull(SessionTitles.clean("Meeting Notes"))
        assertNull(SessionTitles.clean("Summary of the recording."))
        assertNull(SessionTitles.clean("Zusammenfassung der Aufnahme"))
        assertNull(SessionTitles.clean("Transkription einer Sprachnotiz"))
        assertNull(SessionTitles.clean("Zusammenfassung des Gesprächs"))
        assertNull(SessionTitles.clean("Summary of the conversation"))
        assertNull(SessionTitles.clean("Meeting minutes"))
        assertNull(SessionTitles.clean("Besprechungsprotokoll"))
        assertNull(SessionTitles.clean("Sprachaufnahmen"))
        assertNull(SessionTitles.clean("   "))
        assertNull(SessionTitles.clean(null))
    }

    @Test
    fun `rejects a title that reads the text as a request to analyse it`() {
        assertNull(SessionTitles.clean("Request for analysis"))
        assertNull(SessionTitles.clean("Summary request"))
        assertNull(SessionTitles.clean("Anfrage zur Aufnahme"))
        assertEquals("Leave request for June", SessionTitles.clean("Leave request for June"))
    }

    @Test
    fun `accepts a generic word next to a specific one`() {
        assertEquals("Meeting with the landlord", SessionTitles.clean("Meeting with the landlord"))
        assertEquals("Besprechung zum Hausbau", SessionTitles.clean("Besprechung zum Hausbau"))
    }

    @Test
    fun `cuts long titles at a word boundary`() {
        val long = "Planning the very long weekend trip to the mountains with the whole family and friends"
        val cleaned = SessionTitles.clean(long)!!
        assertTrue(cleaned.length <= SessionTitles.MAX_LENGTH)
        assertTrue(long.startsWith(cleaned))
        assertTrue(long[cleaned.length] == ' ')
    }

    @Test
    fun `a name never ends on Text, Recording or Note, in English or German`() {
        // The 1B named a Malayalam recording of 2026-10-16 so; these are made up.
        assertEquals("Village Market", SessionTitles.clean("Village Market Text"))
        assertEquals("Packing for the trip", SessionTitles.clean("Packing for the trip - Notes"))
        assertEquals("Anna's birthday", SessionTitles.clean("Anna's birthday note."))
        assertEquals("Umzug nach Köln", SessionTitles.clean("Umzug nach Köln Aufnahme"))
        // One word left was said of the kind (review of 0.7.1): the notes' title or the default name is used.
        assertNull(SessionTitles.clean("Budget Recording"))
        assertNull(SessionTitles.clean("Einkaufsliste Notizen"))
        // Such words elsewhere in a name stay.
        assertEquals("Notes on the budget", SessionTitles.clean("Notes on the budget"))
        assertEquals("Text messages for Anna", SessionTitles.clean("Text messages for Anna"))
        // Nothing but such words is no name.
        assertNull(SessionTitles.clean("Text Notes"))
        assertNull(SessionTitles.clean("Note"))
    }

    @Test
    fun `what was said of the kind goes with it, and a name left with one word is none`() {
        assertEquals("Key Points", SessionTitles.clean("Key Points of the Text"))
        assertEquals("Victims of the State", SessionTitles.clean("Victims of the State: A Text"))
        assertEquals("Thank You", SessionTitles.clean("Thank You Note"))
        for (broken in listOf(
            "Story of the Text", "Highlights of the Recording", "Summary of the Malayalam Text", "Doctor's Note",
            "Field Recording", "Release Notes", "Song Text", "Arzt Notiz"
        )) {
            assertNull(broken, SessionTitles.clean(broken))
        }
        // A one-word name without such a word stays.
        assertEquals("Dentist", SessionTitles.clean("Dentist"))
    }

    @Test
    fun `a Text label in front goes as a Title label does`() {
        assertEquals("Family Conflict", SessionTitles.clean("Text: Family Conflict"))
        assertEquals("Text messages for Anna", SessionTitles.clean("Text messages for Anna"))
    }

    @Test
    fun `a long name cut on such a word loses it too`() {
        // Cut at 60 characters, it would end on "notes".
        val long = "Planning the weekend trip to the hills with cousins notes for Sunday and Monday"
        assertEquals("Planning the weekend trip to the hills with cousins", SessionTitles.clean(long))
    }

    // ─── A title in the words of what it names (0.7.2) ───────────────────────────
    // The notes a name or a title names, made up: a recording about an app that records meetings
    // and writes notes (the subject of 2026-10-17's Malayalam note), and one about a budget.
    private val appNotes = "- Records meetings and gives notes and minutes; The AI runs offline on the phone; Helps people who find writing hard"
    private val budgetNotes = "- Budget moves to March; Anna updates the plan before Friday; The meeting is on Monday"
    private val germanAppNotes = "- Die App nimmt Besprechungen auf und schreibt Notizen und Protokolle; Die KI läuft offline auf dem Handy"
    private val germanBudget = "- Das Budget wird auf März verschoben; Anna aktualisiert den Plan bis Freitag"

    @Test
    fun `a name of the subject's own words stays, generic or not, ending on a kind word or not`() {
        for (name in listOf("Meeting Notes & Minutes", "Notes and Minutes", "Recording Meetings", "Meeting Notes", "Voice Notes App")) {
            assertEquals(name, SessionTitles.clean(name, subject = appNotes))
        }
        // Without its subject the same names are generic, as in 0.7.1.
        assertNull(SessionTitles.clean("Meeting Notes & Minutes"))
        assertNull(SessionTitles.clean("Recording Meetings"))
        assertEquals("Doctor's Note", SessionTitles.clean("Doctor's Note", subject = "- Get a doctor's note for school on Monday"))
        assertEquals("Release Notes", SessionTitles.clean("Release Notes", subject = "- Release notes for version 2 are due Friday"))
    }

    @Test
    fun `a generic name another text gives still goes, and so does one with a word its subject never says`() {
        assertNull(SessionTitles.clean("Voice Notes & Minutes", subject = appNotes))
        for (name in listOf("Meeting Notes", "Voice Notes", "Audio Transcription Summary", "Summary of the recording", "Release Notes")) {
            assertNull(name, SessionTitles.clean(name, subject = budgetNotes))
        }
        // One word that says something is a kind or a topic alone, whatever the subject says.
        assertNull(SessionTitles.clean("Notes", subject = appNotes))
        assertNull(SessionTitles.clean("Meeting Notes", subject = "- The meeting moved to Monday"))
        // A specific name is the same with a subject or without.
        assertEquals("Budget Moves to March", SessionTitles.clean("Budget Moves to March", subject = budgetNotes))
    }

    @Test
    fun `a kind word on the end goes as before when the subject doesn't say it`() {
        assertEquals("Village Market", SessionTitles.clean("Village Market Text", subject = "- Village market opens on Saturday"))
        assertEquals("Family Conflict", SessionTitles.clean("Family Conflict Text", subject = "- Parents lose their children to the state"))
    }

    @Test
    fun `German names of the subject's words stay, and a generic one of another subject goes`() {
        assertEquals("Besprechungsnotizen und Protokolle", SessionTitles.clean("Besprechungsnotizen und Protokolle", subject = germanAppNotes))
        assertEquals("Notizen und Protokolle", SessionTitles.clean("Notizen und Protokolle", subject = germanAppNotes))
        assertNull(SessionTitles.clean("Besprechungsprotokoll", subject = germanBudget))
        assertNull(SessionTitles.clean("Besprechungsnotizen", subject = germanBudget))
    }

    @Test
    fun `the verdict says why a title is none`() {
        assertEquals(SessionTitles.Verdict.Kept("Budget review"), SessionTitles.verdict("Title: Budget review."))
        assertEquals(SessionTitles.Verdict.Empty, SessionTitles.verdict(null))
        assertEquals(SessionTitles.Verdict.Empty, SessionTitles.verdict("  "))
        assertEquals(SessionTitles.Verdict.Empty, SessionTitles.verdict("Title:"))
        assertEquals(SessionTitles.Verdict.Empty, SessionTitles.verdict("""{"summary": "A plan"}"""))
        assertEquals(SessionTitles.Verdict.Generic, SessionTitles.verdict("Audio Transcription Summary"))
        assertEquals(SessionTitles.Verdict.Generic, SessionTitles.verdict("Notizen und Mitschrift"))
        assertEquals(SessionTitles.Verdict.KindAlone, SessionTitles.verdict("Release Notes"))
        assertEquals(SessionTitles.Verdict.KindAlone, SessionTitles.verdict("Arzt Notiz"))
        assertEquals(SessionTitles.Verdict.Kept("Meeting Notes", bySubject = true), SessionTitles.verdict("Meeting Notes", subject = appNotes))
    }

    // ─── The review of 0.7.2 ────────────────────────────────────────────────────

    @Test
    fun `a title only its subject's words keep says so, one the rules keep is the same with a subject or without`() {
        // A meeting's notes say "meeting" and "minutes": the title is kept, but only by its subject.
        val meeting = "- The meeting agreed the budget for the third quarter; Anna sends the minutes by Friday"
        assertEquals(SessionTitles.Verdict.Kept("Meeting Minutes", bySubject = true), SessionTitles.verdict("Meeting Minutes", subject = meeting))
        assertEquals(SessionTitles.Verdict.Kept("Q3 Budget Review"), SessionTitles.verdict("Q3 Budget Review", subject = meeting))
        assertEquals(SessionTitles.Verdict.Kept("Q3 Budget Review"), SessionTitles.verdict("Q3 Budget Review"))
        // The words of the subject keep a kind word on the end as written; the rules keep the rest, so it is no weak one.
        assertEquals(
            SessionTitles.Verdict.Kept("Packing for the trip - Notes"),
            SessionTitles.verdict("Packing for the trip - Notes", subject = "- Notes on packing for the trip to the hills")
        )
        assertEquals(
            SessionTitles.Verdict.Kept("Besprechung Protokoll", bySubject = true),
            SessionTitles.verdict("Besprechung Protokoll", subject = "- Die Besprechung zum Budget; Anna schickt das Protokoll bis Freitag")
        )
    }

    @Test
    fun `a title of only the words the name's prompt forbids names the kind of text, whatever its subject says`() {
        // A junk transcript's note (made up) that only says it is a recording.
        val junk = "- This is an audio recording.\n- Transcript of the recording.\n- Audio transcription summary."
        assertEquals(SessionTitles.Verdict.KindAlone, SessionTitles.verdict("Audio Recording", subject = junk))
        assertEquals(SessionTitles.Verdict.Generic, SessionTitles.verdict("Recording Transcript", subject = junk))
        assertEquals(SessionTitles.Verdict.Generic, SessionTitles.verdict("Audio Transcription Summary", subject = junk))
        assertEquals(SessionTitles.Verdict.KindAlone, SessionTitles.verdict("Transkript der Aufnahme", subject = "- Transkript der Aufnahme."))
        // A word besides them that its subject says keeps it, as for the 11:31 note about an app that records meetings.
        assertEquals("Recording Meetings", SessionTitles.clean("Recording Meetings", subject = appNotes))
    }
}
