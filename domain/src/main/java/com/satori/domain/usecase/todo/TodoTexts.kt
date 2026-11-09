package com.satori.domain.usecase.todo

import com.satori.domain.language.LanguageDetector
import java.text.Normalizer
import java.util.Locale

/**
 * Tidies the todo texts the model writes, tells when two todos say the same, and when a text
 * names a time. Finds the sentences a todo speaks of, and the hedges said in them.
 *
 * Small models often copy the words as they were said: "I need to call the bank tomorrow". The
 * text keeps what to do. The spoken lead-in goes ("I need to", "Remind me to", "Ich muss",
 * "Nicht vergessen:"), and so does the due time, which the todo stores on its own and which
 * would read wrong once the day has passed ("tomorrow"). English and German rules run on every
 * text, because people mix the languages.
 *
 * So do the hesitations of speech, anywhere in the text and said twice too ("uh uh", "ähm",
 * "hmm"), with the commas around them, and a word a stammer says twice ("the the", "ich ich"):
 * found texts kept them (an idea with "uh uh" in its middle, 2026-10-14). "um" and "er" only go
 * from an English text, as they are words in German ("um 9", "um zu", "er"); a word in capitals
 * never goes ("the ER", "HM"), and the full stop after a hesitation stays where it ends a sentence.
 * The owner's wording stays otherwise: a word said twice on purpose ("very very"), or twice by
 * grammar ("die die Pakete bringen", "Log in in the morning", "ob Sie sie abholen").
 */
internal object TodoTexts {

    /** Longest todo kept, in words. The prompt asks for ten. */
    const val MAX_WORDS = 15

    /**
     * Words that say a plan or its time is not sure: a todo whose sentence holds one is due
     * tentatively. [ExplicitTodoSpotter] reads them in its clause, [ExtractTodosUseCase] in the
     * sentences of the model's todos.
     */
    val HEDGES: Set<String> = setOf(
        "maybe", "perhaps", "probably", "possibly", "vielleicht", "eventuell", "wahrscheinlich", "möglicherweise"
    )

    // A word this long or longer ties a todo to a sentence; its first letters are compared, so
    // "repariert" meets "reparieren" and "options" meets "option".
    private const val MIN_SHARED_WORD = 4

    private val SENTENCE_BREAK = Regex("""(?<=[.!?])\s+""")
    private val HEDGE_WORD = Regex("""[\p{L}\p{N}]+""")

    /**
     * What [isDuplicate] compares: the content words, and all words for texts without any.
     * [owners] are the words said with a possessive "'s" ("anna" of "Call Anna's mother"), which
     * [isRepeat] reads.
     */
    class Key internal constructor(val words: Set<String>, val allWords: String, val owners: Set<String>)

    private val SPACES = Regex("""\s+""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([,.;:!?])""")
    private val LIST_MARK = Regex("""^(?:[-*•–]+\s*|\d+[.)]\s+)""")

    // A word, with the "'s" after it: the possessive of "Anna's" or the "is" of "it's".
    private val WORD = Regex("""[\p{L}\p{N}]+(?:['’]s(?![\p{L}\p{N}]))?""")
    private const val S_ENDING = "'s"
    private val MARKS = Regex("""\p{M}+""")
    private const val QUOTES = "\"'`“”„‟«»‚‘’‹›"
    private const val EDGE_PUNCTUATION = ".,;:!?-–—…·"

    // Words that tie a time to the text: "on Friday", "bis nächsten Freitag". Only the ones
    // right before the time phrase go, so "Log in to the portal on Friday" keeps its "in".
    private const val TIME_PREPOSITIONS =
        "on|at|by|until|till|before|from|am|um|bis|ab|vor|zum|zur|gegen"
    private const val TIME_DETERMINERS =
        "next|this|coming|nächsten|nächster|nächste|kommenden|kommender|kommende|" +
            "diesen|dieser|diese|übernächsten|übernächste"

