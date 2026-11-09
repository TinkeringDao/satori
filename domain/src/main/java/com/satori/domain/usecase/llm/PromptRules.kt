package com.satori.domain.usecase.llm

import com.satori.domain.language.WordClasses
import java.util.Collections
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The rules a call's system prompt gives the model, which a piece of its answer must not give back
 * as if it were something said (0.7.3). On 2026-10-18 and 19, Gemma 3 1B, reading Malayalam it
 * mostly doesn't understand, wrote Satori's notes prompt back as the points of two notes of a
 * 26-minute recording and of the one note of a 143-second one ("Plain statements", "First person",
 * "One idea per point", "No filler", "Keep it as it is unless the sentence leaves no doubt"), and
 * the points of a 28-second one narrate its task (a goal, an initial and a final idea, each of
 * English notes). Until 0.7.3 a piece was only compared with the instruction lines of its user
 * message ([PromptEcho]), never with the system prompt.
 *
 * The rules are the sentences of the system prompt (for the to-dos also those of the user
 * message's fixed header) without what is no rule: the JSON lines, which show the answer's shape,
 * and what is quoted, which are examples or words not to use ("Garden shed", "remind me to call
 * the bank", "the speaker"): a real to-do may well be "Call the bank". A piece gives the rules back
 * when:
 * - it is made of rule phrases ([RULE_PHRASES]: those of the English and German prompts that are
 *   only rules, and the ways the 1B says them, "keep it concise", "no filler words", "one point per
 *   point", "fillers removed"), in any order and case: a phrase is there when all its words are, a
 *   list of the prompts ([RULE_LISTS]: "names, numbers, dates and places") when two of its words
 *   are, and every other word of the piece is a word of a phrase or list that is there, a small
 *   word, or a word that goes with a rule ([FREE_WORDS]: of style, "clear", "plain", "single", of
 *   the orders, "use", "avoid", "removed", "without", the labels of a rule, "style", "perspective",
 *   numbers, the languages, the words of the text and the medium, the parts of a note). A phrase
 *   that is one everyday word once its small words are left aside ("in English", "no lists",
 *   "Ich-Form", "at most 6 points") is there only as it is written, its small words in a row with
 *   it ([Phrase.run]): "Make a list", "Fill in the form" and "Answer the letter in German" are
 *   to-dos (the review of 0.7.3). Words are compared as written, without their English endings or
 *   with a German one ([sameWord]), never one as the start of another: "fill" is no "filler",
 *   "Germany" no "German";
 * - or it is only words of the text and the medium and says nothing of a medium, as
 *   [PromptEcho.isEcho] reads them ("Recording.", "Notes.", "This is an audio recording."), now
 *   with every call, as every call has a system prompt;
 * - or it narrates the making of the answer ([narrates]): nothing but words of a stage of a task
 *   ([STAGE_WORDS]: "goal", "initial", "final", "first", "key", "task"), of making ([MAKING_WORDS]:
 *   "provide", "creation", "completed"), the languages, the parts of a note ([NOTE_PARTS]: "idea",
 *   "point"), of instructions ([INSTRUCTION_WORDS]) and of the user message's instruction lines,
 *   and they say a stage of the text, the medium or a language ("Aim: Deliver notes in English",
 *   "First idea: Note creation"), that the text is made in a language ("Note: English notes
 *   provided", the 28-second note's point 0.7.1 stored), that a stage is done ("Task completed"),
 *   a stage of a part of a note ("Final idea"), a stage of making with nothing made ("Goal:
 *   Provide"), what was done of the text alone ("Note creation", "Notes created"), an order to
 *   make it in a language ("Deliver English notes"), or instructions ("Instructions followed",
 *   "Instruction to write in English"). What a notes app does stays ("Creates notes from
 *   recordings", "Notes are generated", "Notes are written in English or German");
 * - or at least [MIN_SHARE] of its content words, and [MIN_FOUND], are words of one rule sentence
 *   in the order the sentence has them: "Keep names, numbers, dates and places", "Leave out small
 *   talk, fillers and repeats". "Set a time for the visit" is a to-do, though its three words are
 *   words of the to-do prompt's sentence on a visit at a set time;
 * - or it holds [RUN] words in a row of one rule sentence, [RUN_CONTENT] of them content words:
 *   "Keep it as it is unless the sentence leaves no doubt".
 *
 * The system prompt is long, and a real point shares a word or two with it by chance ("Speech
 * recognition misheard the word", "Keep the names and numbers of the suppliers", "Action items for
 * the next meeting"): hence one sentence at a time, three words found at least, four of five, in
 * order, and a longer run than [PromptEcho]'s. And a piece whose words were said together is the
 * speaker's, not the prompt's ([Spoken.says]): someone who reads the rules of a prompt aloud gets
 * them as points. Together means close to each other in what the call read, not anywhere in a
 * long recording (the review of 0.7.3): an hour's meeting says "idea" and "point" somewhere. A
 * Malayalam transcript holds no English word, so for a Malayalam recording that never holds. Pure
 * Kotlin, no I/O.
 */
class PromptRules private constructor(private val sentences: List<List<String>>, private val active: Boolean) {

    /** The content words of each rule sentence, in its order. */
    private val contents: List<List<String>> = sentences.map { words -> words.filter(PromptEcho::isContent) }

    /**
     * Whether [text] gives back these rules, by the rules of this class's description.
     *
     * @param lines The instruction lines of the call's user message ([PromptEcho]), whose words a
     *   narration of the task may hold
     * @param spoken What the call read that was said, whose words said together a piece of the
     *   speaker's is made of; null for an answer read from no speech
     */
    fun echoes(text: String, lines: List<String> = emptyList(), spoken: Spoken? = null): Boolean {
        if (!active) return false
        val said = PromptEcho.wordsOf(text)
        if (said.isEmpty()) return false
        val mine = PromptEcho.contentOf(text, said)
        // Its words that are more than small words, those of the text and the medium too: said
        // together they are the speaker's ("Audio recording" of an app that makes one).
        if (spoken != null && spoken.says(said)) return false
        if (isRulePhrases(said)) return true
        if (mine.isEmpty()) return (PromptEcho.speaksOnlyOfTheText(said) && !PromptEcho.isOrder(said, text)) || sentences.any { sharesRun(said, it) }
        if (narrates(said, mine, lines, text)) return true
        val needed = max(MIN_FOUND, ceil(mine.size * MIN_SHARE).toInt())
        if (contents.any { rule -> inOrder(mine, rule) >= needed }) return true
        return sentences.any { sharesRun(said, it) }
    }

    /**
     * Whether [said] is made of rule phrases only: a phrase of [RULE_PHRASES] is there ([Phrase.isIn]),
     * or a list of [RULE_LISTS] with two of its words, and each of the other words of [said] that
     * are more than small words is a word of one that is there or of [FREE_WORDS].
     */
    private fun isRulePhrases(said: List<String>): Boolean {
        val words = said.filter(::isPhraseWord)
        if (words.isEmpty()) return false
        val there = RULE_PHRASES.filter { it.isIn(words, said) }.map { it.words } +
            RULE_LISTS.filter { list -> list.count { part -> words.any { sameWord(it, part) } } >= 2 }
        if (there.isEmpty()) return false
        return words.all { word -> isFree(word) || there.any { phrase -> phrase.any { sameWord(word, it) } } }
    }

    /**
     * Whether [said], whose content words are [mine], narrates the making of the answer, by the
     * rules of this class's description: nothing but words of the task, and what they say is a
     * stage of the text, the medium or a language, the text made in a language, a stage done, a
     * stage of a part of a note, or instructions followed. A label of [LABEL_WORDS] before a colon
     * ("Topic: voice notes", [text]'s first word) is a stage too; the word alone is none ("Topic in
     * English").
     */
    private fun narrates(said: List<String>, mine: List<String>, lines: List<String>, text: String): Boolean {
        val instructed = lines.flatMap(PromptEcho::wordsOf)
        val label = LABEL.find(text)?.groupValues?.get(1)?.let { PromptEcho.wordsOf(it).firstOrNull() }?.takeIf { it in LABEL_WORDS }
        val ofTheTask = mine.all { word ->
            word in STAGE_WORDS || word in MAKING_WORDS || word in LANGUAGE_NAMES || word in NOTE_PARTS || word in INSTRUCTION_WORDS ||
                word == label || instructed.any { PromptEcho.same(word, it) }
        }
        if (!ofTheTask) return false
        val stage = mine.any { it in STAGE_WORDS } || label != null
        val done = mine.any { it in DONE_WORDS }
        val instruction = mine.any { it in INSTRUCTION_WORDS }
        val ofTheText = said.any { it in PromptEcho.TEXT_WORDS || it in PromptEcho.MEDIUM_WORDS || it in LANGUAGE_NAMES }
        val language = said.any { it in LANGUAGE_NAMES }
        return (stage && ofTheText) ||
            (done && language) ||
            (stage && done) ||
            (stage && mine.any { it in NOTE_PARTS } && mine.all { it in STAGE_WORDS || it in NOTE_PARTS }) ||
            (stage && mine.any { it in MAKING_WORDS } && mine.all { it in STAGE_WORDS || it in MAKING_WORDS }) ||
            (instruction && (ofTheText || mine.all { it in INSTRUCTION_WORDS || it in DONE_WORDS })) ||
            // "Note creation", "Notes created": what was done, said of no subject; "Notes are generated" says what an app does.
            (done && ofTheText && mine.all { it in DONE_WORDS || it in MAKING_WORDS } && said.none { it in BE_WORDS }) ||
            // "Deliver English notes", "Notizen auf Deutsch liefern": an order to make the text, and its language.
            (language && mine.any { it in MAKING_ORDERS })
    }

    /** Whether [said] holds [RUN] words in a row of [rule], [RUN_CONTENT] of them content words. */
    private fun sharesRun(said: List<String>, rule: List<String>): Boolean {
        if (said.size < RUN || rule.size < RUN) return false
        return (0..said.size - RUN).any { start ->
            val run = said.subList(start, start + RUN)
            run.count(PromptEcho::isContent) >= RUN_CONTENT && Collections.indexOfSubList(rule, run) >= 0
        }
    }

    /**
     * The words of what a call read that was said, with where each stands, looked up as written,
     * without their English endings, with a German ending or as the start of another
     * ([PromptEcho.same]): once per call, as a transcript holds thousands of words.
     */
    class Spoken(text: String) {
        private val words: List<String> = PromptEcho.wordsOf(text)

        // Where each word stands, by its first letters and those of its stem: a word that is
        // another by PromptEcho.same or a German ending starts as the other does.
        private val at: Map<String, List<Int>> = HashMap<String, MutableList<Int>>().also { index ->
            words.forEachIndexed { i, word -> keysOf(word).forEach { index.getOrPut(it) { mutableListOf() } += i } }
        }

        /** Whether [word] was said, as written or in another form ("statements" for "statement", "einfacher" for "einfache"). */
        fun has(word: String): Boolean = placesOf(word).isNotEmpty()

        /**
         * Whether the words of [said] (a piece's words) that are more than small words were said
         * together: each of them within [WINDOW_PER_WORD] words per word, and [WINDOW_EXTRA] more,
         * of where the others were said, so "plain statements, one idea per point" was said in
         * one breath. A piece of one such word was said with its small words in a row ("In
         * English", "At most 6 points"), or alone anywhere when it has none ("Recording.").
         */
        fun says(said: List<String>): Boolean {
            val words = said.filter(::isPhraseWord).distinct()
            if (words.isEmpty()) return false
            if (words.size == 1) return if (said.size == 1) has(said.single()) else saysInARow(said)
            val places = words.map(::placesOf)
            if (places.any { it.isEmpty() }) return false
            val window = WINDOW_PER_WORD * words.size + WINDOW_EXTRA
            return places.minBy { it.size }.any { place -> places.all { found -> found.any { abs(it - place) <= window } } }
        }

        /** Whether [said] was said word for word, numbers aside: "at most six points" for "at most 6 points". */
        private fun saysInARow(said: List<String>): Boolean =
            placesOf(said.first()).any { start ->
                start + said.size <= words.size && said.indices.all { i -> sameSaid(said[i], words[start + i]) }
            }

        private fun sameSaid(word: String, spoken: String): Boolean =
            if (isNumber(word)) isNumber(spoken) else PromptEcho.same(word, spoken) || sameGermanStem(word, spoken)

        /** Where [word] was said; a number, wherever one was ("6" for "six"). */
        private fun placesOf(word: String): List<Int> =
            if (isNumber(word)) {
                words.indices.filter { isNumber(words[it]) }
            } else {
                keysOf(word).flatMap { at[it].orEmpty() }.distinct().filter { sameSaid(word, words[it]) }
            }

        private fun keysOf(word: String): Set<String> = setOf(word.take(KEY), PromptEcho.stem(word).take(KEY))
    }

    /**
     * One phrase of [RULE_PHRASES]: its [words] that are more than small words ([isPhraseWord]),
     * and when it is one everyday word once they are aside, all its words as written ([run]), which
     * must stand in a row in a piece for the phrase to be there: "in English", "no lists".
     */
    private class Phrase(val words: List<String>, val run: List<String>?) {
        /** Whether this phrase is in a piece whose words are [said], of which [words] are more than small words. */
        fun isIn(words: List<String>, said: List<String>): Boolean {
            if (run == null) return this.words.all { part -> words.any { sameWord(it, part) } }
            if (said.size < run.size) return false
            return (0..said.size - run.size).any { start -> run.indices.all { i -> sameToken(said[start + i], run[i]) } }
        }

        private fun sameToken(word: String, part: String): Boolean = if (isNumber(part)) isNumber(word) else sameWord(word, part)
    }

    companion object {
        /** The share of a piece's content words that, found in one rule sentence, make it an echo of it. */
        const val MIN_SHARE = 0.8

        /** Of those, the fewest: a piece of one or two words is held to the phrases alone. */
        const val MIN_FOUND = 3

        /** Words in a row of one rule sentence that make a piece an echo of it, whatever else it says. */
        const val RUN = 5

        /** Of those, the content words. */
        const val RUN_CONTENT = 3

        /** The words a piece's word may stand from the others per word of it, and more, to have been said together ([Spoken.says]). */
        const val WINDOW_PER_WORD = 2
        const val WINDOW_EXTRA = 2

        /** The letters a word is looked up by in [Spoken]. */
        private const val KEY = 4

        /** No rules: nothing gives them back. For a call whose system prompt isn't known. */
        val NONE = PromptRules(emptyList(), active = false)

        /**
         * The rules of no prompt in particular: the rule phrases, the words of the medium alone and
         * the narration of a task, for a text whose prompt is not known.
         */
        val PHRASES = PromptRules(emptyList(), active = true)

        /**
         * The rules of [prompts]: a call's system prompt, and for the to-dos the fixed text of its
         * header ([com.satori.domain.provider.ResourceProvider.getTodoHeaderTemplate]), whose
         * placeholders ("{noted}") are left out. The rule phrases hold whatever the prompts are.
         */
        fun of(vararg prompts: String?): PromptRules =
            PromptRules(prompts.filterNotNull().flatMap(::sentencesOf).map(PromptEcho::wordsOf).filter { it.isNotEmpty() }, active = true)

        /** The sentences of [prompt]: its lines without the JSON ones, without what is quoted, cut at each sentence's end. */
        private fun sentencesOf(prompt: String): List<String> =
            prompt.lines()
                .map { PLACEHOLDER.replace(it, " ") }
                .filterNot { '{' in it }
                .map { QUOTED.replace(it, " ") }
                .flatMap { it.split(SENTENCE_END) }
                .map { it.trim() }
                .filter { line -> line.any { it.isLetter() } }

        // Both braces escaped: Android's regex engine (ICU) rejects a "}" that closes no quantifier,
        // where the JVM's tests read it as a letter. 0.7.3 had it bare, and this class's start
        // failed on the phone with every AI call it checks (0.7.4).
        private val PLACEHOLDER = Regex("""\{[a-z_]+\}""")
        private val QUOTED = Regex("\"[^\"]*\"|“[^”]*”|„[^“”]*[“”]|«[^»]*»")
        private val SENTENCE_END = Regex("""[.!?:;](?=\s|$)""")

        /** A word of a phrase: two letters or more, not a small word ([PromptEcho.STOP_WORDS]). */
        private fun isPhraseWord(word: String): Boolean =
            word.length >= 2 && word.any { it.isLetter() } && word !in PromptEcho.STOP_WORDS

        /** A number, in digits or in words: "6", "six", "sechs". */
        private fun isNumber(word: String): Boolean = word.all { it.isDigit() } || word in NUMBER_WORDS

        /**
         * Whether two folded words of a piece and a phrase are one: the same, the same without
         * their English endings ([PromptEcho.stem]), or the same with a German one off
         * ([sameGermanStem]: "schlichten", "schlichte", "schlicht"); never one as the start of
         * another, so "fill" is no "filler", "Germany" no "German", "formal" no "Form".
         */
        private fun sameWord(a: String, b: String): Boolean =
            a == b || PromptEcho.stem(a) == PromptEcho.stem(b) || sameGermanStem(a, b)

        /** The fewest letters a word keeps once its German ending is off ([germanStems]). */
        private const val MIN_GERMAN_STEM = 5

        /** Whether [a] and [b] are one word with German endings ([germanStems]): "verwenden", "verwendet". */
        private fun sameGermanStem(a: String, b: String): Boolean {
            val stems = germanStems(a)
            return germanStems(b).any { it in stems }
        }

        /**
         * [word] as written and without each German ending it has ("-en", "-er", "-et", "-e", ...),
         * when [MIN_GERMAN_STEM] letters are left: "schlichte" is "schlichte" and "schlicht",
         * "schlicht" is "schlicht" and "schlich".
         */
        private fun germanStems(word: String): Set<String> =
            GERMAN_ENDINGS.filter { word.endsWith(it) && word.length - it.length >= MIN_GERMAN_STEM }.mapTo(hashSetOf(word)) { word.dropLast(it.length) }

        private val GERMAN_ENDINGS = listOf("en", "er", "es", "em", "et", "e", "n", "s", "t")

        /** Whether [word] goes with a rule phrase without making one ([FREE_WORDS]), also in another English form. */
        private fun isFree(word: String): Boolean = word in FREE_WORDS || PromptEcho.stem(word) in FREE_STEMS

        /** [phrases] as their words ([isPhraseWord]). */
        private fun wordsOfAll(vararg phrases: String): List<List<String>> =
            phrases.map { phrase -> PromptEcho.wordsOf(phrase).filter(::isPhraseWord) }.filter { it.isNotEmpty() }

        /**
         * [phrases] as [Phrase]s: one of one everyday word, once its small words are aside, holds
         * all its words as written ([Phrase.run]); one of a word that is a rule alone
         * ([RULE_ONLY_WORDS]: "concise", "Füllwörter") doesn't.
         */
        private fun phrasesOf(vararg phrases: String): List<Phrase> =
            phrases.mapNotNull { phrase ->
                val all = PromptEcho.wordsOf(phrase)
                // Each once: "one point per point" is one word, "point", and is there only as written.
                val words = all.filter(::isPhraseWord).distinct()
                when {
                    words.isEmpty() -> null
                    words.size == 1 && words.single() !in RULE_ONLY_WORDS -> Phrase(words, all)
                    else -> Phrase(words, null)
                }
            }.distinctBy { it.words to it.run?.map { if (isNumber(it)) "#" else it } }

        /** The numbers in words, folded, English and German: a phrase's "6" is any of them ([isNumber]). */
        private val NUMBER_WORDS = setOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "twenty",
            "eins", "zwei", "drei", "vier", "funf", "sechs", "sieben", "acht", "neun", "zehn", "zwanzig"
        )

        /** The names of the languages a call reads and writes, folded, as the instructions give them. */
        private val LANGUAGE_NAMES = setOf(
            "english", "german", "malayalam", "englisch", "englische", "englischer", "englischen", "deutsch", "deutsche",
            "deutscher", "deutschen"
        )

        /** The names of the parts of a note, folded, which the 1B numbers in its narration ("Final idea: ..."). */
        private val NOTE_PARTS = setOf(
            "idea", "ideas", "point", "points", "title", "overview", "idee", "ideen", "punkt", "punkte", "gedanke", "gedanken",
            "titel", "ubersicht"
        )

        /**
         * Words of a stage of a task, folded: what a narration of the making of the answer starts
         * from ([narrates]), since the review of 0.7.3 also the ordinals and labels the 1B numbers
         * its narration with ("First idea", "Key idea", "Next step").
         */
        private val STAGE_WORDS = setOf(
            "goal", "goals", "aim", "purpose", "objective", "objectives", "initial", "final", "output", "outcome", "result",
            "results", "task", "step", "summary", "first", "last", "second", "next", "main", "key",
            "ziel", "ziele", "zweck", "ergebnis", "ergebnisse", "ausgabe", "endergebnis", "endgultig", "endgultige", "anfanglich",
            "anfangliche", "aufgabe", "schritt", "zusammenfassung", "erste", "erster", "ersten", "letzte", "letzter", "letzten",
            "zweite", "zweiter", "nachste", "nachster"
        )

        /** Words that, as a label before a colon, start a narration ([narrates]): "Topic: voice notes", "Kontext: Sprachnotiz". */
        private val LABEL_WORDS = setOf("context", "topic", "subject", "overview", "thema", "kontext", "betreff", "ubersicht")

        // A first word with a colon after it: "Topic: ...", "Übersicht: ...". Not \W, which the JVM
        // reads as ASCII only and so took the "Ü" of "Übersicht" before it ([WordClasses]).
        private val LABEL = Regex("""^${WordClasses.NOT_A_WORD_CHAR}*([\p{L}\p{M}']+)\s*:""")

        /** Words of making, folded: "Deliver notes in English", "Transcript creation" ([narrates]). */
        private val MAKING_WORDS = setOf(
            "provide", "provided", "provides", "providing", "create", "created", "creates", "creating", "creation",
            "generate", "generated", "generates", "generation", "produce", "produced", "produces", "deliver", "delivered",
            "transcribe", "transcribes", "complete", "completed", "followed", "following", "taking",
            "erstellen", "erstellt", "erstellung", "bereitstellen", "bereitgestellt", "liefern", "liefert", "geliefert",
            "erzeugen", "erzeugt", "transkribieren", "befolgt", "erledigt", "abgeschlossen"
        )

        /** Of [MAKING_WORDS] and [INSTRUCTION_WORDS], those that say the making is done: "provided", "creation", "completed" ([narrates]). */
        private val DONE_WORDS = setOf(
            "provided", "created", "creation", "generated", "generation", "produced", "delivered", "completed", "followed", "following",
            "instructed", "erstellt", "erstellung", "bereitgestellt", "geliefert", "erzeugt", "befolgt", "erledigt",
            "abgeschlossen", "angewiesen"
        )

        /**
         * Of [MAKING_WORDS], the orders and the -ing form, not what an app does ("creates",
         * "generates"): "Deliver English notes", "Note taking in English" ([narrates]).
         */
        private val MAKING_ORDERS = setOf(
            "provide", "create", "generate", "produce", "deliver", "taking", "erstellen", "bereitstellen", "liefern", "erzeugen"
        )

        /** The verb "to be", folded: "Notes are generated" says what an app does, not what was done ([narrates]). */
        private val BE_WORDS = setOf("is", "are", "was", "were", "be", "been", "being", "ist", "sind", "wird", "werden", "wurde", "wurden")

        /** Words of instructions, folded: "Instructions followed", "As instructed", "Wie angewiesen" ([narrates]). */
        private val INSTRUCTION_WORDS = setOf(
            "instruction", "instructions", "instructed", "prompt", "prompts", "anweisung", "anweisungen", "angewiesen"
        )

        /**
         * Words that go with a rule phrase without making one, folded: of style ("Keep it short
         * and clear", "Single idea per point"), the orders a rule is given with and what they say
         * was done ("Use", "Avoid", "Fillers removed", "Without filler words"), the labels of a
         * rule ("Style: plain statements", "Perspective: first person"), numbers ("Maximum six
         * points"), the languages ("Plain statements in English"), the words of the text and the
         * medium ([PromptEcho.MEDIUM_WORDS], and of [PromptEcho.TEXT_WORDS] those that name no
         * thing to do something with: "letter", "answer", "translate", "speaker" do) and the parts
         * of a note ([NOTE_PARTS]). Not "brief", which is a German letter ("Den Brief schreiben").
         */
        private val FREE_WORDS: Set<String> = setOf(
            "clear", "clearly", "simple", "simply", "short", "shorter", "plain", "precise", "accurate", "neutral",
            "factual", "objective", "relevant", "possible", "always", "yourself", "please", "maximum", "minimum", "max",
            "single", "extra", "free", "none", "without", "don't", "dont", "doesn't", "action", "items", "aktionen",
            "use", "avoid", "keep", "stick", "follow", "include", "give", "make", "leave", "left", "add", "put", "stay", "apply",
            "removed", "omitted", "avoided", "kept", "followed", "applied", "included",
            "style", "tone", "format", "perspective", "view",
            "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "twenty", "under",
            "klar", "einfach", "kurz", "knapp", "genau", "sachlich", "immer", "bitte", "maximal", "hochstens",
            "verwenden", "vermeiden", "halten", "bleiben", "lassen", "geben", "weglassen", "nennen", "festhalten",
            "entfernt", "vermieden", "verwendet", "weggelassen", "befolgt", "beibehalten", "behalten", "stil", "ton", "perspektive",
            "zwei", "drei", "vier", "funf", "sechs", "sieben", "acht", "neun", "zehn", "zwanzig",
            "text", "texts", "texte", "language", "languages", "sprache", "sprachen", "word", "words", "wort", "worte", "worter",
            "written", "write", "writes", "writing", "wrote", "geschrieben", "schreiben", "schreibt", "used", "uses", "using",
            "say", "says", "said", "saying", "gesagt", "sagt", "sagen", "spoken", "gesprochen"
        ) + LANGUAGE_NAMES + PromptEcho.MEDIUM_WORDS + NOTE_PARTS

        private val FREE_STEMS: Set<String> = FREE_WORDS.mapTo(HashSet(), PromptEcho::stem)

        /**
         * The words of a phrase that are a rule alone, folded, so a phrase of one of them is there
         * as a word of a piece in any place ("Fillers removed", "Without repetition"): no subject
         * is named by them alone.
         */
        private val RULE_ONLY_WORDS = setOf(
            "concise", "conciseness", "brevity", "clarity", "json", "filler", "fillers", "repetition", "repetitions", "repeats",
            "zweifelsfrei", "betreffzeile", "pragnant", "smalltalk", "fullworter", "fuellwoerter", "wiederholung", "wiederholungen",
            "schlusspunkt", "perspektive"
        )

        /**
         * The phrases of the notes, name, to-do and map prompts, English and German, that are only
         * rules, and the ways the 1B says them back ("keep it concise", "no filler words", "one
         * point per point", "no repetition", and since the review of 0.7.3 "1st person", "short
         * and clear", "nothing extra", "kurze Notizen"). A word that also names a subject alone
         * ("names", "dates", "notes", "keep", "speech recognition") makes no phrase of its own.
         */
        private val RULE_PHRASES: List<Phrase> = phrasesOf(
            // The notes prompts (prompt_simple_listening, prompt_short_meeting, prompt_long_meeting)
            "one idea per point", "one point per point", "one idea per line", "one idea per bullet", "bullet points",
            "plain statements", "plain statement", "plain language", "first person", "1st person", "as instructions",
            "write down what was said as your own notes", "never report on what was said", "no small talk",
            "no filler", "no filler words", "no repeats", "no repetition", "avoid repetition", "do not repeat", "don't repeat",
            "add nothing", "do not add anything", "nothing that was not said", "nothing extra", "stick to the facts", "one idea each",
            "no reporting", "empty list", "do not start with the",
            "leaves no doubt", "which word was meant", "at most 6 points", "at most 8 points", "max 6 points", "maximum 6 points",
            "one or two points", "fewer than 20 words", "the subject of the points", "2 to 6 words", "the title in 2 to 6 words",
            "subject line of an email", "subject line", "email subject", "title of a story", "a short voice note needs only one or two",
            "starting with a verb", "start with a verb", "leave the list empty", "leave out details", "details left out",
            "no details", "keep it concise", "be concise", "concise", "conciseness", "brevity", "clarity", "keep it short",
            "keep it brief", "short notes", "short sentences", "simple sentences", "keep it simple", "clear and simple",
            "short and clear", "clear and concise", "write what is said about the subject", "in english", "in german", "json",
            "json format", "answer with this json",
            // The name's (prompt_session_title)
            "2 to 5 words", "title alone", "no quotes", "no full stop",
            // The to-dos' (prompt_todo_extraction) and their header's
            "never make up a time", "at most 10 words", "without the time", "names the change",
            // The map's (prompt_mind_topics, prompt_mind_summary)
            "1 to 4 words", "most important first", "only words that occur in the text", "use only what the text says",
            "use only what the lines say", "the person's own notes", "write them as the person's own notes",
            "2 to 4 plain sentences", "plain sentences", "never report on the recordings", "keep times as they were said",
            "work out no dates", "the newest one counts", "no lists", "no headings",
            // German (values-de)
            "ein gedanke pro punkt", "ein gedanke je punkt", "ein punkt pro punkt", "schlichte aussage", "schlichte aussagen", "schlichte sprache",
            "einfache sprache", "ich-form", "ich-perspektive", "erste person", "als anweisung", "eigene notizen",
            "das gesagte festhalten", "nur das gesagte", "nie über das gesagte berichten", "keine füllwörter", "keine fuellwoerter", "kein smalltalk",
            "keine wiederholungen", "keine wiederholung", "nicht wiederholen", "nichts hinzufügen", "nichts, was nicht gesagt wurde",
            "nichts hinzugefügt", "nichts erfinden", "zweifelsfrei", "höchstens 6 punkte", "höchstens 8 punkte", "maximal 6 punkte",
            "weniger als 20 wörtern", "unter 20 wörtern", "das thema der punkte", "2 bis 6 wörtern", "betreffzeile", "betreffzeile einer e-mail",
            "titel einer geschichte", "verb im infinitiv am ende", "die liste leer lassen", "einzelheiten weglassen",
            "kurz und knapp", "kurz und klar", "kurz halten", "knapp halten", "knapp formuliert", "kurze notizen", "kurze sätze",
            "prägnant", "auf deutsch", "auf englisch", "2 bis 5 wörtern", "nur den titel", "ohne anführungszeichen",
            "ohne schlusspunkt", "nie eine zeit erfinden", "höchstens 10 wörter", "ohne die zeit", "nennt die änderung",
            "1 bis 4 wörtern", "das wichtigste zuerst", "nur verwenden, was im text steht", "nur verwenden, was in den zeilen steht",
            "eigene notizen der person", "2 bis 4 schlichte sätze", "schlichte sätze", "nie über die aufnahmen berichten",
            "zeitangaben so lassen, wie sie gesagt wurden", "keine daten ausrechnen", "die neueste gilt",
            "keine listen", "keine überschriften"
        )

        /**
         * The lists of the prompts, of which any two words are a rule said back ("Facts, numbers,
         * plans", "Dates and places", "Summary and analysis"): what to keep or write, what to leave
         * out, the words a name must not use. Without the verb they are kept or left out with, so
         * "Keep the dates" is no list of two, and without the words of the medium, which a subject
         * may be made of ("Audio recording and transcription").
         */
        private val RULE_LISTS: List<List<String>> = wordsOfAll(
            "names, numbers, dates and places", "decisions, facts, numbers, plans and open questions",
            "the main topics, the decisions and what was agreed", "small talk, fillers and repeats",
            "people, places and things", "summary, analysis or request",
            "namen, zahlen, daten und orte", "entscheidungen, fakten, zahlen, pläne und offene fragen",
            "die hauptthemen, die entscheidungen und das vereinbarte", "smalltalk, füllwörter und wiederholungen",
            "personen, orte und dinge", "zusammenfassung, analyse und anfrage"
        )

        /** How many of [mine], in their order, are words of [rule] in its order: the longest such run of them, gaps allowed. */
        private fun inOrder(mine: List<String>, rule: List<String>): Int {
            if (mine.isEmpty() || rule.isEmpty()) return 0
            var previous = IntArray(rule.size + 1)
            for (word in mine) {
                val current = IntArray(rule.size + 1)
                for (j in rule.indices) {
                    current[j + 1] = if (PromptEcho.same(word, rule[j])) previous[j] + 1 else maxOf(previous[j + 1], current[j])
                }
                previous = current
            }
            return previous[rule.size]
        }
    }
}
