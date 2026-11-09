package com.satori.domain.usecase.todo

import com.satori.domain.language.MalayalamText
import java.text.Normalizer

/**
 * The Malayalam words with which a speaker asks for a thing to be done, commits to it, asks to be
 * reminded, or says when: "വിളിക്കണം" (must call), "വേണം" (is needed), "ഓർമ്മിപ്പിക്കുക" (remind),
 * "മറക്കരുത്" (don't forget), "കൊടുക്കാം" (I'll give), "നാളെ" (tomorrow), "ഒമ്പത് മണിക്ക്" (at
 * nine). [ExtractTodosUseCase] keeps a to-do the small live model (Gemma 3 1B) read from a Malayalam
 * segment only when the clause of the words it quotes from the segment holds one ([holds],
 * [quotedClause]), and an idea only when that clause puts one forward ([holdsIdea]): no cue of
 * English or German can be read in Malayalam ([TodoGate]), and on 2026-10-16 the 1B made open
 * to-dos and an idea of a Malayalam reading that asked for nothing. Gemma 4 E2B, which reads
 * Malayalam far better, is not held to them.
 *
 * Malayalam joins its endings to the word, so a cue is found as:
 * - [WORDS]: a word, or the start of one ("വേണമെന്ന്", that it is needed);
 * - [ENDINGS]: the end of a word, the obligative "-അണം" (must) after a verb's stem, whose final ം is
 *   written മ before a further ending ("വിളിക്കണമെന്ന്", that one must call). Only stems that no
 *   common noun in -ണം shares: "-രണം" is left out for "കാരണം" (because) and "മരണം" (death),
 *   "-ഷണം" for "ഭക്ഷണം" (food), and "പട്ടണം" (town) is none ([NOT_OBLIGATIVE]); "പണം" (money)
 *   ends in none. "തരണം" after a verb ("അയച്ചു തരണം", send it to me) asks for a thing to be
 *   done for the speaker; before "ചെയ്" it is "to overcome", and "വിതരണം" (distribution) is none;
 * - [PROMISE]: the promissive "-ആം" at the end of a word ("ചെയ്യാം", I'll do it; "ചെയ്യാമോ", could
 *   you), but not in "എല്ലാം" (all), the ordinals ("രണ്ടാം", second), a word of grams, a name, or
 *   a "may be" ("ആകാം", "വന്നേക്കാം": [NOT_PROMISES], [NOT_PROMISE_ENDINGS]);
 * - [TIMES]: a day or a part of it, as a word or its start ("നാളത്തെ", tomorrow's), today as a
 *   whole word ([TODAY]: "ഇന്നലെ", yesterday, starts the same), and [O_CLOCK] inside a word
 *   ("പത്തുമണിക്ക്", at ten): a time is a cue only in a clause not told in the past ([toldInPast]),
 *   as a story's "ഒരു ദിവസം രാവിലെ ... പോയി" (one morning ... went) asks for nothing;
 * - [PHRASES]: anywhere in the text, words in a row or a piece of a word ("-േണ്ടത്", what has to
 *   be); [TIME_PHRASES] likewise, as times ("അടുത്ത ആഴ്ച", next week).
 *
 * An idea ([holdsIdea]) is put forward with "നന്നായിരിക്കും" (it would be good), "-ആയിരുന്നെങ്കിൽ"
 * (if only it were) and the like ([IDEAS]), or with a request or a promise of the above: a time
 * puts none forward.
 *
 * The lists are a first set, each cue tested (MalayalamTodoCuesTest); English words said in
 * Malayalam come out in Malayalam letters, so "റിമൈൻഡ്" (remind) and "ടുമാറോ" (tomorrow) are among
 * them. The text is compared as [MalayalamText] spells a stored line, without the joiners of an old
 * keyboard.
 */
internal object MalayalamTodoCues {

    /** Cues of a request, a commitment or a reminder that are a word, or the start of one. */
    val WORDS: List<String> = listOf(
        // need, must: "വേണം", "വേണമെന്ന്"
        "വേണം", "വേണമ",
        // must come, must go (as it is said, for "പോകണം"), as words of their own: "-രണം" and "-ണം" also end nouns
        "വരണം", "വരണമ", "പോണം", "പോണമ",
        // remind: "ഓർമ്മിപ്പിക്കുക", "ഓർമ്മിപ്പിക്കണം", also with one മ; remind, said in English
        "ഓർമ്മിപ്പിക്ക", "ഓർമിപ്പിക്ക", "റിമൈൻഡ",
        // don't forget, without forgetting, don't go and forget
        "മറക്കരുത്", "മറക്കല്ലേ", "മറക്കാതെ", "മറന്നുപോകരുത്", "മറന്നുപോവരുത്"
    )

