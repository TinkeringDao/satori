package com.satori.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Test

/** IndicNLP's Malayalam to Devanagari offsets and IndicProcessor's virama fix, with the reference's results. */
class IndicTransliteratorTest {

    @Test
    fun `each case gives what the reference gives`() {
        val cases = listOf(
            // Letters and signs move to the same offset in Devanagari.
            "\u0D15\u0D47\u0D30\u0D33\u0D02" to "\u0915\u0947\u0930\u0933\u0902",
            // Atomic chillus have no Devanagari place and stay.
            "\u0D05\u0D35\u0D7B \u0D05\u0D35\u0D7E" to "\u0905\u0935\u0D7B \u0905\u0935\u0D7E",
            // Malayalam digits move too.
            "\u0D67\u0D68\u0D69" to "\u0967\u0968\u0969",
            // A virama left between spaces joins its letters.
            "\u0D15 \u0D4D \u0D37" to "\u0915\u094D\u0937",
            // Other scripts stay.
            "abc \u09A0 \u0950" to "abc \u09A0 \u0950",
            // Offsets above 0x6F stay Malayalam.
            "\u0D7F\u0D70\u0D79" to "\u0D7F\u0D70\u0D79"
        )
        for ((input, expected) in cases) assertEquals(input, expected, IndicTransliterator.toDevanagari(input))
    }

    @Test
    fun `a letter outside the BMP passes as it is`() {
        assertEquals("\uD83D\uDE00 \u0915", IndicTransliterator.toDevanagari("\uD83D\uDE00 \u0D15"))
    }
}
