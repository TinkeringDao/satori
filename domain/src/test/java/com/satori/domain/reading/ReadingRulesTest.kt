package com.satori.domain.reading

import com.satori.domain.repository.ReaderOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design D5's empty readings, with the numbers V2 gave on the phone (the band 0.500-1.079 tokens a
 * source piece) and the provisional floor until V3 (-1.5): fragments, the floor, the band, the
 * loops, and readings with no words. Synthetic Malayalam and English only.
 */
class ReadingRulesTest {

    /** A reading of [pieces] source pieces into [tokens] tokens, at a mean of [mean]. */
    private fun reading(text: String, pieces: Int = 20, tokens: Int = 15, mean: Double? = -0.4, capped: Boolean = false) =
        ReaderOutput(text, pieces, tokens, mean, capped)

    @Test
    fun `the numbers are V2's band and the provisional floor`() {
        assertEquals(0.500, ReadingRules.BAND_LOW, 0.0)
        assertEquals(1.079, ReadingRules.BAND_HIGH, 0.0)
        assertEquals(12, ReadingRules.BAND_MIN_PIECES)
        assertEquals(-1.5, ReadingRules.LOG_PROB_FLOOR, 0.0)
        // Conservative: under FLORES' lowest mean (-0.894) by far, so it empties only clear junk.
        assertTrue(ReadingRules.LOG_PROB_FLOOR < -0.894 * 1.5)
    }

    @Test
    fun `a line under 2 words or 6 letters and signs is a fragment`() {
        // Made up: "Oh." / "Okay okay." / "Yes, that's right." / "We will meet tomorrow."
        assertTrue(ReadingRules.isFragment("ഓ"))
        assertTrue(ReadingRules.isFragment("ഓ ശരി"))
        // Two words of 6 letters and signs in all (ശ ര ി twice): no fragment.
        assertFalse(ReadingRules.isFragment("ശരി ശരി"))
        assertTrue(ReadingRules.isFragment("ശരിയാണ്."))
        assertFalse(ReadingRules.isFragment("അതെ, ശരിയാണ്"))
        assertFalse(ReadingRules.isFragment("നമുക്ക് നാളെ കാണാം"))
        // A stretch without a letter is no word, so punctuation doesn't make a second one.
        assertTrue(ReadingRules.isFragment("ശരിയാണല്ലോ ."))
        assertTrue(ReadingRules.isFragment("   "))
        assertTrue(ReadingRules.isFragment(""))
        // A no-break space parts words too.
        assertFalse(ReadingRules.isFragment("അതെ\u00A0ശരിയാണ്"))
    }

    @Test
    fun `a reading the model is too unsure of empties under the floor, and one at it stands`() {
        assertNull(ReadingRules.emptied(reading("We will meet tomorrow morning.", mean = -1.5)))
        assertEquals(ReadingRules.Empty.FLOOR, ReadingRules.emptied(reading("We will meet tomorrow morning.", mean = -1.51)))
        // The model allowed nothing but the end, or gave no mean at all.
        assertEquals(ReadingRules.Empty.FLOOR, ReadingRules.emptied(reading("We will meet.", mean = Double.NEGATIVE_INFINITY)))
        assertEquals(ReadingRules.Empty.FLOOR, ReadingRules.emptied(reading("We will meet.", mean = Double.NaN)))
        assertEquals(ReadingRules.Empty.FLOOR, ReadingRules.emptied(reading("We will meet.", mean = null)))
    }

    @Test
    fun `a reading outside V2's band of tokens a piece empties, from 12 pieces on`() {
        val line = "We will bring the papers to the office tomorrow."
        // 20 pieces: 10 to 21 tokens are in the band (0.500 x 20 = 10, 1.079 x 20 = 21.58).
        assertNull(ReadingRules.emptied(reading(line, pieces = 20, tokens = 10)))
        assertNull(ReadingRules.emptied(reading(line, pieces = 20, tokens = 21)))
        assertEquals(ReadingRules.Empty.BAND, ReadingRules.emptied(reading(line, pieces = 20, tokens = 9)))
        assertEquals(ReadingRules.Empty.BAND, ReadingRules.emptied(reading(line, pieces = 20, tokens = 22)))
        // At 12 pieces the band holds: 6 to 12 tokens.
        assertEquals(ReadingRules.Empty.BAND, ReadingRules.emptied(reading(line, pieces = 12, tokens = 13)))
        assertNull(ReadingRules.emptied(reading(line, pieces = 12, tokens = 12)))
    }

    @Test
    fun `a short source is outside the band only when its reading hit the cap`() {
        // 4 pieces into 8 tokens ("Okay, I'll call you."): a short real reading, 2 a piece.
        assertNull(ReadingRules.emptied(reading("Okay, I'll call you.", pieces = 4, tokens = 8)))
        assertNull(ReadingRules.emptied(reading("Okay.", pieces = 11, tokens = 2)))
        // D15's cap reached without its own end: a runaway, at any length.
        assertEquals(ReadingRules.Empty.BAND, ReadingRules.emptied(reading("Okay, I will call you again later.", pieces = 4, tokens = 17, capped = true)))
        assertEquals(ReadingRules.Empty.BAND, ReadingRules.emptied(reading("We will meet tomorrow morning.", pieces = 40, tokens = 30, capped = true)))
    }

    @Test
    fun `a reading that repeats a run of 3 words 3 times empties, overlapping runs counted`() {
        assertEquals(ReadingRules.Empty.REPEATS, ReadingRules.emptied(reading("I will go, I will go, I will go to the market.")))
        // One word over and over: "yes yes yes" three times in five.
        assertEquals(ReadingRules.Empty.REPEATS, ReadingRules.emptied(reading("Yes, yes, yes, yes, yes.")))
        // Two words over and over.
        assertEquals(ReadingRules.Empty.REPEATS, ReadingRules.emptied(reading("the plan the plan the plan the plan")))
        // Case and punctuation don't hide a loop.
        assertEquals(ReadingRules.Empty.REPEATS, ReadingRules.emptied(reading("Call the bank. call the Bank! CALL THE BANK?")))
        // Twice is no loop, nor a word said three times apart.
        assertNull(ReadingRules.emptied(reading("I don't know, I don't know what we will do.")))
        assertNull(ReadingRules.emptied(reading("The bank, the shop and the post office, then home.")))
    }

    @Test
    fun `a reading with no words is blank, before any other rule`() {
        assertEquals(ReadingRules.Empty.BLANK, ReadingRules.emptied(reading("", mean = Double.NEGATIVE_INFINITY)))
        assertEquals(ReadingRules.Empty.BLANK, ReadingRules.emptied(reading(" . , ' ", pieces = 4, tokens = 2)))
        assertNull(ReadingRules.emptied(reading("12 o'clock.", pieces = 4, tokens = 4)))
    }

    @Test
    fun `the words of a reading are its letters and digits, apostrophes kept, lower case`() {
        assertEquals(listOf("i'll", "call", "anna", "at", "12"), ReadingRules.wordsOf("I'll call Anna at 12!"))
        assertEquals(listOf("don’t", "go"), ReadingRules.wordsOf("Don’t... go"))
        assertEquals(emptyList<String>(), ReadingRules.wordsOf("' . -"))
    }
}
