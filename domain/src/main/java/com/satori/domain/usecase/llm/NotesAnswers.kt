package com.satori.domain.usecase.llm

import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.usecase.todo.AnswerLog

/**
 * The answers of one run of a recording's notes ([GenerateFinalInsightUseCase], or a whole pass of
 * [GenerateBatchInsightUseCase]): each call's answer read into a note, a call whose answer leaves
 * no point asked once more, and what the answers lost counted for the field log once, at the end
 * of the run ([logDrops]).
 *
 * "No point" is a note with nothing to read but perhaps its title
 * ([InsightOutputParser.ParsedInsight.hasPoint]): a blank answer, one cut off before its first point
 * ended, or one whose points all repeated the prompt, gave back the rules of its system prompt
 * ([PromptRules], since 0.7.3), were in another script ([WrongScript], since 0.7.3) or held no
 * word. On 2026-10-17 the owner's
 * Malayalam note about a voice-notes app lost its points about notes and recordings that way
 * (until 0.7.2 such words were read as words about the text, [PromptEcho]); a recording with enough
 * words for notes should not end without one because one answer of a sampling model went astray.
 * The second call is the caller's own call again with the order to write what is said about the
 * subject ([NotesTopic.prompt] retry), on the model in memory: no load, no other model, and the
 * same path as the first (the engine's lines, the learning of large contexts). Its answer is kept
 * when it has a point; the two answers are never mixed. When neither has one, the call gives no
 * note, not even one of a title alone. A translation is never asked again: no answer of it is
 * filtered.
 *
 * Once [MAX_EMPTY_IN_A_ROW] calls of the run in a row gave no point in either answer, a call that
 * leaves none is not asked again: on a long transcript the wrong speech model made junk of, every
 * part would be asked twice, about doubling the step on the 1B (the review of 0.7.2). A call with a
 * point starts the count again. The final note is one call, so this holds only for a pass.
 *
 * A note with a point that mostly repeats a note of the recording before it ([NoteRepeats.repeatsNote]:
 * one of [earlier], or one this run kept) loses what that note says and keeps what is new, and with
 * nothing new left is none and is not asked again: the model said something, the same as before,
 * and the note before it stands ([EarlierNotes]; with [wholeRepeats] it goes whole). The merge of a
 * pass says again what its parts said, which is what it is for, so it is never held to them (since
 * 0.7.3).
 *
 * Every answer goes to [answerLog] as the model gave it, under its call's tag and its number
 * (`notes-final-0`, `notes-part2-1`).
 *
 * @param earlier The recording's notes from before the run, which stay: the live ones, for the last
 *   note after Stop; none for a pass, whose notes replace all the recording has
 * @param wholeRepeats Whether a note that mostly repeats an earlier one goes whole, new pieces and
 *   all: for a pass of a recording the model can't read ([EarlierNotes])
 */
