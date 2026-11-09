package com.satori.domain.usecase.todo

import com.satori.domain.model.TodoCapture
import java.util.Locale

/**
 * Finds, without the model, the todos a speaker says outright, in English and German, and the
 * appointments said with their time. A 1B model copies whole sentences and misses requests as
 * plain as "Remind me to call the bank tomorrow at ten"; these rules don't.
 *
 * The transcript is read sentence by sentence; a sentence a segment leaves open goes on in the
 * next one when the time parsing joined them ([TodoPrompt.Line.continued]). A sentence is cut
 * into clauses at ", and", " and then", " und dann", " und danach", ", aber" and the like, before
 * "and I", "und ich", at a comma before a new subject, a conjunction, a cue or a time that starts
 * a clause of its own ("..., um 18 Uhr ist Training"), and between two things to do joined by
 * "and" ("call the bank and pick up the kids"; "die Bank anrufen und die Kinder abholen"). Each
 * clause gives at most one todo:
 * - A request to be reminded: "remind me (to)", "reminder to" (not "a reminder to everyone"),
 *   "don't forget (to)", "don't let me forget", "I mustn't forget", "I can't forget", "remember
 *   to", "note to self", "to do:" (not "nothing to do:"), "make sure (to)" (not "to make sure,");
 *   "erinnere mich (daran)", "nicht vergessen", "denk dran", "denk daran" (not "ich denke
 *   daran", which says what one thinks of), "Notiz an mich", "Aufgabe:", "todo:". So is an item
 *   of a list read out: "one of the to-dos is (to)", "my next to-do is", "another to-do is",
 *   "to-do number two is"; "eine meiner Aufgaben ist", "die nächste Aufgabe ist", "meine Aufgabe
 *   ist (es)". These need their "is", and a thing to do after it: "is to book a room", "is: call
 *   the bank", "is booking the hotel", "ist, die Bank anzurufen", "ist Bank anrufen", or an
 *   appointment with its time. "One of the to-dos to wash the car was missed", "one of the to-dos
 *   is not picked up", "is missing", "is that the reminders come late", "meine Aufgabe ist
 *   schwierig" speak of a to-do and are none. The todo is EXPLICIT.
 * - "Idea:" and "Idee:", with the colon, say an idea: what follows is no todo, and takes no time.
 * - What the speaker means to do, said in the first person: "I need to", "I have to", "I've got
 *   to", "I must", "I should", "I'll have to"; "ich muss", "ich müsste", "ich sollte", "ich
 *   soll", and after a word put first "muss ich", "soll ich" and so on ("Morgen muss ich ...").
 *   The weaker "I'll", "I will", "I'm going to", "I want to", "ich werde", "ich will" and "ich
 *   möchte" also say what will be, so they make a todo only of a thing to do: in English a verb
 *   such as "call", "buy" or "book" first with what it is done to ("I'll call him" names nothing),
 *   not meeting talk ("I'll keep it short", "I'll share my screen"); in German no infinitive of
 *   being, staying, coming or talk ("Ich werde morgen im Büro sein", "Ich möchte mich bei allen
 *   bedanken"). No plan is being somewhere or something ("I'll be right back", "I must be
 *   dreaming", but "I need to be at the airport at 6"), a feeling ("I'm going to miss you"),
 *   leaving ("I have to go now", "Ich muss jetzt Schluss machen") or talk ("I must say the hotel
 *   was lovely", "I want to thank you all"). The todo is a COMMITMENT; one said after a condition
 *   ("If it rains, I need to close the windows") is only INFERRED, and tentative, and a weaker one
 *   there none ("Wenn ich im Lotto gewinne, werde ich kündigen").
 * - Without either, an appointment noun ("appointment", "meeting", "interview", "flight",
 *   "dentist", "doctor"; "Termin" and words ending in "termin", "Meeting" and words ending in
 *   "meeting", "Besprechung", "Treffen", "Flug", "Zahnarzt", "Arzt") said with a time: the todo is
 *   its noun phrase ("Dentist appointment", "Teammeeting", "Meeting with Bob"), an EXPLICIT
 *   appointment. So is a request or plan whose words are only that noun phrase ("Don't forget
 *   the team meeting on Monday at 10", "Ich muss morgen um 9 zum Zahnarzt").
 *
 * Not a todo: a question ("Do I need to ...?", but "Can you remind me to ...?" asks for a
 * reminder), a request to be reminded said inside a wish for what something could do ("It would be
 * nice if the app could remind me to ...", "the phone should remind me": [IdeaCues.wishes]), a
 * negation ("I don't need to", "I will not", "ich muss nicht", "kein Meeting"), a plan doubted or
 * denied before its cue ("I don't think I need to", "I'm not sure I'll", "I doubt
 * I'll", "whether I should") or said as a condition ("If I need to ..., "), what is past or done
 * ("I should have called", "I had to go to the dentist", "I called the doctor", "habe ...
 * angerufen", "already", "erledigt", but "was rescheduled", "already booked"), what someone else
 * says ("..., said Tom"), other people's plans and appointments ("he needs to", "die Heizung wird
 * repariert", "She has a doctor's appointment", "Anna muss zum Zahnarzt", "Tom has a job
 * interview", "Mike's got a dentist appointment", "Lisa hat einen Termin", "The kids have a
 * dentist appointment", "Tom is going to the dentist": "have", "haben" and the like are the
 * speaker's with "I", "we", "ich", "wir" or the polite "Sie"), an appointment the sentence says
 * the speaker won't go to ("..., but I'm not going", "..., I'll skip it", "ich gehe nicht hin"), a
 * cancelled appointment, a cue that leaves no word to do or ends on a joining word ("I need to
 * call the"), nor one that leaves only talk ("I have to say, ...", "ich muss zugeben").
 *
 * The text is what follows the cue up to the clause's end ("Den Termin beim Zahnarzt muss ich
 * absagen" keeps what was put first), without its time words (the parser's phrases, with the
 * word that ties them: "bis Sonntag"), without "also", "too", "then", "please", "just", "now",
 * "auch", "noch", "außerdem", "dann", "bitte", "mal", "unbedingt", "endlich", "jetzt", "leider",
 * "als Erstes" and hedges ("probably", "vielleicht", which make it tentative), German "zu"
 * infinitives and "dass ich ... anrufe" made plain ("anzurufen", "rauszubringen": "anrufen",
 * "rausbringen"), and cleaned as the model's texts are ([TodoTexts.clean]): English starts with
 * its verb, the first letter is upper case, at most [TodoTexts.MAX_WORDS] words. A German plan
 * needs its infinitive at the end ("Ich will ein Eis" is a wish), an English one its verb first
 * ("I'm going to the gym" is none). "Again" ("noch mal", "wieder") stays: it tells a todo from
 * the one done before.
 *
 * The time is the clause's own, as the parser read it: its clock time with the day said apart
 * from it ("Nächsten Freitag" ... "um halb drei"), else its day; a time said before a comma with
 * only words that set the scene ("First thing tomorrow, I need to ...", "By Friday, ...") is the
 * clause's. A clause with more than one clock time keeps only its day ([Spotted.weakTime]): the
 * words don't tell which clock time is whose. Due time and reminder are worked out from it by
 * [ExtractTodosUseCase] as for the model's todos.
 */
internal object ExplicitTodoSpotter {

    /**
     * One todo as said, before its time is checked.
     *
     * @property lines The segments its clause is in, in spoken order
     * @property offset Where its clause starts in the first of them
     * @property text What to do, cleaned ([TodoTexts.clean])
     * @property times Its time: one, or a day and the clock time said apart from it, in spoken
     *   order; empty when its clause names none
     * @property weakTime Its clause names more than one clock time, so none was taken
     * @property appointment An appointment noun said with its time
     * @property capture EXPLICIT for a request to be reminded, an item of a list read out, an
     *   appointment and an idea, COMMITMENT for a plan said in the first person, INFERRED for one
     *   that hangs on a condition
     * @property tentative Hedged ("maybe", "probably", "vielleicht") or hanging on a condition
     * @property idea An idea said as one ("Idea: ...", "Idee: ..."), no to-do; it has no time
     */
    class Spotted(
        val lines: List<TodoPrompt.Line>,
        val offset: Int,
        val text: String,
        val times: List<TodoPrompt.Time>,
        val weakTime: Boolean,
        val appointment: Boolean,
        val capture: TodoCapture,
        val tentative: Boolean,
        val idea: Boolean = false
    )

    /** The todos said outright in [window], in spoken order. */
    fun spot(window: TodoPrompt.Window): List<Spotted> =
        passagesOf(window).flatMap { passage -> sentencesOf(passage).flatMap(::todosIn) }

    /** True for an English verb a thing to do starts with: "call", "buy", "book". */
    fun isTodoVerb(word: String): Boolean = word.lowercase(Locale.ROOT) in TODO_VERBS

    /** True when [word] reads as a German infinitive ("anrufen", "ändern"), not as a word that only ends like one ("morgen"). */
    fun isInfinitiveWord(word: String): Boolean {
        val lower = word.lowercase(Locale.ROOT)
        return lower !in NOT_INFINITIVES && (lower == "tun" || lower == "sein" || INFINITIVE.matches(lower))
    }

    // --- One sentence, one todo per clause ----------------------------------------------------

    /**
     * The todos of one sentence's clauses; when one of them says the speaker won't go ("There's a
     * meeting at 3, but I'm not going", "..., I'll skip it"), none of its appointments, nor that
     * clause's own todo.
     */
    private fun todosIn(sentence: List<Clause>): List<Spotted> {
        val declined = sentence.any(::declines)
        return sentence.mapNotNull { clause ->
            todoIn(clause)?.takeUnless { declined && (it.appointment || declines(clause)) }
        }
    }

    private fun todoIn(clause: Clause): Spotted? {
        val cue = cueIn(clause)
        if (cue != null) {
            if (clause.question && !asksForReminder(clause, cue)) return null
            if (!stands(clause, cue)) return null
            cueTodo(clause, cue)?.let { return it }
        }
        // Also for a cue that came to nothing: "I'll see you at the meeting on Friday at 3".
        return appointmentIn(clause)
    }

    /** "Can you remind me to call the bank?" asks for a reminder; "Do I need to ...?" asks a question. */
    private fun asksForReminder(clause: Clause, cue: CueMatch): Boolean =
        cue.cue.shape == Shape.REMIND_ME && clause.tokens.any { it.end <= cue.start && it.lower == "you" }

    /**
     * False for a cue said in the negative, of the past, of what is done, not as a request, in
     * doubt, in a condition or in someone else's words.
     */
    private fun stands(clause: Clause, cue: CueMatch): Boolean {
        val passage = clause.passage
        val before = clause.tokens.filter { it.end <= cue.start }
        val after = clause.tokens.filter { it.start >= cue.end }
        val shape = cue.cue.shape
        val previous = before.lastOrNull()?.lower
        // "..., said Tom": his words. "If I need to call the bank, ...": only a condition.
        if (clause.reported || clause.condition) return false
        // "Don't remind me to", "do not make sure"
        if (shape in ENGLISH_REQUESTS && previous in ENGLISH_NEGATORS) return false
        // "It would be nice if the app could remind me to drink water": a wish, no request.
        if (shape == Shape.REMIND_ME && IdeaCues.wishes(before.map { it.lower }, request = true)) return false
        // "I always remember to", "I don't forget to", "Ich denke daran, ...": said of oneself, not asked for.
        if (cue.cue.imperative && before.any { it.lower in SUBJECTS }) return false
        // "Muss ich ...", "Sollte ich ..." open a question or a condition.
        if (shape == Shape.VERB_FIRST && before.isEmpty() && clause.sentenceFrom == clause.from) return false
        // "Ich habe nicht vergessen": not forgotten.
        if (shape == Shape.PLAIN && previous in GERMAN_HAVE) return false
        if (shape in COMMITMENTS) {
            // "If I win the lottery, I'll quit": what will be only if; a plan that must be, stays (see cueTodo).
            if (cue.cue.weak && clause.afterCondition) return false
            // "We talked about whether I should apply", "I don't think I need to", "I doubt I'll"
            if (clause.joiner in DOUBT_JOINERS) return false
            if (before.any { passage.timeAt(it.start) == null && isDoubt(it) }) return false
        }
        if (shape == Shape.COMMITMENT_EN) {
            val next = after.firstOrNull()?.lower
            if (next == "not" || next == "never") return false
            // "I should have called"
            if (next == "have" && isParticiple(after.getOrNull(1))) return false
        }
        // German says "nicht" anywhere in the clause: "Ich muss morgen nicht die Bank anrufen".
        val outsideCue = clause.tokens.filter { it.end <= cue.start || it.start >= cue.end }
        if (outsideCue.any { it.lower in GERMAN_NEGATORS || it.lower.startsWith("kein") }) return false
        return outsideCue.none { it.lower in DONE_WORDS }
    }

