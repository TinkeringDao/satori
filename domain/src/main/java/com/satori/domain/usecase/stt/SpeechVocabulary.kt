package com.satori.domain.usecase.stt

/**
 * The owner's words to recognize: names and terms the speech model should listen for (hotwords).
 * Parakeet v3 is given them with beam search, which favours them where the audio allows; it only
 * spells them as written, so they are kept as the owner wrote them, cased.
 *
 * sherpa-onnx reads them one per line and splits a line at spaces. A word that starts with ':'
 * is read as a score: one that is no number ends the app, and one with words after it has the
 * line written to the log. So only letters, digits and the marks names use are kept: nothing the
 * owner wrote then reaches the log, and nothing can be misread. Every piece of what is kept is
 * one of Parakeet v3's: a letter its vocabulary lacks (Turkish ı, say) becomes its unknown piece,
 * so a word with one is kept but never favoured.
 */
object SpeechVocabulary {

    /** At most this many words or phrases are kept; a longer list mostly costs decoding time. */
    const val MAX_WORDS = 100

    /** A longer line is no name. */
    const val MAX_CHARS = 40

    private val SEPARATORS = Regex("[\\n\\r,;]")
    private val SPACES = Regex("\\s+")
    private val KEPT_MARKS = setOf('\'', '-', '.')

    /**
     * The words in [text]: one per line (a comma or a semicolon also separates them), each with
     * any other character turned into a space, spaces collapsed and the ends trimmed. Empty ones,
     * ones without a letter or digit, ones longer than [MAX_CHARS] and repeats are dropped; at most
     * [MAX_WORDS] are kept, in the order written.
     */
    fun clean(text: String): List<String> =
        text.split(SEPARATORS)
            .asSequence()
            .map(::cleanOne)
            .filter { word -> word.isNotEmpty() && word.length <= MAX_CHARS && word.any { it.isLetterOrDigit() } }
            .distinct()
            .take(MAX_WORDS)
            .toList()

    /** [words] as the text field shows them: one per line. */
    fun asText(words: List<String>): String = words.joinToString("\n")

    private fun cleanOne(raw: String): String {
        val kept = raw.map { char ->
            when {
                char.isLetterOrDigit() -> char
                char == '’' -> '\''
                char in KEPT_MARKS -> char
                else -> ' '
            }
        }.joinToString("")
        return kept.replace(SPACES, " ").trim()
    }
}
