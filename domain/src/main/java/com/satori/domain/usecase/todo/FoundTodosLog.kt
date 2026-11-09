package com.satori.domain.usecase.todo

/**
 * Where the field log hears of the found to-dos and ideas the search left out before keeping
 * anything: how many, and why ([Why]). Counts only, never a text: a found item holds what was said.
 * Since 0.7.2 every item the search drops is counted under one reason: on 2026-10-16 an answer of
 * four items whose text was only a time phrase ("tomorrow at 10") went without a line.
 */
fun interface FoundTodosLog {

    /** Why found items were dropped. */
    enum class Why(val label: String) {
        /** Their words aren't in the segments they cite: copied from the prompt or another recording ([TodoGrounding]). */
        NOT_IN_SEGMENT("not in their segment"),

        /** A heading of a list said aloud ("Second, the budget") or a greeting ("See you"), no to-do. */
        HEADING_OR_GREETING("a heading or a greeting"),

        /**
         * Their text is mostly in another script than the Latin one
         * ([com.satori.domain.usecase.llm.WrongScript]): a line of a Malayalam transcript copied as
         * it was said, or since 0.7.3 any other script, Telugu, Bengali, Hebrew, Cyrillic, CJK and
         * the rest. The app writes to-dos and ideas in English or German only, a Malayalam
         * recording's in English (the owner's decision, 2026-10-14). Until 0.7.3 "written in
         * Malayalam", for Malayalam script alone.
         */
        WRITTEN_IN_ANOTHER_SCRIPT("written in another script"),

        /**
         * Their words repeat the call's line of instructions
         * ([com.satori.domain.usecase.llm.PromptEcho]), or hold a key of the answer
         * ([TodoGate.namesField]): on 2026-10-16 the 1B stored the order on the "said" field as an
         * open to-do.
         */
        ECHOES_PROMPT("that repeat their prompt"),

        /**
         * They give back the rules of the to-do prompt or of its header's own words
         * ([com.satori.domain.usecase.llm.PromptRules], since 0.7.3), and not as words said in
         * their part.
         */
        ECHOES_RULES("that repeat the rules of their prompt"),

        /** They say what the text is about instead of naming a thing to do ([TodoGate.describesText]). */
        DESCRIBE_THE_TEXT("that describe the text"),

        /** To-dos of a Malayalam line whose English names no action and no commitment ([TodoGate.namesAction]). */
        NO_ACTION("that name no action"),

        /**
         * To-dos the small live model read from a Malayalam line where nothing is asked for or
         * promised ([MalayalamTodoCues]).
         */
        NO_MALAYALAM_CUE("without a request or commitment in their Malayalam"),

        /**
         * Ideas the small live model read from a Malayalam line where nothing puts an idea forward
         * ([MalayalamTodoCues.holdsIdea]): on 2026-10-16 the 1B retold a reading as an idea.
         */
        NO_MALAYALAM_IDEA("without an idea put forward in their Malayalam"),

        /**
         * The same text as an item of the other kind, found before them or kept for the recording:
         * the same text is never both a to-do and an idea.
         */
        SAME_AS_ANOTHER("the same as another item"),

        /**
         * Their text is only the time it names ("tomorrow at 10", "the end of the month"): nothing
         * is left of it once its segments' time phrases are taken out. Since 0.7.2, also when the
         * model picked no time for it, which used to read as [NOT_IN_SEGMENT] or give a suggestion.
         */
        ONLY_A_TIME("that are only a time"),

        /** They cite no segment of the part they were read from. */
        NO_SEGMENT("that cite no segment"),

        /** They have no text, or nothing of one is left once its fillers and hesitations went. */
        NO_TEXT("without a text"),

        /** They ask a question ([TodoGate.DropReason.QUESTION]). */
        QUESTION("that ask a question"),

        /** Fewer than two words besides fillers, and no appointment ([TodoGate.DropReason.TOO_SHORT]). */
        TOO_SHORT("too short to name a thing to do"),

        /** They end on a word that needs more after it ([TodoGate.DropReason.CUT_OFF]): "It had been". */
        CUT_OFF("cut off mid-phrase"),

        /** A copy of their sentence without a cue or a bare noun ([TodoGate.DropReason.UNCUED_COPY]). */
        UNCUED_COPY("that copy their sentence without a cue"),

        /** A repeat of a to-do or idea known already: the recording's own, or another one still to do. */
        ALREADY_NOTED("already noted"),

        /** Past the [TodoPrompt.MAX_ITEMS] items an answer is read for. */
        OVER_LIMIT("over the answer's limit"),

        /** Appointments the spotter found whose time was over when it was said, so they have none. */
        NO_USABLE_TIME("that are appointments without a usable time")
    }

    /** [count] found items of the recording [sessionId] were dropped, for [why]. */
    fun dropped(sessionId: String, count: Int, why: Why)

    /**
     * Since 0.8.0: of the found items a search of the recording [sessionId] kept, [kept] cite lines
     * read into English, and [wouldDrop] of them are ones the gate of Malayalam lines (the action
     * the to-do names, and from the small model the cue in its Malayalam, [MalayalamTodoCues]),
     * which is off for read lines (design D9), would have dropped. Counts only, for V4 to weigh
     * whether that gate drops none the owner marks real. The default says nothing.
     */
    fun ungated(sessionId: String, kept: Int, wouldDrop: Int) {}

    companion object {
        /** Logs nothing. */
        val NONE = FoundTodosLog { _, _, _ -> }

        /** The field log's line: "Dropped 4 found items not in their segment in <id>". */
        fun line(sessionId: String, count: Int, why: Why): String = "Dropped $count found items ${why.label} in $sessionId"

        /**
         * The field log's line of [ungated]: "Kept 3 found items of read lines in <id>; the gate of
         * Malayalam lines would have dropped 1 of them".
         */
        fun ungatedLine(sessionId: String, kept: Int, wouldDrop: Int): String =
            "Kept $kept found items of read lines in $sessionId; the gate of Malayalam lines would have dropped $wouldDrop of them"
    }
}
