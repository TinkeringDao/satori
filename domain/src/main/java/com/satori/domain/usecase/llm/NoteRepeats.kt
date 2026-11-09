package com.satori.domain.usecase.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/**
 * A note doesn't repeat itself (0.7.3). On 2026-10-19 Gemma 3 1B, reading a 26-minute Malayalam
 * recording it mostly couldn't understand, made up a subject and wrote it again and again with
 * small changes, in two notes and in the recording's name: that a piece of software is needed, is
 * essential, is a complex task.
 *
 * - Within one note, a point that says nothing an earlier point of it doesn't is left out
 *   ([withoutRepeats]); so is an action item after an earlier action item. A piece says nothing new
 *   when every word of it that says something ([keyOf]: content words, folded, the small words and
 *   those that only weigh what is said aside, [WEAK_WORDS]: "needed", "essential", "important",
 *   "complex", "difficult", "task") is a word of the earlier one ([Key.matches]), and it has two
 *   such words, or one that is the earlier piece's only one. "Bluetooth firmware is essential"
 *   after "Bluetooth firmware is needed" goes; "Buy bread for the kids" after "Buy milk for the
 *   kids" stays, as does "Call Anna about the rent" after "Call Anna", which it says more than. The
 *   first point always stays, so this never leaves a note without one.
 * - Within one recording's notes, a later note that mostly repeats an earlier one, more than half
 *   of its pieces (its title, points and action items, of those that say something) each repeating
 *   a piece of one earlier note ([repeatsNote]): the parts of a pass among themselves, the last
 *   note after Stop after the live ones, a live note after the live notes before it. What becomes
 *   of it is [EarlierNotes]'. The merge of a pass is no later note: it says again what its parts
 *   said, which is what it is for.
 *
 * Words are compared folded, without their English endings or with a German adjective's, never one
 * as the start of another; numbers, names and abbreviations as written (the review of 0.7.3):
 * "Meeting at 10" and "Meeting at 11", "Pay 1500 euros" and "Pay 15000 euros", "Call Paul" and "Call
 * Paula", "Andreas" and "Andrea", "vitamin C" and "vitamin D", "the IT team" and "the sales team",
 * "Room A" and "Room B", "9 AM" and "9 PM" are two. A word that turns what is said round counts
 * ([NEGATIONS]: "not", "no", "never", "nicht", "kein"), and a piece with one is never a repeat of
 * one without: "The client does not want the blue logo" after "The client wants the blue logo"
 * stays, as the note would otherwise say the opposite of what was decided. So do the small words
 * of time and amount ("before", "after", "more", "vor", "nach") and the numbers in words ("one",
 * "two"). Pure Kotlin, no I/O.
 */
internal object NoteRepeats {

    /** The share of a later note's pieces that, repeating pieces of an earlier note, make it a repeat of it. */
    private const val NOTE_SHARE = 0.5

    /** The fewest letters a word keeps once a German adjective's ending is off ([matches]). */
    private const val MIN_GERMAN_STEM = 5

    /**
     * A word of a piece that says something ([keyOf]), folded: compared [exact]ly as written when
     * it is a number, an abbreviation, a word written with a capital inside a sentence (a name), or
     * a word that turns what is said round; [initial] when it starts its piece with a capital, as
     * a sentence's first word and a name do: never as a plural of another ("Andreas", "Andrea").
     */
    data class Key(val word: String, val exact: Boolean, val initial: Boolean = false) {
        /** Whether this word is [other]: the same as written, or for two words neither exact, in another form. */
        fun matches(other: Key): Boolean {
            if (word == other.word) return true
            if (exact || other.exact) return false
            val plurals = !initial && !other.initial
            return stemOf(word, plurals) == stemOf(other.word, plurals) || germanForm(word, other.word) || germanForm(other.word, word)
        }

        /** [word] without its English ending ([PromptEcho.stem]); without that of a past or an -ing form only, unless [plurals]. */
        private fun stemOf(word: String, plurals: Boolean): String = when {
            plurals -> PromptEcho.stem(word)
            word.length > 4 && word.endsWith("ied") -> word.dropLast(3) + "y"
            word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
            word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
            else -> word
        }