    /**
     * The obligative "-അണം" after a verb's stem, ending a word: "വിളിക്കണം", "പോകണം" (-കണം);
     * "ചെയ്യണം", "പറയണം" (-യണം); "വാങ്ങണം" (-ങ്ങണം); "കാണണം" (-ണണം); "എഴുതണം", "നിർത്തണം"
     * (-തണം); "കൊണ്ടുവരണം" (-ുവരണം); "ഇടണം", "പൂട്ടണം", "കിട്ടണം" (-ടണം); "മാറ്റണം" (-റ്റണം); and
     * the same for the speaker, "അയച്ചുതരണം", "പറഞ്ഞുതരണം" (-ുതരണം), "വാങ്ങിത്തരണം" (-ിത്തരണം).
     * Each ends on ം, which [holds] also finds written മ.
     */
    val ENDINGS: List<String> = listOf("കണം", "യണം", "ങ്ങണം", "ണണം", "തണം", "ുവരണം", "ടണം", "റ്റണം", "ുതരണം", "ിത്തരണം")

    /** Nouns that end like an obligative: the town ("പട്ടണം", "പട്ടണമാണ്"). */
    val NOT_OBLIGATIVE: List<String> = listOf("പട്ടണ")

    /** The promissive "-ആം" at the end of a word, and the same asked ("-ആമോ", could you). */
    const val PROMISE = "ാം"
    private const val PROMISE_ASKED = "ാമോ"

    /** Words in -ആം that promise nothing: all, the ordinals, names, almonds. */
    val NOT_PROMISES: Set<String> = setOf(
        "എല്ലാം", "ഒന്നാം", "രണ്ടാം", "മൂന്നാം", "നാലാം", "അഞ്ചാം", "ആറാം", "ഏഴാം", "എട്ടാം", "ഒമ്പതാം", "ഒൻപതാം",
        "പത്താം", "നൂറാം", "ഇസ്ലാം", "സലാം", "ഇമാം", "ഹറാം", "ബദാം"
    )

    /** Endings in -ആം that promise nothing: grams and programs, and a "may be" ("ആകാം", "വന്നേക്കാം", "ആയിരിക്കാം"). */
    val NOT_PROMISE_ENDINGS: List<String> = listOf("ഗ്രാം", "ാകാം", "ാവാം", "േക്കാം", "ിരിക്കാം")

    /** Days and parts of the day, a word or the start of one: a cue in a clause not told in the past. */
    val TIMES: List<String> = listOf(
        // tomorrow, tomorrow's, the day after, tomorrow said in English, today's
        "നാളെ", "നാളത്ത", "മറ്റന്നാൾ", "ടുമാറോ", "ഇന്നത്ത",
        // in the morning, at noon, in the evening, at night
        "രാവിലെ", "ഉച്ചയ്ക്ക്", "ഉച്ചക്ക്", "വൈകുന്നേരം", "വൈകിട്ട്", "വൈകീട്ട്", "രാത്രി",
        // Monday to Sunday
        "തിങ്കളാഴ്ച", "ചൊവ്വാഴ്ച", "ബുധനാഴ്ച", "വ്യാഴാഴ്ച", "വെള്ളിയാഴ്ച", "ശനിയാഴ്ച", "ഞായറാഴ്ച"
    )

    /** Today, as a whole word only: yesterday ("ഇന്നലെ") starts the same. */
    val TODAY: Set<String> = setOf("ഇന്ന്", "ഇന്നു")

    /** At ... o'clock, also inside a word ("പത്തുമണിക്ക്"). */
    const val O_CLOCK = "മണിക്ക്"

    /** Cues found anywhere in the text: "-േണ്ടത്", what has to be ("ചെയ്യേണ്ടത്"). */
    val PHRASES: List<String> = listOf("േണ്ടത")

    /** Times found anywhere in the text: next week. */
    val TIME_PHRASES: List<String> = listOf("അടുത്ത ആഴ്ച")

    /**
     * What else says when, for [holdsTime] alone (design D9), which [holds] doesn't read as a cue:
     * the hour's stem, written -ക്ക് or -ക്കു ("അഞ്ചു മണിക്കു", at five), by an hour ("മണിയോടെ"),
     * inside a word.
     */
    val HOURS: List<String> = listOf("മണിക്ക", "മണിയോടെ")

    /** Words that say when, a word or the start of one, for [holdsTime] alone: the afternoon, a date ("തീയതി"), Sunday said short. */
    val WHEN_WORDS: List<String> = listOf("ഉച്ചകഴിഞ്ഞ", "തീയതി", "ഞായർ")

    /** Times in words in a row, anywhere in the text, for [holdsTime] alone: next month, this week, this month. */
    val WHEN_PHRASES: List<String> = listOf("അടുത്ത മാസം", "ഈ ആഴ്ച", "ഈ മാസം")

