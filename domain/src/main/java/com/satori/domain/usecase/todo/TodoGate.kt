package com.satori.domain.usecase.todo

import com.satori.domain.language.LanguageDetector
import com.satori.domain.language.WordClasses
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoKind
import java.util.Locale

/**
 * The check a to-do the model found must pass, because the small on-device model often copies a
 * sentence of the transcript as a "to-do", and takes ideas for to-dos. On the phone it turned a
 * diary entry into seven to-dos such as "Yeah", "It had been" and "Um after hiking for hours and
 * hours", and feedback on the app ("there should be an option to ...") into open to-dos.
 *
 * The rules, the first that applies decides ([verdict]); "the sentences about it" are those of its
 * segments that share a content word with it ([TodoTexts.sentencesAbout]), or all of them when none
 * does, as when the model said it in other words:
 * 1. A text that is a question, filler or a fragment (it ends on a word that needs more after it)
 *    is dropped.
 * 2. A strong cue in the sentences about it keeps it open, with the capture the cue says
 *    ([cueCapture]): EXPLICIT for a note or a request to be reminded ("remind me", "don't
 *    forget", "note to self", "make sure to", "erinnere mich", "nicht vergessen", "denk daran"),
 *    COMMITMENT for a plan that must be ("I need to", "I have to", "I must", "ich muss"). Only
 *    what the speaker said counts: "Don't forget the keys", the model's words for "The keys are
 *    on the hall table", says no cue.
 * 3. An idea cue in the sentences about it ([IdeaCues]: "there should be", "it would be good",
 *    "man könnte"), with no first-person cue of any strength, makes it an idea. Not for a to-do
 *    with a clock time, nor an appointment with a time ("The plumber comes at 8, it should be
 *    fixed then"): an idea has no time, and these would lose theirs and their reminder. Nor for a
 *    sentence the model gave back as a task, idea phrase and all ("Yesterday we walked by the
 *    lake and it would be nice to go hiking again"), which rule 6 drops: an idea names the change
 *    ("Option to delete old recordings").
 * 4. So does the model calling it an idea.
 * 5. A weak cue ("I'll", "I will", "I want to", "I should", "I'm going to", "ich werde", "ich will",
 *    "ich möchte", "ich sollte"), a strong cue said under a condition ("If it rains, I need to
 *    close the windows"), a sentence said as a note of a thing to do ([saysToDo]) or an
 *    appointment with a clock time keeps it as it is, with the model's capture: open only when
 *    that is explicit or a commitment, as the owner decided.
 * 6. A copy of its sentence without such a cue or a bare noun is dropped.
 * 7. Anything else becomes a suggestion to keep or dismiss. So does one whose only cue is a bare
 *    noun ("to do", "todo", "Aufgabe"), whatever the model's capture: "one of the to-dos to wash
 *    the car was missed" speaks of a to-do; it is none.
 *
 * A cue counts only where it is said as a plan or a request ([hasFirstPersonCue]). Not in a
 * question ("Do I need to call the bank?"; "Can you remind me to ...?" asks for a reminder),
 * denied or doubted ("I must not", "Ich muss morgen nicht ...", "I don't think I need to"), with
 * what is done ("already", "erledigt"), as talk ("I have to say the hotel was lovely", "Ich muss
 * sagen, ..."), nor as part of an idea ("It would be nice if the app could remind me to ...").
 * Such a cue is none, for every rule. Small words don't break a cue ("I also need to"), and its
 * clause runs between commas, semicolons, colons and dashes.
 *
 * At most [MAX_SUGGESTIONS] suggestions are kept per recording ([ExtractTodosUseCase]).
 *
 * The cues are English and German. A to-do read from what was said in another language is written
 * in the language of the call (a Malayalam recording's in English, a Translate recording's in its
 * target): its sentences are found by the words the model copied from its segment ([about]), and a
 * source in Malayalam script, which no cue can be read in, leaves the model's capture to decide, as
 * rule 5 does: to-dos of a Malayalam recording come from the model alone, past rules 1 and 4.
 * Before that, [ExtractTodosUseCase] keeps only those whose own words name an action
 * ([namesAction]) and, from the small model, whose Malayalam asks for them or promises them
 * ([MalayalamTodoCues]).
 *
 * [ExtractTodosUseCase] applies it to the model's to-dos; those [ExplicitTodoSpotter] finds follow
 * rules of their own and pass as they are. [RecheckFoundTodosUseCase] puts the to-dos found before
 * the check existed through it once.
 */
object TodoGate {

    /** Most suggestions one recording may add. */
    const val MAX_SUGGESTIONS = 3

    /**
     * What becomes of a to-do: dropped, kept as a suggestion, kept as it is (open with a strong cue
     * or a capture that is explicit or a commitment, else a suggestion), or made an idea.
     */
    enum class Verdict { DROP, SUGGEST, OPEN, IDEA }

    /** Which rule a [Verdict.DROP] comes from ([dropReason]), for the field log. */
    enum class DropReason {
        /** Rule 1: the text asks a question ("Call the bank?"). */
        QUESTION,

        /** Rule 1: fewer than two words besides fillers ("Yeah", "Um okay"), and no appointment. */
        TOO_SHORT,

