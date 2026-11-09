package com.satori.domain.usecase.todo

import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoCertainty
import com.satori.domain.model.TodoKind
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.reading.ReadableSegments
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.time.TimeCandidate
import com.satori.domain.time.TimeExpressionParser
import com.satori.domain.time.TimePolicy
import com.satori.domain.time.TimePrecision
import com.satori.domain.usecase.llm.AnswerChecks
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.PromptRules
import com.satori.domain.usecase.llm.TextLanguageNote
import kotlinx.coroutines.CancellationException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Finds the todos in a recording: what the speaker means to do or was asked to do, each with a
 * due time only when one was said; and the ideas put forward in it for how something could be,
 * which nobody said they will do ("there should be an option to ..."). An idea is stored as an
 * idea ([TodoStatus.IDEA]): a task with no due time, which never reminds.
 *
 * Parse first, then the model links. A [TimeExpressionParser] finds the time expressions in
 * every segment, anchored at the moment that segment was spoken, in the recording's zone. A
 * sentence a segment leaves open (voice detection cuts at short pauses) is given to the parser
 * with the next segment when that one goes on with it ([TodoPrompt.goesOn]), so "um 9" after
 * "Morgen muss ich" is tomorrow's nine. The model reads the segments as "S3 text" with their
 * times listed under them ("T2 = 'Freitag um 9' -> Fr. 16. Okt. 09:00 (Freitag)"), below a header
 * that says when the recording started and what is noted already ([TodoPrompt.header]); its
 * system prompt is the same for every call. Its answer is held to a JSON schema built for the
 * call, so it can only cite segments and times that exist. The model never does date arithmetic:
 * a due time is the parser's reading of the time the model picked.
 *
 * A 1B model copies whole sentences and misses requests as plain as "Remind me to call the bank
 * tomorrow at ten", so the same segments are also read without it ([ExplicitTodoSpotter]): what
 * the speaker asks to be reminded of, plans in the first person ("I need to", "ich muss") and
 * appointments said with their time. Their due time and reminder are worked out as for a time the
 * model picked. A model todo that repeats one of these from the same segment becomes one with it
 * ([TodoTexts.isWithin]): it keeps the spotter's text when the model's is longer or holds a
 * sentence break, else the model's. So does one from the same segment that says it in other words,
 * due at the same moment and naming a thing in common ("Phone the bank regarding the loan" for
 * "Call the bank about the loan", "Arztpraxis anrufen" for "Beim Arzt anrufen"), which keeps the
 * spotter's text. Either way the todo has the time of whichever has one, the spotter's when both do,
 * except the model's clock time on the spotter's day when the spotter's clause named more than
 * one; an appointment when either says so. The spotter's todos stay when the model fails a part
 * or can't be loaded. A model text with a sentence break keeps only its first sentence, and loses
 * the copula and the article of a sentence copied whole ([TodoTexts.cleanAnswer]).
 *
 * Every todo and idea in the answer is then checked:
 * - It must cite a segment of the call, or it is dropped.
 * - It must not repeat its call's line of instructions ([com.satori.domain.usecase.llm.PromptEcho])
 *   or hold a key of the answer ("said: ..."), nor say what the text is about instead of naming a
 *   thing to do ("The text describes ...", [TodoGate.describesText]): on 2026-10-16 the 1B stored
 *   both as open to-dos of a Malayalam recording. Nor, since 0.7.3, give back the rules of the
 *   system prompt or of the header's own words ([PromptRules]), unless it is made of words said in
 *   the part.
 * - Its words must be in what it cites ([TodoGrounding]): the cited segments and the ones their
 *   sentences run on into or come from, else it is dropped, whatever time it has, before the rules
 *   for repeats below can keep it for a new time. So is a greeting, and a heading of a list said
 *   aloud ([TodoGate.isHeadingOrGreeting]) unless it is an appointment with its time. The words and
 *   the greetings go for the spotter's to-dos too, which follow a cue and so head no list;
 *   [foundLog] counts them.
 * - A to-do of a Malayalam line, in which no cue of English or German can be read, must name an
 *   action or a commitment in its own words ([TodoGate.namesAction]), but for an appointment,
 *   which may name only what is on ("Doctor's appointment"); and when the live model wrote it, not
 *   Gemma 4 E2B ([LlmRepository.isSwitchedModelInMemory]), the clause of the words it quotes from
 *   the line must ask for it, promise it, or say when in a clause not told in the past
 *   ([MalayalamTodoCues.holds]). An idea of a Malayalam line from the live model must be put
 *   forward in that clause ([MalayalamTodoCues.holdsIdea]): on 2026-10-16 the 1B gave a reading's
 *   retelling as an idea.
 * - It goes through [TodoGate]: a fragment, a filler, a question or an uncued copy of its sentence
 *   is dropped, a strong cue said as a plan or a request keeps a todo open with the capture it
 *   says (an idea the model saw in it becomes that todo), an idea cue with no first-person cue
 *   makes it an idea unless it has a clock time or is an appointment, and a todo without a cue,
 *   or with only the noun "to do", becomes a suggestion.
 * - The same text is never both a to-do and an idea: an item with the text of the recording's own
 *   item of the other kind is dropped, and of two such items of one reading the stronger stays, an
 *   open to-do over an idea, an idea over a suggestion.
 * - An idea takes no time, whatever time the answer names for it.
 * - Its time must be one of the listed ones, said in a cited segment or the one next to it, and
 *   not over when it was said: a clock time, or a range's end, before that moment ("at 8 this
 *   morning" said at nine), or a day, or a run's last day, before that day ("yesterday").
 *   Otherwise the todo keeps no time.
 * - A clock time picked while its day was said apart from it in the same sentence keeps the day
 *   in its words ("Morgen um 9"). A day picked that way gets the clock time only with evidence
 *   that it belongs to this todo and not to another clause ("Morgen muss ich die
 *   Steuererklärung abgeben, um 18 Uhr ist dann noch Training" stays date-only): see [clockFor].
 * - Its text loses the spoken lead-in and the time words, and is cut to [TodoTexts.MAX_WORDS]
 *   words; a text with nothing left drops the todo.
 *
 * A due time is the clock time said, for a range its start; a part of the day alone
 * ("heute Abend") sits at the part's usual hour without a clock time; a day at the policy's
 * hour, for a run of days ("bis Ende des Monats") its likely day.
 *
 * Repeats are dropped only when they add nothing. A todo taken from a segment a known todo came
 * from is that todo said again when their words overlap ([TodoTexts.isRepeat]; for one the
 * spotter found, when the words of one are all in the other, [TodoTexts.isWithin]), whatever its
 * status and time: reading a recording again brings back neither done nor dismissed todos, nor
 * twins of open ones. Otherwise a new todo repeats a known one only when the texts are
 * near-identical ([TodoTexts.isDuplicate]), the known one is still pending (open or suggested),
 * the new one adds no due time and no other day, and it doesn't commit to what was only
 * suggested. Two mentions in one run that overlap ([TodoTexts.isRepeat]) with due times that
 * fit become one todo, which keeps the fuller text, the more precise due time, the commitment and
 * both sources; mentions on different days stay apart. An idea repeats one from the same segments
 * as for todos, else only a known idea of near-identical words; a known idea is no pending todo,
 * and an idea and a todo never become one: of the same words only one of them is kept (above).
 *
 * What the speaker asked to note or committed to becomes an open todo; what someone else asked
 * for, and what the model only inferred, becomes a suggestion. A recording keeps at most
 * [TodoGate.MAX_SUGGESTIONS] suggestions, the ones it has from an earlier reading included, and the
 * model's new ones are ranked across all its parts for the places left: one with a due time first,
 * then what someone asked for, then what the model inferred, each in spoken order.
 * A due time said firmly is confirmed; one said with a hedge in its sentence ("maybe on Friday",
 * [TodoTexts.HEDGES]), or read ambiguously by the parser, is tentative, whatever the model thinks
 * of it. An appointment with a clock time reminds an hour ([APPOINTMENT_LEAD_HOURS]) before it,
 * or at its start when it was mentioned less than an hour ahead; other todos with a clock time remind at
 * that time. A part of the day reminds at its usual hour, an appointment an hour before it, and
 * date-only todos at the policy's hour on that day. No reminder is set for a moment that had
 * passed when the words were said ("today", said after nine); the due time stays.
 *
 * A long recording is read in parts of about [TodoPrompt.MAX_WINDOW_CHARS] characters, one
 * model call each, and each part is told the todos and ideas found before it, then the
 * recording's own, the known ideas and the other pending todos, as many as
 * [TodoPrompt.MAX_NOTED] allows: with many pending todos the ideas would otherwise never be
 * shown, and the model would give them again. Of those from other recordings, only the ones whose
 * words are in the part ([TodoGrounding]): only they can be said again in it, and the 1B copied
 * the others as new to-dos, with a time of the part (2026-10-14). Each part's answer
 * goes to [answerLog] as the model gave it. A part the model fails to answer gives only the
 * spotter's todos; a model that can't be loaded ends the run with what was found so far, the
 * spotter's todos of that part included.
 *
 * A recording in Malayalam is read the same way, in parts of as many tokens
 * ([com.satori.domain.usecase.llm.TextBudget]), and its to-dos are written in the language
 * of the call ([languageCode]: English, as the owner reads a Malayalam recording's to-dos): the
 * header's line of instructions says to read the Malayalam, English words in Malayalam letters as
 * English, and to write in English ([TextLanguageNote]). The spotter, the time parser and the
 * gate's cues know English and German only, so its to-dos come from the model alone, without a due
 * time. Each item also gives the words of its segment it was read from, as written there (the
 * answer's "said", [TodoPrompt.schema] with quotes, asked for at the end of that line), which are
 * found in the segment in its own script. Every part in another language than the to-dos gives
 * them so, with the order to read its language: a Translate recording's, written in its target
 * language, which also reads no part with the spotter ([invoke]'s spotsOutright), and a German
 * part of a recording whose to-dos are English, which does, as the English and German rules read
 * both. So does a part that holds a line in Malayalam script among lines in Latin letters, with
 * the order to read the Malayalam: a recording switched to Malayalam while it recorded keeps the
 * lines Parakeet wrote before the switch.
 *
 * Since 0.8.0 the finishing and the page give a Malayalam recording's lines as their English
 * readings (ExtractSessionTodosUseCase, design R6), with each line as it was said by its id
 * ([invoke]'s asSaid): they are read as an English part is, the spotter, the time parser and the
 * gate's cues included, the prompt an English recording's (D6), and each item cites its line. A due
 * time read in such a line is kept only where the line as it was said holds a time
 * ([MalayalamTodoCues.holdsTime], D9): the reader's mistake in a date sets no due time. The gate of
 * Malayalam lines above is off for them; [foundLog] counts what it would have dropped
 * ([FoundTodosLog.ungated]).
 *
 * The words copied for an item stand in for its text only where its text can't be found in what
 * it cites: segments in Malayalam script, or in another language than the to-dos. An item of a
 * segment in Latin letters of the to-dos' own language is checked by its own words, as in any
 * English or German part. And an item whose text is itself mostly in another script than the Latin
 * one is dropped ([FoundTodosLog.Why.WRITTEN_IN_ANOTHER_SCRIPT]): the app writes to-dos and ideas in
 * English or German only, and a line of the transcript copied as it was said passes any check of its
 * words. On 2026-10-15 a Malayalam recording got two such lines as open to-dos, in Malayalam script;
 * since 0.7.3 Telugu, Bengali and every other script go too. The words copied for an item ("said")
 * are never held to it: they are in the transcript's own script.
 *
 * @property llmRepository Runs the model; loaded on demand.
 * @property resourceProvider Supplies the prompt and its header in the todo language.
 * @property timeParser Finds and resolves the time expressions in each segment.
 * @property now Clock for the todos' createdAt and updatedAt.
 * @property newId Makes the id of each new todo.
 * @property answerLog Where the model's raw answer for each part goes; [AnswerLog.NONE] keeps none.
 * @property foundLog Where the counts of the found items dropped before anything is kept go
 */
class ExtractTodosUseCase(
    private val llmRepository: LlmRepository,
    private val resourceProvider: ResourceProvider,
    private val timeParser: TimeExpressionParser,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val answerLog: AnswerLog = AnswerLog.NONE,
    private val foundLog: FoundTodosLog = FoundTodosLog.NONE
) {
    companion object {
        /**
         * The order of the field log's lines of what a reading dropped ([FoundTodosLog]): the order
         * the reasons are checked in, the answer's limit first and the repeats last.
         */
        private val LOG_ORDER = listOf(
            FoundTodosLog.Why.OVER_LIMIT,
            FoundTodosLog.Why.NO_SEGMENT,
            FoundTodosLog.Why.NO_TEXT,
            FoundTodosLog.Why.ONLY_A_TIME,
            FoundTodosLog.Why.NO_USABLE_TIME,
            FoundTodosLog.Why.WRITTEN_IN_ANOTHER_SCRIPT,
            FoundTodosLog.Why.ECHOES_PROMPT,
            FoundTodosLog.Why.ECHOES_RULES,
            FoundTodosLog.Why.DESCRIBE_THE_TEXT,
            FoundTodosLog.Why.NOT_IN_SEGMENT,
            FoundTodosLog.Why.HEADING_OR_GREETING,
            FoundTodosLog.Why.NO_ACTION,
            FoundTodosLog.Why.NO_MALAYALAM_CUE,
            FoundTodosLog.Why.NO_MALAYALAM_IDEA,
            FoundTodosLog.Why.QUESTION,
            FoundTodosLog.Why.TOO_SHORT,
            FoundTodosLog.Why.CUT_OFF,
            FoundTodosLog.Why.UNCUED_COPY,
            FoundTodosLog.Why.ALREADY_NOTED,
            FoundTodosLog.Why.SAME_AS_ANOTHER
        )

        /** A time said this many segments away from the todo's own still belongs to it. */
        internal const val MAX_TIME_DISTANCE = 1

        /** A parser reading below this confidence ("nächsten Freitag") makes the time tentative. */
        internal const val LOW_CONFIDENCE = 0.5f

        /** An appointment with a clock time reminds this long before it (user decision, 2026-10-10). */
        internal const val APPOINTMENT_LEAD_HOURS = 1L

        // A clock time this close before the moment it was said still counts ("at 10:42").
        private const val SAID_TOLERANCE_MINUTES = 1L

        // TodoPrompt.MAX_ITEMS items of up to about 70 tokens, plus the whitespace the grammar allows.
        private const val MAX_TOKENS = 768

        // With the words copied from a segment for each item: 8 Malayalam words are about 27 tokens.
        private const val MAX_TOKENS_QUOTED = 1_024

        /**
         * The zone to read a recording's times in: its stored zone, or the phone's when it has
         * none (recordings from before zones were stored) or the stored id is unknown.
         */
        fun zoneOf(zoneId: String?): ZoneId =
            zoneId?.let {
                try {
                    ZoneId.of(it)
                } catch (e: DateTimeException) {
                    null
                }
            } ?: ZoneId.systemDefault()
    }

    /**
     * What one reading of a recording found.
     *
     * @property todos The new todos, suggestions and ideas, in transcript order, with fresh ids
     * @property overCap How many of the model's suggestions were left out, over
     *   [TodoGate.MAX_SUGGESTIONS]
     * @property answered Whether the model read every part: false when it couldn't be loaded or
     *   a part's answer failed, so that only what was said outright was found there
     */
    data class Found(val todos: List<Todo>, val overCap: Int = 0, val answered: Boolean = true)

    /**
     * @param sessionId The recording; only its complete segments are read.
     * @param segments The recording's transcript segments, in any order.
     * @param zone The zone the recording was made in (see [zoneOf]).
     * @param languageCode "en" or "de": the language of the prompt and of the todo texts.
     * @param existingTodos Todos and ideas the recording or the user already has, in any status.
     *   The pending todos (open or suggested), the ideas and the recording's own are shown to the
     *   model as already noted. A new todo or idea taken from the same segments as one of them
     *   repeats it when their words overlap, as when a recording is read again; a todo that adds
     *   nothing to a pending todo from elsewhere is dropped too, and so is an idea that a known
     *   idea says already. The recording's own suggestions count against its
     *   [TodoGate.MAX_SUGGESTIONS].
     * @param policy How ambiguous times are read; its dateOnlyHour is the hour a date-only todo
     *   is due and reminds.
     * @param spotsOutright Whether the spotter reads the segments ([ExplicitTodoSpotter]): not for
     *   a recording whose to-dos are written in another language than was said by the owner's
     *   choice, a Translate recording's (in its target language), as the spotter's would be in the
     *   language spoken. It reads no part in Malayalam either, which it doesn't know.
     * @param asSaid The lines as they were said, by id, of the [segments] given as their English
     *   readings (0.8.0, design D9): they are read as English, and a due time read in one is kept
     *   only when its line as it was said holds a time ([MalayalamTodoCues.holdsTime]), so a date
     *   the reader made up or got wrong sets none; the item keeps its text and cites its line, and
     *   the gate of Malayalam lines is off for them (what it would have dropped is counted,
     *   [FoundTodosLog.ungated]). Empty for segments given as they were said.
     * @return The new todos and ideas, in transcript order, with fresh ids: none when there is
     *   nothing to do, and only the ones said outright when the model fails.
     */
    suspend operator fun invoke(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        zone: ZoneId,
        languageCode: String,
        existingTodos: List<Todo> = emptyList(),
        policy: TimePolicy = TimePolicy(),
        spotsOutright: Boolean = true,
        asSaid: Map<String, String> = emptyMap()
    ): Found {
        val lines = linesOf(sessionId, segments, zone, policy)
        if (lines.isEmpty()) return Found(emptyList())
        // D9: a time read in a line's reading counts only where the line as it was said says when.
        val heard: (TodoPrompt.Time) -> Boolean = { time -> asSaid[time.line.segment.id]?.let(MalayalamTodoCues::holdsTime) ?: true }

        val locale = TodoPrompt.localeFor(languageCode)
        val systemPrompt = resourceProvider.getTodoExtractionPrompt(languageCode)
        val header = resourceProvider.getTodoHeaderTemplate(languageCode)
        // What no item may give back: the rules of the system prompt and of the header's own words.
        val rules = PromptRules.of(systemPrompt, header)
        val known = existingTodos.map { Known(it, TodoTexts.key(TodoTexts.clean(it.text) ?: it.text)) }
        // The recording's own, whose text a find of the other kind must not have.
        val ownKnown = known.filter { it.todo.sessionId == sessionId }
        // The recording's own todos and ideas first, its done and dismissed ones too, so that
        // reading it again leaves them be; then the ideas, so the model doesn't give them again,
        // and the other pending todos.
        val alreadyNoted = existingTodos
            .filter { it.isPending() || it.status == TodoStatus.IDEA || it.sessionId == sessionId }
            .sortedBy { if (it.sessionId == sessionId) 0 else if (it.status == TodoStatus.IDEA) 1 else 2 }
        // The places a reading leaves for suggestions: those of an earlier one count.
        val suggestionsLeft = TodoGate.MAX_SUGGESTIONS -
            existingTodos.count { it.sessionId == sessionId && it.status == TodoStatus.SUGGESTED }
        val found = mutableListOf<Draft>()
        val dropped = Dropped()
        var answered = true

        for ((part, window) in TodoPrompt.windows(lines).withIndex()) {
            val windowText = TodoPrompt.textOf(window)
            // To-dos written in another language than the part: a Malayalam recording's, a Translate one's.
            val spoken = TextLanguageNote.spokenOtherThan(windowText, languageCode)
            // Lines in Malayalam script, also among lines in Latin letters (those before a switch to Malayalam).
            val malayalamLines = window.lines.filter { LanguageDetector.isMalayalam(it.text) }
            val quoted = spoken != null || malayalamLines.isNotEmpty()
            // What the speaker says outright, found without the model: it stays whatever the model does.
            val spots = spotsOutright && spoken != LanguageDetector.MALAYALAM
            val spotted = if (spots) {
                ExplicitTodoSpotter.spot(window).mapNotNull { spot -> draftOf(spot, policy, heard) ?: dropped.drop(FoundTodosLog.Why.NO_USABLE_TIME) }
            } else {
                emptyList()
            }
            if (!modelLoaded()) {
                answered = false
                spotted.filter { dropped.keeps(it, window, spoken, AnswerChecks(rules = rules, spoken = windowText), smallModel = false) }
                    .filter { dropped.isNew(it, known) }
                    .forEach { dropped.addUnlessOtherKind(found, it.ungatedBy(window, asSaid, smallModel = false), ownKnown) }
                break
            }
            // Read once the model is in: Gemma 4 E2B reads Malayalam, the live model's to-dos of it need a cue there.
            val smallModel = !llmRepository.isSwitchedModelInMemory
            // Of other recordings' items, only those that can be said again here. A window without
            // Malayalam letters (a Malayalam recording's English readings, since 0.8.0) shows none
            // that holds some, as an older version's to-do copied from a Malayalam line does: the
            // model reading it never reads Malayalam (D7). They still count as known, so a repeat of
            // one is dropped all the same.
            val englishOnly = window.lines.none { ReadableSegments.needsReader(it.text) }
            val notedHere = alreadyNoted.filter { it.sessionId == sessionId || TodoGrounding.isGrounded(it.text, windowText) }
            val noted = (found.map { it.text } + notedHere.map { it.text }).distinct()
                .filterNot { englishOnly && ReadableSegments.needsReader(it) }
                .take(TodoPrompt.MAX_NOTED)
            // A Malayalam recording's to-dos are English: the header's line of instructions says to
            // read the Malayalam, also where only some of the segments are in Malayalam script, and
            // to copy the words of each item's segment.
            val reading = TextLanguageNote.readingOrders(windowText, languageCode, resourceProvider).ifEmpty {
                TextLanguageNote.readingOrders(malayalamLines.joinToString(" ") { it.text }, languageCode, resourceProvider)
            }
            val said = resourceProvider.getTodoSaidInstruction(languageCode).takeIf { quoted }
            val instruction = TextLanguageNote.line(languageCode, reading + listOfNotNull(said), resourceProvider)
            val answer = ask(
                text = TodoPrompt.header(header, lines.first().spokenAt, noted, locale, instruction) +
                    TodoPrompt.render(window, locale),
                systemPrompt = systemPrompt,
                grammar = OutputGrammar.JsonSchema(TodoPrompt.schema(window, quotes = quoted)),
                maxTokens = if (quoted) MAX_TOKENS_QUOTED else MAX_TOKENS
            )
            if (answer != null) answerLog.todoAnswer(sessionId, part, answer) else answered = false

            val items = answer?.let(TodoAnswer::parse).orEmpty()
            repeat(items.size - TodoPrompt.MAX_ITEMS) { dropped.drop<Unit>(FoundTodosLog.Why.OVER_LIMIT) }
            val picks = items.take(TodoPrompt.MAX_ITEMS).map { Pick.of(it, window) }
            val modelled = picks.indices.mapNotNull { draftOf(it, picks, window, policy, dropped, heard) }
            val checks = AnswerChecks(listOfNotNull(instruction), rules, spoken = windowText)
            for (draft in combined(spotted, modelled)) {
                // Before the gate and the repeats, whose rule for a new time would keep a copy.
                if (!dropped.keeps(draft, window, spoken, checks, smallModel)) continue
                // Gated first: a todo the gate makes an idea repeats an idea, a suggestion a suggestion.
                val kept = if (draft.spotted) draft else gated(draft, dropped) ?: continue
                if (dropped.isNew(kept, known)) dropped.addUnlessOtherKind(found, kept.ungatedBy(window, asSaid, smallModel), ownKnown)
            }
        }
        dropped.log(sessionId)

        val left = overCap(found, suggestionsLeft)
        val createdAt = now()
        val keptDrafts = found.filter { draft -> left.none { it === draft } }
        logUngated(sessionId, keptDrafts, asSaid)
        val todos = keptDrafts
            .sortedWith(compareBy({ it.position }, { it.offset }))
            .map { it.toTodo(newId(), sessionId, createdAt) }
        return Found(todos, overCap = left.size, answered = answered)
    }

    /**
     * Tells [foundLog] how many of the [kept] items cite a line read into English ([asSaid]), and how
     * many of those the gate of Malayalam lines would have dropped ([Draft.ungated]); nothing for a
     * reading of no such line.
     */
    private fun logUngated(sessionId: String, kept: List<Draft>, asSaid: Map<String, String>) {
        if (asSaid.isEmpty()) return
        val ofRead = kept.filter { draft -> draft.sources.any { it.segment.id in asSaid } }
        if (ofRead.isNotEmpty()) foundLog.ungated(sessionId, kept = ofRead.size, wouldDrop = ofRead.count { it.ungated })
    }

    /**
     * This item, marked with whether the gate of Malayalam lines would drop it ([Draft.ungated]),
     * by the lines it was read from ([groundLinesOf]) as they were said ([asSaid]): the gate is off
     * for lines read into English (design D9), and V4 weighs what it would have dropped. As
     * [Dropped.keeps] holds a Malayalam line's items to it: a to-do must name an action unless it is
     * an appointment, and from the [smallModel] its Malayalam must ask for it, promise it or say
     * when ([MalayalamTodoCues.holds]); an idea from the [smallModel] must be put forward there
     * ([MalayalamTodoCues.holdsIdea]). Read from all of those lines, as an item of a reading copies
     * none of their words. An item of no read line is none.
     */
    private fun Draft.ungatedBy(window: TodoPrompt.Window, asSaid: Map<String, String>, smallModel: Boolean): Draft {
        val malayalam = groundLinesOf(this, window).mapNotNull { asSaid[it.segment.id] }
        if (malayalam.isEmpty()) return this
        val lines = malayalam.joinToString(" ")
        val drops = when {
            idea -> smallModel && !MalayalamTodoCues.holdsIdea(lines)
            !appointment && !TodoGate.namesAction(text) -> true
            else -> smallModel && !MalayalamTodoCues.holds(lines)
        }
        return copy(ungated = drops)
    }

    /**
     * A todo or idea only the model found, after [TodoGate]: null for a fragment, a filler, a
     * question or an uncued copy of its sentence, counted in [dropped] under its reason; a todo
     * with the capture of a strong cue said in its sentence; an idea when it puts one forward; a
     * suggestion unless it was said with a cue or is an appointment with a clock time. The
     * spotter's todos don't go through it: its rules are what the gate looks for. One written in
     * another language than its segments has its sentences found by the words copied for it
     * ([Draft.said]).
     */
    private fun gated(draft: Draft, dropped: Dropped): Draft? {
        val source = draft.sources.sortedBy { it.position }.joinToString(" ") { it.text }
        val appointment = draft.appointment && draft.due != null
        val about = draft.said ?: draft.text
        val timed = draft.due?.hasClock == true
        val verdict = TodoGate.verdict(draft.text, appointment, timed, source = source, idea = draft.idea, about = about)
        return when (verdict) {
            TodoGate.Verdict.DROP -> dropped.drop(
                when (TodoGate.dropReason(draft.text, appointment, timed, source = source, idea = draft.idea, about = about)) {
                    TodoGate.DropReason.QUESTION -> FoundTodosLog.Why.QUESTION
                    TodoGate.DropReason.TOO_SHORT -> FoundTodosLog.Why.TOO_SHORT
                    TodoGate.DropReason.CUT_OFF -> FoundTodosLog.Why.CUT_OFF
                    TodoGate.DropReason.UNCUED_COPY, null -> FoundTodosLog.Why.UNCUED_COPY
                }
            )
            TodoGate.Verdict.SUGGEST -> draft.asSuggestion()
            TodoGate.Verdict.IDEA -> draft.asIdea()
            TodoGate.Verdict.OPEN -> draft.asTodo(TodoGate.cueCapture(about, source))
        }
    }

    /**
     * The found items one reading drops before anything is kept, by why, counted for [foundLog]:
     * those written in another script than the Latin one, those that repeat their prompt
     * ([com.satori.domain.usecase.llm.PromptEcho], [TodoGate.namesField]) or, since 0.7.3, give
     * back its rules ([PromptRules]) or describe the text ([TodoGate.describesText]), those whose
     * words aren't in what they cite ([TodoGrounding]), the headings and greetings
     * ([TodoGate.isHeadingOrGreeting]), the to-dos of a Malayalam line that name no action
     * ([TodoGate.namesAction]) or, from the small model, say nothing asked for or promised in
     * their Malayalam ([MalayalamTodoCues.holds]), the small model's ideas of a Malayalam line that
     * nothing there puts forward ([MalayalamTodoCues.holdsIdea]), and those with the text of an
     * item of the other kind. Since 0.7.2 every other drop too ([drop]): the items past the
     * answer's limit, those that cite no segment, have no text or are only a time
     * ([FoundTodosLog.Why.ONLY_A_TIME]), the spotter's appointments without a usable time, the
     * gate's drops by their rule ([TodoGate.dropReason]) and the repeats of what is known.
     */
    private inner class Dropped {
        private val counts = mutableMapOf<FoundTodosLog.Why, Int>()

        /** Counts one item dropped for [why]; null, for the caller to return in place of the item. */
        fun <T> drop(why: FoundTodosLog.Why): T? {
            counts[why] = (counts[why] ?: 0) + 1
            return null
        }

        /** Whether [draft] repeats none of [known], counted as [FoundTodosLog.Why.ALREADY_NOTED] when it does. */
        fun isNew(draft: Draft, known: List<Known>): Boolean = known.none { draft.repeats(it) } || refuse(FoundTodosLog.Why.ALREADY_NOTED)

        /** Counts one item dropped for [why], and says it is not kept ([keeps]). */
        private fun refuse(why: FoundTodosLog.Why): Boolean {
            drop<Unit>(why)
            return false
        }

        /**
         * Whether [draft], read from [window], a part in [spoken] when that isn't the to-dos'
         * language, is kept. Its text is not mostly in another script than the Latin one
         * ([com.satori.domain.usecase.llm.WrongScript]), which the app never writes: a Malayalam
         * line copied whole would pass the check of its words, its words being the segment's, and
         * since 0.7.3 no other script passes either. It doesn't repeat the instruction lines of
         * [checks] (the line of instructions of a part in another language) nor hold a key of the
         * answer, nor since 0.7.3 give back the rules of the system prompt ([PromptRules]; an item
         * of words said in the part is the speaker's), and it doesn't say what the text is about
         * ("The text describes ..."): the 1B stored both as open to-dos on 2026-10-16, the words
         * copied for them being the segment's. Its words are in its segments and the ones
         * their sentences run on into or come from ([evidenceOf]). And it is no greeting, nor a
         * heading. A Malayalam part's item that copied nothing (an answer without the grammar) is
         * so dropped: no English word is in Malayalam. What the spotter found follows a cue ("Note
         * to self: second dentist appointment ..."), and an appointment with its time is one
         * ("Finally, the dentist on Friday at 3"): neither is a heading, whatever ordinal it
         * starts with.
         *
         * A to-do of a Malayalam line, in which no cue of English or German can be read
         * ([TodoGate]), must also name an action or a commitment in its own words
         * ([TodoGate.namesAction]), unless it is an appointment, which may name only what is on;
         * and from the [smallModel] (the live model, not Gemma 4 E2B), the clause of the words it
         * quotes from the line ([MalayalamTodoCues.quotedClause]) must ask for the thing, promise
         * it, or say when it is, not told in the past ([MalayalamTodoCues.holds]): an appointment's
         * day is its cue. An idea names a change, and nobody has said they will make it, so it is
         * held to neither; from the [smallModel] its clause must put it forward instead
         * ([MalayalamTodoCues.holdsIdea]).
         */
        fun keeps(draft: Draft, window: TodoPrompt.Window, spoken: String?, checks: AnswerChecks, smallModel: Boolean): Boolean {
            val drop = checks.dropOf(draft.text)
            if (drop == AnswerDrop.OTHER_SCRIPT) return refuse(FoundTodosLog.Why.WRITTEN_IN_ANOTHER_SCRIPT)
            if (TodoGate.namesField(draft.text)) return refuse(FoundTodosLog.Why.ECHOES_PROMPT)
            when (drop) {
                AnswerDrop.PROMPT -> return refuse(FoundTodosLog.Why.ECHOES_PROMPT)
                AnswerDrop.RULES -> return refuse(FoundTodosLog.Why.ECHOES_RULES)
                AnswerDrop.OTHER_SCRIPT, null -> Unit
            }
            if (TodoGate.describesText(draft.text)) return refuse(FoundTodosLog.Why.DESCRIBE_THE_TEXT)
            // Before the check of its words: a time's words are in its segment, but they are no thing to do.
            if (!draft.spotted && isOnlyATime(draft)) return refuse(FoundTodosLog.Why.ONLY_A_TIME)
            val grounds = groundsOf(draft, window)
            if (!TodoGrounding.isGrounded(evidenceOf(draft, grounds, spoken), grounds)) {
                return refuse(FoundTodosLog.Why.NOT_IN_SEGMENT)
            }
            val mayHead = !draft.spotted && !(draft.appointment && draft.due != null)
            if (TodoGate.isGreeting(draft.text) || (mayHead && TodoGate.isHeading(draft.text))) {
                return refuse(FoundTodosLog.Why.HEADING_OR_GREETING)
            }
            if (LanguageDetector.isMalayalam(grounds)) {
                val clause = MalayalamTodoCues.quotedClause(draft.said, grounds)
                if (draft.idea) {
                    if (smallModel && !MalayalamTodoCues.holdsIdea(clause)) return refuse(FoundTodosLog.Why.NO_MALAYALAM_IDEA)
                    return true
                }
                // An appointment names what is on, often without a verb ("Doctor's appointment"); its day is its cue.
                if (!draft.appointment && !TodoGate.namesAction(draft.text)) return refuse(FoundTodosLog.Why.NO_ACTION)
                if (smallModel && !MalayalamTodoCues.holds(clause)) return refuse(FoundTodosLog.Why.NO_MALAYALAM_CUE)
            }
            return true
        }

        /**
         * Whether nothing of the model's [draft] is left once the time phrases of the segments it
         * was read from are taken out: "Tomorrow at 10", "The end of the month". An item whose text
         * was only the time it picked has none left already ([draftOf]); this finds one that
         * picked no time.
         */
        private fun isOnlyATime(draft: Draft): Boolean {
            val phrases = draft.sources.flatMap { line -> line.candidates.map { it.phrase } }.distinct()
            return phrases.isNotEmpty() && TodoTexts.clean(draft.text, *phrases.toTypedArray()) == null
        }

        /**
         * Adds [draft] to [found] ([add]) unless an item of the other kind, a to-do for an idea or
         * an idea for a to-do, says the same ([TodoTexts.saysSame]: also with a word's English
         * ending off, "Paying the bill" for "Pay the bill"): the same text is never both a to-do and
         * an idea, and the 1B gave one sentence as both on 2026-10-16. Against one of
         * [ownKnown], the recording's own, [draft] is dropped. Against one found before it in this
         * reading the stronger of the two stays, whichever came first: an open to-do (noted,
         * asked to be reminded of, or promised) over an idea, an idea over a suggestion.
         */
        fun addUnlessOtherKind(found: MutableList<Draft>, draft: Draft, ownKnown: List<Known>) {
            if (ownKnown.any { (it.todo.status == TodoStatus.IDEA) != draft.idea && TodoTexts.saysSame(it.key, draft.key) }) {
                drop<Unit>(FoundTodosLog.Why.SAME_AS_ANOTHER)
                return
            }
            val other = found.indexOfFirst { it.idea != draft.idea && TodoTexts.saysSame(it.key, draft.key) }
            if (other < 0) return add(found, draft)
            drop<Unit>(FoundTodosLog.Why.SAME_AS_ANOTHER)
            if (strength(draft) > strength(found[other])) {
                found.removeAt(other)
                add(found, draft)
            }
        }

        /** How much is said of a find: an open to-do most, then an idea, then a suggestion. */
        private fun strength(draft: Draft): Int = when {
            draft.idea -> 1
            draft.open -> 2
            else -> 0
        }

        /** Tells [foundLog] of each reason's count, in the order the reasons are checked; nothing for none. */
        fun log(sessionId: String) {
            for (why in LOG_ORDER) counts[why]?.let { foundLog.dropped(sessionId, it, why) }
        }
    }

    /**
     * The words of [draft] that must be found in [grounds], what it cites: the words the model
     * copied for it from them ([Draft.said]) where its own text can't be compared with them, as
     * they are in Malayalam script or the part is in another language than the to-dos ([spoken]: a
     * Translate recording's, a German part of English to-dos); otherwise its own text, also for an
     * item of a line in Latin letters among Malayalam ones. Never stored: the to-do keeps its text.
     */
    private fun evidenceOf(draft: Draft, grounds: String, spoken: String?): String {
        val said = draft.said ?: return draft.text
        val otherLanguage = spoken != null && spoken != LanguageDetector.MALAYALAM
        return if (LanguageDetector.isMalayalam(grounds) || otherLanguage) said else draft.text
    }

    /**
     * What [draft] was read from, as one text: its sources, and next to each the segment its
     * sentence runs on into ([TodoPrompt.Line.continued]) or comes from, as voice detection cuts
     * a sentence at a pause and the model cites one half of it. In spoken order.
     */
    private fun groundsOf(draft: Draft, window: TodoPrompt.Window): String =
        groundLinesOf(draft, window).joinToString(" ") { it.text }

    /** The lines [groundsOf] joins: [draft]'s sources and the ones their sentences run on into or come from, in spoken order. */
    private fun groundLinesOf(draft: Draft, window: TodoPrompt.Window): List<TodoPrompt.Line> {
        val lines = window.lines
        val cited = draft.sources.mapNotNull { source -> lines.indexOfFirst { it === source }.takeIf { it >= 0 } }
        val around = cited.flatMap { i ->
            listOfNotNull(
                i,
                (i - 1).takeIf { lines[i].continued && it >= 0 },
                (i + 1).takeIf { it < lines.size && lines[it].continued }
            )
        }
        val read = around.distinct().sorted().map { lines[it] }
        // A source outside the window (none is) still counts as what it says.
        val outside = draft.sources.filter { source -> lines.none { it === source } }
        return read + outside
    }

    /**
     * The model's suggestions [found] holds over the [places] the recording has left for them,
     * ranked across the whole reading: one with a due time first, then what someone asked for, then
     * what the model inferred, each in spoken order. Todos said outright, open todos and ideas are
     * none.
     */
    private fun overCap(found: List<Draft>, places: Int): List<Draft> =
        found.filter { !it.spotted && !it.open && !it.idea }
            .sortedWith(
                compareBy({ it.due == null }, { it.capture != TodoCapture.ASKED_BY_OTHER }, { it.position }, { it.offset })
            )
            .drop(places.coerceAtLeast(0))

    /** Adds [draft] to [found], or joins it to the mention of the same todo found before ([Draft.isSameTodo]). */
    private fun add(found: MutableList<Draft>, draft: Draft) {
        val twin = found.indexOfFirst { it.isSameTodo(draft) }
        if (twin < 0) found += draft else found[twin] = found[twin].mergedWith(draft)
    }

    /**
     * The spotter's todos of one part with the model's: a model todo that repeats one the spotter
     * read from the same segment joins it ([Draft.withModel]); from the same words, "Dentist" and
     * "Dentist appointment" are one ([TodoTexts.isWithin]). So does one that says it in its own
     * words ([Draft.isRewordedBy]: "Phone the bank regarding the loan" for "Call the bank about the
     * loan"), keeping the spotter's words, which are the speaker's. Joined, it is what the spotter
     * read, a todo or an idea, whatever the model called it. The others stay as they are.
     */
    private fun combined(spotted: List<Draft>, modelled: List<Draft>): List<Draft> {
        val result = spotted.toMutableList()
        for (draft in modelled) {
            val twin = result.indexOfFirst { it.spotted && it.sharesSegment(draft) && TodoTexts.isWithin(it.key, draft.key) }
            if (twin >= 0) {
                result[twin] = result[twin].withModel(draft)
                continue
            }
            val reworded = result.indexOfFirst { it.spotted && it.sharesSegment(draft) && it.isRewordedBy(draft) }
            if (reworded < 0) result += draft else result[reworded] = result[reworded].withModel(draft, ownWords = true)
        }
        return result
    }

    /**
     * The recording's complete, non-blank segments in spoken order, with their times. A segment
     * that goes on with a sentence the one before left open is parsed with that sentence.
     */
    private fun linesOf(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        zone: ZoneId,
        policy: TimePolicy
    ): List<TodoPrompt.Line> {
        val spoken = segments
            .filter { it.isComplete && it.sessionId == sessionId }
            .map { it to TodoPrompt.text(it.text) }
            .filter { (_, text) -> text.isNotEmpty() }
            .sortedBy { (segment, _) -> segment.timestamp }
        var open = ""
        var openAt = 0L
        return spoken.mapIndexed { position, (segment, text) ->
            val before = if (open.isNotEmpty() && TodoPrompt.goesOn(text, segment.timestamp - openAt)) open else ""
            val spokenAt = Instant.ofEpochMilli(segment.timestamp).atZone(zone)
            val candidates = timesIn(text, before, spokenAt, policy)
            open = TodoPrompt.openSentence(before, text, candidates)
            openAt = segment.timestamp
            TodoPrompt.Line(position, segment, text, spokenAt, candidates, continued = before.isNotEmpty())
        }
    }

    /**
     * The times in [text], read from the moment it was said, with [before] (the sentence the
     * previous segment left open) as the context the parser reads them in; only those in [text]
     * itself are kept, and not one cut down to a unit word ("Uhr" of "um 9" / "Uhr am Bahnhof").
     * None if the parser fails.
     */
    private fun timesIn(text: String, before: String, spokenAt: ZonedDateTime, policy: TimePolicy): List<TimeCandidate> {
        val offset = if (before.isEmpty()) 0 else before.length + 1
        val candidates = try {
            timeParser.find(if (before.isEmpty()) text else "$before $text", spokenAt, policy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            return emptyList()
        }
        return candidates
            .filter { it.end > offset }
            .mapNotNull { candidate ->
                if (offset == 0) return@mapNotNull candidate
                // One that starts in the open sentence ("tomorrow" + "at 3") keeps its part in [text].
                val start = (candidate.start - offset).coerceIn(0, text.length)
                val end = (candidate.end - offset).coerceIn(start, text.length)
                val phrase = text.substring(start, end)
                if (candidate.start < offset && TodoPrompt.isUnitWord(phrase)) return@mapNotNull null
                candidate.copy(phrase = phrase, start = start, end = end)
            }
            .filter { it.phrase.isNotBlank() }
            .distinctBy { it.phrase.lowercase(Locale.ROOT) to it.at }
            .sortedBy { it.start }
    }

    private suspend fun modelLoaded(): Boolean =
        try {
            llmRepository.reloadModel().isSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    /** The model's whole answer, or null when it fails. */
    private suspend fun ask(text: String, systemPrompt: String, grammar: OutputGrammar, maxTokens: Int): String? =
        try {
            val answer = StringBuilder()
            llmRepository.generateInsight(text, systemPrompt, maxTokens, grammar).collect { answer.append(it) }
            answer.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * The checked todo or idea for the answer's item [index], or null, counted in [dropped], when
     * it cites no segment of [window], has no text, or none is left once the time it picked went
     * ([FoundTodosLog.Why.ONLY_A_TIME]: on 2026-10-16 the 1B gave four items whose text was "tomorrow
     * at 10" and the like). The other [picks] of the answer decide which of them a clock time said
     * apart from a day belongs to. A time [heard] doesn't keep (D9: read in a line whose Malayalam
     * says no time) is none it may take, and its words go from the item's text as a time's do.
     */
    private fun draftOf(
        index: Int,
        picks: List<Pick>,
        window: TodoPrompt.Window,
        policy: TimePolicy,
        dropped: Dropped,
        heard: (TodoPrompt.Time) -> Boolean
    ): Draft? {
        val item = picks[index].item
        val cited = item.segments.mapNotNull(window::line).distinct()
        val first = cited.firstOrNull() ?: return dropped.drop(FoundTodosLog.Why.NO_SEGMENT)
        val picked = picks[index].time
        val chosen = picked?.takeIf { time ->
            cited.any { abs(it.position - time.line.position) <= MAX_TIME_DISTANCE } && !isBeforeItWasSaid(time) && heard(time)
        }
        val times = chosen?.let { timesFor(index, it, window, picks, heard) }.orEmpty()
        // D9: a time its line as it was said doesn't say is no time of the item's words either.
        val unheard = picked?.takeIf { !heard(it) }?.candidate?.phrase
        val phrases = (times.map { it.candidate.phrase } + listOfNotNull(unheard)).toTypedArray()
        val text = item.text ?: return dropped.drop(FoundTodosLog.Why.NO_TEXT)
        val answer = TodoTexts.cleanAnswer(text, *phrases)
            ?: return dropped.drop(if (phrases.isEmpty() || TodoTexts.cleanAnswer(text) == null) FoundTodosLog.Why.NO_TEXT else FoundTodosLog.Why.ONLY_A_TIME)
        val key = TodoTexts.key(answer.text)
        val sources = (cited + times.map { it.line }).distinct()
        return Draft(
            position = first.position,
            offset = TodoTexts.firstMention(first.text, key) ?: Int.MAX_VALUE,
            text = answer.text,
            key = key,
            appointment = picks[index].appointment,
            capture = captureOf(item.capture),
            due = dueOf(times, times.isNotEmpty() && isHedged(answer.text, sources, times), policy),
            sources = sources,
            heldBreak = answer.heldSentenceBreak,
            idea = item.idea,
            said = item.said?.let(TodoPrompt::text)?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * True when the todo [text], read from [sources] with its [times], was said with a hedge
     * ([TodoTexts.HEDGES]: "maybe", "vielleicht"): in a sentence of its sources about it, or in the
     * sentence a time of it was said in ("I'll send the report, maybe on Friday").
     */
    private fun isHedged(text: String, sources: List<TodoPrompt.Line>, times: List<TodoPrompt.Time>): Boolean {
        val source = sources.sortedBy { it.position }.joinToString(" ") { it.text }
        return TodoTexts.sentencesAbout(text, source).any(TodoTexts::isHedged) ||
            times.any { TodoTexts.isHedged(TodoPrompt.sentenceOf(it)) }
    }

    /**
     * The checked todo or idea for one the spotter found, or null for an appointment whose time
     * is unusable. Its time is checked and read as a time the model picked: not over when it was
     * said, else the todo keeps no time; a clock time at its moment, a part of the day at its
     * usual hour, a day or a run of days at the policy's hour. An idea has none. A time [heard]
     * doesn't keep (D9) counts as none said.
     */
    private fun draftOf(spotted: ExplicitTodoSpotter.Spotted, policy: TimePolicy, heard: (TodoPrompt.Time) -> Boolean): Draft? {
        val usable = spotted.times.filter(heard)
        val main = usable.firstOrNull { it.candidate.hasClock } ?: usable.firstOrNull()
        val times = if (main == null || isBeforeItWasSaid(main)) emptyList() else usable
        // An appointment is one by its time; without it there is nothing to go by.
        if (spotted.appointment && times.isEmpty()) return null
        return Draft(
            position = spotted.lines.first().position,
            offset = spotted.offset,
            text = spotted.text,
            key = TodoTexts.key(spotted.text),
            appointment = spotted.appointment,
            capture = spotted.capture,
            due = dueOf(times, spotted.tentative, policy),
            sources = (spotted.lines + times.map { it.line }).distinct(),
            spotted = true,
            weakTime = spotted.weakTime,
            idea = spotted.idea
        )
    }

    /**
     * [chosen] with the other half of a day and a clock time said apart in one sentence, in
     * spoken order: "Morgen" and "um 9" in "Morgen muss ich um 9 beim Arzt sein". A picked clock
     * time takes the single day of its sentence on its date for the words and the source; it
     * carries that day already. A picked day takes a clock time only as [clockFor] decides. Only a
     * time [heard] keeps (D9) is a half.
     */
    private fun timesFor(
        index: Int,
        chosen: TodoPrompt.Time,
        window: TodoPrompt.Window,
        picks: List<Pick>,
        heard: (TodoPrompt.Time) -> Boolean
    ): List<TodoPrompt.Time> {
        val half = if (chosen.candidate.hasClock) {
            window.times.singleOrNull { other ->
                !other.candidate.hasClock && onSameDay(chosen, other) && TodoPrompt.sameSentence(chosen, other) && heard(other)
            }
        } else {
            clockFor(index, chosen, window, picks, heard)
        }
        return listOfNotNull(chosen, half).sortedWith(compareBy({ it.line.position }, { it.candidate.start }))
    }

    /**
     * The clock time that todo [index], which picked the day [chosen], gets from the day's
     * sentence, or null. Only clock times on that date that were not past and that no other todo
     * picked count. Each goes to the one todo of the answer whose own text names it ("Dentist at
     * 3" names "at 3"), if that one picked this day; a single clock time that no todo names goes
     * to the single appointment among those that picked it. Otherwise it belongs to no todo:
     * "Tomorrow I need to call the bank, and at 3 I watch the football" leaves the bank
     * date-only, and "Tomorrow I have the dentist, and at 3 I call Bob" with a todo "Call Bob at
     * 3" the dentist. A clock time [heard] doesn't keep (D9) goes to none.
     */
    private fun clockFor(
        index: Int,
        chosen: TodoPrompt.Time,
        window: TodoPrompt.Window,
        picks: List<Pick>,
        heard: (TodoPrompt.Time) -> Boolean
    ): TodoPrompt.Time? {
        val clocks = window.times.filter { other ->
            other.candidate.hasClock && onSameDay(chosen, other) && TodoPrompt.sameSentence(chosen, other) &&
                !isBeforeItWasSaid(other) && heard(other) && picks.indices.none { it != index && picks[it].time?.id == other.id }
        }
        if (clocks.isEmpty()) return null
        val sharing = picks.indices.filter { picks[it].time?.id == chosen.id }
        val owner = clocks.associateWith { clock ->
            val naming = picks.indices.filter { TodoTexts.names(picks[it].item.text.orEmpty(), clock.candidate.phrase) }
            when {
                naming.isNotEmpty() -> naming.singleOrNull()
                clocks.size == 1 -> sharing.singleOrNull { picks[it].appointment }
                else -> null
            }
        }
        return owner.entries.singleOrNull { it.value == index }?.key
    }

    private fun onSameDay(a: TodoPrompt.Time, b: TodoPrompt.Time): Boolean =
        a.candidate.at.toLocalDate() == b.candidate.at.toLocalDate()

    /**
     * True for "yesterday", "last Friday" and "at 8 this morning" said at nine: a time over before
     * the moment it was said. A range counts until its end, so one still running when it was
     * said keeps its time ("Der Handwerker kommt heute zwischen 8 und 12 Uhr" said at nine), and
     * a run of days until its last day.
     */
    private fun isBeforeItWasSaid(time: TodoPrompt.Time): Boolean {
        val said = time.line.spokenAt
        val last = time.candidate.endAt ?: time.candidate.at
        return if (time.candidate.hasClock) last.isBefore(said.minusMinutes(SAID_TOLERANCE_MINUTES))
        else last.toLocalDate().isBefore(said.toLocalDate())
    }

    /**
     * The due time read from [times] (one time, or a day and its clock time), or null for none;
     * tentative when it was said so ([tentative]) or the parser read it ambiguously.
     */
    private fun dueOf(times: List<TodoPrompt.Time>, tentative: Boolean, policy: TimePolicy): Due? {
        val main = times.firstOrNull { it.candidate.hasClock } ?: times.firstOrNull() ?: return null
        return Due(
            at = momentOf(main.candidate, policy),
            precision = main.candidate.precision,
            phrase = times.joinToString(" ") { it.candidate.phrase },
            tentative = tentative || times.any { it.candidate.confidence < LOW_CONFIDENCE },
            said = main.line.spokenAt
        )
    }

    /**
     * The due moment of a parser reading: the time said (for a range its start), a part of the
     * day at the part's usual hour, or the day said (for a run of days its likely day) at the
     * policy's hour. The one place the moment is read from a [TimeCandidate].
     */
    private fun momentOf(candidate: TimeCandidate, policy: TimePolicy): ZonedDateTime {
        val at = candidate.at
        if (candidate.hasClock || candidate.precision == TimePrecision.PART_OF_DAY) return at
        return at.with(LocalTime.of(policy.dateOnlyHour.coerceIn(0, 23), 0))
    }

    private fun captureOf(value: String?): TodoCapture = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "explicit" -> TodoCapture.EXPLICIT
        "commitment" -> TodoCapture.COMMITMENT
        "asked", "asked_by_other" -> TodoCapture.ASKED_BY_OTHER
        else -> TodoCapture.INFERRED
    }

    /**
     * One todo or idea of the model's answer and the listed time it named, before any check.
     *
     * @property appointment The model called it an appointment
     */
    private class Pick(val item: TodoAnswer.Item, val time: TodoPrompt.Time?) {
        val appointment: Boolean = item.kind.equals("appointment", ignoreCase = true)

        companion object {
            /** [item] with the time of [window] it names; an idea names none. */
            fun of(item: TodoAnswer.Item, window: TodoPrompt.Window) =
                Pick(item, if (item.idea) null else item.time?.let(window::time))
        }
    }

    /** A known todo with the words it is compared by. */
    private class Known(val todo: Todo, val key: TodoTexts.Key)

    /**
     * A due time as said.
     *
     * @property at The due moment: a part of the day at its usual hour, a date-only one at the
     *   policy's hour
     * @property precision How exactly it was said: a clock time (EXACT), a part of the day, a
     *   day or a run of days
     * @property phrase The words it came from, as transcribed ("Morgen um 9")
     * @property tentative Said tentatively, or read ambiguously by the parser
     * @property said When its words were said
     */
    private class Due(
        val at: ZonedDateTime,
        val precision: TimePrecision,
        val phrase: String,
        val tentative: Boolean,
        val said: ZonedDateTime
    ) {
        val hasClock: Boolean get() = precision == TimePrecision.EXACT

        /** How much it says: a clock time most, then a part of the day, a day, a run of days. */
        val rank: Int
            get() = when (precision) {
                TimePrecision.EXACT -> 3
                TimePrecision.PART_OF_DAY -> 2
                TimePrecision.DAY -> 1
                TimePrecision.DAYS_RANGE -> 0
            }

        /** Same day, and the same clock time unless one of them names only the day. */
        fun fits(other: Due): Boolean =
            at.toLocalDate() == other.at.toLocalDate() && (!hasClock || !other.hasClock || at.isEqual(other.at))

        /**
         * True when this says no more than [dueAt] and [dueHasTime] of a known todo: the same
         * day, and no clock time or part of the day the known one lacks.
         */
        fun addsNothingTo(dueAt: Long?, dueHasTime: Boolean): Boolean {
            val known = dueAt?.let { Instant.ofEpochMilli(it).atZone(at.zone) } ?: return false
            if (known.toLocalDate() != at.toLocalDate()) return false
            return when (precision) {
                TimePrecision.EXACT -> dueHasTime && known.isEqual(at)
                TimePrecision.PART_OF_DAY -> dueHasTime || known.isEqual(at)
                TimePrecision.DAY, TimePrecision.DAYS_RANGE -> true
            }
        }

        /**
         * When to remind: an hour before an appointment with a clock time or a part of the day,
         * at the due time for other todos (a date-only one at the policy's hour). When that
         * moment had passed as the words were said, a clock time reminds at the due time if
         * that had not; otherwise there is no reminder.
         */
        fun remindAt(appointment: Boolean): ZonedDateTime? {
            val timed = hasClock || precision == TimePrecision.PART_OF_DAY
            val lead = if (appointment && timed) at.minusHours(APPOINTMENT_LEAD_HOURS) else at
            val choices = if (hasClock) listOf(lead, at) else listOf(lead)
            return choices.firstOrNull { !it.isBefore(said) }
        }

        fun confirmedBy(other: Due) = Due(at, precision, phrase, tentative && other.tentative, said)

        /** This time, tentative too when [other] reads the same moment and was said tentatively. */
        fun hedgedBy(other: Due) = Due(at, precision, phrase, tentative || (other.tentative && other.at.isEqual(at)), said)
    }

    /**
     * A checked todo or idea before it gets an id.
     *
     * @property offset Where in its first segment it was said: the spotter's clause, or the first
     *   of the model's words found there; it orders the todos of one segment
     * @property spotted The spotter found it (alone or with the model)
     * @property weakTime The spotter's clause named more than one clock time, so its time is only the day
     * @property heldBreak The model's text held more than one sentence
     * @property idea It is an idea, no todo: it has no due time and is no appointment
     * @property said The words of its segment the model copied for it, for a text in another
     *   language than the segment ([TodoAnswer.Item.said]); null otherwise
     * @property ungated Read from lines read into English, it is one the gate of Malayalam lines,
     *   off for them (D9), would have dropped ([ungatedBy]); counted for the field log only
     */
    private data class Draft(
        val position: Int,
        val offset: Int,
        val text: String,
        val key: TodoTexts.Key,
        /** The model or the spotter called it an appointment; it is one only with a due time. */
        val appointment: Boolean,
        val capture: TodoCapture,
        val due: Due?,
        val sources: List<TodoPrompt.Line>,
        val spotted: Boolean = false,
        val weakTime: Boolean = false,
        val heldBreak: Boolean = false,
        val idea: Boolean = false,
        val said: String? = null,
        val ungated: Boolean = false
    ) {
        val open: Boolean get() = capture == TodoCapture.EXPLICIT || capture == TodoCapture.COMMITMENT

        /** True when both were read from a segment in common: the same words. */
        fun sharesSegment(other: Draft): Boolean = sources.any { line -> other.sources.any { it === line } }

        /**
         * True when [model], a model todo read from a segment this spotter's todo was read from,
         * says this todo in other words: it is due at the same moment and names a thing in common,
         * or one inside the other ("Arztpraxis anrufen", "Beim Arzt anrufen"). Verbs don't count:
         * "Call Anna" is not "Call the bank".
         */
        fun isRewordedBy(model: Draft): Boolean {
            val mine = due ?: return false
            val theirs = model.due ?: return false
            if (!mine.at.isEqual(theirs.at)) return false
            val things = thingsOf(key)
            return thingsOf(model.key).any { word -> things.any { it == word || isInside(it, word) } }
        }

        /**
         * This spotter's todo and [model], the model's todo of the same words, as one: the
         * spotter's text when the model's is longer or held more than one sentence, or with
         * [ownWords] when the model said it in its own words ([isRewordedBy]), else the model's; the
         * time of whichever has one, the spotter's when both do, except that the model's clock time
         * on the spotter's day counts when the spotter's words named more than one ([weakTime]); an
         * appointment when either says so; the commitment and both sources. A spotter's idea stays
         * an idea, with no time.
         */
        fun withModel(model: Draft, ownWords: Boolean = false): Draft {
            val words = if (ownWords || model.heldBreak || model.wordCount > wordCount) this else model
            val mine = due
            val theirs = model.due
            val kept = when {
                mine == null -> theirs
                theirs == null -> mine
                weakTime && theirs.fits(mine) && theirs.rank > mine.rank -> theirs
                else -> mine.hedgedBy(theirs)
            }
            val earlier = if (model.position < position) model else this
            return Draft(
                position = earlier.position,
                offset = earlier.offset,
                text = words.text,
                key = words.key,
                appointment = !idea && (appointment || model.appointment),
                capture = if (open || !model.open) capture else model.capture,
                due = if (idea) null else kept,
                sources = (sources + model.sources).distinct(),
                spotted = true,
                weakTime = weakTime && kept === mine,
                idea = idea
            )
        }

        private val wordCount: Int get() = text.split(' ').size

        /** This todo as a suggestion to keep or dismiss: what the model read into what was said ([TodoGate]). */
        fun asSuggestion(): Draft = if (!open) this else copy(capture = TodoCapture.INFERRED)

        /** This as an idea ([TodoGate]): no time, no appointment, and what the model read into what was said. */
        fun asIdea(): Draft = copy(idea = true, due = null, appointment = false, capture = TodoCapture.INFERRED)

        /**
         * This as a todo [TodoGate] keeps: with the capture its strong [cue] says, unless the
         * model said explicit already, else with the model's. A model idea it makes a todo has no
         * time.
         */
        fun asTodo(cue: TodoCapture?): Draft =
            copy(capture = if (cue == null || capture == TodoCapture.EXPLICIT) capture else cue, idea = false)

        /** True when this adds nothing to [known], by the rules for repeats in the use case's description. */
        fun repeats(known: Known): Boolean {
            val todo = known.todo
            if (sources.any { it.segment.id in todo.sourceSegmentIds }) {
                // The spotter reads the same words the same way each time, but what was kept may be
                // the model's shorter text: "Dentist" for "Dentist appointment".
                return if (spotted) TodoTexts.isWithin(known.key, key) else TodoTexts.isRepeat(known.key, key)
            }
            if (idea) return todo.status == TodoStatus.IDEA && TodoTexts.isDuplicate(known.key, key)
            if (!todo.isPending() || !TodoTexts.isDuplicate(known.key, key)) return false
            if (open && todo.status == TodoStatus.SUGGESTED) return false
            return due == null || due.addsNothingTo(todo.dueAt, todo.dueHasTime)
        }

        /**
         * Two mentions of one todo or of one idea: the same said again, and due times that don't
         * tell them apart. An idea is never a todo said again.
         */
        fun isSameTodo(other: Draft): Boolean =
            idea == other.idea && TodoTexts.isRepeat(key, other.key) &&
                (due == null || other.due == null || due.fits(other.due))

        /**
         * One todo from two mentions of it, keeping the fuller text, the more precise due time,
         * the commitment and both sources. The gate of Malayalam lines would drop it only when it
         * would drop both ([ungated]).
         */
        fun mergedWith(other: Draft): Draft {
            val mine = due
            val theirs = other.due
            val merged = when {
                mine == null -> theirs
                theirs == null -> mine
                theirs.rank > mine.rank -> theirs
                mine.rank > theirs.rank -> mine
                else -> mine.confirmedBy(theirs)
            }
            val fuller = if (other.key.words.size > key.words.size) other else this
            val earlier = if (other.position < position || (other.position == position && other.offset < offset)) other else this
            return Draft(
                position = earlier.position,
                offset = earlier.offset,
                text = fuller.text,
                key = fuller.key,
                appointment = appointment || other.appointment,
                capture = if (other.open && !open) other.capture else capture,
                due = merged,
                sources = (sources + other.sources).distinct(),
                spotted = spotted || other.spotted,
                weakTime = weakTime && other.weakTime,
                heldBreak = fuller.heldBreak,
                idea = idea,
                ungated = ungated && other.ungated
            )
        }

        fun toTodo(id: String, sessionId: String, at: Long): Todo {
            val kind = if (appointment && due != null) TodoKind.APPOINTMENT else TodoKind.TASK
            return Todo(
                id = id,
                sessionId = sessionId,
                text = text,
                kind = kind,
                status = when {
                    idea -> TodoStatus.IDEA
                    open -> TodoStatus.OPEN
                    else -> TodoStatus.SUGGESTED
                },
                certainty = if (due?.tentative == true) TodoCertainty.TENTATIVE else TodoCertainty.CONFIRMED,
                capture = capture,
                dueAt = due?.at?.toInstant()?.toEpochMilli(),
                dueHasTime = due?.hasClock ?: false,
                timePhrase = due?.phrase,
                remindAt = due?.remindAt(kind == TodoKind.APPOINTMENT)?.toInstant()?.toEpochMilli(),
                sourceSegmentIds = sources.sortedBy { it.position }.map { it.segment.id },
                createdAt = at,
                updatedAt = at
            )
        }
    }
}

/** Still to be done: shown to the model as noted, and matched against new todos by their words alone. */
private fun Todo.isPending(): Boolean = status == TodoStatus.OPEN || status == TodoStatus.SUGGESTED

/**
 * The words of [key] that name a thing, not what is done to it: without an English verb first
 * ("call" of "Call the bank"), a German infinitive last ("anrufen" of "Beim Arzt anrufen"), the
 * particles of a verb ("up") and words of one or two letters.
 */
private fun thingsOf(key: TodoTexts.Key): Set<String> {
    val said = key.allWords.split(' ').filter { it.isNotEmpty() }
    val english = said.firstOrNull()?.let(ExplicitTodoSpotter::isTodoVerb) == true
    val verb = if (english) said.first() else said.lastOrNull()?.takeIf { it.length > 3 && GERMAN_INFINITIVE.matches(it) }
    return key.words.filterTo(mutableSetOf()) { it != verb && it.length > 2 && it !in VERB_PARTICLES }
}

/** One word inside the other, as German makes "Arztpraxis" of "Arzt"; both of four letters or more. */
private fun isInside(a: String, b: String): Boolean =
    minOf(a.length, b.length) >= 4 && (a.contains(b) || b.contains(a))

private val GERMAN_INFINITIVE = Regex("""\p{L}+(?:en|ern|eln)""")
private val VERB_PARTICLES = setOf("up", "down", "off", "out", "back", "over", "away", "around", "through")
