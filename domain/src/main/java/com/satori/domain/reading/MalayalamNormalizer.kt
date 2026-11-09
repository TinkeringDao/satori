package com.satori.domain.reading

/**
 * IndicNLP's `MalayalamNormalizer` as IndicProcessor builds it (`IndicNormalizerFactory()
 * .get_normalizer("ml")`, every option at its default), from indic-nlp-library 0.92, the
 * original library and the one IndicTrans2 was trained with: the rules verbatim, in their order.
 *
 * Not the rewrite indic-nlp-library-itt 0.1.1 that IndicTransToolkit's wheel pulls in. Its visarga
 * rule escapes the backslashes of its pattern and of its replacement a second time, so the pattern
 * no longer names the Malayalam block: it turns a Latin capital or a digit before a colon into a
 * backslash, a 1 and a visarga ("A: B" becomes "\1ः B") and leaves a Malayalam letter's colon
 * alone, which no model was trained on.
 */
object MalayalamNormalizer {

    /** Chillus as two code points and a joiner (before Unicode 5.1), and the atomic chillu each becomes. */
    private val OLD_CHILLUS = listOf(
        "\u0D23\u0D4D\u200D" to "\u0D7A",
        "\u0D28\u0D4D\u200D" to "\u0D7B",
        "\u0D30\u0D4D\u200D" to "\u0D7C",
        "\u0D32\u0D4D\u200D" to "\u0D7D",
        "\u0D33\u0D4D\u200D" to "\u0D7E",
        "\u0D15\u0D4D\u200D" to "\u0D7F"
    )

    /**
     * `BaseNormalizer.normalize` and its `_normalize_punctuations`: byte order marks, the word
     * joiner, soft hyphens and the joiners go, no-break and zero-width spaces become spaces, and
     * Moses' punctuation rules follow. The second byte order mark and the `´´` rule do nothing by
     * the time they run; they are kept as the reference has them.
     */
    private val BASE = listOf(
        "\uFEFF" to "",
        "\uFFFE" to "",
        "\u2060" to "",
        "\u00AD" to "",
        "\u200B" to " ",
        "\u00A0" to " ",
        "\u200C" to "",
        "\u200D" to "",
        "\uFEFF" to "",
        "\u201E" to "\"",
        "\u201C" to "\"",
        "\u201D" to "\"",
        "\u2013" to "-",
        "\u2014" to " - ",
        "\u00B4" to "'",
        "\u2018" to "'",
        "\u201A" to "'",
        "\u2019" to "'",
        "''" to "\"",
        "\u00B4\u00B4" to "\"",
        "\u2026" to "..."
    )

    /** Malayalam's own: the generic dandas, two-part vowel signs and the au length mark. */
    private val MALAYALAM = listOf(
        "\u0D64" to "\u0964",
        "\u0D65" to "\u0965",
        "\u0D46\u0D3E" to "\u0D4A",
        "\u0D47\u0D3E" to "\u0D4B",
        "\u0D46\u0D57" to "\u0D4C",
        "\u0D57" to "\u0D4C"
    )

    private const val VISARGA = '\u0D03'

    fun normalize(text: String): String {
        var out = text
        for ((from, to) in OLD_CHILLUS) out = out.replace(from, to)
        for ((from, to) in BASE) out = out.replace(from, to)
        for ((from, to) in MALAYALAM) out = out.replace(from, to)
        return correctVisarga(out)
    }

    /**
     * `re.sub(r"([\u0d00-\u0d7f]):", "\\1\u0d03", text)`: a colon right after any character of the
     * Malayalam block becomes a visarga, as in "ദുഃഖം".
     */
    private fun correctVisarga(text: String): String {
        if (text.indexOf(':') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in '\u0D00'..'\u0D7F' && i + 1 < text.length && text[i + 1] == ':') {
                out.append(c).append(VISARGA)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
