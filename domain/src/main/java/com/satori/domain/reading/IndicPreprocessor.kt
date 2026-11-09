package com.satori.domain.reading

import com.satori.domain.reading.GreedySequence.Companion.among
import com.satori.domain.reading.GreedySequence.Companion.any
import com.satori.domain.reading.GreedySequence.Companion.atLeast
import com.satori.domain.reading.GreedySequence.Companion.char
import com.satori.domain.reading.GreedySequence.Companion.one
import com.satori.domain.reading.GreedySequence.Companion.optional
import com.satori.domain.reading.GreedySequence.Companion.some

/**
 * A Malayalam line, made ready for IndicTrans2: what IndicTransToolkit 1.1.1's
 * `IndicProcessor(inference=True).preprocess_batch([line], src_lang="mal_Mlym", tgt_lang="eng_Latn")`
 * gives, over indic-nlp-library 0.92, string for string. The model was trained on text prepared
 * this way, and reads anything else as noise.
 *
 * The steps, in the reference's order:
 * 1. [punctuation]: its punctuation rules (`_punc_norm`);
 * 2. [digits]: digits of other Indic scripts become ASCII (Malayalam's stay, for step 6);
 * 3. [placeholders]: e-mail and web addresses, figures and tags become `<ID1>`, `<ID2>`, ...;
 * 4. [MalayalamNormalizer]; 5. [TrivialTokenizer]; 6. [IndicTransliterator] into Devanagari.
 *
 * One choice where the reference has none: IndicProcessor numbers the placeholders of one
 * pattern in the order of a Python set, which changes with the process's hash seed, and each
 * replaces its text wherever it occurs, also inside a longer match not yet replaced. This port
 * numbers them longest first, and matches of one length in the order they first occur: one of
 * the reference's outputs (the golden data names a seed that gives it), and the one that keeps
 * every match whole. "10:30 ... 10:30:45" becomes `<ID2> ... <ID1>`, where the order they first
 * occur would give `<ID1> ... <ID1>:45` and the reading would lose the seconds.
 */
object IndicPreprocessor {

    /** The language tags that start every source sequence: Malayalam in, English out. */
    const val SOURCE_TAG = "mal_Mlym"
    const val TARGET_TAG = "eng_Latn"

    /** [line], one segment as the speech model wrote it, prepared as IndicProcessor prepares it. */
    fun preprocess(line: String): Preprocessed {
        val (wrapped, placeholders) = placeholders(digits(punctuation(line)))
        val normalized = MalayalamNormalizer.normalize(PythonText.strip(wrapped))
        val tokens = TrivialTokenizer.tokenize(normalized).joinToString(" ")
        return Preprocessed(PythonText.strip(IndicTransliterator.toDevanagari(tokens)), placeholders)
    }

    /**
     * `_punc_norm`: its replacements in their order, then its five rules, then `strip()`. Several
     * of its patterns hold a no-break space, written `\u00A0` here. Its `...` to `...` changes
     * nothing and is left out.
     */
    internal fun punctuation(text: String): String {
        var t = text.replace("\r", "")
        t = noSpaceAfter(t, '(')
        t = noSpaceBefore(t, ')')
        t = joinedSpaced(t, ':')
        t = joinedSpaced(t, ';')
        t = mapped(t, "`\u00B4\u2018\u201A\u2019", '\'')
        t = mapped(t, "\u201E\u201C\u201D\u00AB\u00BB", '"')
        t = mapped(t, "\u2013\u2014", '-')
        t = t.replace("\u00A0%", "%")
        t = t.replace("n\u00BA\u00A0", "n\u00BA ")
        t = t.replace("\u00A0\u00BAC", " \u00BAC")
        t = noNbspBeforeMark(t)
        t = t.replace(",\u00A0", ", ")
        t = oneSpace(t)
        t = bracketThenMark(t)
        t = digitThenPercent(t)
        t = quoteAfterMarks(t)
        t = digitsAcrossNbsp(t)
        return PythonText.strip(t)
    }

