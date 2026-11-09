package com.satori.domain.usecase.llm

import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSession

/**
 * A recording's notes in display order, with its overall summary pinned on top. The details
 * page and the Markdown export both group them this way, so both show the same thing.
 *
 * @property pinned The summary, if the recording has one.
 * @property rest The other notes, oldest first.
 */
class InsightGroups(val pinned: List<LlmInsight>, val rest: List<LlmInsight>) {
    val all: List<LlmInsight> = pinned + rest

    private val summaryItems: List<String> = pinned.flatMap { NoteText.actionItems(it.tasks) }

    /**
     * The action items to show under [insight]. Under a part of a long recording, those the
     * summary lists too are left out: the page says each once.
     */
    fun actionItems(insight: LlmInsight): List<String> {
        val items = NoteText.actionItems(insight.tasks)
        return if (insight in pinned) items else NoteText.withoutRepeats(items, summaryItems)
    }
}

/**
 * Sorts the notes of [session] and pins its overall summary: the note without source lines of
 * a recording that was written in one pass over the whole transcript. Live notes are snapshots
 * and have no summary, and a translation is running text all through.
 */
fun groupInsights(insights: List<LlmInsight>, session: TranscriptionSession): InsightGroups {
    val sorted = insights.sortedBy { it.timestamp }
    if (session.insightStrategy != InsightStrategy.END_OF_SESSION || session.mode == RecordingMode.REAL_TIME_TRANSLATION) {
        return InsightGroups(emptyList(), sorted)
    }
    val (pinned, rest) = sorted.partition { it.sourceSegmentIds.isEmpty() }
    return InsightGroups(pinned, rest)
}
