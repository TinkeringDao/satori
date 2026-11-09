package com.satori.domain.usecase.llm

import com.satori.domain.model.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording about recordings, meetings or notes has them for its subject (0.7.2): on 2026-10-17
 * a Malayalam note about a voice-notes app lost its points about notes and recordings as echoes of
 * the order to read the Malayalam. Its points stay under every line of instructions a notes call
 * can have (a Malayalam recording's, an English or German one whose notes are in the other
 * language, a Rewrite notes under the recording's name), and those that only name the language,
 * the text or the recording still go. The lines are the app's own (res/values and values-de); the
 * texts and points are made up, none is the owner's.
 */
class PromptEchoSubjectTest {

    private val shipped = ShippedPrompts()

    // Made up: "This app records meetings, then gives notes and minutes."
    private val malayalamText = "ഈ ആപ്പ് മീറ്റിംഗുകൾ റെക്കോർഡ് ചെയ്യും, പിന്നെ നോട്ട്സും മിനിറ്റ്സും തരും."
    private val germanText = "Die App nimmt Besprechungen auf und gibt Notizen und Protokolle. Die KI läuft offline auf dem Handy. " +
        "Sie hilft Leuten, denen Schreiben schwerfällt."
    private val englishText = "This app records meetings and gives notes and minutes. Its AI runs offline on the phone. " +
        "It helps people who find writing hard."

    private fun instructions(text: String, topic: String?, language: String) =
        NotesTopic.prompt(text, topic, RecordingMode.SIMPLE_LISTENING, language, shipped).instructions

    /** A Malayalam recording's notes, in English. */
    private val malayalam = instructions(malayalamText, null, "en")

    /** English notes of a German text. */
    private val englishOfGerman = instructions(germanText, null, "en")

    /** German notes of an English text. */
    private val germanOfEnglish = instructions(englishText, null, "de")

    /** Rewrite notes of an English recording under its name. */
    private val englishTopic = instructions("We talked for a while.", "Offline meeting app", "en")

    /** Rewrite notes of a German recording under its name. */
    private val germanTopic = instructions("Wir haben eine Weile geredet.", "Offline-App", "de")

    @Test
    fun `the lines are those a notes call sends`() {
        assertTrue(malayalam.first(), malayalam.first().startsWith("Instruction, never to be repeated in your answer: read the Malayalam below"))
        assertTrue(englishOfGerman.first(), englishOfGerman.first().contains("German"))
        assertTrue(germanOfEnglish.first(), germanOfEnglish.first().startsWith("Anweisung, nie in der Antwort zu wiederholen:"))
        assertEquals(listOf("The topic is \"\".", "What was said:"), englishTopic)
        assertEquals(listOf("Das Thema ist \"\".", "Was gesagt wurde:"), germanTopic)
    }

    @Test
    fun `a point about recordings, notes or speech is about the subject under the Malayalam line`() {
        for (point in listOf(
            "AI writes the notes.",
            "Uses AI to write notes.",
            "Speech to text.",
            "Notes from recordings.",
            "AI transcription and notes.",
            "Audio recording and transcription.",
            "Records meetings and gives notes and minutes.",
            "The AI runs offline on the phone.",
            "Helps people who find writing hard.",
            "Uses AI.",
            "Written by AI."
        )) {
            assertFalse(point, PromptEcho.isEcho(point, malayalam))
        }
    }

    @Test
    fun `the same points stay in English and German notes of the other language, and under a recording's name`() {
        assertFalse(PromptEcho.isEcho("AI writes the notes.", englishOfGerman))
        assertFalse(PromptEcho.isEcho("Speech to text.", englishTopic))
        assertFalse(PromptEcho.isEcho("Notes from recordings.", englishTopic))
        assertFalse(PromptEcho.isEcho("Records meetings and writes the minutes.", englishTopic))
        assertFalse(PromptEcho.isEcho("KI schreibt die Notizen.", germanOfEnglish))
        assertFalse(PromptEcho.isEcho("Notizen aus Aufnahmen.", germanOfEnglish))
        assertFalse(PromptEcho.isEcho("Notizen aus Aufnahmen.", germanTopic))
        assertFalse(PromptEcho.isEcho("Nimmt Besprechungen auf und gibt Notizen und Protokolle.", germanOfEnglish))
    }

    @Test
    fun `a title about the medium stays once its kind word went`() {
        // SessionTitles takes "Notes" off "AI Speech Notes"; what is left names the subject.
        assertFalse(PromptEcho.isEcho("AI Speech", malayalam))
        assertFalse(PromptEcho.isEcho("AI Transcription", malayalam))
        assertTrue(PromptEcho.isEcho("Malayalam to English", malayalam))
    }

    @Test
    fun `a point that only names the language, the text or the recording still goes`() {
        for (point in listOf(
            "Text is Malayalam.",
            "Recorded in Malayalam.",
            "Recording in Malayalam.",
            "Malayalam recording.",
            "Malayalam audio.",
            "Spoken in Malayalam.",
            "Notes in English.",
            "Transcribed from Malayalam.",
            "The text is in EN."
        )) {
            assertTrue(point, PromptEcho.isEcho(point, malayalam))
        }
        assertTrue(PromptEcho.isEcho("German text.", englishOfGerman))
        assertTrue(PromptEcho.isEcho("Recorded in German.", englishOfGerman))
        assertTrue(PromptEcho.isEcho("Text auf Englisch.", germanOfEnglish))
        assertTrue(PromptEcho.isEcho("Aufnahme auf Englisch.", germanOfEnglish))
        assertTrue(PromptEcho.isEcho("Auf Englisch aufgenommen.", germanOfEnglish))
    }

    @Test
    fun `one word of the medium alone says only that there is a recording, and goes`() {
        for (point in listOf("Recording.", "Notes.", "Audio.", "This is a recording.", "The recordings were recorded.", "Eine Aufnahme.")) {
            assertTrue(point, PromptEcho.isEcho(point, malayalam))
        }
        // Words about the text alone, as before.
        assertTrue(PromptEcho.isEcho("What was said", malayalam))
        assertTrue(PromptEcho.isEcho("The text is written in words", malayalam))
        // An order of them is a to-do or an action item, as before.
        assertFalse(PromptEcho.isEcho("Use the notes", malayalam))
        assertFalse(PromptEcho.isEcho("Write the notes", malayalam))
    }

    // ─── The review of 0.7.2 ────────────────────────────────────────────────────

    /** The second call of an English recording's notes, its first answer left no point. */
    private val englishRetry = NotesTopic.prompt(englishText, null, RecordingMode.SIMPLE_LISTENING, "en", shipped, retry = true).instructions

    /** The same of a German recording. */
    private val germanRetry = NotesTopic.prompt(germanText, null, RecordingMode.SIMPLE_LISTENING, "de", shipped, retry = true).instructions

    /** The second call of a Malayalam recording's notes. */
    private val malayalamRetry = NotesTopic.prompt(malayalamText, null, RecordingMode.SIMPLE_LISTENING, "en", shipped, retry = true).instructions

    @Test
    fun `a piece that only says this is a recording goes, under every line a notes call can have`() {
        val lines = mapOf(
            "Malayalam" to malayalam, "English of German" to englishOfGerman, "a topic" to englishTopic,
            "an English retry" to englishRetry, "a Malayalam retry" to malayalamRetry
        )
        // A junk transcript's second answer, made up, of the shapes the review found kept.
        for (point in listOf(
            "This is an audio recording.", "Audio recording.", "Transcript of the recording.", "Speech recording.",
            "Notes of the recording.", "The recording has notes.", "Audio transcript.", "A recording of speech.",
            "Notes from the audio.", "Recording of a conversation.", "Voice recording.", "Recorded audio.", "The audio is unclear.",
            "Notes of the text."
        )) {
            for ((name, instructions) in lines) assertTrue("$point under $name", PromptEcho.isEcho(point, instructions))
        }
        for (point in listOf("Diese Aufnahme.", "Transkript der Aufnahme.", "Notizen der Aufnahme.", "Eine Sprachaufnahme.", "Audioaufnahme.")) {
            for (instructions in listOf(germanOfEnglish, germanTopic, germanRetry)) assertTrue(point, PromptEcho.isEcho(point, instructions))
        }
    }

    @Test
    fun `a piece about recordings as its subject stays, in the plural and as a feature`() {
        for (point in listOf(
            "Notes from recordings.", "Speech to text.", "Audio recording and transcription.", "Records conversations.",
            "Voice notes.", "Voice to text.", "AI writes the notes.", "Turns speech into text.", "Records meetings."
        )) {
            for (instructions in listOf(malayalam, englishTopic, englishRetry)) assertFalse(point, PromptEcho.isEcho(point, instructions))
        }
        assertFalse(PromptEcho.isEcho("Notizen aus Aufnahmen.", germanRetry))
        assertFalse(PromptEcho.isEcho("Sprachnotizen mit KI.", germanRetry))
        // An order of them is a to-do or an action item, as before.
        assertFalse(PromptEcho.isEcho("Record the conversation.", malayalam))
        assertFalse(PromptEcho.isEcho("Transcribe the audio.", malayalam))
    }

    @Test
    fun `a piece that only names the language goes under the retry of an English or German recording too`() {
        for (point in listOf("The recording is in English.", "Text in English.", "English speech.", "The speaker speaks English.", "Spoken English.")) {
            assertTrue(point, PromptEcho.isEcho(point, englishRetry))
        }
        for (point in listOf("Die Aufnahme ist auf Deutsch.", "Text auf Deutsch.", "Deutscher Text.")) {
            assertTrue(point, PromptEcho.isEcho(point, germanRetry))
        }
        // One that says something besides stays.
        assertFalse(PromptEcho.isEcho("Anna is learning English.", englishRetry))
        assertFalse(PromptEcho.isEcho("English lessons on Friday.", englishRetry))
        assertFalse(PromptEcho.isEcho("Der Deutschkurs beginnt im Mai.", germanRetry))
        // How it was said is no subject either: under the Malayalam line, and German of English.
        for (point in listOf("Conversation in Malayalam.", "Malayalam conversation.", "The speaker talks in Malayalam.")) {
            assertTrue(point, PromptEcho.isEcho(point, malayalam))
        }
        assertTrue(PromptEcho.isEcho("Auf Englisch gesprochen.", germanOfEnglish))
    }

    @Test
    fun `an answer that says nothing was found is made of the retry's words, and goes`() {
        for (point in listOf("The subject is unclear.", "Subject not mentioned.", "Nothing about this recording.", "Nothing.", "Unknown.")) {
            assertTrue(point, PromptEcho.isEcho(point, englishRetry))
        }
        assertTrue(PromptEcho.isEcho("Das Thema ist unklar.", germanRetry))
        assertTrue(PromptEcho.isEcho("Nichts erwähnt.", germanRetry))
        // "Something" is no subject either, under any line.
        assertTrue(PromptEcho.isEcho("The speaker talks about something.", malayalamRetry))
        assertTrue(PromptEcho.isEcho("The speaker talks about something.", malayalam))
        // A point with something to say besides stays.
        assertFalse(PromptEcho.isEcho("The budget is still unclear.", englishRetry))
        assertFalse(PromptEcho.isEcho("Nothing was decided about the venue.", englishRetry))
        assertFalse(PromptEcho.isEcho("Buy something for the party.", malayalam))
        assertFalse(PromptEcho.isEcho("Etwas über das Budget.", germanRetry))
        // Without instructions nothing is an echo, as before.
        assertFalse(PromptEcho.isEcho("The subject is unclear.", emptyList()))
    }

    @Test
    fun `an abbreviation names a thing, but a small word or a language's code in capitals does not`() {
        assertFalse(PromptEcho.isEcho("AI notes.", malayalam))
        assertFalse(PromptEcho.isEcho("TV recording.", malayalam))
        // "IS" is a small word, "EN" and "ML" the languages' codes.
        assertTrue(PromptEcho.isEcho("TEXT IS MALAYALAM.", malayalam))
        assertTrue(PromptEcho.isEcho("ML text.", malayalam))
        assertTrue(PromptEcho.isEcho("Notes in EN.", malayalam))
    }
}
