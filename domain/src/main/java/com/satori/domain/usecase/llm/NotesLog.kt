package com.satori.domain.usecase.llm

/**
 * Where the field log hears what became of a recording's notes and name besides the echoes
 * ([PromptEchoLog]): the pieces of an answer left out and why, a call asked again because its
 * answer left nothing (or not asked again), which answer was kept, and where the name came from,
 * or that the default name shows or the old one stays. Counts, ids and exception classes only,
 * never a text: an answer holds what was said.
 * The lines are [line]'s, under SatoriNotes; docs/FIELD_LOG.md lists them.
 */
fun interface NotesLog {

    /** Something that became of a recording's notes or name. */
    sealed interface Event {
        /** The recording. */
        val sessionId: String
    }

    /** [count] note titles named only the kind of text ([SessionTitles.Verdict.Generic], [SessionTitles.Verdict.KindAlone]). */
    data class GenericTitles(override val sessionId: String, val count: Int) : Event

    /** [count] note lines (titles, points, action items) held no word: blank, or the prompt's "..." copied. */
    data class EmptyLines(override val sessionId: String, val count: Int) : Event

    /**
     * [count] pieces of [what] of the recording were left out for [why] (0.7.3). Those that repeat
     * the instruction lines of their user message are [PromptEchoLog]'s, as since 0.7.1.
     */
    data class Dropped(override val sessionId: String, val count: Int, val what: Piece, val why: AnswerDrop) : Event

    /** [count] points and action items said nothing an earlier one of their note doesn't ([NoteRepeats]), since 0.7.3. */
    data class RepeatedLines(override val sessionId: String, val count: Int) : Event

    /**
     * [count] notes mostly repeated an earlier note of the recording ([NoteRepeats.repeatsNote]),
     * since 0.7.3, and had nothing new left ([EarlierNotes]): none of them is kept.
     */
    data class RepeatedNotes(override val sessionId: String, val count: Int) : Event

    /**
     * [count] titles, points and action items of notes that mostly repeated an earlier note of the
     * recording said what it says, and went; the rest of those notes is kept (since the review of
     * 0.7.3, [EarlierNotes]).
     */
    data class RepeatedNoteLines(override val sessionId: String, val count: Int) : Event

    /**
     * A notes call of [part] left no point in its answer ([InsightOutputParser.ParsedInsight.hasPoint]),
     * for the reason [why], and is asked once more.
     */
    data class NoPoint(override val sessionId: String, val part: Part, val why: Why) : Event

    /** A call of [part] asked twice: the second answer is kept, with [lines] lines ([InsightOutputParser.ParsedInsight.keptLines]). */
    data class SecondKept(override val sessionId: String, val part: Part, val lines: Int) : Event

    /** Neither answer of a call of [part] left a point: it gives no note. */
    data class NoNote(override val sessionId: String, val part: Part) : Event

    /** [count] names named only the kind of text ([SessionTitles.Verdict.Generic], [SessionTitles.Verdict.KindAlone]). */
    data class GenericNames(override val sessionId: String, val count: Int) : Event

    /** [count] names were empty: a blank answer, a label alone, JSON without a title ([SessionTitles.Verdict.Empty]). */
    data class EmptyNames(override val sessionId: String, val count: Int) : Event

    /** The first name answer gave no name: the model is asked once more, from the transcript. */
    data class NameAgain(override val sessionId: String) : Event

    /** The recording was named, from [source], after [answers] answers of the model. */
    data class Named(override val sessionId: String, val source: NameSource, val answers: Int) : Event

    /**
     * No name after [answers] answers and no note title to stand in: the screens show the default
     * name, or with [oldNameStays] the recording keeps the name its text made again was to replace
     * (Make text again).
     */
    data class NoName(override val sessionId: String, val answers: Int, val oldNameStays: Boolean = false) : Event

    /**
     * The model failed ([cause], its exception's class) and no note title stood in: the default
     * name shows, or the old name stays ([oldNameStays], as for [NoName]).
     */
    data class NameFailed(override val sessionId: String, val cause: String, val oldNameStays: Boolean = false) : Event

    /**
     * A call of [part] left no point ([why]), and is not asked again: [failed] calls of the pass in
     * a row gave no point in either answer, so the model and the transcript won't do better
     * ([NotesAnswers]). It gives no note.
     */
    data class NoSecondTry(override val sessionId: String, val part: Part, val why: Why, val failed: Int) : Event

    /** Which call of the notes. */
    sealed interface Part {
        /** The last note after Stop, on the tail ([GenerateFinalInsightUseCase]). */
        data object Final : Part

        /** Part [index] (from 1) of [of] of the whole pass ([GenerateBatchInsightUseCase]); [of] is 1 for a pass in one call. */
        data class Pass(val index: Int, val of: Int) : Part

        /** The merge of the pass's parts. */
        data object Merge : Part
    }

    /** Why an answer left no point. */
    enum class Why(val label: String) {
        /** The answer was blank. */
        BLANK("it was blank"),

        /** No point in full: cut off by the token budget before its first one ended, or none written. */
        NONE_IN_FULL("no point in full"),

        /** Its points were all left out: they repeated the prompt or held no word. */
        ALL_DROPPED("every point left out")
    }

    /** What [Dropped] counts, with the words the field log says it in. */
    enum class Piece(val label: String) {
        /** Titles, points, summaries and action items of notes: a live note's, the last note's, a part's or the merge's. */
        NOTE_LINES("note lines"),

        /** A recording's name, which the next answer, a note title or the default then stands in for. */
        NAMES("names"),

        /**
         * Titles of the recording's notes, stored by an earlier version, that can't stand in for
         * its name (since the review of 0.7.3); the notes keep them.
         */
        TITLES_FOR_THE_NAME("note titles for the name")
    }

    /** Where a name came from. */
    enum class NameSource(val label: String) {
        /** The model's answer from the recording's notes. */
        NOTES("from its notes"),

        /** The model's answer from the transcript. */
        TRANSCRIPT("from its transcript"),

        /** The first note title that names something. */
        NOTE_TITLE("the notes' title")
    }

    fun log(event: Event)

    companion object {
        /** Logs nothing. */
        val NONE = NotesLog { }

        /** The field log's line for [event]. */
        fun line(event: Event): String = when (event) {
            is GenericTitles -> "Dropped ${event.count} note titles that name only the kind of text in ${event.sessionId}"
            is EmptyLines -> "Dropped ${event.count} note lines with no words in ${event.sessionId}"
            is Dropped -> "Dropped ${event.count} ${event.what.label} ${event.why.label} in ${event.sessionId}"
            is RepeatedLines -> "Dropped ${event.count} note lines that repeat an earlier line of their note in ${event.sessionId}"
            is RepeatedNotes -> "Dropped ${event.count} notes that repeat an earlier note in ${event.sessionId}"
            is RepeatedNoteLines -> "Dropped ${event.count} note lines that repeat an earlier note in ${event.sessionId}"
            is NoPoint -> "Notes of ${event.sessionId}${where(event.part)}: no point left in the answer (${event.why.label}), asking once more"
            is SecondKept -> "Notes of ${event.sessionId}${where(event.part)}: the second answer kept, ${event.lines} ${lines(event.lines)}"
            is NoNote -> "Notes of ${event.sessionId}${where(event.part)}: no note, no point in 2 answers"
            is NoSecondTry ->
                "Notes of ${event.sessionId}${where(event.part)}: no note, no point left in the answer (${event.why.label}), " +
                    "and no second try after ${event.failed} calls in a row gave none in 2 answers"
            is GenericNames -> "Dropped ${event.count} names that name only the kind of text in ${event.sessionId}"
            is EmptyNames -> "Dropped ${event.count} names that are empty in ${event.sessionId}"
            is NameAgain -> "Name of ${event.sessionId}: asking once more, from its transcript"
            is Named -> "Name of ${event.sessionId}: ${event.source.label}, after ${event.answers} ${answers(event.answers)}"
            is NoName -> "Name of ${event.sessionId}: none after ${event.answers} ${answers(event.answers)}, ${noName(event.oldNameStays)}"
            is NameFailed -> "Name of ${event.sessionId}: none, the model failed (${event.cause}); ${noName(event.oldNameStays)}"
        }

        private fun noName(oldNameStays: Boolean): String = if (oldNameStays) "the old name stays" else "the default name shows"

        private fun where(part: Part): String = when (part) {
            Part.Final -> ""
            is Part.Pass -> if (part.of > 1) ", part ${part.index} of ${part.of}" else ""
            Part.Merge -> ", the merge"
        }

        private fun answers(count: Int): String = if (count == 1) "answer" else "answers"

        private fun lines(count: Int): String = if (count == 1) "line" else "lines"
    }
}
