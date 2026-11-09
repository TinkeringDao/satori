package com.satori.domain.usecase.llm

import java.lang.Character.UnicodeScript

/**
 * Whether a piece of an AI answer is written in another script than the Latin one (0.7.3). Every
 * recording's notes, titles, name, to-dos, ideas and map are asked for in English or German, so a
 * piece mostly in another script is none of them. On 2026-10-19 a 26-minute Malayalam recording got
 * three notes from Gemma 3 1B whose titles and points are in Telugu script, one mixed with Bengali
 * and Hebrew letters: the 1B mostly doesn't understand Malayalam and wrote another script.
 *
 * Other scripts are all but the Latin: Telugu, Bengali, Hebrew, Devanagari, Tamil, Kannada, Arabic,
 * Cyrillic, Greek, CJK, Thai and Malayalam itself. Latin letters with accents and the German umlauts
 * are Latin; digits, punctuation and marks are no letters of any script. A word is in the script
 * most of its letters are in.
 *
 * A piece is in another script when more than [MAX_SHARE] of its words are. Counted by words, not
 * letters: a Malayalam word kept in an English point (a name, say) is one word, however many
 * letters it has, so "Go to വിദ്യാഭ്യാസമന്ത്രാലയം" (three quarters of its letters Malayalam) stays, as
 * does "Meet രാധാകൃഷ്ണൻ", half its words. Two more rules since the review of 0.7.3, when the script of
 * what the call read is known ([isWrong]'s own, [scriptsOf]):
 * - A word of a script that is neither the Latin one nor one of the transcript's drops the piece
 *   at once: the transcript can't be its source. "Bluetooth firmware కోసం update" goes for a Malayalam
 *   recording, half Latin as it is. A word of one letter of such a script is a sign, not a word
 *   ("π", "µ"), and counts for nothing.
 * - Up to [MAX_NAME_WORDS] words in a row of the transcript's own script count as one, a name of a
 *   given and a family name: "Call രാധ കൃഷ്ണൻ" stays. A longer run counts every word.
 * Scripts written without spaces (Han, kana, Thai, ...) count each letter as a word, so "Android
 * 驱动程序软件很重要" is no English point with one long word in it.
 *
 * Never asked of a transcript, nor of the words a to-do's answer copies from its segment ("said"),
 * which are in the transcript's own script. Pure Kotlin, no I/O.
 */
object WrongScript {

    /** The share of a piece's words in other scripts above which it is in another script. */
    const val MAX_SHARE = 0.5

    /** The most words in a row of the transcript's own script that count as one word: a name. */
    const val MAX_NAME_WORDS = 3

    /** A word: letters, marks and digits, with the joiners of the Indic scripts inside it ("ഓഫ്‌ലൈൻ" is one). */
    private val WORD = Regex("""[\p{L}\p{M}\p{N}'’\u200C\u200D]+""")

    /** Scripts written without spaces between their words: each letter counts as one. */
    private val UNSPACED = setOf(
        UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.THAI, UnicodeScript.LAO,
        UnicodeScript.KHMER, UnicodeScript.MYANMAR, UnicodeScript.TIBETAN
    )

    /** Whether more than [MAX_SHARE] of the words of [text] are in another script than the Latin one, the script of what was read unknown. */
    fun isWrong(text: String): Boolean = isWrong(text, own = null)

    /**
     * Whether [text] is in another script than the Latin one, by this object's rules.
     *
     * @param own The scripts besides the Latin one of what the call read ([scriptsOf]): Malayalam
     *   for a Malayalam transcript, none for an English or German one. Null when it is not known (a
     *   theme's summary reads the map's points): every word of another script then counts as one
     *   word, and none drops the piece alone.
     */
    fun isWrong(text: String, own: Set<UnicodeScript>?): Boolean {
        var latin = 0
        var other = 0
        var run = 0
        fun endRun() {
            other += if (run in 1..MAX_NAME_WORDS) 1 else run
            run = 0
        }
        for (match in WORD.findAll(text)) {
            val word = Word.of(match.value) ?: continue
            // A script the transcript isn't in, also inside a word mostly of another one.
            if (own != null && word.letters.any { (script, count) -> script != UnicodeScript.LATIN && script !in own && (count >= 2 || script in UNSPACED) }) {
                return true
            }
            when {
                word.script == UnicodeScript.LATIN -> {
                    endRun()
                    latin++
                }
                word.script in UNSPACED -> {
                    endRun()
                    other += word.letters.filterKeys { it in UNSPACED }.values.sum()
                }
                own == null -> other++
                // One letter of another script than the transcript's: a sign ("π"), no word.
                word.script in own -> run++
            }
        }
        endRun()
        return other > (latin + other) * MAX_SHARE
    }

    /**
     * The scripts besides the Latin one that [text] has a letter of: Malayalam for a Malayalam
     * transcript and for an English one that holds a name in Malayalam letters ("call ഗീത about
     * the rent"), none for an English or German one.
     */
    fun scriptsOf(text: String): Set<UnicodeScript> {
        val scripts = HashSet<UnicodeScript>()
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            val script = UnicodeScript.of(codePoint)
            if (script != UnicodeScript.LATIN && script !in NO_SCRIPT) scripts += script
        }
        return scripts
    }

    // A letter of no script in particular (COMMON, as some modifier letters are) counts for neither;
    // the marks, whose script is the letter's they sit on, are no letters.
    private val NO_SCRIPT = setOf(UnicodeScript.COMMON, UnicodeScript.UNKNOWN)

    /**
     * A word's [script], the one most of its letters are in (the Latin one on a tie), and its
     * [letters] of each script.
     */
    private class Word(val script: UnicodeScript, val letters: Map<UnicodeScript, Int>) {
        companion object {
            /** [word]'s script; null for a word of no letters of a script ("10"). */
            fun of(word: String): Word? {
                val counts = HashMap<UnicodeScript, Int>()
                var i = 0
                while (i < word.length) {
                    val codePoint = word.codePointAt(i)
                    i += Character.charCount(codePoint)
                    if (!Character.isLetter(codePoint)) continue
                    val script = UnicodeScript.of(codePoint)
                    if (script in NO_SCRIPT) continue
                    counts[script] = (counts[script] ?: 0) + 1
                }
                val (script, _) = counts.entries.maxByOrNull { (script, count) -> count * 2 + if (script == UnicodeScript.LATIN) 1 else 0 } ?: return null
                return Word(script, counts)
            }
        }
    }
}
