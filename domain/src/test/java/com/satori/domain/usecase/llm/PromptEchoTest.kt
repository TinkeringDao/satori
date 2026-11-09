package com.satori.domain.usecase.llm

import com.satori.domain.provider.ResourceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What of an answer repeats its prompt's instructions: the points the 1B made of the note on a
 * Malayalam text's language on 2026-10-16, and the order on the "said" field it stored as a to-do,
 * against the lines the app sends now. The points and items here are made up, of the shape the
 * 1B wrote; none is a line of the owner's.
 */
class PromptEchoTest {

    /** A Malayalam recording's notes call: its line of instructions, then the lead-in. */
    private val notes = listOf(
        "Instruction, never to be repeated in your answer: read the Malayalam below and write in English. " +
            "Read English words written in Malayalam letters as English.",
        ResourceProvider.TEXT_LEAD_IN
    )

    /** A Malayalam part's to-do call: one line, ending on the order on "said". */
    private val todos = listOf(
        "Instruction, never to be repeated in your answer: read the Malayalam below and write in English. " +
            "Read English words written in Malayalam letters as English. ${ResourceProvider.TODO_SAID}"
    )

    @Test
    fun `the points the 1B made of the old note on the language are echoes of the new line too`() {
        assertTrue(PromptEcho.isEcho("Text is Malayalam.", notes))
        assertTrue(PromptEcho.isEcho("Malayalam language used.", notes))
        assertTrue(PromptEcho.isEcho("The text is in Malayalam, the answer in English.", notes))
        assertTrue(PromptEcho.isEcho("English words are written in Malayalam letters.", notes))
        assertTrue(PromptEcho.isEcho("Instruction not to be repeated.", notes))
    }

    @Test
    fun `a point that says something besides the languages is kept`() {
        assertFalse(PromptEcho.isEcho("Malayalam film festival on Saturday at the town hall.", notes))
        assertFalse(PromptEcho.isEcho("Read the letter from the bank before the meeting.", notes))
        assertFalse(PromptEcho.isEcho("Read Malayalam books to the children.", notes))
        assertFalse(PromptEcho.isEcho("The medicine for grandmother runs out on Friday.", notes))
        assertFalse(PromptEcho.isEcho("Write the email to the landlord in English.", notes))
    }

    @Test
    fun `three of four content words found make an echo, two of three don't`() {
        val line = listOf("Instruction: check the harbour lights and the ferry times.")

        // harbour, lights, ferry of harbour, lights, ferry, prices: 3 of 4.
        assertTrue(PromptEcho.isEcho("Harbour lights, ferry prices", line))
        // harbour, ferry of harbour, ferry, prices: 2 of 3.
        assertFalse(PromptEcho.isEcho("Harbour ferry prices", line))
        // Small words don't count either way: harbour and lights of two.
        assertTrue(PromptEcho.isEcho("The lights of the harbour", line))
    }

    @Test
    fun `words count as one with their English endings off, or one the start of the other`() {
        val line = listOf("Copy the repeated item.")

        // "copied" is "copy" without its ending, "item" the start of "items".
        assertTrue(PromptEcho.isEcho("Items copied", line))
        // "repeat" the start of "repeated".
        assertTrue(PromptEcho.isEcho("Repeat the item", line))
        assertFalse(PromptEcho.isEcho("Repaint the item", line))
    }

    @Test
    fun `an answer of nothing but words about the text, its language or the answer is an echo`() {
        assertTrue(PromptEcho.isEcho("What was said", notes))
        assertTrue(PromptEcho.isEcho("The text is written in words", notes))
        // A number and small words are no words about the text.
        assertFalse(PromptEcho.isEcho("8", notes))
    }

    @Test
    fun `four words in a row of one line make an echo, whatever else the answer says`() {
        val line = listOf("Name the topic of the text below in 2 to 5 words, in English.")

        assertTrue(PromptEcho.isEcho("Dentist, the topic of the text below", line))
        // Small words and numbers in a row are no echo.
        assertFalse(PromptEcho.isEcho("Plumber visit in 2 to 5 days", line))
    }