        /** Rule 1: it ends on a word that needs more after it ("It had been", "Buy the"). */
        CUT_OFF,

        /** Rule 6: a copy of its sentence without a cue or a bare noun. */
        UNCUED_COPY
    }

    /** The verdict for [todo] said in [source]. */
    fun verdict(todo: Todo, source: String): Verdict =
        verdict(todo.text, appointment = todo.kind == TodoKind.APPOINTMENT, timed = todo.dueHasTime, source = source)

    /**
     * Why [verdict] drops the to-do whose text is [text], with the same arguments; null when it
     * keeps it (as a suggestion, open or as an idea).
     */
    fun dropReason(
        text: String,
        appointment: Boolean,
        timed: Boolean,
        source: String,
        idea: Boolean = false,
        about: String = text
    ): DropReason? = judge(text, appointment, timed, source, idea, about).second

    /**
     * The verdict for a to-do whose text is [text], said in [source], by the rules above. An
     * [appointment] has a time and may be one word ("Dentist", "Teammeeting"), and one with a
     * clock time ([timed]) needs no cue. [idea]: the model called it an idea. [about] finds the
     * sentences of [source] the to-do is about: its text, or the words the model copied for one
     * written in another language than [source].
     */
    fun verdict(
        text: String,
        appointment: Boolean,
        timed: Boolean,
        source: String,
        idea: Boolean = false,
        about: String = text
    ): Verdict = judge(text, appointment, timed, source, idea, about).first

    /** The verdict of [verdict]'s rules, and for a drop the rule's reason ([dropReason]). */
    private fun judge(
        text: String,
        appointment: Boolean,
        timed: Boolean,
        source: String,
        idea: Boolean,
        about: String
    ): Pair<Verdict, DropReason?> {
        val trimmed = text.trim()
        if (trimmed.contains('?')) return Verdict.DROP to DropReason.QUESTION
        val words = words(trimmed)
        val content = words.dropWhile { it in FILLERS }
        if (content.isEmpty() || (content.size < MIN_WORDS && !appointment)) return Verdict.DROP to DropReason.TOO_SHORT
        if (content.last() in DANGLING) return Verdict.DROP to DropReason.CUT_OFF
        // No cue can be read in Malayalam: the model's capture decides, as for rule 5.
        if (LanguageDetector.isMalayalam(source)) return (if (idea) Verdict.IDEA else Verdict.OPEN) to null
        val said = sentencesAbout(about.trim(), source)
        if (strongCue(said) != null) return Verdict.OPEN to null
        val firstPerson = hasFirstPersonCue(said) || hasFirstPersonCue(trimmed)
        val cued = firstPerson || saysToDo(said) || (appointment && timed)
        val noun = says(said, NOUN_CUES) || says(trimmed, NOUN_CUES)
        val copy = !cued && !noun && isCopy(words, words(said))
        val namesIdea = !(copy && IdeaCues.has(trimmed))
        if (!firstPerson && !timed && !appointment && namesIdea && IdeaCues.has(said)) return Verdict.IDEA to null
        if (idea) return Verdict.IDEA to null
        if (cued) return Verdict.OPEN to null
        return if (copy) Verdict.DROP to DropReason.UNCUED_COPY else Verdict.SUGGEST to null
    }

    /**
     * The capture a strong cue said in the sentences of [source] about [text] says (rule 2 of
     * [verdict]): EXPLICIT for a note or a request to be reminded, else COMMITMENT for a plan that
     * must be; null without one said as a plan or a request, and for one said under a condition.
     */
    fun cueCapture(text: String, source: String): TodoCapture? = strongCue(sentencesAbout(text.trim(), source))

    /**
     * Whether [text] is no to-do but a heading of a list said aloud or a greeting, which the model
     * and the spotter can take for one: "Second, the budget" and "See you" of an English meeting on
     * 2026-10-14 ([isHeading], [isGreeting]).
     */
    fun isHeadingOrGreeting(text: String): Boolean = isHeading(text) || isGreeting(text)

    /**
     * Whether [text] is a heading of a list said aloud: it starts with an ordinal or "finally"
     * ([ENUMERATORS]) set off by a comma, a colon or a dash ("Second, the budget"), or followed by
     * an article ("Zweitens das Budget") or by one word alone ("Third, hiring" written "Third
     * hiring"), and has no verb a to-do is said with after it ("First, call the bank" is a to-do).
     * An item whose first word is an ordinal of its own is none: "Second meeting with Anna",
     * "Third quarter report for Tom", "First aid kit for the trip".
     */
    fun isHeading(text: String): Boolean {
        val words = words(text)
        if (words.isEmpty() || words.first() !in ENUMERATORS) return false
        val rest = words.drop(1)
        if (rest.any { ExplicitTodoSpotter.isTodoVerb(it) } || rest.lastOrNull()?.let(ExplicitTodoSpotter::isInfinitiveWord) == true) {
            return false
        }
        return SET_OFF.containsMatchIn(text) || rest.size <= 1 || rest.first() in ARTICLES
    }

