package com.satori.domain.usecase.stt

import com.satori.domain.model.AudioSpan

/**
 * Finds again, in a new transcript, the lines a note, a to-do or a mind-map link came from (its
 * source segment ids), by when they were said. A note's "Show what was said" reads them, a
 * recording's to-dos are listed in the order their lines were said, and the map opens a recording
 * at a topic's line, so all three would break when Transcribe again replaces every line with one
 * of a new id.
 *
 * A new line counts when its middle falls in the time the old lines took: new lines cut at other
 * places than the old ones still go to one note, not to two. If no middle falls in it (old lines
 * shorter than the new line that holds them), a new line that overlaps the time counts.
 */
internal object SourceLines {

    /**
     * @param sources The old line ids
     * @param places Where each old line was said
     * @param newLines The new lines with where each was said, in spoken order
     * @return The new line ids in spoken order, or null to keep [sources] as they are: none of
     *   them has a place, or no new line was said in their time. Never empty, since a note without
     *   sources would count as the recording's summary.
     */
    fun find(sources: List<String>, places: Map<String, AudioSpan>, newLines: List<Pair<String, AudioSpan>>): List<String>? {
        val spans = sources.mapNotNull { places[it] }
        if (spans.isEmpty()) return null
        val from = spans.minOf { it.startMs }
        val to = spans.maxOf { it.endMs }
        val inside = newLines.filter { (_, span) -> (span.startMs + span.endMs) / 2 in from..to }
        val chosen = inside.ifEmpty { newLines.filter { (_, span) -> span.startMs < to && span.endMs > from } }
        return chosen.map { it.first }.ifEmpty { null }
    }
}