internal class NotesAnswers(
    private val sessionId: String,
    private val notesLog: NotesLog,
    private val answerLog: AnswerLog,
    earlier: List<LlmInsight> = emptyList(),
    wholeRepeats: Boolean = false
) {
    /** One answer of the model ([answer], as it gave it) to a call whose pieces are held to [checks]. */
    class Asked(val answer: String, val checks: AnswerChecks)

    private var echoes = 0
    private var genericTitles = 0
    private var emptyLines = 0
    private var ruleEchoes = 0
    private var otherScript = 0
    private var repeatedLines = 0

    // The notes a note of the run must not mostly repeat: those from before, then the run's own.
    private val notes = EarlierNotes(earlier, whole = wholeRepeats)

    // The calls of the run in a row whose two answers left no point.
    private var emptyInARow = 0

    /**
     * The note of one call, [part] of the run, tagged [call] for [answerLog]: [ask] asks the model
     * the first time with retry false, and once more with retry true when that answer leaves no
     * point, unless [MAX_EMPTY_IN_A_ROW] calls in a row before it gave none twice. Null when no
     * answer does (a translation: when it is blank), and for a note that mostly repeats an earlier
     * one ([NotesAnswers]' description). Whatever [ask] throws, a failure of the model, is passed
     * on, and it is not asked again.
     */
    suspend fun of(
        part: NotesLog.Part,
        call: String,
        mode: RecordingMode,
        keys: InsightFormat.Keys,
        ask: suspend (retry: Boolean) -> Asked
    ): InsightOutputParser.ParsedInsight? {
        val first = read(ask(false), AnswerLog.tag(call, 0), mode, keys)
        if (first.parsed?.hasPoint == true) {
            emptyInARow = 0
            return unlessRepeat(part, mode, first.parsed)
        }
        if (mode == RecordingMode.REAL_TIME_TRANSLATION) return null
        if (emptyInARow >= MAX_EMPTY_IN_A_ROW) {
            notesLog.log(NotesLog.NoSecondTry(sessionId, part, first.why, emptyInARow))
            return null
        }
        notesLog.log(NotesLog.NoPoint(sessionId, part, first.why))
        val second = read(ask(true), AnswerLog.tag(call, 1), mode, keys)
        if (second.parsed?.hasPoint == true) {
            emptyInARow = 0
            notesLog.log(NotesLog.SecondKept(sessionId, part, second.parsed.keptLines))
            return unlessRepeat(part, mode, second.parsed)
        }
        emptyInARow++
        notesLog.log(NotesLog.NoNote(sessionId, part))
        return null
    }

    /**
     * [parsed], the note of [part] in [mode], as [EarlierNotes] keeps it after the notes from
     * before the run and those the run kept: whole, without what an earlier note says, or none.
     * The merge is never held to them, nor a translation, of which nothing is filtered.
     */
    private fun unlessRepeat(part: NotesLog.Part, mode: RecordingMode, parsed: InsightOutputParser.ParsedInsight): InsightOutputParser.ParsedInsight? {
        if (part == NotesLog.Part.Merge || mode == RecordingMode.REAL_TIME_TRANSLATION) return parsed
        return notes.keep(parsed)
    }

    /** Tells [echoLog] and [notesLog] how many lines the run's answers lost, and why; nothing when none. */
    fun logDrops(echoLog: PromptEchoLog) {
        if (echoes > 0) echoLog.dropped(sessionId, echoes, PromptEchoLog.What.NOTE_LINES)
        if (genericTitles > 0) notesLog.log(NotesLog.GenericTitles(sessionId, genericTitles))
        if (emptyLines > 0) notesLog.log(NotesLog.EmptyLines(sessionId, emptyLines))
        if (ruleEchoes > 0) notesLog.log(NotesLog.Dropped(sessionId, ruleEchoes, NotesLog.Piece.NOTE_LINES, AnswerDrop.RULES))
        if (otherScript > 0) notesLog.log(NotesLog.Dropped(sessionId, otherScript, NotesLog.Piece.NOTE_LINES, AnswerDrop.OTHER_SCRIPT))
        if (repeatedLines > 0) notesLog.log(NotesLog.RepeatedLines(sessionId, repeatedLines))
        if (notes.repeatedLines > 0) notesLog.log(NotesLog.RepeatedNoteLines(sessionId, notes.repeatedLines))
        if (notes.repeatedNotes > 0) notesLog.log(NotesLog.RepeatedNotes(sessionId, notes.repeatedNotes))
    }

    /** An answer read: its note, null for a blank one, and why it has no point if it has none. */
    private class Read(val parsed: InsightOutputParser.ParsedInsight?) {
        val why: NotesLog.Why
            get() = when {
                parsed == null -> NotesLog.Why.BLANK
                parsed.droppedLines > 0 -> NotesLog.Why.ALL_DROPPED
                else -> NotesLog.Why.NONE_IN_FULL
            }
    }

    private fun read(asked: Asked, tag: String, mode: RecordingMode, keys: InsightFormat.Keys): Read {
        answerLog.answer(sessionId, tag, asked.answer)
        if (asked.answer.isBlank()) return Read(null)
        val parsed = InsightOutputParser.parse(asked.answer, mode, keys, asked.checks)
        echoes += parsed.echoes
        genericTitles += parsed.genericTitles
        emptyLines += parsed.emptyLines
        ruleEchoes += parsed.ruleEchoes
        otherScript += parsed.otherScript
        repeatedLines += parsed.repeatedLines
        return Read(parsed)
    }

    companion object {
        /** After this many calls in a row with no point in either answer, a call without one is not asked again. */
        const val MAX_EMPTY_IN_A_ROW = 2
    }
}
