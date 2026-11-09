package com.satori.domain.usecase.todo

import com.satori.domain.language.MalayalamText
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil

/**
 * Whether a found to-do or idea comes from the words of the segments it cites. The model, above
 * all Gemma 3 1B, copies items from what its prompt shows it: on 2026-10-14 an English meeting got
 * "Make the clock bigger" (the to-do prompt's own example, through two recordings) and three items
 * of other recordings (from the header's noted list), each with the time of a sentence of the
 * meeting, and a garbled 11 s note got the idea "Make the clock bigger". None of them shares a word
 * with the segments they cited. A found item is kept only when its content words are in them
 * ([isGrounded]): a paraphrase that keeps the key words passes, a copy from elsewhere does not,
 * whatever time it was given.
 *
 * The words compared: those of the item's text, or for an item written in another language than
 * what was said (a Malayalam recording's to-dos are English) the words of the segment the model
 * copied for it, in the transcript's own script ([TodoAnswer.Item.said]). Content words only: the
 * English and German function words, the times ("tomorrow", "Freitag", "five"), numbers and the
 * words that say nothing alone ("something", "make") don't count, nor do Malayalam's commonest
 * function words. A word is found in the segments as itself or as another form of it: the same
 * first letters ("deleting" and "delete", "options" and "option"), one inside the other ("Arzt" in
 * "Arztpraxis"), a German participle ("geliefert" for "liefern") or a German "zu" infinitive
 * ("einzukaufen" for the spotter's "Einkaufen"). Latin letters are compared folded (case and
 * accents); Malayalam letters as written, signs and all.
 *
 * A word found only as the first three letters of another ("big" for "bigger", "car" for
 * "career") is a loose tie: an item needs two words found, or one found firmly, so the prompt's
 * "Make the clock bigger" is no item of a segment about "a big problem".
 *
 * Measured on the to-dos of the owner's phone (2026-10-14, 64 of them, not kept): the copies share
 * no content word with their segments, the items said there share all or nearly all.
 */
internal object TodoGrounding {

    /**
     * The share of an item's content words that must be found in its segments, and at least one.
     * Half would drop "Ring the heating engineer about the broken boiler" for "I must get someone
     * to fix the boiler, it broke" (two of five); a single word would keep a copy of "Call the bank
     * about the loan" in a meeting that names a bank holiday (one of three).
     */
    const val MIN_SHARE = 0.4

    /** The fewest letters of a Latin word that counts; Malayalam words count from two. */
    private const val MIN_LATIN_LETTERS = 3
    private const val MIN_MALAYALAM_LETTERS = 2

    /** First letters two forms of one word share at least: "delet" of "delete" and "deleting". */
    private const val MIN_SHARED_START = 5

    /** The shortest word found inside a longer one: "arzt" in "arztpraxis". */
    private const val MIN_INSIDE = 4

    /**
     * The shortest word whose letters start another and tie the two firmly: "cook" of "cooking".
     * A shorter one ("big" of "bigger") ties them loosely ([Tie.LOOSE]).
     */
    private const val MIN_FIRM_START = 4

    private val WORD = Regex("""[\p{L}\p{M}\p{N}]+""")
    private val MARKS = Regex("""\p{M}+""")
    private val JOINERS = Regex("[‌‍]")
    private val MALAYALAM_BLOCK = 0x0D00..0x0D7F

    /**
     * A folded German "zu" infinitive of a separable verb, as [ExplicitTodoSpotter] makes it plain:
     * the prefix in group 1, the verb in group 2 ("ein" and "kaufen" of "einzukaufen"). Lazy, as it
     * is built from the spotter's prefixes.
     */
    private val ZU_INFINITIVE: Regex by lazy {
        Regex("""^(""" + ExplicitTodoSpotter.ZU_PREFIXES.joinToString("|") { fold(it) } + """)zu([a-z]{2,}(?:en|ern|eln))$""")
    }

    /** How firmly a word of an item is found in a word said: not, by its first three letters alone, or firmly. */
    private enum class Tie { NONE, LOOSE, FIRM }

    /** A word as compared: as written (folded), and as the plain infinitive of a "zu" infinitive. */
    private class Form(val word: String) {
        val withoutZu: String = ZU_INFINITIVE.matchEntire(word)?.let { it.groupValues[1] + it.groupValues[2] } ?: word
    }

    /**
     * Whether [words], an item's text or the words copied for it, are found in [sources], the
     * text of the segments it cites: at least [MIN_SHARE] of its content words, and one; and two
     * of them, or one firmly ([Tie.FIRM]). An item with no content word at all ("Do it", "See
     * you") is not.
     */
    fun isGrounded(words: String, sources: String): Boolean {
        val evidence = contentWords(words)
        if (evidence.isEmpty()) return false
        val said = wordsOf(sources).toSet().map(::Form)
        val ties = evidence.map { tieOf(Form(it), said) }
        val found = ties.count { it != Tie.NONE }
        return found >= 1 && found >= ceil(evidence.size * MIN_SHARE).toInt() && (found >= 2 || Tie.FIRM in ties)
    }

    /** The content words of [text], folded, in order and with repeats. */
    fun contentWords(text: String): List<String> = wordsOf(text).filter(::isContentWord)

    private fun isContentWord(word: String): Boolean {
        if (word.all { it.isDigit() }) return false
        return if (isMalayalam(word)) {
            word.codePointCount(0, word.length) >= MIN_MALAYALAM_LETTERS && word !in MALAYALAM_STOP_WORDS
        } else {
            word.length >= MIN_LATIN_LETTERS && word !in STOP_WORDS
        }
    }