    /**
     * Whether [text] is a greeting: one of [GREETINGS], with at most words of time or of who is
     * greeted after it ("See you next week", "Danke euch").
     */
    fun isGreeting(text: String): Boolean {
        val words = words(text)
        if (words.isEmpty()) return false
        val joined = words.joinToString(" ")
        val greeting = GREETINGS.firstOrNull { joined == it || joined.startsWith("$it ") } ?: return false
        return words.drop(greeting.count { it == ' ' } + 1).all { it in AFTER_GREETING }
    }

    /**
     * Whether [text] says what the text it was read from is about instead of naming a thing to do:
     * - its subject is the text, the recording or who speaks in it, and its verb reports what they
     *   say ("The text describes ...", "The speaker talks about ...", "Der Text handelt von ...",
     *   "Es geht um ..."). After a subject that is also a noun of a to-do ("recording", "book":
     *   [AMBIGUOUS_REPORTED]) the verb must be followed by what it reports (a small word such as
     *   "about", "that", "the", or a name), so "Recording states shown on the cover screen" and
     *   "Book talks with the publisher" are kept;
     * - or it starts with such a verb, its subject left out ("Describes a sad story ...", "Tells
     *   the story of ...", "Narrates ...", "Beschreibt ..."), or with "About a ...";
     * - or it names a story or a tale about something ("A poignant story about a mother's love",
     *   "Story of parents ...", "Eine Geschichte über ..."), unless an action comes first ("Write a
     *   story about the trip").
     *
     * The 1B gave one such sentence about a Malayalam reading as an open to-do and as an idea on
     * 2026-10-16, and Gemma 4 E2B may leave the subject out. One that names its action with who is
     * to do it ("The speaker needs to call the bank") is none, nor is a noun said with its owner
     * ("Speaker notes for the talk").
     */
    fun describesText(text: String): Boolean {
        val trimmed = text.trim()
        if (reports(trimmed) || DESCRIBING.any { it.containsMatchIn(trimmed) }) return true
        if (SUBJECTLESS.containsMatchIn(trimmed) || ABOUT_FIRST.containsMatchIn(trimmed)) return true
        val first = words(trimmed).firstOrNull() ?: return false
        return !isActionVerb(first) && NAMES_A_STORY.any { it.containsMatchIn(trimmed) }
    }

    /**
     * Whether [text] starts with the text or who speaks in it and a verb that reports what they say
     * ([describesText]'s first rule).
     */
    private fun reports(text: String): Boolean {
        val match = REPORTS.find(text) ?: return false
        val subject = match.groupValues[1].lowercase(Locale.ROOT)
        val next = match.groupValues[2]
        if (subject !in AMBIGUOUS_REPORTED || next.isEmpty()) return true
        return next.lowercase(Locale.ROOT) in REPORTS_WHAT || next.first().isUpperCase()
    }

    /**
     * Whether [text], a to-do the model wrote for what was said in a language no cue can be read in
     * (Malayalam, rule 5 of [verdict]), names an action or a commitment, as the to-do prompt asks:
     * an order ("Call the bank", "Hand over the papers", "Die Bank anrufen"), a modal verb phrase
     * ("Mother needs to see a doctor", "Should pay the rent"), a request to be reminded ("Remind me
     * to ...", "Don't forget the keys"), or a time with a verb ("Anna arrives tomorrow at 10").
     * "A sad story about a farmer" and "Quarrel over the old house" name none. The to-dos of what
     * was said in English or German are read by the cues where they were said ([verdict]) instead.
     */
    fun namesAction(text: String): Boolean {
        val trimmed = text.trim()
        val content = words(trimmed).dropWhile { it in FILLERS || it in OPENERS }
        if (content.isEmpty()) return false
        return says(trimmed, REMINDING) || hasModal(content) || isOrder(content) || saysToDo(trimmed) ||
            (hasTime(content) && content.any(::isVerb))
    }

    /**
     * Whether [text] holds a key of the to-do answer, as the model writes the fields it was asked
     * for into the one it fills: a key with a colon or an equals sign at its start ("said: ...",
     * "Capture: explicit"), or one in quotes as the JSON writes it ("\"segment\": \"S2\"").
     */
    fun namesField(text: String): Boolean = FIELD.containsMatchIn(text)

    /**
     * Whether [text] says, in the first person, that the speaker will or must do something, or
     * asks to be reminded: a strong or a weak cue said as a plan or a request, also under a
     * condition, but not one asked, denied, said as talk or as part of an idea, and not a bare noun
     * ("to do", "Aufgabe").
     */
    fun hasFirstPersonCue(text: String): Boolean =
        CUE_LISTS.any { (kind, phrases) -> cuesIn(text, phrases, kind).any { it != Standing.NONE } }

    /**
     * Whether [text] says one of [phrases], each lower-case words separated by single spaces, as
     * whole words and whatever the case. The small words of [BETWEEN] don't break a phrase: "I
     * also need to", "ich muss noch", "es wäre doch schön".
     */
    internal fun says(text: String, phrases: Collection<String>): Boolean {
        val normalized = " " + words(text).filterNot { it in BETWEEN }.joinToString(" ") + " "
        return phrases.any { normalized.contains(" $it ") }
    }

