package com.satori.domain.reading

/**
 * IndicNLP's `trivial_tokenize` for a Brahmi script (indic-nlp-library 0.92): a space on each side
 * of every punctuation mark, runs of spaces made one, then figures such as "12 . 5" or
 * "1 , 2 , 3" joined back up ("12.5", "1,2,3").
 *
 * Two of the reference's quirks are kept, as IndicTrans2 was trained on text they shaped:
 * - Its punctuation class is `string.punctuation` pasted into a regular expression, where the
 *   backslash only escapes the `]` after it, so a backslash is no punctuation here.
 * - The loop that joins figures skips one that starts the text (it only joins a match that starts
 *   after the last one ended, and the first "last end" is 0), so "12 . 5 kg" stays apart at the
 *   start of a line and comes back together anywhere else.
 */
object TrivialTokenizer {

    private const val PUNCTUATION =
        "!\"#\$%&'()*+,-./:;<=>?@[]^_`{|}~\u0964\u0965\uAAF1\uAAF0\uABEB\uABEC\uABED\uABEE\uABEF\u1C7E\u1C7F"

    /** The tokens of [text], separated as the reference separates them (`text.split(" ")` included). */
    fun tokenize(text: String): List<String> {
        val padded = StringBuilder(text.length * 2)
        for (c in text) {
            val ch = if (c == '\t') ' ' else c
            if (PUNCTUATION.indexOf(ch) >= 0) padded.append(' ').append(ch).append(' ') else padded.append(ch)
        }
        return joinFigures(stripSpaces(collapseSpaces(padded))).split(' ')
    }

    /** `re.sub(r"[ ]+", " ", s)`: only the ASCII space counts here. */
    private fun collapseSpaces(text: CharSequence): String {
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            if (text[i] != ' ' || i == 0 || text[i - 1] != ' ') out.append(text[i])
        }
        return out.toString()
    }

    /** `s.strip(" ")`. */
    private fun stripSpaces(text: String): String = text.trim(' ')

    /** The loop over `([0-9]+ [,.:/] )+[0-9]+`, as the reference writes it. */
    private fun joinFigures(s: String): String {
        val out = StringBuilder(s.length)
        var prev = 0
        var i = 0
        while (i < s.length) {
            val end = figureAt(s, i)
            if (end < 0) {
                i++
                continue
            }
            if (i > prev) {
                out.append(s, prev, i)
                for (k in i until end) if (s[k] != ' ') out.append(s[k])
                prev = end
            }
            i = end
        }
        out.append(s, prev, s.length)
        return out.toString()
    }

    /**
     * The end of a figure that starts at [start]: ASCII digits, then at least once a space, one of
     * `,.:/`, a space and more digits. The digit runs are whole (a space ends each), and the match
     * ends after the last group that has digits after it, as the backtracking regex's does.
     */
    private fun figureAt(s: String, start: Int): Int {
        if (!isAsciiDigit(s, start)) return -1
        var end = digitsEnd(s, start)
        var last = -1
        while (end + 3 < s.length && s[end] == ' ' && s[end + 1] in ",.:/" && s[end + 2] == ' ' &&
            isAsciiDigit(s, end + 3)
        ) {
            end = digitsEnd(s, end + 3)
            last = end
        }
        return last
    }

    private fun isAsciiDigit(s: String, i: Int) = i < s.length && s[i] in '0'..'9'

    private fun digitsEnd(s: String, from: Int): Int {
        var i = from
        while (isAsciiDigit(s, i)) i++
        return i
    }
}