    /**
     * The words of [text]: runs of letters, signs and digits. Latin ones lower case without
     * accents, "ß" as "ss"; Malayalam ones as written (NFC), without the joiners an old keyboard
     * puts in a chillu, and spelt as a stored line spells them ([MalayalamText]): the model may
     * copy a word with another spelling of the same letters, a chillu or nta the old way say.
     */
    private fun wordsOf(text: String): List<String> =
        WORD.findAll(Normalizer.normalize(JOINERS.replace(MalayalamText.clean(text), ""), Normalizer.Form.NFC)).map { match ->
            val word = match.value
            if (isMalayalam(word)) word else fold(word)
        }.toList()

    private fun fold(word: String): String =
        MARKS.replace(Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD), "").replace("ß", "ss")

    private fun isMalayalam(word: String): Boolean = word.any { it.code in MALAYALAM_BLOCK }

    /** How firmly [word] is found among [said]: the firmest tie to any of them, as written or as plain infinitives. */
    private fun tieOf(word: Form, said: List<Form>): Tie {
        var best = Tie.NONE
        for (form in said) {
            val written = tie(word.word, form.word)
            val plain = word.withoutZu != word.word || form.withoutZu != form.word
            val tie = if (written != Tie.FIRM && plain) maxOf(written, tie(word.withoutZu, form.withoutZu)) else written
            if (tie == Tie.FIRM) return tie
            if (tie > best) best = tie
        }
        return best
    }

    /** How firmly [said] is [word] or another form of it, as the class description says. */
    private fun tie(word: String, said: String): Tie {
        if (word == said) return Tie.FIRM
        val (short, long) = if (word.length <= said.length) word to said else said to word
        if (short.length >= MIN_LATIN_LETTERS && long.startsWith(short)) {
            return if (short.length >= MIN_FIRM_START) Tie.FIRM else Tie.LOOSE
        }
        if (sharedStart(word, said) >= MIN_SHARED_START) return Tie.FIRM
        if (short.length >= MIN_INSIDE && long.contains(short)) return Tie.FIRM
        return if (sharedStart(withoutParticiple(word), withoutParticiple(said)) >= MIN_SHARED_START) Tie.FIRM else Tie.NONE
    }

    private fun sharedStart(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /** A German participle without its "ge": "geliefert" as "liefert", which "liefern" begins as. */
    private fun withoutParticiple(word: String): String =
        if (word.length >= 6 && word.startsWith("ge") && !isMalayalam(word)) word.removePrefix("ge") else word

    // English and German words that say nothing of what is to be done, folded like the words: the
    // function words, the times and numbers (a time is the to-do's own, never its evidence), and
    // words any item may hold ("something", "make", "get").
    private val STOP_WORDS: Set<String> = (
        "the and but for nor yet with from into onto about over under after before between through during " +
            "without within against among this that these those there here then than also just only still even " +
            "very really quite some any all each every both either neither more most much many few other another " +
            "such same own you your yours him his her hers its our ours they them their theirs she who whom whose " +
            "which what when where why how are was were been being have has had having does did doing done will " +
            "would shall should can could may might must need needs not yes okay well like maybe please let lets " +
            "something anything nothing everything thing things stuff make makes made get gets got going take " +
            "don doesn didn isn aren wasn weren won wouldn couldn shouldn cannot " +
            "today tomorrow tonight yesterday morning afternoon evening night noon midnight day days week weeks " +
            "month months year years hour hours minute minutes monday tuesday wednesday thursday friday saturday " +
            "sunday weekend next last first second third one two three four five six seven eight nine ten " +
            "eleven twelve half quarter later soon asap now ago " +
            "der die das den dem des ein eine einen einem einer eines und oder aber auch noch nur schon sehr mal " +
            "nicht kein keine keinen dass wenn weil als wie ich mich mir wir uns ihr ihm ihn sie sein seine " +
            "meine mein unser unsere dein deine dieser diese dieses diesen jetzt dann hier dort etwas nichts alles " +
            "muss mussen soll sollen sollte will wollen werde werden wird kann konnen habe haben hat ist sind war " +
            "waren bin bitte mit fur von vom zum zur bei beim nach aus auf uber unter vor bis gegen ohne " +
            "heute morgen ubermorgen abend abends mittag nachmittag vormittag nacht tag tage tagen woche wochen " +
            "monat monate jahr stunde stunden montag dienstag mittwoch donnerstag freitag samstag sonntag " +
            "wochenende nachste nachsten nachster eins zwei drei vier funf sechs sieben acht neun zehn halb " +
            "machen mache macht gemacht"
        ).split(' ').filter { it.isNotEmpty() }.toSet()

    // Malayalam's commonest function words: "a", "this", "that", "I", "we", "he", "she", "they",
    // "then", "is", "there is", "not", "also", "only", "all", the quotative.
    private val MALAYALAM_STOP_WORDS: Set<String> = setOf(
        "ഒരു", "ഈ", "ആ", "ഇത്", "അത്", "ഇതു", "അതു", "ഞാൻ", "നമ്മൾ", "ഞങ്ങൾ", "അവൻ", "അവൾ", "അവർ",
        "പിന്നെ", "ആണ്", "ഉണ്ട്", "ഇല്ല", "അല്ല", "കൂടി", "മാത്രം", "എല്ലാം", "എന്ന്", "എന്നു", "ഒക്കെ"
    )
}
