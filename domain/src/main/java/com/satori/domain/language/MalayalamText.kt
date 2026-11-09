package com.satori.domain.language

/**
 * Puts right the Malayalam spellings a speech model writes that no Malayalam text has, before a
 * final line is stored. AI4Bharat's model puts out pieces of words one after the other, and where
 * it goes wrong it writes sequences that break the rules of the script: a vowel sign twice, the
 * candrakkala (the virama, U+0D4D) before a vowel sign, a sign or a chillu at the start of a word.
 * Fonts draw such a sign on a dotted circle, and the AI reads it as noise. The rules are those of
 * the Unicode Standard 17.0, section 12.9 (Malayalam); each change is made only where the text
 * breaks one of them, so a correct text comes out as it went in, and a text cleaned twice comes out
 * as it did the first time.
 *
 * 1. A two-part vowel sign in two parts is the one sign it is canonically equivalent to, as NFC
 *    writes it: e and aa make o (U+0D4A), ee and aa make oo (U+0D4B), e and the au length mark make
 *    au (U+0D4C).
 * 2. A vowel letter written as a letter and a sign (Table 12-34, "Do Not Use") is the one letter:
 *    i and the au length mark make ii, u and it make uu, e and the e sign make ai, o and aa make
 *    oo, o and the au length mark make au.
 * 3. A chillu written the way of Unicode 5.0, consonant, virama and zero width joiner
 *    (Table 12-42), is the atomic chillu, the preferred representation.
 * 4. nta written as na, virama and rra, a legacy representation that is to be treated as the
 *    recommended one, is written as that: chillu n, virama and rra ("Newly generated text should
 *    only use the recommended representation"). Not at the start of a word, where no nta stands.
 * 5. A vowel sign or a virama written twice in a row is written once. The anusvara is left as it is:
 *    Malayalam text repeats it ("ഈംംംം").
 * 6. The signs of a syllable come in one order: the vowel sign, then the virama, then the anusvara
 *    or the visarga ("the candrakkala sign ... follows any vowel sign that may be present and
 *    precedes any anusvara"), and a syllable has one vowel sign. A virama right before a vowel sign
 *    goes, as does a virama after an anusvara or a visarga, and a second vowel sign after a vowel
 *    sign, an anusvara or a visarga.
 * 7. A sign needs a letter to sit on (the standard also lets digits, the hyphen-minus, the no-break
 *    space and the dotted circle carry one), and a chillu never starts a word. One that starts a
 *    word after a word in Malayalam belongs to that word, whose end the model put in the next one:
 *    the space between them goes, if the sign then has its place there (a chillu after a letter or
 *    a vowel sign). Otherwise a sign is left out, as nothing can carry it, and a chillu stays: it is
 *    a letter that can be read, and leaving it out would lose a sound.
 *
 * The zero width joiner and non-joiner stay where they ask for a conjunct's form (Table 12-38), and
 * a sign after one of them sits on the letter before it. A text without Malayalam comes back as it
 * is. Pure Kotlin, no I/O; one pass over the text, quick enough for every line.
 */
object MalayalamText {

    /** [text] with its Malayalam written by the rules of the script; the same text when it holds none to put right. */
    fun clean(text: String): String {
        if (text.none { it in BLOCK }) return text
        val out = StringBuilder(text.length)
        for (c in text) put(out, c)
        return out.toString()
    }

    private fun put(out: StringBuilder, c: Char) {
        when {
            c == ZWJ -> putJoiner(out)
            c == RRA -> putRra(out)
            c in MARKS -> putMark(out, c)
            c in CHILLUS -> putChillu(out, c)
            else -> out.append(c)
        }
    }

    /**
     * A sign: on the letter [out] ends with, as the order of a syllable has it (rules 1, 2, 5 and 6);
     * at the start of a word, at the end of the word before it, or left out (rule 7).
     */
    private fun putMark(out: StringBuilder, mark: Char) {
        if (hasBase(out)) {
            attach(out, mark)
            return
        }
        val spaces = startOfSpaces(out)
        if (spaces == out.length || spaces == 0 || !isMalayalam(out[spaces - 1])) return
        // Joined only if the sign has its place at the end of that word; else it is left out, and
        // the space stays.
        val joined = StringBuilder(out).apply { setLength(spaces) }
        if (attach(joined, mark)) {
            out.setLength(0)
            out.append(joined)
        }
    }

    /**
     * Puts [mark] after the end of [out], which can carry it: in its place in the syllable, or
     * composed with the sign or letter before it. False when it has no place there and is left out.
     */
    private fun attach(out: StringBuilder, mark: Char): Boolean {
        if (mark !in VOWEL_SIGNS) {
            val last = out.last()
            if (mark == VIRAMA && (last == VIRAMA || last == ANUSVARA || last == VISARGA)) return false
            out.append(mark)
            return true
        }
        // A virama before a vowel sign is out of its place, which is after it.
        var end = out.length
        while (end > 0 && out[end - 1] == VIRAMA) end--
        if (end == 0) return false
        val last = out[end - 1]
        val whole = COMPOSED[pair(last, mark)]
        if (whole == null && (last == mark || last in VOWEL_SIGNS || last == ANUSVARA || last == VISARGA)) return false
        out.setLength(end)
        if (whole == null) {
            out.append(mark)
            return true
        }
        out.setLength(end - 1)
        if (whole in VOWEL_SIGNS) return attach(out, whole)
        out.append(whole)
        return true
    }

