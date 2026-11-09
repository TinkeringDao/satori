package com.satori.domain.usecase.finish

import com.satori.domain.model.LlmInsight
import com.satori.domain.model.TranscriptionSegment

/**
 * What the notes of a recording don't cover yet: its tail, which the Notes step writes the last
 * note from ([FinishRecordingUseCase]).
 *
 * It is read from what is stored, not from what a recorder held in memory at Stop, so the step
 * can run in the background, and again after a process death: each live note cites the lines it
 * was written from ([LlmInsight.sourceSegmentIds]), the final note cites the tail it covers, and
 * a line no note cites is one no note has read. A live note that was being written as the
 * recording stopped is dropped, and its lines stay in the tail.
 */
object RecordingTail {

    /**
     * The complete lines of [segments] that no note in [insights] cites, in spoken order (by
     * timestamp; lines with the same one keep the order they came in).
     */
    fun of(segments: List<TranscriptionSegment>, insights: List<LlmInsight>): List<TranscriptionSegment> {
        val cited = insights.flatMapTo(HashSet()) { it.sourceSegmentIds }
        return segments.filter { it.isComplete && it.id !in cited }.sortedBy { it.timestamp }
    }
}
