package com.satori.domain.reading

/**
 * Text as IndicTrans2's Python reference sees it. Its preprocessing is a chain of regular
 * expressions run by Python's `regex` module and `str` methods; Satori's port spells each one out
 * in code (Android's ICU regular expressions are not Python's, and a pattern ICU rejects ends the
 * process), and these are the character classes and the white space those expressions rely on.
 *
 * Python counts code points where Kotlin counts UTF-16 units, so the scanners step with
 * [Character.codePointAt] and compare lengths with [codePoints].
 *
 * `\w` and `\d` come from the platform's Unicode tables. On every code point JDK 17 assigns
 * (Unicode 13) they are exactly regex 2026.9.29's, checked over the whole code space against the
 * reference's own tables (`PythonTextTest`). Characters assigned since can differ; none of them is
 * Malayalam, Latin, a digit Satori's speech model writes or common punctuation.
 */
internal object PythonText {

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    /**
     * `\w`: an alphabetic character, a mark, a decimal digit, a connector such as `_`, or ZWNJ or
     * ZWJ. False for -1, the "no character" of [at] and [before].
     */
    fun isWord(cp: Int): Boolean {
        if (cp < 0) return false
        if (cp == ZWNJ || cp == ZWJ || Character.isAlphabetic(cp)) return true
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    /** `\d`: any decimal digit, Malayalam's and Devanagari's as much as ASCII's. False for -1. */
    fun isDigit(cp: Int): Boolean = cp >= 0 && Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()

    /** `\s`: Unicode's White_Space, which the `regex` module matches (no-break space included). */
    fun isRegexSpace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
            cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

    /**
     * What Python's `str.strip()` and `str.split()` take for white space (`str.isspace`): `\s` and
     * also the four separators U+001C to U+001F, which `\s` leaves alone.
     */
    fun isPythonSpace(cp: Int): Boolean = isRegexSpace(cp) || cp in 0x1C..0x1F

    /** Python's `text.strip()`. Every white space character is in the BMP, so this can look at chars. */
    fun strip(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isPythonSpace(text[start].code)) start++
        while (end > start && isPythonSpace(text[end - 1].code)) end--
        return text.substring(start, end)
    }

    /** Python's `text.split()`: the pieces between runs of white space, none of them empty. */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && isPythonSpace(text[i].code)) i++
            val start = i
            while (i < text.length && !isPythonSpace(text[i].code)) i++
            if (i > start) out += text.substring(start, i)
        }
        return out
    }

    /** `regex.sub(r"\s+", " ", text)`: each run of white space becomes one space. */
    fun collapseSpace(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (isRegexSpace(text[i].code)) {
                out.append(' ')
                while (i < text.length && isRegexSpace(text[i].code)) i++
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** Python's `len()`: code points, so a letter outside the BMP counts once. */
    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /** The code point that ends at [index], or -1 at the start. */
    fun before(text: String, index: Int): Int = if (index > 0) text.codePointBefore(index) else -1

    /** The code point that starts at [index], or -1 at the end. */
    fun at(text: String, index: Int): Int = if (index < text.length) text.codePointAt(index) else -1

    /** The index after the code point at [index]. */
    fun next(text: String, index: Int): Int = index + Character.charCount(text.codePointAt(index))
}