    /** `text.translate(...)`: the digits of the reference's table become ASCII; any other stays. */
    internal fun digits(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) out.append(asciiDigit(c))
        return out.toString()
    }

    /**
     * `_wrap_with_placeholders`: for each pattern in turn, each distinct match (but a short web
     * address or figure), longest first, becomes the next `<IDn>` wherever it occurs; then white
     * space runs become one space and a `/` after a placeholder goes.
     */
    internal fun placeholders(text: String): Pair<String, Placeholders> {
        var t = text
        val matches = ArrayList<String>()
        for (pattern in PlaceholderPattern.entries) {
            val kept = pattern.findAll(t).distinct().filter(pattern::keeps)
            for (match in kept.sortedByDescending(PythonText::codePoints)) {
                matches += match
                t = t.replace(match, "<ID${matches.size}>")
            }
        }
        val out = PythonText.collapseSpace(t).replace(">/", ">").replace("]/", "]")
        return out to Placeholders(matches)
    }

    // ---- _punc_norm's rules, one function each, each a left-to-right regex substitution ----

    /** `\(\s*` to `(`. */
    private fun noSpaceAfter(text: String, mark: Char): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            out.append(text[i])
            if (text[i] == mark) {
                i++
                while (i < text.length && PythonText.isRegexSpace(text[i].code)) i++
            } else {
                i++
            }
        }
        return out.toString()
    }

    /** `\s*\)` to `)`: the white space right before each mark goes. */
    private fun noSpaceBefore(text: String, mark: Char): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (c == mark) {
                while (out.isNotEmpty() && PythonText.isRegexSpace(out[out.length - 1].code)) out.setLength(out.length - 1)
            }
            out.append(c)
        }
        return out.toString()
    }

    /** `\s:\s?` to `:` (and the same for `;`): one white space before the mark, and one after if there is. */
    private fun joinedSpaced(text: String, mark: Char): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (PythonText.isRegexSpace(text[i].code) && i + 1 < text.length && text[i + 1] == mark) {
                out.append(mark)
                i += 2
                if (i < text.length && PythonText.isRegexSpace(text[i].code)) i++
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** A character class to one character. */
    private fun mapped(text: String, from: String, to: Char): String {
        val out = StringBuilder(text.length)
        for (c in text) out.append(if (from.indexOf(c) >= 0) to else c)
        return out.toString()
    }

    /** `\u00A0[?!;]` to the mark alone (the reference strips the match). */
    private fun noNbspBeforeMark(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == NBSP && i + 1 < text.length && text[i + 1] in "?!;") {
                out.append(text[i + 1])
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** `[ ]{2,}` to one space. */
    private fun oneSpace(text: String): String {
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            if (text[i] != ' ' || i == 0 || text[i - 1] != ' ') out.append(text[i])
        }
        return out.toString()
    }

    /** `\) ([\.!:?;,])` to `)` and the mark. */
    private fun bracketThenMark(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == ')' && i + 2 < text.length && text[i + 1] == ' ' && text[i + 2] in ".!:?;,") {
                out.append(')').append(text[i + 2])
                i += 3
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** `(\d) %` to the digit and `%`. */
    private fun digitThenPercent(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            out.appendCodePoint(cp)
            if (PythonText.isDigit(cp) && next + 1 < text.length && text[next] == ' ' && text[next + 1] == '%') {
                out.append('%')
                i = next + 2
            } else {
                i = next
            }
        }
        return out.toString()
    }

    /** `\"([,\.]+)` to the marks and then the quote. */
    private fun quoteAfterMarks(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '"' && i + 1 < text.length && text[i + 1] in ",.") {
                var end = i + 1
                while (end < text.length && text[end] in ",.") end++
                out.append(text, i + 1, end).append('"')
                i = end
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** `(\d)\u00A0(\d)` to the digits with a point between them. */
    private fun digitsAcrossNbsp(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            out.appendCodePoint(cp)
            val after = if (next + 1 < text.length && text[next] == NBSP) text.codePointAt(next + 1) else -1
            if (PythonText.isDigit(cp) && PythonText.isDigit(after)) {
                out.append('.').appendCodePoint(after)
                i = next + 1 + Character.charCount(after)
            } else {
                i = next
            }
        }
        return out.toString()
    }

    private const val NBSP = '\u00A0'

    /** The zero of each script whose ten digits the reference's table makes ASCII. */
    private val DIGIT_ZEROS = intArrayOf(0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0CE6, 0x06F0, 0x1C50, 0xABF0)

    /**
     * The reference's table: Devanagari, Bengali, Gurmukhi, Gujarati, Oriya, Kannada, Extended
     * Arabic-Indic, Ol Chiki and Meetei Mayek digits whole, and two scripts in part as it lists
     * them: of Arabic-Indic only the zero, of Telugu all but the zero.
     */
    private fun asciiDigit(c: Char): Char {
        val code = c.code
        for (zero in DIGIT_ZEROS) if (code in zero..zero + 9) return '0' + (code - zero)
        if (code == ARABIC_INDIC_ZERO) return '0'
        if (code in TELUGU_ZERO + 1..TELUGU_ZERO + 9) return '0' + (code - TELUGU_ZERO)
        return c
    }

    private const val ARABIC_INDIC_ZERO = 0x0660
    private const val TELUGU_ZERO = 0x0C66
}