        /** Whether [longer] is [shorter] with a German adjective's ending ("fällige", "fällig"). */
        private fun germanForm(longer: String, shorter: String): Boolean =
            shorter.length >= MIN_GERMAN_STEM && longer.startsWith(shorter) && longer.substring(shorter.length) in GERMAN_ENDINGS
    }

    /** The words of [piece] that say something, each once ([NoteRepeats]'s description). */
    fun keyOf(piece: String): List<Key> {
        val written = TOKEN.findAll(Normalizer.normalize(piece.replace('’', '\''), Normalizer.Form.NFC))
            .map { it.value.trim('\'').removeSuffix("'s") }
            .filter { it.isNotEmpty() }
            .toList()
        return written.mapIndexedNotNull { i, raw ->
            val word = PromptEcho.wordsOf(raw).firstOrNull() ?: return@mapIndexedNotNull null
            val capitals = raw.length >= 2 && raw.any { it.isLetter() } && raw.none { it.isLowerCase() }
            val capital = raw.first().isUpperCase()
            val afterNumber = i > 0 && written[i - 1].all { it.isDigit() }
            when {
                word in NEGATIONS || word in AMOUNTS || word in NUMBER_WORDS || word.all { it.isDigit() } -> Key(word, exact = true)
                word in PromptEcho.STOP_WORDS ->
                    // "Room A", "the IT team", "9 AM", "the May rent": a small word as no small word. A
                    // title's capitals are none: "Bluetooth Firmware Is Essential".
                    if (capitals || afterNumber || (capital && i > 0 && word.length == 1 && word != "i") || (capital && word == "may")) Key(word, exact = true) else null
                isWeak(word) -> null
                else -> Key(word, exact = capitals || (capital && i > 0) || word.length == 1, initial = capital && i == 0)
            }
        }.distinctBy { it.word }
    }

    /** Whether the piece of [later] key says nothing that the piece of [earlier] key doesn't. */
    fun repeats(later: List<Key>, earlier: List<Key>): Boolean {
        if (later.isEmpty()) return false
        // What one says the other says the opposite of.
        if (later.any { it.word in NEGATIONS } != earlier.any { it.word in NEGATIONS }) return false
        if (!later.all { word -> earlier.any { word.matches(it) } }) return false
        return later.size >= 2 || earlier.size == 1
    }

    /** [pieces] without those that repeat an earlier one of them ([repeats]), in their order, and how many those were. */
    fun withoutRepeats(pieces: List<String>): Pair<List<String>, Int> {
        val kept = mutableListOf<String>()
        val keys = mutableListOf<List<Key>>()
        for (piece in pieces) {
            val key = keyOf(piece)
            if (keys.any { repeats(key, it) }) continue
            kept += piece
            keys += key
        }
        return kept to pieces.size - kept.size
    }

    /**
     * The pieces of a note, its [title], its [content]'s points (a summary or prose as one) and the
     * action items of its [tasks] ([NoteText.actionItems]), as their keys ([keyOf]); those with no
     * word that says something are left out.
     */
    fun piecesOf(title: String?, content: String, tasks: String?): List<List<Key>> =
        (listOfNotNull(title) + pointsOf(content) + NoteText.actionItems(tasks)).map(::keyOf).filter { it.isNotEmpty() }

    /** Whether the note of [pieces] ([piecesOf]) mostly repeats the note of [earlier]: more than half of its pieces each repeat one of those. */
    fun repeatsNote(pieces: List<List<Key>>, earlier: List<List<Key>>): Boolean {
        if (pieces.isEmpty() || earlier.isEmpty()) return false
        val repeated = pieces.count { piece -> earlier.any { repeats(piece, it) } }
        return repeated > pieces.size * NOTE_SHARE
    }

    /** A note's [title], [content] and [tasks] without its pieces that repeat a piece of an earlier note, and how many went ([dropped]). */
    class Left(val title: String?, val content: String, val tasks: String?, val points: Int, val actions: Int, val dropped: Int)