    /** The words that tie a time to a text, in lower case: "on" of "on Friday", "bis" of "bis Sonntag". */
    val TIME_TIES: Set<String> = "$TIME_PREPOSITIONS|$TIME_DETERMINERS".split('|').toSet()

    private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)

    // A time phrase that is a possessive ("Friday's meeting") names the thing, not the due time.
    private val POSSESSIVE = Regex("""['’]s$""", IGNORE_CASE)

    // Spoken lead-ins, removed from the start until none is left: "Okay, so I need to call ..."
    private val LEAD_INS = listOf(
        """^(?:ok(?:ay)?|so|and|also|then|well|right|und|dann|naja|na\s+ja)(?![\p{L}\p{N}])[,:]?\s+""",
        // Hesitations only with their comma: German "Um 9" is a time, not a filler.
        """^(?:u+m+|u+h+|ä+h*m*|e+h*m+)[,.…]+\s*""",
        // "I need to", "we have to", "you've got to"
        """^(?:i|we|you)(?:['’]ve)?\s+(?:(?:really|still|also|just|definitely)\s+)?""" +
            """(?:need|have|got|ought|want)\s+to\s+""",
        // "I will", "we should", "you must"
        """^(?:i|we|you)\s+(?:(?:really|still|also|just|definitely)\s+)?""" +
            """(?:must|should|will|shall|can|could|might|may)\s+(?:(?:also|still|really|definitely)\s+)?""",
        """^(?:i['’]ll|we['’]ll|you['’]ll|i['’]m\s+going\s+to|i\s+am\s+going\s+to|we['’]re\s+going\s+to|""" +
            """we\s+are\s+going\s+to|i['’]m\s+gonna|gonna|gotta|let['’]?s)\s+""",
        """^(?:please\s+)?(?:remember|don['’]t\s+forget|do\s+not\s+forget|never\s+forget)\s+to\s+""",
        """^(?:make|be)\s+sure\s+to\s+""",
        """^remind\s+me\s+to\s+""",
        """^(?:note\s+to\s+self|to-?do|to\s+do|reminder|task|action\s+item)\s*[:\-–]\s*""",
        """^(?:need|have|got)\s+to\s+""",
        """^please\s+""",
        """^to\s+(?=\p{L})""",
        """^(?:nicht\s+vergessen|daran\s+denken|dran\s+denken)(?![\p{L}\p{N}])\s*[:,\-–]?\s*""",
        """^(?:erinnerung|aufgabe|notiz)\s*[:\-–]\s*""",
        """^erinnere?\s+mich(?:\s+daran)?\s*,?\s+""",
        """^bitte\s+"""
    ).map { Regex(it, IGNORE_CASE) }

    // "Ich muss die Bank anrufen" becomes "die Bank anrufen", and so does "muss ich die Bank
    // anrufen", the order German takes once a time said first is gone ("Morgen muss ich ...").
    // Only when an infinitive ends the rest: "Ich will ein Eis" stays as it is. Groups 1 and 2
    // hold the verb and the subject of the verb-first order.
    private const val GERMAN_SUBJECTS = "ich|wir|du|man"
    private const val GERMAN_MODALS = "muss|müssen|musst|sollte|sollten|solltest|soll|sollen|werde|werden|" +
        "wirst|will|wollen|möchte|möchten|kann|können|darf|dürfen"
    private val GERMAN_MODAL = Regex(
        """^(?:(?:$GERMAN_SUBJECTS)\s+(?:$GERMAN_MODALS)|($GERMAN_MODALS)\s+($GERMAN_SUBJECTS))\s+""" +
            """(?:(?:noch|auch|unbedingt|dringend|mal|bitte|endlich)\s+)*""",
        IGNORE_CASE
    )
    private val GERMAN_INFINITIVE_END = Regex("""(?:\p{L}(?:en|ern|eln)|(?<![\p{L}])(?:tun|sein))$""", IGNORE_CASE)

    // "noch mal" and "noch einmal" mean "again": one word, so that it counts when comparing.
    private val NOCH_MAL = Regex("""(?<![\p{L}\p{N}])noch\s+(?:ein)?mal(?![\p{L}\p{N}])""")

    // Words a cut text must not end on.
    private val JOINING_WORDS = setOf(
        "and", "or", "to", "the", "a", "an", "of", "for", "with",
        "und", "oder", "die", "der", "das", "den", "dem", "ein", "eine", "zu", "mit", "für"
    )

    // What a model's copied sentence may keep at an end once its time is gone, and its article.
    private val COPULAS = setOf("is", "are", "was", "ist", "sind", "war")
    private val LEADING_ARTICLES = setOf("the", "der", "die", "das")

    // A copula after one of these ends a question within the text: "Check when the meeting is".
    private val QUESTION_WORDS = setOf(
        "when", "where", "what", "who", "whom", "whose", "which", "why", "how", "if", "whether", "that",
        "wann", "wo", "was", "wer", "wen", "wem", "wessen", "welche", "welcher", "welches", "warum", "wieso",
        "weshalb", "wie", "ob", "dass", "woher", "wohin"
    )

    // A dot after these ends no sentence: "Call Dr. Smith", "z. B. Milch".
    private val ABBREVIATIONS = setOf(
        "dr", "mr", "mrs", "ms", "prof", "st", "nr", "vs", "etc", "ca", "bzw", "usw", "inkl", "evtl", "ggf", "tel",
        "str", "jr", "sr", "approx", "dept", "hr", "hrn", "fr", "frl", "dipl", "ing", "mag", "zb", "bspw"
    )

    // A day number before these is a date: "am 16. Oktober".
    private val MONTHS = setOf(
        "januar", "jänner", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober",
        "november", "dezember", "jan", "feb", "mär", "apr", "jun", "jul", "aug", "sep", "sept", "okt", "nov", "dez"
    )
    private val LAST_WORD = Regex("""[\p{L}\p{N}]+$""")
    private val FIRST_WORD = Regex("""^\s*(\p{L}+)""")
    private val LETTER_DOT_END = Regex("""(?<![\p{L}\p{N}])\p{L}\.\s*$""")
    private val LETTER_DOT_START = Regex("""^\s*\p{L}\.""")

    /**
     * Returns the todo text, or null when nothing to do is left.
     *
     * @param raw The text as the model wrote it
     * @param timePhrases The words of the todo's due time, as transcribed: one phrase, or the
     *   day and the clock time said apart ("Morgen", "um 9"). A possessive ("Friday's") stays.
     */
    fun clean(raw: String, vararg timePhrases: String?): String? {
        var text = SPACES.replace(raw, " ").trim()
        text = text.trim { it in QUOTES || it.isWhitespace() }
        text = LIST_MARK.replace(text, "")
        timePhrases.filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !POSSESSIVE.containsMatchIn(it) }
            .sortedByDescending { it.length }
            .forEach { text = withoutPhrase(text, it) }
        // After the times, whose words are as transcribed: German "um 9" is one of them.
        text = withoutStutters(withoutHesitations(text))
        text = SPACE_BEFORE_PUNCTUATION.replace(SPACES.replace(text, " ").trim(), "$1")
        text = withoutLeadIn(trimEdges(text))
        text = trimEdges(text)
        if (text.none { it.isLetterOrDigit() }) return null
        return firstWords(text).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /**
     * A todo text as the model wrote it, cleaned by [cleanAnswer].
     *
     * @property heldSentenceBreak The model wrote more than one sentence, as when it copies what
     *   was said
     */
    class Answer internal constructor(val text: String, val heldSentenceBreak: Boolean)

    /**
     * Returns the todo text the model wrote, or null when nothing to do is left. A 1B model copies
     * whole sentences, at times two of them cut off: "The dentist appointment is. I also need to
     * buy a birthday present for Anna this". Only the first sentence is kept and cleaned as [clean]
     * does. A copula ("is", "are", "was", "ist", "sind", "war") that the removal of the time words,
     * or the cut, left at an end goes, and with it the article of the sentence copied whole: "The
     * dentist appointment is on Friday at 3 pm" and "Nächsten Freitag ist das Teammeeting um halb
     * drei" become "Dentist appointment" and "Teammeeting". A copula that ends a question within
     * the text stays ("Check when the meeting is").
     *
     * @param raw The text as the model wrote it
     * @param timePhrases As for [clean]
     */
    fun cleanAnswer(raw: String, vararg timePhrases: String?): Answer? {
        val whole = SPACES.replace(raw, " ").trim { it in QUOTES || it.isWhitespace() }
        val first = firstSentence(whole)
        val sentence = first ?: whole
        val cleaned = clean(sentence, *timePhrases) ?: return null
        // The same words with their times, to tell what the time words' removal left at an end.
        val said = clean(sentence)?.split(' ').orEmpty()
        var words = cleaned.split(' ')
        var dropped = false
        if (words.size > 1 && isCopula(words.last()) && (first != null || !isCopula(said.lastOrNull())) &&
            words.dropLast(1).none { trimEdges(it).lowercase(Locale.ROOT) in QUESTION_WORDS }
        ) {
            words = words.dropLast(1)
            dropped = true
        }
        if (words.size > 1 && isCopula(words.first()) && !isCopula(said.firstOrNull())) {
            words = words.drop(1)
            dropped = true
        }
        if (dropped && words.size > 1 && trimEdges(words.first()).lowercase(Locale.ROOT) in LEADING_ARTICLES) {
            words = words.drop(1)
        }
        val text = trimEdges(words.joinToString(" "))
        if (text.none { it.isLetterOrDigit() }) return null
        return Answer(text.replaceFirstChar { it.titlecase(Locale.ROOT) }, heldSentenceBreak = first != null)
    }

    /**
     * True when the "." at [i] in [text] ends a sentence: not in "..." or "9.30", nor after "Dr" or
     * "z.B", nor with [ordinalDates] after a day number before a month or a lower-case word ("am
     * 16. Oktober", "der 3. Stock").
     */
    fun endsSentenceAt(text: String, i: Int, ordinalDates: Boolean): Boolean {
        if (text.getOrNull(i - 1) == '.' || text.getOrNull(i + 1) == '.') return false
        val next = text.getOrNull(i + 1)
        if (next != null && !next.isWhitespace() && next !in QUOTES) return false
        val word = LAST_WORD.find(text.substring(0, i))?.value ?: return true
        if (word.lowercase(Locale.ROOT) in ABBREVIATIONS) return false
        // "z.B.", "z. B.", "d. h.": a letter with a dot next to another one.
        if (word.length == 1 && word[0].isLetter() &&
            (LETTER_DOT_END.containsMatchIn(text.substring(0, i - 1)) || LETTER_DOT_START.containsMatchIn(text.substring(i + 1)))
        ) return false
        if (ordinalDates && word.length <= 2 && word.all { it.isDigit() }) {
            val following = FIRST_WORD.find(text.substring(i + 1))?.groupValues?.get(1)
            if (following != null && (following.first().isLowerCase() || following.lowercase(Locale.ROOT) in MONTHS)) {
                return false
            }
        }
        return true
    }

    /** Where in [text] the first of [key]'s words is said, or null when none is. */
    fun firstMention(text: String, key: Key): Int? =
        WORD.findAll(text)
            .firstOrNull { fold(it.value).replace('’', '\'').removeSuffix(S_ENDING) in key.words }
            ?.range?.first

    /**
     * The sentences of [source] that the todo [text] speaks of: those that share one of its
     * content words ([key], no filler or function word) of four letters or more, compared by their
     * first five, so "Heizung reparieren lassen" speaks of "Die Heizung wird repariert." A segment
     * often holds several sentences, and what one of them says is not said of the others. Empty
     * when none shares a word.
     */
    fun sentencesAbout(text: String, source: String): List<String> {
        val mine = stemsOf(text)
        if (mine.isEmpty()) return emptyList()
        return source.split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() && stemsOf(it).any(mine::contains) }
    }

    /** True when [text] holds one of the [HEDGES]: "maybe Friday", "vielleicht am Montag". */
    fun isHedged(text: String): Boolean =
        HEDGE_WORD.findAll(text.lowercase(Locale.ROOT)).any { it.value in HEDGES }

    private fun stemsOf(text: String): Set<String> =
        key(text).words.filter { it.length >= MIN_SHARED_WORD }.mapTo(mutableSetOf()) { it.take(MIN_SHARED_WORD + 1) }

    /** [text] up to its first sentence break, or null when it holds none. */
    private fun firstSentence(text: String): String? {
        for (i in text.indices) {
            val c = text[i]
            if (c !in ".!?;") continue
            if (text.substring(i + 1).none { it.isLetterOrDigit() }) return null
            val next = text.getOrNull(i + 1)
            if (next == null || !next.isWhitespace()) continue
            if (c == '.' && !endsSentenceAt(text, i, ordinalDates = true)) continue
            return text.substring(0, i).trim().takeIf { it.any(Char::isLetterOrDigit) }
        }
        return null
    }

    private fun isCopula(word: String?): Boolean =
        word != null && trimEdges(word).lowercase(Locale.ROOT) in COPULAS

    /**
     * The words two todos are compared by: case, accents, word order and filler words don't
     * count, nor does an "'s" ("Anna's" is "anna"); a possessive one names an owner, but not the
     * "'s" of "it's" or "let's".
     */
    fun key(text: String): Key {
        val said = WORD.findAll(NOCH_MAL.replace(fold(text), "nochmal")).map { it.value.replace('’', '\'') }.toList()
        val words = said.map { it.removeSuffix(S_ENDING) }
        val owners = said.filter { it.endsWith(S_ENDING) }.map { it.removeSuffix(S_ENDING) }
            .filterNot { it in STOP_WORDS || it in CONTRACTED }
        return Key(words.filterNot { it in STOP_WORDS }.toSet(), words.joinToString(" "), owners.toSet())
    }

    /**
     * True when two todo texts are near-identical: the same content words, whatever their order,
     * case, accents and filler words ("Send Anna the slides", "Send the slides to Anna"). A text
     * that adds a word says something more and is not the same ("Buy milk", "Buy milk and
     * eggs"), and neither is one with another day or "again" in it. Texts made only of filler
     * words are the same only when they read the same.
     */
    fun isDuplicate(a: Key, b: Key): Boolean {
        if (a.words.isEmpty() || b.words.isEmpty()) return a.allWords.isNotEmpty() && a.allWords == b.allWords
        return a.words == b.words
    }

    fun isDuplicate(a: String, b: String): Boolean = isDuplicate(key(a), key(b))

    /**
     * True when two texts say the same thing, an item of each kind (a to-do and an idea): near-
     * identical ([isDuplicate]), or the same words with their English endings off ([stem]), as the
     * model writes one thing as "Pay the electricity bill" and "Paying the electricity bill".
     */
    fun saysSame(a: Key, b: Key): Boolean {
        if (isDuplicate(a, b)) return true
        if (a.words.isEmpty() || b.words.isEmpty()) return false
        return a.words.mapTo(mutableSetOf(), ::stem) == b.words.mapTo(mutableSetOf(), ::stem)
    }

    /** [word] without the English ending of an -ing form, a past or a plural: "paying" is "pay", "bills" "bill". */
    internal fun stem(word: String): String = when {
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 4 && (word.endsWith("ies") || word.endsWith("ied")) -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    /**
     * True when two todo texts from one recording are the same todo said again, in as many or in
     * more words: near-identical ([isDuplicate]), or all content words of the shorter are in the
     * longer and are at least 60 % of them ("Call the bank", "Call the bank about the loan"). A
     * second thing to do ("Pay the rent", "Pay the electricity bill and the rent"), another day
     * ("the Monday meeting", "the Friday meeting"), another owner ("Feed the cat", "Feed the
     * neighbour's cat"), or "again", "not", "cancel" or "move" in the longer one make another
     * todo ("Termin beim Zahnarzt", "Termin beim Zahnarzt absagen").
     */
    fun isRepeat(a: Key, b: Key): Boolean {
        if (isDuplicate(a, b)) return true
        if (a.words.isEmpty() || b.words.isEmpty()) return false
        val (small, large) = if (a.words.size <= b.words.size) a to b else b to a
        if (!large.words.containsAll(small.words) || small.words.size * 5 < large.words.size * 3) return false
        return large.words.none { it !in small.words && it in CHANGING_WORDS } && small.owners.containsAll(large.owners)
    }

    fun isRepeat(a: String, b: String): Boolean = isRepeat(key(a), key(b))

    /**
     * True when one text's content words are all in the other's, however few they are, and the
     * longer one adds no word that makes another todo of it ([isRepeat] without its share): "Dentist"
     * and "Dentist appointment". For texts read from the same words, as when a recording is read
     * again.
     */
    fun isWithin(a: Key, b: Key): Boolean {
        if (isDuplicate(a, b)) return true
        if (a.words.isEmpty() || b.words.isEmpty()) return false
        val (small, large) = if (a.words.size <= b.words.size) a to b else b to a
        if (!large.words.containsAll(small.words)) return false
        return large.words.none { it !in small.words && it in CHANGING_WORDS } && small.owners.containsAll(large.owners)
    }

    /**
     * True when [text] says the time [phrase] as it was transcribed ("Dentist at 3" names "at
     * 3", "Zahnarzt um 15 Uhr" names "um 15 Uhr"), whatever the case, and with "Uhr", "o'clock",
     * "am" or "pm" after it. "Dentist at 3:30" does not name "at 3".
     */
    fun names(text: String, phrase: String): Boolean {
        val words = phrase.trim().split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val pattern = Regex(
            """(?<![\p{L}\p{N}])${words.joinToString("""\s+""") { Regex.escape(it) }}""" +
                """(?:\s*(?:uhr|o['’]clock|[ap]\.?m\.?))?(?![\p{L}\p{N}]|[:.,]\p{N})""",
            IGNORE_CASE
        )
        return pattern.containsMatchIn(text)
    }

    /**
     * [text] without the hesitations of speech ([HESITATION]), wherever they are and however often,
     * each with the commas around it, so "I need to, uh, call" reads "I need to call"; "um" and
     * "er" too ([AMBIGUOUS_HESITATION]) in an English text, one with an English word and no German
     * one ("Email er the landlord", not "Anruf um Mitternacht" nor "Er kommt Montag vorbei"). A
     * word in capitals is none ("Take mom to the ER", "Return the jacket to HM"), and a full stop
     * after a hesitation stays when a sentence ends there ("Buy milk, hmm. Then call Anna").
     */
    internal fun withoutHesitations(text: String): String {
        val without = HESITATION.replace(text) { hesitationOut(text, it) }
        if (looksGerman(without) || !looksEnglish(without)) return without
        return AMBIGUOUS_HESITATION.replace(without) { hesitationOut(without, it) }
    }

    /**
     * What [match], a hesitation in [text] with its commas (group 1 the hesitation, group 2 the
     * marks after it), leaves: all of it for a word in capitals; a full stop that ends the sentence
     * it ends, unless the hesitation was that sentence on its own ("Hmm. Call the bank"); else a
     * space.
     */
    private fun hesitationOut(text: String, match: MatchResult): String {
        val word = match.groupValues[1]
        if (word.length > 1 && word.all { it.isUpperCase() }) return match.value
        val before = text.substring(0, match.range.first).trimEnd()
        val endsSentence = match.groupValues[2].filterNot { it == ',' } == "."
        return if (endsSentence && before.isNotEmpty() && before.last() !in SENTENCE_ENDS) "." else " "
    }

    /**
     * [text] with a word a stammer said twice or more in a row said once: "the the" is "the". Only
     * a repeat written the same way each time, but for a capital that starts the text ("The the
     * plumber"); not "Sie sie", the polite one and another. The small words grammar puts twice in a
     * row too ([ALSO_GRAMMAR]: a relative pronoun before an article, a verb's particle before a
     * preposition) go only where no grammar does: at the start of the text, or said three times.
     */
    internal fun withoutStutters(text: String): String =
        REPEATED_WORD.replace(text) { match ->
            val said = match.value.split(SPACES)
            val lower = said.last().lowercase(Locale.ROOT)
            val atStart = text.substring(0, match.range.first).isBlank()
            val capitalStart = atStart && lower != "sie" && said[0] == said[1].replaceFirstChar { it.titlecase(Locale.ROOT) }
            val sameWay = said.drop(1).all { it == said[1] } && (said[0] == said[1] || capitalStart)
            val stutter = lower in STUTTERED || (lower in ALSO_GRAMMAR && (atStart || said.size >= 3))
            if (sameWay && stutter) said[0] else match.value
        }

    /** Whether [text] has a word only German has, or a letter only German writes. */
    private fun looksGerman(text: String): Boolean =
        text.any { it in GERMAN_LETTERS } || LanguageDetector.words(text).any { it in LanguageDetector.GERMAN_WORDS }

    /** Whether [text] has a word only English has: "the", "to", "you". */
    private fun looksEnglish(text: String): Boolean =
        LanguageDetector.words(text).any { it in LanguageDetector.ENGLISH_WORDS }

    private fun withoutPhrase(text: String, phrase: String): String {
        val words = phrase.split(SPACES).joinToString("""\s+""") { Regex.escape(it) }
        // An apostrophe after the phrase makes it a possessive, which stays ("Friday's meeting").
        val pattern = Regex(
            """(?<![\p{L}\p{N}])(?:(?:$TIME_PREPOSITIONS)\s+)?(?:(?:$TIME_DETERMINERS)\s+)?$words""" +
                """(?:\s+(?:uhr|o['’]clock))?(?![\p{L}\p{N}'’])""",
            IGNORE_CASE
        )
        return pattern.replaceFirst(text, " ")
    }

    private fun withoutLeadIn(text: String): String {
        var result = text
        // Each pass removes at most one lead-in of each kind; four passes cover any real stack.
        repeat(4) {
            val before = result
            for (leadIn in LEAD_INS) result = leadIn.replaceFirst(result, "")
            GERMAN_MODAL.find(result)?.let { modal ->
                val rest = result.substring(modal.range.last + 1)
                val (verb, subject) = modal.destructured
                result = when {
                    GERMAN_INFINITIVE_END.containsMatchIn(trimEdges(rest)) -> rest
                    // "Muss ich zum Arzt" reads as a question: the statement keeps the words.
                    verb.isNotEmpty() -> "$subject ${verb.lowercase(Locale.ROOT)} $rest"
                    else -> result
                }
            }
            if (result == before) return result
        }
        return result
    }

    private fun firstWords(text: String): String {
        val words = text.split(' ')
        if (words.size <= MAX_WORDS) return text
        val kept = words.take(MAX_WORDS).toMutableList()
        while (kept.size > 1 && trimEdges(kept.last()).lowercase(Locale.ROOT) in JOINING_WORDS) {
            kept.removeAt(kept.lastIndex)
        }
        return trimEdges(kept.joinToString(" "))
    }

    private fun trimEdges(text: String): String =
        text.trim { it in EDGE_PUNCTUATION || it in QUOTES || it.isWhitespace() }

    private fun fold(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
            .replace("ß", "ss")

    // Filler and function words in English and German, folded like the texts, left out when
    // comparing: "Ich muss noch die Bank anrufen" repeats "Bank anrufen". Words that can tell two
    // todos apart are not among them: days, parts of the day, "again", "not". Declared last: it
    // is folded with MARKS, which must exist first.
    private val STOP_WORDS: Set<String> = (
        "a an the to of for on at in by with about from into and or but my our your his her their its " +
            "me us him them it this that these those i we you he she they need needs have has had must " +
            "should will would can could shall may might please do does ll m re ve s d also just still " +
            "really some any am pm o clock oclock asap soon later " +
            "der die das den dem des ein eine einen einem einer eines und oder aber zu zum zur für mit " +
            "von vom an am um im in auf bei beim nach bis über ab vor wegen ich wir du sie er es man mich mir " +
            "uns dich dir ihm ihn ihr mein meine meinen meinem meiner unser unsere unseren dein deine " +
            "muss müssen musst sollte sollten soll sollen werde werden wird wirst will wollen möchte " +
            "möchten kann können bitte noch auch mal uhr diese diesen dieser bald später"
        ).split(' ').map(::fold).toSet()

    // Words whose "'s" is "is" or "us", not a possessive: "what's", "let's".
    private val CONTRACTED = setOf("let", "what", "there", "here", "who", "where", "how", "when", "why")

    // Hesitations, with the comma before them and the commas or dots after them: "uh", "uhm",
    // "umm", "hm", "hmm", "mhm", "äh", "ähm", "öhm", "ehm", "erm", and the same drawn out. Not
    // "em", which is "them" ("call 'em"), nor "ah" and "oh", which say something.
    private const val HESITATIONS = "u+h+|u+h+m+|u+m{2,}|h+m+|m+h+m+|ä+h+|ä+h*m+|ö+h*m+|e+h+m+|e+r+m+"
    private val HESITATION = Regex("""[,;]?\s*(?<![\p{L}\p{N}'’])($HESITATIONS)(?![\p{L}\p{N}'’])([,.…]*)""", IGNORE_CASE)

    // "um" and "er", hesitations in English, words in German: "um 9", "um ... zu", "er" (he).
    private val AMBIGUOUS_HESITATION = Regex("""[,;]?\s*(?<![\p{L}\p{N}'’])(u+m|e+r)(?![\p{L}\p{N}'’])([,.…]*)""", IGNORE_CASE)

    // What ends a sentence before a hesitation, so the full stop after it isn't one of its own.
    private const val SENTENCE_ENDS = ".!?…"

    private const val GERMAN_LETTERS = "äöüßÄÖÜ"

    // A word said twice or more in a row; IGNORE_CASE makes the repeat match whatever its case.
    private val REPEATED_WORD = Regex("""(?<![\p{L}\p{N}])(\p{L}+)(?:\s+\1)+(?![\p{L}\p{N}])""", IGNORE_CASE)

    // The small words a stammer says twice; a word said twice on purpose ("very very", "bye
    // bye", "had had") is none of them.
    private val STUTTERED = setOf(
        "the", "a", "an", "i", "we", "you", "he", "she", "it", "they", "to", "of", "and", "or", "at", "for",
        "with", "is", "was", "my", "our", "your", "will", "can",
        "des", "ein", "eine", "einen", "und", "oder", "ich", "wir", "du", "zu", "mit", "für", "auf", "ist", "im", "am"
    )

    // ...and those grammar puts twice in a row too: "die die Pakete bringen", "das Buch, das das
    // Kind liest", "ob sie sie abholen", "Log in in the morning", "Turn it on on Monday".
    private val ALSO_GRAMMAR = setOf("der", "die", "das", "den", "dem", "sie", "es", "in", "on")

    // Words that, added to a todo, make another one of it: "Call Anna" and "Do not call Anna" or
    // "Call Anna again", "Dentist appointment" and "Cancel the dentist appointment". Folded like
    // the texts; declared after MARKS for the same reason.
    private val CHANGING_WORDS: Set<String> = (
        "not no never again anymore nicht kein keine keinen keiner nie niemals nochmal wieder erneut " +
            "cancel cancels cancelled canceled cancelling canceling move moves moved reschedule rescheduled " +
            "postpone postponed absagen abgesagt absage stornieren storniert verschieben verschoben verlegen verlegt"
        ).split(' ').map(::fold).toSet()
}