/**
 * A line as IndicTrans2 reads it.
 *
 * @property text The prepared line, in Devanagari letters; SentencePiece cuts this into pieces
 * @property placeholders What the line's `<IDn>` stand for, to put back into the English
 */
data class Preprocessed(val text: String, val placeholders: Placeholders) {

    /** The line as IndicProcessor returns it: the two language tags, then [text]. */
    val tagged: String get() = "${IndicPreprocessor.SOURCE_TAG} ${IndicPreprocessor.TARGET_TAG} $text"
}

/** IndicProcessor's four placeholder patterns, in its order. */
internal enum class PlaceholderPattern {

    /** `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}`: the `|` in the last class is the reference's. */
    EMAIL {
        private val pattern = GreedySequence(
            listOf(
                some(among("$ASCII_ALNUM._%+-")),
                one(char('@')),
                some(among("$ASCII_ALNUM.-")),
                one(char('.')),
                atLeast(2, among("$ASCII_LETTERS|"))
            )
        )

        override fun findAll(text: String): List<String> = pattern.Matcher(text).let { m -> findAll(text, m::matchAt) }
    },

    /** `\b(?<![\w/.])(?:(?:https?|ftp)://)?(?:(?:[\w-]+\.)+(?!\.))(?:[\w/\-?#&=%.]+)+(?!\.\w+)\b` */
    URL {
        override fun findAll(text: String): List<String> = findAll(text) { urlAt(text, it) }

        /** Fewer than four characters without the dots is no address. */
        override fun keeps(match: String) = PythonText.codePoints(match.replace(".", "")) >= 4
    },

    /**
     * `(~?\d+\.?\d*\s?%?\s?-?\s?~?\d+\.?\d*\s?%|~?\d+%|\d+[-\/.,:']\d+[-\/.,:'+]\d+(?:\.\d+)?|\d+[-\/.:'+]\d+(?:\.\d+)?)`:
     * a range or share with a percent sign, a share, three numbers with marks between them, two.
     */
    NUMERAL {
        private val digit: (Int) -> Boolean = PythonText::isDigit
        private val space: (Int) -> Boolean = PythonText::isRegexSpace
        private val range = GreedySequence(
            listOf(
                optional(char('~')), some(digit), optional(char('.')), any(digit),
                optional(space), optional(char('%')), optional(space), optional(char('-')), optional(space),
                optional(char('~')), some(digit), optional(char('.')), any(digit), optional(space), one(char('%'))
            )
        )
        private val share = GreedySequence(listOf(optional(char('~')), some(digit), one(char('%'))))
        private val three = GreedySequence(
            listOf(some(digit), one(among("-/.,:'")), some(digit), one(among("-/.,:'+")), some(digit))
        )
        private val two = GreedySequence(listOf(some(digit), one(among("-/.:'+")), some(digit)))

        override fun findAll(text: String): List<String> {
            val range = range.Matcher(text)
            val share = share.Matcher(text)
            val three = three.Matcher(text)
            val two = two.Matcher(text)
            return findAll(text) { start ->
                range.matchAt(start).takeIf { it >= 0 }
                    ?: share.matchAt(start).takeIf { it >= 0 }
                    ?: three.matchAt(start).takeIf { it >= 0 }?.let { decimals(text, it) }
                    ?: two.matchAt(start).takeIf { it >= 0 }?.let { decimals(text, it) }
                    ?: -1
            }
        }

        /** Fewer than four characters without the spaces, points and colons is no figure to keep whole. */
        override fun keeps(match: String) =
            PythonText.codePoints(match.replace(" ", "").replace(".", "").replace(":", "")) >= 4
    },