    private fun isDoubt(token: Tok): Boolean = token.lower in DOUBT_WORDS || token.lower.endsWith("n't")

    private fun cueTodo(clause: Clause, cue: CueMatch): Spotted? {
        val passage = clause.passage
        if (cue.cue.idea) {
            // "Idea: a dark mode for the cover screen": the change it names, as said, times and all.
            val said = plain(passage, cue.end, clause.end, fillers = true, times = false)
            val idea = withoutLooseEnd(TodoTexts.clean(said) ?: return null) ?: return null
            if (TodoTexts.key(idea).words.isEmpty()) return null
            return spotted(clause, idea, appointment = false, capture = TodoCapture.EXPLICIT, idea = true)
        }
        val rest = plain(passage, cue.end, clause.end, fillers = true)
        val shape = cue.cue.shape
        var weak = cue.cue.weak
        var appointment: String? = null
        // A plan said inside a request: "Remind me that I have to call the bank".
        var nested = false
        val words: String = when (shape) {
            Shape.REMIND_ME, Shape.TAKES_TO -> {
                val plan = if (startsWithWord(rest, "that")) afterFirstWord(rest) else rest
                when {
                    startsWithWord(rest, "to") -> rest
                    // "Remind me that I have to call the bank", "Make sure I call the insurance"
                    startsWithAny(plan, SPEAKER_SUBJECTS) && WORD.findAll(plan).count() > 1 -> {
                        nested = true
                        afterFirstWord(plan)
                    }
                    ENGLISH_COMMITMENT_START.containsMatchIn(plan) -> {
                        nested = true
                        plan
                    }
                    else -> {
                        // "Don't forget the keys", "Remind me about the bank": the cue says what to do;
                        // "Don't forget, the party is on Saturday": the party.
                        val thing = withoutCopula(rest)
                        if (thing.none { it.isLetterOrDigit() }) return null
                        appointment = appointmentPhrase(clause, thing)
                        "${cue.cue.display ?: cue.words} $thing"
                    }
                }
            }
            Shape.ERINNERE -> {
                // "Erinnere mich an den Termin beim Zahnarzt am Freitag um 9", "... an die Steuer"
                appointment = appointmentPhrase(clause, rest)
                german(rest.replaceFirst(ERINNERE_AN, ""))
            }
            Shape.PLAIN -> {
                // "Note to self: the dentist appointment is on Friday at 3"
                val thing = withoutCopula(rest)
                appointment = appointmentPhrase(clause, thing)
                val plainWords = german(thing)
                // "One of the to-dos is not picked up", "Meine Aufgabe ist schwierig": said of a to-do.
                val list = cue.cue.list
                if (list != null && appointment == null && !listsThingToDo(list, cue, thing, plainWords)) return null
                plainWords
            }
            Shape.COMMITMENT_EN -> {
                // "I'm going to the dentist on Friday at 3" names a place, not what to do.
                if (startsWithAny(rest, DETERMINERS)) {
                    appointment = appointmentPhrase(clause, rest) ?: return null
                }
                rest
            }
            Shape.COMMITMENT_DE, Shape.VERB_FIRST -> {
                var whole = german(listOf(frontOf(clause, cue), rest).filter { it.isNotEmpty() }.joinToString(" "))
                // "Ich werde die Bank anrufen müssen": a plan that must be.
                GERMAN_LAST_MODAL.find(whole)?.let {
                    whole = whole.substring(0, it.range.first).trim()
                    weak = false
                }
                if (!endsWithInfinitive(whole)) appointment = appointmentPhrase(clause, whole) ?: return null
                whole
            }
        }
        // What the speaker means to do if something happens is only a suggestion.
        val conditional = shape in COMMITMENTS && clause.afterCondition
        val capture = if (conditional) TodoCapture.INFERRED else cue.cue.capture
        if (appointment != null) {
            return spotted(clause, appointment, appointment = true, capture = capture, tentative = conditional)
        }
        val text = withoutLooseEnd(TodoTexts.clean(words) ?: return null) ?: return null
        if (TodoTexts.key(text).words.isEmpty() || TALK.containsMatchIn(text)) return null
        val does = when {
            shape == Shape.COMMITMENT_EN -> englishDoes(text, weak)
            shape == Shape.COMMITMENT_DE || shape == Shape.VERB_FIRST -> germanDoes(text, weak)
            // "Don't forget, I'll be late": what the listener is to know, not a thing to do.
            nested -> englishDoes(text, weak = false)
            else -> true
        }
        if (!does) return null
        return spotted(clause, text, appointment = false, capture = capture, tentative = conditional)
    }

    /**
     * True when [text], what follows an English plan's cue, says a thing to do. A weaker cue
     * ("I'll", "I'm going to", "I want to") needs a verb of [TODO_VERBS] with what it is done to,
     * and no meeting talk. Being somewhere counts after a stronger one ("I need to be at the
     * airport"), being something never ("I'll be right back", "I must be dreaming"); nor do
     * feelings ("I'm going to miss you") or leaving ("I have to go now").
     */
    private fun englishDoes(text: String, weak: Boolean): Boolean {
        val lower = text.lowercase(Locale.ROOT).replace('’', '\'')
        val words = WORD.findAll(lower).map { it.value }.toList()
        val verb = words.firstOrNull() ?: return false
        val rest = words.drop(1)
        if (weak) {
            if (verb !in TODO_VERBS || SMALL_TALK.containsMatchIn(lower)) return false
            // "I'll call him", "I'll pick it up", "I'll call you later": nothing said of what.
            if (rest.all { it in PRONOUNS || it in PARTICLES || it in VAGUE_TIMES }) return false
        }
        return when (verb) {
            "be" -> rest.firstOrNull() in ENGLISH_PLACES
            in ENGLISH_STATES -> false
            in LEAVING -> rest.any { it !in LEAVING_REST }
            else -> true
        }
    }