    /**
     * The note of [title], [content] and [tasks] without the pieces that repeat a piece of one of
     * [earlier] (the notes' [piecesOf]): its title, its points (a summary or prose as one) and its
     * action items each. What is left says what the earlier notes don't (since the review of
     * 0.7.3: a live note of a meeting that says the meeting's subject again with one new point
     * keeps the new point).
     */
    fun without(title: String?, content: String, tasks: String?, earlier: List<List<List<Key>>>): Left {
        val known = earlier.flatten()
        var dropped = 0
        // A piece of one word says nothing new when an earlier piece has it ("Installing it is a
        // complex task." after "Install it before the audit."), unless that one turns it round.
        fun said(piece: String): Boolean {
            val key = keyOf(piece)
            val repeated = key.isNotEmpty() && (
                known.any { repeats(key, it) } ||
                    (key.size == 1 && key[0].word !in NEGATIONS && known.any { earlierPiece -> earlierPiece.none { it.word in NEGATIONS } && earlierPiece.any(key[0]::matches) })
                )
            return repeated.also { if (it) dropped++ }
        }
        val keptTitle = title?.takeUnless(::said)
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val listed = lines.isNotEmpty() && lines.all { it.startsWith("- ") }
        val points = pointsOf(content).filterNot(::said)
        val keptContent = if (listed) points.joinToString("\n") { "- $it" } else points.singleOrNull()?.let { content }.orEmpty()
        val actions = NoteText.actionItems(tasks).filterNot(::said)
        val keptTasks = actions.takeIf { it.isNotEmpty() }?.let { items -> JsonArray(items.map(::JsonPrimitive)).toString() }
        return Left(keptTitle, keptContent, keptTasks, points.size, actions.size, dropped)
    }

    /** The points of a note's [content]: its lines of a Markdown list, or all of it as one (a summary, prose); none when blank. */
    private fun pointsOf(content: String): List<String> {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        return if (lines.all { it.startsWith("- ") }) lines.map { it.removePrefix("- ") } else listOf(content)
    }

    /** Whether [word] is one of [WEAK_WORDS], also with a German adjective's ending: "komplexe", "wichtigen". */
    private fun isWeak(word: String): Boolean =
        word in WEAK_WORDS || GERMAN_ENDINGS.any { word.endsWith(it) && word.dropLast(it.length) in WEAK_WORDS }

    private val GERMAN_ENDINGS = listOf("e", "en", "er", "es", "em")

    // A word as written: letters, marks, digits and apostrophes.
    private val TOKEN = Regex("""[\p{L}\p{M}\p{N}']+""")

    /** Words that turn what is said round, folded: a piece with one is never a repeat of one without. */
    private val NEGATIONS = setOf(
        "not", "no", "never", "none", "nobody", "nothing", "cannot", "can't", "don't", "doesn't", "didn't", "won't", "isn't",
        "aren't", "wasn't", "weren't", "nicht", "kein", "keine", "keinen", "keinem", "keiner", "keines", "nie", "niemals",
        "nichts", "niemand"
    )

    /** Small words of time and amount that tell two points apart, folded: "Call Anna before the meeting", "after it". */
    private val AMOUNTS = setOf("before", "after", "over", "under", "more", "less", "vor", "nach", "bis", "unter")

    /** The numbers in words, folded: "Buy one ticket" is no repeat of "Buy two tickets". */
    private val NUMBER_WORDS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "twenty", "hundred",
        "thousand", "half", "eins", "zwei", "drei", "vier", "funf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwolf",
        "zwanzig", "hundert", "tausend", "halb", "halbe"
    )

    /**
     * Words that only weigh what is said, folded: that a thing is needed, matters, is hard or is a
     * task. The 1B's repeats of one made-up subject differ in them alone; since the review of 0.7.3
     * also in "difficult", "challenging", "significant", "matters", "requiring". Not the words of a
     * list's items ("key", "main", "bread"), which tell two points apart, nor "hard" ("hard drive").
     */
    private val WEAK_WORDS = setOf(
        "need", "needs", "needed", "necessary", "necessarily", "essential", "essentially", "important", "importance",
        "crucial", "critical", "vital", "required", "require", "requires", "requiring", "requirement", "requirements",
        "complex", "complicated", "difficult", "challenging", "challenge", "significant", "significantly", "matter",
        "matters", "task", "tasks", "thing", "things", "really", "quite", "absolutely", "definitely",
        "notig", "notwendig", "benotigt", "braucht", "brauchen", "erforderlich", "wichtig", "wesentlich", "unerlasslich",
        "entscheidend", "komplex", "kompliziert", "schwierig", "aufwendig", "bedeutend", "aufgabe", "aufgaben", "sache",
        "sachen", "ding", "dinge", "wirklich"
    )
}