    /** A chillu, joined to the word before it when it would start a word after a Malayalam one (rule 7). */
    private fun putChillu(out: StringBuilder, chillu: Char) {
        val spaces = startOfSpaces(out)
        if (spaces < out.length && spaces > 0 && out[spaces - 1].let { it in CONSONANTS || it in VOWEL_LETTERS || it in VOWEL_SIGNS }) {
            out.setLength(spaces)
        }
        out.append(chillu)
    }

    /** A zero width joiner: after a consonant and a virama the legacy chillu it ends is the atomic one (rule 3). */
    private fun putJoiner(out: StringBuilder) {
        val n = out.length
        val chillu = if (n >= 2 && out[n - 1] == VIRAMA) LEGACY_CHILLUS[out[n - 2]] else null
        if (chillu == null) {
            out.append(ZWJ)
            return
        }
        out.setLength(n - 2)
        putChillu(out, chillu)
    }

    /** rra: after na and a virama within a word it ends nta, whose na is the chillu (rule 4). */
    private fun putRra(out: StringBuilder) {
        val n = out.length
        if (n >= 3 && out[n - 1] == VIRAMA && out[n - 2] == NA && isMalayalam(out[n - 3])) out.setCharAt(n - 2, CHILLU_N)
        out.append(RRA)
    }

    /** Whether the end of [out], past any joiner, can carry a sign: a Malayalam letter or sign, a digit, and those the standard names. */
    private fun hasBase(out: StringBuilder): Boolean {
        var i = out.length - 1
        while (i >= 0 && (out[i] == ZWJ || out[i] == ZWNJ)) i--
        if (i < 0) return false
        val base = out[i]
        return isMalayalam(base) || base.isDigit() || base == '-' || base == NBSP || base == DOTTED_CIRCLE
    }

    /**
     * Where the spaces at the end of [out] start: its length when it doesn't end in one. A no-break
     * space is no space between words here: it can carry a sign.
     */
    private fun startOfSpaces(out: StringBuilder): Int {
        var i = out.length
        while (i > 0 && Character.isWhitespace(out[i - 1])) i--
        return i
    }

    /** A Malayalam letter (consonant, vowel letter, chillu or dot reph) or sign. */
    private fun isMalayalam(c: Char): Boolean =
        c in CONSONANTS || c in VOWEL_LETTERS || c in CHILLUS || c == DOT_REPH || c in MARKS

    private fun pair(first: Char, second: Char): Int = (first.code shl 16) or second.code

    private val BLOCK = '\u0D00'..'\u0D7F'

    /** KA to TTTA, the scholarly letters among them. */
    private val CONSONANTS = '\u0D15'..'\u0D3A'

    private val VOWEL_LETTERS = (('\u0D05'..'\u0D14') - setOf('\u0D0D', '\u0D11') + setOf('\u0D5F', '\u0D60', '\u0D61')).toSet()

    /** The atomic chillus: m, y, lll, and nn, n, rr, l, ll, k. */
    private val CHILLUS = setOf('\u0D54', '\u0D55', '\u0D56', '\u0D7A', '\u0D7B', '\u0D7C', '\u0D7D', '\u0D7E', '\u0D7F')

    private val VOWEL_SIGNS = (('\u0D3E'..'\u0D44') + ('\u0D46'..'\u0D48') + ('\u0D4A'..'\u0D4C') + setOf('\u0D57', '\u0D62', '\u0D63')).toSet()

    private const val VIRAMA = '\u0D4D'
    private const val ANUSVARA = '\u0D02'
    private const val VISARGA = '\u0D03'

    /** Every sign that sits on a letter: the vowel signs, the three viramas, the anusvaras, the candrabindu and the visarga. */
    private val MARKS = VOWEL_SIGNS + setOf(VIRAMA, '\u0D3B', '\u0D3C', '\u0D00', '\u0D01', ANUSVARA, VISARGA)

    private const val DOT_REPH = '\u0D4E'
    private const val NA = '\u0D28'
    private const val RRA = '\u0D31'
    private const val CHILLU_N = '\u0D7B'
    private const val ZWJ = '\u200D'
    private const val ZWNJ = '\u200C'
    private const val NBSP = '\u00A0'
    private const val DOTTED_CIRCLE = '\u25CC'

    /** Table 12-42: the consonants whose chillu was written with a virama and a zero width joiner. */
    private val LEGACY_CHILLUS = mapOf(
        '\u0D23' to '\u0D7A',
        '\u0D28' to '\u0D7B',
        '\u0D30' to '\u0D7C',
        '\u0D32' to '\u0D7D',
        '\u0D33' to '\u0D7E'
    )

    /** Two code points written for one: the two-part vowel signs (rule 1) and Table 12-34's vowel letters (rule 2). */
    private val COMPOSED = mapOf(
        pair('\u0D46', '\u0D3E') to '\u0D4A',
        pair('\u0D47', '\u0D3E') to '\u0D4B',
        pair('\u0D46', '\u0D57') to '\u0D4C',
        pair('\u0D07', '\u0D57') to '\u0D08',
        pair('\u0D09', '\u0D57') to '\u0D0A',
        pair('\u0D0E', '\u0D46') to '\u0D10',
        pair('\u0D12', '\u0D3E') to '\u0D13',
        pair('\u0D12', '\u0D57') to '\u0D14'
    )
}