    /**
     * What puts an idea forward, anywhere in the text: it would be good or better, it would have
     * been good, if only it were (had been), one could have, it should have been.
     */
    val IDEAS: List<String> = listOf(
        "നന്നായിരിക്കും", "നല്ലതായിരിക്കും", "നന്നാവും", "നല്ലതാവും", "നന്നായേനെ", "നല്ലതായേനെ",
        "ിരുന്നെങ്കിൽ", "ാമായിരുന്നു", "ണമായിരുന്നു"
    )

    /** The letter an ending's final ം is written as before a further ending. */
    private const val MA = "മ"

    /** "തരണം" (for me) as a word of its own after a verb, and the "do" that makes it "to overcome". */
    private const val FOR_ME = "തരണം"
    private const val DO = "ചെയ്"

    /** The vowel signs u and i that end a past verb ("പോയി", "പറഞ്ഞു"), and the present it is not ("പോകുന്നു"). */
    private const val SIGN_U = 'ു'
    private const val SIGN_I = 'ി'
    private const val PRESENT = "ുന്നു"
    private const val HAD = "ിരുന്നു"
    private const val SAT = "ഇരുന്നു"

    private val JOINERS = Regex("[\u200C\u200D]")
    private val SPACES = Regex("""\s+""")
    private val EDGE = Regex("""^[^\p{L}\p{M}\p{N}]+|[^\p{L}\p{M}\p{N}]+$""")
    private val TRAILING = Regex("""[^\p{L}\p{M}\p{N}]+$""")

    /** What ends a clause: a comma, a full stop, a question or exclamation mark, a semicolon, a colon, a danda. */
    private const val CLAUSE_BREAKS = ",.?!;:।\n"

    /**
     * Whether [text] holds a cue of a to-do by the rules above: a request, a commitment, a reminder
     * or a promise anywhere; a time in a clause that isn't told in the past.
     */
    fun holds(text: String): Boolean {
        val normalized = normalize(text)
        if (PHRASES.any { it in normalized }) return true
        return clausesOf(normalized).any { clause ->
            val words = words(clause)
            words.indices.any { asks(words, it) } ||
                (!toldInPast(words) && (words.any(::isTime) || TIME_PHRASES.any { it in clause }))
        }
    }

    /**
     * Whether [text] says when (design D9, 0.8.0): a day or a part of it ([TIMES], [WHEN_WORDS],
     * today as a whole word, [TODAY]), an hour ([O_CLOCK], [HOURS], also inside a word, or a clock
     * time in figures, "10:30"), or a time in words in a row ([TIME_PHRASES], [WHEN_PHRASES]),
     * anywhere in it, told in the past or not. A Malayalam line read into English keeps a due time
     * the time parser found in its reading only when its line as it was said holds one, so a date
     * the reader made up or got wrong sets no due time: the parser and the rules for a time already
     * past judge the reading's time itself.
     */
    fun holdsTime(text: String): Boolean {
        val normalized = normalize(text)
        if (TIME_PHRASES.any { it in normalized } || WHEN_PHRASES.any { it in normalized }) return true
        return words(normalized).any { word ->
            isTime(word) || HOURS.any { it in word } || WHEN_WORDS.any { word.startsWith(it) } || isClockTime(word)
        }
    }

    /** Whether [word] holds a clock time in figures: one or two digits, a colon, two digits ("7:30", "10:00"). */
    private fun isClockTime(word: String): Boolean {
        val colon = word.indexOf(':')
        if (colon !in 1 until word.length - 2) return false
        val hour = word.substring(maxOf(0, colon - 2), colon).takeLastWhile { it.isDigit() }
        val minutes = word.substring(colon + 1).takeWhile { it.isDigit() }
        return hour.isNotEmpty() && minutes.length == 2
    }

    /**
     * Whether [text] puts an idea forward: one of [IDEAS], or a request, a commitment or a promise
     * ("ഒരു ഓപ്ഷൻ വേണം", an option is needed). A time alone puts none forward.
     */
    fun holdsIdea(text: String): Boolean {
        val normalized = normalize(text)
        if (IDEAS.any { it in normalized } || PHRASES.any { it in normalized }) return true
        return clausesOf(normalized).any { clause -> words(clause).let { words -> words.indices.any { asks(words, it) } } }
    }