    /** The capture of the first strong cue said in [said] as a plan or a request, EXPLICIT first. */
    private fun strongCue(said: String): TodoCapture? = when {
        cuesIn(said, EXPLICIT_CUES, Kind.EXPLICIT).any { it == Standing.SAID } -> TodoCapture.EXPLICIT
        cuesIn(said, COMMITMENT_CUES, Kind.COMMITMENT).any { it == Standing.SAID } -> TodoCapture.COMMITMENT
        else -> null
    }

    /** The sentences of [source] about the to-do [text] ([TodoTexts.sentencesAbout]), or all of [source] when none is. */
    private fun sentencesAbout(text: String, source: String): String =
        TodoTexts.sentencesAbout(text, source).joinToString(" ").ifEmpty { source }

    /**
     * Whether [source] has a sentence said as a note of a thing to do, the way one dictates a list:
     * in English one that starts with a verb such as "call" or "book" ("And call the plumber on
     * Friday."; not "Look, it was ..."), in German one that ends on its infinitive and has no
     * subject ("Morgen Anna anrufen.").
     */
    fun saysToDo(source: String): Boolean =
        source.split(SENTENCE_BREAK).any { sentence ->
            val tokens = sentence.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.isEmpty() || sentence.trimEnd().endsWith('?')) return@any false
            val lowered = tokens.map { it.lowercase(Locale.ROOT).trim { c -> !c.isLetterOrDigit() } }
            val start = lowered.indexOfFirst { it.isNotEmpty() && it !in FILLERS && it !in OPENERS }
            val verbFirst = start >= 0 && start < tokens.lastIndex && !tokens[start].endsWith(',') &&
                ExplicitTodoSpotter.isTodoVerb(lowered[start])
            val last = tokens.last().trim { !it.isLetter() }
            val infinitiveLast = last.isNotEmpty() && last.first().isLowerCase() &&
                ExplicitTodoSpotter.isInfinitiveWord(last) && lowered.none { it in GERMAN_SUBJECTS }
            verbFirst || infinitiveLast
        }

    // --- What a to-do's own words say ([namesAction]) -------------------------------------------

    /** A modal with "to" and a verb after it ("needs to see"), or with a verb right after it ("must call"); German's with its infinitive last. */
    private fun hasModal(words: List<String>): Boolean {
        for (i in words.indices) {
            val next = words.getOrNull(i + 1) ?: break
            if (words[i] in MODALS_TO && next == "to" && i + 2 < words.size) return true
            if (words[i] in MODALS && next !in NO_ACTION_AFTER_MODAL) return true
        }
        return words.any { it in GERMAN_MODALS } && ExplicitTodoSpotter.isInfinitiveWord(words.last())
    }

    /**
     * An order: a verb first ("Call the bank", "Attend the meeting"), or a word first with what it
     * is done to right after it ("Line up the chairs", "Polish the shoes"), which a noun phrase has
     * not ("Story about a farmer", "Party on Friday").
     */
    private fun isOrder(words: List<String>): Boolean {
        val first = words.first()
        if (isActionVerb(first)) return true
        val second = words.getOrNull(1) ?: return false
        return second in OBJECT_STARTS && first !in NOT_ORDERS && first.none { it.isDigit() } &&
            !first.endsWith("'s") && !first.endsWith("ing") && !first.endsWith("ed") && !isThirdPerson(first)
    }

    /**
     * Whether [word] reads as a verb of the third person, which reports instead of ordering
     * ("Describes a story", "Tells the story", "Narrates the grief"): an -s that no verb of an
     * order ends on ("Discuss", "Focus", "Pass").
     */
    private fun isThirdPerson(word: String): Boolean =
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is")

    /** A day, a part of it or a clock time: "tomorrow", "on Friday", "at 10", "9 pm", "um 9". */
    private fun hasTime(words: List<String>): Boolean =
        words.any { it in TIME_WORDS || CLOCK.matches(it) } ||
            words.zipWithNext().any { (a, b) -> (a.first().isDigit() && b in CLOCK_AFTER) || (a in CLOCK_BEFORE && b.first().isDigit()) }

    /** A verb a thing to do is said with, as it is or with an -s: "visit", "arrives". */
    private fun isVerb(word: String): Boolean =
        isActionVerb(word) || word in MODALS ||
            (word.length > 3 && word.endsWith("s") && isActionVerb(word.dropLast(1))) ||
            (word.length > 4 && word.endsWith("es") && isActionVerb(word.dropLast(2)))

    private fun isActionVerb(word: String): Boolean = ExplicitTodoSpotter.isTodoVerb(word) || word in ORDER_VERBS

    // --- Where a cue is said --------------------------------------------------------------------

    /** The lists a cue phrase comes from, which say how it is read. */
    private enum class Kind { EXPLICIT, COMMITMENT, WEAK }

    /** How a cue phrase is said where it stands. */
    private enum class Standing {
        /** As a plan or a request: "I need to call the bank". */
        SAID,

        /** As a plan that hangs on a condition: "If it rains, I need to close the windows". */
        CONDITIONAL,

        /** Not as one: asked, denied, doubted, done, talk, or part of an idea. */
        NONE
    }

    /** One word of a sentence, in lower case, and whether a clause break comes right before it. */
    private class Word(val text: String, val breakBefore: Boolean)

    /** How each of [phrases] said in [text] stands, read as cues of [kind], sentence by sentence. */
    private fun cuesIn(text: String, phrases: List<String>, kind: Kind): List<Standing> =
        text.split(SENTENCE_BREAK).flatMap { sentence ->
            val said = wordsOf(sentence)
            // Positions of the words that count, without the small words a cue may hold.
            val counted = said.indices.filter { said[it].text !in BETWEEN }
            phrases.flatMap { phrase ->
                val parts = phrase.split(' ')
                (0..counted.size - parts.size)
                    .filter { i -> parts.indices.all { said[counted[i + it]].text == parts[it] } }
                    .map { i -> standing(sentence, said, counted[i], counted[i + parts.lastIndex] + 1, kind, phrase) }
            }
        }

    /** How the cue [phrase] of [kind], words [start] to [end] of [sentence], is said. */
    private fun standing(sentence: String, said: List<Word>, start: Int, end: Int, kind: Kind, phrase: String): Standing {
        val clauseStart = (start downTo 1).firstOrNull { said[it].breakBefore } ?: 0
        val clauseEnd = (end until said.size).firstOrNull { said[it].breakBefore } ?: said.size
        val before = said.subList(clauseStart, start).map { it.text }
        val after = said.subList(end, clauseEnd).map { it.text }.dropWhile { it in BETWEEN }
        // "Can you remind me to call the bank?" asks for a reminder; "Do I need to ...?" asks a question.
        if (sentence.trimEnd().endsWith('?') && !(phrase == "remind me" && "you" in before)) return Standing.NONE
        // "Ich muss morgen nicht die Bank anrufen", "Ich muss keine Rechnung zahlen", "already done"
        if ((before + after).any { it in GERMAN_NEGATORS || it.startsWith("kein") || it in DONE_WORDS }) return Standing.NONE
        // "It would be nice if the app could remind me to ...", "it would be better if I need to tap once"
        if (IdeaCues.wishes(before, request = phrase == "remind me")) return Standing.NONE
        val conditional = when (kind) {
            Kind.EXPLICIT -> {
                // "Don't remind me to", "never remind me"
                if (before.lastOrNull() in ENGLISH_NEGATORS) return Standing.NONE
                // "I don't forget my keys", "We need to make sure to ...": said of someone, not asked for.
                if (phrase in IMPERATIVES && before.any { it in SUBJECTS }) return Standing.NONE
                // "If you remind me to ..., ..."; but "If I forget, remind me to ..." and "It would be
                // great if you could remind me to ..." ask for the reminder.
                before.dropWhile { it in FILLERS || it in OPENERS }.firstOrNull() in CONDITIONS
            }
            Kind.COMMITMENT, Kind.WEAK -> {
                // "I must not", "I will never"
                if (after.firstOrNull() == "not" || after.firstOrNull() == "never") return Standing.NONE
                // "I don't think I need to", "I doubt I'll", "whether I should"
                if (before.any { it in DOUBTS || it.endsWith("n't") }) return Standing.NONE
                // "I have to say the hotel was lovely", "Ich muss sagen, ...", "I'll be honest"
                if (ExplicitTodoSpotter.isTalk(after.joinToString(" "), weak = kind == Kind.WEAK)) return Standing.NONE
                // "If it rains, I need to close the windows", "I'll call the bank if I have to"
                said.subList(0, start).any { it.text in CONDITIONS }
            }
        }
        return if (conditional) Standing.CONDITIONAL else Standing.SAID
    }

    /** The words of [sentence] as [words] reads them, each with whether a clause break comes before it. */
    private fun wordsOf(sentence: String): List<Word> {
        val lowered = sentence.lowercase(Locale.ROOT).replace('’', '\'')
        val result = mutableListOf<Word>()
        var from = 0
        for (match in WORD.findAll(lowered)) {
            val text = match.value.trim('\'')
            if (text.isEmpty()) continue
            val gap = lowered.substring(from, match.range.first)
            result += Word(text, breakBefore = result.isNotEmpty() && gap.any { it in CLAUSE_BREAKS })
            from = match.range.last + 1
        }
        return result
    }

    // A to-do that is most of its own sentence, word for word, is a copy of it.
    private fun isCopy(todo: List<String>, source: List<String>): Boolean {
        if (source.isEmpty()) return false
        val sourceSet = source.toSet()
        val shared = todo.count { it in sourceSet }
        return shared >= todo.size * COPY_SHARE && todo.size >= source.size * COPY_LENGTH
    }

    private fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace('’', '\'')
            .split(WORD_BREAK)
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }

    private const val MIN_WORDS = 2

    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val WORD = Regex("[\\p{L}\\p{Nd}']+")
    private val WORD_BREAK = Regex("[^\\p{L}\\p{Nd}']+")

    /** What ends a clause between two words: "If it rains, I need to ...", "Note to self: ...". */
    private const val CLAUSE_BREAKS = ",;:–—"

    /** Words a dictated note may start with before its verb: "Then call ...", "Right, book ...". */
    private val OPENERS = setOf("then", "right", "now", "next", "first", "please", "dann", "jetzt", "bitte", "außerdem")

    /** A German sentence with one of these has a subject, so it is no note in the infinitive. */
    private val GERMAN_SUBJECTS = setOf("ich", "du", "er", "sie", "es", "wir", "ihr", "man")

    // Of a to-do's words, this share comes from its sentence...
    private const val COPY_SHARE = 0.9f

    // ...and it is at least this share of the sentence's length.
    private const val COPY_LENGTH = 0.6f

    /** Words a speaker fills pauses with; a to-do never starts with them. */
    private val FILLERS = setOf(
        "um", "uh", "uhm", "umm", "er", "erm", "ah", "oh", "hm", "hmm", "yeah", "yes", "ok", "okay",
        "so", "and", "but", "well", "also", "like", "äh", "ähm", "öhm", "ja", "naja", "na", "genau", "und", "aber"
    )

    /** Words a finished to-do doesn't end on: they need more after them. */
    private val DANGLING = setOf(
        "the", "a", "an", "to", "of", "for", "with", "and", "or", "but", "when", "that", "this", "these",
        "is", "was", "are", "were", "it", "be", "been", "had", "have", "has", "if", "because", "as", "at", "on",
        "in", "by", "from", "my", "your", "our", "their", "his", "her", "its", "very",
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "und", "oder",
        "aber", "wenn", "dass", "weil", "ist", "war", "sind", "zu", "mit", "für", "von", "bei", "auf", "an",
        "im", "am", "mein", "meine", "dein", "deine", "sehr"
    )

    /** Words that may stand inside a cue phrase without changing it. */
    private val BETWEEN = setOf(
        "also", "still", "really", "just", "definitely", "actually", "then", "absolutely",
        "auch", "noch", "unbedingt", "dann", "mal", "wirklich", "jetzt", "doch", "ja"
    )

    // How a cue is denied or doubted, said of what is done, or what it hangs on, as ExplicitTodoSpotter reads it.
    private val ENGLISH_NEGATORS = setOf("don't", "dont", "not", "never", "no")
    private val GERMAN_NEGATORS = setOf("nicht", "nie", "niemals", "nichts")
    private val DONE_WORDS = setOf("already", "bereits", "erledigt")
    private val DOUBTS = setOf(
        "not", "never", "no", "dont", "doesnt", "didnt", "cant", "doubt", "doubtful", "unsure", "wonder", "wondering",
        "whether", "unlikely", "hardly", "ob", "bezweifle", "zweifle", "unsicher"
    )
    private val CONDITIONS = setOf("if", "unless", "wenn", "falls", "sofern")
    private val SUBJECTS = setOf(
        "i", "i'm", "i'll", "i've", "i'd", "you", "he", "she", "it", "we", "they", "ich", "du", "er", "sie", "es", "wir", "ihr", "man"
    )

    // Cue phrases, as lower-case words, EN and DE. Strong: a note or a request to be reminded...
    private val EXPLICIT_CUES = listOf(
        "remind me", "reminder to", "don't forget", "do not forget", "dont forget", "note to self", "make sure i",
        "make sure to", "erinnere mich", "erinner mich", "nicht vergessen", "denk daran", "denke daran", "notiz an mich"
    )

    // ...of which these are orders to oneself, which a subject before them makes a statement.
    private val IMPERATIVES = setOf(
        "don't forget", "do not forget", "dont forget", "make sure i", "make sure to", "denk daran", "denke daran"
    )

    // ...and a plan that must be, in the first person.
    private val COMMITMENT_CUES = listOf("i need to", "i have to", "i've got to", "i got to", "i must", "ich muss", "muss ich")

    // Weak: what the speaker will, wants to or should do. "soll ich" is often what someone asked for.
    private val WEAK_CUES = listOf(
        "i'll", "i will", "i'm going to", "i am going to", "i want to", "i should",
        "ich werde", "werde ich", "ich will", "ich möchte", "ich sollte", "ich müsste", "ich soll", "soll ich"
    )

    private val CUE_LISTS = listOf(Kind.EXPLICIT to EXPLICIT_CUES, Kind.COMMITMENT to COMMITMENT_CUES, Kind.WEAK to WEAK_CUES)

    // Weak too, and in no person: the bare nouns, which never open a to-do (rule 7).
    private val NOUN_CUES = listOf("to do", "todo", "aufgabe")

    /** The words a heading of a list said aloud starts with ([isHeading]). */
    private val ENUMERATORS = setOf(
        "first", "firstly", "second", "secondly", "third", "thirdly", "fourth", "fourthly", "fifth", "finally", "lastly",
        "erstens", "zweitens", "drittens", "viertens", "fünftens", "schließlich", "zuletzt"
    )

    /**
     * A heading's first word set off from what it heads: "Second, ...", "Erstens: ...", "Finally
     * - ...", but not the hyphen of a compound ("First-hand").
     */
    private val SET_OFF = Regex("""^[^\p{L}]*\p{L}+(?:\s*[,:;–—]|\s+-)""")

    /** The articles and determiners a heading's subject may start with: "Second, the budget", "Zweitens das Budget". */
    private val ARTICLES = setOf(
        "the", "a", "an", "our", "my", "your", "this", "these", "that", "those",
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "unser", "unsere",
        "unseren", "unserem", "mein", "meine", "dieser", "diese", "dieses"
    )

    /** Greetings, as lower-case words separated by single spaces, longest first where one starts another. */
    private val GREETINGS = listOf(
        "see you soon", "see you", "see ya", "bye bye", "goodbye", "good bye", "bye", "thank you", "thanks",
        "good morning", "good evening", "good night", "hello", "hi", "hey", "cheers", "take care",
        "auf wiedersehen", "bis dann", "bis bald", "bis später", "bis morgen", "tschüss", "tschüs", "ciao",
        "vielen dank", "danke", "hallo", "guten morgen", "guten abend", "gute nacht"
    )

    /** What may follow a greeting in a greeting: when, and who is greeted. */
    private val AFTER_GREETING = setOf(
        "next", "week", "soon", "later", "tomorrow", "tonight", "then", "all", "everyone", "everybody", "guys", "again",
        "nächste", "nächsten", "woche", "euch", "allen", "alle", "allerseits", "zusammen", "dann", "später", "morgen"
    )

    // --- [describesText], [namesAction], [namesField] ------------------------------------------

    /** What the text, the recording or who speaks in it is called, as the subject of [describesText]. */
    private const val REPORTED = "text|texts|speaker|speakers|recording|transcript|transcription|passage|story|narrator|" +
        "narration|audio|conversation|discussion|segment|paragraph|author|writer|user|content|reading|excerpt|extract|book|" +
        "article|poem|speech|summary|video|clip|lecture|letter|message"

    /**
     * The verbs that report what it says. Not those a noun phrase of a to-do may have after such a
     * word ("Speaker notes", "Recording highlights", "Book deals", "Book covers").
     */
    private const val REPORTING = "describes|described|talks|talked|discusses|discussed|mentions|mentioned|says|said|" +
        "tells|told|explains|explained|narrates|narrated|speaks|spoke|expresses|expressed|recounts|recounted|reflects|" +
        "reflected|portrays|portrayed|depicts|depicted|recalls|recalled|refers|referred|revolves|centers|centres|focuses|" +
        "focused|emphasizes|emphasises|argues|argued|states|stated|is about|was about|is a story|is the story"

    /**
     * Of [REPORTED], the words that are also nouns of a to-do or an idea ("Recording states shown
     * on the cover screen", "Book talks with the publisher"): after them the reporting verb must be
     * followed by what it reports ([REPORTS_WHAT], or a name).
     */
    private val AMBIGUOUS_REPORTED = setOf(
        "recording", "audio", "conversation", "discussion", "segment", "paragraph", "user", "content", "reading", "book",
        "article", "speech", "summary", "video", "clip", "lecture", "letter", "message"
    )

    /** What a reporting verb is followed by when it reports: what it is about, or a small word that starts it. */
    private val REPORTS_WHAT = setOf(
        "about", "that", "how", "what", "why", "whether", "who", "whom", "when", "where", "the", "a", "an", "his", "her",
        "their", "its", "this", "these", "those", "of", "on", "upon", "around", "to", "in", "some", "many", "several"
    )

    /** "The text describes ...": the subject (group 1) and the word after the verb (group 2), if any. */
    private val REPORTS = Regex(
        """^(?:the|this|that|a|an)?\s*($REPORTED)\s+(?:(?:also|mainly|mostly|briefly|then|here|clearly|further|""" +
            """again|now|really|first)\s+)?(?:$REPORTING)\b(?:\s+([\p{L}'’]+))?""",
        RegexOption.IGNORE_CASE
    )

    /** A reporting verb first, its subject left out: "Describes a sad story", "Tells the story of", "Beschreibt das Dorf". */
    private val SUBJECTLESS = Regex(
        """^(?:describes|depicts|portrays|narrates|recounts|recalls|tells|talks about|speaks about|discusses|explains|""" +
            """mentions|reflects on|focuses on|centers on|centres on|revolves around|is about|was about|is a story|""" +
            """is the story|beschreibt|erzählt|schildert|handelt von|berichtet|thematisiert)\b""",
        RegexOption.IGNORE_CASE
    )

    /** "About a mother waiting for her son": what the text is about, and nothing to do. */
    private val ABOUT_FIRST = Regex("""^about\s+(?:a|an|the|his|her|their|its|how|what|why|who|when|life)\b""", RegexOption.IGNORE_CASE)

    /** A story or a tale about something, as the text is summed up: "A poignant story about ...", "Story of ...". */
    private val NAMES_A_STORY = listOf(
        Regex(
            """^(?:(?:a|an|the)\s+)?(?:[\p{L}'’-]+\s+){0,3}?(?:story|stories|tale|tales|narrative|retelling|synopsis|""" +
                """depiction|portrayal)\s+(?:about|of)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""^(?:(?:eine|die|der|das)\s+)?(?:[\p{L}'’-]+\s+){0,3}?(?:geschichte|erzählung)\s+(?:über|von)\b""", RegexOption.IGNORE_CASE)
    )

    private val DESCRIBING = listOf(
        Regex("""^(?:it|this|here)\s+(?:is about|was about|describes|talks about|tells|discusses|is a story)\b""", RegexOption.IGNORE_CASE),
        Regex(
            """^(?:der|die|das|dieser|diese|dieses|ein|eine)?\s*(?:text|sprecher|sprecherin|redner|rednerin|aufnahme|""" +
                """transkript|abschnitt|geschichte|erzähler|erzählerin|gespräch|nutzer|nutzerin|autor|autorin|buch|artikel|""" +
                """beitrag|brief|nachricht)\s+(?:beschreibt|erzählt|spricht|redet|erwähnt|sagt|handelt|berichtet|schildert|""" +
                """erklärt|behandelt|thematisiert|dreht)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""^(?:es geht (?:hier |darin |dabei )?um|(?:hier|darin|dabei) geht es um)\b""", RegexOption.IGNORE_CASE)
    )

    /** A request to be reminded, or not to forget: what the to-do prompt calls explicit. */
    private val REMINDING = listOf(
        "remind me", "reminder", "don't forget", "do not forget", "dont forget", "remember to", "make sure",
        "erinnere mich", "erinner mich", "erinnerung", "nicht vergessen", "denk daran", "denke daran"
    )

    /** Modals that take "to" before their verb: "needs to see", "has to pay", "going to call". */
    private val MODALS_TO = setOf("need", "needs", "have", "has", "ought", "want", "wants", "going", "got")

    /** Modals with the verb right after them: "must call", "will send", "I'll pay". */
    private val MODALS = setOf(
        "must", "should", "shall", "will", "i'll", "we'll", "you'll", "he'll", "she'll", "they'll", "gotta", "gonna"
    )

    /** What after a modal names no action: "will be fine", "must not". */
    private val NO_ACTION_AFTER_MODAL = setOf("be", "been", "not", "never", "no")

    /** German modals, with an infinitive at the end: "Mutter muss zum Arzt gehen". */
    private val GERMAN_MODALS = setOf("muss", "müssen", "musst", "soll", "sollte", "sollten", "werde", "werden", "möchte", "möchten")

    /**
     * Verbs a to-do starts with that [ExplicitTodoSpotter]'s own list leaves out, as it reads what
     * was said in English and German and these would make it find more there: "Hand over the
     * papers", "Withdraw money", "Attend the wedding".
     */
    private val ORDER_VERBS = setOf(
        "hand", "withdraw", "deposit", "attend", "complete", "consult", "eat", "drink", "sleep", "wake", "rest", "pray",
        "join", "avoid", "wear", "carry", "use", "begin", "continue", "greet", "wish", "clear", "verify", "photocopy",
        "xerox", "recharge", "top", "claim", "purchase", "boil", "cut", "soak", "come", "arrive", "remember"
    )

    /**
     * What a verb is done to may start with: an article, a pronoun, and of the particles "up" alone
     * ("Line up the chairs"). The others follow a noun as often as a verb ("Quarrel over the house",
     * "Day off on Friday", "Night out"); the verbs that take them are in the lists.
     */
    private val OBJECT_STARTS = setOf(
        "the", "a", "an", "this", "that", "these", "those", "my", "your", "his", "her", "our", "their", "its", "some",
        "all", "every", "him", "them", "it", "me", "us", "up", "der", "die", "das", "den", "dem", "ein", "eine", "einen"
    )

    /** First words of what is no order: small words, a subject, a list's heading. */
    private val NOT_ORDERS: Set<String> = DANGLING + ARTICLES + SUBJECTS + FILLERS + ENUMERATORS + setOf(
        "there", "here", "some", "many", "every", "each", "all", "no", "not", "very", "so", "such", "what", "which",
        "who", "how", "why", "where", "one", "two", "three", "more", "most", "only", "just", "also"
    )

    /** Days, parts of the day and the words of a clock time, English and German. */
    private val TIME_WORDS = setOf(
        "today", "tomorrow", "tonight", "morning", "afternoon", "evening", "noon", "midnight", "monday", "tuesday",
        "wednesday", "thursday", "friday", "saturday", "sunday", "weekend", "week", "month", "o'clock",
        "heute", "morgen", "übermorgen", "abend", "abends", "vormittag", "nachmittag", "mittag", "montag", "dienstag",
        "mittwoch", "donnerstag", "freitag", "samstag", "sonntag", "wochenende", "woche", "monat", "uhr"
    )
    private val CLOCK = Regex("""\d{1,2}(?:am|pm|h)""")
    private val CLOCK_AFTER = setOf("am", "pm", "o'clock", "uhr")
    private val CLOCK_BEFORE = setOf("at", "by", "um", "bis", "ab", "gegen")

    /**
     * A key of the to-do answer at the start of a text, or in quotes ([namesField]). Not \W before
     * it, which the JVM reads as ASCII only, and so passed over a Malayalam word before the key
     * where the phone doesn't ([WordClasses]).
     */
    private val FIELD = TodoPrompt.KEYS.joinToString("|").let { keys ->
        Regex("""^${WordClasses.NOT_A_WORD_CHAR}*(?:$keys)\s*[:=]|"(?:$keys)"\s*:""", RegexOption.IGNORE_CASE)
    }
}