    /** `[A-Za-z0-9]*[#|@]\w+`: a tag or a mention (the `|` is the reference's). */
    OTHER {
        private val pattern = GreedySequence(
            listOf(any(among(ASCII_ALNUM)), one(among("#|@")), some(PythonText::isWord))
        )

        override fun findAll(text: String): List<String> = pattern.Matcher(text).let { m -> findAll(text, m::matchAt) }
    };

    /** Python's `pattern.findall(text)`: the matches from left to right. */
    abstract fun findAll(text: String): List<String>

    /** Whether a match becomes a placeholder; the reference skips short web addresses and figures. */
    open fun keeps(match: String): Boolean = true
}

private const val ASCII_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private const val ASCII_ALNUM = ASCII_LETTERS + "0123456789"

/** `(?:\.\d+)?` after a figure that ends at [end]: a point and digits, if they follow. */
private fun decimals(text: String, end: Int): Int {
    if (end >= text.length || text[end] != '.' || !PythonText.isDigit(PythonText.at(text, end + 1))) return end
    var i = end + 1
    while (i < text.length && PythonText.isDigit(text.codePointAt(i))) i = PythonText.next(text, i)
    return i
}

/**
 * The URL pattern at [start]. A word begins there (`\b`), after nothing that is a word
 * character, a slash or a point. Then, the way the backtracking engine tries them: with the
 * scheme if there is one, then without; as many "label." as follow, then one fewer, and so
 * on, never right before another point; then the longest run of address characters whose
 * end is a word boundary not followed by a point and a word character.
 */
private fun urlAt(text: String, start: Int): Int {
    if (!PythonText.isWord(PythonText.at(text, start))) return -1
    val before = PythonText.before(text, start)
    if (PythonText.isWord(before) || before == '/'.code || before == '.'.code) return -1
    val scheme = SCHEMES.firstOrNull { text.startsWith(it, start) }
    if (scheme != null) {
        val end = hostAt(text, start + scheme.length)
        if (end >= 0) return end
    }
    return hostAt(text, start)
}

private val SCHEMES = listOf("https://", "http://", "ftp://")

/** `(?:(?:[\w-]+\.)+(?!\.))` and then the rest, from [from]. */
private fun hostAt(text: String, from: Int): Int {
    val labelEnds = ArrayList<Int>()
    var at = from
    while (true) {
        var end = at
        while (end < text.length) {
            val cp = text.codePointAt(end)
            if (!PythonText.isWord(cp) && cp != '-'.code) break
            end += Character.charCount(cp)
        }
        if (end == at || end >= text.length || text[end] != '.') break
        labelEnds += end + 1
        at = end + 1
    }
    for (index in labelEnds.indices.reversed()) {
        val end = labelEnds[index]
        if (end < text.length && text[end] == '.') continue
        val path = pathEnd(text, end)
        if (path >= 0) return path
    }
    return -1
}

/** `(?:[\w/\-?#&=%.]+)+(?!\.\w+)\b` from [from]: the run's longest end that both checks pass. */
private fun pathEnd(text: String, from: Int): Int {
    var run = from
    while (run < text.length) {
        val cp = text.codePointAt(run)
        if (!isAddressChar(cp)) break
        run += Character.charCount(cp)
    }
    var end = run
    while (end > from) {
        val pointThenWord = end < text.length && text[end] == '.' && PythonText.isWord(PythonText.at(text, end + 1))
        val boundary = PythonText.isWord(PythonText.before(text, end)) != PythonText.isWord(PythonText.at(text, end))
        if (!pointThenWord && boundary) return end
        end -= Character.charCount(text.codePointBefore(end))
    }
    return -1
}

/** `[\w/\-?#&=%.]`. */
private fun isAddressChar(cp: Int): Boolean =
    PythonText.isWord(cp) || cp < 0x80 && "/-?#&=%.".indexOf(cp.toChar()) >= 0