    @Test
    fun `the old order on said, written as a to-do, is an echo of the new line, its words from two orders`() {
        // "Malayalam" of the order to read it, "copied" of the order on said.
        assertTrue(PromptEcho.isEcho("Malayalam text is copied as it is written there, at most 8 words", todos))
        assertTrue(PromptEcho.isEcho("Copy up to 8 words of the segment", todos))
        // A to-do of the owner's kind is not.
        assertFalse(PromptEcho.isEcho("Buy medicine for mother on the way home", todos))
        assertFalse(PromptEcho.isEcho("Copy the bank papers for the loan", todos))
    }

    @Test
    fun `German echoes are found the same way`() {
        val line = listOf(
            "Anweisung, nie in der Antwort zu wiederholen: was unten auf Malayalam steht, lesen und auf Deutsch schreiben. " +
                "Englische Wörter in Malayalam-Schrift als Englisch lesen."
        )

        assertTrue(PromptEcho.isEcho("Der Text ist auf Malayalam.", line))
        assertTrue(PromptEcho.isEcho("Englische Wörter in Malayalam-Schrift", line))
        assertFalse(PromptEcho.isEcho("Morgen die Unterlagen zur Bank bringen.", line))
        // A German point under a topic stays: of the lead-in, only "Thema" says something.
        assertFalse(PromptEcho.isEcho("Die Miete wurde erhöht.", listOf("Das Thema ist \"\".", "Was gesagt wurde:")))
    }

    @Test
    fun `a point, a title or a name that only names a translation, its audio, speech or speaker is an echo`() {
        val echoes = listOf(
            "Translated from Malayalam to English.",
            "Translation from Malayalam into English.",
            "The text is translated into English.",
            "English translation of the Malayalam text.",
            "The speaker speaks Malayalam.",
            "Malayalam audio.",
            "Malayalam speech.",
            "Content in Malayalam.",
            "Malayalam Translation",
            "Malayalam to English Translation",
            "English Translation"
        )
        for (echo in echoes) assertTrue(echo, PromptEcho.isEcho(echo, notes))
        // What says something besides stays.
        assertFalse(PromptEcho.isEcho("Speech recognition misheard the word", notes))
        assertFalse(PromptEcho.isEcho("Read the report before Friday", notes))
        assertFalse(PromptEcho.isEcho("He asked to translate the letter for the bank", notes))
    }

    @Test
    fun `an order of only such words is a to-do or an action item, not an echo`() {
        for (order in listOf("Write the letter", "Answer the letters", "Use the notes", "Den Brief schreiben")) {
            assertFalse(order, PromptEcho.isEcho(order, todos))
            assertFalse(order, PromptEcho.isEcho(order, notes))
        }
        // A statement of them still is: "What was said", "The text is written in words".
        assertTrue(PromptEcho.isEcho("The words are written", todos))
    }

    /**
     * 0.7.4: a first word with a colon after it is read as the phone reads it. ICU's \W, the
     * phone's, never takes a joiner (ZWNJ, ZWJ): after one, "Note" is no label there but an order's
     * verb, and the point stays. Before, the JVM read \W as ASCII only and took the joiner, so these
     * tests dropped what the phone keeps. A first word that begins with an umlaut or an accent
     * reads alike either way, as the label's word takes its letters.
     */
    @Test
    fun `a label before a colon is read as on the phone, a joiner before it a word's`() {
        for (label in listOf("Note: Audio transcribed.", "- Note: Audio transcribed.", "Nóte: Audio transcribed.")) {
            assertTrue(label, PromptEcho.isEcho(label, notes))
        }
        for (order in listOf("\u200CNote: Audio transcribed.", "\u200DNote: Audio transcribed.")) {
            assertFalse(order, PromptEcho.isEcho(order, notes))
        }
    }

    @Test
    fun `without instructions nothing is an echo`() {
        assertFalse(PromptEcho.isEcho("Text is Malayalam.", emptyList()))
        assertFalse(PromptEcho.isEcho("", notes))
    }

    @Test
    fun `kept leaves the echoes out and counts them`() {
        val (kept, drops) = AnswerChecks(notes).kept(listOf("Text is Malayalam.", "The roof leaks.", "Malayalam language used.")) { it }

        assertEquals(listOf("The roof leaks."), kept)
        assertEquals(mapOf(AnswerDrop.PROMPT to 2), drops)
    }
}
