package com.satori.domain.time

internal enum class TokenKind { WORD, NUMBER, PUNCT }

/**
 * A word, a run of digits or one other character, with its place in the text. Spaces are not
 * tokens; whether two tokens touch ("15:30", "3pm", "16.") is read from their offsets.
 */
internal class Token(val kind: TokenKind, val text: String, val start: Int, val end: Int) {
    /** [TimeLexicon.fold]ed word; the text itself for numbers and punctuation. */
    val folded: String = if (kind == TokenKind.WORD) TimeLexicon.fold(text) else text

    /** The value of a run of digits. */
    val number: Int? = if (kind == TokenKind.NUMBER && text.length <= 9) text.toInt() else null

    val capitalized: Boolean get() = text.first().isUpperCase()

    override fun toString() = text
}

/**
 * Splits text into words (letters, with apostrophes and hyphens inside: "o'clock",
 * "twenty-one", "Freitag-Abend"), runs of ASCII digits and single other characters. A line
 * break is kept as a punctuation token because it ends a sentence.
 */
internal fun tokenize(text: String): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\n' -> {
                tokens += Token(TokenKind.PUNCT, "\n", i, i + 1)
                i++
            }
            c.isWhitespace() -> i++
            c in '0'..'9' -> {
                var j = i + 1
                while (j < text.length && text[j] in '0'..'9') j++
                tokens += Token(TokenKind.NUMBER, text.substring(i, j), i, j)
                i = j
            }
            c.isLetter() -> {
                var j = i + 1
                while (j < text.length) {
                    val d = text[j]
                    j = when {
                        d.isLetter() -> j + 1
                        d in "'’-" && j + 1 < text.length && text[j + 1].isLetter() -> j + 2
                        else -> break
                    }
                }
                tokens += Token(TokenKind.WORD, text.substring(i, j), i, j)
                i = j
            }
            else -> {
                tokens += Token(TokenKind.PUNCT, c.toString(), i, i + 1)
                i++
            }
        }
    }
    return tokens
}
