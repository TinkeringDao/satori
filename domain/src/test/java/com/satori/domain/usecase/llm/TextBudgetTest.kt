package com.satori.domain.usecase.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A cap set in characters for English and German holds 0.6 times as many characters of a
 * Malayalam text, which costs Gemma 4 1.61 times the tokens per character (2.93 characters a token
 * against 4.72 for English on parallel FLEURS sentences).
 */
class TextBudgetTest {

    // Tomorrow morning call the doctor, then send the meeting's report: "doctor", "meeting" and
    // "report" in Malayalam letters, as the Malayalam model writes English words.
    private val malayalam = "നാളെ രാവിലെ ഡോക്ടറെ വിളിക്കണം, പിന്നെ മീറ്റിംഗിന്റെ റിപ്പോർട്ട് അയക്കണം"
    private val english = "Tomorrow morning call the doctor, then send the report of the meeting."

    @Test
    fun `a cap holds its characters of English and German, and six tenths of them of Malayalam`() {
        assertEquals(6_000, TextBudget.chars(6_000, english))
        assertEquals(6_000, TextBudget.chars(6_000, "Morgen früh den Arzt anrufen, dann den Bericht der Sitzung schicken."))
        assertEquals(3_600, TextBudget.chars(6_000, malayalam))
        assertEquals(3_000, TextBudget.chars(5_000, malayalam))
        assertEquals(1_800, TextBudget.chars(3_000, malayalam))
    }

    @Test
    fun `an English text with a Malayalam name is counted as English`() {
        assertEquals(6_000, TextBudget.chars(6_000, "We met ശ്രീധരൻ at the station and talked about the budget for the next quarter."))
    }

    @Test
    fun `a Malayalam line costs its length over six tenths, rounded up, and an English one its length`() {
        assertEquals(english.length, TextBudget.cost(english))
        assertEquals(Math.ceil(malayalam.length / 0.6).toInt(), TextBudget.cost(malayalam))
        // 3,600 characters of Malayalam cost what 6,000 of English do.
        assertEquals(6_000, TextBudget.cost("ക".repeat(3_600)))
    }
}
