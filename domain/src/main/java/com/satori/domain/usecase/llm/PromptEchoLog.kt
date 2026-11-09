package com.satori.domain.usecase.llm

/**
 * Where the field log hears how many pieces of the model's answers were left out as echoes of
 * their prompt's instructions ([PromptEcho]): the points, titles and action items of a note, a
 * recording's name. Counts only, never a text: an answer holds what was said. The to-dos and ideas
 * left out so are counted with the others the search drops
 * ([com.satori.domain.usecase.todo.FoundTodosLog]).
 */
fun interface PromptEchoLog {

    /** What was left out. */
    enum class What(val label: String) {
        /** Points, titles and action items of a note: a live one, the last one, a part's or the merge's. */
        NOTE_LINES("note lines"),

        /** A recording's name, which the next title or the default then stands in for. */
        NAMES("names")
    }

    /** [count] pieces of [what] of the recording [sessionId] repeated their prompt and were left out. */
    fun dropped(sessionId: String, count: Int, what: What)

    companion object {
        /** Logs nothing. */
        val NONE = PromptEchoLog { _, _, _ -> }

        /** The field log's line: "Dropped 2 note lines that repeat their prompt in <id>". */
        fun line(sessionId: String, count: Int, what: What): String = "Dropped $count ${what.label} that repeat their prompt in $sessionId"
    }
}