    /**
     * True when [text], a German plan ending in its infinitive, says a thing to do: not feelings,
     * talk or leaving ("vermissen", "Schluss machen", "jetzt gehen"), being or staying only
     * somewhere ("beim Arzt sein") and only after a stronger cue, and after a weaker one ("ich
     * werde", "ich will", "ich möchte") no coming, having, thanking, greeting or starting.
     */
    private fun germanDoes(text: String, weak: Boolean): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        if (GERMAN_TALK.containsMatchIn(lower)) return false
        val words = WORD.findAll(lower).map { it.value }.toList()
        val verb = words.lastOrNull() ?: return false
        val before = words.dropLast(1)
        return when {
            verb in GERMAN_STATES -> false
            verb == "gehen" -> before.any { it !in GERMAN_LEAVING }
            weak && verb in GERMAN_WEAK_STATES -> false
            // "Ich werde dich anrufen", "Ich werde mich darum kümmern": nothing said of what.
            weak && before.isNotEmpty() && before.all { it in GERMAN_PRONOUNS } -> false
            // "Ich möchte bitte zahlen": an order at the table.
            weak && before.isEmpty() && verb in GERMAN_ORDERING -> false
            verb == "sein" || verb == "bleiben" -> before.any { it in GERMAN_PLACES }
            else -> true
        }
    }

    /**
     * True when what follows a list's cue says a thing to do: [thing] as said, [plainWords] with
     * German "zu" infinitives made plain. In English an infinitive ("is to book a room"), a to-do
     * verb as one dictates it ("is: call the bank", "is buy milk", with what it is done to, as after
     * "I'll"), or its -ing form before what it is done to ("is booking the hotel", "is calling Anna",
     * "is picking up the kids"); in German a "zu" infinitive ("ist, die Bank anzurufen") or an
     * infinitive after what it is done to ("ist Bank anrufen"). Not what is said of the to-do:
     * "is not picked up", "is done", "is missing from the list", "is getting old", "is that ...",
     * "ist schwierig", "ist verschwunden", "ist vergessen worden".
     */
    private fun listsThingToDo(list: ListItem, cue: CueMatch, thing: String, plainWords: String): Boolean {
        if (list == ListItem.GERMAN) {
            val words = WORD.findAll(plainWords).map { Tok(it.range.first, it.range.last + 1, it.value) }.toList()
            val last = words.lastOrNull() ?: return false
            val infinitive = ZU_LAST.containsMatchIn(thing) || ZU_INSIDE.containsMatchIn(thing) ||
                (words.size >= 2 && isInfinitive(last) && !isGermanParticiple(last))
            return infinitive && germanDoes(plainWords, weak = false)
        }
        val text = TodoTexts.clean(plainWords) ?: return false
        val words = WORD.findAll(text).map { it.value }.toList()
        val first = words.firstOrNull()?.lowercase(Locale.ROOT) ?: return false
        if (LIST_TO.containsMatchIn(cue.words)) return englishDoes(text, weak = false)
        val verb = todoVerbOf(first) ?: return false
        if (verb == first) return englishDoes(text, weak = true)
        // The -ing form says what is going on as often as what to do ("is getting old"): only with what it is done to.
        val thingDone = words.drop(1).dropWhile { it.lowercase(Locale.ROOT) in PARTICLES }.firstOrNull() ?: return false
        return thingDone.lowercase(Locale.ROOT) in OBJECT_STARTS || thingDone.first().isUpperCase() || thingDone.all { it.isDigit() }
    }

    /** The to-do verb that [word] is, or is the -ing form of: "book" of "booking", "make" of "making", "get" of "getting". */
    private fun todoVerbOf(word: String): String? {
        if (word in TODO_VERBS) return word
        if (!word.endsWith("ing") || word.length < 5) return null
        val stem = word.removeSuffix("ing")
        val doubled = stem.length > 2 && stem.last() == stem[stem.length - 2]
        return listOfNotNull(stem, stem + "e", stem.dropLast(1).takeIf { doubled }).firstOrNull { it in TODO_VERBS }
    }

    /**
     * True when [rest], the words after a plan's cue up to the end of its clause, are only talk,
     * not a thing to do: "say the hotel was lovely", "tell you something", "be honest", "sagen",
     * "ehrlich sagen", "Schluss machen", and after a [weak] cue ("I'll") also meeting talk ("keep
     * it short"). [TodoGate] reads a cue said so as none.
     */
    fun isTalk(rest: String, weak: Boolean): Boolean {
        val lower = rest.trim().lowercase(Locale.ROOT).replace('’', '\'')
        return TALK.containsMatchIn(lower) || GERMAN_TALK.containsMatchIn(lower) || (weak && SMALL_TALK.containsMatchIn(lower))
    }

    /** The appointment a clause without a cue names with its time, or null. */
    private fun appointmentIn(clause: Clause): Spotted? {
        if (clause.question || clause.times.isEmpty()) return null
        val said = clause.tokens.filter { clause.passage.timeAt(it.start) == null }
        if (isNegated(said) || isPast(said) || isCancelled(said) || isOthers(clause.passage, said)) return null
        val phrase = nounPhrase(plain(clause.passage, clause.start, clause.end, fillers = false)) ?: return null
        if (phrase.ownedByOther || phrase.fromSource || phrase.describesAnother) return null
        return spotted(clause, phrase.text, appointment = true, capture = TodoCapture.EXPLICIT)
    }

    /**
     * [rest] as only an appointment's noun phrase, with the word before it that says where or what
     * ("about the dentist appointment", "zum Zahnarzt"), when the clause names a time; else null.
     */
    private fun appointmentPhrase(clause: Clause, rest: String): String? {
        if (clause.times.isEmpty()) return null
        var words = rest.trim()
        LEADS.find(words)?.let { words = words.substring(it.range.last + 1).trim() }
        val phrase = nounPhrase(words) ?: return null
        if (phrase.ownedByOther || phrase.fromSource || phrase.describesAnother) return null
        val outside = (words.substring(0, phrase.start) + " " + words.substring(phrase.end)).trim()
        return phrase.text.takeIf { WORD.findAll(outside).all { it.value.lowercase(Locale.ROOT) in DETERMINERS } }
    }

    /** [text] said in [clause] as a todo, or with [idea] as an idea, which takes no time. */
    private fun spotted(
        clause: Clause,
        text: String,
        appointment: Boolean,
        capture: TodoCapture,
        tentative: Boolean = false,
        idea: Boolean = false
    ): Spotted? {
        val cleaned = (if (appointment) TodoTexts.clean(text) else text) ?: return null
        val passage = clause.passage
        val first = passage.lineIndexAt(clause.start)
        val last = passage.lineIndexAt(clause.end - 1)
        val (times, weak) = if (idea) emptyList<TodoPrompt.Time>() to false else timesOf(clause)
        return Spotted(
            lines = passage.lines.subList(first, last + 1),
            offset = clause.start - passage.starts[first],
            text = cleaned,
            times = times,
            weakTime = weak,
            appointment = appointment,
            capture = capture,
            tentative = tentative || clause.tokens.any { it.lower in TodoTexts.HEDGES },
            idea = idea
        )
    }

    /**
     * The clause's time: its one clock time, with the one day said apart from it on that date for
     * the words; else its day. With more than one clock time, only the day, and weak.
     */
    private fun timesOf(clause: Clause): Pair<List<TodoPrompt.Time>, Boolean> {
        val spans = clause.times
        val clocks = spans.filter { it.time.candidate.hasClock }
        val days = spans.filterNot { it.time.candidate.hasClock }
        return when (clocks.size) {
            0 -> listOfNotNull(days.firstOrNull()?.time) to false
            1 -> {
                val clock = clocks.single()
                val day = days.singleOrNull { it.time.candidate.at.toLocalDate() == clock.time.candidate.at.toLocalDate() }
                listOfNotNull(day, clock).sortedBy { it.start }.map { it.time } to false
            }
            else -> listOfNotNull(days.singleOrNull()?.time) to true
        }
    }

    // --- Passages, sentences and clauses -----------------------------------------------------

    private fun passagesOf(window: TodoPrompt.Window): List<Passage> {
        val groups = mutableListOf<MutableList<TodoPrompt.Line>>()
        for (line in window.lines) {
            if (groups.isEmpty() || !line.continued) groups += mutableListOf(line) else groups.last() += line
        }
        return groups.map { Passage(it, window) }
    }

    /** The passage's sentences, each as its clauses. */
    private fun sentencesOf(passage: Passage): List<List<Clause>> {
        val sentences = mutableListOf<List<Clause>>()
        var from = 0
        for (i in passage.text.indices) {
            if (!endsSentence(passage, i)) continue
            val to = passage.tokens.indexOfFirst { it.start > i }.let { if (it < 0) passage.tokens.size else it }
            if (to > from) sentences += clausesOf(passage, from, to, question = passage.text[i] == '?')
            from = to
        }
        if (from < passage.tokens.size) sentences += clausesOf(passage, from, passage.tokens.size, question = false)
        return sentences
    }

    /** True for a ".", "!", "?" or ";" at [i] that ends a sentence, outside the times found. */
    private fun endsSentence(passage: Passage, i: Int): Boolean {
        val c = passage.text[i]
        if (c !in ".!?;" || passage.timeAt(i) != null) return false
        return c != '.' || TodoTexts.endsSentenceAt(passage.text, i, ordinalDates = false)
    }

    /** Sentence tokens [from, to) cut into clauses; the words that join them belong to neither. */
    private fun clausesOf(passage: Passage, from: Int, to: Int, question: Boolean): List<Clause> {
        val cues = cuesIn(passage, from, to)
        val clauses = mutableListOf<Clause>()
        var start = from
        var joiner: String? = null
        var k = from + 1
        while (k < to) {
            val skip = joinerAt(passage, start, k, to, cues)
            if (skip < 0) {
                k++
                continue
            }
            clauses += Clause(passage, start, k, from, question, joiner)
            joiner = if (skip > 0) passage.tokens[k].lower else null
            start = k + skip
            k = start + 1
        }
        if (start < to) clauses += Clause(passage, start, to, from, question, joiner)
        val reported = isReported(passage, from, to)
        clauses.forEachIndexed { i, clause ->
            val first = clause.tokens.firstOrNull { it.lower !in OPENERS }?.lower
            // "If I win the lottery, ...", "..., and if they don't call, ...", "Sollte ich Zeit haben, ..."
            clause.condition = clause.joiner in CONDITIONS || first in CONDITIONS ||
                (i == 0 && clauses.size > 1 && first == "sollte")
            clause.afterCondition = i > 0 && clauses[i - 1].condition
            clause.reported = reported
        }
        return clauses
    }

    /**
     * True when sentence tokens [from, to) end in a comma and who said them, someone other than
     * the speaker: "Okay, I'll take care of the invoices, said Tom", "..., meinte Anna".
     */
    private fun isReported(passage: Passage, from: Int, to: Int): Boolean {
        val tokens = passage.tokens
        val comma = (from + 1 until to).lastOrNull { ',' in passage.text.substring(tokens[it - 1].end, tokens[it].start) }
            ?: return false
        val tag = tokens.subList(comma, to)
        return tag.size in 2..3 && tag.any { it.lower in SPEECH_VERBS } && tag.none { it.lower in SPEAKER_WORDS }
    }

    /**
     * When a new clause starts at token [k] of the sentence whose current clause began at [start],
     * the number of joining tokens to skip ("and", "and then"); -1 when the clause goes on.
     */
    private fun joinerAt(passage: Passage, start: Int, k: Int, to: Int, cues: List<CueMatch>): Int {
        val tokens = passage.tokens
        val token = tokens[k]
        val gap = passage.text.substring(tokens[k - 1].end, token.start)
        if (',' in gap) return if (passage.timeAt(tokens[k - 1].end) == null) afterComma(passage, start, k, to, cues) else -1
        // "between 9 and 11" is one time.
        if (gap.isNotBlank() || passage.timeAt(token.start) != null) return -1
        val next = tokens.getOrNull(k + 1)?.takeIf { k + 1 < to }
        // " and then", " und dann", " und danach"
        if (token.lower in AND && next != null && next.lower in THEN) return 2
        // " and I have to", " aber ich muss"
        if (token.lower in COORDINATORS && next != null && next.lower in SUBJECTS) return 1
        // Two things to do: "call the bank and pick up the kids", "die Bank anrufen und die Kinder abholen".
        if (token.lower == "and" && next != null && next.lower in TODO_VERBS && tokens[k - 1].lower !in TODO_VERBS &&
            to - (k + 1) >= 2
        ) return 1
        if (token.lower == "und" && next != null && isInfinitive(tokens[k - 1])) {
            val end = (k + 1 until to).firstOrNull { ',' in passage.text.substring(tokens[it - 1].end, tokens[it].start) } ?: to
            if (end - (k + 1) >= 2 && isInfinitive(tokens[end - 1])) return 1
        }
        return -1
    }

    /** As [joinerAt], for a comma before token [k]. */
    private fun afterComma(passage: Passage, start: Int, k: Int, to: Int, cues: List<CueMatch>): Int {
        val tokens = passage.tokens
        val token = tokens[k]
        // "Erinnere mich daran, die Bank anzurufen", "Note to self, ..."
        if (cues.any { it.end >= tokens[k - 1].end && it.start < tokens[k - 1].end }) return -1
        // "Tomorrow, I need to ...", "Okay, so ...", "First thing tomorrow, ...", "By Friday, ...":
        // what comes first only sets the scene.
        val front = (start until k).map { tokens[it] }
        val timed = front.any { passage.timeAt(it.start) != null }
        if (front.all { passage.timeAt(it.start) != null || it.lower in OPENERS || (timed && it.lower in TIME_LEADS) }) return -1
        if (passage.timeAt(token.start) != null) {
            // "..., um 18 Uhr ist dann noch Training", "..., at 3 I watch the football"
            val span = passage.timeAt(token.start)!!
            val after = (k until to).firstOrNull { tokens[it].start >= span.end } ?: return -1
            val word = tokens[after]
            val opens = word.lower in SUBJECTS || cues.any { it.start == word.start } ||
                (word.text.first().isLowerCase() && word.lower !in CLAUSE_GOES_ON)
            return if (opens) 0 else -1
        }
        if (token.lower in CONNECTORS) {
            val next = tokens.getOrNull(k + 1)?.takeIf { k + 1 < to }
            return if (token.lower in AND && next != null && next.lower in THEN) 2 else 1
        }
        if (token.lower in SUBJECTS || cues.any { it.start == token.start }) return 0
        // "..., da muss ich pünktlich sein", "..., die Rechnung muss ich bezahlen"
        val nextComma = (k + 1 until to).firstOrNull { ',' in passage.text.substring(tokens[it - 1].end, tokens[it].start) } ?: to
        val clauseEnd = if (nextComma < to) tokens[nextComma].start else tokens[to - 1].end
        if (cues.any { it.cue.shape == Shape.VERB_FIRST && it.start >= token.start && it.end <= clauseEnd }) return 0
        return -1
    }

    // --- Cues -------------------------------------------------------------------------------

    private enum class Shape {
        /** "remind me": what follows "to" is the todo; without it the request is. */
        REMIND_ME,

        /** "don't forget", "make sure": like [REMIND_ME], said as an order. */
        TAKES_TO,

        /** "remember to", "note to self", "nicht vergessen": what follows is the todo. */
        PLAIN,

        /** "erinnere mich (daran)" */
        ERINNERE,

        /** "I need to", "I'll": a plan; what follows starts with its verb. */
        COMMITMENT_EN,

        /** "ich muss": a plan; its infinitive ends what follows. */
        COMMITMENT_DE,

        /** "muss ich", after the word put first ("Morgen muss ich ..."), which is part of the todo. */
        VERB_FIRST
    }

    /**
     * @property imperative An order to oneself ("don't forget", "remember to", "denk daran"),
     *   which a subject before it makes a statement ("I always remember to ...", "Ich denke
     *   daran, ...")
     * @property weak A plan that may only say what will be ("I'll", "ich werde"), see [englishDoes]
     *   and [germanDoes]
     * @property notAfter Words after which the cue's words are said in passing ("nothing to do:",
     *   "to make sure")
     * @property display How the request reads in a todo without "to" ("Don't forget the keys")
     * @property idea What follows is an idea, no todo ("Idea: ...")
     * @property list An item of a list read out, in English or German: what follows must say a
     *   thing to do ([listsThingToDo])
     */
    private class Cue(
        pattern: String,
        val capture: TodoCapture,
        val shape: Shape,
        val imperative: Boolean = false,
        val weak: Boolean = false,
        val notAfter: Set<String> = emptySet(),
        val display: String? = null,
        val idea: Boolean = false,
        val list: ListItem? = null
    ) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /** The language of an item of a list read out, which says how its thing to do is said. */
    private enum class ListItem { ENGLISH, GERMAN }

    private class CueMatch(val cue: Cue, val start: Int, val end: Int, val words: String)

    // Word edges, so that "I have to" is no "I have two" and "ich will" no "ich willige".
    private const val B = """(?<![\p{L}\p{N}'’])"""
    private const val E = """(?![\p{L}\p{N}])"""
    private const val ADVERBS = "really|still|also|just|definitely|absolutely|actually|probably|maybe|perhaps|then|now|first"
    private const val STRONG_MODALS = "muss|müsste|sollte|soll"
    private const val WEAK_MODALS = "werde|will|möchte"
    private const val MODALS = "$STRONG_MODALS|$WEAK_MODALS"
    private const val EXPLICIT_END = """\s*[:,\-–]?"""

    // Who a "reminder to" is said to when it isn't a todo: "a reminder to everyone that ...".
    private const val ADDRESSEES = "everyone|everybody|all|you|y['’]all|us|them|the|our|your|team|folks|guys"

    // After these, "to do:" says there is none: "There's nothing to do: the shop is closed".
    private val NOTHING_WORDS = setOf("nothing", "something", "anything", "what", "lot", "lots", "much", "plenty", "else", "little")

    // "to do", "to-do", "todo"
    private const val TO_DO = """to[\s-]?do"""

    // A list's cue that took the "to" of an infinitive: "One of the to-dos is to".
    private val LIST_TO = Regex("""\sto$""", RegexOption.IGNORE_CASE)

    private val CUES = listOf(
        // An item of a list read out: "One of the to-dos is to book a room", "My next to-do is",
        // "Another to-do is", "To-do number two is"; "Eine meiner Aufgaben ist, ...". The "is" is
        // needed: "one of the to-dos to wash the car was missed" speaks of one.
        Cue(
            """${B}one\s+of\s+(?:the|my)\s+$TO_DO(?:s|['’]s)?\s+(?:is|are)(?:\s+to)?$E""",
            TodoCapture.EXPLICIT, Shape.PLAIN, list = ListItem.ENGLISH
        ),
        Cue(
            """${B}(?:my|the)\s+(?:next|first|second|other|last)\s+$TO_DO\s+is(?:\s+to)?$E""",
            TodoCapture.EXPLICIT, Shape.PLAIN, list = ListItem.ENGLISH
        ),
        Cue("""${B}another\s+$TO_DO\s+is(?:\s+to)?$E""", TodoCapture.EXPLICIT, Shape.PLAIN, list = ListItem.ENGLISH),
        Cue(
            """${B}$TO_DO\s+(?:number\s+)?(?:\d+|one|two|three|four|five)\s+is(?:\s+to)?$E""",
            TodoCapture.EXPLICIT, Shape.PLAIN, list = ListItem.ENGLISH
        ),
        // "Meine Aufgabe ist es, ...": the "es" belongs to the cue.
        Cue(
            """${B}(?:eine|einer)\s+(?:meiner|der)\s+aufgaben\s+ist(?:\s+es)?$E""",
            TodoCapture.EXPLICIT, Shape.PLAIN, list = ListItem.GERMAN
        ),
        Cue("""${B}die\s+nächste\s+aufgabe\s+ist(?:\s+es)?$E""", TodoCapture.EXPLICIT, Shape.PLAIN, list = ListItem.GERMAN),
        Cue("""${B}meine\s+aufgabe\s+ist(?:\s+es)?$E""", TodoCapture.EXPLICIT, Shape.PLAIN, list = ListItem.GERMAN),
        // "Idea: ...", "Idee: ...", with the colon: an idea said as one.
        Cue("""${B}(?:idea|idee)\s*:""", TodoCapture.EXPLICIT, Shape.PLAIN, idea = true),
        Cue("""${B}remind\s+me$E""", TodoCapture.EXPLICIT, Shape.REMIND_ME),
        Cue("""${B}reminder(?:\s*:|\s+to$E(?!\s+(?:$ADDRESSEES)$E))""", TodoCapture.EXPLICIT, Shape.PLAIN),
        Cue("""${B}(?:don['’]t|do\s+not)\s+(?:let\s+me\s+)?forget$E""", TodoCapture.EXPLICIT, Shape.TAKES_TO, imperative = true),
        // "I must not forget to", "I mustn't forget", "I can't forget to", "I won't forget to"
        Cue(
            """${B}(?:i|we)\s+(?:must\s+not|mustn['’]t|can\s*not|can['’]t|should\s+not|shouldn['’]t|will\s+not|won['’]t)\s+forget$E""",
            TodoCapture.EXPLICIT, Shape.TAKES_TO, display = "Don't forget"
        ),
        Cue("""${B}remember\s+to$E""", TodoCapture.EXPLICIT, Shape.PLAIN, imperative = true),
        Cue("""${B}note\s+to\s+self$E$EXPLICIT_END""", TodoCapture.EXPLICIT, Shape.PLAIN),
        Cue("""${B}to[\s-]?do\s*:""", TodoCapture.EXPLICIT, Shape.PLAIN, notAfter = NOTHING_WORDS),
        Cue("""${B}make\s+sure$E""", TodoCapture.EXPLICIT, Shape.TAKES_TO, imperative = true, notAfter = setOf("to")),
        // "Erinnere mich daran, ...", "Erinner mich morgen daran, ...", "Erinnere mich am Montag um 9:30 daran, ...",
        // "Erinnere mich an ..."
        Cue(
            """${B}erinnere?\s+mich(?:(?:\s+[\p{L}\p{N}][\p{L}\p{N}:.]*){0,6}?\s+d(?:a)?ran$E\s*,?)?""",
            TodoCapture.EXPLICIT, Shape.ERINNERE
        ),
        Cue("""${B}(?:nicht\s+vergessen|vergiss\s+nicht)$E$EXPLICIT_END""", TodoCapture.EXPLICIT, Shape.PLAIN),
        Cue("""${B}denke?\s+d(?:a)?ran$E$EXPLICIT_END""", TodoCapture.EXPLICIT, Shape.PLAIN, imperative = true),
        Cue("""${B}d(?:a)?ran\s+denken$E$EXPLICIT_END""", TodoCapture.EXPLICIT, Shape.PLAIN),
        Cue("""${B}notiz\s+an\s+mich$E$EXPLICIT_END""", TodoCapture.EXPLICIT, Shape.PLAIN),
        Cue("""${B}aufgabe\s*:""", TodoCapture.EXPLICIT, Shape.PLAIN),
        Cue(
            """${B}i(?:\s+(?:$ADVERBS))*\s+(?:need\s+to|have\s+got\s+to|have\s+to|must|should|got\s+to|gotta)$E""",
            TodoCapture.COMMITMENT, Shape.COMMITMENT_EN
        ),
        // "I'll have to", "I will need to", "I'm going to have to": what must be, not only what will.
        Cue(
            """${B}(?:i['’]ll|i\s+will|(?:i['’]m|i\s+am)\s+(?:going\s+to|gonna))(?:\s+(?:$ADVERBS))*\s+""" +
                """(?:have\s+got\s+to|have\s+to|need\s+to)$E""",
            TodoCapture.COMMITMENT, Shape.COMMITMENT_EN
        ),
        Cue("""${B}i(?:\s+(?:$ADVERBS))*\s+(?:will|want\s+to|wanna)$E""", TodoCapture.COMMITMENT, Shape.COMMITMENT_EN, weak = true),
        Cue("""${B}i['’]ve(?:\s+(?:$ADVERBS))*\s+(?:got\s+to|gotta)$E""", TodoCapture.COMMITMENT, Shape.COMMITMENT_EN),
        Cue("""${B}i['’]ll$E""", TodoCapture.COMMITMENT, Shape.COMMITMENT_EN, weak = true),
        Cue(
            """${B}(?:i['’]m|i\s+am)(?:\s+(?:$ADVERBS))*\s+(?:going\s+to|gonna)$E""",
            TodoCapture.COMMITMENT, Shape.COMMITMENT_EN, weak = true
        ),
        Cue("""${B}ich\s+(?:$STRONG_MODALS)$E""", TodoCapture.COMMITMENT, Shape.COMMITMENT_DE),
        Cue("""${B}ich\s+(?:$WEAK_MODALS)$E""", TodoCapture.COMMITMENT, Shape.COMMITMENT_DE, weak = true),
        Cue("""${B}(?:$STRONG_MODALS)\s+ich$E""", TodoCapture.COMMITMENT, Shape.VERB_FIRST),
        Cue("""${B}(?:$WEAK_MODALS)\s+ich$E""", TodoCapture.COMMITMENT, Shape.VERB_FIRST, weak = true)
    )

    // "that I have to ..." after "remind me": the plan is the todo.
    private val ENGLISH_COMMITMENT_START = Regex(
        """^(?:i(?:\s+(?:$ADVERBS))*\s+(?:need|have|got|want)\s+to|i(?:\s+(?:$ADVERBS))*\s+(?:must|should|will)|i['’](?:ll|ve|m)\b)""",
        RegexOption.IGNORE_CASE
    )

    private val ENGLISH_REQUESTS = setOf(Shape.REMIND_ME, Shape.TAKES_TO, Shape.PLAIN)
    private val COMMITMENTS = setOf(Shape.COMMITMENT_EN, Shape.COMMITMENT_DE, Shape.VERB_FIRST)

    /** Every cue in tokens [from, to) of [passage], each where it starts, longest first. */
    private fun cuesIn(passage: Passage, from: Int, to: Int): List<CueMatch> {
        if (from >= to) return emptyList()
        val start = passage.tokens[from].start
        val text = passage.text.substring(start, passage.tokens[to - 1].end)
        return CUES.flatMap { cue ->
            cue.regex.findAll(text).map { CueMatch(cue, start + it.range.first, start + it.range.last + 1, it.value.trim()) }
        }
            .filter { match -> passage.timeAt(match.start) == null }
            .sortedWith(compareBy({ it.start }, { -it.end }))
    }

    /** The first cue in [clause] not said in passing, or null. */
    private fun cueIn(clause: Clause): CueMatch? =
        cuesIn(clause.passage, clause.from, clause.to).firstOrNull { !inPassing(clause, it) }

    /**
     * True for a cue's words said in passing, which leave the clause to the other rules: "There's
     * nothing to do: ...", "To make sure, the meeting is on Friday at 3".
     */
    private fun inPassing(clause: Clause, cue: CueMatch): Boolean =
        clause.tokens.lastOrNull { it.end <= cue.start }?.lower in cue.cue.notAfter

    /**
     * The word or words a German verb-first cue follows in its clause, with what only sets the
     * scene left out ("Außerdem", "Dann"): "Den Termin beim Zahnarzt" of "Den Termin beim Zahnarzt
     * muss ich absagen". A part before a comma is a clause of its own.
     */
    private fun frontOf(clause: Clause, cue: CueMatch): String {
        val passage = clause.passage
        val before = clause.tokens.filter { it.end <= cue.start }
        val comma = before.indices.lastOrNull { i ->
            i > 0 && ',' in passage.text.substring(before[i - 1].end, before[i].start)
        }
        val from = if (comma == null) before.firstOrNull()?.start ?: return "" else before[comma].start
        var front = plain(passage, from, cue.start, fillers = true)
        while (true) {
            val first = WORD.find(front) ?: return ""
            if (first.range.first != 0 || first.value.lowercase(Locale.ROOT) !in OPENERS) return front
            front = front.substring(first.range.last + 1).trim()
        }
    }

    // --- Words ------------------------------------------------------------------------------

    private val WORD = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}]+)*""")
    private val SPACES = Regex("""\s+""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([,.;:!?])""")
    private val EDGES = Regex("""^[\s,;:\-–—]+|[\s,;:\-–—]+$""")

    // Words that say nothing to do. "noch mal" and "wieder" are "again", which stays.
    private val FILLERS = Regex(
        """(?<![\p{L}\p{N}'’])(?:also|too|then|please|just|really|actually|definitely|absolutely|quickly|""" +
            """urgently|finally|unfortunately|right\s+now|right\s+away|now|first\s+thing|""" +
            """auch|außerdem|dann|bitte|nur|unbedingt|endlich|jetzt|leider|dringend|zuerst|erst\s+mal|erstmal|""" +
            """als\s+erstes|erst|gleich|schnell|""" +
            """maybe|perhaps|probably|possibly|vielleicht|eventuell|wahrscheinlich|möglicherweise|""" +
            """noch(?!\s+(?:ein)?mal(?![\p{L}\p{N}])))(?![\p{L}\p{N}'’])""",
        RegexOption.IGNORE_CASE
    )
    private val MAL = Regex("""(?<![\p{L}\p{N}])(?<!noch\s)mal(?![\p{L}\p{N}])""")

    private val ERINNERE_AN = Regex("""^an(?![\p{L}\p{N}])\s*""", RegexOption.IGNORE_CASE)
    private val DASS_ICH = Regex("""^dass\s+ich\s+(.+?)\s+(?:$MODALS|kann|darf)$""", RegexOption.IGNORE_CASE)

    // "dass ich Oma anrufe": the verb as the speaker says it of himself, made an infinitive.
    private val DASS_ICH_SAID = Regex("""^dass\s+ich\s+(?:(.+?)\s+)?(\p{Ll}{2,}e)$""", RegexOption.IGNORE_CASE)
    private val ZU_LAST = Regex("""(?<![\p{L}])zu\s+(\p{Ll}\p{L}+)$""")

    /**
     * The separable prefixes a German "zu" infinitive takes its "zu" after: "anzurufen",
     * "rauszubringen". [TodoGrounding] finds a spotted "Einkaufen" in the "einzukaufen" it was
     * made from by them.
     */
    internal val ZU_PREFIXES: List<String> = (
        "ab an auf aus bei ein fest her hin los mit nach vor weg zu zurück zusammen um durch über unter wieder frei " +
            "heim weiter raus rein rauf runter rüber hoch heraus herein hinaus hinein herunter hinunter herauf " +
            "hinauf herüber rum herum vorbei zurecht fertig sauber kennen bereit teil dar fort statt"
        ).split(' ')
    private val ZU_INSIDE = Regex("""(?<![\p{L}])((?:${ZU_PREFIXES.joinToString("|")}))zu(\p{Ll}{2,}(?:en|ern|eln))$""")

    // "Ich werde die Bank anrufen müssen"
    private val GERMAN_LAST_MODAL = Regex("""\s+(?:müssen|sollen)$""", RegexOption.IGNORE_CASE)

    // Before the noun phrase of a request or a plan: "about the dentist appointment", "zum Zahnarzt".
    private val LEADS = Regex(
        """^(?:about|of|for|to|at|zum|zur|beim|bei|zu|an|ins|in|für)(?![\p{L}\p{N}])\s*""",
        RegexOption.IGNORE_CASE
    )

    // Only talk: "I have to say, ...", "I must say the hotel was lovely", "I must admit I was wrong",
    // "I want to thank you all", "I need to tell you something", "ich muss zugeben". "Say sorry to
    // Anna" and "Tell Anna about the party" are things to do.
    private val TALK = Regex(
        """^(?:(?:say(?!\s+(?:sorry|goodbye|good\s+bye|bye|hello|hi|thanks|thank|happy|congrat))|admit|confess|""" +
            """guess|mean|suppose|imagine|wonder|tell\s+you|ask\s+you|show\s+you|let\s+you\s+know|talk\s+to\s+you|""" +
            """keep\s+you\s+(?:posted|updated|informed|in\s+the\s+loop)|leave\s+you\s+(?:to\s+it|alone|be)|""" +
            """put\s+you\s+(?:on\s+hold|through)|be\s+honest|""" +
            """thank\s+(?:you|everyone|everybody|all|y['’]all))(?![\p{L}])|""" +
            """(?:see|know)(?:$|[,:;]|\s+(?:that|what|if|whether|how|so|though|i|you|he|she|it|we|they|this|about)(?![\p{L}]))|""" +
            """(?:ehrlich\s+)?(?:sagen|zugeben|gestehen|meinen|glauben|denken|sehen|wissen|ehrlich\s+sein)$)""",
        RegexOption.IGNORE_CASE
    )

    // What a weaker cue says in a meeting, not a thing to do later: "I'll keep it short".
    private val SMALL_TALK = Regex(
        """^(?:start\s+(?:with|by|off)|kick\s+(?:off|things\s+off)|keep\s+(?:it|this|things)\s+(?:short|brief|quick)|""" +
            """make\s+(?:it|this)\s+(?:short|brief|quick)|share\s+(?:my|the)\s+screen|walk\s+(?:you|us|everyone)\s+through|""" +
            """talk\s+about|hand\s+(?:it\s+)?over|pass\s+(?:it\s+)?(?:over|on)|stop\s+(?:here|there|sharing)|""" +
            """leave\s+it\s+(?:there|at\s+that|to\s+you|up\s+to\s+you)|(?:do|try)\s+my\s+best|take\s+(?:that|this|it)\s+as|""" +
            """give\s+it\s+a\s+(?:try|go|shot)|make\s+it\s+work|put\s+it\s+(?:this|that|another)\s+way|""" +
            """make\s+(?:a|my|one|this)\s+point|get\s+the\s+door|go\s+with\s+the)(?![\p{L}])"""
    )

    // German talk a plan can't be: "Schluss machen", "mich kurz fassen", "mich bei allen bedanken".
    private val GERMAN_TALK = Regex(
        """(?<![\p{L}])(?:schluss\s+machen|kurz\s+machen|kurz\s+fassen)$|""" +
            """(?<![\p{L}])bei\s+(?:allen|euch|ihnen|allerseits)(?![\p{L}]).*\sbedanken$"""
    )

    /**
     * The text of passage chars [from, to) without its times, each with the word that ties it
     * to the text ("bis Sonntag", "on Friday"), and with [fillers] without the filler words. With
     * [times] false the times stay: an idea has no due time, so they are part of what it says.
     */
    private fun plain(passage: Passage, from: Int, to: Int, fillers: Boolean, times: Boolean = true): String {
        if (from >= to) return ""
        val builder = StringBuilder()
        var i = from
        for (span in if (times) passage.times else emptyList()) {
            if (span.start < from || span.end > to || span.start < i) continue
            // A possessive names the thing: "Friday's meeting", whether the parser read "Friday" or "Friday's".
            if (passage.text.getOrNull(span.end) in APOSTROPHES || POSSESSIVE.containsMatchIn(span.time.candidate.phrase)) continue
            val tie = passage.tokens.lastOrNull { it.end <= span.start && it.start >= i }
                ?.takeIf { it.lower in TIES && passage.text.substring(it.end, span.start).isBlank() }
                // "for coming today": the verb, not "the coming Friday"
                ?.takeUnless { it.lower == "coming" && SAID_DAY.containsMatchIn(span.time.candidate.phrase) }
            builder.append(passage.text, i, tie?.start ?: span.start).append(' ')
            i = span.end
        }
        if (i < to) builder.append(passage.text, i, to)
        var text = builder.toString()
        if (fillers) text = MAL.replace(FILLERS.replace(text, " "), " ")
        text = SPACE_BEFORE_PUNCTUATION.replace(SPACES.replace(text, " ").trim(), "$1")
        return EDGES.replace(text, "")
    }

    private val SAID_DAY = Regex("""^(?:today|tonight|tomorrow|heute|morgen|übermorgen)""", RegexOption.IGNORE_CASE)

    /** German words after a request: "dass ich ... muss", "dass ich ... anrufe" and "zu" infinitives made plain. */
    private fun german(words: String): String {
        var text = words.trim()
        val modal = DASS_ICH.find(text)
        if (modal != null) {
            text = modal.groupValues[1]
        } else {
            DASS_ICH_SAID.find(text)?.let { said ->
                text = listOf(said.groupValues[1], infinitiveOf(said.groupValues[2])).filter { it.isNotEmpty() }.joinToString(" ")
            }
        }
        text = ZU_LAST.replace(text, "$1")
        return ZU_INSIDE.replace(text, "$1$2")
    }

    /** "anrufe": "anrufen", "ändere": "ändern", "tue": "tun". */
    private fun infinitiveOf(said: String): String = when {
        said.equals("tue", ignoreCase = true) -> "tun"
        said.endsWith("ere") || said.endsWith("ele") -> said.dropLast(1) + "n"
        else -> said + "n"
    }

    /** [text] without a copula at its end that the time's removal left: "the party is" of "the party is on Saturday". */
    private fun withoutCopula(text: String): String {
        val last = WORD.findAll(text).lastOrNull() ?: return text
        if (last.value.lowercase(Locale.ROOT) !in COPULAS || WORD.findAll(text).count() < 2) return text
        return EDGES.replace(text.substring(0, last.range.first).trim(), "")
    }

    /**
     * [text] without the joining words a cut left at its end ("Book a table for the"), or null when
     * that leaves only the verb ("Call the" of "I need to call the", the rest said after a pause).
     */
    private fun withoutLooseEnd(text: String): String? {
        val words = text.split(' ').toMutableList()
        var cut = false
        while (words.size > 1 && words.last().lowercase(Locale.ROOT).trimEnd(',', '.') in LOOSE_ENDS) {
            words.removeAt(words.lastIndex)
            cut = true
        }
        if (cut && words.size < 2) return null
        return words.joinToString(" ")
    }

    private fun startsWithWord(text: String, word: String): Boolean =
        WORD.find(text)?.let { it.range.first == 0 && it.value.equals(word, ignoreCase = true) } ?: false

    private fun startsWithAny(text: String, words: Set<String>): Boolean =
        WORD.find(text)?.let { it.range.first == 0 && it.value.lowercase(Locale.ROOT) in words } ?: false

    private fun afterFirstWord(text: String): String =
        WORD.find(text)?.let { text.substring(it.range.last + 1).trim() } ?: ""

    /** The last word of [text] is a German infinitive: lower case, "-en", "-ern", "-eln", "tun", "sein". */
    private fun endsWithInfinitive(text: String): Boolean {
        val last = WORD.findAll(text).lastOrNull() ?: return false
        return isInfinitive(Tok(last.range.first, last.range.last + 1, last.value))
    }

    private fun isInfinitive(token: Tok): Boolean {
        if (!token.text.first().isLowerCase() || token.lower in NOT_INFINITIVES) return false
        return token.lower == "tun" || token.lower == "sein" || INFINITIVE.matches(token.lower)
    }

    private val INFINITIVE = Regex("""\p{L}{2,}(?:en|ern|eln)""")

    private fun isParticiple(token: Tok?): Boolean {
        val word = token?.lower ?: return false
        return word.endsWith("ed") || word in ENGLISH_PARTICIPLES
    }

    // --- The appointment rule's checks ----------------------------------------------------------

    private fun isNegated(said: List<Tok>): Boolean =
        said.any { it.lower in ENGLISH_NOT || it.lower.endsWith("n't") || it.lower in GERMAN_NEGATORS || it.lower.startsWith("kein") }

    /**
     * Said of the past: "I had to go to the dentist", "I called the doctor", "Ich war beim
     * Zahnarzt", "Ich habe beim Arzt angerufen", or of what is done. Making or moving an
     * appointment ("I booked a flight", "Ich habe einen Termin ausgemacht", "The interview was
     * rescheduled", "I've already booked the flight") is not.
     */
    private fun isPast(said: List<Tok>): Boolean {
        for (i in said.indices) {
            val word = said[i].lower
            if (word in DONE_WORDS || word in PAST_FORMS) {
                if (word in BEFORE_ARRANGED && said.drop(i + 1).any { it.lower in ARRANGED }) continue
                return true
            }
            // "I called", "we just missed"
            if (word in SUBJECTS) {
                val verb = said.getOrNull(i + 1)?.let { if (it.lower in ADVERB_WORDS) said.getOrNull(i + 2) else it }
                if (verb != null && verb.lower.endsWith("ed") && verb.lower !in ARRANGED) return true
            }
            // "habe ... angerufen", "bin ... gegangen"
            if (word in GERMAN_HAVE || word in GERMAN_BE) {
                if (said.drop(i + 1).any { isGermanParticiple(it) }) return true
            }
        }
        return false
    }

    private val GERMAN_PARTICIPLE = Regex(
        """(?:(?:ab|an|auf|aus|bei|ein|mit|nach|vor|weg|zu|zurück|her|hin|los|um)?ge\p{Ll}{2,}(?:t|en)|""" +
            """(?:be|er|ver|zer|ent|emp|miss)\p{Ll}{2,}t|\p{Ll}{3,}iert)"""
    )

    private fun isGermanParticiple(token: Tok): Boolean =
        token.text.first().isLowerCase() && token.lower !in NOT_PARTICIPLES && token.lower !in ARRANGED &&
            GERMAN_PARTICIPLE.matches(token.lower)

    private fun isCancelled(said: List<Tok>): Boolean =
        said.any { it.lower in CANCELLED } ||
            said.zipWithNext().any { (a, b) -> (a.lower == "called" && b.lower == "off") || (a.lower == "fällt" && b.lower == "aus") }

    /**
     * Someone else's: "he", "she", "they", "er", "sie" said in it, "Anna has to", "Anna muss", or
     * the verb before the appointment's noun said of someone else ([heldByOther]). Not the "er" of
     * a hesitation ("Er, the meeting ...") nor the "Sie" that says "you", and not one with the
     * speaker in it ("Bob wants a meeting with me").
     */
    private fun isOthers(passage: Passage, said: List<Tok>): Boolean {
        if (said.zipWithNext().any { (a, b) -> a.lower in WITH_WORDS && b.lower in SPEAKER_OBJECTS }) return false
        for (i in said.indices) {
            val word = said[i].lower
            if (word == "er" && passage.text.getOrNull(said[i].end) == ',') continue
            if (word in OTHER_SUBJECTS) return true
            val formal = said[i].text == "Sie" && i > 0
            if (word == "sie" && !formal && said.getOrNull(i - 1)?.lower !in PREPOSITIONS) return true
            if (word in THIRD_PERSON_MODALS && said.getOrNull(i + 1)?.lower == "to") return true
            if (word in GERMAN_MODAL_WORDS && said.getOrNull(i - 1)?.lower != "ich" && said.getOrNull(i + 1)?.lower != "ich") return true
        }
        val noun = said.indexOfFirst(::isAppointmentNoun)
        return noun > 0 && heldByOther(said, noun)
    }

    /**
     * True when the first verb said before the appointment's noun, said[noun], has someone else as
     * its subject. "has", "'s got", "hat", "hast" and "habt" are never said of oneself ("Tom has a
     * job interview", "Mike's got a dentist appointment", "Lisa hat einen Termin"); "have",
     * "haben", "is", "geht" and the like are the speaker's only with "I", "we", "ich", "wir", the
     * polite "Sie", "there" or "it" ("There's a meeting"), with the appointment itself after the
     * verb ("Am Freitag ist das Teammeeting") or with no subject at all ("Meeting with Bob at 3").
     */
    private fun heldByOther(said: List<Tok>, noun: Int): Boolean {
        for (i in 0 until noun) {
            val word = said[i].lower
            val contraction = CONTRACTION.matchEntire(word)
            if (contraction != null) {
                val (subject, ending) = contraction.destructured
                // "Tom's dentist appointment": whose it is, which the noun phrase reads.
                if (ending == "s" && subject !in EXPLETIVES && said.getOrNull(i + 1)?.lower !in AFTER_IS) continue
                return !isSpeaker(subject)
            }
            when (word) {
                in OTHERS_VERBS -> return true
                in OWN_VERBS -> return false
                in ENGLISH_VERBS -> {
                    val subject = said.subList(0, i).lastOrNull { it.lower !in ADVERB_WORDS && it.lower !in OPENERS }
                    return subject != null && !isSpeaker(subject.lower)
                }
                in GERMAN_VERBS -> return germanSubjectIsOther(said, i, noun)
            }
        }
        return false
    }

    /**
     * True when the German verb said[verb] has someone else as its subject: the word before it
     * ("Die Kinder haben", "Anna geht"), or after a time put first the one after it ("Am Freitag
     * haben die Kinder"), not the appointment itself ("Am Freitag ist das Teammeeting").
     */
    private fun germanSubjectIsOther(said: List<Tok>, verb: Int, noun: Int): Boolean {
        val before = said.subList(0, verb).lastOrNull { it.lower !in OPENERS }
        if (before != null) return !isGermanSpeaker(before, first = before === said.first())
        var j = verb + 1
        while (j < noun && said[j].lower in DETERMINERS) j++
        return j < noun && !isGermanSpeaker(said[j], first = false)
    }

    private fun isSpeaker(word: String): Boolean = word in SPEAKER_WORDS || word in EXPLETIVES

    private fun isGermanSpeaker(token: Tok, first: Boolean): Boolean =
        isSpeaker(token.lower) || (token.text == "Sie" && !first)

    /**
     * True when [clause] says the speaker won't go to an appointment its sentence names: "but I'm
     * not going", "I can't make it", "I won't be there", "I'll skip it", "I need to cancel it";
     * "ich gehe nicht hin", "ich komme nicht", "ich schaffe es nicht", "ich sage ab". What is
     * skipped or not gone to must be the appointment or go unnamed: "I'm not going to the gym"
     * leaves a meeting be, and so does "I can't skip the meeting".
     */
    private fun declines(clause: Clause): Boolean {
        val said = clause.tokens.filter { clause.passage.timeAt(it.start) == null }
        val words = said.map { it.lower }
        val first = words.firstOrNull() ?: return false
        // "but Tom isn't going" is his business.
        val mine = words.any { it in SPEAKER_WORDS || it in SPEAKER_CONTRACTIONS } || isNot(first) || first in DECLINE_VERBS
        if (!mine) return false
        for (i in words.indices) {
            val rest = said.drop(i + 1)
            if (words[i] in DECLINE_VERBS && (i == 0 || !isNot(words[i - 1])) && refersBack(rest)) return true
            if (isNot(words[i])) {
                val verb = rest.take(3).indexOfFirst { it.lower in ATTEND_VERBS }
                if (verb >= 0 && refersBack(rest.drop(verb + 1))) return true
            }
        }
        val german = "nicht" in words && words.any { it in GERMAN_ATTEND } ||
            words.any { it in GERMAN_DECLINES } || ("sage" in words && "ab" in words) ||
            (words.any { it == "lasse" || it == "lass" } && "ausfallen" in words)
        return german && said.all { it.lower in GERMAN_BACK || isAppointmentNoun(it) }
    }

    private fun isNot(word: String): Boolean = word in ENGLISH_NOT_WORDS || word.endsWith("n't")

    /** Nothing named but the appointment: "it", "there", "to it", "the meeting". */
    private fun refersBack(rest: List<Tok>): Boolean =
        rest.all { it.lower in BACK_WORDS } || rest.any(::isAppointmentNoun)

    // --- Noun phrases -------------------------------------------------------------------------

    /**
     * An appointment's noun phrase in [text]: its noun with the words before it that say what kind
     * ("dentist appointment", "doctor's appointment", "job interview") and one phrase after it that
     * says with whom or where ("with Bob", "beim Zahnarzt", "nach Berlin").
     *
     * @property ownedByOther "his appointment", "Anna's appointment"
     * @property fromSource "the invoice from the dentist", "die Rechnung vom Zahnarzt"
     * @property describesAnother The English noun says what kind another one is: "meeting room",
     *   "flight ticket". German joins such nouns in one word, so a word after a German noun is
     *   none ("Das Treffen findet statt").
     */
    private class NounPhrase(
        val text: String,
        val start: Int,
        val end: Int,
        val ownedByOther: Boolean,
        val fromSource: Boolean,
        val describesAnother: Boolean
    )

    private fun nounPhrase(text: String): NounPhrase? {
        val tokens = WORD.findAll(text).map { Tok(it.range.first, it.range.last + 1, it.value) }.toList()
        val first = tokens.indexOfFirst(::isAppointmentNoun)
        if (first < 0) return null
        fun joined(a: Int, b: Int) = text.substring(tokens[a].end, tokens[b].start).all { it.isWhitespace() || it == '-' }
        // "dentist appointment": the last noun is the head.
        var head = first
        while (head + 1 < tokens.size && isAppointmentNoun(tokens[head + 1]) && joined(head, head + 1)) head++
        var left = first
        while (left > 0 && first - left < MAX_MODIFIERS && isModifier(tokens[left - 1]) && joined(left - 1, left)) left--
        var right = head + 1
        val before = tokens.getOrNull(left - 1)
        // English joins nouns with a space: in "meeting room" the meeting only says what room.
        val english = tokens[head].lower.removeSuffix("'s") in ENGLISH_APPOINTMENTS && before?.lower !in GERMAN_BEFORE_NOUNS
        val after = tokens.getOrNull(right)?.takeIf { english && joined(head, right) && it.text.first().isLowerCase() }
        val describesAnother = after != null && isModifier(after) && after.lower !in VERB_STOPS && after.lower !in ADVERBS_AFTER
        if (right < tokens.size && tokens[right].lower in ATTACHMENTS && joined(head, right)) {
            var j = right + 1
            if (j < tokens.size && tokens[j].lower in DETERMINERS && joined(j - 1, j)) j++
            // A name ("Bob Smith", German nouns: "dem Chef") or a few lower-case words ("the new
            // client"), up to a verb: "My flight to Berlin leaves at 6".
            val name = j < tokens.size && tokens[j].text.first().isUpperCase()
            var content = 0
            while (j < tokens.size && content < MAX_ATTACHED && isModifier(tokens[j]) && joined(j - 1, j) &&
                tokens[j].text.first().isUpperCase() == name && tokens[j].lower !in VERB_STOPS
            ) {
                j++
                content++
            }
            // English "to" goes to a place by name: "flight to Berlin", not "meeting to discuss".
            if (content > 0 && (tokens[right].lower != "to" || name)) right = j
        }
        val possessed = before != null && (before.lower in OTHERS_POSSESSIVES || isGermanOthersPossessive(before))
        // "Anna's dentist appointment", but not "the doctor's appointment".
        val namedOwner = (left until first).any { i ->
            val word = tokens[i].lower
            word.endsWith("'s") && word.removeSuffix("'s") !in ENGLISH_APPOINTMENTS
        }
        val beforeArticle = if (before != null && before.lower in DETERMINERS) tokens.getOrNull(left - 2) else before
        return NounPhrase(
            text = text.substring(tokens[left].start, tokens[right - 1].end),
            start = tokens[left].start,
            end = tokens[right - 1].end,
            ownedByOther = possessed || namedOwner,
            fromSource = beforeArticle != null && beforeArticle.lower in SOURCE_WORDS,
            describesAnother = describesAnother
        )
    }

    private fun isAppointmentNoun(token: Tok): Boolean {
        val word = token.lower.removeSuffix("'s")
        if (word in ENGLISH_APPOINTMENTS) return true
        if (!token.text.first().isUpperCase()) return false
        return word == "termin" || word.endsWith("termin") || word.endsWith("meeting") || word in GERMAN_APPOINTMENTS
    }

    private fun isModifier(token: Tok): Boolean =
        token.lower !in NOT_MODIFIERS && !token.lower.all { it.isDigit() }

    private fun isGermanOthersPossessive(token: Tok): Boolean =
        token.text.first().isLowerCase() && token.lower in GERMAN_OTHERS_POSSESSIVES

    private const val MAX_MODIFIERS = 3
    private const val MAX_ATTACHED = 3

    // --- Data ---------------------------------------------------------------------------------

    /** Lines read as one text, with the times found in them and the words. */
    private class Passage(val lines: List<TodoPrompt.Line>, window: TodoPrompt.Window) {
        val starts = IntArray(lines.size)
        val text: String
        val times: List<TimeSpan>
        val tokens: List<Tok>

        init {
            val builder = StringBuilder()
            lines.forEachIndexed { i, line ->
                if (i > 0) builder.append(' ')
                starts[i] = builder.length
                builder.append(line.text)
            }
            text = builder.toString()
            times = window.times.mapNotNull { time ->
                val i = lines.indexOfFirst { it === time.line }
                if (i < 0) null else TimeSpan(starts[i] + time.candidate.start, starts[i] + time.candidate.end, time)
            }.sortedBy { it.start }
            tokens = WORD.findAll(text).map { Tok(it.range.first, it.range.last + 1, it.value) }.toList()
        }

        fun timeAt(index: Int): TimeSpan? = times.firstOrNull { index >= it.start && index < it.end }

        fun lineIndexAt(index: Int): Int = starts.indexOfLast { it <= index }.coerceAtLeast(0)
    }

    /** A time listed in the prompt, where it is in its passage's text. */
    private class TimeSpan(val start: Int, val end: Int, val time: TodoPrompt.Time)

    /** One word, where it is in its passage's text. */
    private class Tok(val start: Int, val end: Int, val text: String) {
        val lower: String = text.lowercase(Locale.ROOT).replace('’', '\'')
    }

    /**
     * Tokens [from, to) of [passage], part of the sentence that starts at token [sentenceFrom].
     *
     * @property question The sentence ends with "?"
     * @property joiner The first of the words that joined it to the clause before ("and", "if",
     *   "whether"), in lower case; null for none
     * @property condition It says on what something hangs: "If I win the lottery", "Wenn ich
     *   nach Hause komme"
     * @property afterCondition The clause before it is a [condition]
     * @property reported Its sentence gives someone else's words: "..., said Tom"
     */
    private class Clause(
        val passage: Passage,
        val from: Int,
        val to: Int,
        val sentenceFrom: Int,
        val question: Boolean,
        val joiner: String?
    ) {
        var condition = false
        var afterCondition = false
        var reported = false
        val tokens: List<Tok> get() = passage.tokens.subList(from, to)
        val start: Int get() = passage.tokens[from].start
        val end: Int get() = passage.tokens[to - 1].end
        val times: List<TimeSpan> get() = passage.times.filter { it.start >= start && it.end <= end }
    }

    private val APOSTROPHES = setOf('\'', '’')
    private val POSSESSIVE = Regex("""['’]s$""", RegexOption.IGNORE_CASE)

    // The words that tie a time to the text: "bis Sonntag", "on Friday", "for Friday at 7".
    private val TIES = TodoTexts.TIME_TIES + setOf("for", "für")

    // Words before a comma that, with a time, only set the scene: "First thing tomorrow, ...".
    private val TIME_LEADS = TIES + setOf("first", "thing", "early", "late", "latest", "the", "very", "spätestens", "frühestens")

    private val SUBJECTS = setOf(
        "i", "i'm", "i'll", "i've", "i'd", "you", "he", "she", "it", "we", "they", "there",
        "ich", "du", "er", "sie", "es", "wir", "ihr", "man"
    )
    private val OTHER_SUBJECTS = setOf("he", "she", "they", "he's", "she's", "they're", "er")
    private val AND = setOf("and", "und")
    private val THEN = setOf("then", "dann", "danach", "anschließend")
    private val COORDINATORS = setOf("and", "but", "so", "or", "und", "aber", "oder", "denn")
    private val CONNECTORS = setOf(
        "and", "but", "so", "because", "since", "then", "which", "who", "while", "although", "though", "unless",
        "otherwise", "or", "whereas", "if", "when", "whether", "once",
        "und", "aber", "oder", "denn", "weil", "damit", "dass", "sonst", "wenn", "ob",
        "obwohl", "bevor", "nachdem", "sondern", "falls", "während", "um", "ohne", "statt", "anstatt"
    )

    // A clause that starts with these says on what the next one hangs. "when" and "sobald" say when.
    private val CONDITIONS = setOf("if", "unless", "wenn", "falls", "sofern")

    // A clause these join to the one before asks, it doesn't plan: "..., whether I should".
    private val DOUBT_JOINERS = setOf("whether", "ob")

    // Before a plan's cue, these deny or doubt it: "I don't think I need to", "I doubt I'll".
    private val DOUBT_WORDS = setOf(
        "not", "never", "no", "dont", "doesnt", "didnt", "cant", "doubt", "doubtful", "unsure", "wonder", "wondering",
        "whether", "if", "unlikely", "hardly", "ob", "bezweifle", "zweifle", "unsicher"
    )

    // Who says the words of a sentence that ends in ", said Tom".
    private val SPEECH_VERBS = setOf(
        "said", "says", "asked", "asks", "told", "replied", "added", "explained", "wrote",
        "sagte", "sagt", "meinte", "meint", "fragte", "fragt", "antwortete", "schrieb"
    )

    // What only sets the scene before a comma or a German verb-first cue.
    private val OPENERS = setOf(
        "okay", "ok", "so", "well", "right", "yes", "yeah", "also", "then", "now", "and", "oh", "hey", "alright",
        "und", "aber", "oder", "denn", "dann", "da", "jetzt", "nun", "ja", "gut", "naja", "außerdem", "auch", "noch",
        "übrigens", "zuerst", "danach", "später", "vorher", "nachher", "anschließend", "sonst", "trotzdem", "deshalb",
        "deswegen", "darum", "eigentlich", "wirklich", "unbedingt", "dringend", "endlich", "gleich", "bald", "sofort",
        "hallo", "als", "erstes", "erst"
    )

    // Lower-case words after a time that don't start a clause: "..., morgen um 9 beim Arzt".
    private val CLAUSE_GOES_ON = setOf(
        "am", "um", "im", "in", "an", "auf", "bei", "beim", "mit", "nach", "vor", "zu", "zum", "zur", "für", "von",
        "vom", "aus", "bis", "ab", "gegen", "über", "unter", "der", "die", "das", "den", "dem", "des", "ein", "eine",
        "einen", "einem", "einer", "und", "oder", "aber", "dann", "noch", "auch", "mal", "bitte", "also", "schon",
        "erst", "ja", "gleich", "so", "da", "hier", "dort", "at", "on", "in", "to", "for", "with", "about", "the",
        "a", "an", "and", "or", "please", "then", "too", "also"
    )

    private val ENGLISH_NEGATORS = setOf("don't", "dont", "not", "never", "no")
    private val ENGLISH_NOT = setOf("not", "no", "never", "nothing", "none")
    private val GERMAN_NEGATORS = setOf("nicht", "nie", "niemals", "nichts")
    private val DONE_WORDS = setOf("already", "bereits", "erledigt")
    private val ADVERB_WORDS = setOf(
        "already", "just", "also", "still", "finally", "actually", "really", "usually", "always", "both", "all"
    )
    private val GERMAN_HAVE = setOf("habe", "hab", "hast", "hat", "haben", "habt", "hatte", "hatten")
    private val GERMAN_BE = setOf("bin", "bist", "sind", "seid", "ist")

    // Past forms that say an appointment was: "I had", "I went", "Ich war".
    private val PAST_FORMS = setOf(
        "had", "did", "was", "were", "went", "met", "saw", "spoke", "rang", "attended", "missed", "visited",
        "war", "waren", "warst", "wart", "hatte", "hatten", "hattest", "ging", "gingen", "rief", "musste", "mussten",
        "wollte", "wollten", "konnte", "konnten"
    )

    // Past or done words that, with a word of [ARRANGED] after them, say it was made or moved:
    // "was rescheduled", "already booked", "war ... verschoben".
    private val BEFORE_ARRANGED = setOf("was", "were", "war", "waren", "already", "bereits")

    // Making, moving or confirming an appointment, which is still to come.
    private val ARRANGED = setOf(
        "booked", "scheduled", "arranged", "planned", "organized", "organised", "confirmed", "moved", "rescheduled",
        "fixed", "ausgemacht", "vereinbart", "gebucht", "verschoben", "verlegt", "bestätigt", "geplant", "organisiert",
        "reserviert", "festgelegt", "angesetzt", "eingetragen"
    )
    private val CANCELLED = setOf(
        "cancelled", "canceled", "cancel", "cancels", "abgesagt", "absagen", "entfällt", "ausgefallen", "storniert",
        "stornieren"
    )
    private val ENGLISH_PARTICIPLES = setOf(
        "been", "done", "gone", "made", "sent", "paid", "told", "said", "bought", "brought", "thought", "taken",
        "given", "seen", "met", "left", "got", "gotten", "had", "forgotten", "written", "spoken", "known", "put",
        "set", "come", "become", "kept", "found", "held", "lost", "rung", "begun", "eaten", "driven", "flown",
        "shown", "chosen", "broken", "sold", "felt", "heard", "meant", "taught", "caught", "built", "spent"
    )
    private val NOT_PARTICIPLES = setOf(
        "gegen", "gestern", "genau", "gerne", "gehen", "geben", "gelten", "genug", "gerade", "geschenk",
        "gemeinsam", "genießen", "gewinnen", "gehören", "gefallen", "geschehen", "gelingen"
    )
    private val NOT_INFINITIVES = setOf(
        "einen", "keinen", "meinen", "deinen", "seinen", "ihren", "unseren", "euren", "diesen", "jenen", "denen",
        "ihnen", "allen", "vielen", "anderen", "beiden", "wenigen", "manchen", "solchen", "welchen", "unten", "oben",
        "morgen", "gestern", "übermorgen", "vorgestern", "zusammen", "gegen", "neben", "wegen", "zwischen", "hinten",
        "draußen", "drinnen", "innen", "außen", "eben", "selten", "ebenen", "nachmittagen", "worden"
    )
    private val THIRD_PERSON_MODALS = setOf("has", "needs", "wants", "plans")
    // Not "will", which is English too: "The meeting will be on Friday at 3".
    private val GERMAN_MODAL_WORDS = setOf("muss", "soll", "möchte", "sollte", "müsste")
    private val PREPOSITIONS = setOf(
        "mit", "für", "bei", "zu", "an", "auf", "von", "ohne", "gegen", "um", "über", "unter", "durch", "vor", "nach"
    )

    // --- Whose an appointment is ---------------------------------------------------------------

    // "Tom's got", "they've got", "I'm seeing": a subject and its verb in one word.
    private val CONTRACTION = Regex("""^(\p{L}+)'(s|ve|re|ll|m|d)$""")

    // After "Tom's", these make it "Tom is" or "Tom has", not "Tom's".
    private val AFTER_IS = setOf(
        "got", "gotten", "going", "having", "seeing", "meeting", "visiting", "flying", "taking", "getting", "been",
        "booked", "scheduled", "arranged", "at", "in", "off", "back", "out", "away", "also", "still", "just", "already"
    )
    private val SPEAKER_WORDS = setOf("i", "we", "ich", "wir", "let's", "lets")
    private val SPEAKER_CONTRACTIONS = setOf("i'm", "i'll", "i've", "i'd", "we're", "we'll", "we've", "we'd")
    private val SPEAKER_SUBJECTS = setOf("i", "we")
    private val SPEAKER_OBJECTS = setOf("me", "us", "mir", "mich", "uns")
    private val WITH_WORDS = setOf("with", "for", "mit", "für")

    // Subjects that say nothing of whose it is: "There's a meeting", "It's the dentist", "Morgen geht es zum Arzt".
    private val EXPLETIVES = setOf("there", "it", "that", "this", "here", "es", "das", "dies", "man", "da", "dort", "hier")

    // Never said of oneself.
    private val OTHERS_VERBS = setOf("has", "hat", "hast", "habt", "bist", "seid", "gehst", "fährst", "fliegst")

    // Said only of oneself.
    private val OWN_VERBS = setOf("habe", "hab", "bin", "gehe", "fahre", "fliege")

    // English verbs before an appointment noun whose subject says whose it is: "The kids have", "Tom is going".
    private val ENGLISH_VERBS = setOf(
        "have", "is", "are", "will", "would", "should", "can", "could", "must", "might", "may", "go", "goes", "see",
        "sees", "meet", "meets", "fly", "flies", "visit", "visits", "take", "takes", "get", "gets", "got", "need",
        "needs", "want", "wants", "plan", "plans", "booked", "scheduled", "arranged", "planned", "organized",
        "organised", "confirmed", "moved", "rescheduled", "fixed", "set", "made"
    )

    // German verbs before an appointment noun whose subject says whose it is: "Die Kinder haben", "Anna geht".
    private val GERMAN_VERBS = setOf(
        "haben", "sind", "ist", "geht", "gehen", "fährt", "fahren", "fliegt", "fliegen", "kommt", "kommen"
    )

    // --- Not going ----------------------------------------------------------------------------

    private val ENGLISH_NOT_WORDS = setOf("not", "never", "cannot", "cant", "wont", "dont")
    private val DECLINE_VERBS = setOf("skip", "skipping", "cancel", "cancelling", "canceling")
    private val ATTEND_VERBS = setOf(
        "go", "going", "come", "coming", "attend", "attending", "make", "making", "join", "joining", "be"
    )

    // What may follow a word of not going when it is the appointment that isn't gone to.
    private val BACK_WORDS = setOf(
        "it", "that", "this", "there", "then", "though", "anyway", "either", "after", "all", "to", "the", "one",
        "please", "really", "just", "at", "go", "going", "make", "be", "attend", "come", "today", "tonight"
    )
    private val GERMAN_ATTEND = setOf(
        "hin", "hingehen", "hinfahren", "gehen", "gehe", "geh", "kommen", "komme", "teilnehmen", "teil", "dabei",
        "schaffe", "schaff", "schaffen", "mitmachen"
    )
    private val GERMAN_DECLINES = setOf("absagen", "schwänze", "schwänzen", "ausfallen")

    // The other words a German clause of not going may have: "Ich gehe da aber nicht hin".
    private val GERMAN_BACK = GERMAN_ATTEND + setOf(
        "ich", "wir", "da", "dahin", "dorthin", "dort", "es", "das", "ihn", "nicht", "dann", "aber", "also", "eh",
        "sowieso", "leider", "wohl", "werde", "will", "kann", "muss", "bin", "sein", "auch", "jedenfalls", "mache",
        "mit", "nehme", "sage", "ab", "lasse", "lass", "ausfallen", "absagen", "schwänze", "schwänzen", "heute",
        "doch", "trotzdem", "den", "die", "dem", "der"
    )

    // --- What a plan does ---------------------------------------------------------------------

    // English verbs of a plan that is no thing to do.
    private val ENGLISH_STATES = setOf(
        "miss", "hope", "think", "believe", "feel", "love", "hate", "agree", "disagree", "assume", "suppose", "guess",
        "wonder", "imagine", "mean", "know", "sleep", "rest", "relax", "hurry", "rush", "worry", "calm", "sit"
    )

    // "be" after a stronger cue says where to be: "I need to be at the airport".
    private val ENGLISH_PLACES = setOf(
        "at", "in", "on", "there", "here", "home", "back", "ready", "inside", "outside", "upstairs", "downstairs", "downtown"
    )

    // Leaving, unless with somewhere to go or something to get: "Go to the bank", "Get milk".
    private val LEAVING = setOf("go", "leave", "run", "dash", "head", "get")
    private val LEAVING_REST = setOf(
        "now", "home", "away", "off", "out", "going", "back", "early", "soon", "right", "then", "too", "there", "to",
        "bed", "sleep", "some", "rest", "first", "already", "straight", "quickly", "in", "a", "bit", "minute",
        "second", "moment"
    )
    private val PRONOUNS = setOf(
        "it", "that", "this", "them", "him", "her", "you", "me", "us", "one", "those", "these", "there", "here", "so",
        "too", "then", "something", "anything", "everything"
    )
    private val PARTICLES = setOf("up", "down", "off", "out", "in", "on", "back", "over", "away", "around", "through")
    private val VAGUE_TIMES = setOf("later", "soon", "sometime", "again")

    // German infinitives of a plan that is no thing to do, whatever the cue.
    private val GERMAN_STATES = setOf(
        "vermissen", "hoffen", "glauben", "meinen", "wissen", "freuen", "zugeben", "gestehen", "werden", "schlafen",
        "ausruhen", "entspannen", "beeilen", "auflegen"
    )

    // ... and after a weaker cue ("ich werde", "ich möchte"), where they say what will be or are said in a meeting.
    private val GERMAN_WEAK_STATES = setOf(
        "sein", "bleiben", "haben", "kommen", "bedanken", "danken", "verabschieden", "vorstellen", "begrüßen",
        "zusammenfassen", "anfangen", "beginnen", "erwähnen", "betonen", "hinweisen", "entschuldigen", "fassen",
        "sagen", "schauen", "gucken", "versuchen"
    )

    private val GERMAN_ORDERING = setOf("zahlen", "bezahlen", "bestellen")

    // Objects that name nothing: "dich anrufen", "mich darum kümmern", "das erledigen".
    private val GERMAN_PRONOUNS = setOf(
        "dich", "dir", "sie", "ihn", "ihm", "ihr", "euch", "es", "das", "mich", "mir", "uns", "darum", "drum", "daran",
        "dran", "darüber", "damit", "dafür", "dann", "später", "mal", "wieder"
    )

    // "sein" and "bleiben" after a stronger cue say where to be: "Ich muss um 9 beim Arzt sein".
    private val GERMAN_PLACES = setOf(
        "im", "in", "am", "an", "auf", "bei", "beim", "zum", "zur", "zu", "vor", "hinter", "unter", "über", "da",
        "dort", "hier", "daheim", "zuhause", "zurück", "drin", "draußen"
    )

    // "gehen" with only these is leaving: "jetzt gehen", "nach Hause gehen".
    private val GERMAN_LEAVING = setOf("jetzt", "gleich", "nach", "hause", "heim", "los", "weg", "schlafen", "ins", "bett", "schon", "bald")

    // A copula the time's removal left at a request's end: "Don't forget, the party is on Saturday".
    private val COPULAS = setOf("is", "are", "ist", "sind")

    // Words a todo can't end on: what is left when a sentence is cut after them.
    private val LOOSE_ENDS = setOf(
        "the", "a", "an", "to", "of", "for", "with", "and", "or", "at", "on", "in", "by", "from", "about", "my",
        "your", "our", "his", "her", "their",
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "zu", "zum", "zur", "mit",
        "für", "und", "oder", "beim", "bei", "vom", "von", "mein", "meine", "meinen", "meinem"
    )

    // --- Appointment nouns ---------------------------------------------------------------------

    private val ENGLISH_APPOINTMENTS = setOf("appointment", "meeting", "interview", "flight", "dentist", "doctor")
    private val GERMAN_APPOINTMENTS = setOf("besprechung", "treffen", "flug", "zahnarzt", "arzt")
    private val ATTACHMENTS = setOf("with", "at", "for", "about", "to", "mit", "beim", "bei", "zum", "zur", "nach", "für", "wegen")

    // Words before a noun that make it German: "Das Meeting findet am Montag statt".
    private val GERMAN_BEFORE_NOUNS = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines", "mein", "meine",
        "meinen", "meinem", "meiner", "unser", "unsere", "unseren", "unserem", "unserer", "dein", "deine", "deinen",
        "deinem", "deiner", "zum", "zur", "beim", "im", "am", "vom", "kein", "keine", "keinen", "nächste", "nächsten"
    )

    // Words after an appointment noun that leave it the thing said: "the flight back", "the doctor again".
    private val ADVERBS_AFTER = setOf("back", "home", "out", "again", "there", "here", "first", "together")

    // Verbs that follow an appointment's noun phrase: "the meeting with the client starts at 3".
    private val VERB_STOPS = setOf(
        "starts", "start", "begins", "begin", "ends", "leaves", "leave", "arrives", "departs", "lands", "takes",
        "goes", "runs", "comes", "gets", "moves", "lasts", "happens", "kicks", "kick", "wraps", "finishes", "closes",
        "opens", "boards", "geht", "beginnt", "fängt", "startet", "ist"
    )
    private val DETERMINERS = setOf(
        "the", "a", "an", "my", "our", "your", "this", "that", "these", "those", "some",
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
        "mein", "meine", "meinen", "meinem", "meiner", "unser", "unsere", "unseren", "unserem", "unserer",
        "dein", "deine", "deinen", "deinem", "deiner"
    )
    private val OTHERS_POSSESSIVES = setOf("his", "her", "their", "its", "sein", "seine", "seinen", "seinem", "seiner", "seines")
    private val GERMAN_OTHERS_POSSESSIVES = setOf("ihr", "ihre", "ihren", "ihrem", "ihrer", "ihres")
    private val SOURCE_WORDS = setOf("from", "of", "von", "vom", "des")

    // Words that start what a verb is done to: "booking the hotel", "calling his mother".
    private val OBJECT_STARTS = DETERMINERS + OTHERS_POSSESSIVES

    // Words that don't describe an appointment noun next to them: articles, pronouns, verbs,
    // prepositions, conjunctions and the like.
    private val NOT_MODIFIERS: Set<String> = DETERMINERS + SUBJECTS + PREPOSITIONS + ATTACHMENTS + setOf(
        "his", "her", "their", "its", "sein", "seine", "seinen", "seinem", "seiner", "ihr", "ihre", "ihren", "ihrem",
        "ihrer", "me", "him", "us", "them", "mich", "mir", "uns", "dich", "dir", "ihm", "ihn",
        "is", "are", "was", "were", "be", "been", "am", "have", "has", "had", "got", "do", "does", "did", "will",
        "would", "shall", "should", "can", "could", "may", "might", "must", "need", "needs", "go", "going",
        "ist", "sind", "war", "waren", "bin", "bist", "habe", "hab", "hast", "hat", "haben", "hatte", "hatten",
        "gibt", "wird", "werde", "werden", "muss", "soll", "kann", "will", "geht", "gehe", "fängt", "beginnt",
        "on", "in", "from", "of", "by", "after", "before", "until", "into", "um", "am", "im", "an", "auf", "vom",
        "aus", "bis", "ab", "über", "unter", "wegen", "and", "or", "but", "so", "und", "oder", "aber", "denn",
        "dass", "weil", "also", "too", "then", "please", "just", "really", "still", "now", "okay", "ok", "well",
        "auch", "noch", "außerdem", "dann", "bitte", "mal", "jetzt", "ja", "nein", "nicht", "not", "no", "never",
        "next", "last", "every", "each", "any", "kein", "keine", "keinen", "nächste", "nächsten", "nächster",
        "kommende", "kommenden", "jede", "jeden", "jeder", "diese", "dieser", "diesen", "dieses",
        "what", "when", "where", "who", "why", "how", "which", "was", "wann", "wo", "wer", "wie", "warum", "welche",
        "here", "da", "dort", "hier", "today", "tomorrow", "heute", "morgen", "s"
    )

    // English verbs a thing to do starts with: "... and pick up the kids", "I'll call the bank".
    private val TODO_VERBS = setOf(
        "call", "ring", "phone", "text", "email", "e-mail", "message", "write", "send", "mail", "post", "buy", "get",
        "pick", "collect", "fetch", "bring", "take", "drop", "return", "order", "book", "reserve", "cancel",
        "schedule", "reschedule", "move", "pay", "transfer", "renew", "sign", "submit", "file", "print", "scan",
        "copy", "check", "ask", "tell", "remind", "invite", "meet", "visit", "see", "go", "drive", "walk", "feed",
        "water", "clean", "wash", "tidy", "fix", "repair", "replace", "change", "charge", "prepare", "finish",
        "start", "make", "cook", "bake", "read", "review", "update", "upload", "download", "install", "look",
        "find", "search", "sort", "pack", "unpack", "ship", "deliver", "wrap", "plan", "organize", "organise",
        "arrange", "confirm", "contact", "follow", "reply", "respond", "answer", "apply", "register", "log",
        "back", "test", "try", "practice", "practise", "study", "learn", "train", "run", "shop", "grab", "put",
        "set", "hang", "mow", "vacuum", "iron", "fold", "empty", "fill", "refill", "recycle", "throw", "sell",
        "donate", "lend", "borrow", "give", "share", "show", "talk", "discuss", "speak", "research", "measure",
        "count", "note", "record", "save", "delete", "archive", "close", "open", "lock", "unlock", "turn", "switch",
        "plug", "unplug", "leave", "stop", "keep", "help", "thank", "congratulate", "celebrate", "apologize",
        "apologise", "do", "finalize", "finalise", "draft", "edit", "proofread", "translate", "compare", "decide",
        "choose", "pick", "repaint", "paint", "mop", "dust", "sweep", "load", "unload", "park", "fuel", "refuel",
        "let", "forward", "attach", "notify", "inform", "approve", "process", "settle", "restock", "service",
        "inspect", "exchange", "swap", "rent", "hire", "reset", "restart", "reboot", "extend", "defrost", "hoover",
        "deal", "handle", "chase", "double", "figure", "work", "sort"
    )
}