    /**
     * The words of [segment] an item that quoted [quote] from it was read from: the quote's clause,
     * where the quote stands in [segment] word for word, from the clause break before it to the one
     * after it, or all of [segment] when it has none (AI4Bharat's model writes no punctuation). A
     * Malayalam clause ends on its verb, which says that a thing must be done ("... കൊടുക്കണം"), and
     * often starts with its time ("നാളെ രാവിലെ ..."), so the clause of a quote of the thing holds its
     * cue. A quote that runs over a break keeps both clauses, each ended by its comma. A quote the
     * model changed counts alone; no quote, nothing.
     */
    fun quotedClause(quote: String?, segment: String): String {
        val quoted = words(normalize(quote.orEmpty()))
        if (quoted.isEmpty()) return ""
        val tokens = tokensOf(normalize(segment))
        val said = tokens.map { it.word }
        val at = (0..said.size - quoted.size).firstOrNull { start -> quoted.indices.all { said[start + it] == quoted[it] } }
            ?: return quoted.joinToString(" ")
        var from = at
        while (from > 0 && !tokens[from - 1].breakAfter) from--
        var to = at + quoted.size - 1
        while (to < tokens.lastIndex && !tokens[to].breakAfter) to++
        return (from..to).joinToString(" ") { i -> tokens[i].word + if (tokens[i].breakAfter && i < to) "," else "" }
    }

    /** Whether word [i] of [words] asks for a thing, commits to it, asks to be reminded or promises it. */
    private fun asks(words: List<String>, i: Int): Boolean {
        val word = words[i]
        if (WORDS.any { word.startsWith(it) }) return true
        if (isObligative(word) || isPromise(word)) return true
        // "അയച്ചു തരണം" (send it to me), not "പ്രതിസന്ധി തരണം ചെയ്തു" (overcame the crisis).
        if (word == FOR_ME || word.startsWith(FOR_ME.dropLast(1) + MA)) {
            val before = words.getOrNull(i - 1).orEmpty()
            val after = words.getOrNull(i + 1).orEmpty()
            return (before.endsWith(SIGN_U) || before.endsWith(SIGN_I)) && !after.startsWith(DO)
        }
        return false
    }

    private fun isObligative(word: String): Boolean =
        ENDINGS.any { ending -> word.endsWith(ending) || (ending.dropLast(1) + MA) in word } && NOT_OBLIGATIVE.none { it in word }

    private fun isPromise(word: String): Boolean {
        val promised = (word.endsWith(PROMISE) || word.endsWith(PROMISE_ASKED)) && word.codePointCount(0, word.length) >= MIN_PROMISE_LENGTH
        return promised && word !in NOT_PROMISES && NOT_PROMISE_ENDINGS.none { word.endsWith(it) }
    }

    private fun isTime(word: String): Boolean = word in TODAY || O_CLOCK in word || TIMES.any { word.startsWith(it) }

    /**
     * Whether the clause of [words] is told in the past: its last word, its verb, ends on the vowel
     * sign u or i of a past tense ("പോയി", went; "കളിച്ചു", played; "ജീവിച്ചിരുന്നു", had lived),
     * not of the present ("പോകുന്നു", goes or is going, as a plan for tomorrow is said).
     */
    private fun toldInPast(words: List<String>): Boolean {
        val last = words.lastOrNull() ?: return false
        if (last.endsWith(HAD) || last == SAT) return true
        if (last.endsWith(PRESENT)) return false
        return last.endsWith(SIGN_U) || last.endsWith(SIGN_I)
    }

    /** One word of a text, and whether a clause break comes right after it. */
    private class Token(val word: String, val breakAfter: Boolean)

    /** The words of a normalized text, each with whether a clause break comes after it. */
    private fun tokensOf(normalized: String): List<Token> {
        val tokens = mutableListOf<Token>()
        for (raw in normalized.split(' ')) {
            val word = EDGE.replace(raw, "")
            val trailing = TRAILING.find(raw)?.value.orEmpty()
            if (word.isEmpty()) {
                // A break written apart from its word ends the clause of the word before it.
                if (raw.any { it in CLAUSE_BREAKS } && tokens.isNotEmpty()) tokens[tokens.lastIndex] = Token(tokens.last().word, breakAfter = true)
                continue
            }
            tokens += Token(word, breakAfter = trailing.any { it in CLAUSE_BREAKS })
        }
        return tokens
    }

    /** The clauses of a normalized text, split at its breaks. */
    private fun clausesOf(normalized: String): List<String> =
        normalized.split(*CLAUSE_BREAKS.toCharArray()).map { it.trim() }.filter { it.isNotEmpty() }

    /** [text] spelt as a stored line is, without the joiners, NFC, its words split by single spaces. */
    private fun normalize(text: String): String =
        SPACES.replace(Normalizer.normalize(JOINERS.replace(MalayalamText.clean(text), ""), Normalizer.Form.NFC), " ").trim()

    /** The words of a normalized text, without the punctuation at their edges. */
    private fun words(normalized: String): List<String> =
        normalized.split(' ').map { EDGE.replace(it, "") }.filter { it.isNotEmpty() }

    /** The fewest letters and signs of a promise: "വരാം" (I'll come); "ജാം" (jam) and "ഡാം" (dam) have fewer. */
    private const val MIN_PROMISE_LENGTH = 4
}
