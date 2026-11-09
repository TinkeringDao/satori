package com.satori.domain.usecase.mindmap

import com.satori.domain.usecase.llm.AnswerDrop

/**
 * Where the field log hears what the mind map's answers lost before anything was stored, since
 * 0.7.3: the topics and points of a recording ([MapRecordingUseCase]) and the sentences of a theme's
 * summary ([RefreshThemeSummariesUseCase]) left out by [com.satori.domain.usecase.llm.AnswerChecks].
 * Counts and ids only, never a text. The lines are [line]'s, under SatoriNotes; docs/FIELD_LOG.md
 * lists them.
 */
fun interface MindMapLog {

    /** What was left out, and what the id of [dropped] is the id of. */
    enum class What(val label: String, val of: String) {
        /** Topics of a recording, with their points: their label was left out. */
        TOPICS("map topics", ""),

        /** Points of a recording's topics. */
        POINTS("map points", ""),

        /** Sentences of a theme's summary. */
        SUMMARY_SENTENCES("summary sentences", "theme ")
    }

    /** [count] pieces of [what] of [id] (a recording's, or a theme's) were left out, for [why]. */
    fun dropped(id: String, count: Int, what: What, why: AnswerDrop)

    companion object {
        /** Logs nothing. */
        val NONE = MindMapLog { _, _, _, _ -> }

        /** The field log's line: "Dropped 2 map points that repeat the rules of their prompt in <id>". */
        fun line(id: String, count: Int, what: What, why: AnswerDrop): String = "Dropped $count ${what.label} ${why.label} in ${what.of}$id"
    }
}
