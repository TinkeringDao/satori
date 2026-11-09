package com.satori.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Test

/** IndicNLP 0.92's Malayalam rules, each with what the Python reference gives (golden.py's venv). */
class MalayalamNormalizerTest {

    @Test
    fun `each rule gives what the reference gives`() {
        val cases = listOf(
            // An old chillu (consonant, virama, ZWJ) becomes the atomic one.
            "\u0D05\u0D35\u0D28\u0D4D\u200D \u0D35\u0D28\u0D4D\u0D28\u0D41" to "\u0D05\u0D35\u0D7B \u0D35\u0D28\u0D4D\u0D28\u0D41",
            // Any other ZWNJ or ZWJ goes.
            "\u0D15\u0D4D\u200C\u0D37" to "\u0D15\u0D4D\u0D37",
            // No-break and zero-width spaces become spaces.
            "\u0D12\u0D30\u0D41\u00A0\u0D35\u0D3E\u0D15\u0D4D\u0D15\u0D4D\u200B\u0D07\u0D24\u0D4D" to "\u0D12\u0D30\u0D41 \u0D35\u0D3E\u0D15\u0D4D\u0D15\u0D4D \u0D07\u0D24\u0D4D",
            // Byte order mark, soft hyphen and word joiner go.
            "\uFEFF\u0D24\u0D41\u0D1F\u0D15\u0D4D\u0D15\u0D02 \u0D2A\u0D30\u0D40\u00AD\u0D15\u0D4D\u0D37\u0D23\u0D02 \u0D35\u0D3E\u2060\u0D15\u0D4D\u0D15\u0D4D" to "\u0D24\u0D41\u0D1F\u0D15\u0D4D\u0D15\u0D02 \u0D2A\u0D30\u0D40\u0D15\u0D4D\u0D37\u0D23\u0D02 \u0D35\u0D3E\u0D15\u0D4D\u0D15\u0D4D",
            // Curly quotes become straight ones, two apostrophes a double quote.
            "\u201C\u0D07\u0D24\u0D4D\u201D \u2018\u0D05\u0D24\u0D4D\u2019 ''\u0D07\u0D30\u0D1F\u0D4D\u0D1F''" to "\"\u0D07\u0D24\u0D4D\" '\u0D05\u0D24\u0D4D' \"\u0D07\u0D30\u0D1F\u0D4D\u0D1F\"",
            // An en dash becomes a hyphen, an em dash a hyphen between spaces.
            "\u0D12\u0D28\u0D4D\u0D28\u0D4D \u2013 \u0D30\u0D23\u0D4D\u0D1F\u0D4D \u2014 \u0D2E\u0D42\u0D28\u0D4D\u0D28\u0D4D" to "\u0D12\u0D28\u0D4D\u0D28\u0D4D - \u0D30\u0D23\u0D4D\u0D1F\u0D4D  -  \u0D2E\u0D42\u0D28\u0D4D\u0D28\u0D4D",
            "\u0D36\u0D30\u0D3F\u2026" to "\u0D36\u0D30\u0D3F...",
            // Two-part vowel signs and the au length mark become the one-part signs.
            "\u0D15\u0D46\u0D3E \u0D15\u0D47\u0D3E \u0D15\u0D46\u0D57 \u0D15\u0D57" to "\u0D15\u0D4A \u0D15\u0D4B \u0D15\u0D4C \u0D15\u0D4C",
            // Malayalam's reserved danda becomes the generic one.
            "\u0D07\u0D24\u0D4D\u0D64" to "\u0D07\u0D24\u0D4D\u0964",
            // A colon after a Malayalam character (a chillu too) becomes a visarga; after a Latin letter it stays.
            "\u0D15: A: \u0D15:: \u0D7D:" to "\u0D15\u0D03 A: \u0D15\u0D03: \u0D7D\u0D03"
        )
        for ((input, expected) in cases) assertEquals(input, expected, MalayalamNormalizer.normalize(input))
    }

    @Test
    fun `a line without any of the characters stays as it is`() {
        val line = "\u0D28\u0D3E\u0D33\u0D46 office-\u0D7D meeting"

        assertEquals(line, MalayalamNormalizer.normalize(line))
    }
}
