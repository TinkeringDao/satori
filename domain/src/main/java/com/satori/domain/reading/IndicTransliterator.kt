package com.satori.domain.reading

/**
 * IndicNLP's `UnicodeIndicTransliterator.transliterate(text, "ml", "hi")` and the fix
 * IndicProcessor applies right after it. IndicTrans2 reads every Indic language in Devanagari
 * letters (its source vocabulary has 75,518 Devanagari pieces and 87 Malayalam ones), so each
 * Malayalam character moves to the same place in the Devanagari block.
 *
 * Only offsets 0x00 to 0x6F move: letters, signs and digits. The atomic chillus (U+0D7A to
 * U+0D7F), the fractions and the other marks above 0x6F have no Devanagari place and stay
 * Malayalam, as the vocabulary expects (its Malayalam pieces are mostly those chillus).
 */
object IndicTransliterator {

    private const val MALAYALAM = 0x0D00
    private const val DEVANAGARI = 0x0900
    private const val LAST_OFFSET = 0x6F

    fun toDevanagari(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            val offset = c.code - MALAYALAM
            out.append(if (offset in 0..LAST_OFFSET) (DEVANAGARI + offset).toChar() else c)
        }
        // IndicProcessor: a virama the tokenizer left between spaces joins its letters again.
        return out.toString().replace(" \u094D ", "\u094D")
    }
}
