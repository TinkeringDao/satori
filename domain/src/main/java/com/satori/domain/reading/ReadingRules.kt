package com.satori.domain.reading

import com.satori.domain.repository.ReaderOutput
import java.util.Locale

/**
 * Design D5: the readings the Reading step leaves empty, counted in the field log with no text, so
 * the AI steps don't read them at all. They catch noise and loops, never a fluent mistranslation,
 * which passes them (V3 and V4 count those). Every number of the rules is here, in one place, and
 * the Reading step's log line gives the floor it ran with ([LOG_PROB_FLOOR]).
 *
 * In order: a fragment ([isFragment]) is left empty without asking the model; a reading is emptied
 * when its mean log-probability is under the floor, its length is outside the band, or it repeats
 * itself ([emptied]). A reading with no words at all is empty as it is ([Empty.BLANK]).
 */
object ReadingRules {

    /** A line of fewer words than this is a fragment. */
    const val MIN_WORDS = 2

    /** A line of fewer letters than this is a fragment: letters and the signs written on them, of any script. */
    const val MIN_LETTERS = 6

    /**
     * The floor of a reading's mean log-probability per token, its end counted; a reading under it
     * is emptied. PROVISIONAL: V3 sets the real floor from the owner's own lines (the lowest value
     * that keeps no "invented" reading). Until then it empties only clear junk: on all 1,012 lines
     * of FLORES-200 devtest (V2 on the phone, 2026-10-21) the lowest mean was -0.894, the 1st
     * percentile -0.617 and the median -0.377; under -1.5 the model gave its own words a mean
     * probability of under 0.22 a token (e^-1.5), where no FLORES reading went under 0.40.
     */
    const val LOG_PROB_FLOOR = -1.5

    /**
     * D5's length band, as V2 gave it on the phone (2026-10-21): output tokens per source piece,
     * the 1st percentile of the readings of FLORES-200 devtest read in one part.
     */
    const val BAND_LOW = 0.500

    /** The band's top: the 99th percentile, as [BAND_LOW]. */
    const val BAND_HIGH = 1.079

    /**
     * The band holds for sources of at least this many pieces: the shortest line of FLORES-200
     * devtest, the lines V2 measured it on, has 12. On shorter ones a token more or less moves the
     * ratio by a twelfth or more (FLORES' lines of 12-19 pieces already reach 1.20, their 99th
     * percentile 1.15), and a short real reading ("Okay, I'll call you.") would fall outside it, so
     * there only a reading that hit D15's cap ([ReaderOutput.capped]) is outside: it is above the
     * band's top at every length (2 x pieces + 9 tokens against 1.079 x pieces).
     */
    const val BAND_MIN_PIECES = 12

    /** A reading that repeats a run of this many words... */
    const val REPEAT_RUN_WORDS = 3

    /** ...this many times or more is emptied: a loop. */
    const val REPEATS = 3

    /** Why a reading is empty. */
    enum class Empty {
        /** A fragment: under [MIN_WORDS] words or [MIN_LETTERS] letters, the model not asked. */
        FRAGMENT,

        /** Its mean log-probability is under [LOG_PROB_FLOOR], or none: the model allowed nothing but the end. */
        FLOOR,

        /** Its length is outside the band ([BAND_LOW] to [BAND_HIGH] tokens a piece), or it hit D15's cap. */
        BAND,

        /** It repeats a run of [REPEAT_RUN_WORDS] words [REPEATS] times. */
        REPEATS,

        /** The model wrote no words. */
        BLANK
    }

    /**
     * Whether [line], a line the reader reads, is a fragment: under [MIN_WORDS] words (a word is a
     * stretch between spaces that holds a letter) or under [MIN_LETTERS] letters and signs in all.
     * Noise and single syllables of a recording are such lines; the model would make fluent
     * English of them.
     */
    fun isFragment(line: String): Boolean {
        var words = 0
        var letters = 0
        var wordHasLetter = false
        var i = 0
        while (i < line.length) {
            val codePoint = line.codePointAt(i)
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (wordHasLetter) words++
                wordHasLetter = false
            } else if (isLetterOrSign(codePoint)) {
                letters++
                wordHasLetter = true
            }
            i += Character.charCount(codePoint)
        }
        if (wordHasLetter) words++
        return words < MIN_WORDS || letters < MIN_LETTERS
    }

    /**
     * Why [reading], the model's reading of a line, is emptied; null when it stands. In this
     * order: no words, the floor, the band, a loop.
     */
    fun emptied(reading: ReaderOutput): Empty? {
        val words = wordsOf(reading.text)
        return when {
            words.isEmpty() -> Empty.BLANK
            reading.meanLogProb == null || reading.meanLogProb.isNaN() || reading.meanLogProb < LOG_PROB_FLOOR -> Empty.FLOOR
            outsideBand(reading) -> Empty.BAND
            repeats(words) -> Empty.REPEATS
            else -> null
        }
    }

    private fun outsideBand(reading: ReaderOutput): Boolean {
        if (reading.capped) return true
        if (reading.pieces < BAND_MIN_PIECES) return false
        val perPiece = reading.tokens.toDouble() / reading.pieces
        return perPiece < BAND_LOW || perPiece > BAND_HIGH
    }

    /** Whether a run of [REPEAT_RUN_WORDS] words comes [REPEATS] times or more in [words], overlapping runs counted. */
    private fun repeats(words: List<String>): Boolean {
        val runs = HashMap<List<String>, Int>()
        for (start in 0..words.size - REPEAT_RUN_WORDS) {
            val run = words.subList(start, start + REPEAT_RUN_WORDS)
            val seen = runs.merge(run, 1, Int::plus)!!
            if (seen >= REPEATS) return true
        }
        return false
    }

    /**
     * The words of an English reading, lower case: stretches of letters, digits and apostrophes
     * that hold a letter or a digit, anything else between them. No regular expression, as every
     * pattern of the app's must also compile on Android's ICU.
     */
    internal fun wordsOf(text: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var hasLetterOrDigit = false
        fun endWord() {
            if (hasLetterOrDigit) words += word.toString().lowercase(Locale.ROOT)
            word.setLength(0)
            hasLetterOrDigit = false
        }
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            when {
                Character.isLetterOrDigit(codePoint) -> {
                    word.appendCodePoint(codePoint)
                    hasLetterOrDigit = true
                }
                codePoint == '\''.code || codePoint == '’'.code -> word.appendCodePoint(codePoint)
                else -> endWord()
            }
            i += Character.charCount(codePoint)
        }
        endWord()
        return words
    }

    private fun isLetterOrSign(codePoint: Int): Boolean = Character.isLetter(codePoint) || when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }
}
