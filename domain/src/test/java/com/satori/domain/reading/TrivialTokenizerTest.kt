package com.satori.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Test

/** IndicNLP 0.92's trivial tokenizer, each case with the Python reference's tokens (golden.py's venv). */
class TrivialTokenizerTest {

    @Test
    fun `punctuation is split off, as the reference splits it`() {
        val cases = listOf(
            "\u0D07\u0D24\u0D4D, \u0D05\u0D24\u0D4D." to listOf("\u0D07\u0D24\u0D4D", ",", "\u0D05\u0D24\u0D4D", "."),
            "\u0D07\u0D24\u0D4D\u0964" to listOf("\u0D07\u0D24\u0D4D", "\u0964"),
            "\t\u0D07\u0D24\u0D4D\t" to listOf("\u0D07\u0D24\u0D4D"),
            "a  b" to listOf("a", "b"),
            "" to listOf("")
        )
        for ((input, expected) in cases) assertEquals(input, expected, TrivialTokenizer.tokenize(input))
    }

    @Test
    fun `a backslash is no punctuation, as the reference's class escapes it away`() {
        assertEquals(listOf("a\\b"), TrivialTokenizer.tokenize("a\\b"))
    }

    @Test
    fun `a figure is joined back up, except at the start of the line`() {
        assertEquals(listOf("x", "12.5", "kg"), TrivialTokenizer.tokenize("x 12.5 kg"))
        assertEquals(listOf("12", ".", "5", "kg"), TrivialTokenizer.tokenize("12.5 kg"))
        assertEquals(listOf("1", ",", "2", ",", "3", "x", "1,2,3"), TrivialTokenizer.tokenize("1,2,3 x 1,2,3"))
        assertEquals(listOf("10", ":", "30", "(", "x", ")"), TrivialTokenizer.tokenize("10:30 (x)"))
    }
}
