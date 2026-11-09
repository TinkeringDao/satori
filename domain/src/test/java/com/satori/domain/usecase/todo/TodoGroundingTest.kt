package com.satori.domain.usecase.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A found item is kept only when its content words are in the segments it cites: the copies of
 * 2026-10-14 share none with theirs, the items said in a meeting share their key words, and so
 * does a paraphrase. A Malayalam item's words are the ones the model copied from its segment, in
 * the transcript's own script.
 */
class TodoGroundingTest {

    // Two segments of the synthetic English meeting feed the copies cited (en-meeting.wav).
    private val meeting = "We have two open positions for backend developers. The first round of interviews is next week. " +
        "Please send me your availability for Tuesday and Wednesday afternoon. I will book the rooms once I have " +
        "everyone's answers. Fourth, the customer feedback from the last survey. Most customers like the new app, but " +
        "many complain that the login takes too long. The team will look into it and propose a fix in the next sprint. " +
        "Let's also add a short question about the login to the next survey. Finally, a reminder that the office is " +
        "closed on Friday for the team event. We meet at the main entrance at 9.30. Remind me to order the lunch for " +
        "the team event tomorrow morning. That's all for today. Thanks everyone."

    @Test
    fun `the four copies of other recordings' items share no word with the meeting they were given`() {
        // Three of the shape of the copies of 2026-10-14, paraphrased, and the prompt's own example.
        for (copy in listOf("Bake something for six weeks", "Weigh how exact", "The places were and to visit them", "Make the clock bigger")) {
            assertFalse(copy, TodoGrounding.isGrounded(copy, meeting))
        }
    }

    @Test
    fun `the items said in the meeting are in it`() {
        assertTrue(TodoGrounding.isGrounded("Book the rooms once I have everyone's answers", meeting))
        assertTrue(TodoGrounding.isGrounded("Order the lunch for the team event", meeting))
        assertTrue(TodoGrounding.isGrounded("Add a question about the login to the survey", meeting))
    }

    @Test
    fun `the prompt's own example is no item of a garbled note`() {
        // A short note the speech model made little of (made up, of the shape of 296d127d's).
        val garbled = "So the uh test of the and then okay so"

        assertFalse(TodoGrounding.isGrounded("Make the clock bigger", garbled))
        assertFalse(TodoGrounding.isGrounded("Option to delete old recordings", garbled))
    }

    @Test
    fun `a paraphrase that keeps the key words is kept`() {
        assertTrue(TodoGrounding.isGrounded("Phone the bank regarding the loan", "I have to call the bank about the loan."))
        // Two of five words: the boiler and its breaking.
        assertTrue(TodoGrounding.isGrounded("Ring the heating engineer about the broken boiler", "I must get someone to fix the boiler, it broke."))
        assertTrue(TodoGrounding.isGrounded("Option to delete older recordings", "There should be a way of deleting the old recordings."))
    }

    @Test
    fun `German forms of a word count as the word, and a word inside a compound too`() {
        assertTrue(TodoGrounding.isGrounded("Möbel liefern lassen", "Die Möbel werden am Montag geliefert."))
        assertTrue(TodoGrounding.isGrounded("Arztpraxis anrufen", "Ich muss beim Arzt anrufen."))
        assertTrue(TodoGrounding.isGrounded("Mittagessen für den Ausflug bestellen", "Erinnere mich daran, morgen früh das Mittagessen für den Ausflug zu bestellen."))
    }

    @Test
    fun `a German zu infinitive is the plain one the spotter makes of it`() {
        assertTrue(TodoGrounding.isGrounded("Einkaufen", "Nicht vergessen, morgen einzukaufen."))
        assertTrue(TodoGrounding.isGrounded("Abwaschen", "Erinnere mich daran, morgen abzuwaschen."))
        assertTrue(TodoGrounding.isGrounded("Aufräumen", "Erinnere mich morgen daran, aufzuräumen."))
        assertTrue(TodoGrounding.isGrounded("Anrufen", "Denk daran, heute Abend anzurufen."))
        assertTrue(TodoGrounding.isGrounded("Den Müll rausbringen", "Erinnere mich daran, den Müll rauszubringen."))
        // And the other way round, as the model may copy it.
        assertTrue(TodoGrounding.isGrounded("Einzukaufen", "Ich muss morgen einkaufen."))
    }

    @Test
    fun `one word in common is not enough for an item of several`() {
        // A copy of the prompt's example where a bank holiday is spoken of: one word of three.
        assertFalse(TodoGrounding.isGrounded("Call the bank about the loan", "Monday is a bank holiday, so the office stays shut."))
    }

    @Test
    fun `a word found only by its first three letters is not enough alone`() {
        // The prompt's example where something big is spoken of: "bigger" only by "big".
        assertFalse(TodoGrounding.isGrounded("Make the clock bigger", "That is a big problem for the move."))
        assertFalse(TodoGrounding.isGrounded("Book the car", "She started her career in a bank."))
        // Two such words, or one found firmly, are.
        assertTrue(TodoGrounding.isGrounded("Fix the car", "The cars need fixing before the trip."))
        assertTrue(TodoGrounding.isGrounded("Cook something for the week", "I'll do the cooking on Sunday."))
        assertTrue(TodoGrounding.isGrounded("Make the clock bigger", "The clock on the screen is too small."))
    }

    @Test
    fun `times, numbers and words that say nothing alone are no evidence`() {
        assertFalse(TodoGrounding.isGrounded("Do something tomorrow at 5", "Tomorrow at 5 we meet."))
        assertEquals(listOf("bake"), TodoGrounding.contentWords("Bake something for six weeks"))
        // An item with no content word at all, a fragment ("See you" has one: the greeting rule drops it).
        assertFalse(TodoGrounding.isGrounded("It had been", "It had been a long day."))
    }

    @Test
    fun `a Malayalam item's copied words are found in its segment as written, and made-up ones are not`() {
        // Tomorrow morning call the doctor, then send the report.
        val segment = "നാളെ രാവിലെ ഡോക്ടറെ വിളിക്കണം, പിന്നെ റിപ്പോർട്ട് അയക്കണം."

        assertTrue(TodoGrounding.isGrounded("ഡോക്ടറെ വിളിക്കണം", segment))
        // "doctor" in its other form, as the model may copy it.
        assertTrue(TodoGrounding.isGrounded("ഡോക്ടർ വിളിക്കണം", segment))
        // Make the clock bigger: nothing of it is in the segment.
        assertFalse(TodoGrounding.isGrounded("ക്ലോക്ക് വലുതാക്കണം", segment))
        // The English text itself can't be found in Malayalam script.
        assertFalse(TodoGrounding.isGrounded("Call the doctor", segment))
    }

    @Test
    fun `a Malayalam word copied with another spelling of the same letters is found as written in the line`() {
        // Made up of code points: a word with nta and one ending in chillu l, as a line is stored
        // (nta as chillu n, virama, rra), and as the model may copy them, the old way.
        val stored = "\u0D35\u0D40\u0D1F\u0D3F\u0D7B\u0D4D\u0D31\u0D46 \u0D15\u0D3E\u0D7D \u0D2A\u0D23\u0D3F"
        val oldWay = "\u0D35\u0D40\u0D1F\u0D3F\u0D28\u0D4D\u0D31\u0D46 \u0D15\u0D3E\u0D32\u0D4D\u200D"

        assertTrue(TodoGrounding.isGrounded(oldWay, stored))
        assertTrue(TodoGrounding.isGrounded(stored, oldWay + " പണി"))
    }
}
